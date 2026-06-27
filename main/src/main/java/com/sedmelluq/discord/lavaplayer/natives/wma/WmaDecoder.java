package com.sedmelluq.discord.lavaplayer.natives.wma;

/**
 * Pure-Java decoder for Windows Media Audio v1 (0x160) and v2 (0x161).
 *
 * <p>A port of FFmpeg's {@code wma.c} / {@code wmadec.c} (LGPL): an MDCT
 * transform codec with VLC- or LSP-coded exponents, run-length spectral
 * coefficients, optional perceptual noise substitution, mid/side stereo and an
 * optional bit reservoir spanning packets.
 *
 * <p>Each call to {@link #decode} consumes one ASF audio packet ({@code blockAlign}
 * bytes) and produces {@code framesDecoded * frameLen} float samples per channel
 * in {@link #samples} (range roughly [-1, 1]). All working buffers are allocated
 * once in the constructor.
 */
public final class WmaDecoder {
    private static final int BLOCK_MIN_BITS = 7;
    private static final int MAX_CHANNELS = 2;
    private static final int HIGH_BAND_MAX_SIZE = 16;
    private static final int NOISE_TAB_SIZE = 8192;
    private static final int NB_LSP_COEFS = 10;
    private static final int LSP_POW_BITS = 7;
    private static final int MAX_CODED_SUPERFRAME_SIZE = 32768;

    private static final int EXP_OFFSET = 60; // pow_tab is indexed by exponent + 60

    public final int version;
    public final int channels;
    public final int sampleRate;
    public final int frameLen;
    public final float[][] samples;

    private final int frameLenBits;
    private final int nbBlockSizes;

    private final boolean useExpVlc;
    private final boolean useBitReservoir;
    private final boolean useVariableBlockLen;
    private boolean useNoiseCoding;
    private final int coefsStart;
    private final int byteOffsetBits;

    private final int[] exponentSizes;
    private final int[][] exponentBands;
    private final int[] coefsEnd;
    private final int[] highBandStart;
    private final int[] exponentHighSizes;
    private final int[][] exponentHighBands;

    private final float[][] windows;

    private final WmaVlc expVlc;
    private final WmaVlc hgainVlc;
    private final WmaVlc[] coefVlc = new WmaVlc[2];
    private final int[][] runTable = new int[2][];
    private final float[][] levelTable = new float[2][];

    private final WmaImdct imdct;

    private final float[] noiseTable;
    private float noiseMult;
    private int noiseIndex;

    private final float[] powTab;

    // LSP exponent tables
    private final float[] lspCosTable;
    private final float[] lspPowETable = new float[256];
    private final float[] lspPowMTable1 = new float[1 << LSP_POW_BITS];
    private final float[] lspPowMTable2 = new float[1 << LSP_POW_BITS];

    // per-frame working state
    private final WmaBitReader gb = new WmaBitReader();
    private final WmaBitReader reservoirGb = new WmaBitReader();
    private final float[][] exponents;
    private final float[] maxExponent = new float[MAX_CHANNELS];
    private final int[] exponentsBsize = new int[MAX_CHANNELS];
    private final boolean[] exponentsInitialized = new boolean[MAX_CHANNELS];
    private final float[][] coefs1;
    private final float[][] coefs;
    private final float[] output;
    private final float[][] frameOut;
    private final int[][] highBandCoded = new int[MAX_CHANNELS][HIGH_BAND_MAX_SIZE];
    private final int[][] highBandValues = new int[MAX_CHANNELS][HIGH_BAND_MAX_SIZE];
    private final float[] expPower = new float[HIGH_BAND_MAX_SIZE];
    private final boolean[] channelCoded = new boolean[MAX_CHANNELS];
    private final int[] nbCoefs = new int[MAX_CHANNELS];
    private boolean msStereo;

    private int blockLenBits;
    private int nextBlockLenBits;
    private int prevBlockLenBits;
    private int blockLen;
    private int blockPos;
    private boolean resetBlockLengths;

    // bit reservoir state
    private final byte[] lastSuperframe = new byte[MAX_CODED_SUPERFRAME_SIZE + 8];
    private int lastSuperframeLen;
    private int lastBitOffset;

