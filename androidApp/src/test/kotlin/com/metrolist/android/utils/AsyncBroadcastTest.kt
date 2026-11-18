package com.metrolist.android.utils

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AsyncBroadcastTest {
    @Test
    fun successAndEarlyReturnFinishOnce() =
        runTest {
            var finishes = 0
            val job = launchAsyncBroadcast({ finishes++ }) { return@launchAsyncBroadcast }
            job.join()
            job.cancelAndJoin()
            assertEquals(1, finishes)
        }

    @Test
    fun immediateCompletionFinishesBeforeLaunchReturns() =
        runTest {
            val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
            var finishes = 0
            val job = scope.launchAsyncBroadcast({ finishes++ }) {}
            assertTrue(job.isCompleted)
            job.cancelAndJoin()
            assertEquals(1, finishes)
        }

    @Test
    fun exceptionFinishesOnce() =
        runTest {
            var finishes = 0
            val job = launchAsyncBroadcast({ finishes++ }) { throw IllegalArgumentException("Failed work") }
            job.join()
            job.cancelAndJoin()
            assertEquals(1, finishes)
        }

    @Test
    fun cancellationDuringWorkFinishesOnce() =
        runTest {
            var finishes = 0
            val job = launchAsyncBroadcast({ finishes++ }) { awaitCancellation() }
            runCurrent()
            assertEquals(0, finishes)
            job.cancelAndJoin()
            assertEquals(1, finishes)
        }

    @Test
    fun cancellationBeforeDispatchStillFinishes() =
        runTest {
            var finishes = 0
            var started = false
            val job = launchAsyncBroadcast({ finishes++ }) { started = true }
            job.cancelAndJoin()
            assertFalse(started)
            assertEquals(1, finishes)
        }

    @Test
    fun cancelledParentStillFinishes() =
        runTest {
            val parent = Job().apply { cancel() }
            val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + parent)
            var finishes = 0
            val job = scope.launchAsyncBroadcast({ finishes++ }) { error("Must not run") }
            job.join()
            assertEquals(1, finishes)
        }

    @Test
    fun timeoutCancelsWorkAndFinishesOnce() =
        runTest {
            var finishes = 0
            var cancelled = false
            val job =
                launchAsyncBroadcast({ finishes++ }) {
                    try {
                        awaitCancellation()
                    } finally {
                        cancelled = true
                    }
                }
            runCurrent()
            advanceTimeBy(8_000)
            runCurrent()
            job.cancelAndJoin()
            assertTrue(cancelled)
            assertTrue(job.isCancelled)
            assertEquals(1, finishes)
        }

    @Test
    fun platformFinishedResultIsNotRetried() =
        runTest {
            var attempts = 0
            val job =
                launchAsyncBroadcast({
                    attempts++
                    throw IllegalStateException("Broadcast already finished")
                }) {}
            job.join()
            job.cancelAndJoin()
            assertEquals(1, attempts)
        }
}
