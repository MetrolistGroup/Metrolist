package com.metrolist.shared.ui.mobile

import com.metrolist.shared.db.StoredHistoryRow
import com.metrolist.shared.model.HistorySortMode
import com.metrolist.shared.model.SortDirection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
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
    fun cancellationIsNotReportedAsAReadFailure() =
        runTest {
            val loader = MobileHistoryLoader { _, _, _, _, _ -> throw CancellationException() }
            assertFailsWith<CancellationException> { loader.refresh() }
            assertFalse(loader.failed)
            assertFalse(loader.isLoading)
        }
}
