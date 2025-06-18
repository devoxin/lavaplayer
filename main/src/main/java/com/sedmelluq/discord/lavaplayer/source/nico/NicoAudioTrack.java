package com.sedmelluq.discord.lavaplayer.source.nico;

import com.grack.nanojson.JsonWriter;
import com.sedmelluq.discord.lavaplayer.container.playlists.HlsStreamSegment;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.DataFormatTools;
import com.sedmelluq.discord.lavaplayer.tools.ExceptionTools;
import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser;
import com.sedmelluq.discord.lavaplayer.tools.http.HttpStreamTools;
import com.sedmelluq.discord.lavaplayer.tools.io.*;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.DelegatedAudioTrack;
import com.sedmelluq.discord.lavaplayer.track.playback.AudioProcessingContext;
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor;
import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Hex;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.util.EntityUtils;
import org.jsoup.parser.Parser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.sedmelluq.discord.lavaplayer.tools.io.HttpClientTools.fetchResponseLines;

/**
 * Audio track that handles processing NicoNico tracks.
 */
public class NicoAudioTrack extends DelegatedAudioTrack {
  private static final Logger log = LoggerFactory.getLogger(NicoAudioTrack.class);

  private static final long SEGMENT_UPDATE_INTERVAL = TimeUnit.MINUTES.toMillis(10);

  private final NicoAudioSourceManager sourceManager;
  private HttpInterface httpInterface;

  /**
   * @param trackInfo     Track info
   * @param sourceManager Source manager which was used to find this track
   */
  public NicoAudioTrack(AudioTrackInfo trackInfo, NicoAudioSourceManager sourceManager) {
    super(trackInfo);

    this.sourceManager = sourceManager;
    this.httpInterface = sourceManager.getHttpInterface();
  }

  @Override
  public void process(LocalAudioTrackExecutor localExecutor) throws Exception {
    log.debug("Starting NicoNico track {}", getIdentifier());

    try (HttpInterface httpI = sourceManager.getHttpInterface()) {
      httpInterface = httpI; // so the segment tracker can use it

      try (SegmentTracker segmentTracker = createSegmentTracker()) {
        segmentTracker.decoder.prepareStream(localExecutor.getProcessingContext(), true);

        localExecutor.executeProcessingLoop(() -> segmentTracker.decoder.playStream(
            localExecutor.getProcessingContext(),
            segmentTracker.streamStartPosition,
            segmentTracker.desiredPosition
        ), timecode -> segmentTracker.seekToTimecode(localExecutor.getProcessingContext(), timecode), true);
      }
    }
  }

  private JsonBrowser loadVideoApi(HttpInterface httpInterface) throws IOException {
    String apiUrl = "https://www.nicovideo.jp/api/watch/v3_guest/" + getIdentifier() + "?_frontendId=6&_frontendVersion=0&actionTrackId=AAAAAAAAAA_" + System.currentTimeMillis();

    try (CloseableHttpResponse response = httpInterface.execute(new HttpGet(apiUrl))) {
      HttpClientTools.assertSuccessWithContent(response, "api response");

      return JsonBrowser.parse(response.getEntity().getContent()).get("data");
    }
  }

  private JsonBrowser loadVideoMainPage(HttpInterface httpInterface) throws IOException {
    try (CloseableHttpResponse response = httpInterface.execute(new HttpGet(trackInfo.uri))) {
      HttpClientTools.assertSuccessWithContent(response, "video main page");

      String urlEncodedData = DataFormatTools.extractBetween(EntityUtils.toString(response.getEntity()), "data-api-data=\"", "\"");
      String watchData = Parser.unescapeEntities(urlEncodedData, false);

      return JsonBrowser.parse(watchData);
    }
  }

  private String loadDmsPlaybackUrl(HttpInterface httpInterface, JsonBrowser apiData) throws IOException {
    List<List<String>> audioFormatIds = apiData.get("media").get("domand").get("audios")
        .values()
        .stream()
        .filter(format -> format.get("isAvailable").asBoolean(true) && !format.get("id").isNull())
        .map(format -> format.get("id").text())
        .map(Collections::singletonList)
        .collect(Collectors.toList());

    String accessKey = apiData.get("media").get("domand").get("accessRightKey").text();
    String trackId = apiData.get("client").get("watchTrackId").text();

    return getHlsUrl(httpInterface, accessKey, trackId, audioFormatIds);
  }

