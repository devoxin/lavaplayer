package com.sedmelluq.discord.lavaplayer.source.nico;

import com.sedmelluq.discord.lavaplayer.tools.io.SeekableInputStream;
import com.sedmelluq.discord.lavaplayer.track.playback.AudioProcessingContext;

import java.io.IOException;
import java.util.function.Supplier;

public interface NicoSegmentDecoder extends AutoCloseable {
  void prepareStream(AudioProcessingContext context, boolean beginning) throws IOException;

  void resetStream() throws IOException;

  void playStream(
      AudioProcessingContext context,
      long startPosition,
      long desiredPosition
  ) throws InterruptedException, IOException;

  interface Factory {
    NicoSegmentDecoder create(Supplier<SeekableInputStream> nextStreamProvider);
  }
}
