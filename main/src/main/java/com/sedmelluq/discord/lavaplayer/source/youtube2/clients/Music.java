package com.sedmelluq.discord.lavaplayer.source.youtube2.clients;

import com.sedmelluq.discord.lavaplayer.source.youtube.YoutubeClientConfig;
import com.sedmelluq.discord.lavaplayer.source.youtube2.clients.skeleton.MusicClient;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;

public class Music extends MusicClient {
    protected YoutubeClientConfig baseConfig = new YoutubeClientConfig()
        .withApiKey("AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30") // Requires header (Referer music.youtube.com)
        .withClientName("WEB_REMIX")
        .withClientField("clientVersion", "1.20240401.00.00");

    @Override
    public YoutubeClientConfig getBaseClientConfig(HttpInterface httpInterface) {
        return baseConfig.copy();
    }

    @Override
    public String getPlayerParams() {
        // This client is not used for format loading so, we don't have
        // any player parameters attached to it.
        throw new UnsupportedOperationException();
    }

    @Override
    public String getIdentifier() {
        return baseConfig.getName();
    }
}
