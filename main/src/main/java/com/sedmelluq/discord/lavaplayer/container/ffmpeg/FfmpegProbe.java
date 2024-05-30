package com.sedmelluq.discord.lavaplayer.container.ffmpeg;

import com.sedmelluq.discord.lavaplayer.container.MediaContainerDetectionResult;
import com.sedmelluq.discord.lavaplayer.container.MediaContainerHints;
import com.sedmelluq.discord.lavaplayer.container.MediaContainerProbe;
import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser;
import com.sedmelluq.discord.lavaplayer.tools.io.SeekableInputStream;
import com.sedmelluq.discord.lavaplayer.track.AudioReference;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.info.AudioTrackInfoBuilder;
import org.apache.commons.io.IOUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

public class FfmpegProbe implements MediaContainerProbe {
    private static final String FFPROBE_LOCATION = System.getenv().getOrDefault("FFPROBE_PATH", "ffprobe");

    @Override
    public String getName() {
        return "ffmpeg";
    }

    @Override
    public boolean matchesHints(MediaContainerHints hints) {
        return false;
    }

    @Override
    public MediaContainerDetectionResult probe(AudioReference reference, SeekableInputStream inputStream) throws IOException {
        if (reference.identifier.contains("\"")) {
            // TODO quotes need sanitising. Not dealing with this atm.
            return null;
        }

        String identifier = reference.getIdentifier();

        Process process = new ProcessBuilder(
            FFPROBE_LOCATION,
            "-v", "error", // only log errors
            "-of", "json", // output as json
            "-select_streams", "a:0", // select the first audio stream
            "-show_format", // show format information for the file (name, size, duration, bitrate, ...)
            '"' + identifier + '"'
        ).start();

        try {
            process.waitFor(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            throw new RuntimeException("Probing stream took too long", e);
        }

        String error = IOUtils.toString(process.getErrorStream(), StandardCharsets.UTF_8);

        if (!error.trim().isEmpty()) {
            return MediaContainerDetectionResult.unsupportedFormat(this, error);
        }

        JsonBrowser browser = JsonBrowser.parse(process.getInputStream());
        JsonBrowser format = browser.get("format");

        if (browser.isNull() || format.isNull()) {
            return MediaContainerDetectionResult.unsupportedFormat(this, "The media format could not be determined.");
        }

        JsonBrowser tags = format.get("tags");
        JsonBrowser durationField = format.get("duration");

        AudioTrackInfoBuilder builder = AudioTrackInfoBuilder.create(reference, inputStream)
            .setAuthor(tags.get("artist").text())
            .setTitle(tags.get("title").text())
            .setLength(!durationField.isNull() ? durationField.as(Number.class).longValue() * 1000 : 0)
            .setIdentifier(reference.getIdentifier())
            .setUri(reference.getUri());

        return MediaContainerDetectionResult.supportedFormat(this, null, builder.build());
    }

    @Override
    public AudioTrack createTrack(String parameters, AudioTrackInfo trackInfo, SeekableInputStream inputStream) {
        return new FfmpegAudioTrack(trackInfo);
    }
}
