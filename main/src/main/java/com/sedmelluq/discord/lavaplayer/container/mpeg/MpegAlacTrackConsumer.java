package com.sedmelluq.discord.lavaplayer.container.mpeg;

import com.sedmelluq.discord.lavaplayer.filter.AudioPipeline;
import com.sedmelluq.discord.lavaplayer.filter.AudioPipelineFactory;
import com.sedmelluq.discord.lavaplayer.filter.PcmFormat;
import com.sedmelluq.discord.lavaplayer.natives.alac.AlacDecoder;
import com.sedmelluq.discord.lavaplayer.track.playback.AudioProcessingContext;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.ReadableByteChannel;

/**
 * Handles processing MP4 ALAC frames. Each call to {@link #consume} receives
 * exactly one complete ALAC frame (as delimited by the MP4 sample table),
 * which is decoded in full and forwarded to the audio pipeline as 16-bit PCM.
 */
public class MpegAlacTrackConsumer implements MpegTrackConsumer {
    private static final Logger log = LoggerFactory.getLogger(MpegAlacTrackConsumer.class);

    private final MpegTrackInfo track;
    private final AudioProcessingContext context;

    private AlacDecoder decoder;
    private AudioPipeline downstream;
    private ByteBuffer frameBuffer;   // accumulates one raw ALAC frame from the channel
    private ShortBuffer sampleBuffer; // receives decoded interleaved 16-bit PCM

    /**
     * @param context Configuration and output information for processing
     * @param track   The MP4 audio track descriptor; {@code decoderConfig} must
     *                contain the 24-byte ALACSpecificConfig with version/flags stripped
     */
    public MpegAlacTrackConsumer(AudioProcessingContext context, MpegTrackInfo track) {
        this.context = context;
        this.track = track;
    }

    @Override
    public void initialise() {
        if (track.decoderConfig == null) {
            throw new IllegalStateException("ALAC track is missing ALACSpecificConfig decoder config.");
        }

        decoder    = new AlacDecoder(track.decoderConfig);
        downstream = AudioPipelineFactory.create(context, new PcmFormat(decoder.numChannels, decoder.sampleRate));

        log.debug("Initialising ALAC track: {} Hz, {} channel(s), {} bit, frame length {}.",
                decoder.sampleRate, decoder.numChannels, decoder.bitDepth, decoder.frameLength);

        // Output buffer: worst-case frameLength samples * numChannels * 2 bytes per short
        sampleBuffer = ByteBuffer
                .allocateDirect(decoder.frameLength * decoder.numChannels * 2)
                .order(ByteOrder.nativeOrder())
                .asShortBuffer();

        // Input frame buffer: a conservative upper bound; resized in consume() if needed
        int frameBytes = decoder.frameLength * decoder.numChannels * ((decoder.bitDepth + 7) / 8) + 64;
        frameBuffer = ByteBuffer.allocateDirect(frameBytes);
    }

    @Override
    public MpegTrackInfo getTrack() {
        return track;
    }

    @Override
    public void seekPerformed(long requestedTimecode, long providedTimecode) {
        // ALAC frames are independently decodable — no decoder state needs resetting.
        downstream.seekPerformed(requestedTimecode, providedTimecode);
    }

    @Override
    public void flush() throws InterruptedException {
        downstream.flush();
    }

    @Override
    public void consume(ReadableByteChannel channel, int length) throws InterruptedException {
        // Resize the frame buffer if this frame is larger than expected
        if (frameBuffer.capacity() < length) {
            frameBuffer = ByteBuffer.allocateDirect(length);
        }

        frameBuffer.clear();
        frameBuffer.limit(length);

        try {
            IOUtils.readFully(channel, frameBuffer);
        } catch (ClosedByInterruptException e) {
            log.trace("Interrupt received while reading channel", e);
            Thread.currentThread().interrupt();
            throw new InterruptedException();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        frameBuffer.flip();
        sampleBuffer.clear();

        int samplesDecoded = decoder.decode(frameBuffer, sampleBuffer);

        if (samplesDecoded > 0) {
            // flip() converts (position=samplesDecoded*channels, limit=capacity)
            // to (position=0, limit=samplesDecoded*channels) for the pipeline to consume
            sampleBuffer.flip();
            downstream.process(sampleBuffer);
        }
    }

    @Override
    public void close() {
        if (downstream != null) {
            downstream.close();
        }
    }
}
