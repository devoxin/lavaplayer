package com.sedmelluq.discord.lavaplayer.natives.alac;

import java.nio.ByteBuffer;
import java.nio.ShortBuffer;

/**
 * Pure-Java ALAC (Apple Lossless Audio Codec) decoder.
 *
 * <p>A direct port of Apple's open-source reference decoder (Apache 2.0): the
 * frame/element parsing of {@code ALACDecoder.cpp}, the adaptive-Golomb entropy
 * decode of {@code ag_dec.c}, the dynamic predictor of {@code dp_dec.c} and the
 * mixing/matrix recombination of {@code matrix_dec.c}. Decodes one MP4 sample
 * (one ALAC frame) per call to {@link #decode} and outputs interleaved 16-bit
 * signed PCM.
 *
 * <p>Supports mono (SCE) and stereo (CPE), compressed and escaped (verbatim)
 * frames, and the "shift off" path used by &gt;16-bit content where the low bytes
 * of each sample are stored uncompressed alongside the entropy-coded high bits.
 * Higher bit depths are truncated to 16-bit on output, matching lavaplayer's
 * internal PCM format.
 */
public class AlacDecoder {
    private static final int MAX_LPC_ORDER = 32;

    // 3-bit element type tags in the ALAC bitstream
    private static final int ID_SCE = 0; // single channel element
    private static final int ID_CPE = 1; // channel pair element
    private static final int ID_LFE = 3; // LFE (treated identically to SCE)
    private static final int ID_DSE = 4; // data stream element
    private static final int ID_FIL = 6; // fill element
    private static final int ID_END = 7; // end of frame

    // Adaptive-Golomb tuning constants (aglib.h)
    private static final int QBSHIFT = 9;
    private static final int QB = 1 << QBSHIFT;                   // 512
    private static final int MMULSHIFT = 2;
    private static final int MDENSHIFT = QBSHIFT - MMULSHIFT - 1; // 6
    private static final int MOFF = 1 << (MDENSHIFT - 2);         // 16
    private static final int BITOFF = 24;
    private static final int MAX_PREFIX_16 = 9;
    private static final int MAX_PREFIX_32 = 9;
    private static final int MAX_DATATYPE_BITS_16 = 16;
    private static final int N_MAX_MEAN_CLAMP = 0xFFFF;
    private static final int N_MEAN_CLAMP_VAL = 0xFFFF;

    private static final long MASK32 = 0xFFFFFFFFL;

    // ALACSpecificConfig (from the 'alac' MP4 box, version/flags already stripped)
    public final int frameLength;
    public final int bitDepth;
    public final int numChannels;
    public final int sampleRate;

    private final int riceHistoryMult;    // 'pb' global parameter
    private final int riceInitialHistory; // 'mb' (mb0) initial mean
    private final int riceKModifier;      // 'kb' maximum Rice parameter

    // Per-frame working buffers — reused across calls
    private final int[][] predictor;  // [ch][sample]: entropy-decoded residuals / predictor in-out
    private final int[][] samples;    // [ch][sample]: reconstructed output (full bit depth)
    private final int[] shiftBuffer;  // interleaved uncompressed low bytes when "shift off" is active
    private final short[][] lpcCoefs; // [ch][coef]: LPC coefficients (reloaded each frame)

    private byte[] frameBytes = new byte[0]; // reusable copy of the input frame

    /**
     * Construct a decoder from a raw 24-byte ALACSpecificConfig.
     * The 4-byte version/flags prefix present in the MP4 box must be
     * stripped by the caller before passing the config here.
     */
    public AlacDecoder(byte[] config) {
        ByteBuffer b = ByteBuffer.wrap(config);

        frameLength        = readBigEndianInt(b);
        b.get();                             // compatibleVersion — always 0
        bitDepth           = b.get() & 0xFF;
        riceHistoryMult    = b.get() & 0xFF; // pb
        riceInitialHistory = b.get() & 0xFF; // mb
        riceKModifier      = b.get() & 0xFF; // kb
        numChannels        = b.get() & 0xFF;
        b.getShort();                        // maxRun (not needed for decoding)
        readBigEndianInt(b);                 // maxFrameBytes (not needed)
        readBigEndianInt(b);                 // avgBitRate (not needed)
        sampleRate         = readBigEndianInt(b);

        int bufSize = frameLength + MAX_LPC_ORDER;
        predictor   = new int[2][bufSize];
        samples     = new int[2][bufSize];
        shiftBuffer = new int[2 * bufSize];
        lpcCoefs    = new short[2][MAX_LPC_ORDER];
    }

