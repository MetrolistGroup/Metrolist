package com.metrolist.shared.model

import kotlin.math.roundToLong

val LyricsShareSelection.durationMs: Long?
    get() {
        val first = lines.firstOrNull()?.entry?.time ?: return null
        if (first < 0 || lines.any { it.entry.time < first || it.endTimeMs == null || it.endTimeMs <= it.entry.time }) return null
        if (lines.zipWithNext().any { (a, b) -> a.entry.time > b.entry.time }) return null
        return (lines.maxOf { it.endTimeMs!! } - first).takeIf { it in 1..30_000 }
    }

fun LyricsShareSelection.activeLineAt(positionMs: Long): Int? {
    val duration = durationMs ?: return null
    if (positionMs !in 0 until duration) return null
    val absoluteMs = lines.first().entry.time + positionMs
    return lines.indexOfLast { absoluteMs >= it.entry.time && absoluteMs < it.endTimeMs!! }.takeIf { it >= 0 }
}

private data class ShareWordRange(
    val startMs: Long,
    val endMs: Long,
    val range: IntRange,
)

private fun Double.wordTimeMs(): Long? {
    if (!isFinite() || this < 0 || this >= Long.MAX_VALUE.toDouble() / 1000.0) return null
    return (this * 1000.0).roundToLong()
}

private fun SelectedLyricsLine.wordRanges(): List<ShareWordRange>? {
    val words = entry.words?.takeIf { it.isNotEmpty() } ?: return null
    val lineEnd = endTimeMs ?: return null
    if (entry.time < 0 || lineEnd <= entry.time) return null
    val joined = StringBuilder()
    val ranges = mutableListOf<ShareWordRange>()
    var previousEnd = entry.time
    for ((index, word) in words.withIndex()) {
        if (word.isLineTimed || word.text.isEmpty()) return null
        val start = word.startTime.wordTimeMs() ?: return null
        val end = word.endTime.wordTimeMs() ?: return null
        if (start < previousEnd || end <= start || end > lineEnd) return null
        val offset = joined.length
        joined.append(word.text)
        ranges += ShareWordRange(start, end, offset until joined.length)
        if (word.hasTrailingSpace && index < words.lastIndex) joined.append(' ')
        previousEnd = end
    }
    if (joined.toString() != entry.text) return null
    return ranges
}

val LyricsShareSelection.hasWordTiming: Boolean
    get() = durationMs != null && lines.all { it.wordRanges() != null }

fun LyricsShareSelection.activeWordRangeAt(positionMs: Long): IntRange? {
    if (!hasWordTiming) return null
    val active = activeLineAt(positionMs) ?: return null
    val absoluteMs = lines.first().entry.time + positionMs
    val range = lines[active].wordRanges()?.firstOrNull { absoluteMs >= it.startMs && absoluteMs < it.endMs }?.range ?: return null
    val offset = lines.take(active).sumOf { it.entry.text.length + 1 }
    return (range.first + offset)..(range.last + offset)
}
