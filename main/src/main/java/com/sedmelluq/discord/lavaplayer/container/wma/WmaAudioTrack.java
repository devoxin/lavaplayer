package com.sedmelluq.discord.lavaplayer.container.wma;

import com.sedmelluq.discord.lavaplayer.tools.io.SeekableInputStream;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.BaseAudioTrack;
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Audio track that handles a WMA (ASF) stream.
 */
public class WmaAudioTrack extends BaseAudioTrack {
  private static final Logger log = LoggerFactory.getLogger(WmaAudioTrack.class);

  private final SeekableInputStream inputStream;

  /**
   * @param trackInfo Track info
   * @param inputStream Input stream for the WMA file
   */
  public WmaAudioTrack(AudioTrackInfo trackInfo, SeekableInputStream inputStream) {
    super(trackInfo);

    this.inputStream = inputStream;
  }

  @Override
  public void process(LocalAudioTrackExecutor localExecutor) throws Exception {
    WmaFileLoader file = new WmaFileLoader(inputStream);
    WmaTrackProvider trackProvider = file.loadTrack(localExecutor.getProcessingContext());

    try {
      log.debug("Starting to play WMA track {}", getIdentifier());
      localExecutor.executeProcessingLoop(trackProvider::provideFrames, trackProvider::seekToTimecode);
    } finally {
      trackProvider.close();
    }
  }
}
