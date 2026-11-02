/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.Artist
import com.metrolist.innertube.models.ArtistItem
import com.metrolist.innertube.models.BrowseEndpoint
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.WatchEndpoint
import com.metrolist.innertube.models.WatchEndpoint.WatchEndpointMusicSupportedConfigs.WatchEndpointMusicConfig.Companion.MUSIC_VIDEO_TYPE_ATV
import com.metrolist.innertube.models.WatchEndpoint.WatchEndpointMusicSupportedConfigs.WatchEndpointMusicConfig.Companion.MUSIC_VIDEO_TYPE_OMV
import com.metrolist.innertube.models.YTItem
import com.metrolist.innertube.models.filterExplicit
import com.metrolist.innertube.models.filterVideoSongs
import com.metrolist.innertube.models.filterYoutubeShorts
import com.metrolist.innertube.pages.ExplorePage
import com.metrolist.innertube.pages.HomePage
import com.metrolist.innertube.utils.completed
import com.metrolist.music.constants.AccountNameKey
import com.metrolist.music.constants.HideExplicitKey
import com.metrolist.music.constants.HideVideoSongsKey
import com.metrolist.music.constants.HideYoutubeShortsKey
import com.metrolist.music.constants.InnerTubeCookieKey
import com.metrolist.music.constants.QuickPicks
import com.metrolist.music.constants.QuickPicksKey
import com.metrolist.music.constants.ShowWrappedCardKey
import com.metrolist.music.constants.WrappedSeenKey
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.Album
import com.metrolist.music.db.entities.LocalItem
import com.metrolist.music.db.entities.Song
import com.metrolist.music.db.entities.SpeedDialItem
import com.metrolist.music.extensions.filterVideoSongs
import com.metrolist.music.extensions.toEnum
import com.metrolist.music.models.SimilarRecommendation
import com.metrolist.music.models.toMediaMetadata
import com.metrolist.music.ui.screens.wrapped.WrappedAudioService
import com.metrolist.music.ui.screens.wrapped.WrappedManager
import com.metrolist.music.utils.NetworkConnectivityObserver
import com.metrolist.music.utils.SyncUtils
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.get
import com.metrolist.music.utils.reportException
import com.metrolist.music.utils.safeDataStoreEdit
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import timber.log.Timber
import java.time.LocalDate
import java.time.LocalDateTime
import javax.inject.Inject
import kotlin.random.Random

data class DailyDiscoverItem(
    val seed: Song,
    val recommendation: YTItem,
    val relatedEndpoint: BrowseEndpoint?
)

data class CommunityPlaylistItem(
    val playlist: PlaylistItem,
    val songs: List<SongItem>
)

internal fun filterDailyDiscoverItems(
    items: List<DailyDiscoverItem>,
    hideVideos: Boolean,
): List<DailyDiscoverItem> {
    val selectedIds = mutableSetOf<String>()
    return items.filterNot {
        hideVideos && (it.seed.song.isVideo || (it.recommendation as? SongItem)?.isVideoSong == true)
    }.groupBy { it.seed.id }.values.mapNotNull { candidates ->
        candidates.firstOrNull { it.recommendation.id !in selectedIds }
            ?.also { selectedIds.add(it.recommendation.id) }
    }
}

internal fun filterKeepListeningItems(items: List<LocalItem>, hideVideos: Boolean): List<LocalItem> {
    val songIds = items.filterIsInstance<Song>().filterVideoSongs(hideVideos)
        .take(10).map { it.id }.toSet()
    return items.filter { it !is Song || it.id in songIds }
}

