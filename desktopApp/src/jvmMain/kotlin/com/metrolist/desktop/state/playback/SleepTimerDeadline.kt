package com.metrolist.desktop.state.playback

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withTimeoutOrNull

internal fun endOfSongSleepTimerProgress(
    position: Flow<Long>,
    duration: Flow<Long>,
    speed: Flow<Float>,
): Flow<Pair<Long?, Float>> =
    combine(position, duration, speed) { positionMs, durationMs, playbackSpeed ->
        val remaining = durationMs.takeIf { it > 0L }?.let { desktopRemainingOutputMs(positionMs, it, playbackSpeed) }
        remaining to (remaining?.let { (it / 60_000f).coerceIn(0f, 1f) } ?: 1f)
    }

internal class SleepTimerDeadline(
    durationMs: Long,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val deadlineMs = now() + durationMs

    fun remainingMs(): Long = (deadlineMs - now()).coerceAtLeast(0L)

    suspend fun awaitExpiry(
        fadeOut: Boolean,
        remaining: MutableStateFlow<Long>,
        uiVisible: StateFlow<Boolean>,
        playbackChanges: Flow<Unit> = emptyFlow(),
        finishCurrentSong: Boolean = false,
        applyGain: (Float) -> Unit,
    ) {
        val fadeCountdown = fadeOut && !finishCurrentSong
        val visibleCountdown = combine(uiVisible, remaining.subscriptionCount) { visible, count -> visible && count > 0 }

        fun phase(timeLeft: Long) =
            when {
                timeLeft == 0L -> 0
                fadeCountdown && timeLeft <= 60_000L -> 1
                else -> 2
            }
        val phases = playbackChanges.onStart { emit(Unit) }.map { phase(remainingMs()) }.distinctUntilChanged()
        while (true) {
            val timeLeft = remainingMs()
            remaining.value = timeLeft
            if (fadeCountdown) applyGain((timeLeft / 60_000f).coerceIn(0f, 1f))
            if (timeLeft == 0L) return
            val visible = uiVisible.value && remaining.subscriptionCount.value > 0
            val untilFade = if (fadeCountdown) (timeLeft - 60_000L).coerceAtLeast(0L) else timeLeft
            val waitMs = if (visible || untilFade == 0L) minOf(500L, timeLeft) else untilFade
            withTimeoutOrNull(waitMs) {
                combine(
                    visibleCountdown,
                    phases,
                ) { shown, currentPhase -> shown != visible || currentPhase != phase(timeLeft) }.first { it }
            }
        }
    }
}
