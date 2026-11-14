package com.metrolist.shared.youtube.playback

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class PlaybackBandwidthMeterTest {
    @BeforeTest
    fun setUp() {
        PlaybackBandwidthMeter.reset()
    }

    @AfterTest
    fun tearDown() {
        PlaybackBandwidthMeter.reset()
    }

    @Test
    fun recordSampleUpdatesEstimateCorrectly() {
        PlaybackBandwidthMeter.reset()

        PlaybackBandwidthMeter.recordSample(1_000_000L, 500L)
        assertEquals(16_000_000L, PlaybackBandwidthMeter.estimatedBandwidthBps)

        PlaybackBandwidthMeter.recordSample(500_000L, 1000L)
        val estimated = PlaybackBandwidthMeter.estimatedBandwidthBps
        assertTrue(estimated in 12_900_000L..13_100_000L)
    }

    @Test
    fun recentBandwidthWeightsBytesByTransferTime() {
        PlaybackBandwidthMeter.recordSample(50_000L, 400L)
        assertEquals(null, PlaybackBandwidthMeter.recentBandwidthBps(window = Duration.INFINITE))

        PlaybackBandwidthMeter.recordSample(10_000_000L, 1_000L)
        PlaybackBandwidthMeter.recordSample(50_000L, 1_000L)
        assertEquals(10_100_000L * 8_000L / 2_400L, PlaybackBandwidthMeter.recentBandwidthBps(window = Duration.INFINITE))
        assertEquals(null, PlaybackBandwidthMeter.recentBandwidthBps(window = (-1).seconds))
    }

    @Test
    fun smallSamplesAreIgnoredToPreventNoise() {
        PlaybackBandwidthMeter.reset(10_000_000L)

        PlaybackBandwidthMeter.recordSample(1024L, 100L)
        assertEquals(10_000_000L, PlaybackBandwidthMeter.estimatedBandwidthBps)

        PlaybackBandwidthMeter.recordSample(50_000L, 5L)
        assertEquals(10_000_000L, PlaybackBandwidthMeter.estimatedBandwidthBps)
    }
}
