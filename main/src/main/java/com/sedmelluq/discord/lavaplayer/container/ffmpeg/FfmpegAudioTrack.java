package com.sedmelluq.discord.lavaplayer.container.ffmpeg;

import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.BaseAudioTrack;
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FfmpegAudioTrack extends BaseAudioTrack {
    private static final Logger log = LoggerFactory.getLogger(FfmpegAudioTrack.class);

    /**
     * @param trackInfo Track info
     */
    public FfmpegAudioTrack(AudioTrackInfo trackInfo) {
        super(trackInfo);
    }

    @Override
    public void process(LocalAudioTrackExecutor localExecutor) throws Exception {
        try (FfmpegTrackProvider provider = new FfmpegTrackProvider(localExecutor.getProcessingContext(), trackInfo.uri)) {
            log.debug("Starting to play FFMPEG track {}", getIdentifier());
            localExecutor.executeProcessingLoop(provider::provideFrames, provider::seekToTimecode);
        }
    }
}
