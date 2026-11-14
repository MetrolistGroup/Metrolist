/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.desktop.state.content

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.metrolist.desktop.state.app.AppState
import com.metrolist.desktop.utils.DesktopImageLoader
import com.metrolist.shared.api.musicvideo.MusicVideoResolver
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.model.BrowseItem
import com.metrolist.shared.model.Chip
import com.metrolist.shared.model.HomePageData
import com.metrolist.shared.model.HomeSection
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.model.PodcastItem
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.YTItem
import com.metrolist.shared.model.isDesktopHomeDiscoverySection
import com.metrolist.shared.model.mergeAndDeduplicateHomeSections
import com.metrolist.shared.viewmodels.HomeViewModel
import com.metrolist.shared.viewmodels.uncategorizedHomeSections
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext
import com.metrolist.shared.model.refetchSongMetadata as refetchSongMetadataFromYouTube

object HomeState {
    private const val MAX_SCROLL_STATES = 32
    private val database: MusicDatabase by lazy { GlobalContext.get().get() }
    private val musicVideoResolver: MusicVideoResolver by lazy { GlobalContext.get().get() }
    private val homeViewModel = HomeViewModel(database, AppState.scope, likedSongsName = "Liked Songs")
    var uiState by mutableStateOf(homeViewModel.uiState.value.forDesktopDiscovery())
        private set
    var isHomeLoading by mutableStateOf(false)
    var isLoadingMore by mutableStateOf(false)
    var homeError by mutableStateOf<String?>(null)
    var homePageData by mutableStateOf(HomePageData())
    var selectedChip by mutableStateOf<Chip?>(null)

    private val listScrollPositions = boundedStateCache<Pair<Int, Int>>()

    init {
        AppState.scope.launch {
            homeViewModel.uiState.collect { state ->
                uiState = state.forDesktopDiscovery()
                homePageData =
                    HomePageData(
                        chips = uiState.chips,
                        sections = uiState.data.orEmpty(),
                        continuation = uiState.continuation,
                    )
                selectedChip = uiState.selectedChip
                isHomeLoading = state.isLoading || state.isRefreshing
                isLoadingMore = state.isLoadingMore
                homeError = state.error
            }
        }
    }

    fun saveListScroll(
        key: String,
        index: Int,
        offset: Int,
    ) {
        listScrollPositions[key] = index to offset
    }

    fun restoreListScroll(key: String): Pair<Int, Int> = listScrollPositions.getOrPut(key) { 0 to 0 }

    @Composable
    fun rememberListState(key: String): LazyListState {
        val (savedIndex, savedOffset) = restoreListScroll(key)
        val state = remember(key) { LazyListState(savedIndex, savedOffset) }

        LaunchedEffect(state) {
            snapshotFlow { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }
                .collect { (index, offset) ->
                    saveListScroll(key, index, offset)
                }
        }

        DisposableEffect(key) {
            onDispose {
                saveListScroll(key, state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset)
            }
        }

        return state
    }

    private fun <T> boundedStateCache() =
        object : LinkedHashMap<String, T>(MAX_SCROLL_STATES, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, T>?): Boolean = size > MAX_SCROLL_STATES
        }

    fun fetchHomeData(
        params: String? = null,
        onComplete: () -> Unit = {},
    ) {
        val chip = params?.let { value -> uiState.chips.firstOrNull { it.params == value } }
        if (chip != null) {
            homeViewModel.toggleChip(chip)
            onComplete()
        } else {
            homeViewModel.refresh(onComplete)
        }
    }

    fun loadMoreSections() = homeViewModel.loadMoreSections()

    fun refresh() = homeViewModel.refresh {}

    fun toggleChip(chip: Chip?) {
        homeViewModel.toggleChip(chip)
    }

    fun pinSpeedDialItem(item: YTItem) = homeViewModel.pinSpeedDialItem(item)

    fun unpinSpeedDialItem(id: String) = homeViewModel.unpinSpeedDialItem(id)

    fun moveSpeedDialItemEarlier(id: String) = homeViewModel.moveSpeedDialItemEarlier(id)

    fun moveSpeedDialItemLater(id: String) = homeViewModel.moveSpeedDialItemLater(id)

    fun setSpeedDialSurprisePinned(pinned: Boolean) = homeViewModel.setSpeedDialSurprisePinned(pinned)

    fun refetchSongMetadata(song: SongItem) {
        AppState.scope.launch {
            val refreshed = refetchSongMetadataFromYouTube(database, musicVideoResolver, song) ?: return@launch
            DesktopImageLoader.evictArtwork(song.thumbnail)
            DesktopImageLoader.evictArtwork(refreshed.thumbnail)
            homeViewModel.updateSongMetadata(refreshed)
        }
    }

    fun setLikedSongsName(name: String) {
        homeViewModel.likedSongsName = name
    }
}

