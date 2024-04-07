package com.sedmelluq.discord.lavaplayer.source.youtube2.clients;

import com.sedmelluq.discord.lavaplayer.source.youtube.YoutubeClientConfig;
import com.sedmelluq.discord.lavaplayer.source.youtube2.clients.skeleton.StreamingNonMusicClient;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;

public class Android extends StreamingNonMusicClient {
    protected static String CLIENT_VERSION = "19.07.39";
    protected AndroidVersion ANDROID_VERSION = AndroidVersion.ANDROID_11;

    protected YoutubeClientConfig baseConfig = new YoutubeClientConfig()
        .withApiKey("AIzaSyA8eiZmM1FaDVjRy-df2KTyQ_vz_yYM39w")
        .withUserAgent(String.format("com.google.android.youtube/%s (Linux; U; Android %s) gzip", CLIENT_VERSION, ANDROID_VERSION.osVersion))
        .withClientName("ANDROID")
        .withClientField("clientVersion", CLIENT_VERSION)
        .withClientField("androidSdkVersion", ANDROID_VERSION.sdkVersion)
        .withUserField("lockedSafetyMode", false);

    @Override
    protected YoutubeClientConfig getBaseClientConfig(HttpInterface httpInterface) {
        return baseConfig.copy();
    }

    @Override
    public String getPlayerParams() {
        return MOBILE_PLAYER_PARAMS;
    }

    @Override
    public String getIdentifier() {
        return baseConfig.getName();
    }

    public enum AndroidVersion {
        // https://apilevels.com/
        ANDROID_13("13", 33),
        ANDROID_12("12", 31), // 12L => 32
        ANDROID_11("11", 30);

        public final String osVersion;
        public final int sdkVersion;

        AndroidVersion(String osVersion, int sdkVersion) {
            this.osVersion = osVersion;
            this.sdkVersion = sdkVersion;
        }
    }
}
