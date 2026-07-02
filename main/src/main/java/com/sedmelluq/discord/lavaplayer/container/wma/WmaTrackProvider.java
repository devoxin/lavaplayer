package com.sedmelluq.discord.lavaplayer.container.wma;

import com.sedmelluq.discord.lavaplayer.filter.AudioPipeline;
import com.sedmelluq.discord.lavaplayer.filter.AudioPipelineFactory;
import com.sedmelluq.discord.lavaplayer.filter.PcmFormat;
import com.sedmelluq.discord.lavaplayer.natives.wma.WmaDecoder;
import com.sedmelluq.discord.lavaplayer.tools.io.SeekableInputStream;
import com.sedmelluq.discord.lavaplayer.track.playback.AudioProcessingContext;
import org.apache.commons.io.IOUtils;

import java.io.EOFException;
import java.io.IOException;

/**
 * Reads ASF data packets, reassembles WMA audio frames and decodes them into
 * PCM for the audio pipeline. All buffers are allocated once: the common case
 * (one payload carrying one whole frame) is decoded straight out of the packet
 * buffer, and only frames that span payloads use the reassembly buffer.
 */
public class WmaTrackProvider {
  private final WmaStreamInfo info;
  private final SeekableInputStream inputStream;
  private final AudioPipeline downstream;
  private final WmaDecoder decoder;

  private final byte[] packet;
  private final byte[] objectBuffer;
  private final short[][] sampleBuffers;

  private long packetsRead;
  private int samplesToSkip;   // decoder priming delay

  // reassembly state for frames that span multiple payloads/packets
  private int objectFill;
  private int objectSize;

  public WmaTrackProvider(AudioProcessingContext context, WmaStreamInfo info, SeekableInputStream inputStream) {
    this.info = info;
    this.inputStream = inputStream;
    this.downstream = AudioPipelineFactory.create(context, new PcmFormat(info.channels, info.sampleRate));
    this.decoder = new WmaDecoder(info.sampleRate, info.channels, info.bitRate, info.blockAlign,
        info.version, info.flags2);
    this.packet = new byte[info.packetSize];
    this.objectBuffer = new byte[info.blockAlign];
    this.sampleBuffers = new short[info.channels][decoder.frameLen * 16];
    this.samplesToSkip = decoder.frameLen * 2;
  }

