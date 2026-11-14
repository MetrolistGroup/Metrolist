/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.api.lyrics

import com.metrolist.shared.model.MlxDocument
import com.metrolist.shared.model.WordTimestamp

private val lrcOffset =
    Regex("""^[ \t]*\[offset:[ \t]*([+-]?\d+)[ \t]*][ \t]*\r?$""", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))

internal fun lrcOffsetMs(text: String): Long =
    lrcOffset
        .findAll(text)
        .lastOrNull()
        ?.groupValues
        ?.get(1)
        ?.toLongOrNull() ?: 0L

internal fun MlxDocument.withLrcOffset(offsetMs: Long): MlxDocument {
    if (offsetMs == 0L) return this

    fun shift(timeMs: Long): Long = timeMs + offsetMs.coerceIn(-timeMs, Long.MAX_VALUE - timeMs)

    fun WordTimestamp.shifted(): WordTimestamp =
        copy(
            startTime = (startTime + offsetMs / 1000.0).coerceAtLeast(0.0),
            endTime = (endTime + offsetMs / 1000.0).coerceAtLeast(0.0),
        )
    return copy(
        lines =
            lines.map { line ->
                if (line.endTime == null && line.words.isNullOrEmpty() && line.time == 0L) {
                    line
                } else {
                    line.copy(
                        time = shift(line.time),
                        endTime = line.endTime?.let(::shift),
                        words = line.words?.map { it.shifted() },
                        romanizedWords = line.romanizedWords?.map { it.shifted() },
                    )
                }
            },
        songParts = songParts.map { it.copy(startMs = shift(it.startMs), endMs = it.endMs?.let(::shift)) },
    )
}
