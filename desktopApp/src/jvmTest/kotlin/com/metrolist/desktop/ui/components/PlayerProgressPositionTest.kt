package com.metrolist.desktop.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class PlayerProgressPositionTest {
    @Test
    fun intervalWaitsForOnePixelOfTravelAndFollowsResizing() {
        assertEquals(155.milliseconds, progressFrameInterval(262_000L, 1700, 1f))
        assertEquals(78.milliseconds, progressFrameInterval(262_000L, 3400, 1f))
        for (width in listOf(1200, 1700, 1920, 3400)) {
            val interval = progressFrameInterval(262_000L, width, 1f).inWholeMilliseconds
            assertTrue(interval * width >= 262_000L)
            assertTrue((interval - 1L) * width < 262_000L)
        }
    }

    @Test
    fun playbackSpeedChangesTheTimeBetweenPixels() {
        assertEquals(120.milliseconds, progressFrameInterval(120_000L, 1000, 1f))
        assertEquals(60.milliseconds, progressFrameInterval(120_000L, 1000, 2f))
        assertEquals(240.milliseconds, progressFrameInterval(120_000L, 1000, 0.5f))
    }

    @Test
    fun shortTracksLongTracksAndUnmeasuredSlidersHaveBoundedIntervals() {
        assertEquals(16.milliseconds, progressFrameInterval(1000L, 1920, 1f))
        assertEquals(250.milliseconds, progressFrameInterval(7_200_000L, 300, 1f))
        assertEquals(250.milliseconds, progressFrameInterval(Long.MAX_VALUE, 1, 1f))
        assertEquals(250.milliseconds, progressFrameInterval(262_000L, 0, 1f))
        assertEquals(250.milliseconds, progressFrameInterval(0L, 1700, 1f))
        assertEquals(250.milliseconds, progressFrameInterval(-1L, -1, 1f))
        assertEquals(250.milliseconds, progressFrameInterval(120_000L, 1000, 0f))
        assertEquals(120.milliseconds, progressFrameInterval(120_000L, 1000, Float.NaN))
    }
}