    public WmaDecoder(int sampleRate, int channels, int bitRate, int blockAlign, int version, int flags2) {
        this.version = version;
        this.channels = channels;
        this.sampleRate = sampleRate;

        this.useExpVlc = (flags2 & 0x0001) != 0;
        this.useBitReservoir = (flags2 & 0x0002) != 0;
        this.useVariableBlockLen = (flags2 & 0x0004) != 0;

        // frame length
        int flb;
        if (sampleRate <= 16000) flb = 9;
        else if (sampleRate <= 22050 || (sampleRate <= 32000 && version == 1)) flb = 10;
        else flb = 11; // v1/v2 cap at 48kHz -> 2048
        this.frameLenBits = flb;
        this.frameLen = 1 << flb;
        this.blockLenBits = flb;
        this.nextBlockLenBits = flb;
        this.prevBlockLenBits = flb;

        int nb = 1;
        if (useVariableBlockLen) {
            int v = ((flags2 >> 3) & 3) + 1;
            if ((bitRate / channels) >= 32000) v += 2;
            int nbMax = frameLenBits - BLOCK_MIN_BITS;
            if (v > nbMax) v = nbMax;
            nb = v + 1;
        }
        this.nbBlockSizes = nb;

        // rate-dependent parameters
        useNoiseCoding = true;
        float highFreq = sampleRate * 0.5f;
        int sr1 = sampleRate;
        if (version == 2) {
            if (sr1 >= 44100) sr1 = 44100;
            else if (sr1 >= 22050) sr1 = 22050;
            else if (sr1 >= 16000) sr1 = 16000;
            else if (sr1 >= 11025) sr1 = 11025;
            else if (sr1 >= 8000) sr1 = 8000;
        }
        float bps = (float) bitRate / (float) (channels * sampleRate);
        this.byteOffsetBits = log2(Math.round(bps * frameLen / 8.0f)) + 2;

        float bps1 = bps;
        if (channels == 2) bps1 = bps * 1.6f;
        if (sr1 == 44100) {
            if (bps1 >= 0.61f) useNoiseCoding = false; else highFreq *= 0.4f;
        } else if (sr1 == 22050) {
            if (bps1 >= 1.16f) useNoiseCoding = false;
            else if (bps1 >= 0.72f) highFreq *= 0.7f;
            else highFreq *= 0.6f;
        } else if (sr1 == 16000) {
            highFreq *= bps > 0.5f ? 0.5f : 0.3f;
        } else if (sr1 == 11025) {
            highFreq *= 0.7f;
        } else if (sr1 == 8000) {
            if (bps <= 0.625f) highFreq *= 0.5f;
            else if (bps > 0.75f) useNoiseCoding = false;
            else highFreq *= 0.65f;
        } else {
            if (bps >= 0.8f) highFreq *= 0.75f;
            else if (bps >= 0.6f) highFreq *= 0.6f;
            else highFreq *= 0.5f;
        }

        this.coefsStart = version == 1 ? 3 : 0;

        exponentSizes = new int[nbBlockSizes];
        exponentBands = new int[nbBlockSizes][25];
        coefsEnd = new int[nbBlockSizes];
        highBandStart = new int[nbBlockSizes];
        exponentHighSizes = new int[nbBlockSizes];
        exponentHighBands = new int[nbBlockSizes][HIGH_BAND_MAX_SIZE];
        computeBands(highFreq);

        // sine windows
        windows = new float[nbBlockSizes][];
        for (int i = 0; i < nbBlockSizes; i++) {
            int n = frameLen >> i;
            float[] w = new float[n];
            for (int j = 0; j < n; j++) {
                w[j] = (float) Math.sin((j + 0.5) * (Math.PI / (2.0 * n)));
            }
            windows[i] = w;
        }

        // noise table
        noiseTable = new float[NOISE_TAB_SIZE];
        if (useNoiseCoding) {
            noiseMult = useExpVlc ? 0.02f : 0.04f;
            int seed = 1;
            double norm = (1.0 / (double) (1L << 31)) * Math.sqrt(3) * noiseMult;
            for (int i = 0; i < NOISE_TAB_SIZE; i++) {
                seed = seed * 314159 + 1;
                noiseTable[i] = (float) (seed * norm);
            }
        }

        // coefficient VLC tables
        int cvt = 2;
        if (sampleRate >= 32000) {
            if (bps1 < 0.72f) cvt = 0;
            else if (bps1 < 1.16f) cvt = 1;
        }
        buildCoefTables(0, cvt * 2);
        buildCoefTables(1, cvt * 2 + 1);

        expVlc = useExpVlc ? WmaVlc.fromCodes(WmaData.AAC_SCALEFACTOR_CODE, WmaData.AAC_SCALEFACTOR_BITS) : null;
        hgainVlc = useNoiseCoding
            ? WmaVlc.fromLengths(WmaData.HGAIN_BITS, WmaData.HGAIN_SYMBOLS, -18)
            : null;

        // pow_tab: 10^((i-60)/16) for i in 0..155
        powTab = new float[156];
        for (int i = 0; i < powTab.length; i++) {
            powTab[i] = (float) Math.pow(10.0, (i - EXP_OFFSET) / 16.0);
        }

        // LSP tables
        lspCosTable = new float[frameLen];
        if (!useExpVlc) {
            double wdel = Math.PI / frameLen;
            for (int i = 0; i < frameLen; i++) {
                lspCosTable[i] = (float) (2.0 * Math.cos(wdel * i));
            }
            for (int i = 0; i < 256; i++) {
                lspPowETable[i] = (float) Math.pow(2.0, (i - 126) * -0.25);
            }
            double b = 1.0;
            for (int i = (1 << LSP_POW_BITS) - 1; i >= 0; i--) {
                int m = (1 << LSP_POW_BITS) + i;
                double a = m * (0.5 / (1 << LSP_POW_BITS));
                a = 1.0 / Math.sqrt(Math.sqrt(a));
                lspPowMTable1[i] = (float) (2 * a - b);
                lspPowMTable2[i] = (float) (b - a);
                b = a;
            }
        }

        imdct = new WmaImdct(frameLen, nbBlockSizes);

        for (int i = 0; i < MAX_CHANNELS; i++) maxExponent[i] = 1.0f;
        exponents = new float[MAX_CHANNELS][frameLen];
        coefs1 = new float[MAX_CHANNELS][frameLen];
        coefs = new float[MAX_CHANNELS][frameLen];
        output = new float[frameLen * 2];
        frameOut = new float[MAX_CHANNELS][frameLen * 2];
        samples = new float[channels][frameLen * 16];
        resetBlockLengths = true;
    }

