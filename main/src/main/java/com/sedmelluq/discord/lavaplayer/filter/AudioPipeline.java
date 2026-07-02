package com.sedmelluq.discord.lavaplayer.filter;

import java.nio.ShortBuffer;
import java.util.List;

/**
 * Represents an audio pipeline (top-level audio filter chain).
 */
public class AudioPipeline extends CompositeAudioFilter {
  private final List<AudioFilter> filters;
  private final UniversalPcmAudioFilter first;
  private final PipelinePositionTracker positionTracker;
  private final int channelCount;

  /**
   * @param chain The top-level filter chain.
   * @param positionTracker Tracker to which consumed source audio is reported for position tracking.
   * @param channelCount Number of channels in the audio entering the pipeline (the source channel count).
   */
  public AudioPipeline(AudioFilterChain chain, PipelinePositionTracker positionTracker, int channelCount) {
    this.filters = chain.filters;
    this.first = chain.input;
    this.positionTracker = positionTracker;
    this.channelCount = channelCount;
  }

  @Override
  public void process(float[][] input, int offset, int length) throws InterruptedException {
    first.process(input, offset, length);
    positionTracker.addInputSamples(length);
  }

  @Override
  public void process(short[] input, int offset, int length) throws InterruptedException {
    first.process(input, offset, length);
    positionTracker.addInputSamples(length / channelCount);
  }

  @Override
  public void process(ShortBuffer buffer) throws InterruptedException {
    int sampleCount = buffer.remaining() / channelCount;
    first.process(buffer);
    positionTracker.addInputSamples(sampleCount);
  }

  @Override
  public void process(short[][] input, int offset, int length) throws InterruptedException {
    first.process(input, offset, length);
    positionTracker.addInputSamples(length);
  }

  @Override
  protected List<AudioFilter> getFilters() {
    return filters;
  }
}
