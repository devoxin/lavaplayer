package com.sedmelluq.discord.lavaplayer.natives.opus;

import com.sedmelluq.discord.lavaplayer.natives.ConnectorNativeLibLoader;

import java.nio.ByteBuffer;
import java.nio.ShortBuffer;

public class OpusEncoderLibrary {
  /** Denotes a successful response. */
  public static final int OPUS_OK = 0;
  public static final int OPUS_BITRATE_MAX = -1;
  /** Denotes that the encoder should automatically select a value. */
  public static final int OPUS_AUTO = -1000;

  static final int APPLICATION_AUDIO = 2049;

  public static final int SET_BITRATE_REQUEST = 4002;
  public static final int SET_VBR_REQUEST = 4006;
  public static final int SET_VBR_CONSTRAINT_REQUEST = 4020;

  private OpusEncoderLibrary() {

  }

  static OpusEncoderLibrary getInstance() {
    ConnectorNativeLibLoader.loadConnectorLibrary();
    return new OpusEncoderLibrary();
  }

  native long create(int sampleRate, int channels, int application, int quality);

  native void destroy(long instance);

  native int encode(long instance, ShortBuffer directInput, int frameSize, ByteBuffer directOutput, int outputCapacity);

  native int configure(long instance, int request, int value);
}
