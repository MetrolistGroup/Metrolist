package com.metrolist.desktop.state.history

import com.metrolist.desktop.history.HistoryEntry
import com.metrolist.shared.model.HistorySortMode
import com.metrolist.shared.model.SortDirection
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HistoryPagingTest {
    @Test
    fun filtersResetPagesAndBulkSelectionDoesNotHydrateTheArchive() =
        runBlocking {
            val requests = mutableListOf<List<Any>>()
            val allIds = (1L..205L).toSet()
            val state =
                HistoryScreenState(
                    readPage = { limit, offset, query, mode, direction ->
                        requests += listOf(limit, offset, query, mode, direction)
                        allIds.drop(offset).take(limit).map { id ->
                            HistoryEntry(id, "song$id", "Title", "Artist", null, null, null, id, "LOCAL", null, null)
                        }
                    },
                    readIds = { allIds },
                    syncHistory = { false },
                )
            state.loadHistory(this).join()
            coroutineScope { state.loadMore(this) }
            assertEquals(200, state.entries.size)
            state.debouncedQuery = "artist"
            state.sortMode = HistorySortMode.ARTIST
            state.sortDirection = SortDirection.ASCENDING
            state.loadHistory(this).join()
            assertEquals(100, state.entries.size)
            assertEquals(listOf(100, 0, "artist", HistorySortMode.ARTIST, SortDirection.ASCENDING), requests.last())
            coroutineScope { state.selectAll(setOf(1L), this) }
            assertEquals(allIds, state.selectedIds)
            assertEquals(100, state.entries.size)
            state.preparePresentation()
            assertEquals(state.entries.map { it.id.toString() }, state.displayedEntries.map { it.id })
            assertTrue(requests.all { it.first() == 100 })
            state.sortMode = HistorySortMode.TITLE
            state.loadHistory(this).join()
            assertEquals(allIds, state.selectedIds)
        }
}
