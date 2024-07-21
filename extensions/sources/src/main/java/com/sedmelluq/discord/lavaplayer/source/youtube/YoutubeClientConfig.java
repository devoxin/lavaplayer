package com.sedmelluq.discord.lavaplayer.source.youtube;

import com.grack.nanojson.JsonWriter;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;

import java.util.HashMap;
import java.util.Map;

@SuppressWarnings("unchecked")
public class YoutubeClientConfig {
    // 28-Feb-2024: https://github.com/yt-dlp/yt-dlp/pull/9317
    public static final String MOBILE_CLIENT_VERSION = "18.11.34"; // 19.07.39
    public static final AndroidVersion DEFAULT_ANDROID_VERSION = AndroidVersion.ANDROID_11;

    // Clients
    public static YoutubeClientConfig ANDROID = new YoutubeClientConfig()
        .withApiKey(YoutubeConstants.INNERTUBE_ANDROID_API_KEY)
        .withUserAgent(String.format("com.google.android.youtube/%s (Linux; U; Android %s) gzip", MOBILE_CLIENT_VERSION, DEFAULT_ANDROID_VERSION.getOsVersion()))
        .withClientName("ANDROID")
        .withClientField("clientVersion", MOBILE_CLIENT_VERSION)
        .withClientField("androidSdkVersion", DEFAULT_ANDROID_VERSION.getSdkVersion())
//        .withClientField("osName", "Android")
//        .withClientField("osVersion", DEFAULT_ANDROID_VERSION.getOsVersion())
//        .withClientField("platform", "MOBILE")
//        .withClientField("hl", "en-US")
//        .withClientField("gl", "US")
        .withUserField("lockedSafetyMode", false);

    public static YoutubeClientConfig IOS = new YoutubeClientConfig()
        .withApiKey(YoutubeConstants.INNERTUBE_IOS_API_KEY)
        .withUserAgent(String.format("com.google.ios.youtube/%s (iPhone14,5; U; CPU iOS 15_6 like Mac OS X)", MOBILE_CLIENT_VERSION)) // 19.07.5
        .withClientName("IOS")
        .withClientField("clientVersion", MOBILE_CLIENT_VERSION) // was 17.36.4, other: 17.39.4, 17.40.5
        .withClientField("osName", "iOS")
        .withClientField("osVersion", "15.6.0.19G71")
        .withClientField("deviceMake", "Apple")
        .withClientField("deviceModel", "iPhone14,5")
        .withClientField("platform", "MOBILE")
        .withClientField("hl", "en-US")
        .withClientField("gl", "US")
        .withUserField("lockedSafetyMode", false);

    public static YoutubeClientConfig TV_EMBEDDED = new YoutubeClientConfig()
        .withApiKey(YoutubeConstants.INNERTUBE_WEB_API_KEY) //.withApiKey(INNERTUBE_TV_API_KEY) // Requires header (Referer tv.youtube.com)
        .withClientName("TVHTML5_SIMPLY_EMBEDDED_PLAYER")
        .withClientField("clientVersion", "2.0");
        //.withClientField("platform", "TV");

    // These may be needed in future for TV client. Not sure about clientScreen one yet.
        //.withUserField("lockedSafetyMode", "false")
        //.withClientField("clientScreen", "EMBED")
        //.withThirdPartyEmbedUrl("https://google.com")

    public static YoutubeClientConfig WEB = new YoutubeClientConfig()
        .withApiKey(YoutubeConstants.INNERTUBE_WEB_API_KEY)
        .withClientName("WEB")
        .withClientField("clientVersion", "2.20240224.11.00") // 2.20220801.00.00
        .withUserField("lockedSafetyMode", false);
//        .withClientField("osName", "Windows")
//        .withClientField("osVersion", "10.0")
//        .withClientField("platform", "DESKTOP");
//        .withClientField("deviceMake", "")
//        .withClientField("deviceModel", "")
//        .withClientField("clientScreen", "WATCH")
//        .withClientField("browserName", "Chrome")
//        .withClientField("browserVersion", "122.0.0.0");
//        .withClientField("visitorData", "...");
//        .withClientField("userAgent", "...");
//        .withClientField("remoteHost", "<client IP>");
//        .withClientField("originalUrl", "https://www.youtube.com");

