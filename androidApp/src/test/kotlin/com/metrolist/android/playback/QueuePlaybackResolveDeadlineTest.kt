package com.metrolist.android.playback

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class QueuePlaybackResolveDeadlineTest {
    @Test
    fun automaticResolveRetainsThirtySecondQueueDeadline() =
        runTest {
            val budget = queuePlaybackResolveBudget(0L, null)
            val result =
                withQueuePlaybackResolveDeadline(budget.deadlineAtMs, { testScheduler.currentTime }) {
                    delay(31_000L)
                    "resolved"
                }

            assertNull(result)
            assertEquals(30_000L, testScheduler.currentTime)
        }

    @Test
    fun explicitSamsungOverrideAllowsRequestTimeAndThirtySecondPreRoll() =
        runTest {
            val budget = queuePlaybackResolveBudget(testScheduler.currentTime, "TVHTML5_SAMSUNG")
            val result =
                withQueuePlaybackResolveDeadline(budget.deadlineAtMs, { testScheduler.currentTime }) {
                    delay(5_000L)
                    delay(30_000L)
                    "resolved"
                }

            assertEquals("resolved", result)
            assertEquals(35_000L, testScheduler.currentTime)
            assertEquals(55_000L, budget.deadlineAtMs)
        }

    @Test
    fun otherExplicitClientsRetainTheNormalDeadline() =
        runTest {
            val budget = queuePlaybackResolveBudget(testScheduler.currentTime, "WEB_REMIX")
            val result =
                withQueuePlaybackResolveDeadline(budget.deadlineAtMs, { testScheduler.currentTime }) {
                    delay(31_000L)
                    "resolved"
                }

            assertNull(result)
            assertEquals(30_000L, testScheduler.currentTime)
        }

    @Test
    fun initialResolveIsBoundedByTheWholeQueueDeadline() =
        runTest {
            var resolveStarted = false

            val result =
                withQueuePlaybackResolveDeadline(
                    deadlineAtMs = 30_000L,
                    nowMs = { 0L },
                ) {
                    resolveStarted = true
                    delay(55_000L)
                    "resolved"
                }

            assertTrue(resolveStarted)
            assertNull(result)
            assertEquals(30_000L, testScheduler.currentTime)
        }

    @Test
    fun retryUsesOnlyTheTimeRemainingFromTheOriginalDeadline() =
        runTest {
            var nowMs = 0L
            withQueuePlaybackResolveDeadline(30_000L, { nowMs }) {
                delay(20_000L)
                nowMs = 20_000L
            }

            val result =
                withQueuePlaybackResolveDeadline(30_000L, { nowMs }) {
                    delay(20_000L)
                    "resolved"
                }

            assertNull(result)
            assertEquals(30_000L, testScheduler.currentTime)
        }
}