    /**
     * Decode one ALAC frame (one MP4 sample) into interleaved 16-bit PCM.
     *
     * @param input  one raw ALAC frame as delivered by the MP4 sample table
     * @param output destination buffer for interleaved PCM; must have remaining
     *               capacity &gt;= {@link #frameLength} * {@link #numChannels}
     * @return number of PCM samples written per channel (may be less than
     *         frameLength for the final frame of a track)
     */
    public int decode(ByteBuffer input, ShortBuffer output) {
        int length = input.remaining();
        if (frameBytes.length < length) {
            frameBytes = new byte[length];
        }
        input.get(frameBytes, 0, length);

        AlacBitReader bits = new AlacBitReader(frameBytes, length);
        int channelIndex = 0;
        int numSamples = frameLength;

        while (channelIndex < numChannels) {
            int tag = bits.readBits(3);

            switch (tag) {
                case ID_SCE:
                case ID_LFE:
                    numSamples = decodeElement(bits, false);
                    writeInterleaved(output, numSamples, 1);
                    channelIndex += 1;
                    break;
                case ID_CPE:
                    numSamples = decodeElement(bits, true);
                    writeInterleaved(output, numSamples, 2);
                    channelIndex += 2;
                    break;
                case ID_DSE:
                    skipDataStreamElement(bits);
                    break;
                case ID_FIL:
                    skipFillElement(bits);
                    break;
                case ID_END:
                    return numSamples;
                default:
                    // CCE / PCE / unknown — cannot continue safely
                    return numSamples;
            }
        }

        return numSamples;
    }

    // ---- Element decode -------------------------------------------------------

    /**
     * Decode a single SCE/LFE (mono) or CPE (stereo) element into {@link #samples},
     * returning the number of samples decoded per channel.
     */
    private int decodeElement(AlacBitReader bits, boolean stereo) {
        bits.readBits(4);  // element instance tag — unused
        bits.readBits(12); // unused header bits — always 0

        int headerByte   = bits.readBits(4);
        int partialFrame = headerByte >> 3;
        int bytesShifted = (headerByte >> 1) & 0x3;
        boolean escape   = (headerByte & 0x1) != 0;
        int shift        = bytesShifted * 8;

        int numSamples = frameLength;
        if (partialFrame != 0) {
            numSamples = (bits.readBits(16) << 16) | bits.readBits(16);
        }

        int channels = stereo ? 2 : 1;

        if (escape) {
            // Uncompressed frame: samples are stored raw, interleaved across channels.
            decodeVerbatim(bits, numSamples, channels);
            return numSamples;
        }

        // chanBits: stereo carries one extra bit from the mixing transform.
        int chanBits = bitDepth - (bytesShifted * 8) + (stereo ? 1 : 0);
        int wb = (1 << riceKModifier) - 1;

        int mixBits = bits.readBits(8);
        int mixRes  = (byte) bits.readBits(8); // signed

        int[] mode      = new int[channels];
        int[] denShift  = new int[channels];
        int[] pbFactor  = new int[channels];
        int[] numCoeffs = new int[channels];

        for (int ch = 0; ch < channels; ch++) {
            int hb = bits.readBits(8);
            mode[ch]     = hb >> 4;
            denShift[ch] = hb & 0xF;

            hb = bits.readBits(8);
            pbFactor[ch]  = hb >> 5;
            numCoeffs[ch] = hb & 0x1F;

            for (int i = 0; i < numCoeffs[ch]; i++) {
                lpcCoefs[ch][i] = (short) bits.readBits(16);
            }
        }

        // Uncompressed low bytes ("shift off") are interleaved here, before the
        // entropy-coded high bits of every channel.
        if (bytesShifted != 0) {
            int count = numSamples * channels;
            for (int i = 0; i < count; i++) {
                shiftBuffer[i] = bits.readBits(shift);
            }
        }

        // Entropy-decode + run the dynamic predictor for each channel.
        for (int ch = 0; ch < channels; ch++) {
            int pbLocal = (riceHistoryMult * pbFactor[ch]) / 4;
            dynDecomp(bits, predictor[ch], numSamples, chanBits, pbLocal, wb);

            if (mode[ch] == 0) {
                unpcBlock(predictor[ch], samples[ch], numSamples,
                        lpcCoefs[ch], numCoeffs[ch], chanBits, denShift[ch]);
            } else {
                // Special "31-coefficient" pre-pass, then the normal predictor in place.
                unpcBlock(predictor[ch], predictor[ch], numSamples, null, 31, chanBits, 0);
                unpcBlock(predictor[ch], samples[ch], numSamples,
                        lpcCoefs[ch], numCoeffs[ch], chanBits, denShift[ch]);
            }
        }

        if (stereo) {
            unmixStereo(numSamples, mixBits, mixRes, bytesShifted);
        } else if (bytesShifted != 0) {
            int[] out = samples[0];
            for (int i = 0; i < numSamples; i++) {
                out[i] = (out[i] << shift) | shiftBuffer[i];
            }
        }

        return numSamples;
    }

