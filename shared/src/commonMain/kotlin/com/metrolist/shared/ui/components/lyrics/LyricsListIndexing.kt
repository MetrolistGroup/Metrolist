/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.ui.components.lyrics

import com.metrolist.shared.model.LyricsEntry
import com.metrolist.shared.model.WordTimestamp

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

private fun WordTimestamp.asBackground() = copy(isBackground = true)

private fun List<WordTimestamp>.withBackgroundPhraseBoundary(): List<WordTimestamp> {
    val lastBackground = indexOfLast(WordTimestamp::isBackground)
    return if (lastBackground >= 0 && !this[lastBackground].hasTrailingSpace) {
        toMutableList().also { it[lastBackground] = it[lastBackground].copy(hasTrailingSpace = true) }
    } else {
        this
    }
}

internal fun List<WordTimestamp>.backgroundText(): String? =
    filter(WordTimestamp::isBackground)
        .joinToString("") { it.text + if (it.hasTrailingSpace) " " else "" }
        .trim()
        .takeIf(String::isNotBlank)

internal fun normalizeBackgroundText(text: String): String =
    text
        .trim()
        .removeSurrounding("(", ")")
        .trim()
        .replace(Regex("\\s+"), " ")
        .lowercase()

private fun textBackgroundWord(
    text: String?,
    entry: LyricsEntry,
): WordTimestamp? =
    text?.takeIf(String::isNotBlank)?.let {
        WordTimestamp(
            text = it.trim().removeSurrounding("(", ")"),
            startTime = entry.time / 1000.0,
            endTime = (entry.endTime ?: entry.time) / 1000.0,
            hasTrailingSpace = true,
            isBackground = true,
            isLineTimed = true,
        )
    }

