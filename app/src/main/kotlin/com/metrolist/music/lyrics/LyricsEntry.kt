/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.lyrics

import kotlinx.coroutines.flow.MutableStateFlow

data class WordTimestamp(
    val text: String,
    val startTime: Double,
    val endTime: Double
)

data class LyricsEntry(
    val time: Long,
    val text: String,
    val words: List<WordTimestamp>? = null,
    val romanizedTextFlow: MutableStateFlow<String?> = MutableStateFlow(null),
    val agent: String? = null,
    val isBackground: Boolean = false
) : Comparable<LyricsEntry> {
    override fun compareTo(other: LyricsEntry): Int = (time - other.time).toInt()

    companion object {
        val HEAD_LYRICS_ENTRY = LyricsEntry(0L, "")
    }
}