    // ---- Verbatim (uncompressed) frames ---------------------------------------

    private void decodeVerbatim(AlacBitReader bits, int numSamples, int channels) {
        for (int i = 0; i < numSamples; i++) {
            for (int ch = 0; ch < channels; ch++) {
                samples[ch][i] = signExtend(bits.readBits(bitDepth), bitDepth);
            }
        }
    }

    // ---- Adaptive-Golomb entropy decoding (ag_dec.c) --------------------------

    /**
     * Decode entropy-coded residuals for one channel using Apple's adaptive-Golomb
     * scheme. The Rice parameter adapts to a running mean ({@code mb}); near-silent
     * runs of zeros are coded with a secondary escape handled inline.
     */
    private void dynDecomp(AlacBitReader bits, int[] out, int numSamples, int maxBits,
                           int pbLocal, int wbLocal) {
        int mb = riceInitialHistory;
        int zmode = 0;
        int c = 0;

        while (c < numSamples) {
            int m = mb >>> QBSHIFT;
            int k = lg3a(m);
            if (k > riceKModifier) k = riceKModifier;
            m = (1 << k) - 1;

            int n = dynGet32(bits, m, k, maxBits);

            // Least-significant bit is the sign; bias by the previous zero-run flag.
            int ndecode = n + zmode;
            int multiplier = -(ndecode & 1);
            multiplier |= 1;
            out[c] = ((ndecode + 1) >>> 1) * multiplier;
            c++;

            // Update the running mean.
            mb = pbLocal * (n + zmode) + mb - ((pbLocal * mb) >>> QBSHIFT);
            if (n > N_MAX_MEAN_CLAMP) {
                mb = N_MEAN_CLAMP_VAL;
            }
            zmode = 0;

            if (((mb << MMULSHIFT) < QB) && (c < numSamples)) {
                zmode = 1;
                int kz = lead(mb) - BITOFF + ((mb + MOFF) >>> MDENSHIFT);
                int mz = ((1 << kz) - 1) & wbLocal;

                int run = dynGet(bits, mz, kz);
                if (run > numSamples - c) {
                    run = numSamples - c; // defensive clamp against malformed input
                }
                for (int j = 0; j < run; j++) {
                    out[c++] = 0;
                }

                if (run >= 65535) {
                    zmode = 0;
                }
                mb = 0;
            }
        }
    }

