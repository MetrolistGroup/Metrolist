package com.metrolist.shared.viewmodels

import com.metrolist.shared.model.SongItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CanvasPreloaderTest {
    private fun song(id: String) = SongItem(id, id, null, emptyList(), null)

    @Test
    fun neighborsLoadConcurrentlyAndPublishIndependently() =
        runTest {
            val requests = mutableListOf<String>()
            val preloader =
                CanvasPreloader(backgroundScope) { song ->
                    requests += song.id
                    delay(if (song.id == "next") 200 else 100)
                    song.id
                }
            preloader.update("current", song("next"), song("previous"))
            runCurrent()
            assertEquals(listOf("next", "previous"), requests)
            advanceTimeBy(100)
            runCurrent()
            assertEquals(mapOf("previous" to "previous"), preloader.ready.value)
            advanceTimeBy(100)
            runCurrent()
            assertEquals(setOf("next", "previous"), preloader.ready.value.keys)
        }

    @Test
    fun hiddenQueueCancelsDownloadsAndRetainsCompletedNeighborsForReopening() =
        runTest {
            val pending = CompletableDeferred<Unit>()
            val requests = mutableListOf<String>()
            val preloader =
                CanvasPreloader(backgroundScope) { song ->
                    requests += song.id
                    if (song.id == "previous") pending.await()
                    song.id
                }
            preloader.update("current", song("next"), song("previous"), enabled = false)
            runCurrent()
            assertTrue(requests.isEmpty())
            preloader.update("current", song("next"), song("previous"))
            runCurrent()
            assertEquals(listOf("next", "previous"), requests)
            preloader.pause()
            pending.complete(Unit)
            runCurrent()
            assertEquals(mapOf("next" to "next"), preloader.ready.value)
            preloader.update("current", song("next"), song("previous"))
            runCurrent()
            assertEquals(listOf("next", "previous", "previous"), requests)
            assertEquals(setOf("next", "previous"), preloader.ready.value.keys)
        }

    @Test
    fun repeatingTheOnlySongNeverPreloadsItAgain() =
        runTest {
            val preloader = CanvasPreloader<String>(backgroundScope) { error("Current canvas must be reused") }
            repeat(10) {
                preloader.update("current", song("current"), song("current"))
                preloader.rememberCurrent("current", "cached")
                runCurrent()
            }
            assertEquals(mapOf("current" to "cached"), preloader.ready.value)
        }

    @Test
    fun advancesWithoutRefetchingAndRetainsOnlyCurrentAndNext() =
        runTest {
            val requests = mutableListOf<String>()
            val preloader =
                CanvasPreloader(this) { song ->
                    requests.add(song.id)
                    song.id
                }
            preloader.update("a", song("b"))
            runCurrent()
            preloader.update("a", song("b"))
            runCurrent()
            assertEquals(listOf("b"), requests)
            preloader.update("b", song("c"))
            assertEquals("b", preloader.ready.value["b"])
            runCurrent()
            assertEquals(setOf("b", "c"), preloader.ready.value.keys)
            preloader.update("c", null)
            assertEquals(setOf("c"), preloader.ready.value.keys)
        }

    @Test
    fun forgettingTheFailedCurrentCanvasKeepsPreparedNeighbors() =
        runTest {
            val requests = mutableListOf<String>()
            val preloader =
                CanvasPreloader(this) { song ->
                    requests.add(song.id)
                    song.id
                }
            preloader.update("a", song("b"))
            preloader.rememberCurrent("a", "a")
            runCurrent()
            preloader.forget("a")
            assertEquals(setOf("b"), preloader.ready.value.keys)
            preloader.update("b", song("c"))
            runCurrent()
            assertEquals(listOf("b", "c"), requests)
        }

    @Test
    fun backAndForwardReusePreparedNeighborsIncludingTheFirstSong() =
        runTest {
            val requests = mutableListOf<String>()
            val preloader =
                CanvasPreloader(this) { song ->
                    requests.add(song.id)
                    song.id
                }
            preloader.update("a", song("b"))
            preloader.rememberCurrent("a", "a")
            runCurrent()
            preloader.update("b", song("c"), song("a"))
            runCurrent()
            assertEquals(setOf("a", "b", "c"), preloader.ready.value.keys)
            preloader.update("a", song("b"))
            runCurrent()
            assertEquals(listOf("b", "c"), requests)
            assertEquals(setOf("a", "b"), preloader.ready.value.keys)
            preloader.rememberCurrent("obsolete", "obsolete")
            assertFalse("obsolete" in preloader.ready.value)
        }

    @Test
    fun startsNextBeforePreviousAndKeepsOnlyThreeSongs() =
        runTest {
            val requests = mutableListOf<String>()
            val preloader =
                CanvasPreloader(this) { song ->
                    requests.add(song.id)
                    song.id
                }
            preloader.update("b", song("c"), song("a"))
            preloader.rememberCurrent("b", "b")
            runCurrent()
            assertEquals(listOf("c", "a"), requests)
            preloader.update("c", song("d"), song("b"))
            runCurrent()
            assertEquals(setOf("b", "c", "d"), preloader.ready.value.keys)
        }

    @Test
    fun reorderCancelsObsoleteWorkWithoutPublishingIt() =
        runTest {
            val old = CompletableDeferred<Unit>()
            val preloader =
                CanvasPreloader(this) { song ->
                    if (song.id == "b") withContext(NonCancellable) { old.await() }
                    song.id
                }
            preloader.update("a", song("b"))
            runCurrent()
            preloader.update("a", song("c"))
            runCurrent()
            old.complete(Unit)
            runCurrent()
            assertEquals(mapOf("c" to "c"), preloader.ready.value)
        }

    @Test
    fun changedSettingsInvalidateEvenTheSameSong() =
        runTest {
            val old = CompletableDeferred<Unit>()
            var calls = 0
            val preloader =
                CanvasPreloader(this) {
                    val version = ++calls
                    if (version == 1) withContext(NonCancellable) { old.await() }
                    version
                }
            preloader.update("a", song("b"))
            runCurrent()
            preloader.clear()
            preloader.update("a", song("b"))
            runCurrent()
            old.complete(Unit)
            runCurrent()
            assertEquals(2, preloader.ready.value["b"])
            preloader.clear()
            assertTrue(preloader.ready.value.isEmpty())
        }

    @Test
    fun failureDoesNotStopLaterPreloads() =
        runTest {
            val preloader =
                CanvasPreloader(this) { song ->
                    delay(if (song.id == "b") 100 else 200)
                    check(song.id != "b")
                    song.id
                }
            preloader.update("a", song("b"), song("previous"))
            advanceTimeBy(200)
            runCurrent()
            assertFalse("b" in preloader.ready.value)
            assertEquals("previous", preloader.ready.value["previous"])
            preloader.update("a", song("c"))
            advanceTimeBy(200)
            runCurrent()
            assertEquals("c", preloader.ready.value["c"])
        }
}
