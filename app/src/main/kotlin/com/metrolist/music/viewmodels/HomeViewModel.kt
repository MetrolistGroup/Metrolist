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
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.innertube.models.SongItem
import kotlinx.coroutines.flow.combine
import com.metrolist.innertube.models.WatchEndpoint
import com.metrolist.innertube.models.BrowseEndpoint
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
import com.metrolist.music.constants.ShowWrappedCardKey
import com.metrolist.music.constants.WrappedSeenKey
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.Album
import com.metrolist.music.db.entities.LocalItem
import com.metrolist.music.db.entities.Song
import com.metrolist.music.db.entities.SpeedDialItem
import com.metrolist.music.extensions.filterVideoSongs
import com.metrolist.music.models.SimilarRecommendation
import com.metrolist.music.ui.screens.wrapped.WrappedAudioService
import com.metrolist.music.ui.screens.wrapped.WrappedManager
import com.metrolist.music.utils.NetworkConnectivityObserver
import com.metrolist.music.utils.SyncUtils
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.safeDataStoreEdit
import com.metrolist.music.utils.get
import com.metrolist.music.utils.reportException
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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

    // Personal Quick Picks are generated only on Home load/refresh and remain stable for the session.
    val quickPicks = MutableStateFlow<List<YTItem>?>(null)
    val dailyDiscover = MutableStateFlow<List<DailyDiscoverItem>?>(null)
    val forgottenFavorites = MutableStateFlow<List<Song>?>(null)
    val keepListening = MutableStateFlow<List<LocalItem>?>(null)
    val similarRecommendations = MutableStateFlow<List<SimilarRecommendation>?>(null)
    val accountPlaylists = MutableStateFlow<List<PlaylistItem>?>(null)
    val homePage = MutableStateFlow<HomePage?>(null)
    val explorePage = MutableStateFlow<ExplorePage?>(null)
    val communityPlaylists = MutableStateFlow<List<CommunityPlaylistItem>?>(null)
    val selectedChip = MutableStateFlow<HomePage.Chip?>(null)
    private val previousHomePage = MutableStateFlow<HomePage?>(null)

    // Official API data for podcast sections
    val savedPodcastShows = MutableStateFlow<List<com.metrolist.innertube.models.PodcastItem>>(emptyList())
    val episodesForLater = MutableStateFlow<List<SongItem>>(emptyList())

    val allLocalItems = MutableStateFlow<List<LocalItem>>(emptyList())
    val allYtItems = MutableStateFlow<List<YTItem>>(emptyList())

    // Speed Dial is an access surface, not a recommendation feed:
    // pins -> most played -> liked -> saved playlists -> recent -> resume.
    private val sessionRecentlyPlayedSpeedDial = MutableStateFlow<List<YTItem>>(emptyList())

    fun recordSpeedDialPlay(item: YTItem?) {
        if (item == null || item.id.isBlank()) return
        sessionRecentlyPlayedSpeedDial.value =
            listOf(item) + sessionRecentlyPlayedSpeedDial.value.filterNot { it.id == item.id }.take(11)
    }

    val pinnedSpeedDialItems: StateFlow<List<SpeedDialItem>> =
        database.speedDialDao.getAll()
            .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val recentlyPlayedSongs =
        database.recentlyPlayedSongs(limit = 20)
            .map { songs -> songs.map(::songToSpeedDialItem) }

    private val frequentlyPlayedSongs =
        database.mostPlayedSongs(
            fromTimeStamp = LocalDateTime.now().minusWeeks(4),
            limit = 20,
            offset = 0,
            toTimeStamp = LocalDateTime.now(),
        ).map { songs -> songs.map(::songToSpeedDialItem) }

    private val recentlyLikedSongs =
        database.likedSongsByCreateDateAsc()
            .map { songs -> songs.asReversed().take(20).map(::songToSpeedDialItem) }

    private val savedSpeedDialPlaylists =
        accountPlaylists.map { it.orEmpty() }

    private val combinedRecentSpeedDialItems =
        combine(
            sessionRecentlyPlayedSpeedDial,
            recentlyPlayedSongs,
        ) { session, persisted ->
            (session + persisted).distinctBy { it.id }
        }

    private val automaticSpeedDialSources =
        combine(
            frequentlyPlayedSongs,
            recentlyLikedSongs,
            savedSpeedDialPlaylists,
        ) { frequent, liked, playlists ->
            Triple(frequent, liked, playlists)
        }

    val speedDialItems: StateFlow<List<YTItem>> =
        combine(
            database.speedDialDao.getAll(),
            automaticSpeedDialSources,
            combinedRecentSpeedDialItems,
            keepListening,
        ) { pinned, automatic, recent, keepListening ->
            val resumeItems = keepListening.orEmpty().mapNotNull { item ->
                when (item) {
                    is Song -> songToSpeedDialItem(item)
                    is Album -> AlbumItem(
                        browseId = item.id,
                        playlistId = item.album.playlistId ?: "",
                        title = item.title,
                        artists = item.artists.map { Artist(name = it.name, id = it.id) },
                        year = item.album.year,
                        thumbnail = item.thumbnailUrl ?: "",
                    )
                    is com.metrolist.music.db.entities.Artist -> ArtistItem(
                        id = item.id,
                        title = item.title,
                        thumbnail = item.thumbnailUrl,
                        shuffleEndpoint = null,
                        radioEndpoint = null,
                    )
                    else -> null
                }
            }

            buildPersonalSpeedDialItems(
                pinned = pinned.map { it.toYTItem() },
                mostPlayed = automatic.first,
                liked = automatic.second,
                savedPlaylists = automatic.third,
                recent = recent,
                resume = resumeItems,
            )
        }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private fun songToSpeedDialItem(song: Song): YTItem =
        SongItem(
            id = song.id,
            title = song.title,
            artists = song.artists.map { Artist(name = it.name, id = it.id) },
            thumbnail = song.thumbnailUrl ?: "",
        )

    suspend fun getRandomItem(): YTItem? {
        try {
            isRandomizing.value = true
            // Visual feedback for the animation
            kotlinx.coroutines.delay(1000)

            val userSongs = mutableListOf<YTItem>()
            val otherSources = mutableListOf<YTItem>()

            quickPicks.value?.let { songs ->
                userSongs.addAll(songs)
            }

            keepListening.value?.let { items ->
                items.forEach { item ->
                    when (item) {
                        is Song -> userSongs.add(SongItem(
                            id = item.id,
                            title = item.title,
                            artists = item.artists.map { Artist(name = it.name, id = it.id) },
                            thumbnail = item.thumbnailUrl ?: "",
                            explicit = false
                        ))
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

            otherSources.addAll(allYtItems.value)

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
        val hideVideoSongs = context.dataStore.get(HideVideoSongsKey, false)
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
                                .filter { item ->
                                    if (hideVideoSongs && item.isVideoSong) return@filter false
                                    if (item.explicit) return@filter false
                                    true
                                }
                                .shuffled()

                            // Simple check to avoid immediate duplicate of seed
                            val recommendation = recommendations.firstOrNull { rec ->
                                rec.id != seed.id
                            }

                            if (recommendation != null) {
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
        
        // Final deduplication just in case multiple seeds recommended the same song
        dailyDiscover.value = items.toList().distinctBy { it.recommendation.id }.shuffled()
    }

    /**
     * Builds our own Quick Picks shelf.
     *
     * The list is generated once per Home load, so playback/recomposition does not reshuffle it.
     * Manual Home refresh and a new HomeViewModel generate a new selection.
     */
    private suspend fun getQuickPicks() {
        val hideVideoSongs = context.dataStore.get(HideVideoSongsKey, false)
        val now = LocalDateTime.now()
        val fromTimeStamp = now.minusWeeks(12)

        val familiarSongs = database
            .mostPlayedSongs(
                fromTimeStamp = fromTimeStamp,
                limit = 40,
                offset = 0,
                toTimeStamp = now,
            ).first()
            .filterVideoSongs(hideVideoSongs)

        val recentSongs = database
            .recentlyPlayedSongs(limit = 30)
            .first()
            .filterVideoSongs(hideVideoSongs)

        val likedSongs = database
            .likedSongsByCreateDateAsc()
            .first()
            .asReversed()
            .filterVideoSongs(hideVideoSongs)
            .take(30)

        val relatedSongs =
            coroutineScope {
                seedSongs.map { seed ->
                    async(Dispatchers.IO) {
                        database
                            .getRelatedSongs(seed.id)
                            .first()
                            .filterVideoSongs(hideVideoSongs)
                    }
                }.awaitAll().flatten().distinctBy { it.id }
            }

val forgottenSongs = database
            .forgottenFavorites()
            .first()
            .filterVideoSongs(hideVideoSongs)
            .take(20)

        val seedSongs =
            (familiarSongs.take(6) + recentSongs.take(6) + likedSongs.take(6))
                .distinctBy { it.id }
                .take(8)

        val youtubeRelated =
            coroutineScope {
                seedSongs.map { seed ->
                    async(Dispatchers.IO) {
                        val endpoint =
                            YouTube
                                .next(WatchEndpoint(videoId = seed.id))
                                .getOrNull()
                                ?.relatedEndpoint
                                ?: return@async emptyList<YTItem>()

                        YouTube
                            .related(endpoint)
                            .getOrNull()
                            ?.songs
                            .orEmpty()
                            .filter { item ->
                                !item.explicit && (!hideVideoSongs || !item.isVideoSong)
                            }
                            .take(12)
                            .map { it as YTItem }
                    }
                }.awaitAll().flatten()
            }

        val freshSeed = System.currentTimeMillis()

        quickPicks.value =
            PersonalQuickPicksEngine.build(
                familiar = familiarSongs.map(::songToSpeedDialItem) + recentSongs.take(10).map(::songToSpeedDialItem),
                related = relatedSongs.map(::songToSpeedDialItem) + youtubeRelated,
                liked = likedSongs.map(::songToSpeedDialItem),
                discovery = forgottenSongs.map(::songToSpeedDialItem) + youtubeRelated.take(24),
                seed = freshSeed,
                limit = 20,
            )
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

        communityPlaylists.value = playlists.shuffled()
    }

    private suspend fun load(regeneratePersonalQuickPicks: Boolean = true) {
        isLoading.value = true
        val hideExplicit = context.dataStore.get(HideExplicitKey, false)
        val hideVideoSongs = context.dataStore.get(HideVideoSongsKey, false)
        val hideYoutubeShorts = context.dataStore.get(HideYoutubeShortsKey, false)
        val fromTimeStamp = LocalDateTime.now().minusWeeks(2)

        // Phase 1: Load essential sections in parallel — local DB (fast) + YouTube home page.
        // isLoading is set to false as soon as all Phase 1 tasks complete so the UI appears quickly.
        coroutineScope {
            if (regeneratePersonalQuickPicks) {
                launch(Dispatchers.IO) { getQuickPicks() }
            }

            launch(Dispatchers.IO) {
                forgottenFavorites.value = database.forgottenFavorites().first()
                    .filterVideoSongs(hideVideoSongs).shuffled().take(20)
            }

            launch(Dispatchers.IO) {
                val songs = database.mostPlayedSongs(fromTimeStamp = fromTimeStamp, limit = 15, offset = 5, toTimeStamp = LocalDateTime.now()).first()
                    .filterVideoSongs(hideVideoSongs).shuffled().take(10)
                val albums = database.mostPlayedAlbums(fromTimeStamp, limit = 8, offset = 2).first()
                    .filter { it.album.thumbnailUrl != null }.shuffled().take(5)
                val artists = database.mostPlayedArtists(fromTimeStamp).first()
                    .filter { it.artist.isYouTubeArtist && it.artist.thumbnailUrl != null }.shuffled().take(5)
                keepListening.value = (songs + albums + artists).shuffled()
            }

            launch(Dispatchers.IO) {
                YouTube.home().onSuccess { page ->
                    homePage.value = page.copy(
                        sections = page.sections.mapNotNull { section ->
                            val filtered = section.items
                                .filterOutNulls()
                                .filterExplicit(hideExplicit)
                                .filterVideoSongs(hideVideoSongs)
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

        // Quick Picks now contains YouTube items (including unseen recommendations), so it is
        // intentionally excluded from the LocalItem cache used by legacy helpers.
        allLocalItems.value = (forgottenFavorites.value.orEmpty() + keepListening.value.orEmpty())
            .filter { it is Song || it is Album }
        isLoading.value = false

        // Phase 2: Heavy multi-request operations — run in background without blocking the UI.
        viewModelScope.launch(Dispatchers.IO) { kotlinx.coroutines.delay(800); getDailyDiscover() }

        viewModelScope.launch(Dispatchers.IO) { kotlinx.coroutines.delay(1000); getCommunityPlaylists() }

        viewModelScope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(900)
            YouTube.explore().onSuccess { page ->
                explorePage.value = page.copy(
                    newReleaseAlbums = page.newReleaseAlbums.filterOutNulls().filterExplicit(hideExplicit),
                    moodAndGenres = page.moodAndGenres.filterOutNulls()
                )
            }.onFailure { reportException(it) }
        }

        viewModelScope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(1200)
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
                            .filterVideoSongs(hideVideoSongs)
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
                            .filterVideoSongs(hideVideoSongs)
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
                            .filterVideoSongs(hideVideoSongs)
                            .shuffled().take(10)
                            .ifEmpty { return@mapNotNull null }
                    )
                }

            similarRecommendations.value = (artistRecommendations + songRecommendations + albumRecommendations).shuffled()
            allYtItems.value = similarRecommendations.value?.flatMap { it.items }.orEmpty() +
                    homePage.value?.sections?.flatMap { it.items }.orEmpty()
        }
    }

    private val _isLoadingMore = MutableStateFlow(false)
    fun loadMoreYouTubeItems(continuation: String?) {
        if (continuation == null || _isLoadingMore.value) return
        val hideExplicit = context.dataStore.get(HideExplicitKey, false)
        val hideVideoSongs = context.dataStore.get(HideVideoSongsKey, false)
        val hideYoutubeShorts = context.dataStore.get(HideYoutubeShortsKey, false)

        viewModelScope.launch(Dispatchers.IO) {
            _isLoadingMore.value = true
            val nextSections = YouTube.home(continuation).getOrNull() ?: run {
                _isLoadingMore.value = false
                return@launch
            }

            homePage.value = nextSections.copy(
                chips = homePage.value?.chips,
                sections = (homePage.value?.sections.orEmpty() + nextSections.sections).mapNotNull { section ->
                    val filteredItems = section.items
                        .filterOutNulls()
                        .filterExplicit(hideExplicit)
                        .filterVideoSongs(hideVideoSongs)
                        .filterYoutubeShorts(hideYoutubeShorts)
                    if (filteredItems.isEmpty()) null else section.copy(items = filteredItems)
                }
            )
            _isLoadingMore.value = false
        }
    }

    fun toggleChip(chip: HomePage.Chip?) {
        if (chip == null || chip == selectedChip.value && previousHomePage.value != null) {
            homePage.value = previousHomePage.value
            previousHomePage.value = null
            selectedChip.value = null
            return
        }

        if (selectedChip.value == null) {
            previousHomePage.value = homePage.value
        }

        viewModelScope.launch(Dispatchers.IO) {
            val hideExplicit = context.dataStore.get(HideExplicitKey, false)
            val hideVideoSongs = context.dataStore.get(HideVideoSongsKey, false)
            val hideYoutubeShorts = context.dataStore.get(HideYoutubeShortsKey, false)
            val nextSections = YouTube.home(params = chip.endpoint?.params).getOrNull() ?: return@launch

            homePage.value = nextSections.copy(
                chips = homePage.value?.chips,
                sections = nextSections.sections.mapNotNull { section ->
                    section.copy(items = section.items.filterOutNulls().filterExplicit(hideExplicit).filterVideoSongs(hideVideoSongs).filterYoutubeShorts(hideYoutubeShorts))
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

    fun refresh(regeneratePersonalQuickPicks: Boolean = true) {
        if (isRefreshing.value) return
        isRefreshing.value = true
        viewModelScope.launch(Dispatchers.IO) {
            // If a chip is selected, reload the chip's content instead of the default home
            val currentChip = selectedChip.value
            if (currentChip != null) {
                val hideExplicit = context.dataStore.get(HideExplicitKey, false)
                val hideVideoSongs = context.dataStore.get(HideVideoSongsKey, false)
                val hideYoutubeShorts = context.dataStore.get(HideYoutubeShortsKey, false)
                val nextSections = YouTube.home(params = currentChip.endpoint?.params).getOrNull()
                if (nextSections != null) {
                    homePage.value = nextSections.copy(
                        chips = homePage.value?.chips,
                        sections = nextSections.sections.mapNotNull { section ->
                            section.copy(items = section.items.filterOutNulls().filterExplicit(hideExplicit).filterVideoSongs(hideVideoSongs).filterYoutubeShorts(hideYoutubeShorts))
                        }
                    )
                }
            } else {
                load(regeneratePersonalQuickPicks)
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
                    // Reconnect should refresh server-backed sections without generating
                    // a new personal Quick Picks order mid-session.
                    refresh(regeneratePersonalQuickPicks = false)
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
