package com.metrolist.desktop.state.playback

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopLyricsOffsetWritesTest {
    @Test
    fun debouncesPerVideoAndRetainsIndependentPendingOffsets() =
        runTest {
            val saved = mutableListOf<Pair<String, Int>>()
            val writes = DesktopLyricsOffsetWrites(backgroundScope) { video, offset -> saved += video to offset }
            writes.save("audio", 100)
            runCurrent()
            advanceTimeBy(50)
            writes.save("video", -150)
            writes.save("audio", 200)
            runCurrent()
            assertEquals(200, writes.resolve("audio") { 0 })
            assertEquals(-150, writes.resolve("video") { 0 })

            advanceTimeBy(160)
            runCurrent()
            assertEquals(2, saved.size)
            assertEquals(mapOf("video" to -150, "audio" to 200), saved.toMap())
            assertEquals(0, writes.resolve("audio") { 0 })
            assertEquals(0, writes.resolve("video") { 0 })
        }

    @Test
    fun lateOlderWriteCannotOvertakeTheNewestOffset() =
        runTest {
            val saved = mutableListOf<Int>()
            val release = CompletableDeferred<Unit>()
            val writes =
                DesktopLyricsOffsetWrites(backgroundScope) { _, offset ->
                    withContext(NonCancellable) {
                        if (offset == 100) release.await()
                        saved += offset
                    }
                }
            try {
                writes.save("song", 100)
                runCurrent()
                advanceTimeBy(160)
                writes.save("song", 200)
                writes.save("song", 300)
                runCurrent()
                advanceTimeBy(160)
                assertEquals(emptyList(), saved)
                assertEquals(300, writes.resolve("song") { 0 })
            } finally {
                release.complete(Unit)
            }
            runCurrent()
            assertEquals(listOf(100, 300), saved)
            assertEquals(0, writes.resolve("song") { 0 })
        }

    @Test
    fun oldReadCannotOutliveANewlyCommittedOffset() =
        runTest {
            var stored = 35
            var reads = 0
            val release = CompletableDeferred<Unit>()
            val writes = DesktopLyricsOffsetWrites(backgroundScope) { _, value -> stored = value }
            val observed =
                async {
                    writes.resolve("song") {
                        val snapshot = stored
                        if (++reads == 1) release.await()
                        snapshot
                    }
                }
            runCurrent()
            writes.save("song", 400)
            runCurrent()
            advanceTimeBy(160)
            runCurrent()
            assertEquals(400, stored)
            release.complete(Unit)
            assertEquals(400, observed.await())
            assertEquals(2, reads)
        }
}
