package com.metrolist.desktop.state.playback

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopPlaybackRestorationTest {
    @Test
    fun quittingDuringUntouchedRestoreWaitsAndPreservesTheStoredSession() =
        runTest {
            val read = CompletableDeferred<Unit>()
            val restoration = DesktopPlaybackRestoration()
            var applied = false
            restoration.start(this) {
                withContext(NonCancellable) { read.await() }
                currentCoroutineContext().ensureActive()
                applied = true
            }
            runCurrent()
            val preserve = async { restoration.stop() }
            runCurrent()
            assertFalse(preserve.isCompleted)
            read.complete(Unit)
            assertTrue(preserve.await())
            assertFalse(applied)
        }

    @Test
    fun deliberateQueueClearAndCompletedRestorationStillFlush() =
        runTest {
            val edited = DesktopPlaybackRestoration()
            edited.start(this) { CompletableDeferred<Unit>().await() }
            runCurrent()
            edited.invalidate()
            assertFalse(edited.stop())
            val completed = DesktopPlaybackRestoration()
            completed.start(this) {}
            completed.await()
            assertFalse(completed.stop())
        }
}