    public static YoutubeClientConfig MUSIC = new YoutubeClientConfig()
        .withApiKey(YoutubeConstants.INNERTUBE_MUSIC_API_KEY) // Requires header (Referer music.youtube.com)
        .withClientName("WEB_REMIX")
        .withClientField("clientVersion", "1.20220727.01.00"); // 0.1

    // https://github.com/MShawon/YouTube-Viewer/issues/593
    // root.cpn => content playback nonce, a-zA-Z0-9-_ (16 characters)
    // contextPlaybackContext.refer => url (video watch URL?)

    private String name;
    private String userAgent;
    private String apiKey;
    private final Map<String, Object> root;

    public YoutubeClientConfig() {
        this.root = new HashMap<>();
        this.userAgent = null;
        this.name = null;
    }

    private YoutubeClientConfig(Map<String, Object> context, String userAgent, String name) {
        this.root = context;
        this.userAgent = userAgent;
        this.name = name;
    }

    public YoutubeClientConfig copy() {
        return new YoutubeClientConfig(new HashMap<>(this.root), this.userAgent, this.name);
    }

    public YoutubeClientConfig withClientName(String name) {
        this.name = name;
        withClientField("clientName", name);
        return this;
    }

    public String getName() {
        return this.name;
    }

    public YoutubeClientConfig withUserAgent(String userAgent) {
        this.userAgent = userAgent;
        return this;
    }

    public String getUserAgent() {
        return this.userAgent;
    }

    public YoutubeClientConfig withApiKey(String apiKey) {
        this.apiKey = apiKey;
        return this;
    }

    public String getApiKey() {
        return this.apiKey;
    }

    public Map<String, Object> putOnceAndJoin(Map<String, Object> on, String key) {
        return (Map<String, Object>) on.computeIfAbsent(key, __ -> new HashMap<String, Object>());
    }

    public YoutubeClientConfig withClientDefaultScreenParameters() {
        withClientField("screenDensityFloat", 1);
        withClientField("screenHeightPoints", 1080);
        withClientField("screenPixelDensity", 1);
        return withClientField("screenWidthPoints", 1920);
    }

    public YoutubeClientConfig withThirdPartyEmbedUrl(String embedUrl) {
        Map<String, Object> context = putOnceAndJoin(root, "context");
        Map<String, Object> thirdParty = putOnceAndJoin(context, "thirdParty");
        thirdParty.put("embedUrl", embedUrl);
        return this;
    }

    public YoutubeClientConfig withPlaybackSignatureTimestamp(String signatureTimestamp) {
        Map<String, Object> playbackContext = putOnceAndJoin(root, "playbackContext");
        Map<String, Object> contentPlaybackContext = putOnceAndJoin(playbackContext, "contentPlaybackContext");
        contentPlaybackContext.put("signatureTimestamp", signatureTimestamp);
        return this;
    }

    public YoutubeClientConfig withRootField(String key, Object value) {
        root.put(key, value);
        return this;
    }

    public YoutubeClientConfig withClientField(String key, Object value) {
        Map<String, Object> context = putOnceAndJoin(root, "context");
        Map<String, Object> client = putOnceAndJoin(context, "client");
        client.put(key, value);
        return this;
    }

    public YoutubeClientConfig withUserField(String key, Object value) {
        Map<String, Object> context = putOnceAndJoin(root, "context");
        Map<String, Object> user = putOnceAndJoin(context, "user");
        user.put(key, value);
        return this;
    }

    public YoutubeClientConfig setAttributes(HttpInterface httpInterface) {
        if (userAgent != null) {
            httpInterface.getContext().setAttribute(YoutubeHttpContextFilter.ATTRIBUTE_USER_AGENT_SPECIFIED, userAgent);
        }

        return this;
    }

    public String toJsonString() {
        return JsonWriter.string().object(root).done();
    }

    public enum AndroidVersion {
        // https://apilevels.com/
        ANDROID_13("13", 33),
        ANDROID_12("12", 31), // 12L => 32
        ANDROID_11("11", 30);

        private final String osVersion;
        private final int sdkVersion;

        AndroidVersion(String osVersion, int sdkVersion) {
            this.osVersion = osVersion;
            this.sdkVersion = sdkVersion;
        }

        public String getOsVersion() {
            return this.osVersion;
        }

        public int getSdkVersion() {
            return this.sdkVersion;
        }
    }
}
