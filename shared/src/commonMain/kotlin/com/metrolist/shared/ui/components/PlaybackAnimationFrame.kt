package com.metrolist.shared.ui.components

import androidx.compose.runtime.withFrameMillis
import kotlinx.coroutines.delay
import kotlin.time.Duration
import kotlin.time.TimeSource

internal val playbackAnimationOrigin = TimeSource.Monotonic.markNow()

internal fun playbackAnimationDelayMillis(
    nowMillis: Long,
    intervalMillis: Long,
): Long = if (intervalMillis > 0L) intervalMillis - nowMillis.mod(intervalMillis) else 0L

internal suspend fun withPlaybackAnimationFrame(
    interval: Duration,
    onFrame: (Long) -> Unit,
) {
    if (interval > Duration.ZERO) {
        delay(playbackAnimationDelayMillis(playbackAnimationOrigin.elapsedNow().inWholeMilliseconds, interval.inWholeMilliseconds))
    }
    withFrameMillis(onFrame)
}