    private void computeBands(float highFreq) {
        int[] cf = WmaData.CRITICAL_FREQS;
        for (int k = 0; k < nbBlockSizes; k++) {
            int blockLen = frameLen >> k;
            if (version == 1) {
                int lpos = 0, i;
                for (i = 0; i < 25; i++) {
                    int a = cf[i], b = sampleRate;
                    int pos = ((blockLen * 2 * a) + (b >> 1)) / b;
                    if (pos > blockLen) pos = blockLen;
                    exponentBands[0][i] = pos - lpos;
                    if (pos >= blockLen) { i++; break; }
                    lpos = pos;
                }
                exponentSizes[0] = i;
            } else {
                // Small blocks at common sample rates use hardcoded band layouts;
                // everything else falls back to the computed critical-band split.
                int[] table = null;
                int a = frameLenBits - BLOCK_MIN_BITS - k;
                if (a >= 0 && a < 3) {
                    if (sampleRate >= 44100) table = WmaData.EXPONENT_BAND_44100[a];
                    else if (sampleRate >= 32000) table = WmaData.EXPONENT_BAND_32000[a];
                    else if (sampleRate >= 22050) table = WmaData.EXPONENT_BAND_22050[a];
                }
                if (table != null) {
                    int n = table[0];
                    for (int i = 0; i < n; i++) exponentBands[k][i] = table[1 + i];
                    exponentSizes[k] = n;
                } else {
                    int j = 0, lpos = 0;
                    for (int i = 0; i < 25; i++) {
                        int a2 = cf[i], b = sampleRate;
                        int pos = ((blockLen * 2 * a2) + (b << 1)) / (4 * b);
                        pos <<= 2;
                        if (pos > blockLen) pos = blockLen;
                        if (pos > lpos) exponentBands[k][j++] = pos - lpos;
                        if (pos >= blockLen) break;
                        lpos = pos;
                    }
                    exponentSizes[k] = j;
                }
            }
            coefsEnd[k] = (frameLen - ((frameLen * 9) / 100)) >> k;
            highBandStart[k] = (int) ((blockLen * 2 * highFreq) / sampleRate + 0.5);
            int n = exponentSizes[k];
            int j = 0, pos = 0;
            for (int i = 0; i < n; i++) {
                int start = pos;
                pos += exponentBands[k][i];
                int end = pos;
                if (start < highBandStart[k]) start = highBandStart[k];
                if (end > coefsEnd[k]) end = coefsEnd[k];
                if (end > start) exponentHighBands[k][j++] = end - start;
            }
            exponentHighSizes[k] = j;
        }
    }

