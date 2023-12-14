package com.sedmelluq.discord.lavaplayer.source.nico;

import com.grack.nanojson.JsonWriter;
import com.sedmelluq.discord.lavaplayer.container.mpeg.MpegAudioTrack;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.DataFormatTools;
import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpClientTools;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.DelegatedAudioTrack;
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor;
import org.apache.http.HttpStatus;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.util.EntityUtils;
import org.jsoup.parser.Parser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Audio track that handles processing NicoNico tracks.
 */
public class NicoAudioTrack extends DelegatedAudioTrack {
  private static final Logger log = LoggerFactory.getLogger(NicoAudioTrack.class);
  private static String actionTrackId = "S1G2fKdzOl_1702504390263";

  private final NicoAudioSourceManager sourceManager;

  private String heartbeatUrl;
  private int heartbeatIntervalMs;
  private String initialHeartbeatPayload;

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

      try (HeartbeatingHttpStream stream = new HeartbeatingHttpStream(
          httpInterface,
          new URI(playbackUrl),
          null,
          heartbeatUrl,
          heartbeatIntervalMs,
          initialHeartbeatPayload
      )) {
        processDelegate(new MpegAudioTrack(trackInfo, stream), localExecutor);
      }
    }
  }

  private JsonBrowser loadVideoApi(HttpInterface httpInterface) throws IOException {
    String apiUrl = "https://www.nicovideo.jp/api/watch/v3_guest/" + getIdentifier() + "?_frontendId=6&_frontendVersion=0&actionTrackId=" + actionTrackId + "&i18nLanguage=en-us";

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

  private String loadPlaybackUrl(HttpInterface httpInterface) throws IOException {
    JsonBrowser videoJson = loadVideoApi(httpInterface);

    if (videoJson.isNull()) {
      log.warn("Couldn't retrieve NicoNico video details from API, falling back to HTML page...");
      videoJson = loadVideoMainPage(httpInterface);
    }

    if (!videoJson.isNull()) {
      // an "actionTrackId" is necessary to receive an API response.
      // We make sure this is kept up to date to prevent any issues with tracking IDs becoming invalid.
      String trackingId = videoJson.get("client").get("watchTrackId").text();

      if (trackingId != null) {
        actionTrackId = trackingId;
      }
    }

    String watchData = processJson(videoJson.get("media").get("delivery").get("movie").get("session"));

    HttpPost request = new HttpPost("https://api.dmc.nico/api/sessions?_format=json");
    request.addHeader("Host", "api.dmc.nico");
    request.addHeader("Connection", "keep-alive");
    request.addHeader("Content-Type", "application/json");
    request.addHeader("Origin", "https://www.nicovideo.jp");
    request.setEntity(new StringEntity(watchData));

    try (CloseableHttpResponse response = httpInterface.execute(request)) {
      int statusCode = response.getStatusLine().getStatusCode();

      if (statusCode != HttpStatus.SC_CREATED) {
        throw new IOException("Unexpected status code from playback parameters page: " + statusCode);
      }

      JsonBrowser info = JsonBrowser.parse(response.getEntity().getContent()).get("data");
      JsonBrowser session = info.get("session");

      heartbeatUrl = "https://api.dmc.nico/api/sessions/" + session.get("id").text() + "?_format=json&_method=PUT";
      heartbeatIntervalMs = session.get("keep_method").get("heartbeat").get("lifetime").asInt(120000) - 5000;
      initialHeartbeatPayload = info.format();

      return session.get("content_uri").text();
    }
  }

  private String processJson(JsonBrowser input) {
    if (input.isNull()) {
      throw new IllegalStateException("Invalid response received from NicoNico when loading video details");
    }

    List<String> videos = input.get("videos").values().stream()
        .map(JsonBrowser::text)
        .collect(Collectors.toList());

    List<String> audios = input.get("audios").values().stream()
        .map(JsonBrowser::text)
        .collect(Collectors.toList());

    JsonBrowser url = input.get("urls").index(0);
    boolean useWellKnownPort = url.get("isWellKnownPort").asBoolean(false);
    boolean useSsl = url.get("isSsl").asBoolean(false);

    return JsonWriter.string()
        .object()
          .object("session")
            .value("content_type", "movie")
            .value("timing_constraint", "unlimited")
            .value("recipe_id", input.get("recipeId").text())
            .value("content_id", input.get("contentId").text())
            .object("keep_method")
              .object("heartbeat")
                .value("lifetime", input.get("heartbeatLifetime").asLong(120000))
              .end()
            .end()
            .array("content_src_id_sets")
              .object()
                .array("content_src_ids")
                  .object()
                    .object("src_id_to_mux")
                      .array("video_src_ids", videos)
                      .array("audio_src_ids", audios)
                    .end()
                  .end()
                .end()
              .end()
            .end()
            .object("protocol")
              .value("name", "http")
              .object("parameters")
                .object("http_parameters")
                  .object("parameters")
                    .object("http_output_download_parameters")
                      .value("use_well_known_port", useWellKnownPort ? "yes" : "no")
                      .value("use_ssl", useSsl ? "yes" : "no")
                    .end()
                  .end()
                .end()
              .end()
            .end()
            .object("session_operation_auth")
              .object("session_operation_auth_by_signature")
                .value("token", input.get("token").text())
                .value("signature", input.get("signature").text())
              .end()
            .end()
            .object("content_auth")
              .value("auth_type", input.get("authTypes").get("http").text())
              .value("content_key_timeout", input.get("contentKeyTimeout").asLong(120000))
              .value("service_id", "nicovideo")
              .value("service_user_id", input.get("serviceUserId").text())
            .end()
            .object("client_info")
              .value("player_id", input.get("playerId").text())
            .end()
          .end()
        .end()
        .done();
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
