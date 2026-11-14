package com.metrolist.shared.api.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class LrcConversionTest {
    @Test
    fun enhancedLrcRetainsWordTimingThroughMlxConversion() {
        val mlx = assertNotNull(lrcToMlx("[00:01.000]<00:01.000>Hello <00:01.500>world<00:02.000>", null, null))
        val line = LyricsUtils.parseLyrics(mlx).single()
        assertEquals("Hello world", line.text)
        assertEquals(listOf(1.0, 1.5), line.words?.map { it.startTime })
        assertEquals(listOf(1.5, 2.0), line.words?.map { it.endTime })
    }

    @Test
    fun expandsWholeSecondAndRepeatedTimestampsInTimeOrder() {
        val mlx = assertNotNull(lrcToMlx("[ar:Artist]\n[00:12][00:03.5]Repeat\n[00:04.25]Next\n[01:00.123]Last", null, null))
        val lines = LyricsUtils.parseLyrics(mlx)
        assertEquals(listOf(3500L, 4250L, 12000L, 60123L), lines.map { it.time })
        assertEquals(listOf("Repeat", "Next", "Repeat", "Last"), lines.map { it.text })
    }
}