  /**
   * Decode audio frames and push them downstream until the stream ends.
   * @throws InterruptedException when interrupted (seek/stop)
   */
  public void provideFrames() throws InterruptedException {
    try {
      while (packetsRead < info.totalPackets) {
        try {
          IOUtils.readFully(inputStream, packet);
        } catch (EOFException e) {
          break;
        }
        packetsRead++;
        parsePacket();
      }
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private void decodeFrame(byte[] buf, int offset, int length) throws InterruptedException {
    int framesDecoded = decoder.decode(buf, offset, length);
    if (framesDecoded > 0) {
      emit(framesDecoded * decoder.frameLen);
    }
  }

  private void emit(int count) throws InterruptedException {
    int start = 0;
    if (samplesToSkip > 0) {
      int skip = Math.min(samplesToSkip, count);
      samplesToSkip -= skip;
      start = skip;
    }
    int remaining = count - start;
    if (remaining <= 0) {
      return;
    }
    float[][] src = decoder.samples;
    for (int ch = 0; ch < info.channels; ch++) {
      float[] s = src[ch];
      short[] d = sampleBuffers[ch];
      for (int i = 0; i < remaining; i++) {
        int v = Math.round(s[start + i] * 32768.0f);
        if (v > Short.MAX_VALUE) v = Short.MAX_VALUE;
        else if (v < Short.MIN_VALUE) v = Short.MIN_VALUE;
        d[i] = (short) v;
      }
    }
    downstream.process(sampleBuffers, 0, remaining);
  }

  private void parsePacket() throws InterruptedException {
    int[] pos = { 0 };
    int flags = u8(pos);
    if ((flags & 0x80) != 0) {
      pos[0] += flags & 0x0F; // skip error correction data
    }

    int lengthTypeFlags = u8(pos);
    int propertyFlags = u8(pos);
    boolean multiplePayloads = (lengthTypeFlags & 0x01) != 0;
    int seqType = (lengthTypeFlags >> 1) & 0x03;
    int padType = (lengthTypeFlags >> 3) & 0x03;
    int pktLenType = (lengthTypeFlags >> 5) & 0x03;

    int repType = propertyFlags & 0x03;
    int mooType = (propertyFlags >> 2) & 0x03; // offset into media object
    int monType = (propertyFlags >> 4) & 0x03; // media object number
    // bits 6-7 are the stream-number length type; the field is always one byte in practice

    int packetLen = readLen(pos, pktLenType);
    readLen(pos, seqType);
    int padLen = readLen(pos, padType);
    pos[0] += 6; // send time(4) + duration(2)

    int numPayloads = 1;
    int payloadLenType = 0;
    if (multiplePayloads) {
      int pf = u8(pos);
      numPayloads = pf & 0x3F;
      payloadLenType = (pf >> 6) & 0x03;
    }

    int total = pktLenType != 0 ? packetLen : packet.length;

    for (int n = 0; n < numPayloads; n++) {
      pos[0] += 1; // stream number byte (+ key frame flag)
      readLen(pos, monType);
      int moo = readLen(pos, mooType);
      int repLen = readLen(pos, repType);
      int mediaObjectSize = repLen >= 8 ? readInt(pos[0]) : -1;
      pos[0] += repLen;

      int payloadLen = multiplePayloads ? readLen(pos, payloadLenType) : (total - pos[0] - padLen);
      if (payloadLen < 0 || pos[0] + payloadLen > packet.length) {
        break;
      }

      handlePayload(moo, mediaObjectSize, pos[0], payloadLen);
      pos[0] += payloadLen;
    }
  }

  private void handlePayload(int moo, int mediaObjectSize, int dataOffset, int len) throws InterruptedException {
    int size = mediaObjectSize >= 0 ? mediaObjectSize : info.blockAlign;
    if (moo == 0 && len >= size) {
      // whole frame contained in this payload: decode straight from the packet
      decodeFrame(packet, dataOffset, size);
      return;
    }

    // frame spans payloads/packets: accumulate into the reassembly buffer
    if (moo == 0) {
      objectFill = 0;
      objectSize = size;
    }
    if (moo + len <= objectBuffer.length) {
      System.arraycopy(packet, dataOffset, objectBuffer, moo, len);
      objectFill = Math.max(objectFill, moo + len);
    }
    if (objectSize > 0 && objectFill >= objectSize) {
      decodeFrame(objectBuffer, 0, objectSize);
      objectFill = 0;
      objectSize = 0;
    }
  }

  /**
   * Seek to the given timecode. ASF lacks a precise per-sample index here, so we
   * estimate the packet from the constant packet size and re-prime the decoder.
   */
  public void seekToTimecode(long timecode) {
    try {
      long packetIndex = 0;
      if (info.duration > 0) {
        packetIndex = timecode * info.totalPackets / info.duration;
        if (packetIndex < 0) packetIndex = 0;
        if (packetIndex >= info.totalPackets) packetIndex = info.totalPackets - 1;
      }
      inputStream.seek(info.firstPacketPosition + packetIndex * info.packetSize);
      packetsRead = packetIndex;
      objectFill = 0;
      objectSize = 0;
      decoder.reset();
      samplesToSkip = decoder.frameLen * 2;
      long actual = info.totalPackets > 0 ? packetIndex * info.duration / info.totalPackets : 0;
      downstream.seekPerformed(timecode, actual);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  public void close() {
    downstream.close();
  }

  private int u8(int[] pos) {
    return packet[pos[0]++] & 0xFF;
  }

  private int readLen(int[] pos, int lenType) {
    switch (lenType) {
      case 0: return 0;
      case 1: return packet[pos[0]++] & 0xFF;
      case 2: {
        int v = (packet[pos[0]] & 0xFF) | ((packet[pos[0] + 1] & 0xFF) << 8);
        pos[0] += 2;
        return v;
      }
      default: {
        int v = readInt(pos[0]);
        pos[0] += 4;
        return v;
      }
    }
  }

  private int readInt(int offset) {
    return (packet[offset] & 0xFF) | ((packet[offset + 1] & 0xFF) << 8)
        | ((packet[offset + 2] & 0xFF) << 16) | ((packet[offset + 3] & 0xFF) << 24);
  }
}
