package com.sedmelluq.discord.lavaplayer.container.wma;

/**
 * Audio stream configuration parsed from an ASF (Windows Media) file header,
 * together with the location of the audio data packets.
 */
public class WmaStreamInfo {
  /** WMA codec version: 1 for format tag 0x160, 2 for 0x161. */
  public final int version;
  public final int channels;
  public final int sampleRate;
  public final int bitRate;
  public final int blockAlign;
  /** Codec-specific flags (flags2) extracted from the WAVEFORMATEX extra data. */
  public final int flags2;
  /** Track duration in milliseconds. */
  public final long duration;

  /** Fixed size of each ASF data packet. */
  public final int packetSize;
  /** Number of ASF data packets. */
  public final long totalPackets;
  /** Absolute byte offset of the first ASF data packet. */
  public final long firstPacketPosition;

  public WmaStreamInfo(int version, int channels, int sampleRate, int bitRate, int blockAlign,
                       int flags2, long duration, int packetSize, long totalPackets,
                       long firstPacketPosition) {
    this.version = version;
    this.channels = channels;
    this.sampleRate = sampleRate;
    this.bitRate = bitRate;
    this.blockAlign = blockAlign;
    this.flags2 = flags2;
    this.duration = duration;
    this.packetSize = packetSize;
    this.totalPackets = totalPackets;
    this.firstPacketPosition = firstPacketPosition;
  }
}
