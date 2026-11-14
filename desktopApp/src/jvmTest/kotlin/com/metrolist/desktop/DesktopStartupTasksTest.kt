package com.metrolist.desktop

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class DesktopStartupTasksTest {
    @Test
    fun visitorSaveReturnsToCallerAfterSignInWhileFetchWasSuspended() =
        runTest {
            val response = CompletableDeferred<String>()
            val started = CompletableDeferred<Unit>()
            val callerThread = Thread.currentThread()
            var signedIn = false
            var guestSaved = false
            val job =
                launchVisitorDataFetch(
                    shouldFetch = true,
                    dispatcher = StandardTestDispatcher(testScheduler),
                    fetch = {
                        started.complete(Unit)
                        response.await()
                    },
                    save = {
                        assertEquals(callerThread, Thread.currentThread())
                        if (!signedIn) guestSaved = true
                    },
                )!!
            started.await()
            signedIn = true
            response.complete("visitor")
            job.join()
            assertFalse(guestSaved)
        }

    @Test
    fun terminationWatchdogIsIndependentOfBlockingCleanup() {
        val cleanupStarted = CountDownLatch(1)
        val releaseCleanup = CountDownLatch(1)
        val cleanupFinished = CountDownLatch(1)
        val forceTerminated = CountDownLatch(1)

        try {
            runBoundedShutdown(
                timeoutMillis = 0L,
                beginShutdown = {
                    cleanupStarted.countDown()
                    try {
                        releaseCleanup.await()
                    } finally {
                        cleanupFinished.countDown()
                    }
                },
                terminate = { error("cleanup did not block") },
                forceTerminate = forceTerminated::countDown,
            )

            assertTrue(cleanupStarted.await(10L, TimeUnit.SECONDS))
            assertTrue(forceTerminated.await(10L, TimeUnit.SECONDS))
        } finally {
            releaseCleanup.countDown()
            assertTrue(cleanupFinished.await(10L, TimeUnit.SECONDS))
        }
    }

    @Test
    fun visitorFetchDoesNotBlockStartup() =
        runTest {
            val response = CompletableDeferred<String>()
            var saved: String? = null

            val job =
                launchVisitorDataFetch(
                    shouldFetch = true,
                    dispatcher = StandardTestDispatcher(testScheduler),
                    fetch = { response.await() },
                    save = { saved = it },
                )

            assertFalse(job!!.isCompleted)
            response.complete("visitor")
            job.join()
            assertEquals("visitor", saved)
        }

    @Test
    fun visitorFetchIsSkippedForAnExistingSession() =
        runTest {
            val job =
                launchVisitorDataFetch(
                    shouldFetch = false,
                    dispatcher = StandardTestDispatcher(testScheduler),
                    fetch = { error("should not run") },
                    save = {},
                )

            assertNull(job)
        }
}
