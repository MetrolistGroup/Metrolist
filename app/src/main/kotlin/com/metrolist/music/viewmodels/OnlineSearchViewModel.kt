/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.viewmodels

import android.content.Context
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.YTItem
import com.metrolist.innertube.models.filterExplicit
import com.metrolist.innertube.models.filterVideoSongs
import com.metrolist.innertube.models.filterYoutubeShorts
import com.metrolist.innertube.pages.SearchSummaryPage
import com.metrolist.music.constants.HideExplicitKey
import com.metrolist.music.constants.HideVideoSongsKey
import com.metrolist.music.constants.HideYoutubeShortsKey
import com.metrolist.music.constants.PauseSearchHistoryKey
import com.metrolist.music.models.ItemsPage
import com.metrolist.music.utils.SearchRoutes
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.get
import com.metrolist.music.utils.reportException
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OnlineSearchViewModel
@Inject
constructor(
    @ApplicationContext val context: Context,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    val query = SearchRoutes.decodeQuery(savedStateHandle.get<String>("query").orEmpty())
    val filter = MutableStateFlow<YouTube.SearchFilter?>(null)
    private var hideVideoSongs by mutableStateOf<Boolean?>(null)
    private var _summaryPage by mutableStateOf<SearchSummaryPage?>(null)
    private val _viewStateMap = mutableStateMapOf<String, ItemsPage?>()

    val summaryPage by derivedStateOf {
        hideVideoSongs?.let { _summaryPage?.filterVideoSongs(it) }
    }
    val viewStateMap by derivedStateOf {
        hideVideoSongs?.let { hide ->
            _viewStateMap.mapValues { (_, page) ->
                page?.copy(items = page.items.filterVideoSongs(hide))
            }
        }.orEmpty()
    }

    private suspend fun resolveSearchMetadata(items: List<YTItem>): List<YTItem> =
        coroutineScope {
            val knownDurations =
                items
                    .filterIsInstance<SongItem>()
                    .mapNotNull { song -> song.duration?.let { song.id to it } }
                    .toMap()
            val missingDurationIds =
                items
                    .filterIsInstance<SongItem>()
                    .filter { it.duration == null && it.id !in knownDurations }
                    .map { it.id }
                    .distinct()
            val resolvedArtists = async { YouTube.resolveArtistIds(items) }
            val fetchedMetadata =
                async {
                    if (missingDurationIds.isEmpty()) {
                        emptyMap()
                    } else {
                        missingDurationIds.chunked(YouTube.MAX_GET_QUEUE_SIZE)
                            .flatMap { ids -> YouTube.queue(videoIds = ids).getOrDefault(emptyList()) }
                            .associateBy { it.id }
                    }
                }
            val metadata = fetchedMetadata.await()

            resolvedArtists.await().map { item ->
                if (item is SongItem) {
                    item.copy(
                        duration = item.duration ?: knownDurations[item.id] ?: metadata[item.id]?.duration,
                        musicVideoType = item.musicVideoType ?: metadata[item.id]?.musicVideoType,
                    )
                } else {
                    item
                }
            }
        }

    private suspend fun loadSummaryPage() {
        if (_summaryPage == null) {
            YouTube
                .searchSummary(query, recordSearchHistory = !context.dataStore.get(PauseSearchHistoryKey, false))
                .onSuccess { page ->
                    val resolvedItems = resolveSearchMetadata(page.summaries.flatMap { it.items })
                    var offset = 0
                    val resolvedSummaries =
                        page.summaries.map { summary ->
                            val nextOffset = offset + summary.items.size
                            val resolvedSummary = summary.copy(items = resolvedItems.subList(offset, nextOffset))
                            offset = nextOffset
                            resolvedSummary
                        }
                    val resolvedPage = page.copy(summaries = resolvedSummaries)
                    val hideExplicit = context.dataStore.get(HideExplicitKey, false)
                    val hideYoutubeShorts = context.dataStore.get(HideYoutubeShortsKey, false)
                    _summaryPage =
                        resolvedPage
                            .filterExplicit(hideExplicit)
                            .filterYoutubeShorts(hideYoutubeShorts)
                }.onFailure {
                    reportException(it)
                }
        }
    }

    init {
        viewModelScope.launch {
            context.dataStore.data
                .map { it[HideVideoSongsKey] ?: false }
                .distinctUntilChanged()
                .collect { hideVideoSongs = it }
        }
        viewModelScope.launch {
            filter.collect { filter ->
                if (filter == null) {
                    loadSummaryPage()
                } else if (filter == YouTube.SearchFilter.FILTER_EPISODE) {
                    // The FILTER_EPISODE API returns episodes in a format that differs from the
                    // summary search: playlistItemData is absent and the subtitle structure is
                    // different, making reliable isEpisode detection fail for many items.
                    // Reuse the "Episodes" section from the summary page instead — it is already
                    // parsed correctly by fromMusicResponsiveListItemRenderer and guaranteed to
                    // show the same results as the episodes section in the "All" filter.
                    if (_viewStateMap[filter.value] == null) {
                        loadSummaryPage()
                        _summaryPage?.let { page ->
                            val episodes = page.summaries
                                .firstOrNull { it.title == "Episodes" }
                                ?.items
                                .orEmpty()
                            _viewStateMap[filter.value] = ItemsPage(episodes, null)
                        }
                    }
                } else {
                    if (_viewStateMap[filter.value] == null) {
                        YouTube
                            .search(query, filter, recordSearchHistory = !context.dataStore.get(PauseSearchHistoryKey, false))
                            .onSuccess { result ->
                                val resolvedItems = resolveSearchMetadata(result.items)
                                val hideExplicit = context.dataStore.get(HideExplicitKey, false)
                                val hideYoutubeShorts = context.dataStore.get(HideYoutubeShortsKey, false)
                                _viewStateMap[filter.value] =
                                    ItemsPage(
                                        resolvedItems
                                            .distinctBy { it.id }
                                            .filterExplicit(hideExplicit)
                                            .filterYoutubeShorts(hideYoutubeShorts),
                                        result.continuation,
                                    )
                            }.onFailure {
                                reportException(it)
                            }
                    }
                }
            }
        }
    }

    fun loadMore() {
        val currentFilter = filter.value
        val filterValue = currentFilter?.value ?: return
        viewModelScope.launch {
            val viewState = _viewStateMap[filterValue] ?: return@launch
            val continuation = viewState.continuation ?: return@launch
            val searchResult =
                YouTube.searchContinuation(
                    continuation,
                    recordSearchHistory = !context.dataStore.get(PauseSearchHistoryKey, false),
                ).getOrNull() ?: return@launch
            val resolvedItems = resolveSearchMetadata(searchResult.items)
            val hideExplicit = context.dataStore.get(HideExplicitKey, false)
            val hideYoutubeShorts = context.dataStore.get(HideYoutubeShortsKey, false)
            val newItems = resolvedItems
                .filterExplicit(hideExplicit)
                .filterYoutubeShorts(hideYoutubeShorts)
            _viewStateMap[filterValue] = ItemsPage(
                (viewState.items + newItems).distinctBy { it.id },
                searchResult.continuation
            )
        }
    }
}
