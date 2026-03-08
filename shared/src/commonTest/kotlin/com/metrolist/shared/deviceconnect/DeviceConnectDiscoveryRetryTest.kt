package com.metrolist.shared.deviceconnect

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeviceConnectDiscoveryRetryTest {
    @Test
    fun startupFailureRetriesWithoutPeersAndStopsAfterRecovery() {
        val retry = DeviceConnectDiscoveryRetry()
        var now = 0L
        assertFalse(retry.shouldRestart(now, emptySet(), discoveryFailed = true))
        for (interval in listOf(30_000L, 60_000L, 120_000L, 240_000L, 480_000L, 600_000L, 600_000L)) {
            now += interval
            assertFalse(retry.shouldRestart(now - 1, emptySet(), discoveryFailed = true))
            assertTrue(retry.shouldRestart(now, emptySet(), discoveryFailed = true))
        }
        assertFalse(retry.shouldRestart(now, emptySet()))
        assertFalse(retry.shouldRestart(now + 600_000L, emptySet()))
    }

    @Test
    fun missingPeersBackOffAndTransitionsResetTheDelay() {
        val retry = DeviceConnectDiscoveryRetry()
        val missing = setOf("peer")
        var now = 0L
        assertFalse(retry.shouldRestart(now, missing))
        for (interval in listOf(30_000L, 60_000L, 120_000L, 240_000L, 480_000L, 600_000L, 600_000L)) {
            now += interval
            assertFalse(retry.shouldRestart(now - 1, missing))
            assertTrue(retry.shouldRestart(now, missing))
        }
        retry.reset(now)
        assertTrue(retry.shouldRestart(now + 30_000L, missing))
        assertFalse(retry.shouldRestart(now + 30_001L, emptySet()))
        assertFalse(retry.shouldRestart(now + 30_002L, missing))
        assertTrue(retry.shouldRestart(now + 60_002L, missing))
    }
}
