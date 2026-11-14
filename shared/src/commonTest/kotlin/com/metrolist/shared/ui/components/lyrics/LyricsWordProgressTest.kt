package com.metrolist.shared.ui.components.lyrics

import com.metrolist.shared.ui.components.lyrics.words.syllableWipePosition
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource

class LyricsWordProgressTest {
    @Test
    fun drawnWipeStaysWithinOneFrameWithDelayedHundredMillisecondSamples() {
        for (speed in listOf(0.5f, 1f, 2f)) {
            val time = TestTimeSource()
            val clock = LyricsPositionClock(speed, timeSource = time)
            var elapsedMs = 0L
            val livePosition = { 45_000L + (elapsedMs * speed).toLong() }
            clock.sample(44_900L, livePosition)
            var nextDeliveryMs = 150L
            while (elapsedMs < 1_000L) {
                time += 16.milliseconds
                elapsedMs += 16L
                if (elapsedMs >= nextDeliveryMs) {
                    clock.sample(45_000L + ((elapsedMs - 50L) * speed).toLong(), livePosition)
                    nextDeliveryMs += 100L
                }
                val drawnX = syllableWipePosition(clock.positionMs(), 45_000L, 48_000L, 10f, 310f)
                val drawnPositionMs = 45_000.0 + (drawnX - 10f) * 10.0
                val lagMs = livePosition() - drawnPositionMs
                assertTrue(abs(lagMs) / speed < 16.0, "speed=$speed t=$elapsedMs lagMs=$lagMs")
            }
        }
    }

    @Test
    fun aPositionCorrectionReachesTheWipeInTheSameFrame() {
        val time = TestTimeSource()
        val clock = LyricsPositionClock(1f, timeSource = time)
        clock.sample(45_000L)
        time += 100.milliseconds
        clock.sample(45_190L)
        assertEquals(19f, syllableWipePosition(clock.positionMs(), 45_000L, 46_000L, 0f, 100f), 0.0001f)
        time += 16.milliseconds
        assertEquals(20.6f, syllableWipePosition(clock.positionMs(), 45_000L, 46_000L, 0f, 100f), 0.0001f)
    }

    @Test
    fun seeksResetTheClockAndOrdinarySamplesDoNotRewindTheWipe() {
        val time = TestTimeSource()
        val clock = LyricsPositionClock(1f, maxPosition = 60_000L, timeSource = time)
        clock.sample(45_000L)
        time += 100.milliseconds
        assertEquals(45_100L, clock.positionMs())
        clock.sample(45_095L)
        assertEquals(45_100L, clock.positionMs())
        clock.sample(20_000L)
        assertEquals(20_000L, clock.positionMs())
        clock.sample(59_990L)
        time += 100.milliseconds
        assertEquals(60_000L, clock.positionMs())
    }

    @Test
    fun wordProgressUsesItsOwnStartAndEndAcrossGaps() {
        assertEquals(10f, syllableWipePosition(45_500L, 45_600L, 45_900L, 10f, 40f))
        assertEquals(10f, syllableWipePosition(45_600L, 45_600L, 45_900L, 10f, 40f))
        assertEquals(25f, syllableWipePosition(45_750L, 45_600L, 45_900L, 10f, 40f))
        assertEquals(40f, syllableWipePosition(45_950L, 45_600L, 45_900L, 10f, 40f))
    }
}
