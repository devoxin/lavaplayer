package com.sedmelluq.discord.lavaplayer.natives.wma;

/**
 * Full inverse MDCT (N coefficients to 2N time samples) used by the WMA decoder.
 *
 * <p>Implemented as a DCT-IV followed by the standard MDCT sign-fold unfolding,
 * with the DCT-IV evaluated through a 2N-point radix-2 complex FFT. All scratch
 * and twiddle buffers are preallocated per block size so {@link #imdct} performs
 * no allocation.
 */
final class WmaImdct {
    private final int nbSizes;
    private final int[] n;            // coefficient count per block-size index
    private final float[][] twCos;    // DCT-IV pre/post twiddle, angle pi(4k+1)/(8N)
    private final float[][] twSin;
    private final float[][] fftCos;   // FFT roots cos(2*pi*t/L), t in [0, L/2)
    private final float[][] fftSin;
    private final int[][] bitrev;     // bit-reversal permutation for size L = 2N

    private final float[] re;         // scratch, sized 2 * maxN
    private final float[] im;

    WmaImdct(int frameLen, int nbBlockSizes) {
        nbSizes = nbBlockSizes;
        n = new int[nbSizes];
        twCos = new float[nbSizes][];
        twSin = new float[nbSizes][];
        fftCos = new float[nbSizes][];
        fftSin = new float[nbSizes][];
        bitrev = new int[nbSizes][];

        int maxN = frameLen;
        for (int s = 0; s < nbSizes; s++) {
            int nn = frameLen >> s;
            n[s] = nn;
            int len = 2 * nn; // FFT size

            float[] tc = new float[nn];
            float[] ts = new float[nn];
            for (int k = 0; k < nn; k++) {
                double ang = Math.PI * (4 * k + 1) / (8.0 * nn);
                tc[k] = (float) Math.cos(ang);
                ts[k] = (float) Math.sin(ang);
            }
            twCos[s] = tc;
            twSin[s] = ts;

            float[] fc = new float[len / 2];
            float[] fs = new float[len / 2];
            for (int t = 0; t < len / 2; t++) {
                double ang = 2.0 * Math.PI * t / len;
                fc[t] = (float) Math.cos(ang);
                fs[t] = (float) Math.sin(ang);
            }
            fftCos[s] = fc;
            fftSin[s] = fs;

            bitrev[s] = buildBitrev(len);
        }

        re = new float[2 * maxN];
        im = new float[2 * maxN];
    }

    private static int[] buildBitrev(int len) {
        int[] rev = new int[len];
        int j = 0;
        for (int i = 1; i < len; i++) {
            int bit = len >> 1;
            for (; (j & bit) != 0; bit >>= 1) {
                j ^= bit;
            }
            j |= bit;
            rev[i] = j;
        }
        return rev;
    }

    /**
     * Compute the full inverse MDCT of {@code coefs} (the first N entries) into
     * {@code out} (first 2N entries), multiplied by {@code scale}.
     */
    void imdct(int bsize, float[] coefs, float[] out, float scale) {
        int nn = n[bsize];
        int len = 2 * nn;
        float[] tc = twCos[bsize];
        float[] ts = twSin[bsize];

        // pre-twiddle into complex, upper half zeroed
        for (int k = 0; k < nn; k++) {
            float x = coefs[k];
            re[k] = x * tc[k];
            im[k] = x * ts[k];
        }
        for (int k = nn; k < len; k++) {
            re[k] = 0f;
            im[k] = 0f;
        }

        fft(bsize, len);

        // post-twiddle -> DCT-IV result z[m], then sign-fold unfold into out
        int h = nn / 2;
        for (int m = 0; m < nn; m++) {
            float zr = re[m] * tc[m] - im[m] * ts[m];
            re[m] = zr * scale; // stash z*scale in re for the unfold below
        }
        // y[n] = z[n+h]            for n in [0, h)
        // y[n] = -z[3h-1-n]        for n in [h, 3h)
        // y[n] = -z[n-3h]          for n in [3h, 2N)
        for (int i = 0; i < h; i++) {
            out[i] = re[i + h];
        }
        for (int i = h; i < 3 * h; i++) {
            out[i] = -re[3 * h - 1 - i];
        }
        for (int i = 3 * h; i < len; i++) {
            out[i] = -re[i - 3 * h];
        }
    }

    /** In-place inverse-sign (exp(+i...)) radix-2 FFT of {@code len} points. */
    private void fft(int bsize, int len) {
        int[] rev = bitrev[bsize];
        float[] fc = fftCos[bsize];
        float[] fs = fftSin[bsize];

        for (int i = 1; i < len; i++) {
            int j = rev[i];
            if (i < j) {
                float tr = re[i]; re[i] = re[j]; re[j] = tr;
                float ti = im[i]; im[i] = im[j]; im[j] = ti;
            }
        }

        for (int size = 2; size <= len; size <<= 1) {
            int half = size >> 1;
            int step = len / size; // index stride into the root tables
            for (int i = 0; i < len; i += size) {
                int ti = 0;
                for (int k = 0; k < half; k++) {
                    int a = i + k;
                    int b = a + half;
                    float wr = fc[ti];
                    float wi = fs[ti]; // +sign (inverse transform)
                    float xr = re[b] * wr - im[b] * wi;
                    float xi = re[b] * wi + im[b] * wr;
                    re[b] = re[a] - xr;
                    im[b] = im[a] - xi;
                    re[a] += xr;
                    im[a] += xi;
                    ti += step;
                }
            }
        }
    }
}
