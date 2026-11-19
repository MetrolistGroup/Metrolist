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

        assertEquals("existing background", merged.first().backgroundText)
        assertTrue(mainTimeline.hasActiveMainVocals(1_500))
        assertEquals("Main lyric (existing background background)", selectedLyricsText(merged, setOf(0)))
    }
}
