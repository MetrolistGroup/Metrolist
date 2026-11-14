package com.metrolist.shared.playback

import kotlin.time.Duration.Companion.milliseconds

internal val WARM_REPLACEMENT_STALL = 1_500.milliseconds

class PlaybackProgressWatchdog(
    private val timeoutMs: Long = 12_000L,
    private val endToleranceMs: Long = 2_000L,
) {
    private var playbackInstanceId = 0L
    private var positionMs = -1L
    private var lastProgressAtMs = 0L
    private var reported = false
    private var expectingProgress = false

    fun observe(
        playbackInstanceId: Long,
        positionMs: Long,
        durationMs: Long,
        expectProgress: Boolean,
        nowMs: Long,
    ): Boolean {
        if (
            playbackInstanceId != this.playbackInstanceId ||
            positionMs != this.positionMs ||
            !expectProgress || !expectingProgress ||
            playbackInstanceId <= 0L ||
            (durationMs > 0L && durationMs - positionMs <= endToleranceMs)
        ) {
            this.playbackInstanceId = playbackInstanceId
            this.positionMs = positionMs
            lastProgressAtMs = nowMs
            reported = false
            expectingProgress = expectProgress
            return false
        }
        if (reported || nowMs - lastProgressAtMs < timeoutMs) return false
        reported = true
        return true
    }

    fun stalledForMs(nowMs: Long): Long = if (expectingProgress && playbackInstanceId > 0L) nowMs - lastProgressAtMs else 0L
}
