package com.metrolist.desktop.utils

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.awt.EventQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopStartupTest {
    @Test
    fun initializationWaitsForTheFirstVisibleFrameAndRunsOffTheUiThread() =
        runTest {
            val startup = DesktopStartup()
            val callerThread = Thread.currentThread()
            val job =
                async {
                    startup.initialize {
                        assertFalse(EventQueue.isDispatchThread())
                        assertFalse(Thread.currentThread() === callerThread)
                    }
                }
            runCurrent()
            assertFalse(job.isCompleted)
            startup.frameDrawn(backendReady = false)
            runCurrent()
            assertFalse(job.isCompleted)
            startup.windowShown.complete(Unit)
            job.await()
            assertTrue(startup.initialized)
            assertFalse(startup.appFrame.isCompleted)
            startup.frameDrawn(backendReady = true)
            assertTrue(startup.firstFrame.isCompleted)
            assertTrue(startup.appFrame.isCompleted)
        }

    @Test
    fun closingBeforeTheWindowShowsDoesNotInitializeServices() =
        runTest {
            val startup = DesktopStartup()
            var calls = 0
            val job = async { startup.initialize { calls++ } }
            runCurrent()
            startup.frameDrawn(backendReady = false)
            runCurrent()
            job.cancelAndJoin()
            startup.windowShown.complete(Unit)
            runCurrent()
            assertEquals(0, calls)
            assertFalse(startup.initialized)
        }

    @Test
    fun closingDuringInitializationStillAllowsCleanup() =
        runTest {
            val startup = DesktopStartup()
            val entered = CompletableDeferred<Unit>()
            val finish = CountDownLatch(1)
            startup.frameDrawn(backendReady = false)
            startup.windowShown.complete(Unit)
            val job =
                async {
                    startup.initialize {
                        entered.complete(Unit)
                        check(finish.await(5, TimeUnit.SECONDS))
                    }
                }
            entered.await()
            job.cancel()
            finish.countDown()
            job.join()
            assertTrue(startup.initialized)
        }

    @Test
    fun failuresDoNotPublishAnInitializedBackend() =
        runTest {
            val startup = DesktopStartup()
            startup.frameDrawn(backendReady = false)
            startup.windowShown.complete(Unit)
            assertFailsWith<IllegalStateException> {
                startup.initialize { error("database unavailable") }
            }
            assertFalse(startup.initialized)
            assertFalse(startup.appFrame.isCompleted)
        }
}