internal fun mergeBackgroundLyricsEntries(lyrics: List<LyricsEntry>): List<LyricsEntry> {
    if (lyrics.none(LyricsEntry::isBackground)) return lyrics
    val displayLines = lyrics.toMutableList()
    lyrics.forEachIndexed { index, background ->
        if (!background.isBackground) return@forEachIndexed
        val mainIndex = (index - 1 downTo 0).firstOrNull { !lyrics[it].isBackground }
        if (mainIndex == null) {
            val nextMain = lyrics.drop(index + 1).firstOrNull { !it.isBackground }
            val sourceBackground =
                listOfNotNull(
                    background.text.takeIf(String::isNotBlank),
                    background.backgroundText?.takeIf(String::isNotBlank),
                    background.words?.backgroundText(),
                ).joinToString(" ").takeIf(String::isNotBlank)
            val nextMainBackground =
                nextMain?.let {
                    listOfNotNull(it.backgroundText?.takeIf(String::isNotBlank), it.words?.backgroundText())
                        .joinToString(" ").takeIf(String::isNotBlank)
                }
            val backgroundEnd = background.endTime?.takeIf { it > background.time } ?: background.time + 4000L
            val nextMainStart = nextMain?.time
            val nextMainEnd =
                nextMain?.let { main ->
                    main.endTime?.takeIf { it > main.time } ?: main.time + 4000L
                }
            val overlapsNextMain =
                nextMainStart != null && nextMainEnd != null && background.time < nextMainEnd && nextMainStart < backgroundEnd
            if (overlapsNextMain && sourceBackground != null && nextMainBackground != null &&
                normalizeBackgroundText(sourceBackground) == normalizeBackgroundText(nextMainBackground)
            ) {
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
            return@forEachIndexed
        }
        val main = displayLines[mainIndex]
        val mainBackgroundWords = main.words.orEmpty().filter(WordTimestamp::isBackground)
        val mainWordBackgroundText = mainBackgroundWords.backgroundText()
        val mainTextBackgroundWord =
            main.backgroundText
                ?.takeIf {
                    mainWordBackgroundText == null ||
                        normalizeBackgroundText(it) != normalizeBackgroundText(mainWordBackgroundText)
                }?.let { textBackgroundWord(it, main) }
        val backgroundWords = background.words.orEmpty().map(WordTimestamp::asBackground)
        val wordBackgroundText = backgroundWords.backgroundText()
        val sourceBackgroundTexts =
            listOfNotNull(
                background.text
                    .trim()
                    .removeSurrounding("(", ")")
                    .takeIf(String::isNotBlank),
                background.backgroundText?.takeIf(String::isNotBlank),
            ).distinctBy(::normalizeBackgroundText)
        val representedSourceTextIndex =
            sourceBackgroundTexts.indexOfFirst { source ->
                wordBackgroundText?.let { normalizeBackgroundText(source) == normalizeBackgroundText(it) } == true
            }
        val allSourceTextsRepresented =
            wordBackgroundText != null &&
                normalizeBackgroundText(wordBackgroundText) == normalizeBackgroundText(sourceBackgroundTexts.joinToString(" "))
        val incomingTextBackgroundWords =
            sourceBackgroundTexts
                .filterNot { source ->
                    allSourceTextsRepresented ||
                        wordBackgroundText?.let { normalizeBackgroundText(source) == normalizeBackgroundText(it) } == true
                }.mapNotNull { textBackgroundWord(it, background) }
        val incomingBackgroundWords =
            when {
                backgroundWords.isEmpty() -> {
                    incomingTextBackgroundWords.withBackgroundPhraseBoundary()
                }

                representedSourceTextIndex > 0 -> {
                    incomingTextBackgroundWords.withBackgroundPhraseBoundary() + backgroundWords.withBackgroundPhraseBoundary()
                }

                else -> {
                    backgroundWords.withBackgroundPhraseBoundary() + incomingTextBackgroundWords.withBackgroundPhraseBoundary()
                }
            }
        val incomingRomanizedWords = background.romanizedWords?.map(WordTimestamp::asBackground)?.takeIf { it.isNotEmpty() }
        val incomingRomanizedTextWord = textBackgroundWord(background.romanizedText, background)
        val romanizedIncomingWords =
            if (incomingRomanizedWords != null) {
                val romanizedText = incomingRomanizedWords.backgroundText()
                incomingRomanizedWords.withBackgroundPhraseBoundary() +
                    listOfNotNull(
                        incomingRomanizedTextWord?.takeUnless { word ->
                            romanizedText != null && normalizeBackgroundText(word.text) == normalizeBackgroundText(romanizedText)
                        },
                    ).withBackgroundPhraseBoundary()
            } else if (incomingRomanizedTextWord != null) {
                listOf(incomingRomanizedTextWord).withBackgroundPhraseBoundary()
            } else {
                incomingBackgroundWords
            }
        val existingRomanizedWords = main.romanizedWords.orEmpty()
        val existingRawBackgroundWords = mainBackgroundWords + listOfNotNull(mainTextBackgroundWord)
        val mainWordsWithBoundary =
            if (mainTextBackgroundWord != null || incomingBackgroundWords.isNotEmpty()) {
                main.words.orEmpty().withBackgroundPhraseBoundary()
            } else {
                main.words.orEmpty()
            }
        val mergedWords =
            mainWordsWithBoundary + listOfNotNull(mainTextBackgroundWord?.copy(hasTrailingSpace = true)) + incomingBackgroundWords
        val mergedRomanizedWords =
            if (main.romanizedWords != null || incomingRomanizedWords != null || incomingRomanizedTextWord != null) {
                (
                    existingRomanizedWords.filterNot(WordTimestamp::isBackground) +
                        existingRomanizedWords.filter(WordTimestamp::isBackground) +
                        existingRawBackgroundWords.filterNot { raw ->
                            existingRomanizedWords.any { roman ->
                                roman.isBackground &&
                                    roman.startTime == raw.startTime &&
                                    roman.endTime == raw.endTime &&
                                    (!raw.isLineTimed || normalizeBackgroundText(roman.text) == normalizeBackgroundText(raw.text))
                            }
                        }
                ).withBackgroundPhraseBoundary() + romanizedIncomingWords.withBackgroundPhraseBoundary()
            } else {
                null
            }
        val timedWordEndTime =
            backgroundWords.filter { !it.isLineTimed && it.endTime > it.startTime }
                .maxOfOrNull { (it.endTime * 1000.0).toLong() }
        val nextMainEndTime = lyrics.drop(index + 1).firstOrNull { !it.isBackground }?.time?.takeIf { it > background.time }
        val backgroundFallbackEndTime =
            background.endTime?.takeIf { it > background.time }
                ?: timedWordEndTime?.takeIf { it > background.time }
                ?: nextMainEndTime
                ?: background.time + 4000L
        val backgroundEndTime =
            maxOf(
                backgroundFallbackEndTime,
                timedWordEndTime ?: Long.MIN_VALUE,
            )
        displayLines[mainIndex] =
            main.copy(
                words = mergedWords.takeIf { it.isNotEmpty() },
                romanizedWords = mergedRomanizedWords,
                endTime = listOfNotNull(main.endTime, backgroundEndTime).maxOrNull(),
                backgroundText = mergedWords.backgroundText(),
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
