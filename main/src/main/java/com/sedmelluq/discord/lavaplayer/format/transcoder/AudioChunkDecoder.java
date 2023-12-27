package com.sedmelluq.discord.lavaplayer.format.transcoder;

import java.nio.ByteBuffer;
import java.nio.ShortBuffer;

/**
 * Decodes one chunk of audio into internal PCM format.
 */
public interface AudioChunkDecoder {
  /**
   * @param encoded Encoded bytes
   * @param buffer Output buffer for the PCM data
   */
  void decode(byte[] encoded, ShortBuffer buffer);

  /**
   * @param encoded Encoded bytes
   * @param buffer Output buffer for the PCM data
   */
  default void decode(ByteBuffer encoded, ShortBuffer buffer) {
    byte[] array;

    if (encoded.hasArray()) {
      array = encoded.array();
    } else {
      array = new byte[encoded.remaining()];
      encoded.get(array);
    }

    decode(array, buffer);
  }

  /**
   * Frees up all held resources.
   */
  void close();
}
