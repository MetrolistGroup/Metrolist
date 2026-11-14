package com.metrolist.android.viewmodels

import androidx.lifecycle.ViewModelStore
import com.metrolist.android.data.HistoryEntry
import com.metrolist.shared.model.HistorySortMode
import com.metrolist.shared.model.SortDirection
import com.metrolist.shared.model.toggleDisplayedHistorySelection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModelTest {
    @Test
    fun deletionFinishesAfterClearingTheHistoryViewModel() = checkMutationAfterClearing(updateEntry = false)

    @Test
    fun metadataEditFinishesAfterClearingTheHistoryViewModel() = checkMutationAfterClearing(updateEntry = true)

    private fun checkMutationAfterClearing(updateEntry: Boolean) =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val store = ViewModelStore()
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val finished = CompletableDeferred<Unit>()
            var entries = listOf(entry(1))
            try {
                val model =
                    HistoryViewModel(
                        { _, _, _, _, _ -> entries },
                        { setOf(1L) },
                        { _, _ ->
                            started.complete(Unit)
                            release.await()
                            entries = emptyList()
                            finished.complete(Unit)
                        },
                        { _, title, _, _ ->
                            started.complete(Unit)
                            release.await()
                            entries = listOf(entry(1).copy(title = title))
                            finished.complete(Unit)
                        },
                        { false },
                        { true },
                        applicationScope = applicationScope(),
                    )
                store.put("history", model)
                model.uiState.first { !it.isLoading }
                if (updateEntry) model.updateEntry(1, "Edited", "", null) else model.deleteEntry(1, deleteRemote = false)
                started.await()
                store.clear()
                release.complete(Unit)
                finished.await()
                assertEquals(if (updateEntry) listOf("Edited") else emptyList<String>(), entries.map { it.title })
            } finally {
                release.complete(Unit)
                store.clear()
                Dispatchers.resetMain()
            }
        }

    @Test
    fun singleAndSelectedDeletionReloadOlderPlaysWithoutSyncing() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            try {
                val plays = (1L..205L).flatMap { id -> listOf(entry(id), entry(id + 205).copy(songId = "song-$id")) }.toMutableList()

                fun displayed() = plays.sortedByDescending { it.id }.distinctBy { it.songId }
                var syncs = 0
                val deleted = mutableListOf<Set<Long>>()
                val model =
                    HistoryViewModel(
                        { limit, offset, _, _, _ -> displayed().drop(offset).take(limit) },
                        { displayed().mapTo(mutableSetOf()) { it.id } },
                        { ids, _ ->
                            deleted += ids
                            plays.removeAll { it.id in ids }
                        },
                        { _, _, _, _ -> },
                        {
                            syncs++
                            false
                        },
                        { true },
                        applicationScope = applicationScope(),
                    )
                advanceUntilIdle()
                model.uiState.first { !it.isLoading && !it.isDeleting }
                model.deleteEntry(410, false)
                advanceUntilIdle()
                model.uiState.first { !it.isLoading && !it.isDeleting }
                model.loadMore()
                advanceUntilIdle()
                model.uiState.first { it.entries.size == 200 }
                model.loadMore()
                advanceUntilIdle()
                model.uiState.first { !it.hasMore }
                assertTrue(
                    model.uiState.value.entries
                        .any { it.id == 205L },
                )
                assertEquals(
                    409L,
                    model.uiState.value.entries
                        .first()
                        .id,
                )
                model.selectAll(
                    model.uiState.value.entries
                        .mapTo(mutableSetOf()) { it.id },
                )
                advanceUntilIdle()
                assertEquals(displayed().mapTo(mutableSetOf()) { it.id }, model.uiState.value.selectedIds)
                model.deleteEntries(model.uiState.value.selectedIds, false)
                advanceUntilIdle()
                model.uiState.first { !it.isLoading && !it.isDeleting }
                assertEquals(listOf(setOf(410L), (206L..409L).toSet() + 205L), deleted)
                assertEquals((1L..204L).toSet(), plays.mapTo(mutableSetOf()) { it.id })
                assertEquals(
                    204L,
                    model.uiState.value.entries
                        .first()
                        .id,
                )
                assertTrue(
                    model.uiState.value.selectedIds
                        .isEmpty(),
                )
                assertEquals(1, syncs)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun failedRefreshAfterDeletionRetriesLoadingWithoutRepeatingDeletion() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            try {
                var failRead = false
                var deletions = 0
                val model =
                    HistoryViewModel(
                        { _, _, _, _, _ ->
                            if (failRead) error("read failed")
                            if (deletions == 0) listOf(entry(2)) else listOf(entry(1).copy(songId = "song-2"))
                        },
                        { emptySet() },
                        { _, _ ->
                            deletions++
                            failRead = true
                        },
                        { _, _, _, _ -> },
                        { false },
                        { false },
                        applicationScope = applicationScope(),
                    )
                advanceUntilIdle()
                model.uiState.first { !it.isLoading && !it.isDeleting }
                model.deleteEntry(2, false)
                advanceUntilIdle()
                model.uiState.first { !it.isLoading && !it.isDeleting }
                assertEquals(HistoryError.LOAD, model.uiState.value.error)
                assertTrue(
                    model.uiState.value.entries
                        .isEmpty(),
                )
                failRead = false
                model.retry()
                advanceUntilIdle()
                model.uiState.first { !it.isLoading && !it.isDeleting }
                assertEquals(1, deletions)
                assertEquals(
                    1L,
                    model.uiState.value.entries
                        .single()
                        .id,
                )
                assertNull(model.uiState.value.error)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun failedDeletionKeepsRowsAndSelectionAndRetryDeletes() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            try {
                var entries = listOf(entry(1))
                var fail = true
                val model =
                    HistoryViewModel(
                        { _, _, _, _, _ -> entries },
                        { setOf(1L) },
                        { ids, remote ->
                            assertTrue(remote)
                            assertEquals(setOf(1L), ids)
                            if (fail) error("offline")
                            entries = emptyList()
                        },
                        { _, _, _, _ -> },
                        { false },
                        { true },
                        applicationScope = applicationScope(),
                    )
                advanceUntilIdle()
                model.uiState.first { !it.isLoading && !it.isDeleting }
                model.toggleSelection(1)
                model.deleteEntries(setOf(1), true)
                advanceUntilIdle()
                model.uiState.first { !it.isLoading && !it.isDeleting }
                assertEquals(entries, model.uiState.value.entries)
                assertEquals(setOf(1L), model.uiState.value.selectedIds)
                assertEquals(HistoryError.DELETE, model.uiState.value.error)
                assertFalse(model.uiState.value.isDeleting)
                fail = false
                model.retry()
                advanceUntilIdle()
                model.uiState.first { !it.isLoading && !it.isDeleting }
                assertTrue(
                    model.uiState.value.entries
                        .isEmpty(),
                )
                assertTrue(
                    model.uiState.value.selectedIds
                        .isEmpty(),
                )
                assertNull(model.uiState.value.error)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun cancellationIsNotReportedAsDeletionFailure() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            try {
                val model =
                    HistoryViewModel(
                        { _, _, _, _, _ -> listOf(entry(1)) },
                        { setOf(1L) },
                        { _, _ -> throw CancellationException() },
                        { _, _, _, _ -> },
                        { false },
                        { true },
                        applicationScope = applicationScope(),
                    )
                advanceUntilIdle()
                model.uiState.first { !it.isLoading && !it.isDeleting }
                model.toggleSelection(1)
                model.deleteEntries(setOf(1), true)
                advanceUntilIdle()
                model.uiState.first { !it.isLoading && !it.isDeleting }
                assertEquals(setOf(1L), model.uiState.value.selectedIds)
                assertEquals(1, model.uiState.value.entries.size)
                assertFalse(model.uiState.value.isDeleting)
                assertNull(model.uiState.value.error)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun searchSortAndSelectAllKeepDisplayRowsPaged() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            try {
                val calls = mutableListOf<List<Any>>()
                val allIds = (1L..10_000L).toSet()
                var selectionQuery: String? = null
                val model =
                    HistoryViewModel(
                        { limit, offset, query, sort, direction ->
                            calls += listOf(limit, offset, query, sort, direction)
                            (offset + 1L..offset + limit.toLong()).map(::entry)
                        },
                        {
                            selectionQuery = it
                            allIds
                        },
                        { _, _ -> },
                        { _, _, _, _ -> },
                        { false },
                        { true },
                        applicationScope = applicationScope(),
                    )
                advanceUntilIdle()
                model.uiState.first { !it.isLoading && !it.isDeleting }
                model.setQuery("needle")
                advanceUntilIdle()
                model.uiState.first { !it.isLoading && !it.isDeleting }
                model.setSortMode(HistorySortMode.TITLE)
                advanceUntilIdle()
                model.uiState.first { !it.isLoading && !it.isDeleting }
                model.setSortDirection(SortDirection.DESCENDING)
                advanceUntilIdle()
                model.uiState.first { !it.isLoading && !it.isDeleting }
                model.selectAll(setOf(1L))
                advanceUntilIdle()
                model.uiState.first { !it.isLoading && !it.isDeleting }
                assertEquals("needle", selectionQuery)
                assertEquals(allIds, model.uiState.value.selectedIds)
                assertEquals(100, model.uiState.value.uiDisplayedEntries.size)
                assertTrue(model.uiState.value.hasMore)
                model.loadMore()
                advanceUntilIdle()
                model.uiState.first { !it.isLoading && !it.isDeleting }
                assertEquals(listOf(100, 100, "needle", HistorySortMode.TITLE, SortDirection.DESCENDING), calls.last())
                model.uiState.first { it.uiDisplayedEntries.size == 200 }
                assertEquals(200, model.uiState.value.uiDisplayedEntries.size)
                assertTrue(calls.all { it[0] == 100 })
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun deselectAllClearsSelectedEntriesBeyondLoadedPage() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            try {
                val entries = (1L..101L).map(::entry)
                val allIds = entries.mapTo(mutableSetOf()) { it.id }
                var selectionReads = 0
                val model =
                    HistoryViewModel(
                        { limit, offset, _, _, _ -> entries.drop(offset).take(limit) },
                        {
                            selectionReads++
                            allIds
                        },
                        { _, _ -> },
                        { _, _, _, _ -> },
                        { false },
                        { true },
                        applicationScope = applicationScope(),
                    )
                advanceUntilIdle()
                model.uiState.first { !it.isLoading }
                val displayedIds =
                    model.uiState.value.uiDisplayedEntries
                        .mapTo(mutableSetOf()) { it.id }
                val onSelectAll: (Set<String>) -> Unit = { model.selectAll(it.mapTo(mutableSetOf(), String::toLong)) }
                onSelectAll(toggleDisplayedHistorySelection(emptySet(), displayedIds))
                advanceUntilIdle()
                assertEquals(allIds, model.uiState.value.selectedIds)
                assertEquals(100, displayedIds.size)
                assertTrue(model.uiState.value.hasMore)

                val selectedIds =
                    model.uiState.value.selectedIds
                        .mapTo(mutableSetOf(), Long::toString)
                onSelectAll(toggleDisplayedHistorySelection(selectedIds, displayedIds))
                advanceUntilIdle()
                assertEquals(emptySet<Long>(), model.uiState.value.selectedIds)
                assertEquals(1, selectionReads)
                assertEquals(100, model.uiState.value.uiDisplayedEntries.size)
                assertTrue(model.uiState.value.hasMore)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun overlappingPagesKeepOneRowPerHistoryId() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            try {
                var entries = (1L..250L).map(::entry)
                val model =
                    HistoryViewModel(
                        { limit, offset, _, _, _ -> entries.drop(offset).take(limit) },
                        { emptySet() },
                        { _, _ -> },
                        { _, _, _, _ -> },
                        { false },
                        { true },
                        applicationScope = applicationScope(),
                    )
                advanceUntilIdle()
                model.uiState.first { !it.isLoading }
                entries = listOf(entry(251)) + entries
                model.loadMore()
                advanceUntilIdle()
                model.uiState.first { it.entries.size == 199 }
                assertEquals(
                    (1L..199L).toList(),
                    model.uiState.value.entries
                        .map { it.id },
                )
                assertEquals(
                    199,
                    model.uiState.value.uiDisplayedEntries
                        .map { it.id }
                        .toSet()
                        .size,
                )
                assertTrue(model.uiState.value.hasMore)

                model.loadMore()
                advanceUntilIdle()
                model.uiState.first { !it.hasMore }
                assertEquals(
                    (1L..250L).toList(),
                    model.uiState.value.entries
                        .map { it.id },
                )
                assertEquals(250, model.uiState.value.uiDisplayedEntries.size)
            } finally {
                Dispatchers.resetMain()
            }
        }

    private fun TestScope.applicationScope() =
        CoroutineScope(backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext[Job]))

    private fun entry(id: Long) = HistoryEntry(id, "song-$id", "Song", "", null, null, null, null, id, "LOCAL", "token")
}
