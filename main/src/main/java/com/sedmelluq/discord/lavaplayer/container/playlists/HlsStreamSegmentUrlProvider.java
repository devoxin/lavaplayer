package com.sedmelluq.discord.lavaplayer.container.playlists;

import com.sedmelluq.discord.lavaplayer.source.stream.M3uStreamSegmentUrlProvider;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpUriRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import static com.sedmelluq.discord.lavaplayer.tools.io.HttpClientTools.fetchResponseLines;

public class HlsStreamSegmentUrlProvider extends M3uStreamSegmentUrlProvider {
  private static final Logger log = LoggerFactory.getLogger(HlsStreamSegmentUrlProvider.class);

  private final String streamListUrl;
  private volatile String segmentPlaylistUrl;

  public HlsStreamSegmentUrlProvider(String streamListUrl, String segmentPlaylistUrl) {
    super(streamListUrl);
    this.streamListUrl = streamListUrl;
    this.segmentPlaylistUrl = segmentPlaylistUrl;
  }

  @Override
  protected String getQualityFromM3uDirective(ExtendedM3uParser.Line directiveLine) {
    return "default";
  }

  protected boolean isSegmentPlaylist(String[] lines) {
    return Arrays.stream(lines).noneMatch(line -> line.startsWith("#EXT-X-STREAM-INF"));
  }

  @Override
  protected String fetchSegmentPlaylistUrl(HttpInterface httpInterface) throws IOException {
    if (segmentPlaylistUrl != null) {
      return segmentPlaylistUrl;
    }

    HttpUriRequest request = new HttpGet(streamListUrl);

    String[] lines = fetchResponseLines(httpInterface, request, "HLS stream list");

    if (isSegmentPlaylist(lines)) {
      return (segmentPlaylistUrl = streamListUrl);
    }

    List<ChannelStreamInfo> streams = loadChannelStreamsList(lines);

    if (streams.isEmpty()) {
      throw new IllegalStateException("No streams listed in HLS stream list.");
    }

    ChannelStreamInfo stream = streams.get(0);

    log.debug("Chose stream {} with url {}", stream.quality, stream.url);
    segmentPlaylistUrl = stream.url;
    return segmentPlaylistUrl;
  }

  @Override
  protected HttpUriRequest createSegmentGetRequest(String url) {
    return new HttpGet(url);
  }

  public static String findHlsEntryUrl(String[] lines) {
    List<ChannelStreamInfo> streams = new HlsStreamSegmentUrlProvider(null, null)
        .loadChannelStreamsList(lines);

    return streams.isEmpty() ? null : streams.get(0).url;
  }
}
