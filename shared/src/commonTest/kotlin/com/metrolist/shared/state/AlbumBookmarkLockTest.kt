package com.metrolist.shared.state

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AlbumBookmarkLockTest {
    @Test
    fun independentAlbumsProceedWhileSameAlbumWaitsAndCancelledWaitersReleaseTheirReference() =
        runTest {
            val release = CompletableDeferred<Unit>()
            var sameAlbumDone = false
            var otherAlbumDone = false
            launch { withAlbumBookmarkLock("a") { release.await() } }
            runCurrent()
            val cancelled = launch { withAlbumBookmarkLock("a") { error("Cancelled waiter must not run") } }
            launch { withAlbumBookmarkLock("a") { sameAlbumDone = true } }
            launch { withAlbumBookmarkLock("b") { otherAlbumDone = true } }
            runCurrent()
            assertTrue(otherAlbumDone)
            assertFalse(sameAlbumDone)
            cancelled.cancel()
            release.complete(Unit)
            runCurrent()
            assertTrue(sameAlbumDone)
            withAlbumBookmarkLock("a") { withAlbumBookmarkLock("b") { } }
        }
}
