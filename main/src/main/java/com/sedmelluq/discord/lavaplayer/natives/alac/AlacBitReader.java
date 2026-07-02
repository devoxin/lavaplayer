package com.sedmelluq.discord.lavaplayer.natives.alac;

/**
 * Bit-level reader for ALAC frame data. ALAC packs fields at arbitrary bit widths
 * with no byte-alignment between them, so a dedicated reader is required.
 *
 * <p>Backed by a raw byte array with an absolute bit position. In addition to the
 * usual sequential {@link #readBits} interface used for the frame header, it exposes
 * the underlying bytes via {@link #read32} so the adaptive-Golomb entropy decoder can
 * load 32-bit windows at arbitrary bit offsets, exactly as Apple's reference does.
 */
class AlacBitReader {
    final byte[] data;
    final int byteSize;
    int bitPos;

    /**
     * @param data   backing buffer holding the raw ALAC frame
     * @param length number of valid bytes in {@code data}
     */
    AlacBitReader(byte[] data, int length) {
        this.data = data;
        this.byteSize = length;
        this.bitPos = 0;
    }

    /** Read exactly {@code n} bits (0–32) MSB-first as an unsigned value, returned as int. */
    int readBits(int n) {
        int result = 0;
        int remaining = n;
        while (remaining > 0) {
            int bytePos = bitPos >> 3;
            int bitOff = bitPos & 7;
            int avail = 8 - bitOff;
            int take = Math.min(avail, remaining);
            int b = bytePos < byteSize ? (data[bytePos] & 0xFF) : 0;
            int shifted = (b >> (avail - take)) & ((1 << take) - 1);
            result = (result << take) | shifted;
            bitPos += take;
            remaining -= take;
        }
        return result;
    }

    /** Read a single bit (0 or 1). */
    int readBit() {
        return readBits(1);
    }

    /**
     * Load a big-endian 32-bit window starting at {@code byteOffset}, zero-padded
     * past the end of the buffer. Returned in the low 32 bits of the long so callers
     * can treat it as unsigned.
     */
    long read32(int byteOffset) {
        long b0 = byteOffset     < byteSize ? (data[byteOffset]     & 0xFFL) : 0;
        long b1 = byteOffset + 1 < byteSize ? (data[byteOffset + 1] & 0xFFL) : 0;
        long b2 = byteOffset + 2 < byteSize ? (data[byteOffset + 2] & 0xFFL) : 0;
        long b3 = byteOffset + 3 < byteSize ? (data[byteOffset + 3] & 0xFFL) : 0;
        return (b0 << 24) | (b1 << 16) | (b2 << 8) | b3;
    }

    /** Single byte at {@code byteOffset}, zero past the end of the buffer. */
    int byteAt(int byteOffset) {
        return byteOffset < byteSize ? (data[byteOffset] & 0xFF) : 0;
    }
}