    private void buildCoefTables(int slot, int tableIndex) {
        int[] codes;
        byte[] bits;
        int[] levels;
        switch (tableIndex) {
            case 0: codes = WmaData.COEF0_CODES; bits = WmaData.COEF0_BITS; levels = WmaData.LEVELS0; break;
            case 1: codes = WmaData.COEF1_CODES; bits = WmaData.COEF1_BITS; levels = WmaData.LEVELS1; break;
            case 2: codes = WmaData.COEF2_CODES; bits = WmaData.COEF2_BITS; levels = WmaData.LEVELS2; break;
            case 3: codes = WmaData.COEF3_CODES; bits = WmaData.COEF3_BITS; levels = WmaData.LEVELS3; break;
            case 4: codes = WmaData.COEF4_CODES; bits = WmaData.COEF4_BITS; levels = WmaData.LEVELS4; break;
            default: codes = WmaData.COEF5_CODES; bits = WmaData.COEF5_BITS; levels = WmaData.LEVELS5; break;
        }
        coefVlc[slot] = WmaVlc.fromCodes(codes, bits);
        int n = bits.length;
        int[] run = new int[n];
        float[] level = new float[n];
        int i = 2, lvl = 1, k = 0;
        while (i < n) {
            int l = levels[k++];
            for (int j = 0; j < l; j++) {
                run[i] = j;
                level[i] = lvl;
                i++;
            }
            lvl++;
        }
        runTable[slot] = run;
        levelTable[slot] = level;
    }

    /** Reset all inter-frame state. Call after seeking before decoding resumes. */
    public void reset() {
        for (int ch = 0; ch < MAX_CHANNELS; ch++) {
            java.util.Arrays.fill(frameOut[ch], 0f);
            exponentsInitialized[ch] = false;
            exponentsBsize[ch] = 0;
            maxExponent[ch] = 1.0f;
        }
        noiseIndex = 0;
        lastSuperframeLen = 0;
        lastBitOffset = 0;
        resetBlockLengths = true;
        blockLenBits = frameLenBits;
        nextBlockLenBits = frameLenBits;
        prevBlockLenBits = frameLenBits;
    }

