package com.metrolist.desktop.state.history

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.metrolist.desktop.history.HistoryEntry
import com.metrolist.desktop.history.HistoryRepository
import com.metrolist.desktop.state.playback.PlayerState
import com.metrolist.shared.model.HistoryEntryUi
import com.metrolist.shared.model.HistorySortMode
import com.metrolist.shared.model.SortDirection
import com.metrolist.shared.state.GlobalYouTubeRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HistoryScreenState(
    private val readPage: suspend (
        Int,
        Int,
        String,
        HistorySortMode,
        SortDirection,
    ) -> List<HistoryEntry> = HistoryRepository::getHistoryPage,
    private val readIds: suspend (String) -> Set<Long> = HistoryRepository::getVisibleHistoryIds,
    private val deleteHistory: suspend (
        Set<Long>,
        Boolean,
    ) -> Unit = { ids, remote -> HistoryRepository.deleteHistoryEntries(ids, remote) },
    private val historyChanged: () -> Unit = { PlayerState.refreshHistory() },
    private val syncHistory: suspend () -> Boolean = { HistoryRepository.syncFromYouTubeHistory() },
) {
    var entries by mutableStateOf(emptyList<HistoryEntry>())
    var uiEntries by mutableStateOf(emptyList<HistoryEntryUi>())
    var displayedEntries by mutableStateOf(emptyList<HistoryEntryUi>())
    var hasMore by mutableStateOf(false)
    private var loadJob: Job? = null
    private var pageJob: Job? = null
    private val pageSize = 100
    private var loadedFilter: Triple<String, HistorySortMode, SortDirection>? = null
    private var uiSource: List<HistoryEntry>? = null
    var loadFailed by mutableStateOf(false)
        private set
    var isLoading by mutableStateOf(true)
    var refreshKey by mutableStateOf(0)
    var deletionFailed by mutableStateOf(false)
        private set
    var isDeleting by mutableStateOf(false)
    var query by mutableStateOf("")
    var debouncedQuery by mutableStateOf("")
    var sortDirection by mutableStateOf(SortDirection.DESCENDING)
    var sortMode by mutableStateOf(HistorySortMode.NEWEST)
    var selectedIds by mutableStateOf(emptySet<Long>())
    var selectedHasRemoteEntries by mutableStateOf(false)
        private set

    fun loadHistory(scope: CoroutineScope): Job {
        val query = debouncedQuery
        val mode = sortMode
        val direction = sortDirection
        if (Triple(query, mode, direction) != loadedFilter) hasMore = false
        loadJob?.cancel()
        pageJob?.cancel()
        loadJob =
            scope.launch {
                isLoading = true
                loadFailed = false
                deletionFailed = false
                try {
                    val local = withContext(Dispatchers.IO) { readPage(pageSize, 0, query, mode, direction) }
                    loadedFilter = Triple(query, mode, direction)
                    entries = local
                    hasMore = local.size == pageSize
                    if (selectedIds.isNotEmpty()) selectedIds = selectedIds.intersect(readIds(query))
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    loadFailed = true
                    return@launch
                } finally {
                    if (currentCoroutineContext().isActive) isLoading = false
                }
                val synced =
                    try {
                        if (!withContext(Dispatchers.IO) { syncHistory() }) return@launch
                        withContext(Dispatchers.IO) { readPage(pageSize, 0, query, mode, direction) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        return@launch
                    }
                pageJob?.cancel()
                entries = synced
                hasMore = synced.size == pageSize
                if (selectedIds.isNotEmpty()) selectedIds = selectedIds.intersect(readIds(query))
            }
        return requireNotNull(loadJob)
    }

    fun deleteEntry(
        entry: HistoryEntry,
        alsoRemote: Boolean,
        scope: CoroutineScope,
    ) = deleteEntries(setOf(entry.id), alsoRemote, scope)

    fun deleteEntries(
        ids: Set<Long>,
        alsoRemote: Boolean,
        scope: CoroutineScope,
    ): Job {
        isDeleting = true
        deletionFailed = false
        return scope.launch {
            try {
                withContext(Dispatchers.IO) { deleteHistory(ids, alsoRemote) }
                refreshAfterDeletion()
                selectedIds = selectedIds - ids
                historyChanged()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                deletionFailed = true
            } finally {
                isDeleting = false
            }
        }
    }

    fun updateEntry(
        entry: HistoryEntry,
        title: String,
        artists: String,
        album: String?,
        scope: CoroutineScope,
    ) {
        scope.launch(Dispatchers.IO) {
            HistoryRepository.updateHistoryEntry(entry.id, title, artists, album)
            withContext(Dispatchers.Main) {
                refreshAfterDeletion()
                PlayerState.refreshHistory()
            }
        }
    }

    private suspend fun refreshAfterDeletion() {
        val limit = maxOf(pageSize, entries.size)
        entries = withContext(Dispatchers.IO) { readPage(limit, 0, debouncedQuery, sortMode, sortDirection) }
        hasMore = entries.size == limit
    }

    fun loadMore(scope: CoroutineScope) {
        if (!hasMore || pageJob?.isActive == true || isLoading) return
        val query = debouncedQuery
        val mode = sortMode
        val direction = sortDirection
        if (Triple(query, mode, direction) != loadedFilter) return
        val offset = entries.size
        pageJob =
            scope.launch {
                val next =
                    withContext(Dispatchers.IO) {
                        readPage(pageSize, offset, query, mode, direction)
                    }
                entries = entries + next
                hasMore = next.size == pageSize
            }
    }

    fun selectAll(
        ids: Set<Long>,
        scope: CoroutineScope,
    ) {
        if (ids.isEmpty() || ids.size < selectedIds.size) {
            selectedIds = emptySet()
        } else {
            val query = debouncedQuery
            scope.launch {
                val allIds = withContext(Dispatchers.IO) { readIds(query) }
                if (query == debouncedQuery) selectedIds = allIds
            }
        }
    }

    suspend fun prepareSelection() {
        selectedHasRemoteEntries = false
        if (selectedIds.isEmpty()) return
        try {
            selectedHasRemoteEntries = withContext(Dispatchers.IO) { HistoryRepository.hasHistoryRemoteTokens(selectedIds) }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            loadFailed = true
        }
    }

    suspend fun preparePresentation() {
        val source = entries
        val cachedUi = uiEntries.takeIf { uiSource === source }
        val result =
            withContext(Dispatchers.Default) {
                val context = currentCoroutineContext()
                val ui =
                    cachedUi ?: source.map {
                        context.ensureActive()
                        HistoryEntryUi(
                            it.id.toString(),
                            it.songId,
                            it.title,
                            it.artists,
                            it.album,
                            it.thumbnailUrl,
                            it.playedAt,
                            it.remoteFeedbackToken,
                            it.playTimeMs,
                            it.remoteDateLabel,
                            it.remoteOrder,
                            hasEstimatedDate = it.source == "YOUTUBE" && it.playTimeMs == null,
                        )
                    }
                ui
            }
        uiSource = source
        uiEntries = result
        displayedEntries = result
    }

    val canDeleteRemote: Boolean
        get() = GlobalYouTubeRepository.instance.isHistorySyncEnabled()
}
