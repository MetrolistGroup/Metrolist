package com.metrolist.android.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.android.data.HistoryEntry
import com.metrolist.android.data.PlayHistoryRepository
import com.metrolist.shared.model.HistoryEntryUi
import com.metrolist.shared.model.HistorySortMode
import com.metrolist.shared.model.SortDirection
import com.metrolist.shared.model.defaultDirection
import com.metrolist.shared.state.GlobalYouTubeRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class HistoryUiState(
    val entries: List<HistoryEntry> = emptyList(),
    val displayedEntries: List<HistoryEntry> = emptyList(),
    val uiEntries: List<HistoryEntryUi> = emptyList(),
    val uiDisplayedEntries: List<HistoryEntryUi> = emptyList(),
    val query: String = "",
    val sortMode: HistorySortMode = HistorySortMode.NEWEST,
    val sortDirection: SortDirection = SortDirection.DESCENDING,
    val selectedIds: Set<Long> = emptySet(),
    val isLoading: Boolean = true,
    val hasMore: Boolean = false,
    val isDeleting: Boolean = false,
    val canDeleteRemote: Boolean = false,
    val error: HistoryError? = null,
)

enum class HistoryError { LOAD, DELETE }

class HistoryViewModel internal constructor(
    private val readPage: suspend (Int, Int, String, HistorySortMode, SortDirection) -> List<HistoryEntry>,
    private val readIds: suspend (String) -> Set<Long>,
    private val delete: suspend (Set<Long>, Boolean) -> Unit,
    private val update: suspend (Long, String, String, String?) -> Unit,
    private val sync: suspend () -> Boolean,
    private val canDeleteRemote: () -> Boolean,
    private val applicationScope: CoroutineScope,
) : ViewModel() {
    constructor(repository: PlayHistoryRepository, applicationScope: CoroutineScope) : this(
        repository::getHistoryPage,
        repository::getHistoryIds,
        repository::deleteHistoryEntries,
        repository::updateHistoryEntry,
        repository::syncFromYouTubeHistory,
        { GlobalYouTubeRepository.instance.isHistorySyncEnabled() },
        applicationScope,
    )

    private val _uiState = MutableStateFlow(HistoryUiState())
    val uiState: StateFlow<HistoryUiState> = _uiState.asStateFlow()
    private var loadJob: Job? = null
    private var pageJob: Job? = null
    private var selectionJob: Job? = null
    private val pageSize = 100
    private var failedDeletion: Pair<Set<Long>, Boolean>? = null

    init {
        loadHistory()
    }

    fun loadHistory() = reload(syncRemote = true)

    private suspend fun page(
        state: HistoryUiState,
        offset: Int = 0,
    ) = readPage(pageSize, offset, state.query, state.sortMode, state.sortDirection)

    private suspend fun publish(
        entries: List<HistoryEntry>,
        hasMore: Boolean,
    ) {
        // Offset pages can overlap when playback inserts a newer history row.
        val uniqueEntries = entries.distinctBy(HistoryEntry::id)
        val rows =
            withContext(Dispatchers.Default) {
                uniqueEntries.map { entry ->
                    HistoryEntryUi(
                        entry.id.toString(),
                        entry.songId,
                        entry.title,
                        entry.artists,
                        entry.album,
                        entry.thumbnailUrl,
                        entry.playedAt,
                        entry.remoteFeedbackToken,
                        entry.playTimeMs,
                        entry.remoteDateLabel,
                        entry.remoteOrder,
                        hasEstimatedDate = entry.source == "YOUTUBE" && entry.playTimeMs == null,
                    )
                }
            }
        _uiState.value =
            _uiState.value.copy(
                entries = uniqueEntries,
                displayedEntries = uniqueEntries,
                uiEntries = rows,
                uiDisplayedEntries = rows,
                hasMore = hasMore,
                isLoading = false,
            )
    }

    private fun reload(syncRemote: Boolean = false) {
        loadJob?.cancel()
        pageJob?.cancel()
        selectionJob?.cancel()
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)
        val state = _uiState.value
        loadJob =
            viewModelScope.launch {
                try {
                    val local = page(state)
                    publish(local, local.size == pageSize)
                    _uiState.value = _uiState.value.copy(canDeleteRemote = canDeleteRemote())
                    if (syncRemote) {
                        val synced =
                            try {
                                sync()
                            } catch (error: CancellationException) {
                                throw error
                            } catch (_: Exception) {
                                false
                            }
                        if (synced) {
                            pageJob?.cancel()
                            val refreshed = page(state)
                            publish(refreshed, refreshed.size == pageSize)
                        }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    _uiState.value = _uiState.value.copy(isLoading = false, error = HistoryError.LOAD)
                }
            }
    }

    fun retry() {
        val failed = failedDeletion
        if (_uiState.value.error == HistoryError.DELETE && failed != null) {
            deleteEntries(failed.first, failed.second)
        } else {
            loadHistory()
        }
    }

    fun setQuery(query: String) {
        if (_uiState.value.query == query) return
        _uiState.value = _uiState.value.copy(query = query, selectedIds = emptySet())
        reload()
    }

    fun setSortMode(sortMode: HistorySortMode) {
        if (_uiState.value.sortMode == sortMode) return
        _uiState.value = _uiState.value.copy(sortMode = sortMode, sortDirection = sortMode.defaultDirection, selectedIds = emptySet())
        reload()
    }

    fun setSortDirection(direction: SortDirection) {
        if (_uiState.value.sortDirection == direction) return
        _uiState.value = _uiState.value.copy(sortDirection = direction)
        reload()
    }

    fun toggleSelection(id: Long) {
        selectionJob?.cancel()
        val selected = _uiState.value.selectedIds
        _uiState.value = _uiState.value.copy(selectedIds = if (id in selected) selected - id else selected + id)
    }

    fun selectAll(ids: Set<Long>) {
        selectionJob?.cancel()
        if (ids.isEmpty() || !_uiState.value.hasMore) {
            _uiState.value = _uiState.value.copy(selectedIds = ids)
            return
        }
        val query = _uiState.value.query
        selectionJob =
            viewModelScope.launch {
                try {
                    val selected = readIds(query)
                    _uiState.value = _uiState.value.copy(selectedIds = selected)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    _uiState.value = _uiState.value.copy(error = HistoryError.LOAD)
                }
            }
    }

    fun loadMore() {
        if (!_uiState.value.hasMore || _uiState.value.isLoading || pageJob?.isActive == true || _uiState.value.isDeleting) return
        val state = _uiState.value
        pageJob =
            viewModelScope.launch {
                try {
                    val next = page(state, state.entries.size)
                    publish(state.entries + next, next.size == pageSize)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    _uiState.value = _uiState.value.copy(error = HistoryError.LOAD)
                }
            }
    }

    fun deleteEntry(
        id: Long,
        deleteRemote: Boolean,
    ) = deleteEntries(setOf(id), deleteRemote)

    fun deleteEntries(
        ids: Set<Long>,
        deleteRemote: Boolean,
    ) {
        if (ids.isEmpty() || _uiState.value.isDeleting) return
        pageJob?.cancel()
        selectionJob?.cancel()
        loadJob?.cancel()
        _uiState.value = _uiState.value.copy(isDeleting = true, isLoading = false, error = null)
        val result = applicationScope.async(Dispatchers.Main.immediate) { delete(ids, deleteRemote) }
        viewModelScope.launch {
            try {
                result.await()
                failedDeletion = null
                _uiState.value = _uiState.value.copy(selectedIds = emptySet())
                val state = _uiState.value
                val limit = maxOf(pageSize, state.entries.size)
                try {
                    val refreshed = readPage(limit, 0, state.query, state.sortMode, state.sortDirection)
                    publish(refreshed, refreshed.size == limit)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    publish(state.entries.filterNot { it.id in ids }, state.hasMore)
                    _uiState.value = _uiState.value.copy(error = HistoryError.LOAD)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                failedDeletion = ids to deleteRemote
                _uiState.value = _uiState.value.copy(error = HistoryError.DELETE)
            } finally {
                _uiState.value = _uiState.value.copy(isDeleting = false)
            }
        }
    }

    fun updateEntry(
        id: Long,
        title: String,
        artists: String,
        album: String?,
    ) {
        val result = applicationScope.async(Dispatchers.Main.immediate) { update(id, title, artists, album) }
        viewModelScope.launch {
            result.await()
            reload()
        }
    }
}
