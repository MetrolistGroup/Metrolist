package com.metrolist.android.playback

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class QueuePreCacheSchedulerTest {
    @Test
    fun cancellationKeepsReplacementSerializedAndCancellable() =
        runTest {
            val scheduler = QueuePreCacheScheduler(backgroundScope)
            var active = 0
            var replacementStarts = 0
            scheduler.schedule {
                active++
                try {
                    awaitCancellation()
                } finally {
                    active--
                }
            }
            runCurrent()
            assertEquals(1, active)

            scheduler.cancel()
            scheduler.schedule {
                replacementStarts++
                active++
                try {
                    awaitCancellation()
                } finally {
                    active--
                }
            }
            runCurrent()
            assertEquals(1, replacementStarts)
            assertEquals(1, active)

            scheduler.cancel()
            runCurrent()
            assertEquals(0, active)
        }

    @Test
    fun cancellationBeforeWorkerStartsAllowsANewRequest() =
        runTest {
            val scheduler = QueuePreCacheScheduler(backgroundScope)
            val started = mutableListOf<String>()
            scheduler.schedule { started += "cancelled" }
            scheduler.cancel()
            scheduler.schedule { started += "replacement" }
            runCurrent()
            assertEquals(listOf("replacement"), started)

            scheduler.schedule { started += "later" }
            runCurrent()
            assertEquals(listOf("replacement", "later"), started)
        }

    @Test
    fun pendingRequestsCoalesceWhileTheCurrentRequestFinishes() =
        runTest {
            val scheduler = QueuePreCacheScheduler(backgroundScope)
            val release = CompletableDeferred<Unit>()
            val events = mutableListOf<String>()
            scheduler.schedule {
                events += "first starts"
                release.await()
                events += "first ends"
            }
            runCurrent()
            scheduler.schedule { events += "superseded" }
            scheduler.schedule { events += "latest" }
            runCurrent()
            assertEquals(listOf("first starts"), events)
            release.complete(Unit)
            runCurrent()
            assertEquals(listOf("first starts", "first ends", "latest"), events)

            scheduler.schedule { events += "later" }
            runCurrent()
            assertEquals(listOf("first starts", "first ends", "latest", "later"), events)
        }

    @Test
    fun latestRequestWaitsForCancellationCleanup() =
        runTest {
            val scheduler = QueuePreCacheScheduler(backgroundScope)
            val releaseCleanup = CompletableDeferred<Unit>()
            val events = mutableListOf<String>()
            scheduler.schedule {
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) {
                        events += "cleanup starts"
                        releaseCleanup.await()
                        events += "cleanup ends"
                    }
                }
            }
            runCurrent()
            scheduler.cancel()
            runCurrent()
            scheduler.schedule { events += "superseded" }
            scheduler.schedule { events += "latest" }
            runCurrent()
            assertEquals(listOf("cleanup starts"), events)

            releaseCleanup.complete(Unit)
            runCurrent()
            assertEquals(listOf("cleanup starts", "cleanup ends", "latest"), events)
        }

    @Test
    fun cancellationDropsAnUnstartedPendingRequest() =
        runTest {
            val scheduler = QueuePreCacheScheduler(backgroundScope)
            val releaseCleanup = CompletableDeferred<Unit>()
            val events = mutableListOf<String>()
            scheduler.schedule {
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) { releaseCleanup.await() }
                }
            }
            runCurrent()
            scheduler.cancel()
            runCurrent()
            scheduler.schedule { events += "cancelled replacement" }
            scheduler.cancel()
            releaseCleanup.complete(Unit)
            runCurrent()
            assertEquals(emptyList<String>(), events)

            scheduler.schedule { events += "later" }
            runCurrent()
            assertEquals(listOf("later"), events)
        }

    @Test
    fun immediateCompletionCanScheduleMoreWork() =
        runTest {
            val scheduler =
                QueuePreCacheScheduler(CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)))
            val completed = mutableListOf<Int>()
            scheduler.schedule {
                completed += 1
                scheduler.schedule { completed += 2 }
            }
            scheduler.schedule { completed += 3 }
            assertEquals(listOf(1, 2, 3), completed)
        }

    @Test
    fun cancellingAfterReplacementStopsEveryWorker() =
        runTest {
            val scheduler = QueuePreCacheScheduler(backgroundScope)
            var active = 0
            var starts = 0
            val cache: suspend () -> Unit = {
                starts++
                active++
                try {
                    awaitCancellation()
                } finally {
                    active--
                }
            }
            scheduler.schedule(cache)
            runCurrent()
            scheduler.cancel()
            scheduler.schedule(cache)
            runCurrent()
            assertEquals(2, starts)
            assertEquals(1, active)
            scheduler.cancel()
            runCurrent()
            assertEquals(0, active)
        }

    @Test
    fun cancelledScopeDoesNotRestartPendingWork() =
        runTest {
            val scope = CoroutineScope(backgroundScope.coroutineContext)
            val scheduler = QueuePreCacheScheduler(scope)
            val started = mutableListOf<String>()
            scheduler.schedule {
                started += "running"
                awaitCancellation()
            }
            runCurrent()
            scheduler.schedule { started += "pending" }
            scope.cancel()
            runCurrent()
            scheduler.schedule { started += "after cancellation" }
            runCurrent()
            assertEquals(listOf("running"), started)
        }
}
