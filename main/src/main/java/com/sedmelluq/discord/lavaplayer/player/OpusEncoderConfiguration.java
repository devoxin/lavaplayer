package com.sedmelluq.discord.lavaplayer.player;

import com.sedmelluq.discord.lavaplayer.natives.opus.OpusEncoder;
import com.sedmelluq.discord.lavaplayer.natives.opus.OpusEncoderLibrary;

import java.util.HashMap;
import java.util.Map;

public class OpusEncoderConfiguration {
    private final Map<Integer, Integer> rawConfiguration = new HashMap<>();

    /**
     * Controls whether VBR will be enabled for the encoder.
     * In VBR mode the bitrate may go up and down freely depending on the content to achieve more consistent quality.
     * @param vbr True, if VBR should be used.
     * @return This, useful for chaining.
     */
    public OpusEncoderConfiguration setVbr(boolean vbr) {
        configureRaw(OpusEncoderLibrary.SET_VBR_REQUEST, vbr ? 1 : 0);
        return this;
    }

    /**
     * Controls whether VBR constraint will be enabled for the encoder.
     * Outputs to a specific bitrate. This mode is analogous to CBR in AAC/MP3 encoders and managed mode in vorbis coders.
     * This delivers less consistent quality than VBR mode but consistent bitrate.
     * @param constrained True, if VBR constraint should be used.
     * @return This, useful for chaining.
     */
    public OpusEncoderConfiguration setVbrConstraint(boolean constrained) {
        configureRaw(OpusEncoderLibrary.SET_VBR_CONSTRAINT_REQUEST, constrained ? 1 : 0);
        return this;
    }

    /**
     * Sets the target bitrate for the encoder.
     * When a target bitrate is set, it's recommended to enable {@link #setVbrConstraint},
     * or disable {@link #setVbr}.
     * @param bps The target bitrate, in bits per second. There is an upper limit of 510Kbps imposed for 2-channel Opus.
     *            You may use {@link OpusEncoderLibrary#OPUS_BITRATE_MAX} to have the encoder calculate the
     *            maximum bitrate allowed based on the encoder initialisation parameters.
     * @return This, useful for chaining.
     */
    public OpusEncoderConfiguration setBitrate(int bps) {
        configureRaw(OpusEncoderLibrary.SET_BITRATE_REQUEST, bps);
        return this;
    }

    /**
     * Apply a specific Opus encoder configuration that might not be offered by this class.
     * This should only be used if you know what you're doing, otherwise you risk misconfiguring
     * the encoder which can harm performance, or at worst, hinder the encoder's ability to produce
     * opus samples.
     * Refer to <a href="https://github.com/xiph/opus/blob/32d4d874accd322f7e237b734678542fea88393b/include/opus_defines.h#L130-L175">Opus encoder CTLs</a>.
     * Note that, this function may only be used for requests that expect a single, int32 value.
     * @param request The request ID.
     * @param value The request value.
     * @return This, useful for chaining.
     */
    public OpusEncoderConfiguration configureRaw(int request, int value) {
        this.rawConfiguration.put(request, value);
        return this;
    }

    /**
     * Applies the configuration of the other OpusEncoderConfiguration to this one.
     * Existing values that aren't present in the other configuration are preserved,
     * otherwise, they are overwritten.
     * @param other The other opus encoder configuration to copy.
     * @return This, useful for chaining.
     */
    public OpusEncoderConfiguration copyFrom(OpusEncoderConfiguration other) {
        other.rawConfiguration.putAll(this.rawConfiguration);
        return this;
    }

    public OpusEncoderConfiguration clear() {
        this.rawConfiguration.clear();
        return this;
    }

    public void apply(OpusEncoder encoder) {
        for (Map.Entry<Integer, Integer> entry : rawConfiguration.entrySet()) {
            int ret = encoder.configure(entry.getKey(), entry.getValue());

            if (ret != OpusEncoderLibrary.OPUS_OK) {
                throw new IllegalStateException("configure encoder failed with error " + ret);
            }
        }
    }
}
