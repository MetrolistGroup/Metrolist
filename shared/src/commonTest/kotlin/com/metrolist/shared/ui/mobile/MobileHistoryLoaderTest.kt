package com.metrolist.shared.ui.mobile

import com.metrolist.shared.db.StoredHistoryRow
import com.metrolist.shared.model.HistorySortMode
import com.metrolist.shared.model.SortDirection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MobileHistoryLoaderTest {
    private fun row(id: Long) = StoredHistoryRow(id, "song$id", "Song", "Artist", null, null, null, id, "local", null, null)

    @Test
    fun searchAndSortResetBoundedPagesAndPassEveryFilterToTheReader() =
        runTest {
            val requests = mutableListOf<List<Any>>()
            val loader =
                MobileHistoryLoader { limit, offset, query, mode, direction ->
                    requests += listOf(limit, offset, query, mode, direction)
                    (offset + 1L..offset + limit.toLong()).map(::row)
                }
            loader.refresh()
            loader.loadMore()
            assertEquals(200, loader.rows.size)
            loader.refresh("artist", HistorySortMode.ARTIST, SortDirection.ASCENDING)
            assertEquals(100, loader.rows.size)
            loader.loadMore()
            assertEquals(listOf(100, 100, "artist", HistorySortMode.ARTIST, SortDirection.ASCENDING), requests.last())
            loader.refresh("artist", HistorySortMode.TITLE, SortDirection.DESCENDING)
            assertEquals(listOf(100, 0, "artist", HistorySortMode.TITLE, SortDirection.DESCENDING), requests.last())
            assertTrue(requests.all { it.first() == 100 })
        }

    @Test
    fun obsoletePageIsIgnoredBeforeRefreshCompletes() = stalePageDuringRefresh(pageFinishesFirst = true)

    @Test
    fun obsoletePageIsIgnoredAfterRefreshCompletes() = stalePageDuringRefresh(pageFinishesFirst = false)

    private fun stalePageDuringRefresh(pageFinishesFirst: Boolean) =
        runTest {
            val initial = (1L..100L).map(::row)
            val refreshed = (1L..50L).map(::row)
            val pageResult = CompletableDeferred<List<StoredHistoryRow>>()
            val refreshResult = CompletableDeferred<List<StoredHistoryRow>>()
            var reads = 0
            val loader =
                MobileHistoryLoader { _, offset, _, _, _ ->
                    if (offset != 0) {
                        pageResult.await()
                    } else if (reads++ == 0) {
                        initial
                    } else {
                        refreshResult.await()
                    }
                }
            loader.refresh()
            val pageJob = launch(start = CoroutineStart.UNDISPATCHED) { loader.loadMore() }
            val refreshJob = launch(start = CoroutineStart.UNDISPATCHED) { loader.refresh(query = "updated") }
            if (pageFinishesFirst) {
                pageResult.complete((101L..200L).map(::row))
                pageJob.join()
                assertEquals(initial, loader.rows)
                assertTrue(loader.isLoading)
            }
            refreshResult.complete(refreshed)
            refreshJob.join()
            if (!pageFinishesFirst) {
                pageResult.complete((101L..200L).map(::row))
                pageJob.join()
            }
            assertEquals(refreshed, loader.rows)
            assertEquals(
                loader.rows.size,
                loader.rows
                    .map { it.id }
                    .toSet()
                    .size,
            )
            assertFalse(loader.hasMore)
            assertFalse(loader.isLoading)
            assertFalse(loader.loadingPage)
            assertFalse(loader.failed)
        }

    @Test
    fun obsoletePageFailureDoesNotResetNewPageState() =
        runTest {
            val initial = (1L..100L).map(::row)
            val oldPage = CompletableDeferred<List<StoredHistoryRow>>()
            val newPage = CompletableDeferred<List<StoredHistoryRow>>()
            var pages = 0
            val loader =
                MobileHistoryLoader { _, offset, _, _, _ ->
                    if (offset == 0) {
                        initial
                    } else if (pages++ == 0) {
                        oldPage.await()
                    } else {
                        newPage.await()
                    }
                }
            loader.refresh()
            val oldJob = launch(start = CoroutineStart.UNDISPATCHED) { loader.loadMore() }
            loader.refresh()
            val newJob = launch(start = CoroutineStart.UNDISPATCHED) { loader.loadMore() }
            oldPage.completeExceptionally(IllegalStateException("Obsolete page failed"))
            oldJob.join()
            assertTrue(loader.loadingPage)
            assertFalse(loader.failed)
            newPage.complete(listOf(row(101)))
            newJob.join()
            assertEquals(initial + row(101), loader.rows)
            assertFalse(loader.loadingPage)
        }

    @Test
    fun supersededRefreshCannotOverwriteTheLatestRefresh() =
        runTest {
            val stale = CompletableDeferred<List<StoredHistoryRow>>()
            var reads = 0
            val loader = MobileHistoryLoader { _, _, _, _, _ -> if (reads++ == 0) stale.await() else listOf(row(2)) }
            val oldJob = launch(start = CoroutineStart.UNDISPATCHED) { assertFalse(loader.refresh()) }
            assertTrue(loader.refresh(query = "updated"))
            stale.complete(listOf(row(1)))
            oldJob.join()
            assertEquals(listOf(row(2)), loader.rows)
            assertFalse(loader.isLoading)
        }

    @Test
    fun readFailuresCanBeRetriedWithoutDiscardingLoadedHistory() =
        runTest {
            var fail = true
            val history = listOf(row(1))
            val loader =
                MobileHistoryLoader { _, _, _, _, _ ->
                    if (fail) error("Read failed")
                    history
                }
            assertFalse(loader.refresh())
            assertTrue(loader.failed)
            assertFalse(loader.isLoading)
            fail = false
            assertTrue(loader.refresh())
            assertEquals(history, loader.rows)
            assertFalse(loader.failed)
            fail = true
            assertFalse(loader.refresh())
            assertEquals(history, loader.rows)
            fail = false
            assertTrue(loader.refresh())
            assertFalse(loader.failed)
        }

    @Test
    fun failedPageCanBeRetriedFromTheSameCursor() =
        runTest {
            val initial = (1L..100L).map(::row)
            var fail = true
            val loader =
                MobileHistoryLoader { _, offset, _, _, _ ->
                    if (offset == 0) {
                        initial
                    } else {
                        assertEquals(100, offset)
                        if (fail) error("Page failed")
                        listOf(row(101))
                    }
                }
            loader.refresh()
            loader.loadMore()
            assertTrue(loader.failed)
            assertFalse(loader.loadingPage)
            assertEquals(initial, loader.rows)
            fail = false
            loader.loadMore()
            assertEquals(initial + row(101), loader.rows)
            assertFalse(loader.hasMore)
            assertFalse(loader.failed)
        }

    @Test
    fun failedFilterChangeCannotAppendPagesFromTheNewFilterToOldRows() =
        runTest {
            val requests = mutableListOf<Pair<Int, Int>>()
            var fail = false
            val loader =
                MobileHistoryLoader { limit, offset, _, _, _ ->
                    requests += limit to offset
                    if (fail) error("Read failed")
                    (offset + 1L..offset + limit.toLong()).map(::row)
                }
            loader.refresh()
            loader.loadMore()
            fail = true
            assertFalse(loader.refresh("changed"))
            val reads = requests.size
            loader.loadMore()
            assertEquals(reads, requests.size)
            assertEquals(200, loader.rows.size)
            fail = false
            assertTrue(loader.refresh("changed"))
            assertEquals(100 to 0, requests.last())
            assertEquals(100, loader.rows.size)
        }

    @Test
    fun insertionBetweenPagesKeepsRowsUniqueAndAdvancesByRawPageSize() =
        runTest {
            var source = (250L downTo 1L).map(::row)
            val offsets = mutableListOf<Int>()
            val loader =
                MobileHistoryLoader { size, offset, _, _, _ ->
                    offsets += offset
                    source.drop(offset).take(size)
                }
            assertTrue(loader.refresh())
            source = listOf(row(251)) + source
            loader.loadMore()
            assertEquals((250L downTo 52L).toList(), loader.rows.map { it.id })
            assertTrue(loader.hasMore)
            loader.loadMore()
            assertEquals((250L downTo 1L).toList(), loader.rows.map { it.id })
            assertEquals(listOf(0, 100, 200), offsets)
            assertFalse(loader.hasMore)
        }

    @Test
    fun fullyOverlappingRawPageDoesNotStrandOlderEntries() =
        runTest {
            var source = (150L downTo 1L).map(::row)
            val offsets = mutableListOf<Int>()
            val loader =
                MobileHistoryLoader { size, offset, _, _, _ ->
                    offsets += offset
                    source.drop(offset).take(size)
                }
            assertTrue(loader.refresh())
            source = (250L downTo 151L).map(::row) + source
            loader.loadMore()
            assertEquals((150L downTo 1L).toList(), loader.rows.map { it.id })
            assertEquals(listOf(0, 100, 200), offsets)
            assertFalse(loader.hasMore)
        }

    @Test
    fun failedReadAfterOverlapRetriesTheUnconsumedOffset() =
        runTest {
            val initial = (150L downTo 51L).map(::row)
            val offsets = mutableListOf<Int>()
            var fail = true
            val loader =
                MobileHistoryLoader { _, offset, _, _, _ ->
                    offsets += offset
                    when (offset) {
                        0, 100 -> initial
                        200 -> if (fail) error("Read failed") else (50L downTo 1L).map(::row)
                        else -> error("Unexpected offset")
                    }
                }
            assertTrue(loader.refresh())
            loader.loadMore()
            assertEquals(initial, loader.rows)
            assertTrue(loader.failed)
            assertTrue(loader.hasMore)
            assertFalse(loader.loadingPage)

            fail = false
            loader.loadMore()
            assertEquals((150L downTo 1L).toList(), loader.rows.map { it.id })
            assertEquals(listOf(0, 100, 200, 200), offsets)
            assertFalse(loader.hasMore)
            assertFalse(loader.failed)
        }

    @Test
    fun refreshSupersedesAReadAfterAnOverlapPage() =
        runTest {
            val initial = (150L downTo 51L).map(::row)
            val readStarted = CompletableDeferred<Unit>()
            val staleResult = CompletableDeferred<List<StoredHistoryRow>>()
            val loader =
                MobileHistoryLoader { _, offset, query, _, _ ->
                    when {
                        query == "changed" -> {
                            listOf(row(999))
                        }

                        offset == 0 || offset == 100 -> {
                            initial
                        }

                        offset == 200 -> {
                            readStarted.complete(Unit)
                            staleResult.await()
                        }

                        else -> {
                            error("Unexpected offset")
                        }
                    }
                }
            assertTrue(loader.refresh())
            val page = launch { loader.loadMore() }
            withTimeout(1_000L) { readStarted.await() }
            assertTrue(loader.loadingPage)
            assertTrue(loader.refresh(query = "changed"))
            staleResult.complete(listOf(row(50)))
            page.join()
            assertEquals(listOf(row(999)), loader.rows)
            assertFalse(loader.hasMore)
            assertFalse(loader.loadingPage)
            assertFalse(loader.failed)
        }

    @Test
    fun cancellationIsNotReportedAsAReadFailure() =
        runTest {
            val loader = MobileHistoryLoader { _, _, _, _, _ -> throw CancellationException() }
            assertFailsWith<CancellationException> { loader.refresh() }
            assertFalse(loader.failed)
            assertFalse(loader.isLoading)
        }
}
