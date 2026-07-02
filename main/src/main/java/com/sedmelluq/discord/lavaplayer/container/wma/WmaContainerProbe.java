package com.sedmelluq.discord.lavaplayer.container.wma;

import com.sedmelluq.discord.lavaplayer.container.MediaContainerDetectionResult;
import com.sedmelluq.discord.lavaplayer.container.MediaContainerHints;
import com.sedmelluq.discord.lavaplayer.container.MediaContainerProbe;
import com.sedmelluq.discord.lavaplayer.tools.io.SeekableInputStream;
import com.sedmelluq.discord.lavaplayer.track.AudioReference;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.info.AudioTrackInfoBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

import static com.sedmelluq.discord.lavaplayer.container.MediaContainerDetection.checkNextBytes;
import static com.sedmelluq.discord.lavaplayer.container.MediaContainerDetectionResult.supportedFormat;
import static com.sedmelluq.discord.lavaplayer.container.MediaContainerDetectionResult.unsupportedFormat;

/**
 * Container detection probe for WMA (ASF) format.
 */
public class WmaContainerProbe implements MediaContainerProbe {
  private static final Logger log = LoggerFactory.getLogger(WmaContainerProbe.class);

  @Override
  public String getName() {
    return "wma";
  }

  @Override
  public boolean matchesHints(MediaContainerHints hints) {
    return false;
  }

  @Override
  public MediaContainerDetectionResult probe(AudioReference reference, SeekableInputStream inputStream) throws IOException {
    if (!WmaAudioTrack.getEnableWmaDecoding()) {
      return null;
    }

    if (!checkNextBytes(inputStream, WmaFileLoader.ASF_GUID)) {
      return null;
    }

    log.debug("Track {} is a WMA (ASF) file.", reference.identifier);

    WmaFileLoader file = new WmaFileLoader(inputStream);

    WmaStreamInfo streamInfo;
    try {
      streamInfo = file.parseHeaders();
    } catch (IllegalStateException e) {
      return unsupportedFormat(this, e.getMessage());
    }

    AudioTrackInfo trackInfo = AudioTrackInfoBuilder.create(reference, inputStream)
        .setTitle(file.getTextMetadata("Title"))
        .setAuthor(file.getTextMetadata("Author"))
        .setLength(streamInfo.duration)
        .build();

    return supportedFormat(this, null, trackInfo);
  }

  @Override
  public AudioTrack createTrack(String parameters, AudioTrackInfo trackInfo, SeekableInputStream inputStream) {
    return new WmaAudioTrack(trackInfo, inputStream);
  }
}
