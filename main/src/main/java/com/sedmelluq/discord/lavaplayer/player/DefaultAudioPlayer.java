package com.sedmelluq.discord.lavaplayer.player;

import com.sedmelluq.discord.lavaplayer.filter.PcmFilterFactory;
import com.sedmelluq.discord.lavaplayer.player.event.*;
import com.sedmelluq.discord.lavaplayer.tools.CopyOnUpdateIdentityList;
import com.sedmelluq.discord.lavaplayer.tools.ExceptionTools;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason;
import com.sedmelluq.discord.lavaplayer.track.InternalAudioTrack;
import com.sedmelluq.discord.lavaplayer.track.TrackStateListener;
import com.sedmelluq.discord.lavaplayer.track.playback.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason.*;

/**
 * An audio player that is capable of playing audio tracks and provides audio frames from the currently playing track.
 */
public class DefaultAudioPlayer implements AudioPlayer, TrackStateListener {
  private static final Logger log = LoggerFactory.getLogger(AudioPlayer.class);

  protected volatile InternalAudioTrack scheduledTrack;
  protected volatile InternalAudioTrack activeTrack;
  private volatile long lastRequestTime;
  private volatile long lastReceiveTime;
  private volatile boolean stuckEventSent;
  protected volatile InternalAudioTrack shadowTrack;
  private final AtomicBoolean paused;
  protected final DefaultAudioPlayerManager manager;
  private final CopyOnUpdateIdentityList<AudioEventListener> listeners;
  protected final Object trackSwitchLock;
  private final AudioPlayerOptions options;

  /**
   * @param manager Audio player manager which this player is attached to
   */
  public DefaultAudioPlayer(DefaultAudioPlayerManager manager) {
    this.manager = manager;
    activeTrack = null;
    paused = new AtomicBoolean();
    listeners = new CopyOnUpdateIdentityList<>();
    trackSwitchLock = new Object();
    options = new AudioPlayerOptions();
  }

  /**
   * @return Currently playing track, or null
   */
  public AudioTrack getPlayingTrack() {
    return activeTrack;
  }

  /**
   * @return Currently scheduled track, or null
   */
  public AudioTrack getScheduledTrack() {
    return scheduledTrack;
  }

  /**
   * Schedules the next track to be played. This will not trigger the track to be immediately played,
   * but rather schedules it to play after the current track has finished. If there is no playing track,
   * this function will return false
   * @param track The track to schedule. This will overwrite the currently scheduled track, if one exists.
   *              Passing null will clear the current scheduled track.
   * @return True if the track was scheduled
   */
  public boolean scheduleTrack(AudioTrack track) {
    if (activeTrack == null) {
      return false;
    }

    synchronized (trackSwitchLock) {
      if (scheduledTrack != null) {
        scheduledTrack.stop();
      }

      InternalAudioTrack newTrack = (InternalAudioTrack) track;
      scheduledTrack = newTrack;

      manager.executeTrack(this, newTrack, manager.getConfiguration(), options);
    }

    return true;
  }

  /**
   * @param track The track to start playing
   */
  public void playTrack(AudioTrack track) {
    startTrack(track, false);
  }

  /**
   * Starts a new track. This will clear any scheduled tracks.
   * @param track The track to start playing, passing null will stop the current track and return false
   * @param noInterrupt Whether to only start if nothing else is playing
   * @return True if the track was started
   */
  public boolean startTrack(AudioTrack track, boolean noInterrupt) {
    InternalAudioTrack newTrack = (InternalAudioTrack) track;
    InternalAudioTrack previousTrack;

    synchronized (trackSwitchLock) {
      previousTrack = activeTrack;

      if (noInterrupt && previousTrack != null) {
        return false;
      }

      if (scheduledTrack != null) {
        scheduledTrack.stop();
        scheduledTrack = null;
      }

      activeTrack = newTrack;
      lastRequestTime = System.currentTimeMillis();
      lastReceiveTime = System.nanoTime();
      stuckEventSent = false;

      if (previousTrack != null) {
        previousTrack.stop();
        dispatchEvent(new TrackEndEvent(this, previousTrack, newTrack == null ? STOPPED : REPLACED));

        shadowTrack = previousTrack;
      }
    }

    if (newTrack == null) {
      shadowTrack = null;
      return false;
    }

    dispatchEvent(new TrackStartEvent(this, newTrack));

    manager.executeTrack(this, newTrack, manager.getConfiguration(), options);
    return true;
  }

