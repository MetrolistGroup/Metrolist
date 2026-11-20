package com.metrolist.desktop.state.history

import com.metrolist.desktop.history.HistoryEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HistoryScreenStateTest {
    @Test
    fun failedSingleAndBulkDeletionRetainEntriesAndSelectionForRetry() =
        runTest {
            val entry = HistoryEntry(1L, "song", "Song", "Artist", null, null, null, 0L, "LOCAL", "token", 60_000L)
            for (single in listOf(true, false)) {
                val state =
                    HistoryScreenState(deleteHistory = { _, remote ->
                        assertTrue(remote)
                        error("offline")
                    }, historyChanged = { error("must not refresh") })
                state.entries = listOf(entry)
                state.selectedIds = setOf(1L, 2L)
                if (single) state.deleteEntry(entry, true, this).join() else state.deleteEntries(state.selectedIds, true, this).join()
                assertTrue(state.deletionFailed)
                assertFalse(state.isDeleting)
                assertEquals(listOf(entry), state.entries)
                assertEquals(setOf(1L, 2L), state.selectedIds)
            }
        }

    @Test
    fun cancellationIsNotReportedAsDeletionFailure() =
        runTest {
            val state = HistoryScreenState(deleteHistory = { _, _ -> throw CancellationException("cancelled") })
            val job = state.deleteEntries(setOf(1L), true, this)
            job.join()
            assertTrue(job.isCancelled)
            assertFalse(state.deletionFailed)
            assertFalse(state.isDeleting)
        }

    @Test
    fun aFailedNextPageRetainsTheCurrentPageAndCanBeRetried() =
        runTest {
            val entries =
                List(100) { index ->
                    HistoryEntry(index.toLong(), "song-$index", "Song", "Artist", null, null, null, 0L, "LOCAL", null, null)
                }
            val seenOffsets = mutableListOf<Int>()
            var failPage = true
            val state =
                HistoryScreenState(
                    readPage = { _, offset, _, _, _ ->
                        seenOffsets += offset
                        if (offset == 0) {
                            entries
                        } else {
                            if (failPage) throw IOException("temporary read failure")
                            listOf(entries.first().copy(id = 100L))
                        }
                    },
                    readIds = { setOf(1L) },
                    syncHistory = { false },
                )
            state.loadHistory(this).join()
            state.selectedIds = setOf(1L)

            val failures = mutableListOf<Throwable>()
            val scope =
                CoroutineScope(
                    SupervisorJob() + StandardTestDispatcher(testScheduler) +
                        CoroutineExceptionHandler { _, error ->
                            failures += error
                        },
                )
            try {
                state.loadMore(scope)
                scope.coroutineContext[Job]!!
                    .children
                    .single()
                    .join()
                assertTrue(failures.isEmpty(), "page errors must not escape the UI scope")
                assertTrue(state.loadFailed)
                assertFalse(state.hasMore)
                assertEquals(entries, state.entries)
                assertEquals(setOf(1L), state.selectedIds)

                state.loadMore(scope)
                assertEquals(listOf(0, 100), seenOffsets)
                failPage = false
                state.loadHistory(this).join()
                assertFalse(state.loadFailed)
                state.loadMore(scope)
                scope.coroutineContext[Job]!!
                    .children
                    .single()
                    .join()
                assertEquals(listOf(0, 100, 0, 100), seenOffsets)
                assertEquals(entries.map { it.id } + 100L, state.entries.map { it.id })
                assertFalse(state.hasMore)
                assertEquals(setOf(1L), state.selectedIds)
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun cancelledNextPageDoesNotReportAReadFailure() =
        runTest {
            val entries =
                List(100) { index ->
                    HistoryEntry(index.toLong(), "song-$index", "Song", "Artist", null, null, null, 0L, "LOCAL", null, null)
                }
            val state =
                HistoryScreenState(
                    readPage = { _, offset, _, _, _ -> if (offset == 0) entries else throw CancellationException("cancelled") },
                    syncHistory = { false },
                )
            state.loadHistory(this).join()
            state.loadMore(this)
            coroutineContext[Job]!!.children.single().join()
            assertFalse(state.loadFailed)
            assertTrue(state.hasMore)
            assertEquals(entries, state.entries)
        }

    @Test
    fun backgroundSyncDoesNotRestartFailedPaginationBeforeRetry() =
        runTest {
            val entries =
                List(100) { index ->
                    HistoryEntry(index.toLong(), "song-$index", "Song", "Artist", null, null, null, 0L, "LOCAL", null, null)
                }
            val syncedEntries = entries.map { it.copy(title = "Synced") }
            val syncStarted = CompletableDeferred<Unit>()
            val completeSync = CompletableDeferred<Unit>()
            val state =
                HistoryScreenState(
                    readPage = { _, offset, _, _, _ ->
                        if (offset != 0) throw IOException("temporary page failure")
                        if (completeSync.isCompleted) syncedEntries else entries
                    },
                    syncHistory = {
                        syncStarted.complete(Unit)
                        completeSync.await()
                        true
                    },
                )
            val initialLoad = state.loadHistory(this)
            syncStarted.await()
            assertTrue(state.hasMore)
            state.loadMore(this)
            coroutineContext[Job]!!.children.single { it != initialLoad }.join()
            assertTrue(state.loadFailed)
            assertFalse(state.hasMore)

            completeSync.complete(Unit)
            initialLoad.join()

            assertEquals(syncedEntries, state.entries)
            assertTrue(state.loadFailed)
            assertFalse(state.hasMore, "sync can refresh loaded entries but must not restart the failed pager")
        }

    @Test
    fun historyRemovalReplacesThePendingOffsetPageFromBeforeTheDeletion() = assertPendingOffsetPageAfterDeletion(failOldRead = false)

    @Test
    fun aLateErrorFromBeforeHistoryRemovalDoesNotFailTheReplacementPage() = assertPendingOffsetPageAfterDeletion(failOldRead = true)

    private fun assertPendingOffsetPageAfterDeletion(failOldRead: Boolean) =
        runTest {
            val archive =
                AtomicReference(
                    (1L..205L).map { id ->
                        HistoryEntry(id, "song-$id", "Song", "Artist", null, null, null, id, "LOCAL", null, null)
                    },
                )
            val started = CompletableDeferred<Unit>()
            val releaseOldRead = CompletableDeferred<Unit>()
            val offsetReads = AtomicInteger()
            val state =
                HistoryScreenState(
                    readPage = { limit, offset, _, _, _ ->
                        val snapshot = archive.get().drop(offset).take(limit)
                        if (offset > 0 && offsetReads.incrementAndGet() == 1) {
                            withContext(NonCancellable) {
                                started.complete(Unit)
                                releaseOldRead.await()
                                if (failOldRead) throw IOException("old archive read failed")
                            }
                        }
                        snapshot
                    },
                    deleteHistory = { ids, _ -> archive.updateAndGet { loaded -> loaded.filterNot { it.id in ids } } },
                    historyChanged = {},
                    syncHistory = { false },
                )
            try {
                state.loadHistory(this).join()
                state.loadMore(this)
                val oldPage = coroutineContext[Job]!!.children.single()
                started.await()
                state.deleteEntries(setOf(1L), false, this).join()

                releaseOldRead.complete(Unit)
                oldPage.join()
                coroutineContext[Job]!!.children.toList().forEach { it.join() }

                assertFalse(state.loadFailed)
                assertEquals((2L..201L).toList(), state.entries.map { it.id })
                assertTrue(state.hasMore)
                assertEquals(2, offsetReads.get())
            } finally {
                releaseOldRead.complete(Unit)
            }
        }

    @Test
    fun deletionRefreshDoesNotCancelPaginationForANewerFilter() = assertNewerLoadKeepsItsPage(changeFilter = true)

    @Test
    fun deletionRefreshDoesNotCancelPaginationAfterReloadingTheSameFilter() = assertNewerLoadKeepsItsPage(changeFilter = false)

    private fun assertNewerLoadKeepsItsPage(changeFilter: Boolean) =
        runTest {
            fun rows(ids: LongRange) = ids.map { HistoryEntry(it, "song-$it", "Song", "Artist", null, null, null, it, "LOCAL", null, null) }

            val oldRows = rows(1L..205L)
            val newRows = rows(1001L..1205L)
            val oldRefreshStarted = CompletableDeferred<Unit>()
            val releaseOldRefresh = CompletableDeferred<Unit>()
            val newPageStarted = CompletableDeferred<Unit>()
            val releaseNewPage = CompletableDeferred<Unit>()
            val firstPageReads = AtomicInteger()
            val newOffsetReads = AtomicInteger()
            val state =
                HistoryScreenState(
                    readPage = { limit, offset, _, _, _ ->
                        if (offset == 0) {
                            when (firstPageReads.incrementAndGet()) {
                                1 -> {
                                    oldRows.take(limit)
                                }

                                2 -> {
                                    oldRefreshStarted.complete(Unit)
                                    releaseOldRefresh.await()
                                    oldRows.drop(1).take(limit)
                                }

                                else -> {
                                    newRows.take(limit)
                                }
                            }
                        } else {
                            newOffsetReads.incrementAndGet()
                            newPageStarted.complete(Unit)
                            releaseNewPage.await()
                            newRows.drop(offset).take(limit)
                        }
                    },
                    deleteHistory = { _, _ -> },
                    historyChanged = {},
                    syncHistory = { false },
                )
            try {
                state.loadHistory(this).join()
                val deletion = state.deleteEntries(setOf(1L), false, this)
                oldRefreshStarted.await()
                if (changeFilter) state.debouncedQuery = "new filter"
                state.loadHistory(this).join()
                state.loadMore(this)
                val currentPage = coroutineContext[Job]!!.children.single { it != deletion }
                newPageStarted.await()

                releaseOldRefresh.complete(Unit)
                deletion.join()
                assertFalse(currentPage.isCancelled)
                assertEquals(newRows.take(100), state.entries)

                releaseNewPage.complete(Unit)
                currentPage.join()
                assertEquals(newRows.take(200), state.entries)
                assertEquals(1, newOffsetReads.get())
            } finally {
                releaseOldRefresh.complete(Unit)
                releaseNewPage.complete(Unit)
            }
        }
}