    /** Decode one value with the primary 32-bit adaptive-Golomb code (dyn_get_32bit). */
    private static int dynGet32(AlacBitReader bits, int m, int k, int maxBits) {
        int tempbits = bits.bitPos;
        long streamlong = (bits.read32(tempbits >> 3) << (tempbits & 7)) & MASK32;
        int result = lead((int) ~streamlong);

        if (result >= MAX_PREFIX_32) {
            result = getStreamBits(bits, tempbits + MAX_PREFIX_32, maxBits);
            tempbits += MAX_PREFIX_32 + maxBits;
        } else {
            tempbits += result + 1;
            if (k != 1) {
                long sl = (streamlong << (result + 1)) & MASK32;
                int v = (int) (sl >>> (32 - k));
                tempbits += k - 1;
                result = result * m;
                if (v >= 2) {
                    result += v - 1;
                    tempbits += 1;
                }
            }
        }

        bits.bitPos = tempbits;
        return result;
    }

    /** Decode one value with the secondary 16-bit zero-run code (dyn_get). */
    private static int dynGet(AlacBitReader bits, int m, int k) {
        int tempbits = bits.bitPos;
        long streamlong = (bits.read32(tempbits >> 3) << (tempbits & 7)) & MASK32;
        int pre = lead((int) ~streamlong);
        int result;

        if (pre >= MAX_PREFIX_16) {
            pre = MAX_PREFIX_16;
            tempbits += pre;
            long sl = (streamlong << pre) & MASK32;
            result = (int) (sl >>> (32 - MAX_DATATYPE_BITS_16));
            tempbits += MAX_DATATYPE_BITS_16;
        } else {
            tempbits += pre + 1;
            long sl = (streamlong << (pre + 1)) & MASK32;
            int v = k > 0 ? (int) (sl >>> (32 - k)) : 0;
            tempbits += k;
            result = pre * m + v - 1;
            if (v < 2) {
                result -= v - 1;
                tempbits -= 1;
            }
        }

        bits.bitPos = tempbits;
        return result;
    }

    /** Read {@code numBits} (&le; 32) at an arbitrary bit offset (getstreambits). */
    private static int getStreamBits(AlacBitReader bits, int bitOffset, int numBits) {
        int byteOffset = bitOffset / 8;
        long load1 = bits.read32(byteOffset);
        long result;

        if (numBits + (bitOffset & 7) > 32) {
            result = (load1 << (bitOffset & 7)) & MASK32;
            long load2 = bits.byteAt(byteOffset + 4);
            int load2shift = 8 - (numBits + (bitOffset & 7) - 32);
            load2 >>>= load2shift;
            result >>>= (32 - numBits);
            result |= load2;
        } else {
            result = load1 >>> (32 - numBits - (bitOffset & 7));
        }

        if (numBits != 32) {
            result &= ~(MASK32 << numBits);
        }
        return (int) result;
    }

    // ---- Dynamic predictor (dp_dec.c) -----------------------------------------

    /**
     * Reconstruct samples from entropy-decoded residuals using Apple's dynamic
     * predictor. Coefficients adapt with a sign-based LMS rule and are re-read from
     * the bitstream each frame, so no state leaks between frames.
     */
    private static void unpcBlock(int[] pc1, int[] out, int num, short[] coefs,
                                  int numactive, int chanbits, int denshift) {
        int chanshift = 32 - chanbits;
        int denhalf = denshift > 0 ? (1 << (denshift - 1)) : 0;

        out[0] = pc1[0];

        if (numactive == 0) {
            if (num > 1 && pc1 != out) {
                System.arraycopy(pc1, 1, out, 1, num - 1);
            }
            return;
        }

        if (numactive == 31) {
            // Integer-difference mode: integrate the residuals.
            int prev = out[0];
            for (int j = 1; j < num; j++) {
                int del = pc1[j] + prev;
                prev = (del << chanshift) >> chanshift;
                out[j] = prev;
            }
            return;
        }

        // Warm-up: the first 'numactive' samples are simple running sums.
        for (int j = 1; j <= numactive; j++) {
            int del = pc1[j] + out[j - 1];
            out[j] = (del << chanshift) >> chanshift;
        }

        int lim = numactive + 1;
        for (int j = lim; j < num; j++) {
            int top = out[j - lim];
            int sum1 = 0;
            for (int k = 0; k < numactive; k++) {
                sum1 += coefs[k] * (out[j - 1 - k] - top);
            }

            int del = pc1[j];
            int del0 = del;
            int sg = signOf(del);
            del += top + ((sum1 + denhalf) >> denshift);
            out[j] = (del << chanshift) >> chanshift;

            // Sign-based coefficient adaptation with early exit.
            if (sg > 0) {
                for (int k = numactive - 1; k >= 0; k--) {
                    int dd = top - out[j - 1 - k];
                    int sgn = signOf(dd);
                    coefs[k] -= sgn;
                    del0 -= (numactive - k) * ((sgn * dd) >> denshift);
                    if (del0 <= 0) break;
                }
            } else if (sg < 0) {
                for (int k = numactive - 1; k >= 0; k--) {
                    int dd = top - out[j - 1 - k];
                    int sgn = signOf(dd);
                    coefs[k] += sgn;
                    del0 -= (numactive - k) * ((-sgn * dd) >> denshift);
                    if (del0 >= 0) break;
                }
            }
        }
    }

