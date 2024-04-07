package com.sedmelluq.discord.lavaplayer.source.youtube2;

import com.sedmelluq.discord.lavaplayer.format.AudioDataFormat;
import com.sedmelluq.discord.lavaplayer.format.AudioDataFormatTools;
import com.sedmelluq.discord.lavaplayer.format.StandardAudioDataFormats;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter;
import com.sedmelluq.discord.lavaplayer.source.youtube2.clients.*;
import com.sedmelluq.discord.lavaplayer.source.youtube2.clients.skeleton.Client;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioItem;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioReference;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.playback.AudioFrame;

import javax.sound.sampled.*;
import java.io.IOException;
import java.util.stream.Collectors;

public class SourceTesting {
    private static AudioPlayerManager apm;

    public static void main(String[] args) throws CannotBeLoaded, IOException, LineUnavailableException, InterruptedException {
        apm = new DefaultAudioPlayerManager();
        YoutubeAudioSourceManager source = new YoutubeAudioSourceManager(new Web(), new Android(), new TvHtml5Embedded());
        Client client = new Music();

        // Video test
//        AudioItem result = client.loadVideo(source, source.getInterface(), "67rB95_3j90");

        // Age-restricted video test (should fail on all but tvhtml5embedded)
//        AudioItem result = source.loadItem(apm, new AudioReference("https://www.youtube.com/watch?v=siTGRbeq5K8", null));

        // Search test
//        AudioItem result = client.loadSearch(source, source.getInterface(), "hurt myself wasted penguinz");
        AudioItem result = client.loadSearchMusic(source, source.getInterface(), "salute joy");

        // Mix test
//        AudioItem result = client.loadMix(source, source.getInterface(), "RD67rB95_3j90", "67rB95_3j90");

        // Playlist test
//        client.setPlaylistPageCount(20);
//        AudioItem result = client.loadPlaylist(source, source.getInterface(), "PL_oFlvgqkrjUVQwiiE3F3k3voF4tjXeP0", null);

        // Link router testing
//        AudioItem result = source.loadItem(
//            apm,
//            new AudioReference("https://www.youtube.com/watch?v=67rB95_3j90&list=RD67rB95_3j90", null)
//        );

        if (result == null) {
            System.out.println("No results");
        } else if (result == AudioReference.NO_TRACK) {
            System.out.println("Got page but no tracks");
        } else if (result instanceof AudioPlaylist) {
            AudioPlaylist playlist = (AudioPlaylist) result;
            System.out.println(playlist.getTracks().stream()
                .map(track -> track.getInfo().title + " by " + track.getInfo().author + " [" + track.getDuration() + "]")
                .collect(Collectors.joining("\n"))
            );

            System.out.println("=== " + playlist.getName() + " ===");
        } else if (result instanceof AudioTrack) {
            AudioTrack track = (AudioTrack) result;
            System.out.println(((AudioTrack) result).getInfo().title + " by " + track.getInfo().author + " [" + track.getDuration() + "]");
        }

//        testPlayback(result);
    }

    private static void testPlayback(AudioTrack track) throws LineUnavailableException {
        AudioDataFormat audioDataFormat = StandardAudioDataFormats.COMMON_PCM_S16_LE;
        AudioFormat audioFormat = AudioDataFormatTools.toAudioFormat(audioDataFormat);
        DataLine.Info info = new DataLine.Info(SourceDataLine.class, audioFormat);
        SourceDataLine line = (SourceDataLine) AudioSystem.getLine(info);
        line.open(audioFormat);
        line.start();

        apm.getConfiguration().setOutputFormat(audioDataFormat);

        AudioPlayer player = apm.createPlayer();
        player.addListener(new AudioEventAdapter() {
            @Override
            public void onTrackException(AudioPlayer player, AudioTrack track, FriendlyException exception) {
                exception.printStackTrace();
            }
        });

        player.startTrack(track, false);

        long start = System.currentTimeMillis();

        while (System.currentTimeMillis() - start < 30000) {
            AudioFrame frame = player.provide();

            if (frame != null) {
                line.write(frame.getData(), 0, frame.getDataLength());
            }
        }
    }
}