    /**
     * Decode one ASF audio packet. Returns the number of decoded frames; the
     * samples are written into {@link #samples} as {@code framesDecoded * frameLen}
     * values per channel. Returns 0 when the packet was only buffered (reservoir).
     */
    public int decode(byte[] buf, int offset, int length) {
        gb.reset(buf, offset, length);

        int nbFrames;
        if (useBitReservoir) {
            gb.skip(4); // superframe index
            nbFrames = gb.getBits(4) - (lastSuperframeLen <= 0 ? 1 : 0);
            if (nbFrames <= 0) {
                // entire packet is reservoir spill for the next superframe
                int q = lastSuperframeLen;
                int len = length - 1;
                while (len > 0) {
                    lastSuperframe[q++] = (byte) gb.getBits(8);
                    len--;
                }
                lastSuperframeLen += 8 * length - 8;
                return 0;
            }
        } else {
            nbFrames = 1;
        }

        int framesOut = 0;
        if (useBitReservoir) {
            int bitOffset = gb.getBits(byteOffsetBits + 3);

            if (lastSuperframeLen > 0) {
                int q = lastSuperframeLen;
                int len = bitOffset;
                while (len > 7) {
                    lastSuperframe[q++] = (byte) gb.getBits(8);
                    len -= 8;
                }
                if (len > 0) {
                    lastSuperframe[q++] = (byte) (gb.getBits(len) << (8 - len));
                }
                reservoirGb.reset(lastSuperframe, 0, lastSuperframeLen + ((bitOffset + 7) >> 3));
                reservoirGb.skip(lastBitOffset > 0 ? lastBitOffset : 0);
                decodeFrameInto(reservoirGb, framesOut);
                framesOut++;
                nbFrames--;
            }

            // first full frame starts bitOffset bits after the superframe header
            int pos = bitOffset + 4 + 4 + byteOffsetBits + 3;
            gb.position(offset * 8 + pos);

            resetBlockLengths = true;
            for (int i = 0; i < nbFrames; i++) {
                decodeFrameInto(gb, framesOut);
                framesOut++;
            }

            // the unconsumed tail of this superframe carries into the next one
            int posBits = gb.position() - offset * 8;
            lastBitOffset = posBits & 7;
            int bytePos = posBits >> 3;
            int len = length - bytePos;
            if (len < 0 || len > MAX_CODED_SUPERFRAME_SIZE) {
                lastSuperframeLen = 0;
            } else {
                lastSuperframeLen = len;
                System.arraycopy(buf, offset + bytePos, lastSuperframe, 0, len);
            }
        } else {
            decodeFrameInto(gb, 0);
            framesOut = 1;
        }

        return framesOut;
    }

    private void decodeFrameInto(WmaBitReader bits, int frameIndex) {
        blockPos = 0;
        while (decodeBlock(bits) == 0) {
            // continue
        }
        int sampleOffset = frameIndex * frameLen;
        for (int ch = 0; ch < channels; ch++) {
            System.arraycopy(frameOut[ch], 0, samples[ch], sampleOffset, frameLen);
            System.arraycopy(frameOut[ch], frameLen, frameOut[ch], 0, frameLen);
            java.util.Arrays.fill(frameOut[ch], frameLen, frameLen * 2, 0f);
        }
    }

