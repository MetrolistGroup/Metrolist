package com.metrolist.shared.playback

import kotlin.math.abs

data class PendingSeekPosition(
    val targetMs: Long,
    val previousPositionMs: Long,
) {
    fun isAcknowledged(
        positionMs: Long,
        renderedPositionMs: Long,
    ): Boolean {
        if (targetMs == previousPositionMs) return true
        return abs(positionMs - targetMs) < abs(positionMs - previousPositionMs) &&
            abs(renderedPositionMs - targetMs) < abs(renderedPositionMs - previousPositionMs)
    }
}
