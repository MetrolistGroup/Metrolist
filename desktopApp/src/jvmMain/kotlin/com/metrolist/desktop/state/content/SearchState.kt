/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.desktop.state.content

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.metrolist.desktop.state.app.AppState
import com.metrolist.desktop.state.app.DesktopNavigation
import com.metrolist.desktop.state.app.UiState
import com.metrolist.desktop.state.playback.PlayerState
import com.metrolist.innertubex.utils.YouTubeUrlParser
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.search_history_removal_failed
import com.metrolist.shared.model.SearchFilter
import com.metrolist.shared.ui.navigation.Screen
import com.metrolist.shared.viewmodels.SearchViewModel
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString

object SearchState {
    private val controller by lazy {
        SearchViewModel(AppState.scope)
    }
    var uiState by mutableStateOf(controller.uiState.value)
        private set

    val selectedSearchFilter get() = uiState.selectedFilter
    val currentSearchQuery get() = uiState.query
    val searchHistory get() = uiState.searchHistory
    val searchSuggestions get() = uiState.searchSuggestions

    init {
        AppState.scope.launch {
            controller.uiState.collect { state ->
                val failed = state.historyMutationFailures > uiState.historyMutationFailures
                uiState = state
                if (failed) {
                    AppState.scope.launch {
                        AppState.snackbarHostState.showSnackbar(getString(Res.string.search_history_removal_failed))
                    }
                }
            }
        }
    }

    fun onQueryChange(query: String) = controller.onQueryChange(query)

    fun removeSearchQuery(query: String) = controller.removeSearchHistoryItem(query)

    fun clearSearchHistory() = controller.clearSearchHistory()

    fun refreshSearchHistory() = controller.refreshSearchHistory()

    fun search(query: String): Boolean {
        val normalized = query.trim()
        if (normalized.isBlank()) return false
        val link = resolveDesktopSearchLink(normalized)
        if (link != null) {
            UiState.showSettings = false
            UiState.showSignIn = false
            UiState.showSwitchChannel = false
            PlaylistAlbumState.selectedLocalPlaylist = null
            controller.resetSearch()
            when (link) {
                is DesktopSearchLink.Podcast -> {
                    PodcastState.fetch(link.id)
                }

                is DesktopSearchLink.Media -> {
                    when (val destination = link.destination) {
                        is YouTubeUrlParser.ParsedUrl.Video -> PlayerState.playFromId(destination.id)
                        is YouTubeUrlParser.ParsedUrl.Playlist -> PlaylistAlbumState.fetchPlaylistData(destination.id)
                        is YouTubeUrlParser.ParsedUrl.Album -> PlaylistAlbumState.fetchAlbumData(destination.id)
                        is YouTubeUrlParser.ParsedUrl.Artist -> ArtistState.fetchArtistData(destination.id)
                    }
                }
            }
            return true
        }
        DesktopNavigation.navigate(Screen.Search)
        PodcastState.clear()
        ArtistState.selectedArtistId = null
        PlaylistAlbumState.selectedPlaylistId = null
        PlaylistAlbumState.selectedAlbumId = null
        UiState.showSettings = false
        UiState.showSignIn = false
        UiState.showSwitchChannel = false
        PlaylistAlbumState.selectedLocalPlaylist = null
        controller.searchQuery(normalized)
        return false
    }

    fun updateSearchFilter(filter: SearchFilter?) {
        if (filter == null) controller.clearFilter() else controller.searchFiltered(filter)
    }

    fun loadMoreFilteredResults() = controller.loadMoreFilteredResults()

    fun retryLoadMoreFilteredResults() = controller.retryLoadMoreFilteredResults()

    fun reset() = controller.resetSearch()

    fun close() = controller.close()
}
