package com.metrolist.shared.listentogether.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServerClockTest {
    @Test
    fun sampleMapsServerTimeToMonotonicTime() {
        var elapsed = 1_100L
        val clock = ServerClock { elapsed }

        assertTrue(clock.recordSample(1_000L, 5_100L, 5_100L))
        assertEquals(5_150L, clock.serverTimeNow())

        elapsed += 250L
        assertEquals(5_400L, clock.serverTimeNow())
    }

    @Test
    fun playingPositionAgesUsingServerClockAndClampsToDuration() {
        var elapsed = 1_100L
        val clock = ServerClock { elapsed }
        clock.recordSample(1_000L, 5_100L, 5_100L)

        assertEquals(1_650L, clock.positionAt(1_000L, 4_500L, true, 2_000L))
        elapsed += 1_000L
        assertEquals(2_000L, clock.positionAt(1_000L, 4_500L, true, 2_000L))
        assertEquals(1_000L, clock.positionAt(1_000L, 4_500L, false, 2_000L))
    }

    @Test
    fun invalidSamplesAreRejected() {
        val clock = ServerClock { 1_000L }

        assertFalse(clock.recordSample(1_001L, 5_000L, 5_000L))
        assertFalse(clock.recordSample(900L, 5_100L, 5_000L))
        assertEquals(0, clock.sampleCount())
        assertNull(clock.serverTimeNow())
    }

    @Test
    fun delayedAndHighLatencySamplesDoNotMoveClock() {
        var elapsed = 20_000L
        val clock = ServerClock { elapsed }
        assertFalse(clock.recordSample(1_000L, 5_000L, 5_000L))

        elapsed = 1_100L
        assertTrue(clock.recordSample(1_000L, 5_100L, 5_100L))
        val synchronizedTime = clock.serverTimeNow()

        elapsed = 2_100L
        assertFalse(clock.recordSample(1_000L, 6_000L, 6_000L))
        assertEquals(synchronizedTime?.plus(1_000L), clock.serverTimeNow())
    }

    @Test
    fun resetClearsClockMapping() {
        val clock = ServerClock { 1_100L }
        clock.recordSample(1_000L, 5_100L, 5_100L)
        assertEquals(1, clock.sampleCount())

        clock.reset()

        assertEquals(0, clock.sampleCount())
        assertNull(clock.serverTimeNow())
    }
}
