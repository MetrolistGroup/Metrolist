package com.metrolist.desktop.storage

import com.metrolist.shared.model.SongItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadLyricsEnricherTest {
    private fun song(id: String) = SongItem(id, id, null, emptyList(), null)

    @Test
    fun restorationBatchesIdsAndNeverPollsCompletedSongs() =
        runTest {
            val songs = List(10_000) { song(it.toString()) }
            val queries = mutableListOf<List<String>>()
            val worker =
                DownloadLyricsEnricher(backgroundScope, {
                    queries += it
                    it.toSet()
                }, { error("Already enriched") }, { true })
            worker.restore(songs)
            runCurrent()
            assertEquals(20, queries.size)
            assertTrue(queries.all { it.size == 500 })
            advanceTimeBy(3_600_000)
            runCurrent()
            assertEquals(20, queries.size)
        }

    @Test
    fun slowLyricsReturnImmediatelyDeduplicateAndPersistBeforeStoppingRetries() =
        runTest {
            val release = CompletableDeferred<Unit>()
            val completed = mutableSetOf<String>()
            var fetches = 0
            var queries = 0
            val worker =
                DownloadLyricsEnricher(backgroundScope, { ids ->
                    queries++
                    ids.filter { it in completed }.toSet()
                }, {
                    fetches++
                    release.await()
                    completed += it.id
                }, { true })
            worker.enqueue(song("pending"))
            worker.enqueue(song("pending"))
            runCurrent()
            assertEquals(1, fetches)
            assertTrue(completed.isEmpty())
            release.complete(Unit)
            runCurrent()
            assertEquals(setOf("pending"), completed)
            val settledQueries = queries
            advanceTimeBy(3_600_000)
            runCurrent()
            assertEquals(settledQueries, queries)
            assertEquals(1, fetches)
        }

    @Test
    fun failedFetchesBackOffAndDeletionCancelsRetries() =
        runTest {
            var attempts = 0
            val worker = DownloadLyricsEnricher(backgroundScope, { emptySet() }, { attempts++ }, { true })
            worker.enqueue(song("pending"))
            runCurrent()
            assertEquals(1, attempts)
            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(2, attempts)
            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(2, attempts)
            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(3, attempts)
            worker.cancel(setOf("pending"))
            advanceTimeBy(3_600_000)
            runCurrent()
            assertEquals(3, attempts)
        }

    @Test
    fun restartRetriesOnlyMissingResultsAndDeletedSongsAreIgnored() =
        runTest {
            val fetched = mutableListOf<String>()
            val completed = mutableSetOf("complete", "confirmed-miss")
            val worker =
                DownloadLyricsEnricher(backgroundScope, { ids -> ids.filter { it in completed }.toSet() }, {
                    fetched += it.id
                    completed += it.id
                }, { it != "deleted" })
            worker.restore(listOf(song("complete"), song("confirmed-miss"), song("pending"), song("deleted")))
            runCurrent()
            assertEquals(listOf("pending"), fetched)
        }

    @Test
    fun backfillFetchesOneSongAtATime() =
        runTest {
            val release = CompletableDeferred<Unit>()
            val started = mutableListOf<String>()
            val worker =
                DownloadLyricsEnricher(backgroundScope, { emptySet() }, {
                    started += it.id
                    release.await()
                }, { true })
            worker.restore(listOf(song("a"), song("b"), song("c")))
            runCurrent()
            assertEquals(listOf("a"), started)
            release.complete(Unit)
            runCurrent()
            assertEquals(listOf("a", "b", "c"), started)
        }
}