    private int decodeBlock(WmaBitReader bits) {
        // block length
        if (useVariableBlockLen) {
            int n = log2(nbBlockSizes - 1) + 1;
            if (resetBlockLengths) {
                resetBlockLengths = false;
                int v = bits.getBits(n);
                if (v >= nbBlockSizes) return -1;
                prevBlockLenBits = frameLenBits - v;
                v = bits.getBits(n);
                if (v >= nbBlockSizes) return -1;
                blockLenBits = frameLenBits - v;
            } else {
                prevBlockLenBits = blockLenBits;
                blockLenBits = nextBlockLenBits;
            }
            int v = bits.getBits(n);
            if (v >= nbBlockSizes) return -1;
            nextBlockLenBits = frameLenBits - v;
        } else {
            nextBlockLenBits = frameLenBits;
            prevBlockLenBits = frameLenBits;
            blockLenBits = frameLenBits;
        }

        if (frameLenBits - blockLenBits >= nbBlockSizes) return -1;

        blockLen = 1 << blockLenBits;
        if ((blockPos + blockLen) > frameLen) return -1;

        msStereo = false;
        if (channels == 2) {
            msStereo = bits.getBits1() != 0;
        }
        boolean anyCoded = false;
        for (int ch = 0; ch < channels; ch++) {
            boolean a = bits.getBits1() != 0;
            channelCoded[ch] = a;
            anyCoded |= a;
        }

        int bsize = frameLenBits - blockLenBits;

        if (!anyCoded) {
            return runImdctAndAdvance(bsize);
        }

        int totalGain = 1;
        for (;;) {
            int a = bits.getBits(7);
            totalGain += a;
            if (a != 127) break;
        }
        int coefNbBits = totalGainToBits(totalGain);

        int n = coefsEnd[bsize] - coefsStart;
        for (int ch = 0; ch < channels; ch++) nbCoefs[ch] = n;

        if (useNoiseCoding) {
            for (int ch = 0; ch < channels; ch++) {
                if (channelCoded[ch]) {
                    int nh = exponentHighSizes[bsize];
                    for (int i = 0; i < nh; i++) {
                        int a = bits.getBits1();
                        highBandCoded[ch][i] = a;
                        if (a != 0) nbCoefs[ch] -= exponentHighBands[bsize][i];
                    }
                }
            }
            for (int ch = 0; ch < channels; ch++) {
                if (channelCoded[ch]) {
                    int nh = exponentHighSizes[bsize];
                    int val = 0x80000000;
                    for (int i = 0; i < nh; i++) {
                        if (highBandCoded[ch][i] != 0) {
                            if (val == 0x80000000) {
                                val = bits.getBits(7) - 19;
                            } else {
                                val += hgainVlc.decode(bits);
                            }
                            highBandValues[ch][i] = val;
                        }
                    }
                }
            }
        }

        // exponents
        if ((blockLenBits == frameLenBits) || bits.getBits1() != 0) {
            for (int ch = 0; ch < channels; ch++) {
                if (channelCoded[ch]) {
                    if (useExpVlc) {
                        if (decodeExpVlc(bits, ch) < 0) return -1;
                    } else {
                        decodeExpLsp(bits, ch);
                    }
                    exponentsBsize[ch] = bsize;
                    exponentsInitialized[ch] = true;
                }
            }
        }
        for (int ch = 0; ch < channels; ch++) {
            if (channelCoded[ch] && !exponentsInitialized[ch]) return -1;
        }

        // spectral coefficients
        for (int ch = 0; ch < channels; ch++) {
            if (channelCoded[ch]) {
                int tindex = (ch == 1 && msStereo) ? 1 : 0;
                java.util.Arrays.fill(coefs1[ch], 0, blockLen, 0f);
                runLevelDecode(bits, coefVlc[tindex], levelTable[tindex], runTable[tindex],
                        coefs1[ch], nbCoefs[ch], blockLen, coefNbBits);
            }
            if (version == 1 && channels >= 2) bits.align();
        }

        int n4 = blockLen / 2;
        float mdctNorm = 1.0f / n4;
        if (version == 1) mdctNorm *= (float) Math.sqrt(n4);

        for (int ch = 0; ch < channels; ch++) {
            if (channelCoded[ch]) {
                dequant(ch, bsize, totalGain, mdctNorm);
            }
        }

        if (msStereo && channelCoded[1]) {
            if (!channelCoded[0]) {
                java.util.Arrays.fill(coefs[0], 0, blockLen, 0f);
                channelCoded[0] = true;
            }
            float[] a = coefs[0], b = coefs[1];
            for (int i = 0; i < blockLen; i++) {
                float t = a[i];
                a[i] = t + b[i];
                b[i] = t - b[i];
            }
        }

        return runImdctAndAdvance(bsize);
    }

