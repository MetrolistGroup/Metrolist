package com.metrolist.sponsorblock.models

/**
 * A single stretch of a track that should be skipped, in milliseconds.
 *
 * [startMs] is inclusive and [endMs] is exclusive.
 */
data class Segment(
    val category: SponsorBlockCategory,
    val startMs: Long,
    val endMs: Long,
) {
    val durationMs: Long get() = endMs - startMs

    fun contains(positionMs: Long): Boolean = positionMs >= startMs && positionMs < endMs
}

/**
 * Drops unusable segments, sorts them, and merges any that overlap or touch.
 *
 * Merging matters: the API can return several segments that butt up against each
 * other (an intro immediately followed by a sponsor read). Skipping to the end of
 * the first would land inside the second and cause a second seek, which sounds
 * like a stutter. Merged, that becomes one clean jump.
 *
 * [minDurationMs] discards segments too short to be worth a seek — skipping those
 * is more jarring than just playing them.
 */
fun List<Segment>.sanitized(minDurationMs: Long = MIN_SEGMENT_DURATION_MS): List<Segment> {
    val usable = filter { it.durationMs >= minDurationMs && it.startMs >= 0 }
        .sortedBy { it.startMs }
    if (usable.isEmpty()) return emptyList()

    val merged = ArrayList<Segment>(usable.size)
    var current = usable.first()
    for (next in usable.drop(1)) {
        current = if (next.startMs <= current.endMs) {
            // Overlapping or touching: widen the current segment.
            if (next.endMs > current.endMs) current.copy(endMs = next.endMs) else current
        } else {
            merged += current
            next
        }
    }
    merged += current
    return merged
}

/** The segment covering [positionMs], or null when the position is in normal content. */
fun List<Segment>.segmentAt(positionMs: Long): Segment? = firstOrNull { it.contains(positionMs) }

/**
 * The first segment that begins after [positionMs].
 *
 * Used to sleep exactly until the next skip is due instead of polling the player.
 */
fun List<Segment>.nextSegmentAfter(positionMs: Long): Segment? =
    filter { it.startMs > positionMs }.minByOrNull { it.startMs }

/** Segments shorter than this are not worth interrupting playback for. */
const val MIN_SEGMENT_DURATION_MS = 1_000L
