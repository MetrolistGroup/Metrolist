/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import androidx.media3.common.C
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.minutes

class SleepTimer(
    private val scope: CoroutineScope,
    var player: Player,
    private val onVolumeMultiplierChanged: (Float) -> Unit = {},
) : Player.Listener {
    private companion object {
        private const val TIMER_TICK_MS = 1000L
        private const val FADE_OUT_WINDOW_MS = 60_000L
    }

    private var sleepTimerJob: Job? = null
    var triggerTime by mutableLongStateOf(-1L)
        private set
    var pauseWhenSongEnd by mutableStateOf(false)
        private set
    var stopAfterCurrentSongOnTimeout by mutableStateOf(false)
        private set
    var fadeOutEnabled by mutableStateOf(false)
        private set
    val isActive: Boolean
        get() = triggerTime != -1L || pauseWhenSongEnd

    fun start(minute: Int) {
        start(
            minute = minute,
            stopAfterCurrentSong = false,
            fadeOut = false,
        )
    }

    fun start(
        minute: Int,
        stopAfterCurrentSong: Boolean,
        fadeOut: Boolean,
    ) {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        updateVolumeMultiplier(1f)
        fadeOutEnabled = fadeOut

        if (minute == -1) {
            pauseWhenSongEnd = true
            stopAfterCurrentSongOnTimeout = false
            triggerTime = -1L
            if (fadeOutEnabled) {
                sleepTimerJob =
                    scope.launch {
                        while (this@SleepTimer.isActive) {
                            updateVolumeMultiplierForCurrentSong()
                            delay(TIMER_TICK_MS)
                        }
                    }
            }
        } else {
            pauseWhenSongEnd = false
            stopAfterCurrentSongOnTimeout = stopAfterCurrentSong
            triggerTime = System.currentTimeMillis() + minute.minutes.inWholeMilliseconds
            sleepTimerJob =
                scope.launch {
                    while (this@SleepTimer.isActive) {
                        if (triggerTime != -1L) {
                            val remainingMs = triggerTime - System.currentTimeMillis()
                            if (remainingMs <= 0L) {
                                triggerTime = -1L
                                if (stopAfterCurrentSongOnTimeout) {
                                    pauseWhenSongEnd = true
                                    stopAfterCurrentSongOnTimeout = false
                                    if (!fadeOutEnabled) {
                                        break
                                    }
                                } else {
                                    completeTimerAndPause()
                                    break
                                }
                            } else if (fadeOutEnabled && !stopAfterCurrentSongOnTimeout) {
                                updateVolumeMultiplierForRemainingTime(remainingMs)
                            }
                        } else if (pauseWhenSongEnd && fadeOutEnabled) {
                            updateVolumeMultiplierForCurrentSong()
                        }

                        delay(TIMER_TICK_MS)
                    }
                }
        }
    }

    /**
     * Notify the sleep timer that a song transition has occurred outside of normal
     * player callbacks (e.g. during crossfade player swap). If "end of song" mode
     * is active, this will pause the player and deactivate the timer.
     */
    fun notifySongTransition() {
        if (pauseWhenSongEnd) {
            completeTimerAndPause()
        }
    }

    fun clear() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        pauseWhenSongEnd = false
        stopAfterCurrentSongOnTimeout = false
        fadeOutEnabled = false
        triggerTime = -1L
        updateVolumeMultiplier(1f)
    }

    override fun onMediaItemTransition(
        mediaItem: MediaItem?,
        reason: Int,
    ) {
        if (pauseWhenSongEnd) {
            completeTimerAndPause()
        }
    }

    override fun onPlaybackStateChanged(
        @Player.State playbackState: Int,
    ) {
        if (playbackState == Player.STATE_ENDED && pauseWhenSongEnd) {
            completeTimerAndPause()
        }
    }

    private fun completeTimerAndPause() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        pauseWhenSongEnd = false
        stopAfterCurrentSongOnTimeout = false
        fadeOutEnabled = false
        triggerTime = -1L
        updateVolumeMultiplier(1f)
        player.pause()
    }

    private fun updateVolumeMultiplierForRemainingTime(remainingMs: Long) {
        updateVolumeMultiplier(volumeMultiplierForRemainingTime(remainingMs))
    }

    private fun updateVolumeMultiplierForCurrentSong() {
        val duration = player.duration
        if (duration == C.TIME_UNSET || duration <= 0) {
            updateVolumeMultiplier(1f)
            return
        }

        val remainingMs = (duration - player.currentPosition).coerceAtLeast(0L)
        updateVolumeMultiplierForRemainingTime(remainingMs)
    }

    private fun volumeMultiplierForRemainingTime(remainingMs: Long): Float {
        if (remainingMs >= FADE_OUT_WINDOW_MS) return 1f
        return (remainingMs.toFloat() / FADE_OUT_WINDOW_MS).coerceIn(0f, 1f)
    }

    private fun updateVolumeMultiplier(multiplier: Float) {
        onVolumeMultiplierChanged(multiplier)
    }
}
