package com.sedmelluq.discord.lavaplayer.source.soundcloud;

import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;

public class SoundcloudAudioTrackInfo extends AudioTrackInfo {
    /**
     * The monetization model of this track.
     * For Go+ tracks, at the time of writing this is "SUB_HIGH_TIER".
     */
    public final String monetizationModel;

    /**
     * If this track contains any snipped playback streams.
     * This is typically true for Go+ tracks that are restricted to a 30-second preview.
     */
    public final boolean snipped;

    public SoundcloudAudioTrackInfo(
        String title,
        String author,
        long length,
        String identifier,
        boolean isStream,
        String uri,
        String monetizationModel,
        boolean snipped
    ) {
        super(title, author, length, identifier, isStream, uri);

        this.monetizationModel = monetizationModel;
        this.snipped = snipped;
    }
}