internal fun buildSpeedDialItems(
    pinned: List<YTItem>,
    keepListening: List<YTItem>,
    quickPicks: List<YTItem>,
    home: List<YTItem>,
    hideVideoSongs: Boolean = false,
): List<YTItem> {
    // Keep unresolved pins hidden until their metadata has been loaded.
    val visiblePinned = if (hideVideoSongs) {
        pinned.filterNot { it is SongItem && it.musicVideoType == null }
    } else pinned
    val items = visiblePinned + keepListening + quickPicks + home
    val videoIds =
        if (hideVideoSongs) {
            items.filterIsInstance<SongItem>().filter { it.isVideoSong }.map { it.id }.toSet()
        } else emptySet()

    return items.filterNot { it.id in videoIds }
        .distinctBy { it.id }
        .take(27)
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext val context: Context,
    val database: MusicDatabase,
    val syncUtils: SyncUtils,
    val wrappedManager: WrappedManager,
    private val wrappedAudioService: WrappedAudioService,
    private val networkConnectivity: NetworkConnectivityObserver,
) : ViewModel() {
    val isRefreshing = MutableStateFlow(false)
    val isLoading = MutableStateFlow(false)
    val isRandomizing = MutableStateFlow(false)

    private val quickPicksEnum = context.dataStore.data.map {
        it[QuickPicksKey].toEnum(QuickPicks.QUICK_PICKS)
    }.distinctUntilChanged()

    private val _quickPicks = MutableStateFlow<List<Song>?>(null)
    private val _dailyDiscover = MutableStateFlow<List<DailyDiscoverItem>?>(null)
    private val _forgottenFavorites = MutableStateFlow<List<Song>?>(null)
    private val _keepListening = MutableStateFlow<List<LocalItem>?>(null)
    private val _similarRecommendations = MutableStateFlow<List<SimilarRecommendation>?>(null)
    val accountPlaylists = MutableStateFlow<List<PlaylistItem>?>(null)
    private val _homePage = MutableStateFlow<HomePage?>(null)
    val explorePage = MutableStateFlow<ExplorePage?>(null)
    private val _communityPlaylists = MutableStateFlow<List<CommunityPlaylistItem>?>(null)
    val selectedChip = MutableStateFlow<HomePage.Chip?>(null)
    private val previousHomePage = MutableStateFlow<HomePage?>(null)

    // Official API data for podcast sections
    val savedPodcastShows = MutableStateFlow<List<com.metrolist.innertube.models.PodcastItem>>(emptyList())
    val episodesForLater = MutableStateFlow<List<SongItem>>(emptyList())

    private val _allLocalItems = MutableStateFlow<List<LocalItem>>(emptyList())
    private val _allYtItems = MutableStateFlow<List<YTItem>>(emptyList())

    private val hideVideoSongs = context.dataStore.data
        .map { it[HideVideoSongsKey] ?: false }
        .distinctUntilChanged()

    val quickPicks = _quickPicks.withVideoFilter { songs, hide ->
        songs?.filterVideoSongs(hide)?.take(20)
    }
    val forgottenFavorites = _forgottenFavorites.withVideoFilter { songs, hide ->
        songs?.filterVideoSongs(hide)?.take(20)
    }
    val keepListening = _keepListening.withVideoFilter { items, hide ->
        items?.let { filterKeepListeningItems(it, hide) }
    }
    val homePage = _homePage.withVideoFilter { page, hide -> page?.filterVideoSongs(hide) }
    val dailyDiscover = _dailyDiscover.withVideoFilter { items, hide ->
        items?.let { filterDailyDiscoverItems(it, hide) }
    }
    val similarRecommendations = _similarRecommendations.withVideoFilter { sections, hide ->
        if (hide) {
            sections?.mapNotNull { section ->
                if ((section.title as? Song)?.song?.isVideo == true) {
                    null
                } else {
                    section.copy(items = section.items.filterVideoSongs(true))
                        .takeIf { it.items.isNotEmpty() }
                }
            }
        } else sections
    }
    val communityPlaylists = _communityPlaylists.withVideoFilter { playlists, hide ->
        if (hide) {
            playlists?.mapNotNull { playlist ->
                playlist.copy(songs = playlist.songs.filterVideoSongs(true))
                    .takeIf { it.songs.isNotEmpty() }
            }
        } else playlists
    }
    val allLocalItems = _allLocalItems.withVideoFilter { items, hide ->
        items.filterNot { hide && it is Song && it.song.isVideo }
    }
    val allYtItems = _allYtItems.withVideoFilter { items, hide -> items.filterVideoSongs(hide) }

    private fun <T> StateFlow<T>.withVideoFilter(filter: (T, Boolean) -> T): StateFlow<T> =
        combine(this, hideVideoSongs) { items, hide -> filter(items, hide) }
            .stateIn(viewModelScope, SharingStarted.Lazily, value)

    val pinnedSpeedDialItems: StateFlow<List<SpeedDialItem>> =
        database.speedDialDao.getAll()
            .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    private val resolvedPinnedItems =
        combine(database.speedDialDao.getAll(), hideVideoSongs) { pinned, hide -> pinned to hide }
            .transformLatest { (pinned, hide) ->
                emit(pinned.map { it.toYTItem() })
                if (hide && pinned.any { it.type == "SONG" && it.secondaryId == null }) {
                    emit(resolvePinnedItems(pinned, hide))
                }
            }
            .flowOn(Dispatchers.IO)

    val speedDialItems: StateFlow<List<YTItem>> =
        combine(
            resolvedPinnedItems,
            _keepListening,
            _quickPicks,
            _homePage,
            hideVideoSongs,
        ) { pinned, keepListening, quick, home, hideVideoSongs ->
            buildSpeedDialItems(
                pinned = pinned,
                keepListening =
                    keepListening.orEmpty().mapNotNull { item ->
                        when (item) {
                            is Song -> item.toMediaMetadata().toYTItem()

                            is Album ->
                                AlbumItem(
                                    browseId = item.id,
                                    playlistId = item.album.playlistId ?: "",
                                    title = item.title,
                                    artists = item.artists.map { Artist(name = it.name, id = it.id) },
                                    year = item.album.year,
                                    thumbnail = item.thumbnailUrl ?: "",
                                )

                            is com.metrolist.music.db.entities.Artist ->
                                ArtistItem(
                                    id = item.id,
                                    title = item.title,
                                    thumbnail = item.thumbnailUrl,
                                    shuffleEndpoint = null,
                                    radioEndpoint = null,
                                )

                            else -> null
                        }
                    },
                quickPicks =
                    quick.orEmpty().map { it.toMediaMetadata().toYTItem() },
                home = home?.sections.orEmpty().flatMap { it.items },
                hideVideoSongs = hideVideoSongs,
            )
        }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private suspend fun resolvePinnedItems(
        pinned: List<SpeedDialItem>,
        hideVideos: Boolean,
    ): List<YTItem> {
        if (!hideVideos) return pinned.map { it.toYTItem() }

        val unresolvedPins = pinned.filter { it.type == "SONG" && it.secondaryId == null }
        // Keep each query within SQLite's bind limit on older Android versions.
        val localSongs = unresolvedPins.map { it.id }.chunked(999)
            .flatMap { database.getSongsByIds(it) }
            .associateBy { it.id }
        val unresolvedIds = unresolvedPins.filter {
            val song = localSongs[it.id]?.song
            song?.isVideo != true && song?.isLocal != true
        }.map { it.id }
        val remoteSongs =
            unresolvedIds.chunked(YouTube.MAX_GET_QUEUE_SIZE).flatMap { ids ->
                YouTube.queue(videoIds = ids)
                    .onFailure {
                        if (it is CancellationException) throw it
                        reportException(it)
                    }
                    .getOrDefault(emptyList())
            }.associateBy { it.id }

        return pinned.map { pin ->
            val item = pin.toYTItem()
            if (item is SongItem) {
                val type = item.musicVideoType ?: remoteSongs[pin.id]?.musicVideoType
                    ?: localSongs[pin.id]?.let {
                        if (it.song.isVideo) MUSIC_VIDEO_TYPE_OMV else MUSIC_VIDEO_TYPE_ATV
                    }
                item.copy(musicVideoType = type)
            } else item
        }
    }

    suspend fun getRandomItem(): YTItem? {
        try {
            isRandomizing.value = true
            // Visual feedback for the animation
            kotlinx.coroutines.delay(1000)

            val userSongs = mutableListOf<YTItem>()
            val otherSources = mutableListOf<YTItem>()

            _quickPicks.value?.let { songs ->
                userSongs.addAll(songs.map { it.toMediaMetadata().toYTItem() })
            }

            _keepListening.value?.let { items ->
                items.forEach { item ->
                    when (item) {
                        is Song -> userSongs.add(item.toMediaMetadata().toYTItem())
                        is Album -> otherSources.add(AlbumItem(
                            browseId = item.id,
                            playlistId = item.album.playlistId ?: "",
                            title = item.title,
                            artists = item.artists.map { Artist(name = it.name, id = it.id) },
                            year = item.album.year,
                            thumbnail = item.thumbnailUrl ?: ""
                        ))
                        is com.metrolist.music.db.entities.Artist -> otherSources.add(ArtistItem(
                            id = item.id,
                            title = item.title,
                            thumbnail = item.thumbnailUrl,
                            shuffleEndpoint = null,
                            radioEndpoint = null
                        ))
                        else -> {}
                    }
                }
            }

            otherSources.addAll(_allYtItems.value)
            if (context.dataStore.get(HideVideoSongsKey, false)) {
                userSongs.removeAll { (it as? SongItem)?.isVideoSong == true }
                otherSources.removeAll { (it as? SongItem)?.isVideoSong == true }
            }

            // Probability: 80% User Songs, 20% Other Sources
            val item = if (userSongs.isNotEmpty() && (otherSources.isEmpty() || Random.nextFloat() < 0.8f)) {
                userSongs.distinctBy { it.id }.shuffled().firstOrNull()
            } else {
                otherSources.distinctBy { it.id }.shuffled().firstOrNull()
            } ?: userSongs.firstOrNull() ?: otherSources.firstOrNull()

            return item
        } finally {
            isRandomizing.value = false
        }
    }

    val accountName = MutableStateFlow("Guest")
    val accountImageUrl = MutableStateFlow<String?>(null)

	val showWrappedCard: StateFlow<Boolean> = context.dataStore.data.map { prefs ->
        val showWrappedPref = prefs[ShowWrappedCardKey] ?: false
        val seen = prefs[WrappedSeenKey] ?: false
        val isBeforeDate = LocalDate.now().isBefore(LocalDate.of(2026, 2, 1))

        isBeforeDate && (!seen || showWrappedPref)
    }.stateIn(viewModelScope, SharingStarted.Lazily, false)

    val wrappedSeen: StateFlow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[WrappedSeenKey] ?: false
    }.stateIn(viewModelScope, SharingStarted.Lazily, false)

    fun markWrappedAsSeen() {
        viewModelScope.launch(Dispatchers.IO) {
            context.safeDataStoreEdit {
                it[WrappedSeenKey] = true
            }
        }
    }
    private suspend fun getDailyDiscover() {
        val likedSongs = database.likedSongsByCreateDateAsc().first()
        if (likedSongs.isEmpty()) return

        val seeds = likedSongs.shuffled().distinctBy { it.id }.take(5)
        
        // Use a synchronized list to collect results safely from concurrent coroutines
        val items = java.util.Collections.synchronizedList(mutableListOf<DailyDiscoverItem>())

        kotlinx.coroutines.coroutineScope {
            seeds.map { seed ->
                launch(Dispatchers.IO) {
                    val endpoint = YouTube.next(WatchEndpoint(videoId = seed.id)).getOrNull()?.relatedEndpoint
                    if (endpoint != null) {
                        YouTube.related(endpoint).onSuccess { page ->
                            val recommendations = page.songs
                                .filterExplicit()
                                .shuffled()

                            // Simple check to avoid immediate duplicate of seed
                            recommendations.filter { it.id != seed.id }.forEach { recommendation ->
                                items.add(
                                    DailyDiscoverItem(
                                        seed = seed,
                                        recommendation = recommendation,
                                        relatedEndpoint = endpoint
                                    )
                                )
                            }
                        }
                    }
                }
            }.forEach { it.join() }
        }
        
        _dailyDiscover.value = items.toList().shuffled()
    }

    private suspend fun getQuickPicks() {
        when (quickPicksEnum.first()) {
            QuickPicks.QUICK_PICKS -> {
                val relatedSongs = database.quickPicks().first()
                val forgotten = database.forgottenFavorites().first().take(8)

                // Get similar songs from YouTube based on recent listening
                val recentSong = database.latestEvent().first()?.song
                val ytSimilarSongs = mutableListOf<Song>()

                if (recentSong != null) {
                    val endpoint = YouTube.next(WatchEndpoint(videoId = recentSong.id)).getOrNull()?.relatedEndpoint
                    if (endpoint != null) {
                        YouTube.related(endpoint).onSuccess { page ->
                            // Convert YouTube songs to local Song format if they exist in database
                            page.songs.take(10).forEach { ytSong ->
                                database.song(ytSong.id).first()?.let { localSong ->
                                    ytSimilarSongs.add(localSong)
                                }
                            }
                        }
                    }
                }

                // Combine all sources and remove duplicates
                val combined = (relatedSongs + forgotten + ytSimilarSongs)
                    .distinctBy { it.id }
                    .shuffled()

                _quickPicks.value = combined
            }
            QuickPicks.LAST_LISTEN -> {
                val song = database.latestEvent().first()?.song
                if (song != null && database.hasRelatedSongs(song.id)) {
                    _quickPicks.value = database.getRelatedSongs(song.id).first().shuffled()
                }
            }
        }
    }

    private suspend fun getCommunityPlaylists() {
        val fromTimeStamp = LocalDateTime.now().minusWeeks(4)
        val artistSeeds = database.mostPlayedArtists(fromTimeStamp, limit = 10).first()
            .filter { it.artist.isYouTubeArtist }
            .shuffled().take(3)
        val songSeeds = database.mostPlayedSongs(fromTimeStamp = fromTimeStamp, limit = 5, offset = 0, toTimeStamp = LocalDateTime.now()).first()
            .shuffled().take(2)

        val candidatePlaylists = java.util.Collections.synchronizedList(mutableListOf<PlaylistItem>())

        kotlinx.coroutines.coroutineScope {
            artistSeeds.map { seed ->
                launch(Dispatchers.IO) {
                    YouTube.artist(seed.id).onSuccess { page ->
                        page.sections.forEach { section ->
                            section.items.filterIsInstance<PlaylistItem>().forEach { playlist ->
                                if (playlist.author?.name != "YouTube Music" && 
                                    playlist.author?.name != "YouTube" && 
                                    playlist.author?.name != "Playlist" &&
                                    playlist.author?.name != seed.artist.name &&
                                    !playlist.id.startsWith("RD") &&
                                    !playlist.id.startsWith("OLAK")
                                ) {
                                    candidatePlaylists.add(playlist)
                                }
                            }
                        }
                    }
                }
            }
            
            songSeeds.map { seed ->
                launch(Dispatchers.IO) {
                    val endpoint = YouTube.next(WatchEndpoint(videoId = seed.id)).getOrNull()?.relatedEndpoint
                    if (endpoint != null) {
                        YouTube.related(endpoint).onSuccess { page ->
                            page.playlists.forEach { playlist ->
                                if (playlist.author?.name != "YouTube Music" && 
                                    playlist.author?.name != "YouTube" && 
                                    playlist.author?.name != "Playlist" &&
                                    !playlist.id.startsWith("RD") &&
                                    !playlist.id.startsWith("OLAK")
                                ) {
                                    candidatePlaylists.add(playlist)
                                }
                            }
                        }
                    }
                }
            }
        }

        val uniqueCandidates = candidatePlaylists.distinctBy { it.id }.shuffled().take(5)

        val playlists = java.util.Collections.synchronizedList(mutableListOf<CommunityPlaylistItem>())

        kotlinx.coroutines.coroutineScope {
            uniqueCandidates.map { playlist ->
                launch(Dispatchers.IO) {
                    YouTube.playlist(playlist.id).onSuccess { page ->
                        val songs = page.songs.take(10)
                        if (songs.isNotEmpty()) {
                            // Use song count from the playlist page if available, otherwise use original
                            val songCountText = page.playlist.songCountText ?: playlist.songCountText
                            val updatedPlaylist = playlist.copy(songCountText = songCountText)
                            playlists.add(CommunityPlaylistItem(updatedPlaylist, songs))
                        }
                    }
                }
            }.forEach { it.join() }
        }

        _communityPlaylists.value = playlists.shuffled()
    }

    private suspend fun load() {
        isLoading.value = true
        val hideExplicit = context.dataStore.get(HideExplicitKey, false)
        val hideYoutubeShorts = context.dataStore.get(HideYoutubeShortsKey, false)
        val fromTimeStamp = LocalDateTime.now().minusWeeks(2)

        // Phase 1: Load essential sections in parallel — local DB (fast) + YouTube home page.
        // isLoading is set to false as soon as all Phase 1 tasks complete so the UI appears quickly.
        coroutineScope {
            launch(Dispatchers.IO) { getQuickPicks() }

            launch(Dispatchers.IO) {
                _forgottenFavorites.value = database.forgottenFavorites().first()
                    .shuffled()
            }

            launch(Dispatchers.IO) {
                val songs = database.mostPlayedSongs(fromTimeStamp = fromTimeStamp, limit = 15, offset = 5, toTimeStamp = LocalDateTime.now()).first()
                    .shuffled()
                val albums = database.mostPlayedAlbums(fromTimeStamp, limit = 8, offset = 2).first()
                    .filter { it.album.thumbnailUrl != null }.shuffled().take(5)
                val artists = database.mostPlayedArtists(fromTimeStamp).first()
                    .filter { it.artist.isYouTubeArtist && it.artist.thumbnailUrl != null }.shuffled().take(5)
                _keepListening.value = (songs + albums + artists).shuffled()
            }

            launch(Dispatchers.IO) {
                YouTube.home().onSuccess { page ->
                    _homePage.value = page.copy(
                        sections = page.sections.mapNotNull { section ->
                            val filtered = section.items
                                .filterOutNulls()
                                .filterExplicit(hideExplicit)
                                .filterYoutubeShorts(hideYoutubeShorts)
                            if (filtered.isEmpty()) null else section.copy(items = filtered)
                        }
                    )
                }.onFailure { reportException(it) }
            }

            if (YouTube.cookie != null) {
                launch(Dispatchers.IO) { loadAccountInfo() }
                launch(Dispatchers.IO) { loadAccountPlaylists() }
            }
        }

        _allLocalItems.value = (_quickPicks.value.orEmpty() + _forgottenFavorites.value.orEmpty() + _keepListening.value.orEmpty())
            .filter { it is Song || it is Album }
        isLoading.value = false

        // Phase 2: Heavy multi-request operations — run in background without blocking the UI.
        viewModelScope.launch(Dispatchers.IO) { getDailyDiscover() }

        viewModelScope.launch(Dispatchers.IO) { getCommunityPlaylists() }

        viewModelScope.launch(Dispatchers.IO) {
            YouTube.explore().onSuccess { page ->
                explorePage.value = page.copy(
                    newReleaseAlbums = page.newReleaseAlbums.filterOutNulls().filterExplicit(hideExplicit),
                    moodAndGenres = page.moodAndGenres.filterOutNulls()
                )
            }.onFailure { reportException(it) }
        }

        viewModelScope.launch(Dispatchers.IO) {
            val artistRecommendations = database.mostPlayedArtists(fromTimeStamp, limit = 15).first()
                .filter { it.artist.isYouTubeArtist }
                .shuffled().take(4)
                .mapNotNull {
                    val items = mutableListOf<YTItem>()
                    YouTube.artist(it.id).onSuccess { page ->
                        page.sections.takeLast(3).forEach { section -> items += section.items }
                    }
                    SimilarRecommendation(
                        title = it,
                        items = items
                            .distinctBy { item -> item.id }
                            .filterExplicit(hideExplicit)
                            .shuffled().take(12)
                            .ifEmpty { return@mapNotNull null }
                    )
                }

            val songRecommendations = database.mostPlayedSongs(fromTimeStamp = fromTimeStamp, limit = 15, offset = 0, toTimeStamp = LocalDateTime.now()).first()
                .filter { it.album != null }
                .shuffled().take(3)
                .mapNotNull { song ->
                    val endpoint = YouTube.next(WatchEndpoint(videoId = song.id)).getOrNull()?.relatedEndpoint
                        ?: return@mapNotNull null
                    val page = YouTube.related(endpoint).getOrNull() ?: return@mapNotNull null
                    SimilarRecommendation(
                        title = song,
                        items = (page.songs.shuffled().take(10) +
                                page.albums.shuffled().take(5) +
                                page.artists.shuffled().take(3) +
                                page.playlists.shuffled().take(3))
                            .distinctBy { it.id }
                            .filterExplicit(hideExplicit)
                            .shuffled()
                            .ifEmpty { return@mapNotNull null }
                    )
                }

            val albumRecommendations = database.mostPlayedAlbums(fromTimeStamp, limit = 10).first()
                .filter { it.album.thumbnailUrl != null }
                .shuffled().take(2)
                .mapNotNull { album ->
                    val items = mutableListOf<YTItem>()
                    YouTube.album(album.id).onSuccess { page ->
                        page.otherVersions.let { items += it }
                    }
                    album.artists.firstOrNull()?.id?.let { artistId ->
                        YouTube.artist(artistId).onSuccess { page ->
                            page.sections.lastOrNull()?.items?.let { items += it }
                        }
                    }
                    SimilarRecommendation(
                        title = album,
                        items = items
                            .distinctBy { it.id }
                            .filterExplicit(hideExplicit)
                            .shuffled().take(10)
                            .ifEmpty { return@mapNotNull null }
                    )
                }

            _similarRecommendations.value = (artistRecommendations + songRecommendations + albumRecommendations).shuffled()
            _allYtItems.value = _similarRecommendations.value?.flatMap { it.items }.orEmpty() +
                    _homePage.value?.sections?.flatMap { it.items }.orEmpty()
        }
    }

    private val _isLoadingMore = MutableStateFlow(false)
    fun loadMoreYouTubeItems(continuation: String?) {
        if (continuation == null || _isLoadingMore.value) return
        val hideExplicit = context.dataStore.get(HideExplicitKey, false)
        val hideYoutubeShorts = context.dataStore.get(HideYoutubeShortsKey, false)

        viewModelScope.launch(Dispatchers.IO) {
            _isLoadingMore.value = true
            val nextSections = YouTube.home(continuation).getOrNull() ?: run {
                _isLoadingMore.value = false
                return@launch
            }

            _homePage.value = nextSections.copy(
                chips = _homePage.value?.chips,
                sections = (_homePage.value?.sections.orEmpty() + nextSections.sections).mapNotNull { section ->
                    val filteredItems = section.items
                        .filterOutNulls()
                        .filterExplicit(hideExplicit)
                        .filterYoutubeShorts(hideYoutubeShorts)
                    if (filteredItems.isEmpty()) null else section.copy(items = filteredItems)
                }
            )
            _isLoadingMore.value = false
        }
    }

    fun toggleChip(chip: HomePage.Chip?) {
        if (chip == null || chip == selectedChip.value && previousHomePage.value != null) {
            _homePage.value = previousHomePage.value
            previousHomePage.value = null
            selectedChip.value = null
            return
        }

        if (selectedChip.value == null) {
            previousHomePage.value = _homePage.value
        }

        viewModelScope.launch(Dispatchers.IO) {
            val hideExplicit = context.dataStore.get(HideExplicitKey, false)
            val hideYoutubeShorts = context.dataStore.get(HideYoutubeShortsKey, false)
            val nextSections = YouTube.home(params = chip.endpoint?.params).getOrNull() ?: return@launch

            _homePage.value = nextSections.copy(
                chips = _homePage.value?.chips,
                sections = nextSections.sections.mapNotNull { section ->
                    section.copy(items = section.items.filterOutNulls().filterExplicit(hideExplicit).filterYoutubeShorts(hideYoutubeShorts))
                }
            )
            selectedChip.value = chip

            // Fetch podcast-specific data when podcasts chip is selected
            if (chip.title.contains("Podcast", ignoreCase = true)) {
                fetchPodcastData()
            }
        }
    }

    private suspend fun fetchPodcastData() {
        // Fetch saved podcast shows from official API
        YouTube.savedPodcastShows().onSuccess { shows ->
            savedPodcastShows.value = shows.filterOutNulls()
        }.onFailure {
            reportException(it)
        }

        // Fetch episodes for later from official API
        YouTube.episodesForLater().onSuccess { episodes ->
            episodesForLater.value = episodes.filterOutNulls()
        }.onFailure {
            reportException(it)
        }
    }

    private suspend fun loadAccountInfo() {
        YouTube.accountInfo().onSuccess { info ->
            accountName.value = info.name
            accountImageUrl.value = info.thumbnailUrl
        }.onFailure {
            reportException(it)
        }
    }

    private suspend fun loadAccountPlaylists() {
        val hideYoutubeShorts = context.dataStore.get(HideYoutubeShortsKey, false)
        YouTube.library("FEmusic_liked_playlists").completed().onSuccess {
            accountPlaylists.value = it.items.filterIsInstance<PlaylistItem>()
                .filterOutNulls()
                .filterNot { it.id == "SE" }
                .filterYoutubeShorts(hideYoutubeShorts)
        }.onFailure {
            reportException(it)
        }
    }

    /**
     * Safely filters out null items from a list whose type says non-null
     * but may contain nulls at runtime due to JSON parsing.
     */
    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> List<T>.filterOutNulls(): List<T> =
        (this as List<T?>).filterNotNull()

    fun refresh() {
        if (isRefreshing.value) return
        isRefreshing.value = true
        viewModelScope.launch(Dispatchers.IO) {
            // If a chip is selected, reload the chip's content instead of the default home
            val currentChip = selectedChip.value
            if (currentChip != null) {
                val hideExplicit = context.dataStore.get(HideExplicitKey, false)
                val hideYoutubeShorts = context.dataStore.get(HideYoutubeShortsKey, false)
                val nextSections = YouTube.home(params = currentChip.endpoint?.params).getOrNull()
                if (nextSections != null) {
                    _homePage.value = nextSections.copy(
                        chips = _homePage.value?.chips,
                        sections = nextSections.sections.mapNotNull { section ->
                            section.copy(items = section.items.filterOutNulls().filterExplicit(hideExplicit).filterYoutubeShorts(hideYoutubeShorts))
                        }
                    )
                }
            } else {
                load()
            }
            isRefreshing.value = false
        }
        // Run sync when user manually refreshes
        viewModelScope.launch(Dispatchers.IO) {
            syncUtils.tryAutoSync()
        }
    }

    override fun onCleared() {
        super.onCleared()
        wrappedManager.dispose()
    }

    init {
        // Run sync in separate coroutine with cooldown to avoid blocking UI
        viewModelScope.launch(Dispatchers.IO) {
            syncUtils.tryAutoSync()
        }

        var wasOffline = !networkConnectivity.networkStatus.value
        viewModelScope.launch(Dispatchers.IO) {
            networkConnectivity.networkStatus.collect { isConnected ->
                if (!isConnected) {
                    wasOffline = true
                } else if (wasOffline) {
                    wasOffline = false
                    refresh()
                }
            }
        }

        // Prepare wrapped data in background
        viewModelScope.launch(Dispatchers.IO) {
            showWrappedCard.collect { shouldShow ->
                if (shouldShow && !wrappedManager.state.value.isDataReady) {
                    try {
                        wrappedManager.prepare()
                        val state = wrappedManager.state.first { it.isDataReady }
                        val trackMap = state.trackMap
                        if (trackMap.isNotEmpty()) {
                            val firstTrackId = trackMap.entries.first().value
                            wrappedAudioService.prepareTrack(firstTrackId)
                        }
                    } catch (e: Exception) {
                        reportException(e)
                    }
                }
            }
        }

        // Listen for cookie changes and reload account data
        viewModelScope.launch(Dispatchers.IO) {
            context.dataStore.data
                .map { it[InnerTubeCookieKey] to it[AccountNameKey] }
                .distinctUntilChanged()
                .collect { (cookie, savedAccountName) ->
                    if (!cookie.isNullOrEmpty()) {
                        YouTube.cookie = cookie
                        accountName.value = savedAccountName.orEmpty().ifBlank { "Guest" }
                        loadAccountInfo()
                    } else {
                        accountName.value = "Guest"
                        accountImageUrl.value = null
                        accountPlaylists.value = null
                    }
                }
        }

        // Listen for HideYoutubeShorts preference changes and reload account playlists instantly
        viewModelScope.launch(Dispatchers.IO) {
            context.dataStore.data
                .map { it[HideYoutubeShortsKey] ?: false }
                .distinctUntilChanged()
                .collect {
                    if (YouTube.cookie != null && accountPlaylists.value != null) {
                        loadAccountPlaylists()
                    }
                }
        }
    }

    private var isHomeDataLoaded = false

    fun loadHomeData() {
        if (isHomeDataLoaded) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val cookie = context.dataStore.data
                    .map { it[InnerTubeCookieKey] }
                    .distinctUntilChanged()
                    .first()

                if (!cookie.isNullOrEmpty()) {
                    YouTube.cookie = cookie
                }

                isHomeDataLoaded = true
                load()
            } catch (e: Exception) {
                isHomeDataLoaded = false
                Timber.e(e, "Failed to load home data")
            }
        }
    }
}
