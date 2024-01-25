package com.sedmelluq.discord.lavaplayer.integration;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.FunctionalResultHandler;
import com.sedmelluq.discord.lavaplayer.source.soundcloud.SoundCloudAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.soundcloud.SoundCloudAudioTrack;
import com.sedmelluq.discord.lavaplayer.source.soundcloud.SoundcloudAudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.tools.io.MessageInput;
import com.sedmelluq.discord.lavaplayer.tools.io.MessageOutput;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;

public class SoundcloudTrackInfoTest {
    static final AudioPlayerManager playerManager = new DefaultAudioPlayerManager();

    public static void main(String[] args) throws IOException {
        playerManager.registerSourceManager(SoundCloudAudioSourceManager.createDefault());

        final AudioTrack stdTrack = loadTrack("https://soundcloud.com/itsrudeboy/love-sorrow");
        final SoundcloudAudioTrackInfo stdInfo = (SoundcloudAudioTrackInfo) stdTrack.getInfo();

        final AudioTrack goPlusTrack = loadTrack("https://soundcloud.com/eminemofficial/the-real-slim-shady-album");
        final SoundcloudAudioTrackInfo goPlusInfo = (SoundcloudAudioTrackInfo) goPlusTrack.getInfo();

        final AudioTrack decodedEncoded = decodeTrack(encodeTrack(goPlusTrack));
        final SoundcloudAudioTrackInfo decodedInfo = (SoundcloudAudioTrackInfo) decodedEncoded.getInfo();

        final AudioTrack decoded = decodeTrack("QAAA8QIAE1RoZSBSZWFsIFNsaW0gU2hhZHkABkVtaW5lbQAAAAAABF4qAHJVOmh0dHBzOi8vYXBpLXYyLnNvdW5kY2xvdWQuY29tL21lZGlhL3NvdW5kY2xvdWQ6dHJhY2tzOjI1NjI1Njg4Ny85MWM0N2JiYy0yOTNiLTRmNzQtOGI1Yy05MWU0MTRkM2MxMmUvcHJldmlldy9obHMAAQA/aHR0cHM6Ly9zb3VuZGNsb3VkLmNvbS9lbWluZW1vZmZpY2lhbC90aGUtcmVhbC1zbGltLXNoYWR5LWFsYnVtAApzb3VuZGNsb3VkAAAAAAAAAAA=");

        assert stdInfo.monetizationModel.equals("NOT_APPLICABLE");
        assert !stdInfo.snipped;

        assert goPlusInfo.monetizationModel.equals("SUB_HIGH_TIER");
        assert goPlusInfo.snipped;

        assert decodedInfo.monetizationModel.equals("SUB_HIGH_TIER");
        assert decodedInfo.snipped;

        assert !(decoded.getInfo() instanceof SoundcloudAudioTrackInfo);
    }

    static AudioTrack loadTrack(String url) {
        final CompletableFuture<AudioTrack> future = new CompletableFuture<>();

        playerManager.loadItem(url, new FunctionalResultHandler(
            future::complete,
            unused -> future.completeExceptionally(new IllegalStateException("Loaded a playlist instead of expected type track")),
            () -> future.completeExceptionally(new IllegalStateException("No results for URL")),
            future::completeExceptionally
        ));

        return future.join();
    }

    static SoundCloudAudioTrack decodeTrack(String encoded) throws IOException {
        final MessageInput input = new MessageInput(new ByteArrayInputStream(Base64.getDecoder().decode(encoded)));
        return (SoundCloudAudioTrack) playerManager.decodeTrack(input).decodedTrack;
    }

    static String encodeTrack(AudioTrack track) throws IOException {
        final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        final MessageOutput output = new MessageOutput(outputStream);

        playerManager.encodeTrack(output, track);

        return Base64.getEncoder().encodeToString(outputStream.toByteArray());
    }
}
