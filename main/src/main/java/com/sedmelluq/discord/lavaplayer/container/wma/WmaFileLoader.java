package com.sedmelluq.discord.lavaplayer.container.wma;

import com.sedmelluq.discord.lavaplayer.tools.io.SeekableInputStream;
import com.sedmelluq.discord.lavaplayer.track.playback.AudioProcessingContext;
import org.apache.commons.io.IOUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Parses the ASF (Windows Media) container header to extract the WMA audio
 * stream configuration and the location of the audio data packets.
 */
public class WmaFileLoader {
  /** First 16 bytes of every ASF file: the ASF Header Object GUID. */
  public static final int[] ASF_GUID = new int[] {
      0x30, 0x26, 0xB2, 0x75, 0x8E, 0x66, 0xCF, 0x11, 0xA6, 0xD9, 0x00, 0xAA, 0x00, 0x62, 0xCE, 0x6C
  };

  private static final byte[] GUID_FILE_PROP = guid(
      0xA1, 0xDC, 0xAB, 0x8C, 0x47, 0xA9, 0xCF, 0x11, 0x8E, 0xE4, 0x00, 0xC0, 0x0C, 0x20, 0x53, 0x65);
  private static final byte[] GUID_STREAM_PROP = guid(
      0x91, 0x07, 0xDC, 0xB7, 0xB7, 0xA9, 0xCF, 0x11, 0x8E, 0xE6, 0x00, 0xC0, 0x0C, 0x20, 0x53, 0x65);
  private static final byte[] GUID_AUDIO_MEDIA = guid(
      0x40, 0x9E, 0x69, 0xF8, 0x4D, 0x5B, 0xCF, 0x11, 0xA8, 0xFD, 0x00, 0x80, 0x5F, 0x5C, 0x44, 0x2B);
  private static final byte[] GUID_DATA = guid(
      0x36, 0x26, 0xB2, 0x75, 0x8E, 0x66, 0xCF, 0x11, 0xA6, 0xD9, 0x00, 0xAA, 0x00, 0x62, 0xCE, 0x6C);
  private static final byte[] GUID_CONTENT_DESC = guid(
      0x33, 0x26, 0xB2, 0x75, 0x8E, 0x66, 0xCF, 0x11, 0xA6, 0xD9, 0x00, 0xAA, 0x00, 0x62, 0xCE, 0x6C);

  private final SeekableInputStream inputStream;
  private final Map<String, String> metadata = new HashMap<>();
  private WmaStreamInfo streamInfo;

  public WmaFileLoader(SeekableInputStream inputStream) {
    this.inputStream = inputStream;
  }

