package com.metrolist.shared.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TrackMarqueeTest {
    @Test
    fun stepsKeepAverageSpeedWithoutFractionalPixels() {
        for (pixelsPerSecond in listOf(30, 45, 60)) {
            val distance = pixelsPerSecond * 10
            var previous = 0
            for (tick in 1..100) {
                val elapsed = tick * 34L
                val offset = marqueeStepOffset(elapsed, distance, 10_000)
                assertEquals((elapsed * pixelsPerSecond / 1000).toInt(), offset)
                assertTrue(offset >= previous)
                previous = offset
            }
            assertEquals(pixelsPerSecond, marqueeStepOffset(1000, distance, 10_000))
        }
    }

    @Test
    fun iterationEndsAtTheRepeatAndReturnsToTheOriginalDrawing() {
        val distance = 400
        assertEquals(0, marqueeStepOffset(-1, distance, 10_000))
        assertEquals(distance, marqueeStepOffset(10_000, distance, 10_000))
        assertEquals(distance, marqueeStepOffset(10_034, distance, 10_000))
        val start = marqueeDrawOffsets(0, 300, 300, distance, rtl = false)
        val end = marqueeDrawOffsets(distance, 300, 300, distance, rtl = false)
        assertEquals(start.first, end.second)
    }

    @Test
    fun rtlStartsAtTheLogicalStartAndMovesRightWithTheRepeatOnTheLeft() {
        assertEquals(-50 to 350, marqueeDrawOffsets(50, 400, 100, 400, rtl = false))
        assertEquals(-250 to -650, marqueeDrawOffsets(50, 400, 100, 400, rtl = true))
        assertEquals(50 to -350, marqueeDrawOffsets(50, 400, 100, 400, rtl = false, direction = -1))
        assertEquals(-350 to 50, marqueeDrawOffsets(50, 400, 100, 400, rtl = true, direction = -1))
        val start = marqueeDrawOffsets(0, 400, 100, 433, rtl = true)
        val end = marqueeDrawOffsets(433, 400, 100, 433, rtl = true)
        assertEquals(start.first, end.second)
    }
}
