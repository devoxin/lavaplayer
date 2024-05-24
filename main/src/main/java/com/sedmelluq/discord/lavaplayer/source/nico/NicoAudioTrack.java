package com.sedmelluq.discord.lavaplayer.source.nico;

import com.grack.nanojson.JsonWriter;
import com.sedmelluq.discord.lavaplayer.container.playlists.HlsStreamTrack;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.DataFormatTools;
import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpClientTools;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.DelegatedAudioTrack;
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.util.EntityUtils;
import org.jsoup.parser.Parser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Audio track that handles processing NicoNico tracks.
 */
public class NicoAudioTrack extends DelegatedAudioTrack {
  private static final Logger log = LoggerFactory.getLogger(NicoAudioTrack.class);

  private final NicoAudioSourceManager sourceManager;

  /**
   * @param trackInfo     Track info
   * @param sourceManager Source manager which was used to find this track
   */
  public NicoAudioTrack(AudioTrackInfo trackInfo, NicoAudioSourceManager sourceManager) {
    super(trackInfo);

    this.sourceManager = sourceManager;
  }

  @Override
  public void process(LocalAudioTrackExecutor localExecutor) throws Exception {
    try (HttpInterface httpInterface = sourceManager.getHttpInterface()) {
      String playbackUrl = loadPlaybackUrl(httpInterface);

      log.debug("Starting NicoNico track from URL: {}", playbackUrl);

      processDelegate(
          new HlsStreamTrack(trackInfo, playbackUrl, sourceManager.getHttpInterfaceManager(), false),
          localExecutor
      );
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
}