  /**
   * Stop currently playing track.
   */
  public void stopCurrentTrack() {
    stopWithReason(STOPPED, false);
  }

  /**
   * Stop currently playing track and any scheduled tracks.
   */
  public void stopTrack() {
    stopWithReason(STOPPED, true);
  }

  private void stopWithReason(AudioTrackEndReason reason, boolean includeScheduled) {
    shadowTrack = null;

    synchronized (trackSwitchLock) {
      if (includeScheduled && scheduledTrack != null) {
        scheduledTrack.stop();
        scheduledTrack = null;
      }

      InternalAudioTrack previousTrack = activeTrack;
      boolean swapped = false;

      if (scheduledTrack != null) {
        activeTrack = scheduledTrack;
        scheduledTrack = null;
        swapped = true;
      } else {
        activeTrack = null;
      }

      if (previousTrack != null) {
        previousTrack.stop();
        dispatchEvent(new TrackEndEvent(this, previousTrack, reason));
      }

      if (swapped && activeTrack != null) {
        dispatchEvent(new TrackStartEvent(this, activeTrack));
      }
    }
  }

  private AudioFrame provideShadowFrame() {
    InternalAudioTrack shadow = shadowTrack;
    AudioFrame frame = null;

    if (shadow != null) {
      frame = shadow.provide();

      if (frame != null && frame.isTerminator()) {
        shadowTrack = null;
        frame = null;
      }
    }

    return frame;
  }

  private boolean provideShadowFrame(MutableAudioFrame targetFrame) {
    InternalAudioTrack shadow = shadowTrack;

    if (shadow != null && shadow.provide(targetFrame)) {
      if (targetFrame.isTerminator()) {
        shadowTrack = null;
        return false;
      }

      return true;
    }

    return false;
  }

  @Override
  public AudioFrame provide() {
    return AudioFrameProviderTools.delegateToTimedProvide(this);
  }

  @Override
  public AudioFrame provide(long timeout, TimeUnit unit) throws TimeoutException, InterruptedException {
    InternalAudioTrack track;

    lastRequestTime = System.currentTimeMillis();

    if (timeout == 0 && paused.get()) {
      return null;
    }

    while ((track = activeTrack) != null) {
      AudioFrame frame = timeout > 0 ? track.provide(timeout, unit) : track.provide();

      if (frame != null) {
        lastReceiveTime = System.nanoTime();
        shadowTrack = null;

        if (frame.isTerminator()) {
          handleTerminator(track);
          continue;
        }
      } else if (timeout == 0) {
        checkStuck(track);

        frame = provideShadowFrame();
      }

      return frame;
    }

    return null;
  }

  @Override
  public boolean provide(MutableAudioFrame targetFrame) {
    try {
      return provide(targetFrame, 0, TimeUnit.MILLISECONDS);
    } catch (TimeoutException | InterruptedException e) {
      ExceptionTools.keepInterrupted(e);
      throw new RuntimeException(e);
    }
  }

  @Override
  public boolean provide(MutableAudioFrame targetFrame, long timeout, TimeUnit unit)
      throws TimeoutException, InterruptedException {

    InternalAudioTrack track;

    lastRequestTime = System.currentTimeMillis();

    if (timeout == 0 && paused.get()) {
      return false;
    }

    while ((track = activeTrack) != null) {
      if (timeout > 0 ? track.provide(targetFrame, timeout, unit) : track.provide(targetFrame)) {
        lastReceiveTime = System.nanoTime();
        shadowTrack = null;

        if (targetFrame.isTerminator()) {
          handleTerminator(track);
          continue;
        }

        return true;
      } else if (timeout == 0) {
        checkStuck(track);
        return provideShadowFrame(targetFrame);
      } else {
        return false;
      }
    }

    return false;
  }