internal fun HomeViewModel.HomeUiState.forDesktopDiscovery(): HomeViewModel.HomeUiState {
    val userPlaylistIds = userPlaylists.mapTo(mutableSetOf()) { it.id.removePrefix("VL") }

    fun filterDiscovery(sections: List<HomeSection>): List<HomeSection> =
        sections
            .filter { it.isDesktopHomeDiscoverySection(userPlaylistIds) }
            .map { section ->
                section.copy(
                    items =
                        section.items.filterNot {
                            it.id.startsWith("FEmusic_library") || (it is SongItem && it.isLocal) || (it is PlaylistItem && it.isLocal)
                        },
                )
            }.filter { it.items.isNotEmpty() }

    val discovery = filterDiscovery(mergeAndDeduplicateHomeSections(data.orEmpty() + discoverySections, userPlaylistIds))

    fun items(prefix: String): List<YTItem> = discovery.filter { it.browseId?.startsWith(prefix) == true }.flatMap(HomeSection::items)

    return copy(
        data = discovery,
        discoverySections = discovery,
        chips = chips.filterNot { it.browseId?.startsWith("FEmusic_library") == true },
        selectedChip = selectedChip?.takeUnless { it.browseId?.startsWith("FEmusic_library") == true },
        chipFeeds =
            chipFeeds.map { feed ->
                feed.copy(
                    sections =
                        if (feed.chip.browseId?.startsWith("FEmusic_library") == true) {
                            emptyList()
                        } else {
                            filterDiscovery(feed.sections)
                        },
                )
            },
        speedDialItems = speedDialItems.filterNot { it.id.startsWith("FEmusic_library") && it.id !in speedDialPinnedIds },
        quickPicks = items("FEmusic_quickpicks").filterIsInstance<SongItem>().distinctBy(SongItem::id),
        forgottenFavorites = items("FEmusic_forgotten_favorites").filterIsInstance<SongItem>().distinctBy(SongItem::id),
        trending = items("FEmusic_charts").filterIsInstance<SongItem>().distinctBy(SongItem::id),
        albumCharts = items("FEmusic_charts").filterIsInstance<AlbumItem>().distinctBy(AlbumItem::id),
        newReleases =
            discovery
                .filter {
                    it.browseId?.startsWith("FEmusic_new_releases") == true &&
                        it.browseId?.endsWith("_videos") != true
                }.flatMap(HomeSection::items)
                .filterIsInstance<AlbumItem>()
                .distinctBy(AlbumItem::id),
        keepListening = items("FEmusic_listen_again").filterIsInstance<SongItem>().distinctBy(SongItem::id),
        communityPlaylist =
            items("FEmusic_community").filterIsInstance<PlaylistItem>().distinctBy(PlaylistItem::id),
        moodsAndGenres =
            items("FEmusic_moods_and_genres")
                .filterIsInstance<BrowseItem>()
                .map { HomeViewModel.Genre(it.title, it.id, it.thumbnail, it.params) }
                .distinctBy { it.id to it.params },
        recentlyPlayed = emptyList(),
        recapPlaylists = emptyList(),
        podcasts = items("FEmusic_immersive").filterIsInstance<PodcastItem>().distinctBy(PodcastItem::id),
        similarRecommendations = emptyList(),
        userPlaylists = emptyList(),
        sections = uncategorizedHomeSections(discovery),
    )
}
