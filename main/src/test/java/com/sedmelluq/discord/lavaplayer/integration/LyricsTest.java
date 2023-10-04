package com.sedmelluq.discord.lavaplayer.integration;

import com.sedmelluq.discord.lavaplayer.source.youtube.YoutubeAudioSourceManager;

public class LyricsTest {
    public static void main(String[] args) {
        final YoutubeAudioSourceManager ytasm = new YoutubeAudioSourceManager();
        String lyrics = ytasm.getLyricsForVideo(args[0]);
        System.out.println(lyrics);
    }
}
