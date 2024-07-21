package com.sedmelluq.discord.lavaplayer.source;

import com.sedmelluq.discord.lavaplayer.container.MediaContainerRegistry;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.bandcamp.BandcampAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.getyarn.GetyarnAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.http.HttpAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.local.LocalAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.soundcloud.SoundCloudAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.twitch.TwitchStreamAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.vimeo.VimeoAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.youtube.YoutubeAudioSourceManager;

import java.util.*;

/**
 * A helper class for registering built-in source managers to a player manager.
 */
@SuppressWarnings("deprecation")
public class AudioSourceManagers {
  public static final Map<Class<? extends AudioSourceManager>, SourceFactory> DEFAULT_REMOTE_SOURCES;

  static {
    Map<Class<? extends AudioSourceManager>, SourceFactory> sources = new HashMap<>();

    sources.put(YoutubeAudioSourceManager.class,      (unused) -> new YoutubeAudioSourceManager());
    sources.put(SoundCloudAudioSourceManager.class,   (unused) -> SoundCloudAudioSourceManager.createDefault());
    sources.put(BandcampAudioSourceManager.class,     (unused) -> new BandcampAudioSourceManager());
    sources.put(VimeoAudioSourceManager.class,        (unused) -> new VimeoAudioSourceManager());
    sources.put(TwitchStreamAudioSourceManager.class, (unused) -> new TwitchStreamAudioSourceManager());
    sources.put(GetyarnAudioSourceManager.class,      (unused) -> new GetyarnAudioSourceManager());
    sources.put(HttpAudioSourceManager.class,         HttpAudioSourceManager::new);

    DEFAULT_REMOTE_SOURCES = Collections.unmodifiableMap(sources);
  }

  /**
   * See {@link #registerRemoteSources(AudioPlayerManager, MediaContainerRegistry)}, but with default containers.
   */
  public static void registerRemoteSources(AudioPlayerManager playerManager) {
    registerRemoteSources(playerManager, MediaContainerRegistry.DEFAULT_REGISTRY);
  }

  public static void registerRemoteSources(AudioPlayerManager playerManager,
                                           MediaContainerRegistry containerRegistry) {
    for (SourceFactory sourceFactory : DEFAULT_REMOTE_SOURCES.values()) {
      playerManager.registerSourceManager(sourceFactory.create(containerRegistry));
    }
  }

  @SafeVarargs
  public static void registerRemoteSources(AudioPlayerManager playerManager,
                                           Class<? extends AudioSourceManager>... excluding) {
    registerRemoteSources(playerManager, MediaContainerRegistry.DEFAULT_REGISTRY, excluding);
  }

  /**
   * Registers all built-in remote audio sources to the specified player manager. Local file audio source must be
   * registered separately.
   *
   * @param playerManager Player manager to register the source managers to
   * @param containerRegistry Media container registry to be used by any probing sources.
   */
  @SafeVarargs
  public static void registerRemoteSources(AudioPlayerManager playerManager,
                                           MediaContainerRegistry containerRegistry,
                                           Class<? extends AudioSourceManager>... excluding) {
    Set<Class<? extends AudioSourceManager>> excludedSources = new HashSet<>(Arrays.asList(excluding));

    for (Map.Entry<Class<? extends AudioSourceManager>, SourceFactory> source : DEFAULT_REMOTE_SOURCES.entrySet()) {
      if (excludedSources.contains(source.getKey())) {
        continue;
      }

      playerManager.registerSourceManager(source.getValue().create(containerRegistry));
    }
  }

  /**
   * Registers the local file source manager to the specified player manager.
   *
   * @param playerManager Player manager to register the source manager to
   */
  public static void registerLocalSource(AudioPlayerManager playerManager) {
    registerLocalSource(playerManager, MediaContainerRegistry.DEFAULT_REGISTRY);
  }

  /**
   * Registers the local file source manager to the specified player manager.
   *
   * @param playerManager Player manager to register the source manager to
   * @param containerRegistry Media container registry to be used by the local source.
   */
  public static void registerLocalSource(AudioPlayerManager playerManager, MediaContainerRegistry containerRegistry) {
    playerManager.registerSourceManager(new LocalAudioSourceManager(containerRegistry));
  }

  public interface SourceFactory {
    AudioSourceManager create(MediaContainerRegistry containerRegistry);
  }
}
