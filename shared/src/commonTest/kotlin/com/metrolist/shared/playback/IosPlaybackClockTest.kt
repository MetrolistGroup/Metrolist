package com.metrolist.shared.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IosPlaybackClockTest {
    @Test
    fun silencePreparationCrossfadeAndEndHaveIndependentBoundaries() {
        val silence = listOf(SilenceInterval(5_000L, 7_000L), SilenceInterval(20_000L, 22_000L))

        fun next(
            position: Long,
            skippedUntil: Long = 0L,
        ) = nextIosPlaybackBoundaryMs(position, 180_000L, 5_000L, silence, skippedUntil)

        assertEquals(3_001L, next(0L))
        assertEquals(5_000L, next(3_001L))
        assertEquals(15_000L, next(7_000L, 7_000L))
        assertEquals(20_000L, next(15_000L, 7_000L))
        assertEquals(175_000L, next(22_000L, 22_000L))
        assertEquals(180_000L, next(175_000L, 22_000L))
        assertNull(next(180_000L, 22_000L))
        assertEquals(3_001L, next(2_000L))
    }

    @Test
    fun shortTracksAndLatePreparationDoNotWaitForTheCoarseTick() {
        assertEquals(1L, nextIosPlaybackBoundaryMs(0L, 10_000L, 10_000L, emptyList(), 0L))
        assertEquals(3_001L, nextIosPlaybackBoundaryMs(1L, 10_000L, 10_000L, emptyList(), 0L))
        assertEquals(180_000L, nextIosPlaybackBoundaryMs(178_000L, 180_000L, 5_000L, emptyList(), 0L))
    }

    @Test
    fun correctedEndAndGaplessPolicyChooseTheSameTimelineAsPlayback() {
        val end = correctedNativeDurationMs(180_000L, 360_000L)
        val fade = playbackCrossfadeDurationMs(true, true, true, 5_000L, end, 120_000L)
        assertEquals(180_000L, nextIosPlaybackBoundaryMs(170_000L, end, fade, emptyList(), 0L))
        assertEquals(175_000L, nextIosPlaybackBoundaryMs(170_000L, end, 5_000L, emptyList(), 0L))
    }

    @Test
    fun preparationKeepsTheReportedDurationEvenWhenNativeEndDiffers() {
        assertEquals(
            5_000L,
            nextIosPlaybackBoundaryMs(4_000L, 40_000L, 0L, emptyList(), 0L, preparationDurationMs = 35_000L),
        )
    }

    @Test
    fun unknownDurationStillSchedulesKnownSilenceWithoutInventingAnEnd() {
        assertNull(nextIosPlaybackBoundaryMs(4_000L, 0L, 5_000L, emptyList(), 0L))
        assertEquals(2_000L, nextIosPlaybackBoundaryMs(0L, 0L, 0L, listOf(SilenceInterval(2_000L, 3_000L)), 0L))
        assertEquals(3_001L, nextIosPlaybackBoundaryMs(0L, 0L, 0L, listOf(SilenceInterval(2_000L, 3_000L)), 3_000L))
    }
}
