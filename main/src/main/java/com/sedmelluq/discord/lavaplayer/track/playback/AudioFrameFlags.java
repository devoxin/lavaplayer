package com.sedmelluq.discord.lavaplayer.track.playback;

public class AudioFrameFlags {
    /**
     * This flag is applied when the input format is the same as the output format (and both are Opus),
     * and there are no transcoding pipeline operations, such as resampling, filters or volume changes.
     */
    public static String OPUS_PASSTHROUGH = "OPUS_PASSTHROUGH";
}
