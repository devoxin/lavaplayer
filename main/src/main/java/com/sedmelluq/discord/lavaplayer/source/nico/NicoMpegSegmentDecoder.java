package com.sedmelluq.discord.lavaplayer.source.nico;

import com.sedmelluq.discord.lavaplayer.container.mpeg.*;
import com.sedmelluq.discord.lavaplayer.container.mpeg.reader.MpegFileTrackProvider;
import com.sedmelluq.discord.lavaplayer.tools.io.SeekableInputStream;
import com.sedmelluq.discord.lavaplayer.track.playback.AudioProcessingContext;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

public class NicoMpegSegmentDecoder implements NicoSegmentDecoder {
  private final Supplier<SeekableInputStream> nextStreamProvider;

  private MpegTrackConsumer consumer;
  private MpegFileTrackProvider reader;
  private SeekableInputStream lastStream;

  public NicoMpegSegmentDecoder(Supplier<SeekableInputStream> nextStreamProvider) {
    this.nextStreamProvider = nextStreamProvider;
  }

  private MpegTrackConsumer selectAudioTrack(List<MpegTrackInfo> tracks, AudioProcessingContext context) {
    for (MpegTrackInfo track : tracks) {
      if ("soun".equals(track.handler) && "mp4a".equals(track.codecName)) {
        return new MpegAacTrackConsumer(context, track);
      }
    }

    return null;
  }

  private SeekableInputStream getNextStream() throws IOException {
    if (lastStream != null) {
      lastStream.close();
    }

    lastStream = nextStreamProvider.get();
    return lastStream;
  }

  @Override
  public void prepareStream(AudioProcessingContext context, boolean beginning) throws IOException {
    if (beginning && consumer == null) {
      MpegFileLoader file = new MpegFileLoader(getNextStream());
      file.parseHeaders();

      consumer = Objects.requireNonNull(selectAudioTrack(file.getTrackList(), context));
      reader = file.loadReader(consumer);
    }
  }

  @Override
  public void resetStream() throws IOException {
    if (lastStream != null) {
      lastStream.close();
    }
  }

  @Override
  public void playStream(
      AudioProcessingContext context,
      long startPosition,
      long desiredPosition
  ) throws InterruptedException, IOException {
    consumer.seekPerformed(startPosition, desiredPosition);
    reader.provideFrames();
  }

  @Override
  public void close() {
    if (consumer != null) {
      consumer.close();
      consumer = null;
    }
  }
}
