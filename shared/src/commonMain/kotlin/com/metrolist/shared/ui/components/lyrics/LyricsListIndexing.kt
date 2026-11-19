/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.ui.components.lyrics

import com.metrolist.shared.model.LyricsEntry

sealed interface LyricsListItem {
    data class Line(
        val index: Int,
        val entry: LyricsEntry,
    ) : LyricsListItem

    data class Indicator(
        val gapStartMs: Long,
        val gapEndMs: Long,
        val afterLineIndex: Int,
    ) : LyricsListItem

    data class Provider(
        val name: String? = null,
        val songwriters: List<String> = emptyList(),
    ) : LyricsListItem
}

internal const val LYRICS_GAP_MIN_MS = 7000L
internal const val LYRICS_GAP_START_LAG_MS = 310L
internal const val LYRICS_GAP_END_LEAD_MS = 800L

internal fun mergeBackgroundLyricsEntries(lyrics: List<LyricsEntry>): List<LyricsEntry> {
    if (lyrics.none(LyricsEntry::isBackground)) return lyrics
    val displayLines = lyrics.toMutableList()
    lyrics.forEachIndexed { index, background ->
        if (!background.isBackground) return@forEachIndexed
        val mainIndex = (index - 1 downTo 0).firstOrNull { !lyrics[it].isBackground } ?: return@forEachIndexed
        val main = displayLines[mainIndex]
        val backgroundWords = background.words.orEmpty().map { it.copy(isBackground = true) }
        val backgroundText =
            if (backgroundWords.isEmpty()) {
                background.text
                    .trim()
                    .removeSurrounding("(", ")")
                    .takeIf(String::isNotBlank)
            } else {
                null
            }
        val backgroundEndTime =
            maxOf(
                background.endTime ?: Long.MIN_VALUE,
                backgroundWords.maxOfOrNull { (it.endTime * 1000.0).toLong() } ?: Long.MIN_VALUE,
            ).takeIf { it != Long.MIN_VALUE }
        displayLines[mainIndex] =
            main.copy(
                words = (main.words.orEmpty() + backgroundWords).takeIf { it.isNotEmpty() },
                romanizedWords =
                    (main.romanizedWords.orEmpty() + background.romanizedWords.orEmpty().map { it.copy(isBackground = true) })
                        .takeIf { it.isNotEmpty() },
                endTime = listOfNotNull(main.endTime, backgroundEndTime).maxOrNull(),
                backgroundText =
                    listOfNotNull(main.backgroundText?.takeIf(String::isNotBlank), backgroundText)
                        .joinToString(" ")
                        .takeIf(String::isNotBlank),
                backgroundTranslatedText =
                    listOfNotNull(
                        main.backgroundTranslatedText?.takeIf(String::isNotBlank),
                        background.translatedText?.takeIf(String::isNotBlank),
                        background.backgroundTranslatedText?.takeIf(String::isNotBlank),
                    ).joinToString(" ").takeIf(String::isNotBlank),
            )
        displayLines[index] =
            background.copy(
                text = "",
                words = null,
                romanizedText = null,
                romanizedWords = null,
                translatedText = null,
                backgroundText = null,
                backgroundTranslatedText = null,
            )
    }
    return displayLines
}

internal fun buildLyricsListItems(
    lyrics: List<LyricsEntry>,
    provider: String? = null,
    songwriters: List<String> = emptyList(),
    activeByRealTiming: Boolean = false,
): List<LyricsListItem> {
    val lines =
        lyrics.mapIndexedNotNull { index, entry ->
            if (entry.isBackground && entry.text.isBlank() && entry.words.isNullOrEmpty()) {
                null
            } else {
                LyricsListItem.Line(index, entry)
            }
        }
    val timeline = buildLyricsTimelineItems(lines, lyrics)
    return buildList {
        var lastEndTime: Long? = null
        if ((lyrics.firstOrNull()?.time ?: 0L) >= LYRICS_GAP_MIN_MS) {
            add(LyricsListItem.Indicator(0L, lyrics.first().time - LYRICS_GAP_END_LEAD_MS, -1))
        }
        for ((linePosition, line) in lines.withIndex()) {
            val previousEnd = lastEndTime
            if (previousEnd != null && line.entry.time - previousEnd >= LYRICS_GAP_MIN_MS) {
                add(
                    LyricsListItem.Indicator(
                        previousEnd + LYRICS_GAP_START_LAG_MS,
                        line.entry.time - LYRICS_GAP_END_LEAD_MS,
                        line.index - 1,
                    ),
                )
            }
            add(line)
            val item = timeline[linePosition]
            val endTimeMs = if (activeByRealTiming) item.sourceEndTimeMs else item.endTimeMs
            lastEndTime = maxOf(previousEnd ?: 0L, endTimeMs)
        }
        if (!provider.isNullOrEmpty() || songwriters.isNotEmpty()) add(LyricsListItem.Provider(provider, songwriters))
    }
}

class LyricsItemIndex(
    val firstLineListIndex: Int,
    val lineListIndices: IntArray,
)

fun buildLyricsItemIndex(
    processedItems: List<LyricsListItem>,
    lineCount: Int,
): LyricsItemIndex {
    var firstLineListIndex = -1
    val lineListIndices = IntArray(lineCount) { -1 }

    processedItems.forEachIndexed { listIndex, item ->
        if (item is LyricsListItem.Line) {
            if (firstLineListIndex == -1) firstLineListIndex = listIndex
            if (item.index in lineListIndices.indices) {
                lineListIndices[item.index] = listIndex
            }
        }
    }

    return LyricsItemIndex(
        firstLineListIndex = firstLineListIndex.coerceAtLeast(0),
        lineListIndices = lineListIndices,
    )
}
