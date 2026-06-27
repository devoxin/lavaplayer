package com.sedmelluq.discord.lavaplayer.natives.wma;

import java.util.ArrayList;
import java.util.List;

/**
 * Prefix-code (Huffman/VLC) decoder. Uses a single direct lookup table indexed
 * by the next {@code PRIMARY_BITS} bits for the common short codes, falling back
 * to a linear scan for the rare longer codes. Equivalent in output to FFmpeg's
 * multi-level {@code get_vlc2}.
 */
final class WmaVlc {
    private static final int PRIMARY_BITS = 9;
    private static final int PRIMARY_SIZE = 1 << PRIMARY_BITS;

    private final int[] symByPrefix = new int[PRIMARY_SIZE];
    private final byte[] lenByPrefix = new byte[PRIMARY_SIZE]; // 0 => long code, scan fallback
    private final int[] longBits;
    private final int[] longCodes;
    private final int[] longSyms;
    private final int maxBits;

    private WmaVlc(int[] codes, int[] bits, int[] symbols) {
        List<int[]> longList = new ArrayList<>();
        int max = 0;
        for (int i = 0; i < bits.length; i++) {
            int b = bits[i];
            if (b <= 0) {
                continue;
            }
            max = Math.max(max, b);
            int sym = symbols != null ? symbols[i] : i;
            if (b <= PRIMARY_BITS) {
                int base = codes[i] << (PRIMARY_BITS - b);
                int count = 1 << (PRIMARY_BITS - b);
                for (int j = 0; j < count; j++) {
                    symByPrefix[base + j] = sym;
                    lenByPrefix[base + j] = (byte) b;
                }
            } else {
                int prefix = codes[i] >>> (b - PRIMARY_BITS);
                lenByPrefix[prefix] = 0; // mark as "needs fallback"
                longList.add(new int[] { b, codes[i], sym });
            }
        }
        maxBits = max;
        longBits = new int[longList.size()];
        longCodes = new int[longList.size()];
        longSyms = new int[longList.size()];
        for (int i = 0; i < longList.size(); i++) {
            longBits[i] = longList.get(i)[0];
            longCodes[i] = longList.get(i)[1];
            longSyms[i] = longList.get(i)[2];
        }
    }

    /** Build from explicit (code, bitLength) pairs; symbol == table index. */
    static WmaVlc fromCodes(int[] codes, byte[] bits) {
        int[] b = new int[bits.length];
        for (int i = 0; i < bits.length; i++) {
            b[i] = bits[i] & 0xFF;
        }
        return new WmaVlc(codes, b, null);
    }

    /**
     * Build from per-symbol code lengths using FFmpeg's canonical accumulator
     * assignment (used for the hgain table). {@code offset} is added to symbols.
     */
    static WmaVlc fromLengths(byte[] lengths, int[] symbols, int offset) {
        int n = lengths.length;
        int[] codes = new int[n];
        int[] bits = new int[n];
        int[] syms = new int[n];
        long code = 0;
        for (int i = 0; i < n; i++) {
            int len = lengths[i];
            bits[i] = len;
            syms[i] = symbols[i] + offset;
            if (len > 0) {
                codes[i] = (int) (code >>> (32 - len));
                code += 1L << (32 - len);
            }
        }
        return new WmaVlc(codes, bits, syms);
    }

    int decode(WmaBitReader br) {
        int idx = br.peek(PRIMARY_BITS);
        int len = lenByPrefix[idx];
        if (len > 0) {
            br.skip(len);
            return symByPrefix[idx];
        }
        int peeked = br.peek(maxBits);
        for (int i = 0; i < longBits.length; i++) {
            int b = longBits[i];
            if ((peeked >>> (maxBits - b)) == longCodes[i]) {
                br.skip(b);
                return longSyms[i];
            }
        }
        throw new IllegalStateException("WMA VLC decode failed");
    }
}
