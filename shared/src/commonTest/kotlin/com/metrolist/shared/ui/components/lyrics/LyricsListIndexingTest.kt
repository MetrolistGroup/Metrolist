package com.metrolist.shared.ui.components.lyrics

import com.metrolist.shared.model.LyricsEntry
import com.metrolist.shared.model.WordTimestamp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LyricsListIndexingTest {
    @Test
    fun backgroundEntryRendersWithThePrecedingMainLine() {
        val lyrics =
            listOf(
                LyricsEntry(
                    time = 1_000,
                    endTime = 2_000,
                    text = "People are watching",
                    words = listOf(WordTimestamp("People are watching", 1.0, 2.0)),
                ),
                LyricsEntry(
                    time = 1_500,
                    endTime = 2_500,
                    text = "(But we don't care care)",
                    words = listOf(WordTimestamp("But we don't care care", 1.5, 2.5, isBackground = true)),
                    isBackground = true,
                ),
                LyricsEntry(time = 3_000, text = "Way I'm touching your body"),
            )

        val displayLyrics = mergeBackgroundLyricsEntries(lyrics)
        val items = buildLyricsListItems(displayLyrics)
        val lines = items.filterIsInstance<LyricsListItem.Line>()

        assertEquals(3, displayLyrics.size)
        assertEquals(2, lines.size)
        assertEquals("People are watching", lines.first().entry.text)
        assertTrue(
            lines
                .first()
                .entry.words
                .orEmpty()
                .last()
                .isBackground,
        )
        assertTrue(displayLyrics[1].text.isBlank())
        assertEquals(null, displayLyrics[1].words)
        assertEquals("Way I'm touching your body", lines.last().entry.text)
    }

    @Test
    fun backgroundEntryTextAndRomanizedMainTimingArePreserved() {
        val lyrics =
            listOf(
                LyricsEntry(
                    time = 1_000,
                    text = "Main lyric",
                    romanizedWords = listOf(WordTimestamp("Main", 1.0, 2.0)),
                ),
                LyricsEntry(
                    time = 1_500,
                    text = "(background)",
                    isBackground = true,
                    words = listOf(WordTimestamp("background", 1.5, 2.5, isBackground = true)),
                    backgroundText = "existing background",
                ),
            )

        val merged = mergeBackgroundLyricsEntries(lyrics)
        val items = buildLyricsListItems(merged)
        val timeline = buildLyricsTimelineItems(items, merged)
        val mainTimeline = timeline.first { !it.isGap }

        assertEquals("background existing background", merged.first().backgroundText)
        assertTrue(mainTimeline.hasActiveMainVocals(1_500))
        assertEquals("Main lyric (background existing background)", selectedLyricsText(merged, setOf(0)))
        assertTrue(
            merged
                .first()
                .romanizedWords
                .orEmpty()
                .any { it.isBackground && it.text == "background" },
        )
    }

    @Test
    fun mainTextBackgroundPhraseIsNotDuplicatedInRomanizedWords() {
        val lyrics =
            listOf(
                LyricsEntry(
                    time = 1_000,
                    text = "Main lyric",
                    endTime = 2_000,
                    backgroundText = "existing background",
                    romanizedWords = listOf(WordTimestamp("Main", 1.0, 2.0)),
                ),
                LyricsEntry(
                    time = 1_500,
                    text = "(new background)",
                    isBackground = true,
                    words = listOf(WordTimestamp("new background", 1.5, 2.5, isBackground = true)),
                ),
            )

        val merged = mergeBackgroundLyricsEntries(lyrics).first()

        assertEquals(
            1,
            merged
                .romanizedWords
                .orEmpty()
                .count { it.isBackground && it.text == "existing background" },
        )
    }

    @Test
    fun romanizedTextOnlyBackgroundIsRetainedWhenItsEntryIsMerged() {
        val lyrics =
            listOf(
                LyricsEntry(time = 0, text = "Main lyric", romanizedText = "Main romanized"),
                LyricsEntry(time = 0, text = "(background)", isBackground = true, romanizedText = "background romanized"),
            )

        val merged = mergeBackgroundLyricsEntries(lyrics).first()

        assertEquals("Main romanized", merged.romanizedText)
        assertEquals("background romanized", merged.romanizedWords.orEmpty().backgroundText())
    }

    @Test
    fun differentBackgroundTextWithMatchingWordTimesRemainsInRomanization() {
        val lyrics =
            listOf(
                LyricsEntry(
                    time = 1_000,
                    endTime = 2_000,
                    text = "Main lyric",
                    backgroundText = "Echo",
                    words =
                        listOf(
                            WordTimestamp("Main", 1.0, 2.0),
                            WordTimestamp("Harmony", 1.0, 2.0, isBackground = true),
                        ),
                    romanizedWords =
                        listOf(
                            WordTimestamp("Main roman", 1.0, 2.0),
                            WordTimestamp("Hāmonī", 1.0, 2.0, isBackground = true),
                        ),
                ),
                LyricsEntry(time = 1_500, text = "New vocal", isBackground = true),
            )

        val mergedBackgroundText =
            mergeBackgroundLyricsEntries(lyrics)
                .first()
                .romanizedWords
                .orEmpty()
                .backgroundText()

        assertEquals("Hāmonī Echo New vocal", mergedBackgroundText)
    }

    @Test
    fun textOnlyBackgroundIsRenderedAlongsideExistingTimedBackgroundWords() {
        val lyrics =
            listOf(
                LyricsEntry(
                    time = 1_000,
                    text = "Main lyric",
                    words = listOf(WordTimestamp("old vocal", 1.0, 2.0, hasTrailingSpace = false, isBackground = true)),
                ),
                LyricsEntry(time = 1_500, text = "new vocal", isBackground = true),
            )

        val merged = mergeBackgroundLyricsEntries(lyrics).first()

        assertEquals("old vocal new vocal", merged.words.orEmpty().backgroundText())
        assertEquals("old vocal new vocal", merged.backgroundText)
    }

    @Test
    fun selectedLyricsTextDeduplicatesBackgroundAndPreservesTokenSpacing() {
        val lyrics =
            listOf(
                LyricsEntry(
                    time = 1_000,
                    text = "Lead",
                    words = listOf(WordTimestamp("Echo", 1.0, 2.0, hasTrailingSpace = false, isBackground = true)),
                    backgroundText = "(Echo)",
                ),
                LyricsEntry(
                    time = 2_000,
                    text = "Main",
                    words =
                        listOf(
                            WordTimestamp("But", 2.0, 2.2, hasTrailingSpace = true, isBackground = true),
                            WordTimestamp("we", 2.2, 2.4, hasTrailingSpace = true, isBackground = true),
                            WordTimestamp("care", 2.4, 2.6, hasTrailingSpace = false, isBackground = true),
                        ),
                ),
            )

        assertEquals("Lead (Echo)\nMain (But we care)", selectedLyricsText(lyrics, setOf(0, 1)))
    }

    @Test
    fun nestedBackgroundEntryKeepsBothVocalTextsAndTheirOrder() {
        val lyrics =
            listOf(
                LyricsEntry(time = 1_000, text = "Main line"),
                LyricsEntry(
                    time = 1_500,
                    text = "Lead vocal",
                    isBackground = true,
                    backgroundText = "Echo vocal",
                    words =
                        listOf(
                            WordTimestamp("Echo vocal", 1.5, 1.5, hasTrailingSpace = false, isBackground = true, isLineTimed = true),
                        ),
                ),
            )

        val merged = mergeBackgroundLyricsEntries(lyrics).first()

        assertEquals("Lead vocal Echo vocal", merged.words.orEmpty().backgroundText())
        assertEquals("Main line (Lead vocal Echo vocal)", selectedLyricsText(listOf(merged), setOf(0)))
    }

    @Test
    fun leadingBackgroundRowIsSharedOnlyOnce() {
        val background =
            LyricsEntry(
                time = 1_000,
                text = "(Echo)",
                isBackground = true,
                words = listOf(WordTimestamp("Echo", 1.0, 2.0, hasTrailingSpace = false, isBackground = true)),
            )

        assertEquals("(Echo)", selectedLyricsText(listOf(background), setOf(0)))
    }

    @Test
    fun syntheticTextBackgroundDoesNotShortenMainLineTiming() {
        val lyrics =
            listOf(
                LyricsEntry(time = 1_000, text = "Main line"),
                LyricsEntry(
                    time = 1_500,
                    endTime = 1_500,
                    text = "background",
                    isBackground = true,
                    words =
                        listOf(
                            WordTimestamp("background", 1.5, 1.5, hasTrailingSpace = false, isBackground = true, isLineTimed = true),
                        ),
                ),
                LyricsEntry(time = 6_000, text = "Next line"),
            )
        val merged = mergeBackgroundLyricsEntries(lyrics)
        val items = buildLyricsListItems(merged)
        val mainTimeline = buildLyricsTimelineItems(items, merged).first { it.lineIndex == 0 }

        assertEquals(6_000L, mainTimeline.endTimeMs)
    }
}