  private void handleTerminator(InternalAudioTrack track) {
    synchronized (trackSwitchLock) {
      if (activeTrack == track) {
        activeTrack = null;

        boolean failedBeforeLoad = track.getActiveExecutor().failedBeforeLoad();
        boolean swapped = false;

        AudioTrackEndReason endReason = scheduledTrack != null
            ? (failedBeforeLoad ? LOAD_FAILED_GAPLESS : FINISHED_GAPLESS)
            : (failedBeforeLoad ? LOAD_FAILED : FINISHED);

        if (scheduledTrack != null) {
          activeTrack = scheduledTrack;
          scheduledTrack = null;
          swapped = true;
        }

        dispatchEvent(new TrackEndEvent(this, track, endReason));

        if (swapped && activeTrack != null) {
          dispatchEvent(new TrackStartEvent(this, activeTrack));
        }
      }
    }
  }

  private void checkStuck(AudioTrack track) {
    if (!stuckEventSent && System.nanoTime() - lastReceiveTime > manager.getTrackStuckThresholdNanos()) {
      stuckEventSent = true;

      StackTraceElement[] stackTrace = getStackTrace(track);
      long threshold = TimeUnit.NANOSECONDS.toMillis(manager.getTrackStuckThresholdNanos());

      dispatchEvent(new TrackStuckEvent(this, track, threshold, stackTrace));
    }
  }

  private StackTraceElement[] getStackTrace(AudioTrack track) {
    if (track instanceof InternalAudioTrack) {
      AudioTrackExecutor executor = ((InternalAudioTrack) track).getActiveExecutor();

      if (executor instanceof LocalAudioTrackExecutor) {
        return ((LocalAudioTrackExecutor) executor).getStackTrace();
      }
    }

    return null;
  }

  public int getVolume() {
    return options.volumeLevel.get();
  }

  public void setVolume(int volume) {
    options.volumeLevel.set(Math.min(1000, Math.max(0, volume)));
  }

  public void setFilterFactory(PcmFilterFactory factory) {
    options.filterFactory.set(factory);
  }

  public void setFrameBufferDuration(Integer duration) {
    if (duration != null) {
      duration = Math.max(200, duration);
    }

    options.frameBufferDuration.set(duration);
  }

  /**
   * @return Whether the player is paused
   */
  public boolean isPaused() {
    return paused.get();
  }

  /**
   * @param value True to pause, false to resume
   */
  public void setPaused(boolean value) {
    if (paused.compareAndSet(!value, value)) {
      if (value) {
        dispatchEvent(new PlayerPauseEvent(this));
      } else {
        dispatchEvent(new PlayerResumeEvent(this));
        lastReceiveTime = System.nanoTime();
      }
    }
  }

  /**
   * Destroy the player and stop playing track.
   */
  public void destroy() {
    stopTrack();
  }

  /**
   * Add a listener to events from this player.
   * @param listener New listener
   */
  public void addListener(AudioEventListener listener) {
    synchronized (trackSwitchLock) {
      listeners.add(listener);
    }
  }

  /**
   * Remove an attached listener using identity comparison.
   * @param listener The listener to remove
   */
  public void removeListener(AudioEventListener listener) {
    synchronized (trackSwitchLock) {
      listeners.remove(listener);
    }
  }

  protected void dispatchEvent(AudioEvent event) {
    log.debug("Firing an event with class {}", event.getClass().getSimpleName());

    synchronized (trackSwitchLock) {
      for (AudioEventListener listener : listeners.items) {
        try {
          listener.onEvent(event);
        } catch (Exception e) {
          log.error("Handler of event {} threw an exception.", event, e);
        }
      }
    }
  }

  @Override
  public void onTrackException(AudioTrack track, FriendlyException exception) {
    if (track == scheduledTrack) {
      synchronized (trackSwitchLock) {
        if (track == scheduledTrack) {
          scheduledTrack.stop();
          scheduledTrack = null;
        }
      }
    }

    dispatchEvent(new TrackExceptionEvent(this, track, exception));
  }

  @Override
  public void onTrackStuck(AudioTrack track, long thresholdMs) {
    dispatchEvent(new TrackStuckEvent(this, track, thresholdMs, null));
  }

  /**
   * Check if the player should be "cleaned up" - stopped due to nothing using it, with the given threshold.
   * @param threshold Threshold in milliseconds to use
   */
  public void checkCleanup(long threshold) {
    AudioTrack track = getPlayingTrack();
    if (track != null && System.currentTimeMillis() - lastRequestTime >= threshold) {
      log.debug("Triggering cleanup on an audio player playing track {}", track);

      stopWithReason(CLEANUP, true);
    }
  }
}
