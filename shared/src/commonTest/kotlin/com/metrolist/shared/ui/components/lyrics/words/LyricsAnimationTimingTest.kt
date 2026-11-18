package com.metrolist.shared.ui.components.lyrics.words

import com.metrolist.shared.model.WordTimestamp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LyricsAnimationTimingTest {
    @Test
    fun shortWordFillsAcrossMinimumAnimationDuration() {
        assertEquals(0.5f, lyricsWordAnimationProgress(positionMs = 50L, startMs = 0L, endMs = 20L))
        assertEquals(1f, lyricsWordAnimationProgress(positionMs = 100L, startMs = 0L, endMs = 20L))
    }

    @Test
    fun longerWordKeepsItsTimestampDuration() {
        assertEquals(0.5f, lyricsWordAnimationProgress(positionMs = 150L, startMs = 100L, endMs = 200L))
    }

    @Test
    fun vocalRangeKeepsTheLastWordRunningAfterScrollFocusMovesAndExcludesSeeksOutsideTheLine() {
        val words = listOf(WordTimestamp("First", 1.0, 2.0), WordTimestamp("last", 2.0, 3.0))
        val range = requireNotNull(lyricsWordAnimationRangeMs(words))
        assertTrue(2_340L in range)
        assertTrue(2_999L in range)
        assertTrue(3_000L in range)
        assertFalse(500L in range)
        assertFalse(6_000L in range)
        assertEquals(1f, lyricsWordAnimationProgress(range.last, 2_000L, 3_000L))
    }

    @Test
    fun vocalRangeIncludesTheMinimumAnimationDurationAndUnsortedOverlaps() {
        assertEquals(1_000L..3_100L, lyricsWordAnimationRangeMs(listOf(WordTimestamp("last", 3.0, 3.02), WordTimestamp("first", 1.0, 2.0))))
        assertNull(lyricsWordAnimationRangeMs(emptyList()))
    }
}
