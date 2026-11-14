package com.metrolist.shared.ui.components.lyrics.words

import kotlin.test.Test
import kotlin.test.assertEquals

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
}
