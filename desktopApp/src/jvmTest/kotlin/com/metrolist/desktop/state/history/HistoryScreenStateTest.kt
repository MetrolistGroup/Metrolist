package com.metrolist.desktop.state.history

import com.metrolist.desktop.history.HistoryEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
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
}
