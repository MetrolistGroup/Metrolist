package com.metrolist.shared.ui.mobile

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.metrolist.shared.db.StoredHistoryRow
import com.metrolist.shared.model.HistorySortMode
import com.metrolist.shared.model.SortDirection
import kotlinx.coroutines.CancellationException

internal class MobileHistoryLoader(
    private val read: suspend (Int, Int, String, HistorySortMode, SortDirection) -> List<StoredHistoryRow>,
) {
    var rows by mutableStateOf(emptyList<StoredHistoryRow>())
        private set
    var hasMore by mutableStateOf(false)
        private set
    var isLoading by mutableStateOf(true)
        private set
    var loadingPage by mutableStateOf(false)
        private set
    var failed by mutableStateOf(false)
        private set

    private var generation = 0L
    private var filter = Triple("", HistorySortMode.NEWEST, SortDirection.DESCENDING)
    private var loadedFilter: Triple<String, HistorySortMode, SortDirection>? = null

    suspend fun refresh(
        query: String = "",
        sortMode: HistorySortMode = HistorySortMode.NEWEST,
        direction: SortDirection = SortDirection.DESCENDING,
    ): Boolean {
        val requestedFilter = Triple(query, sortMode, direction)
        val limit = if (requestedFilter == loadedFilter) maxOf(100, rows.size) else 100
        if (requestedFilter != loadedFilter) hasMore = false
        filter = requestedFilter
        val requestGeneration = ++generation
        loadingPage = false
        isLoading = true
        failed = false
        try {
            val refreshed = read(limit, 0, query, sortMode, direction)
            if (requestGeneration != generation) return false
            loadedFilter = requestedFilter
            rows = refreshed
            hasMore = rows.size == limit
            return true
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            if (requestGeneration == generation) failed = true
            return false
        } finally {
            if (requestGeneration == generation) isLoading = false
        }
    }

    suspend fun loadMore() {
        if (!hasMore || loadingPage || isLoading || filter != loadedFilter) return
        val (query, sortMode, direction) = filter
        val requestGeneration = generation
        loadingPage = true
        try {
            val page = read(100, rows.size, query, sortMode, direction)
            if (requestGeneration != generation) return
            rows = rows + page
            hasMore = page.size == 100
            failed = false
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            if (requestGeneration == generation) failed = true
        } finally {
            if (requestGeneration == generation) loadingPage = false
        }
    }
}
