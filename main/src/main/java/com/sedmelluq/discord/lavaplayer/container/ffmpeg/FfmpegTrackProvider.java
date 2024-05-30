package com.sedmelluq.discord.lavaplayer.container.ffmpeg;

import com.sedmelluq.discord.lavaplayer.filter.AudioPipeline;
import com.sedmelluq.discord.lavaplayer.filter.AudioPipelineFactory;
import com.sedmelluq.discord.lavaplayer.filter.PcmFormat;
import com.sedmelluq.discord.lavaplayer.track.playback.AudioProcessingContext;
import com.sedmelluq.lava.common.natives.architecture.DefaultOperatingSystemTypes;
import com.sedmelluq.lava.common.natives.architecture.OperatingSystemType;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.List;

public class FfmpegTrackProvider implements Closeable {
    private static final String FFMPEG_LOCATION = System.getenv().getOrDefault("FFMPEG_PATH", "ffmpeg");

    private final String uri;

    private Process process;
    private InputStream reader;
    private final AudioPipeline downstream;

    private final byte[] buffer;
    private final ShortBuffer shorts;

    public FfmpegTrackProvider(AudioProcessingContext context, String uri) throws IOException {
        this.uri = uri;
        createProcess(null);
        this.downstream = context != null ? AudioPipelineFactory.create(context, new PcmFormat(2, 48000)) : null;

        this.buffer = new byte[4096];
        this.shorts = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
    }

    public void provideFrames() throws InterruptedException {
        try {
            while (reader.read(buffer) > 0) {
                downstream.process(shorts);
                shorts.clear();
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void seekToTimecode(long timecode) {
        try {
            createProcess(timecode);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void createProcess(Long timecode) throws IOException {
        if (this.process != null) {
            this.process.destroyForcibly();
        }

        List<String> command = new ArrayList<>();
        command.add(FFMPEG_LOCATION);
        command.add("-hide_banner");

        command.add("-v");
        command.add("-error");

        command.add("-analyzeduration");
        command.add("0");

        command.add("-reconnect");
        command.add("1");

        command.add("-reconnect_delay_max");
        command.add("2");

        if (timecode != null) {
            command.add("-ss");
            command.add(timecode + "ms");
        }

        command.add("-i");
        command.add('"' + uri + '"');
        command.add("-vn");
        command.add("-sn");
        command.add("-f");
        command.add("s16le");
        command.add("-ar");
        command.add("48000");
        command.add("-ac");
        command.add("2");
        command.add("-");

        this.process = PlatformSpecificProcessBuilder.getProcessBuilder(command).start();
        this.reader = process.getInputStream();
    }


    @Override
    public void close() throws IOException {
        if (this.process != null) {
            this.process.destroyForcibly();
        }

        if (downstream != null) {
            downstream.close();
        }
    }
}
