package com.metrolist.shared.model

import kotlin.math.roundToLong

data class SelectedLyricsLine(
    val sourceIndex: Int,
    val entry: LyricsEntry,
    val endTimeMs: Long?,
)

data class LyricsShareSelection(
    val lines: List<SelectedLyricsLine>,
) {
    val text: String get() = lines.joinToString("\n") { it.entry.text }
}

data class LyricsShareRequest(
    val selection: LyricsShareSelection,
    val songId: String,
    val title: String,
    val artist: String,
    val artworkUrl: String?,
)

fun selectedLyricsSnapshot(
    entries: List<LyricsEntry>,
    indices: Set<Int>,
): LyricsShareSelection =
    LyricsShareSelection(
        indices.sorted().mapNotNull { index ->
            val source = entries.getOrNull(index)?.takeIf { it.text.isNotBlank() } ?: return@mapNotNull null
            val entry = source.copy(words = source.words?.toList(), romanizedWords = source.romanizedWords?.toList())
            val nextStart = entries.subList(index + 1, entries.size).firstOrNull { it.time > entry.time }?.time
            val wordEnd = entry.words?.filterNot { it.isLineTimed }?.mapNotNull { it.endTime.wordTimeMs() }?.maxOrNull()
            val end = entry.endTime?.takeIf { it > entry.time } ?: nextStart ?: wordEnd?.takeIf { it > entry.time }
            SelectedLyricsLine(index, entry, end)
        },
    )

private fun Double.wordTimeMs(): Long? {
    if (!isFinite() || this < 0 || this >= Long.MAX_VALUE.toDouble() / 1000.0) return null
    return (this * 1000.0).roundToLong()
}
