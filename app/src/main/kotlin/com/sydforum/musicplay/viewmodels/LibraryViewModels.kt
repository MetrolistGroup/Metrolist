/**
 * MusicPlayer Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

@file:OptIn(ExperimentalCoroutinesApi::class, kotlinx.coroutines.FlowPreview::class)

package com.sydforum.musicplay.viewmodels

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sydforum.musicplay.innertube.YouTube
import com.sydforum.musicplay.innertube.models.ArtistItem
import com.sydforum.musicplay.innertube.utils.completed
import com.sydforum.musicplay.constants.AlbumFilter
import com.sydforum.musicplay.constants.AlbumFilterKey
import com.sydforum.musicplay.constants.AlbumSortDescendingKey
import com.sydforum.musicplay.constants.AlbumSortType
import com.sydforum.musicplay.constants.AlbumSortTypeKey
import com.sydforum.musicplay.constants.ArtistFilter
import com.sydforum.musicplay.constants.ArtistFilterKey
import com.sydforum.musicplay.constants.ArtistSongSortDescendingKey
import com.sydforum.musicplay.constants.ArtistSongSortType
import com.sydforum.musicplay.constants.ArtistSongSortTypeKey
import com.sydforum.musicplay.constants.ArtistSortDescendingKey
import com.sydforum.musicplay.constants.ArtistSortType
import com.sydforum.musicplay.constants.ArtistSortTypeKey
import com.sydforum.musicplay.constants.HideExplicitKey
import com.sydforum.musicplay.constants.HideVideoSongsKey
import com.sydforum.musicplay.constants.HideYoutubeShortsKey
import com.sydforum.musicplay.constants.PlaylistSortDescendingKey
import com.sydforum.musicplay.constants.PlaylistSortType
import com.sydforum.musicplay.constants.PlaylistSortTypeKey
import com.sydforum.musicplay.constants.SongFilter
import com.sydforum.musicplay.constants.SongFilterKey
import com.sydforum.musicplay.constants.SongSortDescendingKey
import com.sydforum.musicplay.constants.SongSortType
import com.sydforum.musicplay.constants.SongSortTypeKey
import com.sydforum.musicplay.constants.TopSize
import com.sydforum.musicplay.db.MusicDatabase
import com.sydforum.musicplay.extensions.filterExplicit
import com.sydforum.musicplay.extensions.filterExplicitAlbums
import com.sydforum.musicplay.extensions.filterVideoSongs
import com.sydforum.musicplay.extensions.filterYoutubeShorts
import com.sydforum.musicplay.extensions.matchesNormalizedQuery
import com.sydforum.musicplay.extensions.normalizeForSearch
import com.sydforum.musicplay.extensions.toEnum
import com.sydforum.musicplay.playback.DownloadUtil
import com.sydforum.musicplay.utils.PodcastRefreshTrigger
import com.sydforum.musicplay.utils.SyncUtils
import com.sydforum.musicplay.utils.dataStore
import com.sydforum.musicplay.utils.reportException
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.LocalDateTime
import javax.inject.Inject

@HiltViewModel
class LibrarySongsViewModel
@Inject
constructor(
    @ApplicationContext context: Context,
    database: MusicDatabase,
    downloadUtil: DownloadUtil,
    private val syncUtils: SyncUtils,
) : ViewModel() {
    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()
    val debouncedSearchQuery = _searchQuery
        .debounce(300)
        .stateIn(viewModelScope, SharingStarted.Lazily, "")

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    val allSongs =
        context.dataStore.data
            .map {
                Triple(
                    Triple(
                        it[SongFilterKey].toEnum(SongFilter.LIKED),
                        it[SongSortTypeKey].toEnum(SongSortType.CREATE_DATE),
                        (it[SongSortDescendingKey] ?: true),
                    ),
                    it[HideExplicitKey] ?: false,
                    it[HideVideoSongsKey] ?: false
                )
            }.distinctUntilChanged()
            .flatMapLatest { (filterSort, hideExplicit, hideVideoSongs) ->
                val (filter, sortType, descending) = filterSort
                when (filter) {
                    SongFilter.LIBRARY -> database.songs(sortType, descending).map { it.filterExplicit(hideExplicit).filterVideoSongs(hideVideoSongs) }
                    SongFilter.LIKED -> database.likedSongs(sortType, descending).map { it.filterExplicit(hideExplicit).filterVideoSongs(hideVideoSongs) }
                    SongFilter.DOWNLOADED -> database.downloadedSongs(sortType, descending).map { it.filterExplicit(hideExplicit).filterVideoSongs(hideVideoSongs) }
                    SongFilter.UPLOADED -> database.uploadedSongs(sortType, descending).map { it.filterExplicit(hideExplicit).filterVideoSongs(hideVideoSongs) }
                }
            }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun syncLikedSongs() {
        viewModelScope.launch(Dispatchers.IO) { syncUtils.syncLikedSongs() }
    }

    fun syncLibrarySongs() {
        viewModelScope.launch(Dispatchers.IO) { syncUtils.syncLibrarySongs() }
    }

    fun syncUploadedSongs() {
        viewModelScope.launch(Dispatchers.IO) { syncUtils.syncUploadedSongs() }
    }
}

@HiltViewModel
class LibraryArtistsViewModel
@Inject
constructor(
    @ApplicationContext context: Context,
    database: MusicDatabase,
    private val syncUtils: SyncUtils,
) : ViewModel() {
    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()
    val debouncedSearchQuery = _searchQuery
        .debounce(300)
        .stateIn(viewModelScope, SharingStarted.Lazily, "")

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    val allArtists =
        context.dataStore.data
            .map {
                Triple(
                    it[ArtistFilterKey].toEnum(ArtistFilter.LIKED),
                    it[ArtistSortTypeKey].toEnum(ArtistSortType.CREATE_DATE),
                    it[ArtistSortDescendingKey] ?: true,
                )
            }.distinctUntilChanged()
            .flatMapLatest { (filter, sortType, descending) ->
                when (filter) {
                    ArtistFilter.LIKED -> database.artistsBookmarked(sortType, descending)
                    ArtistFilter.LIBRARY -> database.artists(sortType, descending)
                }
            }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val filteredArtists =
        combine(allArtists, searchQuery) { artists, query ->
            val normalizedQuery = query.normalizeForSearch()
            artists
                .filter { artist ->
                    matchesNormalizedQuery(normalizedQuery, artist.artist.name)
                }
                .distinctBy { it.id }
        }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun sync() {
        viewModelScope.launch(Dispatchers.IO) { syncUtils.syncArtistsSubscriptions() }
    }

    init {
        viewModelScope.launch(Dispatchers.IO) {
            allArtists.collect { artists ->
                artists
                    .map { it.artist }
                    .filter {
                        it.thumbnailUrl == null || Duration.between(
                            it.lastUpdateTime,
                            LocalDateTime.now()
                        ) > Duration.ofDays(10)
                    }.take(5)
                    .forEach { artist ->
                        YouTube.artist(artist.id).onSuccess { artistPage ->
                            database.query {
                                update(artist, artistPage)
                            }
                        }
                    }
            }
        }
    }
}

@HiltViewModel
class LibraryAlbumsViewModel
@Inject
constructor(
    @ApplicationContext context: Context,
    database: MusicDatabase,
    private val syncUtils: SyncUtils,
) : ViewModel() {
    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()
    val debouncedSearchQuery = _searchQuery
        .debounce(300)
        .stateIn(viewModelScope, SharingStarted.Lazily, "")

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    val allAlbums =
        context.dataStore.data
            .map {
                Pair(
                    Triple(
                        it[AlbumFilterKey].toEnum(AlbumFilter.LIKED),
                        it[AlbumSortTypeKey].toEnum(AlbumSortType.CREATE_DATE),
                        it[AlbumSortDescendingKey] ?: true,
                    ),
                    it[HideExplicitKey] ?: false
                )
            }.distinctUntilChanged()
            .flatMapLatest { (filterSort, hideExplicit) ->
                val (filter, sortType, descending) = filterSort
                when (filter) {
                    AlbumFilter.LIKED -> database.albumsLiked(sortType, descending).map { it.filterExplicitAlbums(hideExplicit) }
                    AlbumFilter.LIBRARY -> database.albums(sortType, descending).map { it.filterExplicitAlbums(hideExplicit) }
                    AlbumFilter.UPLOADED -> database.albumsUploaded(sortType, descending).map { it.filterExplicitAlbums(hideExplicit) }
                }
            }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun sync() {
        viewModelScope.launch(Dispatchers.IO) { syncUtils.syncLikedAlbums() }
    }

    init {
        viewModelScope.launch(Dispatchers.IO) {
            allAlbums.collect { albums ->
                albums
                    .filter {
                        it.album.songCount == 0
                    }.take(5)
                    .forEach { album ->
                        YouTube
                            .album(album.id)
                            .onSuccess { albumPage ->
                                database.query {
                                    update(album.album, albumPage, album.artists)
                                }
                            }.onFailure {
                                reportException(it)
                            }
                    }
            }
        }
    }
}

@HiltViewModel
class LibraryPlaylistsViewModel
@Inject
constructor(
    @ApplicationContext context: Context,
    database: MusicDatabase,
    private val syncUtils: SyncUtils,
) : ViewModel() {
    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()
    val debouncedSearchQuery = _searchQuery
        .debounce(300)
        .stateIn(viewModelScope, SharingStarted.Lazily, "")

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    val allPlaylists =
        context.dataStore.data
            .map {
                Triple(
                    it[PlaylistSortTypeKey].toEnum(PlaylistSortType.CREATE_DATE),
                    it[PlaylistSortDescendingKey] ?: true,
                    it[HideYoutubeShortsKey] ?: false
                )
            }.distinctUntilChanged()
            .flatMapLatest { (sortType, descending, hideYoutubeShorts) ->
                database.playlists(sortType, descending).map { it.filterYoutubeShorts(hideYoutubeShorts) }
            }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun sync() {
        viewModelScope.launch(Dispatchers.IO) { syncUtils.syncSavedPlaylists() }
    }

    val topValue =
        context.dataStore.data
            .map { it[TopSize] ?: "50" }
            .distinctUntilChanged()
}

@HiltViewModel
class ArtistSongsViewModel
@Inject
constructor(
    @ApplicationContext context: Context,
    database: MusicDatabase,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val artistId = savedStateHandle.get<String>("artistId")!!
    val artist =
        database
            .artist(artistId)
            .stateIn(viewModelScope, SharingStarted.Lazily, null)

    val songs =
        context.dataStore.data
            .map {
                Triple(
                    it[ArtistSongSortTypeKey].toEnum(ArtistSongSortType.CREATE_DATE) to (it[ArtistSongSortDescendingKey]
                        ?: true),
                    it[HideExplicitKey] ?: false,
                    it[HideVideoSongsKey] ?: false
                )
            }.distinctUntilChanged()
            .flatMapLatest { (sortDesc, hideExplicit, hideVideoSongs) ->
                val (sortType, descending) = sortDesc
                database.artistSongs(artistId, sortType, descending).map { it.filterExplicit(hideExplicit).filterVideoSongs(hideVideoSongs) }
            }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
}

@HiltViewModel
class LibraryMixViewModel
@Inject
constructor(
    @ApplicationContext context: Context,
    database: MusicDatabase,
    private val syncUtils: SyncUtils,
) : ViewModel() {
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing = _isRefreshing.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()
    val debouncedSearchQuery = _searchQuery
        .debounce(300)
        .stateIn(viewModelScope, SharingStarted.Lazily, "")

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    val syncAllLibrary = {
         viewModelScope.launch(Dispatchers.IO) {
             syncUtils.tryAutoSync()
         }
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            _isRefreshing.value = true
            syncUtils.performFullSyncSuspend()
            _isRefreshing.value = false
        }
    }

    val topValue =
        context.dataStore.data
            .map { it[TopSize] ?: "50" }
            .distinctUntilChanged()
    var artists =
        database
            .artistsBookmarked(
                ArtistSortType.CREATE_DATE,
                true,
            ).stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    var albums = context.dataStore.data
        .map { it[HideExplicitKey] ?: false }
        .distinctUntilChanged()
        .flatMapLatest { hideExplicit ->
            database.albumsLiked(AlbumSortType.CREATE_DATE, true).map { it.filterExplicitAlbums(hideExplicit) }
        }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    var songs = context.dataStore.data
        .map { Triple(it[HideExplicitKey] ?: false, it[HideVideoSongsKey] ?: false, it[HideYoutubeShortsKey] ?: false) }
        .distinctUntilChanged()
        .flatMapLatest { (hideExplicit, hideVideoSongs, _) ->
            combine(
                database.songs(SongSortType.CREATE_DATE, true),
                database.songsInBookmarkedPlaylists()
            ) { librarySongs, playlistSongs ->
                (librarySongs + playlistSongs)
                    .distinctBy { it.id }
                    .filterExplicit(hideExplicit)
                    .filterVideoSongs(hideVideoSongs)
            }
        }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    var playlists = context.dataStore.data
        .map { it[HideYoutubeShortsKey] ?: false }
        .distinctUntilChanged()
        .flatMapLatest { hideYoutubeShorts ->
            database.playlists(PlaylistSortType.CREATE_DATE, true).map { it.filterYoutubeShorts(hideYoutubeShorts) }
        }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    init {
        viewModelScope.launch(Dispatchers.IO) {
            albums.collect { albums ->
                albums
                    .filter {
                        it.album.songCount == 0
                    }.take(5)
                    .forEach { album ->
                        YouTube
                            .album(album.id)
                            .onSuccess { albumPage ->
                                database.query {
                                    update(album.album, albumPage, album.artists)
                                }
                            }.onFailure {
                                reportException(it)
                            }
                    }
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            artists.collect { artists ->
                artists
                    .map { it.artist }
                    .filter {
                        it.thumbnailUrl == null ||
                                Duration.between(
                                    it.lastUpdateTime,
                                    LocalDateTime.now(),
                                ) > Duration.ofDays(10)
                    }.take(5)
                    .forEach { artist ->
                        YouTube.artist(artist.id).onSuccess { artistPage ->
                            database.query {
                                update(artist, artistPage)
                            }
                        }
                    }
            }
        }
    }
}

@HiltViewModel
class LibraryPodcastsViewModel
@Inject
constructor(
    @ApplicationContext context: Context,
    private val database: MusicDatabase,
    private val syncUtils: SyncUtils,
) : ViewModel() {
    // Subscribed podcast channels synced from YT Music
    val subscribedChannels = database.subscribedPodcasts()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // SE "Episodes for Later" playlist fetched from YT Music (like AccountScreen)
    private val _sePlaylist = MutableStateFlow<com.sydforum.musicplay.innertube.models.PlaylistItem?>(null)
    val sePlaylist = _sePlaylist.asStateFlow()

    // RDPN "New Episodes" playlist fetched from YouTube Music (real thumbnail + episode count)
    private val _rdpnPlaylist = MutableStateFlow<com.sydforum.musicplay.innertube.models.PlaylistItem?>(null)
    val rdpnPlaylist = _rdpnPlaylist.asStateFlow()

    // Podcast host channels fetched from YT Music library/podcast_channels
    private val _apiPodcastChannels = MutableStateFlow<List<ArtistItem>>(emptyList())

    // Podcast channels: API subscriptions + locally bookmarked artists that have podcasts
    // Only shows channels explicitly subscribed to (not derived from saved podcasts)
    val podcastChannels = kotlinx.coroutines.flow.combine(
        _apiPodcastChannels,
        database.bookmarkedPodcastChannels()
    ) { apiChannels, localPodcastChannels ->
        // Convert locally bookmarked podcast channels to ArtistItem format
        val localAsArtistItems = localPodcastChannels.map { artist ->
            ArtistItem(
                id = artist.id,
                title = artist.artist.name,
                thumbnail = artist.artist.thumbnailUrl,
                shuffleEndpoint = null,
                radioEndpoint = null,
            )
        }

        // Combine and deduplicate by ID (prefer API version if exists)
        val apiIds = apiChannels.map { it.id }.toSet()
        val uniqueLocalChannels = localAsArtistItems.filter { it.id !in apiIds }
        apiChannels + uniqueLocalChannels
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // Downloaded podcast episodes
    val downloadedEpisodes =
        context.dataStore.data
            .map {
                Pair(
                    it[SongSortTypeKey].toEnum(SongSortType.CREATE_DATE) to (it[SongSortDescendingKey] ?: true),
                    it[HideExplicitKey] ?: false
                )
            }.distinctUntilChanged()
            .flatMapLatest { (sortDesc, hideExplicit) ->
                val (sortType, descending) = sortDesc
                database.downloadedPodcastEpisodes(sortType, descending).map { it.filterExplicit(hideExplicit) }
            }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // Saved podcast episodes (in library, not necessarily downloaded)
    val savedEpisodes =
        context.dataStore.data
            .map {
                Pair(
                    it[SongSortTypeKey].toEnum(SongSortType.CREATE_DATE) to (it[SongSortDescendingKey] ?: true),
                    it[HideExplicitKey] ?: false
                )
            }.distinctUntilChanged()
            .flatMapLatest { (sortDesc, hideExplicit) ->
                val (sortType, descending) = sortDesc
                database.savedPodcastEpisodes(sortType, descending).map { it.filterExplicit(hideExplicit) }
            }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private suspend fun fetchSePlaylist() {
        YouTube.library("FEmusic_liked_playlists").completed().onSuccess {
            _sePlaylist.value = it.items
                .filterIsInstance<com.sydforum.musicplay.innertube.models.PlaylistItem>()
                .find { it.id == "SE" }
        }.onFailure {
            timber.log.Timber.e(it, "[PODCAST] Failed to fetch SE playlist")
        }
    }

    private suspend fun fetchPodcastChannels() {
        YouTube.libraryPodcastChannels().onSuccess { page ->
            val channels = page.items.filterIsInstance<ArtistItem>()
            _apiPodcastChannels.value = channels
            timber.log.Timber.d("[PODCAST] Fetched ${channels.size} podcast channels from YT Music")
        }.onFailure {
            timber.log.Timber.e(it, "[PODCAST] Failed to fetch podcast channels")
        }
    }

    private suspend fun fetchRdpnPlaylist() {
        YouTube.newEpisodesPlaylistInfo().onSuccess { item ->
            _rdpnPlaylist.value = item
            timber.log.Timber.d("[PODCAST] RDPN playlist: ${item.title}, thumbnail: ${item.thumbnail}")
        }.onFailure {
            timber.log.Timber.e(it, "[PODCAST] Failed to fetch RDPN playlist info")
        }
    }

    init {
        viewModelScope.launch(Dispatchers.IO) {
            fetchSePlaylist()
        }
        viewModelScope.launch(Dispatchers.IO) {
            fetchPodcastChannels()
        }
        viewModelScope.launch(Dispatchers.IO) {
            fetchRdpnPlaylist()
        }
        viewModelScope.launch(Dispatchers.IO) {
            syncUtils.syncPodcastSubscriptionsSuspend()
        }
        // Observe refresh trigger for auto-refresh after subscribe/unsubscribe
        viewModelScope.launch(Dispatchers.IO) {
            PodcastRefreshTrigger.refreshFlow.collect {
                // Small delay to allow YouTube's backend to update
                kotlinx.coroutines.delay(1500)
                fetchPodcastChannels()
            }
        }
    }

    fun clearPodcastData() {
        viewModelScope.launch(Dispatchers.IO) {
            syncUtils.clearPodcastData()
        }
    }

    suspend fun refreshAll() {
        fetchSePlaylist()
        fetchPodcastChannels()
        fetchRdpnPlaylist()
        syncUtils.syncPodcastSubscriptionsSuspend()
        syncUtils.syncEpisodesForLaterSuspend()
    }

    /**
     * Force refresh podcast channels. Called when screen becomes visible.
     */
    fun refreshChannels() {
        viewModelScope.launch(Dispatchers.IO) {
            fetchPodcastChannels()
        }
    }
}
