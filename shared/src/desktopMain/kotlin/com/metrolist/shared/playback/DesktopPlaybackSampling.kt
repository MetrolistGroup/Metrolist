package com.metrolist.shared.playback

import com.metrolist.shared.playback.sponsorblock.SponsorBlockSegment
import kotlin.math.ceil

const val DESKTOP_PRELOAD_MIN_POSITION_MS = 500L

internal fun desktopPlaybackSampleDelayMs(
    visible: Boolean,
    crossfading: Boolean,
    positionMs: Long,
    durationMs: Long,
    speed: Float,
    transitionRemainingMs: Long?,
    segment: SponsorBlockSegment? = null,
    starting: Boolean = false,
    preparationRemainingMs: Long? = null,
): Long {
    if (visible || crossfading || starting) return 50L
    val untilTransition =
        if (durationMs > 0L && transitionRemainingMs != null) {
            ((durationMs - positionMs) / speed).toLong() - transitionRemainingMs
        } else {
            Long.MAX_VALUE
        }
    val untilPreparation =
        if (durationMs > 0L && preparationRemainingMs != null) {
            val untilLead = ((durationMs - positionMs) / speed).toLong() - preparationRemainingMs
            if (positionMs <= DESKTOP_PRELOAD_MIN_POSITION_MS) {
                maxOf(untilLead, ceil((DESKTOP_PRELOAD_MIN_POSITION_MS + 1L - positionMs).toDouble() / speed).toLong())
            } else {
                untilLead.takeIf { it > 0L } ?: Long.MAX_VALUE
            }
        } else {
            Long.MAX_VALUE
        }
    val untilSegment = segment?.let { ((it.startMs - positionMs) / speed).toLong() } ?: Long.MAX_VALUE
    return minOf(untilPreparation, untilTransition - 50L, untilSegment - 50L).coerceIn(50L, 1_000L)
}
