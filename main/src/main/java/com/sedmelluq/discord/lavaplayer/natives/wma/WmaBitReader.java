package com.sedmelluq.discord.lavaplayer.natives.wma;

/**
 * MSB-first bit reader over a byte array, modelled on FFmpeg's get_bits API.
 * Supports peeking (for VLC decoding), byte alignment, and absolute bit
 * positioning (needed when reassembling frames across the bit reservoir).
 */
final class WmaBitReader {
    private byte[] data;
    private int byteLen;
    private int sizeBits;
    private int pos; // absolute bit position

    WmaBitReader() {
    }

    /** (Re)point the reader at {@code length} bytes of {@code buffer} starting at {@code offset}. */
    void reset(byte[] buffer, int offset, int length) {
        this.data = buffer;
        this.byteLen = offset + length;
        this.sizeBits = (offset + length) * 8;
        this.pos = offset * 8;
    }

    /** Peek up to 32 bits without consuming them, zero-padded past the end. */
    int peek(int n) {
        long acc = 0;
        int got = 0;
        int p = pos;
        while (got < n) {
            int bytePos = p >> 3;
            int bitOff = p & 7;
            int avail = 8 - bitOff;
            int take = Math.min(avail, n - got);
            int b = bytePos < byteLen ? (data[bytePos] & 0xFF) : 0;
            int chunk = (b >> (avail - take)) & ((1 << take) - 1);
            acc = (acc << take) | chunk;
            got += take;
            p += take;
        }
        return (int) acc;
    }

    int getBits(int n) {
        int v = peek(n);
        pos += n;
        return v;
    }

    int getBits1() {
        int bytePos = pos >> 3;
        int b = bytePos < byteLen ? (data[bytePos] & 0xFF) : 0;
        int bit = (b >> (7 - (pos & 7))) & 1;
        pos++;
        return bit;
    }

    void skip(int n) {
        pos += n;
    }

    void align() {
        pos = (pos + 7) & ~7;
    }

    int bitsLeft() {
        return sizeBits - pos;
    }

    int position() {
        return pos;
    }

    void position(int bitPosition) {
        pos = bitPosition;
    }
}