  private String getHlsUrl(HttpInterface httpInterface,
                           String accessKey,
                           String trackId,
                           List<List<String>> requestedAudioFormats) throws IOException {
    // @formatter:off
    String json = JsonWriter.string()
        .object()
          .value("outputs", requestedAudioFormats)
        .end()
        .done();
    // @formatter:on

    HttpPost request = new HttpPost("https://nvapi.nicovideo.jp/v1/watch/" + getIdentifier() + "/access-rights/hls?actionTrackId=" + trackId);
    request.setEntity(new StringEntity(json, ContentType.APPLICATION_JSON));
    request.addHeader("x-access-right-key", accessKey);
    request.addHeader("x-frontend-id", "6");
    request.addHeader("x-frontend-version", "0");
    request.addHeader("x-request-with", "https://www.nicovideo.jp");

    try (CloseableHttpResponse httpResponse = httpInterface.execute(request)) {
      int statusCode = httpResponse.getStatusLine().getStatusCode();

      if (statusCode != 201) {
        throw new IOException("Invalid status code for retrieve hls formats: " + statusCode);
      }

      JsonBrowser browser = JsonBrowser.parse(httpResponse.getEntity().getContent());
      return browser.get("data").get("contentUrl").text();
    }
  }

  private String loadPlaybackUrl(HttpInterface httpInterface) throws IOException {
    JsonBrowser videoJson = null;

    try {
      videoJson = loadVideoApi(httpInterface);
    } catch (IOException e) {
      if (!"Invalid status code for api response: 400".equals(e.getMessage())) {
        throw e;
      }
    }

    if (videoJson == null || videoJson.isNull()) {
      log.warn("Couldn't retrieve NicoNico video details from API, falling back to HTML page...");
      videoJson = loadVideoMainPage(httpInterface);
    }

    if (videoJson == null || videoJson.isNull()) {
      throw new RuntimeException("Couldn't retrieve video details for " + getIdentifier());
    }

    return loadDmsPlaybackUrl(httpInterface, videoJson);
  }

  @Override
  protected AudioTrack makeShallowClone() {
    return new NicoAudioTrack(trackInfo, sourceManager);
  }

  @Override
  public AudioSourceManager getSourceManager() {
    return sourceManager;
  }

  private List<HlsStreamSegment> loadSegments() throws IOException {
    String playbackUrl = loadPlaybackUrl(httpInterface);
    String[] lines = fetchResponseLines(httpInterface, new HttpGet(playbackUrl), "fetch stream urls");

    String segmentPlaylistUrl = null;
    boolean hasStreamInf = false;

    for (String line : lines) {
      if (hasStreamInf) {
        segmentPlaylistUrl = line;
        break;
      }

      if (line.startsWith("#EXT-X-STREAM-INF")) {
        hasStreamInf = true;
      }
    }

    if (segmentPlaylistUrl == null) {
      throw new IllegalStateException("Missing stream URL from initial playback URL");
    }

    return NicoHlsUtils.parseFromUrl(httpInterface, segmentPlaylistUrl);
  }

  private SegmentTracker createSegmentTracker() throws IOException {
    List<HlsStreamSegment> initialSegments = loadSegments();
    SegmentTracker tracker;

    try {
      tracker = new SegmentTracker(initialSegments);
    } catch (Exception e) {
      throw ExceptionTools.toRuntimeException(e);
    }

    tracker.setupDecoder(NicoMpegSegmentDecoder::new);
    return tracker;
  }

  private class SegmentTracker implements AutoCloseable {
    private final List<HlsStreamSegment> segments;
    private long desiredPosition = 0;
    private long streamStartPosition = 0;
    private long lastUpdate;
    private NicoSegmentDecoder decoder;
    private int segmentIndex = 0;

    private boolean initialized = false;
    private byte[] initData;
    private Cipher cipher;

