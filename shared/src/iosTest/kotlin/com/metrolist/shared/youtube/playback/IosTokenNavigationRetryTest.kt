package com.metrolist.shared.youtube.playback

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class IosTokenNavigationRetryTest {
    @Test
    fun retriesContinueAtCappedDelayAfterReportingExhaustionAndResetOnSuccess() =
        runTest {
            val retry = IosTokenNavigationRetry(backgroundScope, maxAttempts = 2, maxDelayMs = 4_000L)
            var reloads = 0
            var exhausted = 0
            for (delay in listOf(1_000L, 2_000L, 4_000L, 4_000L)) {
                val previous = reloads
                retry.failed(onExhausted = { exhausted++ }, reload = { reloads++ })
                retry.failed(onExhausted = { error("Duplicate exhaustion") }, reload = { error("Duplicate navigation retry") })
                runCurrent()
                advanceTimeBy(delay - 1)
                assertEquals(previous, reloads)
                advanceTimeBy(1)
                runCurrent()
                assertEquals(previous + 1, reloads)
            }
            assertEquals(1, exhausted)

            retry.ready()
            retry.failed(onExhausted = { exhausted++ }, reload = { reloads++ })
            runCurrent()
            advanceTimeBy(1_000L)
            runCurrent()
            assertEquals(5, reloads)
            assertEquals(1, exhausted)

            retry.failed(onExhausted = { exhausted++ }, reload = { reloads++ })
            runCurrent()
            advanceTimeBy(2_000L)
            runCurrent()
            assertEquals(6, reloads)
            assertEquals(2, exhausted)

            retry.failed(onExhausted = { exhausted++ }, reload = { reloads++ })
            retry.close()
            advanceTimeBy(30_000L)
            runCurrent()
            assertEquals(6, reloads)
            retry.failed(onExhausted = { exhausted++ }, reload = { error("Disposed navigation retry") })
            assertEquals(2, exhausted)
        }
}
