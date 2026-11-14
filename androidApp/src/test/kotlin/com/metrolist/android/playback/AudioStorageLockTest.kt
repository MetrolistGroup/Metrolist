package com.metrolist.android.playback

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AudioStorageLockTest {
    @Test
    fun transferDownloadAndClearCannotPublishOverEachOther() =
        runTest {
            val locks = AudioStorageLock()
            val releaseTransfer = CompletableDeferred<Unit>()
            val events = mutableListOf<String>()
            launch {
                locks.song("song") {
                    events += "cache starts"
                    releaseTransfer.await()
                    events += "cache published"
                }
            }
            runCurrent()
            launch { locks.song("song") { events += "download published" } }
            runCurrent()
            launch { locks.all { events += "cleared" } }
            runCurrent()
            assertEquals(listOf("cache starts"), events)
            releaseTransfer.complete(Unit)
            runCurrent()
            assertEquals(listOf("cache starts", "cache published", "download published", "cleared"), events)
            locks.song("song") { events += "available" }
            assertEquals("available", events.last())
        }
}
