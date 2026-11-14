package com.metrolist.android.playback

import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal fun shouldSamplePlaybackProgress(
    isPlaying: Boolean,
    wantsPlayback: Boolean,
    playbackState: Int,
): Boolean = isPlaying || (wantsPlayback && playbackState == Player.STATE_BUFFERING)

internal suspend inline fun runCrossfadeFrames(
    playbackActive: Flow<Boolean>,
    frame: () -> Boolean,
) {
    val active = MutableStateFlow(false)
    val observer = CoroutineScope(currentCoroutineContext()).launch { playbackActive.collect { active.value = it } }
    try {
        while (currentCoroutineContext().isActive) {
            active.first { it }
            if (!frame()) return
            delay(CROSSFADE_UPDATE_INTERVAL_MS)
        }
    } finally {
        observer.cancel()
    }
}

internal fun shouldMonitorPlaybackProgress(
    playWhenReady: Boolean,
    suppressionReason: Int,
    playbackState: Int,
    transitionPending: Boolean,
    offloadRequested: Boolean,
): Boolean =
    playWhenReady && suppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE && !transitionPending &&
        (playbackState == Player.STATE_READY || (offloadRequested && playbackState == Player.STATE_BUFFERING))

internal fun playbackPollIntervalMs(
    fineProgressActive: Boolean,
    positionMs: Long,
    durationMs: Long,
    playbackSpeed: Float,
    crossfadeEnabled: Boolean,
    crossfadeDurationMs: Long,
): Long {
    if (fineProgressActive) return 100L
    if (!crossfadeEnabled || durationMs <= 0L) return 1_000L
    val untilCrossfadeMs = durationMs - positionMs - crossfadeDurationMs
    return if (untilCrossfadeMs <= 1_100L * playbackSpeed.coerceAtLeast(1f)) 100L else 1_000L
}
