package com.metrolist.music.listentogether

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerClockTest {
    @Test
    fun `maps server wall time onto local monotonic time`() {
        var elapsedRealtime = 1_000L
        val clock = ServerClock { elapsedRealtime }

        assertNull(clock.now())
        elapsedRealtime = 1_120L
        assertTrue(
            clock.recordPong(
                clientTime = 1_000L,
                serverReceiveTime = 10_000L,
                serverSendTime = 10_010L,
            ),
        )

        assertEquals(10_065L, clock.now())
        assertEquals(565L, clock.positionAt(500L, 10_000L, isPlaying = true))

        elapsedRealtime += 100L
        assertEquals(10_165L, clock.now())
        assertEquals(665L, clock.positionAt(500L, 10_000L, isPlaying = true))
        assertEquals(500L, clock.positionAt(500L, 10_000L, isPlaying = false))
    }

    @Test
    fun `rejects invalid and stale samples`() {
        var elapsedRealtime = 100_000L
        val clock = ServerClock { elapsedRealtime }

        assertFalse(clock.recordPong(0L, 1_000L, 1_001L))
        assertFalse(clock.recordPong(100_001L, 1_000L, 1_001L))
        assertFalse(clock.recordPong(100_000L, 1_001L, 1_000L))
        elapsedRealtime = 200_001L
        assertFalse(clock.recordPong(100_000L, 1_000L, 1_001L))
        assertNull(clock.now())
    }

    @Test
    fun `reset removes the server clock mapping`() {
        var elapsedRealtime = 1_100L
        val clock = ServerClock { elapsedRealtime }
        clock.recordPong(1_000L, 10_000L, 10_000L)

        clock.reset()
        elapsedRealtime += 100L

        assertNull(clock.now())
        assertEquals(500L, clock.positionAt(500L, 10_000L, isPlaying = true))
    }

    @Test
    fun `rejects jitter spikes using sliding window min-rtt`() {
        var elapsedRealtime = 1_000L
        val clock = ServerClock { elapsedRealtime }

        // Baseline sample (RTT = 20ms)
        elapsedRealtime = 1_020L
        assertTrue(
            clock.recordPong(
                clientTime = 1_000L,
                serverReceiveTime = 10_000L,
                serverSendTime = 10_000L,
            ),
        )
        val baseNow = clock.now()!!

        // Isolated jitter spike (RTT = 150ms > 20ms + 50ms)
        elapsedRealtime = 1_200L
        assertFalse(
            clock.recordPong(
                clientTime = 1_050L,
                serverReceiveTime = 10_060L,
                serverSendTime = 10_060L,
            ),
        )
        assertEquals(baseNow + 180L, clock.now())
    }

    @Test
    fun `unequal one-way delays within accepted margin do not shift clock offset`() {
        var elapsedRealtime = 1_000L
        val clock = ServerClock { elapsedRealtime }

        // Symmetric 20ms RTT sample establishes baseline offset = 9000
        elapsedRealtime = 1_020L
        assertTrue(
            clock.recordPong(
                clientTime = 1_000L,
                serverReceiveTime = 10_010L,
                serverSendTime = 10_010L,
            ),
        )

        // Multiple 34ms RTT samples with 14ms asymmetric downstream delay
        for (i in 1..5) {
            val send = 1_000L + i * 1_000L
            elapsedRealtime = send + 34L
            assertFalse(
                clock.recordPong(
                    clientTime = send,
                    serverReceiveTime = 10_010L + i * 1_000L,
                    serverSendTime = 10_010L + i * 1_000L,
                ),
            )
            // Clock offset must remain anchored to the 20ms sample (offset = 9000)
            assertEquals(elapsedRealtime + 9000L, clock.now())
        }
    }

    @Test
    fun `adapts to sustained RTT increase after consecutive elevated samples`() {
        var elapsedRealtime = 1_000L
        val clock = ServerClock { elapsedRealtime }

        // Initial 20ms RTT baseline (offset = 9000ms)
        elapsedRealtime = 1_020L
        assertTrue(
            clock.recordPong(
                clientTime = 1_000L,
                serverReceiveTime = 10_010L,
                serverSendTime = 10_010L,
            ),
        )

        // First elevated sample (RTT = 80ms > 20ms + 50ms, new offset = 9500ms): rejected as potential spike
        elapsedRealtime = 2_080L
        assertFalse(
            clock.recordPong(
                clientTime = 2_000L,
                serverReceiveTime = 11_540L,
                serverSendTime = 11_540L,
            ),
        )
        // Verifies clock offset remains at initial baseline 9000ms
        assertEquals(elapsedRealtime + 9000L, clock.now())

        // Second elevated sample (RTT = 76ms, offset = 9500ms): rejected
        elapsedRealtime = 3_076L
        assertFalse(
            clock.recordPong(
                clientTime = 3_000L,
                serverReceiveTime = 12_538L,
                serverSendTime = 12_538L,
            ),
        )
        assertEquals(elapsedRealtime + 9000L, clock.now())

        // Third consecutive elevated sample (RTT = 90ms, offset = 9500ms): accepted as sustained shift
        elapsedRealtime = 4_090L
        assertFalse(
            clock.recordPong(
                clientTime = 4_000L,
                serverReceiveTime = 13_545L,
                serverSendTime = 13_545L,
            ),
        )
        // Offset has adapted to new baseline 9500ms (seeded with lowest-RTT elevated sample: 76ms)
        assertEquals(elapsedRealtime + 9500L, clock.now())
    }
}
