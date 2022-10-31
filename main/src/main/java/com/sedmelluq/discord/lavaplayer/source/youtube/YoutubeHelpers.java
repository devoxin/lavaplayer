package com.sedmelluq.discord.lavaplayer.source.youtube;

import java.util.Random;

public class YoutubeHelpers {
    private static final String CONTENT_PLAYBACK_NONCE_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";

    private static final Random random = new Random();

    /**
     * Generates a random, 16-character string using characters from {@link #CONTENT_PLAYBACK_NONCE_ALPHABET}.
     */
    public static String generateContentPlaybackNonce() {
        final StringBuilder stringBuilder = new StringBuilder(16);

        for (int i = 0; i < 16; i++) {
            stringBuilder.append(CONTENT_PLAYBACK_NONCE_ALPHABET.charAt(random.nextInt(CONTENT_PLAYBACK_NONCE_ALPHABET.length())));
        }

        return stringBuilder.toString();
    }
}
