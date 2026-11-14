package com.metrolist.shared.playback

internal fun loadedPlaybackBufferEndMs(
    positionMs: Long,
    durationMs: Long,
    ranges: List<Pair<Long, Long>>,
): Long {
    val position = positionMs.coerceAtLeast(0L)
    val end =
        ranges.sortedBy { it.first }.fold(position) { buffered, (start, end) ->
            if (start <= buffered && end >= start) maxOf(buffered, end) else buffered
        }
    return end.coerceAtMost(maxOf(position, durationMs.takeIf { it > 0L } ?: Long.MAX_VALUE))
}
