package com.metrolist.shared.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.TimeSource

data class SleepTimerState(
    val isActive: Boolean = false,
    val remainingMs: Long? = null,
    val totalDurationMs: Long? = null,
    val stopAfterCurrentSong: Boolean = false,
    val fadeOut: Boolean = false,
    val finishCurrentSong: Boolean = false,
)

interface SleepTimerPlayer {
    val isAvailable: Boolean
    val currentPositionMs: Long
    val durationMs: Long
    val playbackProgress: Flow<Pair<Long, Long>>
    var pauseAtEndOfMediaItems: Boolean

    fun pause()

    fun cancelCrossfadeTransition()

    fun setSleepTimerGain(gain: Float)
}

private val monotonicTimeSource = TimeSource.Monotonic
private val monotonicStartMark = monotonicTimeSource.markNow()
private val defaultElapsedRealtimeMs: () -> Long = {
    monotonicStartMark.elapsedNow().inWholeMilliseconds
}

open class SleepTimerController(
    private val scope: CoroutineScope,
    private val player: SleepTimerPlayer,
    private val elapsedRealtimeMs: () -> Long = defaultElapsedRealtimeMs,
    private val updateIntervalMs: Long = 500L,
) {
    companion object {
        private const val FADE_DURATION_MS = 60_000L
    }

    private var timerJob: Job? = null
    private var appliedGain = 1f
    private var fadeDurationMs: Long? = null
    private val _state = MutableStateFlow(SleepTimerState())
    val state: StateFlow<SleepTimerState> = _state.asStateFlow()

    val isStopAfterCurrentSong: Boolean
        get() = _state.value.stopAfterCurrentSong

    fun start(
        durationMs: Long,
        fadeOut: Boolean,
        finishCurrentSong: Boolean = false,
    ) {
        if (durationMs <= 0L) return
        cancel()
        val deadline = elapsedRealtimeMs() + durationMs
        fadeDurationMs = minOf(FADE_DURATION_MS, durationMs)
        _state.value = SleepTimerState(true, durationMs, durationMs, fadeOut = fadeOut, finishCurrentSong = finishCurrentSong)
        timerJob =
            scope.launch {
                while (isActive) {
                    val remainingMs = (deadline - elapsedRealtimeMs()).coerceAtLeast(0L)
                    _state.value = _state.value.copy(remainingMs = remainingMs)
                    if (!finishCurrentSong) updateFade(remainingMs)
                    if (remainingMs == 0L) {
                        if (finishCurrentSong) {
                            stopAfterCurrentSong(fadeOut)
                        } else {
                            player.pause()
                            finish()
                        }
                        break
                    }
                    val subscribers = _state.subscriptionCount.value
                    val untilFade = if (fadeOut && !finishCurrentSong) (remainingMs - FADE_DURATION_MS).coerceAtLeast(0L) else remainingMs
                    val waitMs = if (subscribers > 0 || untilFade == 0L) updateIntervalMs.coerceAtMost(remainingMs) else untilFade
                    withTimeoutOrNull(waitMs) { _state.subscriptionCount.first { it != subscribers } }
                }
            }
    }

    fun stopAfterCurrentSong(fadeOut: Boolean) {
        cancel()
        player.cancelCrossfadeTransition()
        if (!player.isAvailable) return
        val remainingMs = currentTrackRemainingMs()
        fadeDurationMs = remainingMs?.coerceIn(1L, FADE_DURATION_MS)
        player.pauseAtEndOfMediaItems = true
        _state.value = SleepTimerState(true, remainingMs, remainingMs, true, fadeOut)
        timerJob =
            scope.launch {
                player.playbackProgress.collect { (position, duration) ->
                    val remaining = if (duration > 0L) (duration - position).coerceAtLeast(0L) else null
                    _state.value = _state.value.copy(remainingMs = remaining)
                    updateFade(remaining)
                }
            }
    }

    fun cancel() {
        timerJob?.cancel()
        timerJob = null
        player.pauseAtEndOfMediaItems = false
        appliedGain = 1f
        fadeDurationMs = null
        player.setSleepTimerGain(1f)
        _state.value = SleepTimerState()
    }

    fun onEndOfMediaItem() {
        if (!isStopAfterCurrentSong) return
        player.pause()
        finish()
    }

    fun onDestroy() {
        cancel()
    }

    private fun finish() {
        timerJob?.cancel()
        timerJob = null
        player.pauseAtEndOfMediaItems = false
        appliedGain = 1f
        fadeDurationMs = null
        player.setSleepTimerGain(1f)
        _state.value = SleepTimerState()
    }

    private fun currentTrackRemainingMs(): Long? {
        if (player.durationMs <= 0L) return null
        return (player.durationMs - player.currentPositionMs).coerceAtLeast(0L)
    }

    private fun applyGain(gain: Float) {
        if (appliedGain == gain) return
        appliedGain = gain
        player.setSleepTimerGain(gain)
    }

    private fun updateFade(remainingMs: Long?) {
        if (fadeDurationMs == null && remainingMs != null) fadeDurationMs = remainingMs.coerceIn(1L, FADE_DURATION_MS)
        val fadeDuration = fadeDurationMs ?: FADE_DURATION_MS
        val gain =
            if (_state.value.fadeOut && remainingMs != null && remainingMs <= fadeDuration) {
                remainingMs.toFloat() / fadeDuration
            } else {
                1f
            }
        applyGain(gain)
    }
}