    private SegmentTracker(List<HlsStreamSegment> segments) throws NoSuchPaddingException, NoSuchAlgorithmException, DecoderException, InvalidAlgorithmParameterException, InvalidKeyException, IOException {
      for (HlsStreamSegment segment : segments) {
        if (segment instanceof HlsDataSegment) {
          HlsDataSegment dataSegment = (HlsDataSegment) segment;

          if (dataSegment.directiveName.equals("EXT-X-MAP")) {
            String initUri = dataSegment.directiveArguments.get("URI");
            initData = NicoHlsUtils.fetchResponseByteArray(httpInterface, new HttpGet(initUri), "fetch initial segment");
          } else if (dataSegment.directiveName.equals("EXT-X-KEY")) {
            String keyUri = dataSegment.directiveArguments.get("URI");
            String iv = dataSegment.directiveArguments.get("IV");

            byte[] cipherKey = NicoHlsUtils.fetchResponseByteArray(httpInterface, new HttpGet(keyUri), "fetch cipher key");
            cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            SecretKeySpec keySpec = new SecretKeySpec(cipherKey, "AES");
            IvParameterSpec ivSpec = new IvParameterSpec(Hex.decodeHex(iv.replace("0x", "")));
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec);
          }
        }
      }

      segments.removeIf(segment -> segment instanceof HlsDataSegment);

      this.segments = segments;
      this.lastUpdate = System.currentTimeMillis();
    }

    private void setupDecoder(NicoSegmentDecoder.Factory factory) {
      decoder = factory.create(this::createChainedStream);
    }

    private SeekableInputStream createChainedStream() {
      return new NonSeekableInputStream(new ChainedInputStream(this::getNextStream));
    }

    private void seekToTimecode(AudioProcessingContext context, long timecode) throws IOException {
      long segmentTimecode = 0;

      for (int i = 0; i < segments.size(); i++) {
        Long duration = segments.get(i).duration;

        if (duration == null) {
          break;
        }

        long nextTimecode = segmentTimecode + duration;

        if (timecode >= segmentTimecode && timecode < nextTimecode) {
          seekToSegment(context, i, timecode, segmentTimecode);
          return;
        }

        segmentTimecode = nextTimecode;
      }

      seekToEnd();
    }

    private void seekToSegment(AudioProcessingContext context, int index, long requestedTimecode, long segmentTimecode) throws IOException {
      decoder.resetStream();

      segmentIndex = index;
      desiredPosition = requestedTimecode;
      streamStartPosition = segmentTimecode;

      decoder.prepareStream(context, streamStartPosition == 0);
    }

    private void seekToEnd() throws IOException {
      decoder.resetStream();

      segmentIndex = segments.size();
    }

    private InputStream getNextStream() {
      boolean skipCipher = !initialized; // first segment isn't ciphered so needs explicit handling.

      if (!initialized) {
        initialized = true;

        if (initData != null) {
          return new ByteArrayInputStream(initData);
        }
      }

      HlsStreamSegment segment = getNextSegment();

      if (segment == null) {
        return null;
      }

      InputStream content = HttpStreamTools.streamContent(httpInterface, new HttpGet(segment.url));

      if (cipher != null && !skipCipher) {
        return new CipherInputStream(content, cipher);
      }

      return content;
    }

    private void updateSegmentList() {
      try {
        List<HlsStreamSegment> newSegments = loadSegments();
        newSegments.removeIf(segment -> segment instanceof HlsDataSegment);

        if (newSegments.size() != segments.size()) {
          log.error("For {}, received different number of segments on update, skipping.", trackInfo.identifier);
          return;
        }

        for (int i = 0; i < segments.size(); i++) {
          if (!Objects.equals(newSegments.get(i).duration, segments.get(i).duration)) {
            log.error("For {}, segment {} has different length than previously on update.", trackInfo.identifier, i);
            return;
          }
        }

        for (int i = 0; i < segments.size(); i++) {
          segments.set(i, newSegments.get(i));
        }
      } catch (Exception e) {
        log.error("For {}, failed to update segment list, skipping.", trackInfo.identifier, e);
      }
    }

    private void checkSegmentListUpdate() {
      long now = System.currentTimeMillis();
      long delta = now - lastUpdate;

      if (delta > SEGMENT_UPDATE_INTERVAL) {
        log.debug("For {}, {}ms has passed since last segment update, updating", trackInfo.identifier, delta);

        updateSegmentList();
        lastUpdate = now;
      }
    }

    private HlsStreamSegment getNextSegment() {
      int current = segmentIndex++;

      if (current < segments.size()) {
        checkSegmentListUpdate();
        return segments.get(current);
      } else {
        return null;
      }
    }

    @Override
    public void close() throws Exception {
//      decoder.resetStream();
      decoder.close();
    }
  }
}