    private void dequant(int ch, int bsize, int totalGain, float mdctNorm) {
        float[] exps = exponents[ch];
        int esize = exponentsBsize[ch];
        float mult = (float) (Math.pow(10.0, totalGain * 0.05) / maxExponent[ch]) * mdctNorm;
        float[] c = coefs[ch];
        float[] c1 = coefs1[ch];

        if (useNoiseCoding) {
            int ci = 0;
            int c1i = 0;
            for (int i = 0; i < coefsStart; i++) {
                c[ci++] = noiseTable[noiseIndex] * exps[(i << bsize) >> esize] * mult;
                noiseIndex = (noiseIndex + 1) & (NOISE_TAB_SIZE - 1);
            }
            int n1 = exponentHighSizes[bsize];
            int[] hb = exponentHighBands[frameLenBits - blockLenBits];

            int expPtr = (highBandStart[bsize] << bsize) >> esize;
            int lastHighBand = 0;
            for (int j = 0; j < n1; j++) {
                int nn = hb[j];
                if (highBandCoded[ch][j] != 0) {
                    float e2 = 0;
                    for (int i = 0; i < nn; i++) {
                        float v = exps[expPtr + ((i << bsize) >> esize)];
                        e2 += v * v;
                    }
                    expPower[j] = e2 / nn;
                    lastHighBand = j;
                }
                expPtr += (nn << bsize) >> esize;
            }

            expPtr = (coefsStart << bsize) >> esize;
            for (int j = -1; j < n1; j++) {
                int nn;
                if (j < 0) nn = highBandStart[bsize] - coefsStart;
                else nn = hb[j];
                if (j >= 0 && highBandCoded[ch][j] != 0) {
                    float m1 = (float) Math.sqrt(expPower[j] / expPower[lastHighBand]);
                    m1 *= (float) Math.pow(10.0, highBandValues[ch][j] * 0.05);
                    m1 /= (maxExponent[ch] * noiseMult);
                    m1 *= mdctNorm;
                    for (int i = 0; i < nn; i++) {
                        float noise = noiseTable[noiseIndex];
                        noiseIndex = (noiseIndex + 1) & (NOISE_TAB_SIZE - 1);
                        c[ci++] = noise * exps[expPtr + ((i << bsize) >> esize)] * m1;
                    }
                    expPtr += (nn << bsize) >> esize;
                } else {
                    for (int i = 0; i < nn; i++) {
                        float noise = noiseTable[noiseIndex];
                        noiseIndex = (noiseIndex + 1) & (NOISE_TAB_SIZE - 1);
                        c[ci++] = (c1[c1i++] + noise) * exps[expPtr + ((i << bsize) >> esize)] * mult;
                    }
                    expPtr += (nn << bsize) >> esize;
                }
            }

            int nn = blockLen - coefsEnd[bsize];
            float m1 = mult * exps[expPtr + ((-(1 << bsize)) >> esize)];
            for (int i = 0; i < nn; i++) {
                c[ci++] = noiseTable[noiseIndex] * m1;
                noiseIndex = (noiseIndex + 1) & (NOISE_TAB_SIZE - 1);
            }
        } else {
            for (int i = 0; i < coefsStart; i++) c[i] = 0f;
            int nn = nbCoefs[ch];
            for (int i = 0; i < nn; i++) {
                c[coefsStart + i] = c1[i] * exps[(i << bsize) >> esize] * mult;
            }
            for (int i = coefsEnd[bsize]; i < blockLen; i++) c[i] = 0f;
        }
    }

    private int runImdctAndAdvance(int bsize) {
        for (int ch = 0; ch < channels; ch++) {
            int n4 = blockLen / 2;
            if (channelCoded[ch]) {
                imdct.imdct(bsize, coefs[ch], output, 1.0f / 32768.0f);
            } else if (!(msStereo && ch == 1)) {
                java.util.Arrays.fill(output, 0, blockLen * 2, 0f);
            }
            int index = (frameLen / 2) + blockPos - n4;
            window(frameOut[ch], index);
        }

        blockPos += blockLen;
        return blockPos >= frameLen ? 1 : 0;
    }

    private void window(float[] frame, int index) {
        float[] in = output;
        int bLen, bsize, n;
        // left part
        if (blockLenBits <= prevBlockLenBits) {
            bLen = blockLen;
            bsize = frameLenBits - blockLenBits;
            float[] w = windows[bsize];
            for (int i = 0; i < bLen; i++) {
                frame[index + i] += in[i] * w[i];
            }
        } else {
            bLen = 1 << prevBlockLenBits;
            n = (blockLen - bLen) / 2;
            bsize = frameLenBits - prevBlockLenBits;
            float[] w = windows[bsize];
            for (int i = 0; i < bLen; i++) {
                frame[index + n + i] += in[n + i] * w[i];
            }
            for (int i = 0; i < n; i++) {
                frame[index + n + bLen + i] = in[n + bLen + i];
            }
        }

        int outBase = index + blockLen;
        int inBase = blockLen;
        // right part
        if (blockLenBits <= nextBlockLenBits) {
            bLen = blockLen;
            bsize = frameLenBits - blockLenBits;
            float[] w = windows[bsize];
            for (int i = 0; i < bLen; i++) {
                frame[outBase + i] = in[inBase + i] * w[bLen - 1 - i];
            }
        } else {
            bLen = 1 << nextBlockLenBits;
            n = (blockLen - bLen) / 2;
            bsize = frameLenBits - nextBlockLenBits;
            float[] w = windows[bsize];
            for (int i = 0; i < n; i++) {
                frame[outBase + i] = in[inBase + i];
            }
            for (int i = 0; i < bLen; i++) {
                frame[outBase + n + i] = in[inBase + n + i] * w[bLen - 1 - i];
            }
            for (int i = 0; i < n; i++) {
                frame[outBase + n + bLen + i] = 0f;
            }
        }
    }

