package com.sedmelluq.discord.lavaplayer.source.twitch;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.*;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpClientTools;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpConfigurable;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterfaceManager;
import com.sedmelluq.discord.lavaplayer.track.AudioItem;
import com.sedmelluq.discord.lavaplayer.track.AudioReference;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import org.apache.http.Header;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.net.URI;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.sedmelluq.discord.lavaplayer.source.twitch.TwitchConstants.*;
import static com.sedmelluq.discord.lavaplayer.tools.FriendlyException.Severity.SUSPICIOUS;

/**
 * Audio source manager which detects Twitch tracks by URL.
 */
public class TwitchStreamAudioSourceManager implements AudioSourceManager, HttpConfigurable {
  private static final Logger log = LoggerFactory.getLogger(TwitchStreamAudioSourceManager.class);

  private static final String STREAM_NAME_REGEX = "^https://(?:www\\.|go\\.|m\\.)?twitch.tv/([^/]+)$";
  private static final Pattern streamNameRegex = Pattern.compile(STREAM_NAME_REGEX);

  private final HttpInterfaceManager httpInterfaceManager;
  private String twitchClientId;
  private String twitchDeviceId;

  /**
   * Create an instance.
   */
  public TwitchStreamAudioSourceManager() {
    httpInterfaceManager = HttpClientTools.createDefaultThreadLocalManager();

    try {
      initRequestHeaders();
    } catch (FriendlyException e) {
      log.warn("Failed to fetch Twitch source keys. " +
          "Key fetching will be retried upon loading a Twitch track. " +
          "This warning can be ignored unless the issue persists.", e);
    }
  }

  public String getClientId() {
    return twitchClientId;
  }

  public String getDeviceId() {
    return twitchDeviceId;
  }

  @Override
  public String getSourceName() {
    return "twitch";
  }

  @Override
  public AudioItem loadItem(AudioPlayerManager manager, AudioReference reference) {
    String streamName = getChannelIdentifierFromUrl(reference.identifier);

    if (streamName == null) {
      return null;
    }

    initRequestHeaders();

    JsonBrowser channelInfo = fetchStreamChannelInfo(streamName).get("data").get("user");

    if (channelInfo == null || channelInfo.get("stream").get("type").isNull()) {
      return AudioReference.NO_TRACK;
    } else {
      String title = channelInfo.get("lastBroadcast").get("title").text();

      return new TwitchStreamAudioTrack(new AudioTrackInfo(
          title,
          streamName,
          Units.DURATION_MS_UNKNOWN,
          reference.identifier,
          true,
          reference.identifier
      ), this);
    }
  }

  @Override
  public boolean isTrackEncodable(AudioTrack track) {
    return true;
  }

  @Override
  public void encodeTrack(AudioTrack track, DataOutput output) throws IOException {
    // Nothing special to do, URL (identifier) is enough
  }

  @Override
  public AudioTrack decodeTrack(AudioTrackInfo trackInfo, DataInput input) throws IOException {
    return new TwitchStreamAudioTrack(trackInfo, this);
  }

  /**
   * Extract channel identifier from a channel URL.
   * @param url Channel URL
   * @return Channel identifier (for API requests)
   */
  public static String getChannelIdentifierFromUrl(String url) {
    Matcher matcher = streamNameRegex.matcher(url);
    if (!matcher.matches()) {
      return null;
    }

    return matcher.group(1).toLowerCase(Locale.ROOT);
  }

  /**
   * @param url Request URL
   * @return Request with necessary headers attached.
   */
  public HttpUriRequest createGetRequest(String url) {
    return addClientHeaders(new HttpGet(url), twitchClientId, twitchDeviceId);
  }

  /**
   * @param url Request URL
   * @return Request with necessary headers attached.
   */
  public HttpUriRequest createGetRequest(URI url) {
    return addClientHeaders(new HttpGet(url), twitchClientId, twitchDeviceId);
  }

  /**
   * @return Get an HTTP interface for a playing track.
   */
  public HttpInterface getHttpInterface() {
    return httpInterfaceManager.getInterface();
  }

  @Override
  public void configureRequests(Function<RequestConfig, RequestConfig> configurator) {
    httpInterfaceManager.configureRequests(configurator);
  }

  @Override
  public void configureBuilder(Consumer<HttpClientBuilder> configurator) {
    httpInterfaceManager.configureBuilder(configurator);
  }

  private static HttpUriRequest addClientHeaders(HttpUriRequest request, String clientId, String deviceId) {
    request.setHeader("Client-ID", clientId);
    request.setHeader("X-Device-ID", deviceId);
    return request;
  }

  protected JsonBrowser fetchAccessToken(String name) {
    try (HttpInterface httpInterface = getHttpInterface()) {
      HttpPost post = new HttpPost(TWITCH_GRAPHQL_BASE_URL);
      addClientHeaders(post, twitchClientId, twitchDeviceId);
      post.setEntity(new StringEntity(String.format(ACCESS_TOKEN_PAYLOAD, name)));
      return HttpClientTools.fetchResponseAsJson(httpInterface, post);
    } catch (IOException e) {
      throw new FriendlyException("Loading Twitch channel access token failed.", SUSPICIOUS, e);
    }
  }

  private JsonBrowser fetchStreamChannelInfo(String channelId) {
    try (HttpInterface httpInterface = getHttpInterface()) {
      HttpPost post = new HttpPost(TWITCH_GRAPHQL_BASE_URL);
      addClientHeaders(post, twitchClientId, twitchDeviceId);
      post.setEntity(new StringEntity(String.format(METADATA_PAYLOAD, channelId)));
      return HttpClientTools.fetchResponseAsJson(httpInterface, post);
    } catch (IOException e) {
      throw new FriendlyException("Loading Twitch channel information failed.", SUSPICIOUS, e);
    }
  }

  private void initRequestHeaders() {
    if (twitchClientId != null) {
      return;
    }

    synchronized (this) {
      if (twitchClientId != null) {
        return;
      }

      try (HttpInterface httpInterface = getHttpInterface()) {
        HttpGet get = new HttpGet(TWITCH_URL);
        get.setHeader("Accept", "text/html");
        CloseableHttpResponse response = httpInterface.execute(get);
        HttpClientTools.assertSuccessWithContent(response, "twitch main page");

        String responseText = EntityUtils.toString(response.getEntity());
        twitchClientId = DataFormatTools.extractBetween(responseText, "clientId=\"", "\"");

        for (Header header : response.getAllHeaders()) {
          if (header.getName().contains("Set-Cookie") && header.getValue().contains("unique_id=")) {
            twitchDeviceId = DataFormatTools.extractBetween(header.toString(), "unique_id=", ";");
          }
        }
      } catch (IOException e) {
        throw new FriendlyException("Loading Twitch main page failed.", SUSPICIOUS, e);
      }
    }
  }

  @Override
  public void shutdown() {
    ExceptionTools.closeWithWarnings(httpInterfaceManager);
  }
}