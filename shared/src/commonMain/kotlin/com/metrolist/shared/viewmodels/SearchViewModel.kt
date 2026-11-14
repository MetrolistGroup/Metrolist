package com.metrolist.shared.viewmodels

import com.metrolist.innertubex.utils.YouTubeUrlParser
import com.metrolist.shared.api.YouTubeRepository
import com.metrolist.shared.model.SearchFilter
import com.metrolist.shared.model.SearchResultPage
import com.metrolist.shared.model.SearchSuggestions
import com.metrolist.shared.model.SearchSummaryPage
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.YTItem
import com.metrolist.shared.model.filterAvailableSearchFilters
import com.metrolist.shared.settings.BlockedContent
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.state.GlobalYouTubeRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.milliseconds

class SearchViewModel(
    private val scope: CoroutineScope,
    private val repository: YouTubeRepository = GlobalYouTubeRepository.instance,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private var sessionJob: Job? = null
    private var suggestionsJob: Job? = null
    private var searchJob: Job? = null
    private var filterJob: Job? = null
    private var loadMoreJob: Job? = null
    private var historySyncJob: Job? = null
    private var historyMutationJob: Job? = null
    private val historyMutationMutex = Mutex()
    private val searchHistoryRevisions = mutableMapOf<String, Long>()
    private val removedRemoteSearchQueries = mutableSetOf<String>()
    private var nextSearchHistoryRevision = 0L
    private var loadingContinuation: String? = null
    private var loadMoreGeneration = 0
    private var unfilteredRecentSearchItems = emptyList<YTItem>()
    private var localSearchHistory = emptyList<String>()
    private var historySession = repository.sessionSnapshot()

    init {
        sessionJob =
            scope.launch {
                repository.sessionFlow.collect { resetRemoteHistoryIfSessionChanged() }
            }
        scope.launch {
            BlockedContent.version.drop(1).collect {
                _uiState.update { it.copy(recentSearchItems = BlockedContent.filterItems(unfilteredRecentSearchItems)) }
                val state = _uiState.value
                val updatedState =
                    state.clearUnavailableSearchFilter(
                        hideVideoSongs = Settings.shared.hideVideoSongs,
                        hidePodcasts = Settings.shared.hidePodcasts,
                    )
                if (updatedState.selectedFilter != state.selectedFilter) {
                    filterJob?.cancel()
                    cancelLoadMore()
                    _uiState.value = updatedState
                    search(recordSearchHistory = false)
                } else {
                    _uiState.update {
                        it.copy(
                            searchSummary = it.unfilteredSearchSummary?.withoutBlockedSongs(),
                            filteredResults = it.unfilteredResults?.withoutBlockedItems(),
                        )
                    }
                }
            }
        }
        val saved = Settings.shared.searchHistory
        val history = SearchHistoryCodec.decode(saved)
        localSearchHistory = history
        if (history.isNotEmpty()) {
            _uiState.update { it.copy(searchHistory = history) }
        }
        if (saved.isNotBlank() && !SearchHistoryCodec.isJson(saved)) persistSearchHistory(history)
        unfilteredRecentSearchItems = loadRecentPlayedSearchSongs().orEmpty()
        _uiState.update { it.copy(recentSearchItems = BlockedContent.filterItems(unfilteredRecentSearchItems)) }
    }

    private fun persistSearchHistory(history: List<String>) {
        localSearchHistory = history
        Settings.shared.searchHistory = SearchHistoryCodec.encode(history)
    }

    fun onQueryChange(newQuery: String) {
        suggestionsJob?.cancel()
        searchJob?.cancel()
        filterJob?.cancel()
        cancelLoadMore()
        _uiState.update { queryChangeState(it, newQuery) }
        if (newQuery.length >= 2) {
            suggestionsJob =
                scope.launch {
                    delay(300L.milliseconds)
                    val result = repository.getSearchSuggestions(newQuery) ?: return@launch
                    if (isCurrentSearchRequest(_uiState.value, newQuery, submitted = false)) {
                        _uiState.update { state ->
                            state.copy(
                                searchSuggestions =
                                    result.copy(
                                        recommendedItems = BlockedContent.filterItems(result.recommendedItems),
                                    ),
                            )
                        }
                    }
                }
        }
    }

    fun searchQuery(newQuery: String) {
        if (newQuery.isNotBlank()) {
            suggestionsJob?.cancel()
            _uiState.update { it.copy(query = newQuery, isQuerySubmitted = true, searchSuggestions = null) }
            search(recordSearchHistory = !Settings.shared.pauseSearchHistory)
        } else if (newQuery.isEmpty()) {
            clearResults()
        }
    }

    fun search(recordSearchHistory: Boolean = false) {
        val currentQuery = _uiState.value.query
        if (currentQuery.isEmpty()) return
        if (recordSearchHistory) addSearchHistory(currentQuery)
        val pendingHistoryMutation = historyMutationJob.takeIf { recordSearchHistory }

        searchJob?.cancel()
        filterJob?.cancel()
        cancelLoadMore()
        searchJob =
            scope.launch {
                pendingHistoryMutation?.join()
                _uiState.update {
                    it.copy(
                        isSearching = true,
                        searchFailed = false,
                        hasSearched = true,
                        selectedFilter = null,
                        unfilteredResults = null,
                        filteredResults = null,
                    )
                }

                try {
                    val summary =
                        repository.getSearchSummary(
                            currentQuery,
                            recordSearchHistory = recordSearchHistory,
                        )
                    if (isCurrentSearchRequest(_uiState.value, currentQuery, filter = null)) {
                        _uiState.update { state ->
                            state.copy(
                                unfilteredSearchSummary = summary,
                                searchSummary = summary.withoutBlockedSongs(),
                                searchFailed = summary.onlineSearchFailed,
                                isSearching = false,
                            )
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    if (isCurrentSearchRequest(_uiState.value, currentQuery, filter = null)) {
                        clearResults()
                        _uiState.update { it.copy(isSearching = false, searchFailed = true) }
                    }
                }
            }
    }

    fun searchFiltered(filter: SearchFilter) {
        val currentQuery = _uiState.value.query
        if (currentQuery.isEmpty()) return
        val recordSearchHistory = !Settings.shared.pauseSearchHistory
        if (recordSearchHistory) addSearchHistory(currentQuery)
        val pendingHistoryMutation = historyMutationJob.takeIf { recordSearchHistory }

        filterJob?.cancel()
        searchJob?.cancel()
        cancelLoadMore()
        filterJob =
            scope.launch {
                pendingHistoryMutation?.join()
                _uiState.update { it.copy(isSearching = true, selectedFilter = filter, searchFailed = false) }

                try {
                    val result =
                        repository.searchFiltered(
                            currentQuery,
                            filter,
                            recordSearchHistory = recordSearchHistory,
                        )
                    if (isCurrentSearchRequest(_uiState.value, currentQuery, filter)) {
                        _uiState.update { state ->
                            state.copy(
                                unfilteredResults = result,
                                filteredResults = result.withoutBlockedItems(),
                                searchFailed = result.onlineSearchFailed,
                                isSearching = false,
                            )
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    if (isCurrentSearchRequest(_uiState.value, currentQuery, filter)) {
                        val emptyResult = SearchResultPage(emptyList(), null)
                        _uiState.update {
                            it.copy(
                                unfilteredResults = emptyResult,
                                filteredResults = emptyResult,
                                searchFailed = true,
                                isSearching = false,
                            )
                        }
                    }
                }
            }
    }

    fun retryLoadMoreFilteredResults() {
        _uiState.update { it.copy(loadMoreFailed = false) }
        loadMoreFilteredResults()
    }

    fun loadMoreFilteredResults() {
        val state = _uiState.value
        val query = state.query
        val filter = state.selectedFilter ?: return
        val continuation = state.filteredResults?.continuation ?: return
        val submitted = state.isQuerySubmitted
        if (!canLoadMoreFilteredResults(state, loadingContinuation)) return
        val recordSearchHistory = !Settings.shared.pauseSearchHistory
        val pendingHistoryMutation = historyMutationJob.takeIf { recordSearchHistory }

        loadingContinuation = continuation
        val generation = ++loadMoreGeneration
        loadMoreJob =
            scope.launch {
                pendingHistoryMutation?.join()
                _uiState.update { it.copy(isLoadingMore = true, loadMoreFailed = false) }
                try {
                    val nextPage =
                        repository.searchFiltered(
                            query,
                            filter,
                            continuation,
                            recordSearchHistory = recordSearchHistory,
                        )
                    _uiState.update { current ->
                        if (!isCurrentSearchRequest(current, query, filter, submitted) ||
                            current.filteredResults?.continuation != continuation
                        ) {
                            current
                        } else {
                            val merged =
                                appendSearchResultPage(
                                    current.unfilteredResults ?: current.filteredResults,
                                    nextPage,
                                    continuation,
                                )
                            current.copy(
                                unfilteredResults = merged,
                                filteredResults = merged.withoutBlockedItems(),
                            )
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    if (loadMoreGeneration == generation) {
                        _uiState.update { current ->
                            if (isCurrentSearchRequest(current, query, filter, submitted)) {
                                current.copy(loadMoreFailed = true)
                            } else {
                                current
                            }
                        }
                    }
                } finally {
                    if (loadMoreGeneration == generation) {
                        loadingContinuation = null
                        _uiState.update { it.copy(isLoadingMore = false) }
                    }
                }
            }
    }

    private fun cancelLoadMore() {
        loadMoreJob?.cancel()
        loadMoreJob = null
        loadMoreGeneration++
        loadingContinuation = null
        _uiState.update { it.copy(isLoadingMore = false, loadMoreFailed = false) }
    }

    fun clearFilter() {
        cancelLoadMore()
        _uiState.update { it.copy(selectedFilter = null, unfilteredResults = null, filteredResults = null) }
        if (_uiState.value.query.isNotEmpty()) {
            search(recordSearchHistory = !Settings.shared.pauseSearchHistory)
        }
    }

    fun addSearchHistory(query: String) {
        if (!shouldAddSearchHistory(query, Settings.shared.pauseSearchHistory)) return
        historySyncJob?.cancel()
        removedRemoteSearchQueries.remove(query.lowercase())
        searchHistoryRevisions[query] = ++nextSearchHistoryRevision
        persistSearchHistory((listOf(query) + localSearchHistory.filterNot { it == query }).take(10))
        _uiState.update { state ->
            val currentHistory = state.searchHistory.toMutableList()
            currentHistory.remove(query)
            currentHistory.add(0, query)
            if (currentHistory.size > 10) {
                currentHistory.removeAt(currentHistory.size - 1)
            }
            state.copy(searchHistory = currentHistory)
        }
    }

    fun searchFromHistory(query: String) {
        suggestionsJob?.cancel()
        _uiState.update { it.copy(query = query, isQuerySubmitted = true, searchSuggestions = null) }
        search(recordSearchHistory = !Settings.shared.pauseSearchHistory)
    }

    fun refreshSearchHistory() {
        resetRemoteHistoryIfSessionChanged()
        historySyncJob?.cancel()
        val session = repository.sessionSnapshot()
        val pendingMutation = historyMutationJob
        historySyncJob =
            scope.launch {
                pendingMutation?.join()
                val remoteHistory = repository.getSearchHistory() ?: return@launch
                if (repository.sessionSnapshot() != session) return@launch
                val visibleRemoteHistory = remoteHistory.filterNot { it.query.lowercase() in removedRemoteSearchQueries }
                removedRemoteSearchQueries.retainAll(remoteHistory.map { it.query.lowercase() }.toSet())
                _uiState.update { state ->
                    val syncedHistory = (visibleRemoteHistory.map { it.query } + localSearchHistory).distinctBy(String::lowercase).take(10)
                    state.copy(
                        searchHistory = syncedHistory,
                        remoteSearchHistoryFeedbackTokens =
                            visibleRemoteHistory
                                .mapNotNull { entry ->
                                    entry.feedbackToken?.let { entry.query to it }
                                }.toMap(),
                    )
                }
            }
    }

    private fun resetRemoteHistoryIfSessionChanged() {
        val session = repository.sessionSnapshot()
        if (session.cookie == historySession.cookie && session.dataSyncId == historySession.dataSyncId &&
            session.authUser == historySession.authUser
        ) {
            return
        }
        historySession = session
        historySyncJob?.cancel()
        historyMutationJob?.cancel()
        removedRemoteSearchQueries.clear()
        _uiState.update {
            it.copy(searchHistory = localSearchHistory, remoteSearchHistoryFeedbackTokens = emptyMap())
        }
    }

    fun removeSearchHistoryItem(query: String) {
        resetRemoteHistoryIfSessionChanged()
        historySyncJob?.cancel()
        val feedbackToken = _uiState.value.remoteSearchHistoryFeedbackTokens[query]
        if (feedbackToken == null && !repository.canManagePlaylists()) {
            removeLocalSearchHistory(setOf(query))
        } else {
            removeRemoteSearchHistory(mapOf(query to feedbackToken))
        }
    }

    fun clearSearchHistory() {
        resetRemoteHistoryIfSessionChanged()
        historySyncJob?.cancel()
        val session = repository.sessionSnapshot()
        val state = _uiState.value
        val remoteTokens = state.remoteSearchHistoryFeedbackTokens.values.toList()
        val revisions =
            (state.searchHistory + localSearchHistory).distinct().associateWith { query ->
                searchHistoryRevisions.getOrPut(query) { ++nextSearchHistoryRevision }
            }
        historyMutationJob =
            scope.launch {
                historyMutationMutex.withLock {
                    try {
                        if (repository.sessionSnapshot() != session) return@withLock
                        repository.clearSearchHistory(remoteTokens)
                        if (repository.sessionSnapshot() != session) return@withLock
                        removeLocalSearchHistory(revisions.filter { (query, revision) -> searchHistoryRevisions[query] == revision }.keys)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        _uiState.update { it.copy(historyMutationFailures = it.historyMutationFailures + 1) }
                    }
                }
            }
    }

    private fun removeLocalSearchHistory(queries: Set<String>) {
        if (queries.isEmpty()) return
        queries.forEach(searchHistoryRevisions::remove)
        _uiState.update { state ->
            val updated = state.searchHistory.filterNot(queries::contains)
            persistSearchHistory(localSearchHistory.filterNot(queries::contains))
            state.copy(
                searchHistory = updated,
                remoteSearchHistoryFeedbackTokens = state.remoteSearchHistoryFeedbackTokens - queries,
            )
        }
    }

    private fun removeRemoteSearchHistory(items: Map<String, String?>) {
        val session = repository.sessionSnapshot()
        val versionedItems =
            items.mapValues { (query, feedbackToken) ->
                feedbackToken to searchHistoryRevisions.getOrPut(query) { ++nextSearchHistoryRevision }
            }
        historyMutationJob =
            scope.launch {
                historyMutationMutex.withLock {
                    try {
                        val remoteHistory =
                            if (items.values.any { it == null }) {
                                checkNotNull(repository.getSearchHistory(session)) { "Could not load search history" }
                            } else {
                                emptyList()
                            }
                        val currentItems = versionedItems.filter { (query, entry) -> searchHistoryRevisions[query] == entry.second }
                        val tokens =
                            currentItems.mapNotNull { (query, entry) ->
                                entry.first ?: remoteHistory.firstOrNull { it.query.equals(query, ignoreCase = true) }?.let {
                                    checkNotNull(it.feedbackToken) { "Search history entry has no removal token" }
                                }
                            }
                        repository.removeSearchHistoryItems(
                            tokens,
                            session,
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        _uiState.update { it.copy(historyMutationFailures = it.historyMutationFailures + 1) }
                        return@withLock
                    }
                    val unchangedQueries =
                        versionedItems
                            .filter { (query, tokenAndRevision) ->
                                searchHistoryRevisions[query] == tokenAndRevision.second
                            }.keys
                    removeLocalSearchHistory(unchangedQueries)
                    if (unchangedQueries.isNotEmpty()) {
                        removedRemoteSearchQueries.addAll(unchangedQueries.map(String::lowercase))
                        refreshSearchHistory()
                    }
                }
            }
    }

    fun removeRecentPlayedSearchSong(song: YTItem) {
        updateRecentPlayedSearchSongs(unfilteredRecentSearchItems.filterNot { it.id == song.id })
    }

    fun clearRecentPlayedSearchSongs() {
        updateRecentPlayedSearchSongs(emptyList())
    }

    fun addPlayedSearchSong(song: SongItem) {
        val updated = listOf(song) + unfilteredRecentSearchItems.filterNot { it.id == song.id }
        updateRecentPlayedSearchSongs(updated.take(12))
    }

    private fun updateRecentPlayedSearchSongs(items: List<YTItem>) {
        unfilteredRecentSearchItems = items
        saveRecentPlayedSearchSongs(items)
        _uiState.update { it.copy(recentSearchItems = BlockedContent.filterItems(items)) }
    }

    private fun loadRecentPlayedSearchSongs(): List<YTItem>? {
        val saved = Settings.shared.recentPlayedSearchSongsJson
        if (saved.isBlank()) return null
        return try {
            json.decodeFromString<PersistedSearchItems>(saved).items
        } catch (_: Exception) {
            null
        }
    }

    private fun saveRecentPlayedSearchSongs(items: List<YTItem>) {
        try {
            Settings.shared.recentPlayedSearchSongsJson =
                if (items.isEmpty()) {
                    ""
                } else {
                    json.encodeToString(PersistedSearchItems(items))
                }
        } catch (_: Exception) {
        }
    }

    private fun SearchSummaryPage.withoutBlockedSongs(): SearchSummaryPage =
        copy(
            summaries =
                summaries.mapNotNull { summary ->
                    val items = BlockedContent.filterItems(summary.items)
                    if (items.isEmpty()) null else summary.copy(items = items)
                },
        )

    private fun SearchResultPage.withoutBlockedItems(): SearchResultPage = copy(items = BlockedContent.filterItems(items))

    @Serializable
    private data class PersistedSearchItems(
        val items: List<YTItem>,
    )

    fun resetSearch() {
        suggestionsJob?.cancel()
        searchJob?.cancel()
        filterJob?.cancel()
        cancelLoadMore()
        _uiState.update {
            it.copy(
                query = "",
                isQuerySubmitted = false,
                hasSearched = false,
                searchFailed = false,
                searchSummary = null,
                unfilteredSearchSummary = null,
                filteredResults = null,
                unfilteredResults = null,
                selectedFilter = null,
                searchSuggestions = null,
                loadMoreFailed = false,
                isSearching = false,
            )
        }
    }

    private fun clearResults() {
        _uiState.update {
            it.copy(
                searchSummary = null,
                unfilteredSearchSummary = null,
                filteredResults = null,
                unfilteredResults = null,
                selectedFilter = null,
            )
        }
    }

    fun close() {
        sessionJob?.cancel()
        suggestionsJob?.cancel()
        searchJob?.cancel()
        filterJob?.cancel()
        loadMoreJob?.cancel()
        historySyncJob?.cancel()
        historyMutationJob?.cancel()
    }

    data class SearchUiState(
        val query: String = "",
        val isSearching: Boolean = false,
        val isQuerySubmitted: Boolean = false,
        val unfilteredSearchSummary: SearchSummaryPage? = null,
        val searchSummary: SearchSummaryPage? = null,
        val unfilteredResults: SearchResultPage? = null,
        val filteredResults: SearchResultPage? = null,
        val selectedFilter: SearchFilter? = null,
        val searchHistory: List<String> = emptyList(),
        val historyMutationFailures: Int = 0,
        val remoteSearchHistoryFeedbackTokens: Map<String, String> = emptyMap(),
        val recentSearchItems: List<YTItem> = emptyList(),
        val hasSearched: Boolean = false,
        val searchSuggestions: SearchSuggestions? = null,
        val isLoadingMore: Boolean = false,
        val searchFailed: Boolean = false,
        val loadMoreFailed: Boolean = false,
    )
}

object SearchHistoryCodec {
    private val json = Json

    fun isJson(value: String): Boolean = value.trimStart().startsWith("[")

    fun decode(value: String): List<String> {
        if (value.isBlank()) return emptyList()
        if (isJson(value)) {
            return try {
                json.decodeFromString<List<String>>(value).filter { it.isNotBlank() }
            } catch (_: Exception) {
                emptyList()
            }
        }
        val delimiter = if ('|' in value) '|' else ','
        return value.split(delimiter).map(String::trim).filter(String::isNotBlank)
    }

    fun encode(history: List<String>): String = json.encodeToString(history)
}

enum class SearchPresentation {
    IDLE,
    SUGGESTIONS,
    RESULTS,
}

fun searchPresentation(
    query: String,
    isSubmitted: Boolean,
): SearchPresentation =
    when {
        isSubmitted -> SearchPresentation.RESULTS
        query.isNotEmpty() -> SearchPresentation.SUGGESTIONS
        else -> SearchPresentation.IDLE
    }

fun isCurrentSearchRequest(
    state: SearchViewModel.SearchUiState,
    query: String,
    filter: SearchFilter? = state.selectedFilter,
    submitted: Boolean = state.isQuerySubmitted,
): Boolean =
    state.query == query &&
        state.selectedFilter == filter &&
        state.isQuerySubmitted == submitted

fun queryChangeState(
    state: SearchViewModel.SearchUiState,
    query: String,
): SearchViewModel.SearchUiState =
    state.copy(
        query = query,
        isQuerySubmitted = false,
        isSearching = false,
        searchSuggestions = state.searchSuggestions.takeIf { query.length >= 2 },
        searchFailed = false,
        loadMoreFailed = false,
    )

internal fun SearchViewModel.SearchUiState.clearUnavailableSearchFilter(
    hideVideoSongs: Boolean,
    hidePodcasts: Boolean,
): SearchViewModel.SearchUiState =
    if (
        selectedFilter != null &&
        selectedFilter !in
        SearchFilter.entries.toList().filterAvailableSearchFilters(hideVideoSongs, hidePodcasts)
    ) {
        copy(selectedFilter = null, unfilteredResults = null, filteredResults = null)
    } else {
        this
    }

internal fun appendSearchResultPage(
    current: SearchResultPage?,
    next: SearchResultPage,
    requestedContinuation: String,
): SearchResultPage {
    val items = (current?.items.orEmpty() + next.items).distinctBy(YTItem::id)
    return SearchResultPage(
        items = items,
        continuation = next.continuation?.takeUnless { it == requestedContinuation },
    )
}

internal fun canLoadMoreFilteredResults(
    state: SearchViewModel.SearchUiState,
    loadingContinuation: String?,
): Boolean =
    state.selectedFilter != null &&
        state.filteredResults?.continuation != null &&
        !state.isSearching &&
        !state.isLoadingMore &&
        !state.loadMoreFailed &&
        loadingContinuation == null

internal fun shouldAddSearchHistory(
    query: String,
    paused: Boolean,
): Boolean = !paused && !YouTubeUrlParser.isYouTubeUrl(query)
