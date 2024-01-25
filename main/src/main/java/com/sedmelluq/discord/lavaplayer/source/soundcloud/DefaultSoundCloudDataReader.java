package com.sedmelluq.discord.lavaplayer.source.soundcloud;

import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class DefaultSoundCloudDataReader implements SoundCloudDataReader {
  private static final Logger log = LoggerFactory.getLogger(DefaultSoundCloudDataReader.class);

  @Override
  public JsonBrowser findTrackData(JsonBrowser rootData) {
    return findEntryOfKind(rootData, "track");
  }

  @Override
  public String readTrackId(JsonBrowser trackData) {
    return trackData.get("id").safeText();
  }

  @Override
  public boolean isTrackBlocked(JsonBrowser trackData) {
    return "BLOCK".equals(trackData.get("policy").safeText());
  }

  @Override
  public AudioTrackInfo readTrackInfo(JsonBrowser trackData, String identifier) {
    boolean hasSnippedStreams = trackData.get("media").get("transcodings").values().stream()
        .anyMatch(transcoding -> transcoding.get("snipped").asBoolean(false));

    return new SoundcloudAudioTrackInfo(
        trackData.get("title").safeText(),
        trackData.get("user").get("username").safeText(),
        trackData.get("full_duration").as(Integer.class),
        identifier,
        false,
        trackData.get("permalink_url").text(),
        trackData.get("monetization_model").text(),
        hasSnippedStreams
    );
  }

  @Override
  public List<SoundCloudTrackFormat> readTrackFormats(JsonBrowser trackData) {
    List<SoundCloudTrackFormat> formats = new ArrayList<>();
    String trackId = readTrackId(trackData);

    if (trackId.isEmpty()) {
      log.warn("Track data {} missing track ID: {}.", trackId, trackData.format());
    }

    for (JsonBrowser transcoding : trackData.get("media").get("transcodings").values()) {
      // transcoding.get("snipped").asBoolean(false) -> true if track is a SoundCloud Go+ track.
      JsonBrowser format = transcoding.get("format");

      String protocol = format.get("protocol").safeText();
      String mimeType = format.get("mime_type").safeText();

      if (!protocol.isEmpty() && !mimeType.isEmpty()) {
        String lookupUrl = transcoding.get("url").safeText();

        if (!lookupUrl.isEmpty()) {
          formats.add(new DefaultSoundCloudTrackFormat(trackId, protocol, mimeType, lookupUrl));
        } else {
          log.warn("Transcoding of {} missing url: {}.", trackId, transcoding.format());
        }
      } else {
        log.warn("Transcoding of {} missing protocol/mimetype: {}.", trackId, transcoding.format());
      }
    }

    return formats;
  }

  @Override
  public JsonBrowser findPlaylistData(JsonBrowser rootData, String kind) {
    return findEntryOfKind(rootData, kind);
  }

  @Override
  public String readPlaylistName(JsonBrowser playlistData) {
    return playlistData.get("title").safeText();
  }

  @Override
  public String readPlaylistIdentifier(JsonBrowser playlistData) {
    return playlistData.get("permalink").safeText();
  }

  @Override
  public List<JsonBrowser> readPlaylistTracks(JsonBrowser playlistData) {
    return playlistData.get("tracks").values();
  }

  protected JsonBrowser findEntryOfKind(JsonBrowser data, String kind) {
    if (data.isMap() && kind.equals(data.get("kind").text())) {
      return data;
    }

    return null;
  }
}
