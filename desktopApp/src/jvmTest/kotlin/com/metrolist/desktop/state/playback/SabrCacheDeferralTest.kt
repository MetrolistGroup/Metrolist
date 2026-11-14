package com.metrolist.desktop.state.playback

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

@OptIn(ExperimentalCoroutinesApi::class)
class SabrCacheDeferralTest {
    @Test
    fun deferralSleepsUntilReleaseAndHandlesReleaseBeforeSubscription() =
        runTest {
            val changes = MutableStateFlow(0)
            var deferred = true
            var checks = 0

            suspend fun awaitReady() =
                awaitSabrCacheReady(changes.map { Unit }, { true }) {
                    checks++
                    deferred
                }
            val waiter = backgroundScope.async { awaitReady() }
            runCurrent()
            assertEquals(1, checks)
            advanceTimeBy(600_000L)
            runCurrent()
            assertEquals(1, checks)
            assertFalse(waiter.isCompleted)
            deferred = false
            changes.value++
            runCurrent()
            waiter.await()
            awaitReady()
            assertEquals(3, checks)
        }

    @Test
    fun disablingCachingCancelsTheEventWait() =
        runTest {
            val enabled = MutableStateFlow(true)
            val waiter = backgroundScope.async { awaitSabrCacheReady(enabled.map { Unit }, { enabled.value }, { true }) }
            runCurrent()
            enabled.value = false
            runCurrent()
            assertFailsWith<CancellationException> { waiter.await() }
        }
}
