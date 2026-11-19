package com.metrolist.android.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LyricsLocalVideoLimitsTest {
    private val limits = LyricsLocalVideoLimits

    @Test fun capsSourceLoopWithoutOverflow() {
        assertEquals(6_000L, limits.loopDuration(600_000))
        assertEquals(1L, limits.loopDuration(1))
        for (duration in listOf(0L, -1L, Long.MAX_VALUE)) assertFailsWith<IllegalArgumentException> { limits.loopDuration(duration) }
    }

    @Test fun sampleBudgetIsAtMost48AndNeverOutsideClip() {
        for (duration in 1L..6_000L) {
            val count = limits.frameCount(duration)
            assertTrue(count in 1..48)
            for (index in 0 until count) assertTrue(limits.sampleTimeUs(index, duration) in 0 until duration * 1000)
        }
    }

    @Test fun sampledFramesHoldForThree24FpsOutputFrames() {
        assertEquals(listOf(0, 0, 0, 1, 1, 1), (0..5).map { limits.frameIndex(it * 1000L / 24, 6_000) })
        assertEquals(47, limits.frameIndex(5_999, 6_000))
        assertEquals(0, limits.frameIndex(6_000, 6_000))
    }

    @Test fun hugePlaybackPositionDoesNotOverflow() {
        assertTrue(limits.frameIndex(Long.MAX_VALUE, 6_000) in 0..47)
        assertFailsWith<IllegalArgumentException> { limits.frameIndex(-1, 6_000) }
    }

    @Test fun partialFinalFrameLoopsByActualDuration() {
        assertEquals(2, limits.frameCount(126))
        assertEquals(1, limits.frameIndex(125, 126))
        assertEquals(0, limits.frameIndex(126, 126))
    }

    @Test fun byteLimitsRejectExcessAndArithmeticOverflow() {
        assertEquals(10, limits.addWithinBudget(9, 1, 10))
        assertFailsWith<IllegalArgumentException> { limits.addWithinBudget(10, 1, 10) }
        assertFailsWith<IllegalArgumentException> { limits.addWithinBudget(Long.MAX_VALUE, 1, Long.MAX_VALUE) }
        assertFailsWith<IllegalArgumentException> { limits.addWithinBudget(0, Long.MAX_VALUE, 100) }
        assertFailsWith<IllegalArgumentException> { limits.addWithinBudget(-1, 1, 100) }
    }

    @Test fun sourcePixelBudgetAllowsPortraitWithoutMultiplicationOverflow() {
        assertTrue(limits.sourceDimensionsAllowed(1080, 1920))
        assertTrue(limits.sourceDimensionsAllowed(1920, 1080))
        assertFalse(limits.sourceDimensionsAllowed(3840, 2160))
        assertFalse(limits.sourceDimensionsAllowed(Int.MAX_VALUE, Int.MAX_VALUE))
        assertFalse(limits.sourceDimensionsAllowed(0, 10))
    }

    @Test fun worstCaseArgbFramesFitFrameBudget() {
        val bytes = 48L * 320 * 320 * 4
        assertTrue(bytes < limits.MAX_FRAME_BYTES)
        assertEquals(bytes, limits.addWithinBudget(0, bytes, limits.MAX_FRAME_BYTES))
    }
}