    // ---- Mixing / matrix recombination (matrix_dec.c) -------------------------

    /**
     * Undo Apple's mid-side matrix and fold the uncompressed low bytes back in.
     * On entry {@code samples[0]} is u (mid) and {@code samples[1]} is v (side);
     * on exit they hold full-bit-depth left and right.
     */
    private void unmixStereo(int numSamples, int mixBits, int mixRes, int bytesShifted) {
        int[] u = samples[0];
        int[] v = samples[1];
        int shift = bytesShifted * 8;

        for (int j = 0; j < numSamples; j++) {
            int l, r;
            if (mixRes != 0) {
                l = u[j] + v[j] - ((mixRes * v[j]) >> mixBits);
                r = l - v[j];
            } else {
                l = u[j];
                r = v[j];
            }

            if (bytesShifted != 0) {
                l = (l << shift) | shiftBuffer[2 * j];
                r = (r << shift) | shiftBuffer[2 * j + 1];
            }

            u[j] = l;
            v[j] = r;
        }
    }

    // ---- Fill / data stream elements ------------------------------------------

    private static void skipFillElement(AlacBitReader bits) {
        int count = bits.readBits(4);
        if (count == 15) {
            count += bits.readBits(8) - 1;
        }
        bits.bitPos += count * 8;
    }

    private static void skipDataStreamElement(AlacBitReader bits) {
        bits.readBits(4); // element instance tag
        boolean byteAlign = bits.readBit() != 0;
        int count = bits.readBits(8);
        if (count == 255) {
            count += bits.readBits(8);
        }
        if (byteAlign) {
            bits.bitPos = (bits.bitPos + 7) & ~7;
        }
        bits.bitPos += count * 8;
    }

    // ---- Output ---------------------------------------------------------------

    private void writeInterleaved(ShortBuffer out, int numSamples, int channels) {
        // Shift >16-bit depths down to 16-bit; 16-bit files have shift == 0
        int shift = Math.max(0, bitDepth - 16);
        for (int i = 0; i < numSamples; i++) {
            for (int ch = 0; ch < channels; ch++) {
                int val = samples[ch][i] >> shift;
                if (val > Short.MAX_VALUE) val = Short.MAX_VALUE;
                else if (val < Short.MIN_VALUE) val = Short.MIN_VALUE;
                out.put((short) val);
            }
        }
    }

    // ---- Utility --------------------------------------------------------------

    /** Number of leading zero bits in a 32-bit value (Apple's {@code lead}). */
    private static int lead(int m) {
        return Integer.numberOfLeadingZeros(m);
    }

    /** floor(log2(x + 3)) == 31 - lead(x + 3) (Apple's {@code lg3a}). */
    private static int lg3a(int x) {
        return 31 - lead(x + 3);
    }

    /** -1, 0, or 1 for negative, zero, or positive (Apple's {@code sign_of_int}). */
    private static int signOf(int i) {
        return Integer.compare(i, 0);
    }

    private static int signExtend(int val, int bits) {
        int shift = 32 - bits;
        return (val << shift) >> shift;
    }

    private static int readBigEndianInt(ByteBuffer b) {
        return ((b.get() & 0xFF) << 24)
             | ((b.get() & 0xFF) << 16)
             | ((b.get() & 0xFF) <<  8)
             |  (b.get() & 0xFF);
    }
}
