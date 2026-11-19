package com.metrolist.desktop.state.history

import com.metrolist.desktop.history.HistoryEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import java.io.IOException
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
}
