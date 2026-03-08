package com.metrolist.shared.playback

internal class WatchTimeSegments {
    data class Drained(
        val startSeconds: String,
        val endSeconds: String,
        val segments: List<Pair<Long, Long>>,
    )

    private val pending = mutableListOf<Pair<Long, Long>>()
    private val reported = mutableListOf<Pair<Long, Long>>()
    private var openStartMs: Long? = null
    private var lastPositionMs = 0L

    val isOpen: Boolean
        get() = openStartMs != null

    fun lastKnownPositionMs(): Long = lastPositionMs

    fun onPlay(positionMs: Long) {
        val position = positionMs.coerceAtLeast(0L)
        if (openStartMs == null) openStartMs = position
        lastPositionMs = position
    }

    fun onProgress(positionMs: Long) {
        if (openStartMs == null) return
        val position = positionMs.coerceAtLeast(0L)
        if (position < lastPositionMs) return
        lastPositionMs = position
    }

    fun onPause(positionMs: Long) {
        closeAt(positionMs)
    }

    fun onSeek(
        fromPositionMs: Long,
        toPositionMs: Long,
        continuePlaying: Boolean,
    ) {
        closeAt(fromPositionMs)
        val target = toPositionMs.coerceAtLeast(0L)
        if (continuePlaying) openStartMs = target
        lastPositionMs = target
    }

    fun drain(
        currentPositionMs: Long,
        continuePlaying: Boolean,
    ): Drained? {
        if (openStartMs != null) closeAt(maxOf(currentPositionMs, lastPositionMs))
        val emitted =
            merge(pending)
                .flatMap(::subtractReported)
                .filter { (start, end) -> end - start >= MIN_SEGMENT_MS }
        pending.clear()
        if (continuePlaying) {
            val position = currentPositionMs.coerceAtLeast(0L)
            openStartMs = position
            lastPositionMs = position
        }
        if (emitted.isEmpty()) return null
        val updatedReported = merge(reported + emitted)
        reported.clear()
        reported += updatedReported
        return Drained(
            startSeconds = emitted.joinToString(",") { formatSeconds(it.first) },
            endSeconds = emitted.joinToString(",") { formatSeconds(it.second) },
            segments = emitted,
        )
    }

    private fun subtractReported(segment: Pair<Long, Long>): List<Pair<Long, Long>> {
        var remaining = listOf(segment)
        reported.forEach { covered ->
            remaining =
                remaining.flatMap { candidate ->
                    when {
                        covered.second <= candidate.first || covered.first >= candidate.second -> listOf(candidate)
                        covered.first <= candidate.first && covered.second >= candidate.second -> emptyList()
                        covered.first <= candidate.first -> listOf(covered.second to candidate.second)
                        covered.second >= candidate.second -> listOf(candidate.first to covered.first)
                        else -> listOf(candidate.first to covered.first, covered.second to candidate.second)
                    }
                }
        }
        return remaining
    }

    private fun merge(segments: List<Pair<Long, Long>>): List<Pair<Long, Long>> {
        val sorted = segments.filter { it.second > it.first }.sortedBy { it.first }
        if (sorted.isEmpty()) return emptyList()
        val merged = mutableListOf(sorted.first())
        sorted.drop(1).forEach { segment ->
            val previous = merged.last()
            if (segment.first <= previous.second) {
                merged[merged.lastIndex] = previous.first to maxOf(previous.second, segment.second)
            } else {
                merged += segment
            }
        }
        return merged
    }

    private fun closeAt(positionMs: Long) {
        val start = openStartMs ?: return
        val end = maxOf(positionMs, start)
        if (end > start) pending += start to end
        openStartMs = null
        lastPositionMs = end
    }

    companion object {
        const val MIN_SEGMENT_MS = 500L

        fun formatSeconds(milliseconds: Long): String {
            val tenths = (milliseconds.coerceAtLeast(0L) + 50L) / 100L
            return "${tenths / 10L}.${tenths % 10L}"
        }
    }
}
