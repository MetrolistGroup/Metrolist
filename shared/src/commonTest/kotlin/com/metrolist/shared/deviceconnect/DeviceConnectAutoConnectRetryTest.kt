package com.metrolist.shared.deviceconnect

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeviceConnectAutoConnectRetryTest {
    @Test
    fun failuresBackOffPerDeviceAndOnlyTheFirstWarns() {
        val retry = DeviceConnectAutoConnectRetry()
        var now = 0L
        for ((index, interval) in listOf(30_000L, 60_000L, 120_000L, 240_000L, 480_000L, 600_000L, 600_000L).withIndex()) {
            assertTrue(retry.begin("offline", now))
            assertFalse(retry.begin("offline", now))
            assertTrue(retry.isDue("other", now))
            val first = retry.failed("offline", now)
            if (index == 0) assertTrue(first) else assertFalse(first)
            now += interval
            assertFalse(retry.isDue("offline", now - 1))
            assertTrue(retry.isDue("offline", now))
        }
    }

    @Test
    fun successAndNetworkChangesResetFailuresAndPendingAttempts() {
        val retry = DeviceConnectAutoConnectRetry()
        retry.begin("left", 0)
        retry.failed("left", 0)
        retry.begin("right", 0)
        retry.reset("left")
        assertTrue(retry.begin("left", 1))
        assertTrue(retry.failed("left", 1))
        assertFalse(retry.isDue("right", 1))

        retry.clear()
        assertTrue(retry.begin("left", 2))
        assertTrue(retry.begin("right", 2))
        assertTrue(retry.failed("left", 2))
        assertFalse(retry.isDue("left", 30_001))
        assertTrue(retry.isDue("left", 30_002))
    }

    @Test
    fun cancellationReleasesTheAttemptWithoutIncreasingTheDelay() {
        val retry = DeviceConnectAutoConnectRetry()
        assertTrue(retry.begin("peer", 0))
        retry.cancel("peer")
        assertTrue(retry.begin("peer", 1))
        assertTrue(retry.failed("peer", 1))
        assertTrue(retry.begin("peer", 30_001))
        retry.cancel("peer")
        assertTrue(retry.begin("peer", 30_001))
        assertFalse(retry.failed("peer", 30_001))
        assertFalse(retry.isDue("peer", 90_000))
        assertTrue(retry.isDue("peer", 90_001))
    }
}
