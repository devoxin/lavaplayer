package com.sedmelluq.discord.lavaplayer.filter;

/**
 * Tracks playback position based on how much source audio has been fed into an audio pipeline, rather
 * than how much audio the pipeline has produced. Positions derived from produced (output) audio become
 * incorrect whenever a filter changes the audio duration - for example a timescale/tempo filter, where
 * speeding up produces fewer output samples per source sample and slowing down produces more. Because
 * every input sample corresponds to exactly one source sample at the input sample rate, deriving the
 * timecode from consumed input keeps the reported position correct for any such filter automatically,
 * without the filter needing to report anything.
 *
 * <p>All access happens on the single audio processing thread, so no synchronisation is required.
 */
public class PipelinePositionTracker {
  private final int inputSampleRate;

  private long inputSampleCount;
  private long baseSampleCount;
  private long baseTimecode;

  /**
   * @param inputSampleRate Sample rate of the audio entering the pipeline (the source sample rate).
   */
  public PipelinePositionTracker(int inputSampleRate) {
    this.inputSampleRate = inputSampleRate;
  }

  /**
   * Record that some source audio has been fed into the pipeline.
   *
   * @param sampleCount Number of per-channel samples (frames) added.
   */
  public void addInputSamples(long sampleCount) {
    inputSampleCount += sampleCount;
  }

  /**
   * Rebase the tracked position after a seek. The reported timecode continues from the seek target and
   * counts input consumed from this point on.
   *
   * @param requestedTime Timecode which was requested to seek to, in milliseconds.
   * @param providedTime Timecode which the track was actually able to seek to, in milliseconds.
   */
  public void seekPerformed(long requestedTime, long providedTime) {
    baseTimecode = Math.max(requestedTime, providedTime);
    baseSampleCount = inputSampleCount;
  }

  /**
   * @return The current playback timecode in milliseconds, derived from consumed source audio.
   */
  public long currentTimecode() {
    return baseTimecode + (inputSampleCount - baseSampleCount) * 1000 / inputSampleRate;
  }
}