    private int decodeExpVlc(WmaBitReader bits, int ch) {
        int[] bands = exponentBands[frameLenBits - blockLenBits];
        int bptr = 0;
        float[] exps = exponents[ch];
        int q = 0;
        int qEnd = blockLen;
        float maxScale = 0;
        int lastExp;
        if (version == 1) {
            lastExp = bits.getBits(5) + 10;
            float v = powTab[EXP_OFFSET + lastExp];
            maxScale = v;
            int nn = bands[bptr++];
            for (int i = 0; i < nn; i++) exps[q++] = v;
        } else {
            lastExp = 36;
        }
        while (q < qEnd) {
            int code = expVlc.decode(bits);
            lastExp += code - 60;
            if (lastExp + EXP_OFFSET >= powTab.length || lastExp + EXP_OFFSET < 0) {
                return -1;
            }
            float v = powTab[EXP_OFFSET + lastExp];
            if (v > maxScale) maxScale = v;
            int nn = bands[bptr++];
            for (int i = 0; i < nn; i++) exps[q++] = v;
        }
        maxExponent[ch] = maxScale;
        return 0;
    }

    private void decodeExpLsp(WmaBitReader bits, int ch) {
        float[] lsp = new float[NB_LSP_COEFS];
        for (int i = 0; i < NB_LSP_COEFS; i++) {
            int val;
            if (i == 0 || i >= 8) val = bits.getBits(3);
            else val = bits.getBits(4);
            lsp[i] = WmaData.LSP_CODEBOOK[i][val];
        }
        lspToCurve(ch, lsp);
    }

    private void lspToCurve(int ch, float[] lsp) {
        float[] out = exponents[ch];
        int n = blockLen;
        float valMax = 0;
        for (int i = 0; i < n; i++) {
            float p = 0.5f, q = 0.5f;
            float w = lspCosTable[i];
            for (int j = 1; j < NB_LSP_COEFS; j += 2) {
                q *= w - lsp[j - 1];
                p *= w - lsp[j];
            }
            p *= p * (2.0f - w);
            q *= q * (2.0f + w);
            float v = powM14(p + q);
            if (v > valMax) valMax = v;
            out[i] = v;
        }
        maxExponent[ch] = valMax;
    }

    private float powM14(float x) {
        int bits = Float.floatToRawIntBits(x);
        int e = bits >>> 23;
        int m = (bits >>> (23 - LSP_POW_BITS)) & ((1 << LSP_POW_BITS) - 1);
        int tBits = ((bits << LSP_POW_BITS) & ((1 << 23) - 1)) | (127 << 23);
        float t = Float.intBitsToFloat(tBits);
        float a = lspPowMTable1[m];
        float b = lspPowMTable2[m];
        return lspPowETable[e] * (a + b * t);
    }

    private void runLevelDecode(WmaBitReader bits, WmaVlc vlc, float[] levels, int[] runs,
                                float[] ptr, int numCoefs, int blockLen, int coefNbBits) {
        int coefMask = blockLen - 1;
        int offset = 0;
        while (offset < numCoefs) {
            int code = vlc.decode(bits);
            if (code > 1) {
                offset += runs[code];
                int sign = bits.getBits1();
                float v = levels[code];
                ptr[offset & coefMask] = sign != 0 ? -v : v;
            } else if (code == 1) {
                break;
            } else {
                int level = bits.getBits(coefNbBits);
                offset += bits.getBits(frameLenBits);
                int sign = bits.getBits1();
                ptr[offset & coefMask] = sign != 0 ? -level : level;
            }
            offset++;
        }
    }

    private static int totalGainToBits(int totalGain) {
        if (totalGain < 15) return 13;
        if (totalGain < 32) return 12;
        if (totalGain < 40) return 11;
        if (totalGain < 45) return 10;
        return 9;
    }

    private static int log2(int v) {
        return v <= 0 ? 0 : 31 - Integer.numberOfLeadingZeros(v);
    }
}
