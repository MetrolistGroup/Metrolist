package com.metrolist.shared.deviceconnect

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidNsdShutdownBarrierTest {
    @Test
    fun failedStopRequestsRemainRetryableUntilTheListenerIsRetired() =
        runTest {
            for (startBeforeClose in listOf(true, false)) {
                var requests = 0
                var reported = 0
                var completions = 0
                val barrier =
                    AndroidNsdShutdownBarrier(
                        {
                            requests++
                            if (requests == 1) throw IllegalStateException()
                        },
                        { reported++ },
                        { completions++ },
                    )
                if (startBeforeClose) barrier.started()
                val firstClose = async { barrier.close(1_000) }
                runCurrent()
                if (!startBeforeClose) barrier.started()
                advanceTimeBy(1_001)
                runCurrent()
                assertFalse(firstClose.await())
                assertEquals(1, reported)
                assertEquals(0, completions)

                val retry = async { barrier.close(1_000) }
                runCurrent()
                assertEquals(2, requests)
                assertFalse(retry.isCompleted)
                barrier.finished()
                barrier.finished()
                assertTrue(retry.await())
                assertEquals(1, completions)
            }
        }

    @Test
    fun shutdownWaitsForPendingStartAndStillStopsAfterTimeout() =
        runTest {
            var stopRequests = 0
            val barrier = AndroidNsdShutdownBarrier({ stopRequests++ }, { error(it) })
            val close = async { barrier.close(1_000) }

            runCurrent()
            assertFalse(close.isCompleted)
            barrier.started()
            barrier.started()
            assertEquals(1, stopRequests)
            barrier.finished()
            assertTrue(close.await())

            var lateCompletions = 0
            val delayedBarrier =
                AndroidNsdShutdownBarrier(
                    { stopRequests++ },
                    { error(it) },
                    { lateCompletions++ },
                )
            val timedOutClose = async { delayedBarrier.close(1_000) }
            advanceTimeBy(1_001)
            runCurrent()
            assertFalse(timedOutClose.await())
            assertEquals(0, lateCompletions)

            delayedBarrier.started()
            assertEquals(2, stopRequests)
            delayedBarrier.finished()
            assertEquals(1, lateCompletions)
        }
}
