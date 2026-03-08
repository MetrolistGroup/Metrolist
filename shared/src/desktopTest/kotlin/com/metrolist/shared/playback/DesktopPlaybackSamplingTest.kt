package com.metrolist.shared.playback

import com.metrolist.shared.playback.sponsorblock.SponsorBlockSegment
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopPlaybackSamplingTest {
    @Test
    fun hiddenPlaybackSleepsUntilTheCrossfadeBoundaryAtTheCurrentSpeed() {
        fun delay(
            position: Long,
            speed: Float = 1f,
            transition: Long? = 5_000L,
        ) = desktopPlaybackSampleDelayMs(false, false, position, 120_000L, speed, transition)

        assertEquals(1_000L, delay(60_000L))
        assertEquals(450L, delay(114_500L))
        assertEquals(50L, delay(114_950L))
        assertEquals(50L, delay(116_000L))
        assertEquals(450L, delay(109_000L, speed = 2f))
        assertEquals(1_000L, delay(116_000L, transition = null))
        assertEquals(1_000L, delay(60_000L), "Seeking back must leave fine sampling")
    }

    @Test
    fun preloadingWakesAtItsLeadDeadlineThenReturnsToCoarseSampling() {
        fun delay(position: Long) =
            desktopPlaybackSampleDelayMs(
                false,
                false,
                position,
                120_000L,
                1f,
                5_000L,
                preparationRemainingMs = 35_000L,
            )
        assertEquals(500L, delay(84_500L))
        assertEquals(50L, delay(84_980L))
        assertEquals(1_000L, delay(85_000L))
        assertEquals(1_000L, delay(86_000L))
        assertEquals(50L, delay(115_000L))
    }

    @Test
    fun shortHiddenTracksWakeAfterPreloadEligibilityAtTheCurrentSpeed() {
        fun delay(
            position: Long,
            speed: Float = 1f,
        ) = desktopPlaybackSampleDelayMs(
            false,
            false,
            position,
            1_000L,
            speed,
            0L,
            preparationRemainingMs = 30_000L,
        )

        assertEquals(401L, delay(100L))
        assertEquals(50L, delay(500L))
        assertEquals(449L, delay(501L))
        assertEquals(201L, delay(100L, speed = 2f))
        assertEquals(50L, delay(500L, speed = 2f))
        assertEquals(802L, delay(100L, speed = 0.5f))
    }

    @Test
    fun sponsorBlockBoundariesRemainPreciseWithoutAVisibleWindow() {
        val segment = SponsorBlockSegment(60_000L, 70_000L)
        assertEquals(450L, desktopPlaybackSampleDelayMs(false, false, 59_000, 120_000, 2f, null, segment))
        assertEquals(50L, desktopPlaybackSampleDelayMs(false, false, 60_000, 120_000, 1f, null, segment))
        assertEquals(50L, desktopPlaybackSampleDelayMs(false, false, 65_000, 120_000, 1f, null, segment))
        assertEquals(1_000L, desktopPlaybackSampleDelayMs(false, false, 70_000, 120_000, 1f, null))
    }
}