  /**
   * Read the ASF header and audio stream configuration.
   * @return the parsed stream info
   * @throws IOException on IO error
   */
  public WmaStreamInfo parseHeaders() throws IOException {
    if (streamInfo != null) {
      return streamInfo;
    }

    inputStream.seek(0);
    byte[] preamble = new byte[30];
    IOUtils.readFully(inputStream, preamble);

    for (int i = 0; i < 16; i++) {
      if ((preamble[i] & 0xFF) != ASF_GUID[i]) {
        throw new IllegalStateException("Not an ASF file");
      }
    }

    long headerSize = readLong(preamble, 16);
    if (headerSize < 30 || headerSize > 32 * 1024 * 1024) {
      throw new IllegalStateException("Invalid ASF header size: " + headerSize);
    }

    byte[] header = new byte[(int) headerSize];
    System.arraycopy(preamble, 0, header, 0, 30);
    IOUtils.readFully(inputStream, header, 30, header.length - 30);

    int version = 0, channels = 0, sampleRate = 0, bitRate = 0, blockAlign = 0, flags2 = 0;
    long playDuration = 0, preroll = 0;
    int packetSize = 0;

    int p = 30;
    while (p + 24 <= header.length) {
      long objectSize = readLong(header, p + 16);
      if (objectSize < 24 || p + objectSize > header.length) {
        break;
      }
      if (matchesGuid(header, p, GUID_FILE_PROP)) {
        // file_id(16) file_size(8) creation(8) data_packets(8) play_dur(8) send_dur(8) preroll(8)
        // flags(4) min_packet(4) max_packet(4) max_bitrate(4)
        playDuration = readLong(header, p + 24 + 16 + 8 + 8 + 8);
        preroll = readLong(header, p + 24 + 16 + 8 + 8 + 8 + 8 + 8);
        packetSize = readInt(header, p + 24 + 16 + 8 + 8 + 8 + 8 + 8 + 8 + 4 + 4);
      } else if (matchesGuid(header, p, GUID_STREAM_PROP)) {
        if (matchesGuid(header, p + 24, GUID_AUDIO_MEDIA)) {
          int tsOffset = p + 24 + 16 + 16 + 8 + 4 + 4 + 2 + 4; // start of WAVEFORMATEX
          int formatTag = readShort(header, tsOffset);
          channels = readShort(header, tsOffset + 2);
          sampleRate = readInt(header, tsOffset + 4);
          int avgBytesPerSec = readInt(header, tsOffset + 8);
          blockAlign = readShort(header, tsOffset + 12);
          int cbSize = readShort(header, tsOffset + 16);
          version = formatTag == 0x0160 ? 1 : 2;
          bitRate = avgBytesPerSec * 8;
          int extraOffset = tsOffset + 18;
          // flags2 lives at extradata+4 for v2, extradata+2 for v1
          int flagsOffset = extraOffset + (version == 2 ? 4 : 2);
          if (cbSize >= (version == 2 ? 6 : 4)) {
            flags2 = readShort(header, flagsOffset);
          }
        }
      } else if (matchesGuid(header, p, GUID_CONTENT_DESC)) {
        parseContentDescription(header, p + 24);
      }
      p += (int) objectSize;
    }

    if (sampleRate == 0 || channels == 0 || blockAlign == 0) {
      throw new IllegalStateException("No supported WMA audio stream found in ASF file");
    }

    // Data object follows the header
    byte[] dataHeader = new byte[50];
    inputStream.seek(headerSize);
    IOUtils.readFully(inputStream, dataHeader);
    if (!matchesGuid(dataHeader, 0, GUID_DATA)) {
      throw new IllegalStateException("ASF data object not found");
    }
    long totalPackets = readLong(dataHeader, 16 + 8 + 16);
    long firstPacketPosition = headerSize + 50;

    long duration = playDuration / 10000L - preroll;
    if (duration < 0) {
      duration = 0;
    }

    streamInfo = new WmaStreamInfo(version, channels, sampleRate, bitRate, blockAlign, flags2,
        duration, packetSize, totalPackets, firstPacketPosition);
    return streamInfo;
  }

  /**
   * Create a track provider that decodes the WMA stream into PCM.
   * @param context Configuration and output information for processing
   * @return the track provider
   * @throws IOException on IO error
   */
  public WmaTrackProvider loadTrack(AudioProcessingContext context) throws IOException {
    return new WmaTrackProvider(context, parseHeaders(), inputStream);
  }

  public String getTextMetadata(String key) {
    return metadata.get(key);
  }

  private void parseContentDescription(byte[] header, int offset) {
    int titleLen = readShort(header, offset);
    int authorLen = readShort(header, offset + 2);
    int pos = offset + 10; // skip title/author/copyright/description/rating length fields
    metadata.put("Title", utf16(header, pos, titleLen));
    pos += titleLen;
    metadata.put("Author", utf16(header, pos, authorLen));
  }

  private static String utf16(byte[] data, int offset, int length) {
    if (length <= 0 || offset + length > data.length) {
      return null;
    }
    String s = new String(data, offset, length, StandardCharsets.UTF_16LE);
    int end = s.indexOf('\0');
    if (end >= 0) {
      s = s.substring(0, end);
    }
    return s.isEmpty() ? null : s;
  }

  private static boolean matchesGuid(byte[] data, int offset, byte[] guid) {
    if (offset + 16 > data.length) {
      return false;
    }
    for (int i = 0; i < 16; i++) {
      if (data[offset + i] != guid[i]) {
        return false;
      }
    }
    return true;
  }

  private static int readShort(byte[] data, int offset) {
    return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
  }

  private static int readInt(byte[] data, int offset) {
    return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8)
        | ((data[offset + 2] & 0xFF) << 16) | ((data[offset + 3] & 0xFF) << 24);
  }

  private static long readLong(byte[] data, int offset) {
    long v = 0;
    for (int i = 0; i < 8; i++) {
      v |= (long) (data[offset + i] & 0xFF) << (8 * i);
    }
    return v;
  }

  private static byte[] guid(int... bytes) {
    byte[] out = new byte[16];
    for (int i = 0; i < 16; i++) {
      out[i] = (byte) bytes[i];
    }
    return out;
  }
}
