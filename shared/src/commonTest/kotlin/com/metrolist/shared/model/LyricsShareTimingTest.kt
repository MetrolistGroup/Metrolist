package com.metrolist.shared.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LyricsShareTimingTest {
    @Test
    fun repeatedTextKeepsOriginalIndicesAndUnselectedGap() {
        val entries = listOf(
            LyricsEntry(time = 1_000, text = "Repeat"),
            LyricsEntry(time = 2_000, text = "Do not include"),
            LyricsEntry(time = 3_000, text = "Repeat", endTime = 4_000),
        )
        val share = selectedLyricsSnapshot(entries, setOf(2, 0))
        assertEquals(listOf(0, 2), share.lines.map { it.sourceIndex })
        assertEquals("Repeat\nRepeat", share.text)
        assertEquals(3_000L, share.durationMs)
        assertEquals(0, share.activeLineAt(999))
        assertNull(share.activeLineAt(1_000))
        assertNull(share.activeLineAt(1_999))
        assertEquals(1, share.activeLineAt(2_000))
        assertNull(share.activeLineAt(3_000))
    }

    @Test
    fun wordOffsetsUseTheCombinedTextAndExactHalfOpenBoundaries() {
        val first = LyricsEntry(
            time = 1_100,
            text = "🌙 night",
            endTime = 2_000,
            words = listOf(
                WordTimestamp("🌙", 1.100, 1.301, hasTrailingSpace = true),
                WordTimestamp("night", 1.301, 2.000),
            ),
        )
        val second = LyricsEntry(
            time = 2_000,
            text = "night",
            endTime = 2_500,
            words = listOf(WordTimestamp("night", 2.000, 2.500)),
        )
        val share = selectedLyricsSnapshot(listOf(first, second), setOf(0, 1))
        assertTrue(share.hasWordTiming)
        assertEquals(0..1, share.activeWordRangeAt(200))
        assertEquals(3..7, share.activeWordRangeAt(201))
        assertEquals(9..13, share.activeWordRangeAt(900))
        assertNull(share.activeWordRangeAt(1_400))
    }

    @Test
    fun invalidWordsDoNotDisableValidLineTiming() {
        val row = LyricsEntry(
            time = 5_000,
            text = "Actual words",
            endTime = 6_000,
            words = listOf(WordTimestamp("different", 5.000, 6.000, isLineTimed = true)),
        )
        val share = selectedLyricsSnapshot(listOf(row), setOf(0))
        assertEquals(1_000L, share.durationMs)
        assertEquals(0, share.activeLineAt(0))
        assertFalse(share.hasWordTiming)
        assertNull(share.activeWordRangeAt(0))
    }

    @Test
    fun timingUnavailableWithoutEndpointsOrForAClipOverTheBound() {
        assertNull(selectedLyricsSnapshot(listOf(LyricsEntry(time = 0, text = "Plain")), setOf(0)).durationMs)
        assertNull(selectedLyricsSnapshot(listOf(LyricsEntry(time = 0, text = "Too long", endTime = 30_001)), setOf(0)).durationMs)
    }
}
