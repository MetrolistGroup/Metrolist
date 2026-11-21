package com.metrolist.shared.viewmodels

import androidx.compose.runtime.Stable
import androidx.compose.runtime.snapshotFlow
import com.metrolist.innertubex.InnerTube
import com.metrolist.shared.auth.AuthManager
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.db.entities.Song
import com.metrolist.shared.db.entities.SpeedDialItem
import com.metrolist.shared.db.entities.toSongItem
import com.metrolist.shared.db.entities.withoutHistoryArtistPlaceholder
import com.metrolist.shared.db.refreshSpeedDialArtwork
import com.metrolist.shared.deviceconnect.DeviceCrypto
import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.model.ArtistItem
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.BrowseItem
import com.metrolist.shared.model.Chip
import com.metrolist.shared.model.EpisodeItem
import com.metrolist.shared.model.HomePageData
import com.metrolist.shared.model.HomeSection
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.model.PodcastItem
import com.metrolist.shared.model.SearchFilter
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.UserPlaylist
import com.metrolist.shared.model.YTItem
import com.metrolist.shared.model.artworkExplicitness
import com.metrolist.shared.model.canonicalHomeItemId
import com.metrolist.shared.model.filterPodcastChips
import com.metrolist.shared.model.isLibrarySection
import com.metrolist.shared.model.isNonMusicalVideo
import com.metrolist.shared.model.isPodcast
import com.metrolist.shared.model.isPodcastChip
import com.metrolist.shared.model.mergeAndDeduplicateHomeSections
import com.metrolist.shared.model.toSongItem
import com.metrolist.shared.model.withoutRegionalCharts
import com.metrolist.shared.settings.AiSlopBlocklist
import com.metrolist.shared.settings.BlockedContent
import com.metrolist.shared.settings.HiddenHomeItems
import com.metrolist.shared.settings.HomeContentFilter
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.state.GlobalYouTube
import com.metrolist.shared.state.GlobalYouTubeRepository
import com.metrolist.shared.ui.components.AUTO_PLAYLIST_ICON_LIKED
import com.metrolist.shared.ui.screens.HomeShelfCache
import com.metrolist.shared.utils.Logger
import com.metrolist.shared.utils.getCurrentTimeMillis
import com.metrolist.shared.utils.withArtworkCacheRevision
import com.metrolist.shared.youtube.playback.PlaybackNetworkStateStore
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json
import kotlin.concurrent.Volatile
import kotlin.io.encoding.Base64
import kotlin.time.TimeSource

private const val HOME_CHIP_FRESHNESS_MS = 120_000L
private const val HOME_CHIP_CACHE_SIZE = 6
private const val HOME_LOG_TAG = "HomeViewModel"

private const val HOME_SNAPSHOT_SECTIONS = 8
private const val HOME_SNAPSHOT_ITEMS = 24

private class HomeLibraryRevisionChangedException : Exception()

internal data class HomeUserPlaylistSnapshot(
    val revision: Long,
    val playlists: List<UserPlaylist>,
)

internal fun HomeViewModel.HomeUiState.startupSnapshot(): HomeViewModel.HomeUiState {
    fun List<HomeSection>.bounded() = take(HOME_SNAPSHOT_SECTIONS).map { it.copy(items = it.items.take(HOME_SNAPSHOT_ITEMS)) }
    return copy(
        data = data?.bounded(),
        discoverySections = discoverySections.bounded(),
        sections = sections.ifEmpty { uncategorizedHomeSections(data.orEmpty()) }.bounded(),
        // A continuation from the full feed would skip shelves omitted from this startup snapshot.
        continuation = null,
        trending = trending.take(HOME_SNAPSHOT_ITEMS),
        albumCharts = albumCharts.take(HOME_SNAPSHOT_ITEMS),
        quickPicks = quickPicks.take(HOME_SNAPSHOT_ITEMS),
        speedDialItems =
            speedDialItems.filter { it.id in speedDialPinnedIds } +
                speedDialItems.filterNot { it.id in speedDialPinnedIds }.take(HOME_SNAPSHOT_ITEMS),
        newReleases = newReleases.take(HOME_SNAPSHOT_ITEMS),
        keepListening = keepListening.take(HOME_SNAPSHOT_ITEMS),
        forgottenFavorites = forgottenFavorites.take(HOME_SNAPSHOT_ITEMS),
        communityPlaylist = communityPlaylist.take(HOME_SNAPSHOT_ITEMS),
        moodsAndGenres = moodsAndGenres.take(HOME_SNAPSHOT_ITEMS),
        recentlyPlayed = recentlyPlayed.take(HOME_SNAPSHOT_ITEMS),
        recapPlaylists = recapPlaylists.take(HOME_SNAPSHOT_ITEMS),
        podcasts = podcasts.take(HOME_SNAPSHOT_ITEMS),
        similarRecommendations =
            similarRecommendations.take(HOME_SNAPSHOT_SECTIONS).map {
                it.copy(items = it.items.take(HOME_SNAPSHOT_ITEMS))
            },
    )
}

internal val homeCacheJson =
    Json {
        ignoreUnknownKeys = true
        classDiscriminator = "_type"
    }

internal fun HomeViewModel.HomeUiState.asOfflineSnapshot(): HomeViewModel.HomeUiState =
    copy(
        isLoading = false,
        isRefreshing = false,
        isRevalidating = false,
        isContentLoading = false,
        isLoadingMore = false,
        isAllContentPending = false,
        error = null,
    )

internal fun HomeViewModel.HomeUiState.loadingChip(chip: Chip): HomeViewModel.HomeUiState =
    pendingHomeChipContent(this, chip).copy(isLoading = true)

internal fun HomeViewModel.HomeUiState.primaryHomeContentLoaded(
    sections: List<HomeSection>,
    chips: List<Chip>,
    userPlaylists: List<UserPlaylist>,
): HomeViewModel.HomeUiState =
    copy(
        data = sections,
        userPlaylists = userPlaylists,
        discoverySections = emptyList(),
        isLoading = false,
        isRefreshing = false,
        isRevalidating = false,
        isContentLoading = false,
        isLoadingMore = false,
        error = null,
        isAllContentPending = selectedChip == null,
        chips = chips,
        continuation = null,
    )

internal fun List<ArtistTiny>.withFallbackMetadata(fallback: List<ArtistTiny>): List<ArtistTiny> {
    val fallbackArtists = fallback.associateBy { it.name }
    return ifEmpty { fallback }.map { artist ->
        val fallbackArtist = fallbackArtists[artist.name]
        artist.copy(
            id = artist.id.withoutHistoryArtistPlaceholder() ?: fallbackArtist?.id.withoutHistoryArtistPlaceholder(),
            thumbnail = artist.thumbnail ?: fallbackArtist?.thumbnail,
        )
    }
}

internal fun SongItem.withFallbackMetadata(fallback: SongItem?): SongItem {
    val mergedArtists = artists.withFallbackMetadata(fallback?.artists.orEmpty())
    if (fallback == null) return if (mergedArtists == artists) this else copy(artists = mergedArtists)
    val mergedAlbum =
        album?.let { current ->
            fallback.album?.let { stored ->
                val sameAlbum = current.name.isBlank() || current.name == stored.name
                current.copy(
                    id = current.id?.takeIf { it.isNotBlank() } ?: stored.id.takeIf { sameAlbum },
                    name = current.name.ifBlank { stored.name },
                )
            } ?: current
        } ?: fallback.album
    return copy(
        thumbnail = thumbnail?.takeIf { it.isNotBlank() } ?: fallback.thumbnail,
        artists = mergedArtists,
        album = mergedAlbum,
        duration = duration?.takeIf { it > 0 } ?: fallback.duration,
        isExplicit = isExplicit || fallback.isExplicit,
        endpointParams = endpointParams ?: fallback.endpointParams,
        libraryAddToken = libraryAddToken ?: fallback.libraryAddToken,
        libraryRemoveToken = libraryRemoveToken ?: fallback.libraryRemoveToken,
        musicVideoType = musicVideoType ?: fallback.musicVideoType,
        isUploaded = isUploaded || fallback.isUploaded,
        isLocal = isLocal || fallback.isLocal,
        uploadEntityId = uploadEntityId ?: fallback.uploadEntityId,
        playlistSetVideoId = playlistSetVideoId ?: fallback.playlistSetVideoId,
        podcastId = podcastId ?: fallback.podcastId,
        year = year ?: fallback.year,
        customTitle = customTitle ?: fallback.customTitle,
        customArtist = customArtist ?: fallback.customArtist,
        canonicalTitle = canonicalTitle ?: fallback.canonicalTitle,
        canonicalArtists = canonicalArtists ?: fallback.canonicalArtists,
        explicitness = if (isExplicit || fallback.isExplicit) true else artworkExplicitness ?: fallback.artworkExplicitness,
    )
}

internal suspend fun <T> homeRequestResult(request: suspend () -> T): Result<T> = homeRequestResult("browse", request)

internal suspend fun <T> homeRequestResult(
    requestName: String,
    request: suspend () -> T,
): Result<T> {
    val startedAt = TimeSource.Monotonic.markNow()
    return withContext(Dispatchers.Default) {
        try {
            Result.success(request())
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(error)
        } finally {
            Logger.d(HOME_LOG_TAG, "$requestName completed elapsedMs=${startedAt.elapsedNow().inWholeMilliseconds}")
        }
    }
}

class HomeViewModel(
    private val database: MusicDatabase,
    private val scope: CoroutineScope,
    private val httpClient: HttpClient = GlobalYouTube.client,
    var likedSongsName: String,
) {
    val shelfCache = HomeShelfCache()

    private val _uiState =
        MutableStateFlow(
            HomeUiState(isLoading = true, isRestoringCache = true, speedDialSurprisePinned = Settings.shared.speedDialSurprisePinned),
        )
    val uiState: StateFlow<HomeUiState> =
        combine(
            _uiState,
            database
                .dao()
                .albumsLikedByCreateDateAsc()
                .map { albums ->
                    albums.mapTo(mutableSetOf()) { it.album.id }
                }.distinctUntilChanged(),
            snapshotFlow { Settings.shared.hiddenHomeItemsJson }.map { HomeContentFilter(HiddenHomeItems.all()) },
        ) { state, savedAlbumIds, homeFilter ->
            state
                .copy(
                    sections = state.sections.withLibraryMusicAlbumsOnly(savedAlbumIds),
                    chipFeeds =
                        state.chipFeeds.map {
                            it.copy(sections = homeFilter.filterSections(it.sections.withLibraryMusicAlbumsOnly(savedAlbumIds)))
                        },
                ).withoutHiddenHomeItems(homeFilter)
        }.stateIn(scope, SharingStarted.Eagerly, _uiState.value)

    @Volatile private var cachedAllContent: HomeUiState? = null

    private val publishedPrimaryAllContent = MutableStateFlow<Pair<Long, HomeUiState>?>(null)

    private val publishedPrimaryChipContent = MutableStateFlow<Pair<Long, HomeUiState>?>(null)

    @Volatile private var cachedSelectedChipContent: HomeUiState? = null

    @Volatile private var chipContentCache: Map<HomeChipKey, HomeUiState> = emptyMap()
    private var chipUpdatedAt: Map<HomeChipKey, Long> = emptyMap()

    @Volatile private var lastKnownLocaleSignature: String =
        GlobalYouTube.innerTube.locale.let { "${it.hl}:${it.gl}" }

    @Volatile private var speedDialRecommendationItems: List<YTItem> = emptyList()
    private var paginationJob: Job? = null
    private var loadJob: Job? = null

    @Volatile private var contentLoadGeneration = 0L

    @Volatile private var librarySnapshotRevision = 0L

    @Volatile private var latestSpeedDialPins: List<SpeedDialItem> = emptyList()

    @Volatile private var latestSpeedDialItemMetadata: Map<String, YTItem> = emptyMap()
    private var artworkCacheRevision = 0L
    private val initialSpeedDialPins = CompletableDeferred<Unit>()

    private val paginationInProgress = MutableStateFlow(false)
    private var allContentReloadRequired = false
    private val chipFeedPager =
        HomeChipFeedPager(
            loadFirst = { chip ->
                val page = GlobalYouTubeRepository.instance.getHomePageData(chip.params, chip.browseId ?: "FEmusic_home")
                page.copy(sections = prepareChipSections(page.sections))
            },
            loadContinuation = { chip, token ->
                val (sections, next) =
                    GlobalYouTubeRepository.instance.getHomeSectionsContinuation(
                        token,
                        isPersonal = chip.browseId == null || chip.browseId == "FEmusic_home",
                        loadAllPages = false,
                    )
                HomePageData(sections = prepareChipSections(sections), continuation = next)
            },
        )

    init {
        AuthManager().restorePersistedSession()
        val restoreGeneration = contentLoadGeneration
        scope.launch {
            snapshotFlow { Settings.shared.classicRecommendations }
                .drop(1)
                .collect {
                    // A load started before this change may still publish Quick picks
                    // selected with the old value; wait for it to settle first.
                    withTimeoutOrNull(10_000) {
                        _uiState.first { state ->
                            !state.isLoading && !state.isRefreshing && !state.isRevalidating &&
                                !state.isContentLoading && !state.isLoadingMore
                        }
                    }
                    rebuildQuickPicks()
                }
        }
        scope.launch {
            val cacheStart = TimeSource.Monotonic.markNow()
            val restoredState = withContext(Dispatchers.Default) { loadLastHomePage() }
            if (restoredState != null && restoreGeneration == contentLoadGeneration &&
                restoredState.sessionKey == GlobalYouTube.innerTube.sessionSnapshot().homeCacheSessionKey()
            ) {
                val userPlaylistIds = restoredState.userPlaylists.map { it.id.removePrefix("VL") }.toSet()
                speedDialRecommendationItems =
                    restoredState
                        .speedDialUnpinnedItems()
                        .filterNot { item ->
                            item is PlaylistItem && (item.isEditable || canonicalHomeItemId(item) in userPlaylistIds)
                        }
                latestSpeedDialItemMetadata =
                    restoredState.speedDialItems
                        .associateBy { it.id }
                val restored =
                    restoredState.copy(
                        isLoading = !restoredState.hasHomeFeedContent(),
                        isRefreshing = false,
                        isRevalidating = false,
                        isLoadingMore = false,
                        error = null,
                        speedDialSurprisePinned = Settings.shared.speedDialSurprisePinned,
                    )
                _uiState.value =
                    restored.withoutBlockedContent().let {
                        it.copy(isLoading = !it.hasHomeFeedContent(), isRestoringCache = true)
                    }
                if (restoredState.selectedChip != null) {
                    cachedSelectedChipContent = restored
                } else if (!restoredState.isAllContentPending) {
                    val locale = GlobalYouTube.innerTube.locale
                    lastKnownLocaleSignature = "${locale.hl}:${locale.gl}"
                    cachedAllContent = restored
                }
                // The snapshot may have been written with the other
                // recommendations mode; rebuild picks for the current setting.
                rebuildQuickPicks()
                scope.launch {
                    val restoredMetadata =
                        database
                            .dao()
                            .getSongsByIds(restoredState.loadedHomeSongs().map { it.id })
                            .associate { it.id to it.toSongItem() }
                    _uiState.update { state ->
                        val hydratedState = state.withLocalSongMetadata(restoredMetadata).withoutBlockedContent()
                        if (hydratedState.selectedChip == null && !hydratedState.isAllContentPending) {
                            val hydratedUserPlaylistIds = hydratedState.userPlaylists.map { it.id.removePrefix("VL") }.toSet()
                            speedDialRecommendationItems =
                                hydratedState
                                    .speedDialUnpinnedItems()
                                    .filterNot { item ->
                                        item is PlaylistItem && (item.isEditable || canonicalHomeItemId(item) in hydratedUserPlaylistIds)
                                    }
                        }
                        hydratedState
                    }
                    cachedAllContent = cachedAllContent?.withLocalSongMetadata(restoredMetadata)
                    cachedSelectedChipContent = cachedSelectedChipContent?.withLocalSongMetadata(restoredMetadata)
                    Logger.d(HOME_LOG_TAG, "cached metadata hydrated songs=${restoredMetadata.size}")
                }
            }

            Logger.d(
                HOME_LOG_TAG,
                "startup cache restored=${restoredState != null} elapsedMs=${cacheStart.elapsedNow().inWholeMilliseconds}",
            )

            scope.launch {
                database.speedDialDao().getAll().collect { pinned ->
                    latestSpeedDialPins = pinned
                    val storedSongs =
                        database
                            .dao()
                            .getSongsByIds(pinned.filter { it.type == "SONG" }.map { it.id })
                            .associate { it.id to it.toSongItem() }
                    latestSpeedDialItemMetadata =
                        pinned
                            .mapNotNull { pin ->
                                val cached = latestSpeedDialItemMetadata[pin.id]
                                val stored = storedSongs[pin.id]
                                val metadata =
                                    if (cached is SongItem && stored != null) {
                                        cached.withFallbackMetadata(stored)
                                    } else if (cached is PlaylistItem) {
                                        cached.copy(thumbnail = pin.thumbnailUrl ?: cached.thumbnail)
                                    } else {
                                        cached ?: stored
                                    }
                                metadata?.let { pin.id to it }
                            }.toMap()
                    Logger.d(
                        HOME_LOG_TAG,
                        "speed dial metadata pins=${pinned.size} hydrated=${latestSpeedDialItemMetadata.size}",
                    )
                    _uiState.update { state ->
                        val withPins =
                            if (shouldApplyGlobalSpeedDialPins(state.selectedChip)) {
                                state.withSpeedDialPins(pinned, speedDialRecommendationItems).withoutBlockedContent()
                            } else {
                                state
                            }
                        withPins.copy(isRestoringCache = false)
                    }
                    if (initialSpeedDialPins.complete(Unit)) {
                        Logger.d(HOME_LOG_TAG, "startup cache ready elapsedMs=${cacheStart.elapsedNow().inWholeMilliseconds}")
                    }
                    cachedAllContent =
                        cachedAllContent?.let { cached ->
                            cached.withSpeedDialPins(pinned, cached.speedDialUnpinnedItems())
                        }
                    publishedPrimaryAllContent.update { primary ->
                        primary?.let { (generation, state) ->
                            generation to state.withSpeedDialPins(pinned, state.speedDialUnpinnedItems())
                        }
                    }
                    publishedPrimaryChipContent.update { primary ->
                        primary?.let { (generation, state) ->
                            generation to state.withSpeedDialPins(pinned, state.speedDialUnpinnedItems())
                        }
                    }
                }
            }

            scope.launch(Dispatchers.Default) {
                val attemptedArtwork = mutableSetOf<String>()
                val artworkRefreshTimes =
                    runCatching { Json.decodeFromString<Map<String, Long>>(Settings.shared.speedDialArtworkRefreshTimes) }
                        .getOrDefault(emptyMap())
                        .toMutableMap()
                combine(
                    database.dao().allPlaylists(),
                    snapshotFlow { Settings.shared.lastLibraryRefreshAccount },
                ) { _, _ -> Unit }.collectLatest {
                    val revision = ++librarySnapshotRevision
                    val snapshot = fetchUserPlaylists()
                    if (!isCurrentHomeLibraryRevision(snapshot.revision, librarySnapshotRevision) || snapshot.revision != revision) {
                        return@collectLatest
                    }
                    val playlists = snapshot.playlists

                    fun HomeUiState.withLibrarySnapshot(): HomeUiState {
                        val ids = playlists.mapTo(mutableSetOf()) { it.id.removePrefix("VL") }
                        return copy(
                            userPlaylists = playlists,
                            speedDialItems =
                                speedDialItems.filterNot {
                                    it is PlaylistItem && it.id !in speedDialPinnedIds && canonicalHomeItemId(it) in ids
                                },
                        )
                    }
                    val ids = playlists.mapTo(mutableSetOf()) { it.id.removePrefix("VL") }
                    speedDialRecommendationItems =
                        speedDialRecommendationItems.filterNot { it is PlaylistItem && canonicalHomeItemId(it) in ids }
                    _uiState.update { it.withLibrarySnapshot() }
                    cachedAllContent = cachedAllContent?.withLibrarySnapshot()
                    cachedSelectedChipContent = cachedSelectedChipContent?.withLibrarySnapshot()
                    publishedPrimaryAllContent.update { primary ->
                        primary?.let { (generation, state) -> generation to state.withLibrarySnapshot() }
                    }
                    publishedPrimaryChipContent.update { primary ->
                        primary?.let { (generation, state) -> generation to state.withLibrarySnapshot() }
                    }
                    chipContentCache = chipContentCache.mapValues { it.value.withLibrarySnapshot() }
                    val known =
                        playlists
                            .mapNotNull { playlist ->
                                playlist.thumbnailUrl?.let { playlist.id.removePrefix("VL") to it }
                            }.toMap()
                    initialSpeedDialPins.await()
                    val attemptedThisRefresh = mutableSetOf<String>()
                    try {
                        database.refreshSpeedDialArtwork(known, artworkRefreshTimes) { id ->
                            if (attemptedArtwork.add(id)) {
                                attemptedThisRefresh.add(id)
                                try {
                                    GlobalYouTubeRepository.instance
                                        .getPlaylistPages(id)
                                        .first()
                                        .sections["header"]
                                        ?.filterIsInstance<PlaylistItem>()
                                        ?.firstOrNull()
                                } catch (error: CancellationException) {
                                    attemptedArtwork.remove(id)
                                    throw error
                                }
                            } else {
                                null
                            }
                        }
                    } catch (error: CancellationException) {
                        attemptedArtwork.removeAll(attemptedThisRefresh)
                        throw error
                    }
                    Settings.shared.speedDialArtworkRefreshTimes = Json.encodeToString(artworkRefreshTimes)
                }
            }

            scope.launch {
                var filtersNeedRefresh = false
                var previousBlocks = homeFilterIdentity()
                BlockedContent.version.drop(1).collectLatest {
                    val blocks = homeFilterIdentity()
                    filtersNeedRefresh = filtersNeedRefresh || blocks.requiresRefreshAfter(previousBlocks)
                    previousBlocks = blocks
                    val currentState = _uiState.value
                    val selectedChip = currentState.selectedChip
                    val visibleSelectedChip = selectedChip?.takeUnless { Settings.shared.hidePodcasts && it.isPodcastChip }
                    if (selectedChip != visibleSelectedChip) {
                        contentLoadGeneration++
                        cancelPagination()
                        loadJob?.cancel()
                        filtersNeedRefresh = filtersNeedRefresh || cachedAllContent == null
                        cachedSelectedChipContent = null
                        chipContentCache = emptyMap()
                        _uiState.value =
                            (cachedAllContent ?: currentState)
                                .copy(selectedChip = null)
                                .withoutBlockedContent()
                    } else {
                        _uiState.value =
                            homeContentAfterBlockedContentChange(
                                currentState = currentState,
                                cachedAllContent = cachedAllContent,
                                cachedSelectedChipContent = cachedSelectedChipContent,
                                pendingChipContent =
                                    publishedPrimaryChipContent.value
                                        ?.takeIf { it.first == contentLoadGeneration && loadJob?.isActive == true }
                                        ?.second,
                                pendingAllContent =
                                    publishedPrimaryAllContent.value
                                        ?.takeIf { it.first == contentLoadGeneration }
                                        ?.second
                                        ?.withLatestLibraryAndSpeedDialState(currentState)
                                        ?.withLatestSongMetadataFrom(currentState)
                                        ?.withSpeedDialPins(latestSpeedDialPins, speedDialRecommendationItems),
                            )
                    }
                    if (!filtersNeedRefresh) return@collectLatest
                    chipContentCache = emptyMap()
                    chipUpdatedAt = emptyMap()
                    delay(150)
                    filtersNeedRefresh = false
                    loadHomeData(
                        params = visibleSelectedChip?.params,
                        browseId = visibleSelectedChip?.browseId ?: "FEmusic_home",
                        isRefresh = true,
                    )
                }
            }

            scope.launch {
                var previousSession: HomeSessionIdentity? = null
                GlobalYouTube.innerTube.sessionFlow
                    .map(InnerTube.SessionSnapshot::homeSessionIdentity)
                    .distinctUntilChanged()
                    .collect { session ->
                        if (previousSession != null) {
                            chipContentCache = emptyMap()
                            chipUpdatedAt = emptyMap()
                            cachedAllContent = null
                            cachedSelectedChipContent = null
                            speedDialRecommendationItems = emptyList()
                            latestSpeedDialItemMetadata = emptyMap()
                            Settings.shared.lastHomePageJson = ""
                            _uiState.value = HomeUiState(isLoading = true)
                        }
                        previousSession = session
                        val selectedChip = _uiState.value.selectedChip?.takeUnless { Settings.shared.hidePodcasts && it.isPodcastChip }
                        loadHomeData(
                            params = selectedChip?.params,
                            browseId = selectedChip?.browseId ?: "FEmusic_home",
                            isRefresh = true,
                        )
                    }
            }

            scope.launch {
                PlaybackNetworkStateStore.state
                    .map { it.connected }
                    .distinctUntilChanged()
                    .drop(1)
                    .collect { connected ->
                        if (connected) {
                            val selectedChip = _uiState.value.selectedChip?.takeUnless { Settings.shared.hidePodcasts && it.isPodcastChip }
                            loadHomeData(
                                params = selectedChip?.params,
                                browseId = selectedChip?.browseId ?: "FEmusic_home",
                                isRefresh = true,
                            )
                        } else {
                            val cancelledGeneration = contentLoadGeneration
                            val interruptedPrimary = publishedPrimaryAllContent.value
                            val interruptedState = _uiState.value
                            if (interruptedPrimary?.first == cancelledGeneration) {
                                val latestPrimary =
                                    interruptedPrimary.second
                                        .withLatestLibraryAndSpeedDialState(interruptedState)
                                        .withLatestSongMetadataFrom(interruptedState)
                                        .withSpeedDialPins(latestSpeedDialPins, speedDialRecommendationItems)
                                cachedAllContent =
                                    homeCacheAfterOfflineCancellation(
                                        cachedAllContent,
                                        latestPrimary,
                                        interruptedPrimary.first,
                                        cancelledGeneration,
                                    )
                                publishedPrimaryAllContent.value = null
                                if (cachedAllContent != null) {
                                    val locale = GlobalYouTube.innerTube.locale
                                    lastKnownLocaleSignature = "${locale.hl}:${locale.gl}"
                                }
                            }
                            if (isFilteredHome(
                                    interruptedState.selectedChip?.params,
                                    interruptedState.selectedChip?.browseId ?: "FEmusic_home",
                                )
                            ) {
                                cachedSelectedChipContent =
                                    cachedSelectedChipContent
                                        ?.takeIf { it.selectedChip == interruptedState.selectedChip }
                                        ?.withLatestLibraryAndSpeedDialState(interruptedState)
                                        ?.withLatestSongMetadataFrom(interruptedState)
                                        ?.asOfflineSnapshot()
                                        ?: publishedPrimaryChipContent.value
                                            ?.takeIf { it.first == cancelledGeneration }
                                            ?.second
                                            ?.withLatestLibraryAndSpeedDialState(interruptedState)
                                            ?.withLatestSongMetadataFrom(interruptedState)
                                            ?.asOfflineSnapshot()
                                        ?: homeSelectedChipCacheAfterOfflineCancellation(
                                            cachedSelectedChipContent,
                                            interruptedState,
                                        )
                                cachedSelectedChipContent?.selectedChip?.let { chip ->
                                    cachedSelectedChipContent?.let { cacheChipContent(chip, it, markFresh = false) }
                                }
                                publishedPrimaryChipContent.value = null
                            }
                            contentLoadGeneration++
                            cancelPagination()
                            loadJob?.cancel()
                            _uiState.update(HomeUiState::asOfflineSnapshot)
                        }
                    }
            }

            scope.launch {
                GlobalYouTube.regionApplied
                    .map { "${it.hl}:${it.gl}" }
                    .distinctUntilChanged()
                    .collect { signature ->
                        if (signature != lastKnownLocaleSignature) {
                            invalidateCacheForLocaleChange()
                        }
                    }
            }
        }
    }

    private fun loadHomeData(
        params: String? = null,
        browseId: String = "FEmusic_home",
        isRefresh: Boolean = false,
        onComplete: () -> Unit = {},
        primaryPageOverride: HomePageData? = null,
    ) {
        val isFiltered = isFilteredHome(params, browseId)
        val hadIncompleteAllContent = allContentReloadRequired || cachedAllContent?.isAllContentPending == true
        if (!isFiltered) allContentReloadRequired = true
        val loadStartedAt = TimeSource.Monotonic.markNow()
        val generation = ++contentLoadGeneration
        val sessionKey = GlobalYouTube.innerTube.sessionSnapshot().homeCacheSessionKey()
        cancelPagination()
        cachedAllContent = cachedAllContent?.withoutChipFeeds()
        _uiState.update { it.withoutChipFeeds().copy(isRefreshing = isRefresh, loadMoreError = false) }
        loadJob?.cancel()
        loadJob =
            scope.launch(Dispatchers.Default) {
                if (!PlaybackNetworkStateStore.current.connected) {
                    _uiState.update(HomeUiState::asOfflineSnapshot)
                    scope.launch { onComplete() }
                    return@launch
                }
                _uiState.update { state ->
                    state.copy(
                        isLoading = state.isContentLoading || !state.hasHomeFeedContent(),
                        isRefreshing = isRefresh,
                        isRevalidating = !isRefresh && !state.isContentLoading && !state.hasNoHomeContent(),
                        error = null,
                        isLoadingMore = false,
                    )
                }

                var primaryContentPublished = false
                var primaryContinuation: String? = null
                var primaryContentSnapshot: HomeUiState? = null
                var loadedPrimaryPage: HomePageData? = primaryPageOverride
                var completionTransferred = false
                supervisorScope {
                    try {
                        val homePageDataDeferred =
                            async {
                                primaryPageOverride?.let { Result.success(it) }
                                    ?: homeRequestResult("primary home") {
                                        GlobalYouTubeRepository.instance.getHomePageData(params, browseId)
                                    }
                            }
                        val authenticatedSpeedDialSectionsDeferred =
                            async {
                                if (isFiltered) {
                                    Result.success(emptyList<HomeSection>())
                                } else {
                                    homeRequestResult("authenticated speed dial") { authenticatedSpeedDialSections(params, browseId) }
                                }
                            }
                        val signedIn = AuthManager().isLoggedIn
                        val exploreSectionsDeferred =
                            async {
                                homeRequestResult("explore") {
                                    if (!isFiltered && !signedIn) {
                                        GlobalYouTubeRepository.instance
                                            .getHomePageData(browseId = "FEmusic_explore")
                                            .sections
                                    } else {
                                        emptyList()
                                    }
                                }
                            }
                        val quickPicksDeferred = async { if (isFiltered) emptyList() else database.dao().quickPicks().first() }
                        val forgottenFavoritesDeferred =
                            async { if (isFiltered) emptyList() else database.dao().forgottenFavorites().first() }
                        val userPlaylistsDeferred = async { fetchUserPlaylists() }
                        val keepListeningDeferred =
                            async {
                                if (isFiltered) {
                                    emptyList()
                                } else {
                                    database
                                        .dao()
                                        .mostPlayedSongs(
                                            fromTimeStamp = getCurrentTimeMillis() - 86400000L * 30L,
                                            limit = 20,
                                        ).first()
                                }
                            }
                        val homePageData = homePageDataDeferred.await().getOrThrow()
                        loadedPrimaryPage = homePageData
                        primaryContinuation = homePageData.continuation
                        val rawHomeSections = homePageData.sections.withoutRegionalCharts(signedIn)
                        if (generation != contentLoadGeneration ||
                            sessionKey != GlobalYouTube.innerTube.sessionSnapshot().homeCacheSessionKey()
                        ) {
                            return@supervisorScope
                        }
                        val primaryLocalSongMetadata = loadLocalSongMetadata(rawHomeSections)
                        if (generation != contentLoadGeneration ||
                            sessionKey != GlobalYouTube.innerTube.sessionSnapshot().homeCacheSessionKey()
                        ) {
                            return@supervisorScope
                        }
                        val hydratedPrimarySections =
                            rawHomeSections.withLocalSongMetadata(primaryLocalSongMetadata).withoutNonMusicalVideos()
                        var userPlaylistsSnapshot = userPlaylistsDeferred.await()
                        if (!isCurrentHomeLibraryRevision(userPlaylistsSnapshot.revision, librarySnapshotRevision)) {
                            userPlaylistsSnapshot = fetchUserPlaylists()
                        }
                        var userPlaylists = userPlaylistsSnapshot.playlists
                        val chartSections = findSectionsByBrowseId(hydratedPrimarySections, "FEmusic_charts")
                        withContext(Dispatchers.Main.immediate) {
                            if (generation != contentLoadGeneration ||
                                sessionKey != GlobalYouTube.innerTube.sessionSnapshot().homeCacheSessionKey()
                            ) {
                                return@withContext
                            }
                            _uiState.update { state ->
                                val primaryState =
                                    state
                                        .primaryHomeContentLoaded(
                                            sections = hydratedPrimarySections,
                                            chips = homeChipsForSelection(homePageData.chips, state.selectedChip),
                                            userPlaylists = userPlaylists,
                                        ).copy(
                                            trending = chartSections.flatMap { it.items.filterIsInstance<SongItem>() },
                                            albumCharts = chartSections.flatMap { it.items.filterIsInstance<AlbumItem>() },
                                            quickPicks = findSectionSongs(hydratedPrimarySections, "FEmusic_quickpicks"),
                                            keepListening = findSectionSongs(hydratedPrimarySections, "FEmusic_listen_again"),
                                            forgottenFavorites =
                                                findSectionSongs(hydratedPrimarySections, "FEmusic_forgotten_favorites"),
                                            newReleases = newReleasesFrom(hydratedPrimarySections),
                                            communityPlaylist =
                                                findSectionsByBrowseId(hydratedPrimarySections, "FEmusic_community")
                                                    .flatMap { it.items.filterIsInstance<PlaylistItem>() },
                                            moodsAndGenres = findMoodsAndGenresByBrowseId(hydratedPrimarySections),
                                            podcasts =
                                                findSectionsByBrowseId(hydratedPrimarySections, "FEmusic_immersive")
                                                    .flatMap { it.items.filterIsInstance<PodcastItem>() },
                                            similarRecommendations = findSimilarRecommendations(hydratedPrimarySections),
                                            recapPlaylists =
                                                findSectionsByBrowseId(hydratedPrimarySections, "FEmusic_listening_review")
                                                    .flatMap { it.items.filterIsInstance<PlaylistItem>() },
                                            sections = uncategorizedHomeSections(hydratedPrimarySections),
                                        )
                                primaryContentSnapshot = primaryState
                                if (!isFiltered) {
                                    publishedPrimaryAllContent.value =
                                        generation to primaryState.copy(continuation = homePageData.continuation)
                                }
                                if (isFiltered) {
                                    val chipPrimaryState =
                                        primaryState.copy(
                                            continuation = homePageData.continuation,
                                            isContentLoading = true,
                                        )
                                    cachedSelectedChipContent = chipPrimaryState
                                    publishedPrimaryChipContent.value = generation to chipPrimaryState
                                }
                                primaryState.withoutBlockedContent()
                            }
                        }
                        if (generation != contentLoadGeneration) return@supervisorScope
                        primaryContentPublished = true
                        Logger.d(
                            HOME_LOG_TAG,
                            "primary home content published elapsedMs=${loadStartedAt.elapsedNow().inWholeMilliseconds}",
                        )
                        val exploreSections = exploreSectionsDeferred.await().getOrDefault(emptyList())
                        val youtubeMusicEditorialPlaylists =
                            (
                                findSectionsByBrowseId(exploreSections, "FEmusic_community")
                                    .flatMap { it.items.filterIsInstance<PlaylistItem>() }
                                    .ifEmpty {
                                        findSectionsByBrowseId(homePageData.sections, "FEmusic_community")
                                            .flatMap { it.items.filterIsInstance<PlaylistItem>() }
                                    }
                            ).distinctBy { it.id }
                        val localQuickPicks = quickPicksDeferred.await().map { it.toSongItem() }
                        val localForgottenFavorites = forgottenFavoritesDeferred.await().map { it.toSongItem() }
                        val localKeepListening = keepListeningDeferred.await().map { it.toSongItem() }
                        if (!isCurrentHomeLibraryRevision(userPlaylistsSnapshot.revision, librarySnapshotRevision)) {
                            userPlaylistsSnapshot = fetchUserPlaylists()
                            userPlaylists = userPlaylistsSnapshot.playlists
                        }
                        initialSpeedDialPins.await()
                        val localSpeedDial = latestSpeedDialPins

                        val rawSpeedDialSections =
                            authenticatedSpeedDialSectionsDeferred.await().getOrDefault(emptyList()).ifEmpty { rawHomeSections }
                        val localSongMetadata =
                            loadLocalSongMetadata(
                                sections = rawHomeSections + exploreSections + rawSpeedDialSections,
                                fallbackSongs = localQuickPicks + localForgottenFavorites + localKeepListening,
                            )
                        if (generation != contentLoadGeneration ||
                            sessionKey != GlobalYouTube.innerTube.sessionSnapshot().homeCacheSessionKey()
                        ) {
                            return@supervisorScope
                        }
                        val homeSections = rawHomeSections.withLocalSongMetadata(localSongMetadata)
                        val discoverySections =
                            exploreSections
                                .withLocalSongMetadata(localSongMetadata)
                                .withoutNonMusicalVideos()
                        val rawCombined =
                            (homeSections + discoverySections)
                                .withoutNonMusicalVideos()
                        val userPlaylistIds = userPlaylists.map { it.id.removePrefix("VL") }.toSet()
                        val sections = mergeAndDeduplicateHomeSections(rawCombined, userPlaylistIds)

                        val apiQuickPicks = findSectionSongs(sections, "FEmusic_quickpicks")
                        val apiKeepListening = findSectionSongs(sections, "FEmusic_listen_again")
                        val apiForgottenFavorites = findSectionSongs(sections, "FEmusic_forgotten_favorites")

                        val quickPicks =
                            if (Settings.shared.classicRecommendations && !isFiltered) {
                                classicQuickPicks(localQuickPicks, localForgottenFavorites).ifEmpty { apiQuickPicks }
                            } else {
                                personalizedSection(apiQuickPicks, localQuickPicks, isFiltered)
                            }.distinctBy(::canonicalHomeItemId)
                        val keepListening =
                            personalizedSection(
                                apiKeepListening,
                                localKeepListening,
                                isFiltered,
                            ).distinctBy(::canonicalHomeItemId)
                        val forgottenFavorites =
                            personalizedSection(
                                apiForgottenFavorites,
                                localForgottenFavorites,
                                isFiltered,
                            ).distinctBy(::canonicalHomeItemId)

                        val speedDialSections =
                            rawSpeedDialSections
                                .withLocalSongMetadata(localSongMetadata)
                                .withoutNonMusicalVideos()
                                .filterNot { it.isLibrarySection(userPlaylistIds) }
                        Logger.d(
                            HOME_LOG_TAG,
                            "metadata merge localSongs=${localSongMetadata.size} homeSections=${homeSections.size} " +
                                "speedDialSections=${speedDialSections.size}",
                        )
                        val speedDialRecommendations =
                            if (isFiltered) {
                                emptyList()
                            } else {
                                speedDialRecommendations(
                                    quickPicks = findSectionSongs(speedDialSections, "FEmusic_quickpicks"),
                                    otherShelves =
                                        speedDialSections
                                            .filterNot { it.browseId?.startsWith("FEmusic_quickpicks") == true }
                                            .map { it.items },
                                    localFallback = localKeepListening + localQuickPicks,
                                    excludedPlaylistIds = userPlaylistIds,
                                )
                            }
                        val speedDialItems =
                            buildSpeedDialItems(
                                pinned = if (isFiltered) emptyList() else localSpeedDial,
                                recommendations = speedDialRecommendations,
                            )
                        if (generation != contentLoadGeneration ||
                            sessionKey != GlobalYouTube.innerTube.sessionSnapshot().homeCacheSessionKey()
                        ) {
                            return@supervisorScope
                        }
                        if (!isCurrentHomeLibraryRevision(userPlaylistsSnapshot.revision, librarySnapshotRevision)) {
                            throw HomeLibraryRevisionChangedException()
                        }
                        if (!isFiltered) speedDialRecommendationItems = speedDialRecommendations

                        val loadingMoods = sections.needsHomeMoodsFallback(signedIn, isFiltered, homePageData.continuation)
                        val loadedState =
                            _uiState.value.copy(
                                contentLocaleSignature = currentContentLocaleSignature(),
                                sessionKey = sessionKey,
                                data = sections,
                                discoverySections = discoverySections,
                                isLoading = false,
                                isRefreshing = false,
                                isRevalidating = false,
                                isContentLoading = false,
                                isAllContentPending = isFiltered && _uiState.value.isAllContentPending,
                                isLoadingMore = loadingMoods,
                                chips = homeChipsForSelection(homePageData.chips, _uiState.value.selectedChip),
                                continuation = homePageData.continuation,
                                trending =
                                    findSectionsByBrowseId(sections, "FEmusic_charts")
                                        .flatMap { it.items.filterIsInstance<SongItem>() }
                                        .distinctBy(::canonicalHomeItemId),
                                albumCharts =
                                    findSectionsByBrowseId(sections, "FEmusic_charts")
                                        .flatMap { it.items.filterIsInstance<AlbumItem>() }
                                        .distinctBy(::canonicalHomeItemId),
                                quickPicks = quickPicks,
                                newReleases = newReleasesFrom(sections).distinctBy(::canonicalHomeItemId),
                                keepListening = keepListening,
                                forgottenFavorites = forgottenFavorites,
                                speedDialItems = speedDialItems.distinctBy(::canonicalHomeItemId),
                                speedDialPinnedIds = if (isFiltered) emptyList() else localSpeedDial.map { it.id },
                                communityPlaylist = youtubeMusicEditorialPlaylists.distinctBy(::canonicalHomeItemId),
                                moodsAndGenres = findMoodsAndGenresByBrowseId(sections),
                                podcasts =
                                    findSectionsByBrowseId(sections, "FEmusic_immersive")
                                        .flatMap { it.items.filterIsInstance<PodcastItem>() }
                                        .distinctBy(::canonicalHomeItemId),
                                similarRecommendations = findSimilarRecommendations(sections),
                                recapPlaylists =
                                    findSectionsByBrowseId(sections, "FEmusic_listening_review")
                                        .flatMap { it.items.filterIsInstance<PlaylistItem>() }
                                        .distinctBy(::canonicalHomeItemId),
                                userPlaylists = userPlaylists,
                                sections = uncategorizedHomeSections(sections),
                                recentlyPlayed = emptyList(),
                            )
                        if (isFiltered) cachedSelectedChipContent = null
                        if (loadingMoods) paginationInProgress.value = true
                        try {
                            _uiState.value = loadedState.withoutBlockedContent()
                            primaryContentSnapshot = loadedState
                            if (isFiltered) {
                                cachedSelectedChipContent = loadedState
                                loadedState.selectedChip?.let { chip ->
                                    cacheChipContent(chip, loadedState)
                                }
                                publishedPrimaryChipContent.update { it?.takeUnless { primary -> primary.first == generation } }
                            } else {
                                allContentReloadRequired = false
                                cacheAllContentState(loadedState)
                                publishedPrimaryAllContent.value = null
                                publishedPrimaryChipContent.value = null
                            }
                            saveLastHomePage(loadedState)
                            if (!isFiltered) appendMissingMainFeedMoods(generation)
                        } finally {
                            if (loadingMoods && generation == contentLoadGeneration) {
                                paginationInProgress.value = false
                                _uiState.update { it.copy(isLoadingMore = false) }
                            }
                        }
                        if (!isFiltered) preloadMoreSections()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: HomeLibraryRevisionChangedException) {
                        if (generation == contentLoadGeneration) {
                            completionTransferred = true
                            loadHomeData(
                                params = params,
                                browseId = browseId,
                                isRefresh = isRefresh,
                                onComplete = onComplete,
                                primaryPageOverride = loadedPrimaryPage,
                            )
                        }
                    } catch (e: Exception) {
                        if (generation != contentLoadGeneration ||
                            sessionKey != GlobalYouTube.innerTube.sessionSnapshot().homeCacheSessionKey()
                        ) {
                            return@supervisorScope
                        }
                        val currentState = _uiState.value
                        val failedAllPending =
                            !isFiltered &&
                                shouldKeepIncompleteAllContentPendingAfterFailure(
                                    primaryContentPublished = primaryContentPublished,
                                    cachedAllContent = cachedAllContent,
                                    hadIncompleteAllContent = hadIncompleteAllContent,
                                )
                        val retainedPrimaryContent =
                            publishedPrimaryAllContent.value
                                ?.takeIf { it.first == generation }
                                ?.second
                                ?: primaryContentSnapshot
                        val primaryFailureSnapshot =
                            retainedPrimaryContent?.copy(
                                userPlaylists = currentState.userPlaylists,
                                isLoading = false,
                                isRefreshing = false,
                                isRevalidating = false,
                                isContentLoading = false,
                                isLoadingMore = false,
                                isAllContentPending = false,
                                continuation =
                                    homeContinuationAfterAssemblyFailure(
                                        currentContinuation = currentState.continuation,
                                        primaryContinuation = primaryContinuation,
                                        primaryContentPublished = true,
                                    ),
                                error = e.message.orEmpty(),
                            )
                        val failedState =
                            currentState.copy(
                                isLoading = false,
                                isRefreshing = false,
                                isRevalidating = false,
                                isContentLoading = false,
                                isLoadingMore = false,
                                isAllContentPending = if (!isFiltered) false else currentState.isAllContentPending,
                                continuation =
                                    if (failedAllPending) {
                                        null
                                    } else {
                                        homeContinuationAfterAssemblyFailure(
                                            currentContinuation = currentState.continuation,
                                            primaryContinuation = primaryContinuation,
                                            primaryContentPublished = primaryContentPublished,
                                        )
                                    },
                                error = e.message.orEmpty(),
                            )
                        _uiState.value = failedState
                        if (!isFiltered) {
                            allContentReloadRequired = failedAllPending
                            publishedPrimaryAllContent.update { it?.takeUnless { primary -> primary.first == generation } }
                            cachedAllContent =
                                homeCacheAfterAssemblyFailure(
                                    cachedAllContent,
                                    (primaryFailureSnapshot ?: failedState)
                                        .withLatestLibraryAndSpeedDialState(currentState)
                                        .withLatestSongMetadataFrom(currentState)
                                        .withSpeedDialPins(latestSpeedDialPins, speedDialRecommendationItems),
                                    primaryContentPublished,
                                )
                            if (primaryContentPublished) {
                                val locale = GlobalYouTube.innerTube.locale
                                lastKnownLocaleSignature = "${locale.hl}:${locale.gl}"
                            }
                        } else if (primaryContentPublished) {
                            val latestChipPrimarySnapshot =
                                cachedSelectedChipContent
                                    ?.takeIf { it.selectedChip == currentState.selectedChip }
                                    ?.let { cached ->
                                        homeChipSnapshotWithRecoveredContinuation(cached, primaryFailureSnapshot)
                                    } ?: primaryFailureSnapshot
                            val failedChipSnapshot =
                                homeSelectedChipCacheAfterAssemblyFailure(
                                    cachedSelectedChipContent,
                                    latestChipPrimarySnapshot?.withLatestSongMetadataFrom(currentState),
                                    failedState,
                                    primaryContentPublished,
                                )
                            cachedSelectedChipContent = failedChipSnapshot
                            failedChipSnapshot?.selectedChip?.let { chip -> cacheChipContent(chip, failedChipSnapshot) }
                            publishedPrimaryChipContent.update { it?.takeUnless { primary -> primary.first == generation } }
                        } else {
                            cachedSelectedChipContent =
                                cachedSelectedChipContent
                                    ?.takeIf { it.selectedChip == currentState.selectedChip }
                                    ?.asOfflineSnapshot()
                            cachedSelectedChipContent?.let { cached ->
                                cached.selectedChip?.let { chip -> cacheChipContent(chip, cached, markFresh = false) }
                            }
                        }
                    } finally {
                        if (generation == contentLoadGeneration) {
                            _uiState.update {
                                it.copy(
                                    isLoading = false,
                                    isRefreshing = false,
                                    isRevalidating = false,
                                    isContentLoading = false,
                                )
                            }
                        }
                        if (!completionTransferred) scope.launch { onComplete() }
                    }
                }
            }
    }

    fun loadMoreSections() {
        val continuation = _uiState.value.continuation
        if (!paginationInProgress.compareAndSet(false, true)) return
        if (continuation == null) {
            val state = _uiState.value
            if (shouldLoadMoreHomeContent(state)) {
                loadMoreChipSections()
            } else {
                paginationInProgress.value = false
            }
            return
        }
        val generation = contentLoadGeneration
        _uiState.update { it.copy(isLoadingMore = true, loadMoreError = false) }

        paginationJob =
            scope.launch(Dispatchers.Default) {
                try {
                    val (newSections, nextContinuation) = GlobalYouTubeRepository.instance.getHomeSectionsContinuation(continuation)
                    if (generation != contentLoadGeneration || _uiState.value.continuation != continuation) return@launch
                    val pageMetadata = loadLocalSongMetadata(newSections)
                    if (generation != contentLoadGeneration || _uiState.value.continuation != continuation) return@launch
                    val hydratedNewSections = newSections.withLocalSongMetadata(pageMetadata)
                    if (isFilteredHome(_uiState.value.selectedChip?.params, _uiState.value.selectedChip?.browseId ?: "FEmusic_home")) {
                        val currentState = _uiState.value
                        val merged =
                            (cachedSelectedChipContent?.takeIf { it.selectedChip == currentState.selectedChip } ?: currentState)
                                .withMergedSections(hydratedNewSections, nextContinuation)
                        cachedSelectedChipContent = merged
                        merged.selectedChip?.let { chip ->
                            chipContentCache =
                                boundedHomeChipCache(chipContentCache, chipCacheKey(chip), merged)
                        }
                        _uiState.value = merged.withoutBlockedContent().copy(isLoadingMore = true)
                    } else {
                        val merged = (cachedAllContent ?: _uiState.value).withMergedSections(hydratedNewSections, nextContinuation)
                        cacheAllContentState(merged)
                        _uiState.value = merged.withoutBlockedContent().copy(isLoadingMore = true)
                        appendMissingMainFeedMoods(generation)
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    if (generation == contentLoadGeneration) {
                        _uiState.update { it.copy(isLoadingMore = false, loadMoreError = true) }
                    }
                } finally {
                    if (generation == contentLoadGeneration) {
                        paginationInProgress.value = false
                        _uiState.update { it.copy(isLoadingMore = false) }
                    }
                }
            }
    }

    private fun loadMoreChipSections() {
        val generation = contentLoadGeneration
        _uiState.update { it.copy(isLoadingMore = true, loadMoreError = false) }
        paginationJob =
            scope.launch(Dispatchers.Default) {
                try {
                    val state = _uiState.value
                    if (state.homePaginationKey == null) return@launch
                    val page = checkNotNull(chipFeedPager.loadNext(state)) { "An appended Home chip request is still in progress" }
                    if (generation != contentLoadGeneration) return@launch
                    val merged = (cachedAllContent ?: _uiState.value).copy(chipFeeds = page.chipFeeds, usedHomeChips = page.usedHomeChips)
                    cacheAllContentState(merged)
                    _uiState.value = merged.withoutBlockedContent().copy(isLoadingMore = true)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Logger.w(HOME_LOG_TAG, "Failed to load appended Home chip page", error)
                    if (generation == contentLoadGeneration) {
                        _uiState.update { it.copy(loadMoreError = true) }
                    }
                } finally {
                    if (generation == contentLoadGeneration) {
                        paginationInProgress.value = false
                        _uiState.update { it.copy(isLoadingMore = false) }
                    }
                }
            }
    }

    private suspend fun prepareChipSections(sections: List<HomeSection>): List<HomeSection> =
        sections
            .withLocalSongMetadata(loadLocalSongMetadata(sections))
            .withoutRegionalCharts(AuthManager().isLoggedIn)
            .withoutNonMusicalVideos()

    private fun preloadMoreSections() {
        val selectedChip = _uiState.value.selectedChip
        if (isFilteredHome(selectedChip?.params, selectedChip?.browseId ?: "FEmusic_home")) return
        if (!paginationInProgress.compareAndSet(false, true)) return
        val generation = contentLoadGeneration
        val continuation =
            _uiState.value.continuation ?: run {
                paginationInProgress.value = false
                return
            }
        paginationJob?.cancel()
        _uiState.update { it.copy(isLoadingMore = true) }
        paginationJob =
            scope.launch(Dispatchers.Default) {
                try {
                    val (newSections, nextContinuation) =
                        GlobalYouTubeRepository.instance.getHomeSectionsContinuation(continuation)
                    if (generation != contentLoadGeneration || _uiState.value.continuation != continuation) return@launch
                    val pageMetadata = loadLocalSongMetadata(newSections)
                    if (generation != contentLoadGeneration || _uiState.value.continuation != continuation) return@launch
                    val hydratedNewSections = newSections.withLocalSongMetadata(pageMetadata)
                    val merged = (cachedAllContent ?: _uiState.value).withMergedSections(hydratedNewSections, nextContinuation)
                    cacheAllContentState(merged)
                    _uiState.value = merged.withoutBlockedContent().copy(isLoadingMore = true)
                    appendMissingMainFeedMoods(generation)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                } finally {
                    if (generation == contentLoadGeneration) {
                        paginationInProgress.value = false
                        _uiState.update { it.copy(isLoadingMore = false) }
                    }
                }
            }
    }

    private suspend fun appendMissingMainFeedMoods(generation: Long) {
        val chip = _uiState.value.selectedChip
        if (generation != contentLoadGeneration || isFilteredHome(chip?.params, chip?.browseId ?: "FEmusic_home")) return
        val state = cachedAllContent ?: _uiState.value
        val sections = state.data.orEmpty()
        val sessionKey = GlobalYouTube.innerTube.sessionSnapshot().homeCacheSessionKey()
        val completed =
            sections.appendMissingHomeMoods(
                signedIn = AuthManager().isLoggedIn,
                isFiltered = isFilteredHome(state.selectedChip?.params, state.selectedChip?.browseId ?: "FEmusic_home"),
                continuation = state.continuation,
                loadExplore = { GlobalYouTubeRepository.instance.getHomePageData(browseId = "FEmusic_explore").sections },
            )
        val moods = completed.drop(sections.size)
        if (moods.isEmpty() || generation != contentLoadGeneration ||
            sessionKey != GlobalYouTube.innerTube.sessionSnapshot().homeCacheSessionKey()
        ) {
            return
        }
        val latest = cachedAllContent ?: _uiState.value
        if (latest.continuation != null || latest.data.orEmpty().any(HomeSection::isMoodAndGenresSection)) return
        val merged = latest.withMergedSections(moods, null)
        cacheAllContentState(merged)
        _uiState.value = merged.withoutBlockedContent().copy(isLoadingMore = _uiState.value.isLoadingMore)
        saveLastHomePage(merged)
    }

    private suspend fun rebuildQuickPicks() {
        val state = _uiState.value
        if (state.isLoading || state.isRefreshing || state.isRevalidating ||
            state.isContentLoading || state.isLoadingMore
        ) {
            return
        }
        val selectedChip = state.selectedChip
        val isFiltered = isFilteredHome(selectedChip?.params, selectedChip?.browseId ?: "FEmusic_home")
        // A restored startup snapshot keeps quickPicks separately and may lack
        // the API shelf; fall back to the current picks so they are not lost.
        val apiQuickPicks =
            findSectionSongs(state.data.orEmpty(), "FEmusic_quickpicks").ifEmpty { state.quickPicks }
        val localQuickPicks =
            database
                .dao()
                .quickPicks()
                .first()
                .map { it.toSongItem() }
        val localForgottenFavorites =
            database
                .dao()
                .forgottenFavorites()
                .first()
                .map { it.toSongItem() }
        val quickPicks =
            if (Settings.shared.classicRecommendations && !isFiltered) {
                classicQuickPicks(localQuickPicks, localForgottenFavorites).ifEmpty { apiQuickPicks }
            } else {
                personalizedSection(apiQuickPicks, localQuickPicks, isFiltered)
            }.distinctBy(::canonicalHomeItemId)
        val filteredQuickPicks = BlockedContent.filterSongs(quickPicks, BlockedContent.snapshot())
        _uiState.update { current ->
            if (current.selectedChip == selectedChip) {
                current.copy(quickPicks = filteredQuickPicks)
            } else {
                current
            }
        }
        // Mirror the rebuild into the caches and the persisted snapshot so
        // pagination and chip round trips keep the rebuilt picks.
        val updated = _uiState.value
        if (updated.selectedChip != selectedChip || updated.quickPicks != filteredQuickPicks) return
        if (isFiltered) {
            cachedSelectedChipContent = updated
            updated.selectedChip?.let { chip -> cacheChipContent(chip, updated) }
            rebuildCachedAllQuickPicks(localQuickPicks, localForgottenFavorites)
        } else {
            cacheAllContentState(updated)
        }
        saveLastHomePage(updated)
    }

    // The mode change applies to the cached All feed too; rebuild its picks so
    // returning to All does not restore the previous mode's selection.
    private fun rebuildCachedAllQuickPicks(
        localQuickPicks: List<SongItem>,
        localForgottenFavorites: List<SongItem>,
    ) {
        val cached = cachedAllContent ?: return
        val apiQuickPicks =
            findSectionSongs(cached.data.orEmpty(), "FEmusic_quickpicks").ifEmpty { cached.quickPicks }
        val quickPicks =
            if (Settings.shared.classicRecommendations) {
                classicQuickPicks(localQuickPicks, localForgottenFavorites).ifEmpty { apiQuickPicks }
            } else {
                personalizedSection(apiQuickPicks, localQuickPicks, isFiltered = false)
            }.distinctBy(::canonicalHomeItemId)
        cachedAllContent =
            cached.copy(quickPicks = BlockedContent.filterSongs(quickPicks, BlockedContent.snapshot()))
    }

    private fun HomeUiState.withMergedSections(
        newSections: List<HomeSection>,
        nextContinuation: String?,
    ): HomeUiState {
        val userPlaylistIds = userPlaylists.map { it.id.removePrefix("VL") }.toSet()
        val allSections =
            mergeAndDeduplicateHomeSections(
                data.orEmpty() + newSections.withoutRegionalCharts(AuthManager().isLoggedIn).withoutNonMusicalVideos(),
                userPlaylistIds,
            )
        val apiQuickPicks = findSectionSongs(allSections, "FEmusic_quickpicks")
        val apiKeepListening = findSectionSongs(allSections, "FEmusic_listen_again")
        val apiForgottenFavorites = findSectionSongs(allSections, "FEmusic_forgotten_favorites")
        return copy(
            data = allSections,
            isLoadingMore = false,
            continuation = nextContinuation,
            sections = uncategorizedHomeSections(allSections),
            similarRecommendations = findSimilarRecommendations(allSections),
            trending =
                findSectionsByBrowseId(allSections, "FEmusic_charts")
                    .flatMap { it.items.filterIsInstance<SongItem>() }
                    .distinctBy(::canonicalHomeItemId),
            albumCharts =
                findSectionsByBrowseId(allSections, "FEmusic_charts")
                    .flatMap { it.items.filterIsInstance<AlbumItem>() }
                    .distinctBy(::canonicalHomeItemId),
            quickPicks =
                if (isFilteredHome(selectedChip?.params, selectedChip?.browseId ?: "FEmusic_home")) {
                    apiQuickPicks.ifEmpty { quickPicks }
                } else {
                    preferredQuickPicks(apiQuickPicks, quickPicks)
                }.distinctBy(::canonicalHomeItemId),
            newReleases = newReleasesFrom(allSections).distinctBy(::canonicalHomeItemId),
            keepListening = apiKeepListening.ifEmpty { keepListening }.distinctBy(::canonicalHomeItemId),
            forgottenFavorites = apiForgottenFavorites.ifEmpty { forgottenFavorites }.distinctBy(::canonicalHomeItemId),
            moodsAndGenres = findMoodsAndGenresByBrowseId(allSections),
            podcasts =
                findSectionsByBrowseId(allSections, "FEmusic_immersive")
                    .flatMap { it.items.filterIsInstance<PodcastItem>() }
                    .distinctBy(::canonicalHomeItemId),
            recapPlaylists =
                findSectionsByBrowseId(allSections, "FEmusic_listening_review")
                    .flatMap { it.items.filterIsInstance<PlaylistItem>() }
                    .distinctBy(::canonicalHomeItemId),
        )
    }

    private fun cacheAllContentState(state: HomeUiState = _uiState.value) {
        val cacheSnapshot =
            cacheableAllContentSnapshot(
                state,
                allContentReloadRequired,
            ) ?: return
        val locale = GlobalYouTube.innerTube.locale
        lastKnownLocaleSignature = "${locale.hl}:${locale.gl}"
        cachedAllContent = cacheSnapshot
    }

    private fun cancelPagination() {
        paginationJob?.cancel()
        paginationJob = null
        paginationInProgress.value = false
        chipFeedPager.reset()
    }

    private suspend fun loadLocalSongMetadata(
        sections: List<HomeSection>,
        fallbackSongs: List<SongItem> = emptyList(),
    ): Map<String, SongItem> {
        val sectionSongIds =
            sections
                .flatMap(HomeSection::items)
                .filterIsInstance<SongItem>()
                .map(SongItem::id)
                .filter(String::isNotBlank)
                .distinct()
        val storedSongs = database.dao().getSongsByIds(sectionSongIds).map(Song::toSongItem)
        Logger.d(
            HOME_LOG_TAG,
            "metadata lookup sectionSongs=${sectionSongIds.size} stored=${storedSongs.size} " +
                "fallback=${fallbackSongs.size}",
        )
        return (fallbackSongs + storedSongs).associateBy(SongItem::id)
    }

    private fun List<YTItem>.withSongMetadata(localSongs: Map<String, SongItem>): List<YTItem> =
        map { item -> if (item is SongItem) item.withFallbackMetadata(localSongs[item.id]) else item }

    private fun List<HomeSection>.withLocalSongMetadata(localSongs: Map<String, SongItem>): List<HomeSection> =
        map { section -> section.copy(items = section.items.withSongMetadata(localSongs)) }

    private fun HomeUiState.withLocalSongMetadata(localSongs: Map<String, SongItem>): HomeUiState =
        copy(
            data = data?.withLocalSongMetadata(localSongs),
            trending = trending.map { it.withFallbackMetadata(localSongs[it.id]) },
            quickPicks = quickPicks.map { it.withFallbackMetadata(localSongs[it.id]) },
            keepListening = keepListening.map { it.withFallbackMetadata(localSongs[it.id]) },
            forgottenFavorites = forgottenFavorites.map { it.withFallbackMetadata(localSongs[it.id]) },
            speedDialItems = speedDialItems.withSongMetadata(localSongs),
            similarRecommendations =
                similarRecommendations.map { recommendation ->
                    recommendation.copy(items = recommendation.items.withSongMetadata(localSongs))
                },
            sections = sections.withLocalSongMetadata(localSongs),
            chipFeeds = chipFeeds.map { it.copy(sections = it.sections.withLocalSongMetadata(localSongs)) },
        )

    private fun List<HomeSection>.withoutNonMusicalVideos(): List<HomeSection> =
        mapNotNull { section ->
            val filtered = section.items.filterNot { it is SongItem && it.isNonMusicalVideo }
            section.copy(items = filtered).takeIf { filtered.isNotEmpty() }
        }

    private suspend fun fetchUserPlaylists(): HomeUserPlaylistSnapshot {
        while (true) {
            val revision = librarySnapshotRevision
            val playlists = readUserPlaylists()
            if (revision == librarySnapshotRevision) return HomeUserPlaylistSnapshot(revision, playlists)
        }
    }

    private suspend fun readUserPlaylists(): List<UserPlaylist> {
        val playlists = mutableListOf<UserPlaylist>()
        val likedSongsCount = database.dao().likedSongsCount().first()
        val localPlaylists =
            try {
                database.dao().allPlaylists().first()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                emptyList()
            }

        playlists.add(
            UserPlaylist(
                id = "liked",
                name = likedSongsName,
                thumbnailUrl = AUTO_PLAYLIST_ICON_LIKED,
                songCount = likedSongsCount,
                isAutoPlaylist = true,
            ),
        )
        val ids = mutableSetOf("liked")
        val currentAccount = libraryAccountKey(Settings.shared.dataSyncId, Settings.shared.accountEmail, Settings.shared.innerTubeAuthUser)
        val snapshotMatchesAccount = Settings.shared.lastLibraryRefreshAccount == currentAccount
        localPlaylists
            .filter { it.isLocal || (snapshotMatchesAccount && it.bookmarkedAt != null) }
            .filter {
                it.id != "LM" &&
                    it.browseId?.canonicalPlaylistId() != "LM"
            }.forEach { playlist ->
                val remoteId = playlist.browseId ?: playlist.id
                if (ids.add(remoteId.canonicalPlaylistId())) {
                    playlists.add(
                        UserPlaylist(
                            id = remoteId,
                            name = playlist.name,
                            thumbnailUrl = playlist.thumbnailUrl,
                            songCount = playlist.remoteSongCount,
                            author = null,
                            isAutoPlaylist = playlist.remoteSongCount == null,
                        ),
                    )
                }
            }

        return playlists
    }

    private fun String.canonicalPlaylistId(): String = removePrefix("VL")

    fun refresh(onComplete: () -> Unit) {
        chipContentCache = emptyMap()
        val selectedChip = _uiState.value.selectedChip?.takeUnless { Settings.shared.hidePodcasts && it.isPodcastChip }
        loadHomeData(
            params = selectedChip?.params,
            browseId = selectedChip?.browseId ?: "FEmusic_home",
            isRefresh = true,
            onComplete = onComplete,
        )
    }

    fun invalidateCacheForLocaleChange() {
        val locale = GlobalYouTube.innerTube.locale
        lastKnownLocaleSignature = "${locale.hl}:${locale.gl}"
        cachedAllContent = null
        cachedSelectedChipContent = null
        chipContentCache = emptyMap()
        Settings.shared.lastHomePageJson = ""
        val selectedChip = _uiState.value.selectedChip?.takeUnless { Settings.shared.hidePodcasts && it.isPodcastChip }
        loadHomeData(
            params = selectedChip?.params,
            browseId = selectedChip?.browseId ?: "FEmusic_home",
            isRefresh = true,
        )
    }

    private fun restoreAllContent() {
        cachedSelectedChipContent = null
        contentLoadGeneration++
        cancelPagination()
        loadJob?.cancel()
        cachedAllContent = cachedAllContent?.withoutChipFeeds()
        val locale = GlobalYouTube.innerTube.locale
        val currentSignature = "${locale.hl}:${locale.gl}"
        val cached = cachedAllContent?.takeIf { lastKnownLocaleSignature == currentSignature }
        val reloadIncompleteFeed = shouldReloadIncompleteAllContent(allContentReloadRequired, _uiState.value)
        if (cached != null && !reloadIncompleteFeed) {
            allContentReloadRequired = false
            val restored =
                homeContentForAllSelection(_uiState.value, cached).copy(
                    isLoading = false,
                    isRefreshing = false,
                    isRevalidating = false,
                    isContentLoading = false,
                    isLoadingMore = false,
                    error = null,
                )
            val userPlaylistIds = restored.userPlaylists.map { it.id.removePrefix("VL") }.toSet()
            speedDialRecommendationItems =
                restored
                    .speedDialUnpinnedItems()
                    .filterNot { item ->
                        item is PlaylistItem && (item.isEditable || canonicalHomeItemId(item) in userPlaylistIds)
                    }
            _uiState.value = restored.withoutBlockedContent()
            saveLastHomePage(restored)
        } else if (cached != null) {
            val restored =
                homeContentForAllSelection(_uiState.value, cached).copy(
                    isLoading = false,
                    isRefreshing = false,
                    isRevalidating = false,
                    isContentLoading = false,
                    isLoadingMore = false,
                    error = null,
                )
            _uiState.value = restored.withoutBlockedContent()
            saveLastHomePage(restored)
            loadHomeData(isRefresh = true)
        } else {
            cachedAllContent = null
            lastKnownLocaleSignature = currentSignature
            _uiState.update {
                homeContentForAllSelection(it, cachedAllContent).copy(
                    isContentLoading = true,
                    error = null,
                )
            }
            loadHomeData()
        }
    }

    fun toggleChip(chip: Chip?) {
        if (chip?.isPodcastChip == true && Settings.shared.hidePodcasts) return
        val currentState = _uiState.value
        val currentChip = currentState.selectedChip
        val pendingChip = publishedPrimaryChipContent.value?.takeIf { it.first == contentLoadGeneration }?.second
        if (currentChip != null && pendingChip?.selectedChip == currentChip) {
            cacheChipContent(
                currentChip,
                pendingChip.withLatestLibraryAndSpeedDialState(currentState).withLatestSongMetadataFrom(currentState),
                markFresh = false,
            )
        }
        val params = chip?.params

        val shouldRestoreAll =
            chip == null ||
                chip == currentChip ||
                (params == null && chip.browseId == null)

        if (shouldRestoreAll) {
            restoreAllContent()
            return
        }

        if (currentChip == null) {
            val pendingPrimary = publishedPrimaryAllContent.value?.takeIf { it.first == contentLoadGeneration }?.second
            if (pendingPrimary != null) {
                cachedAllContent =
                    cacheableAllContentSnapshot(pendingPrimary.copy(isAllContentPending = false)) ?: cachedAllContent
                allContentReloadRequired = true
            } else {
                cacheAllContentState()
            }
        }

        contentLoadGeneration++
        cancelPagination()
        loadJob?.cancel()
        cachedAllContent = cachedAllContent?.withoutChipFeeds()
        val chipKey = chipCacheKey(chip)
        val cachedChipContent = chipContentCache[chipKey]
        if (cachedChipContent != null) {
            cachedSelectedChipContent = cachedChipContent
            _uiState.value =
                cachedChipContent
                    .copy(
                        selectedChip = chip,
                        chips = homeChipsForSelection(cachedChipContent.chips, chip),
                        isLoading = false,
                        isRefreshing = false,
                        isRevalidating = false,
                        isContentLoading = false,
                        isLoadingMore = false,
                        error = null,
                    ).withoutBlockedContent()
            chipContentCache = (chipContentCache - chipKey) + (chipKey to cachedChipContent)
            if (isFreshHomeChip(chipUpdatedAt[chipKey], getCurrentTimeMillis())) return
        } else {
            cachedSelectedChipContent = null
            _uiState.update { it.loadingChip(chip) }
        }
        loadHomeData(
            params = chip.params,
            browseId = chip.browseId ?: "FEmusic_home",
        )
    }

    private fun cacheChipContent(
        chip: Chip,
        state: HomeUiState,
        markFresh: Boolean = true,
    ) {
        val key = chipCacheKey(chip)
        chipContentCache = boundedHomeChipCache(chipContentCache, key, state)
        chipUpdatedAt =
            if (markFresh) {
                chipUpdatedAt.filterKeys { it in chipContentCache } + (key to getCurrentTimeMillis())
            } else {
                chipUpdatedAt.filterKeys { it in chipContentCache && it != key }
            }
    }

    private fun chipCacheKey(chip: Chip): HomeChipKey =
        HomeChipKey(
            currentContentLocaleSignature(),
            GlobalYouTube.innerTube.sessionSnapshot().homeSessionIdentity(),
            chip.browseId,
            chip.params,
        )

    fun pinSpeedDialItem(item: YTItem) {
        latestSpeedDialItemMetadata += item.id to item
        scope.launch {
            database.speedDialDao().pin(SpeedDialItem.fromYTItem(item))
        }
    }

    fun unpinSpeedDialItem(id: String) {
        scope.launch { database.speedDialDao().unpin(id) }
    }

    fun moveSpeedDialItemEarlier(id: String) {
        scope.launch { database.speedDialDao().moveEarlier(id) }
    }

    fun moveSpeedDialItemLater(id: String) {
        scope.launch { database.speedDialDao().moveLater(id) }
    }

    fun setSpeedDialSurprisePinned(pinned: Boolean) {
        Settings.shared.speedDialSurprisePinned = pinned
        _uiState.update { it.copy(speedDialSurprisePinned = pinned) }
        cachedAllContent = cachedAllContent?.copy(speedDialSurprisePinned = pinned)
        publishedPrimaryAllContent.update { primary ->
            primary?.let { (generation, state) -> generation to state.copy(speedDialSurprisePinned = pinned) }
        }
        publishedPrimaryChipContent.update { primary ->
            primary?.let { (generation, state) -> generation to state.copy(speedDialSurprisePinned = pinned) }
        }
    }

    fun updateSongMetadata(song: SongItem) {
        val updated = song.copy(thumbnail = song.thumbnail.withArtworkCacheRevision(++artworkCacheRevision))
        latestSpeedDialItemMetadata += updated.id to updated
        speedDialRecommendationItems = speedDialRecommendationItems.withUpdatedSongItems(updated)
        _uiState.update { it.withUpdatedSongMetadata(updated) }
        publishedPrimaryAllContent.update { primary ->
            primary?.let { (generation, snapshot) ->
                generation to snapshot.withUpdatedSongMetadata(updated)
            }
        }
        publishedPrimaryChipContent.update { primary ->
            primary?.let { (generation, snapshot) ->
                generation to snapshot.withUpdatedSongMetadata(updated)
            }
        }
        cachedAllContent = cachedAllContent?.withUpdatedSongMetadata(updated)
        cachedSelectedChipContent = cachedSelectedChipContent?.withUpdatedSongMetadata(updated)
        chipContentCache = chipContentCache.mapValues { it.value.withUpdatedSongMetadata(updated) }
        saveLastHomePage(homeContentForCurrentSelection(_uiState.value, cachedAllContent, cachedSelectedChipContent))
    }

    private fun loadLastHomePage(): HomeUiState? {
        val saved = Settings.shared.lastHomePageJson
        if (saved.isBlank()) return null
        return try {
            homeCacheJson
                .decodeFromString<HomeUiState>(saved)
                .let {
                    cachedHomeStateForSession(
                        it,
                        currentContentLocaleSignature(),
                        GlobalYouTube.innerTube.sessionSnapshot().homeCacheSessionKey(),
                    )
                }?.startupSnapshot()
                ?.normalizeCachedSections()
                ?.let { it.copy(chips = homeChipsForSelection(it.chips, it.selectedChip)) }
        } catch (_: Exception) {
            null
        }
    }

    private fun HomeUiState.normalizeCachedSections(): HomeUiState {
        val cachedSections = data ?: return this
        val apiQuickPicks = findSectionSongs(cachedSections, "FEmusic_quickpicks")
        val apiKeepListening = findSectionSongs(cachedSections, "FEmusic_listen_again")
        val apiForgottenFavorites = findSectionSongs(cachedSections, "FEmusic_forgotten_favorites")
        return copy(
            trending =
                findSectionsByBrowseId(cachedSections, "FEmusic_charts")
                    .flatMap { it.items.filterIsInstance<SongItem>() }
                    .ifEmpty { trending },
            albumCharts =
                findSectionsByBrowseId(cachedSections, "FEmusic_charts")
                    .flatMap { it.items.filterIsInstance<AlbumItem>() }
                    .distinctBy { it.id }
                    .ifEmpty { albumCharts },
            quickPicks = preferredQuickPicks(apiQuickPicks, quickPicks),
            newReleases = newReleasesFrom(cachedSections).ifEmpty { newReleases },
            keepListening = apiKeepListening.ifEmpty { keepListening },
            forgottenFavorites = apiForgottenFavorites.ifEmpty { forgottenFavorites },
            moodsAndGenres = findMoodsAndGenresByBrowseId(cachedSections).ifEmpty { moodsAndGenres },
            podcasts =
                findSectionsByBrowseId(cachedSections, "FEmusic_immersive")
                    .flatMap { it.items.filterIsInstance<PodcastItem>() }
                    .ifEmpty { podcasts },
            similarRecommendations = findSimilarRecommendations(cachedSections).ifEmpty { similarRecommendations },
            recapPlaylists =
                findSectionsByBrowseId(cachedSections, "FEmusic_listening_review")
                    .flatMap { it.items.filterIsInstance<PlaylistItem>() }
                    .ifEmpty { recapPlaylists },
        )
    }

    private fun saveLastHomePage(state: HomeUiState) {
        if (state.hasNoHomeContent() || state.sessionKey != GlobalYouTube.innerTube.sessionSnapshot().homeCacheSessionKey()) return
        try {
            Settings.shared.lastHomePageJson =
                homeCacheJson.encodeToString(
                    state.startupSnapshot().copy(
                        contentLocaleSignature = currentContentLocaleSignature(),
                        isLoading = false,
                        isRefreshing = false,
                        isRevalidating = false,
                        isContentLoading = false,
                        isLoadingMore = false,
                        error = null,
                    ),
                )
        } catch (_: Exception) {
        }
    }

    private suspend fun authenticatedSpeedDialSections(
        params: String?,
        browseId: String,
    ): List<HomeSection> {
        val repository = GlobalYouTubeRepository.instance
        if (GlobalYouTube.innerTube.useLoginForBrowse || !repository.isYouTubeHistoryAvailable()) return emptyList()
        return repository
            .getHomePageData(
                params = params,
                browseId = browseId,
                setLogin = true,
            ).sections
    }

    private fun buildSpeedDialItems(
        pinned: List<SpeedDialItem>,
        recommendations: List<YTItem>,
    ): List<YTItem> =
        mergeSpeedDialItems(
            pinned =
                enrichSpeedDialPins(
                    pinned = pinned.map { it.toYTItem() },
                    metadata = latestSpeedDialItemMetadata,
                ),
            recommendations = recommendations,
        )

    private fun HomeUiState.speedDialUnpinnedItems(): List<YTItem> = speedDialItems.filterNot { it.id in speedDialPinnedIds }

    private fun HomeUiState.withSpeedDialPins(
        pinned: List<SpeedDialItem>,
        recommendations: List<YTItem>,
    ): HomeUiState =
        copy(
            speedDialItems = buildSpeedDialItems(pinned, recommendations),
            speedDialPinnedIds = pinned.map { it.id },
        )

    private fun findSectionSongs(
        sections: List<HomeSection>,
        browseIdPrefix: String,
    ): List<SongItem> =
        findSectionsByBrowseId(sections, browseIdPrefix)
            .flatMap { section -> section.items.filterIsInstance<SongItem>() }

    private fun findSectionsByBrowseId(
        sections: List<HomeSection>,
        browseIdPrefix: String,
    ): List<HomeSection> =
        sections.filter { section ->
            section.browseId?.startsWith(browseIdPrefix) == true
        }

    private fun findNewReleaseSections(sections: List<HomeSection>): List<HomeSection> =
        sections.filter { section ->
            section.browseId?.startsWith("FEmusic_new_releases") == true &&
                !section.browseId.endsWith("_videos")
        }

    private fun newReleasesFrom(sections: List<HomeSection>): List<AlbumItem> =
        findNewReleaseSections(sections)
            .flatMap { it.items }
            .filterIsInstance<AlbumItem>()
            .distinctBy(::canonicalHomeItemId)

    private fun findMoodsAndGenresByBrowseId(sections: List<HomeSection>): List<Genre> {
        val genreSections = sections.filter(HomeSection::isMoodAndGenresSection)
        return genreSections
            .flatMap { section ->
                section.items.filterIsInstance<BrowseItem>().map { item ->
                    Genre(item.title, item.id, item.thumbnail, item.params)
                }
            }.distinctBy { it.id to it.params }
    }

    private fun findSimilarRecommendations(sections: List<HomeSection>): List<SimilarRecommendation> =
        sections
            .filter { section -> section.browseId?.startsWith("FEmusic_similar") == true }
            .mapNotNull { section ->
                val items = section.items.distinctBy(::canonicalHomeItemId)
                if (items.isEmpty()) return@mapNotNull null
                val source = ArtistItem(section.headerBrowseId.orEmpty(), section.title, section.thumbnail)
                SimilarRecommendation(source, items, section.browseId, section.params, section.subtitle)
            }

    @Serializable
    data class HomeUiState(
        @Transient val isRestoringCache: Boolean = false,
        val data: List<HomeSection>? = null,
        val discoverySections: List<HomeSection> = emptyList(),
        val contentLocaleSignature: String? = null,
        val sessionKey: String? = null,
        val isLoading: Boolean = false,
        val isRefreshing: Boolean = false,
        val isRevalidating: Boolean = false,
        val isContentLoading: Boolean = false,
        val isAllContentPending: Boolean = false,
        val isLoadingMore: Boolean = false,
        val loadMoreError: Boolean = false,
        val error: String? = null,
        val selectedFilter: String? = null,
        val selectedChip: Chip? = null,
        val chips: List<Chip> = emptyList(),
        val continuation: String? = null,
        @Transient val chipFeeds: List<HomeChipFeed> = emptyList(),
        @Transient val usedHomeChips: List<Chip> = emptyList(),
        @Transient val chipFeedGeneration: Long = 0,
        val trending: List<SongItem> = emptyList(),
        val albumCharts: List<AlbumItem> = emptyList(),
        val quickPicks: List<SongItem> = emptyList(),
        val speedDialItems: List<YTItem> = emptyList(),
        val speedDialPinnedIds: List<String> = emptyList(),
        val speedDialSurprisePinned: Boolean = false,
        val newReleases: List<AlbumItem> = emptyList(),
        val keepListening: List<SongItem> = emptyList(),
        val forgottenFavorites: List<SongItem> = emptyList(),
        val communityPlaylist: List<PlaylistItem> = emptyList(),
        val moodsAndGenres: List<Genre> = emptyList(),
        val recentlyPlayed: List<SongItem> = emptyList(),
        val recapPlaylists: List<PlaylistItem> = emptyList(),
        val podcasts: List<PodcastItem> = emptyList(),
        val similarRecommendations: List<SimilarRecommendation> = emptyList(),
        val userPlaylists: List<UserPlaylist> = emptyList(),
        val sections: List<HomeSection> = emptyList(),
    ) {
        fun loadedHomeSongs(): List<SongItem> =
            buildList {
                addAll(quickPicks)
                addAll(trending)
                addAll(keepListening)
                addAll(forgottenFavorites)
                addAll(sections.flatMap(HomeSection::items))
                addAll(data.orEmpty().flatMap(HomeSection::items))
                addAll(speedDialItems)
                addAll(chipFeeds.flatMap { feed -> feed.sections.flatMap(HomeSection::items) })
            }.mapNotNull { item ->
                when (item) {
                    is SongItem -> item
                    is EpisodeItem -> item.toSongItem()
                    else -> null
                }
            }.filterNot { it.isNonMusicalVideo || it.isPodcast }
                .distinctBy(SongItem::id)

        val showLoadingPlaceholder: Boolean
            get() =
                (isRestoringCache || isLoading || isRefreshing || isContentLoading || isAllContentPending) &&
                    !hasHomeFeedContent()

        fun hasNoHomeContent(): Boolean = !hasHomeFeedContent()

        fun hasHomeFeedContent(): Boolean =
            trending.isNotEmpty() || albumCharts.isNotEmpty() ||
                data.orEmpty().any { it.items.isNotEmpty() } || discoverySections.any { it.items.isNotEmpty() } ||
                quickPicks.isNotEmpty() || speedDialItems.isNotEmpty() || newReleases.isNotEmpty() ||
                keepListening.isNotEmpty() || forgottenFavorites.isNotEmpty() || communityPlaylist.isNotEmpty() ||
                moodsAndGenres.isNotEmpty() || recapPlaylists.isNotEmpty() || podcasts.isNotEmpty() ||
                similarRecommendations.any { it.items.isNotEmpty() } || sections.any { it.items.isNotEmpty() } ||
                chipFeeds.any { feed -> feed.sections.any { it.items.isNotEmpty() } }
    }

    @Serializable
    data class Genre(
        val name: String,
        val id: String,
        val thumbnail: String? = null,
        val params: String? = null,
    )

    @Serializable
    data class SimilarRecommendation(
        val artist: ArtistItem,
        val items: List<YTItem>,
        val browseId: String? = null,
        val params: String? = null,
        val label: String? = null,
    )
}

private fun currentContentLocaleSignature(): String = GlobalYouTube.innerTube.locale.let { "${it.hl}:${it.gl}" }

internal fun cachedHomeStateForSession(
    state: HomeViewModel.HomeUiState,
    contentLocaleSignature: String,
    sessionKey: String,
): HomeViewModel.HomeUiState? = state.takeIf { it.contentLocaleSignature == contentLocaleSignature && it.sessionKey == sessionKey }

internal fun homeContentForCurrentSelection(
    currentState: HomeViewModel.HomeUiState,
    cachedAllContent: HomeViewModel.HomeUiState?,
    cachedSelectedChipContent: HomeViewModel.HomeUiState?,
): HomeViewModel.HomeUiState =
    when {
        currentState.isLoading || currentState.isRefreshing || currentState.isRevalidating ||
            currentState.isContentLoading || currentState.isAllContentPending -> currentState

        currentState.selectedChip == null -> cachedAllContent

        else -> cachedSelectedChipContent?.takeIf { it.selectedChip == currentState.selectedChip }
    } ?: currentState

internal fun pendingHomeChipContent(
    currentState: HomeViewModel.HomeUiState,
    chip: Chip,
): HomeViewModel.HomeUiState =
    HomeViewModel.HomeUiState(
        selectedChip = chip,
        chips = homeChipsForSelection(currentState.chips, chip),
        contentLocaleSignature = currentState.contentLocaleSignature,
        userPlaylists = currentState.userPlaylists,
        isContentLoading = true,
    )

internal fun homeContentForAllSelection(
    currentState: HomeViewModel.HomeUiState,
    cachedAllContent: HomeViewModel.HomeUiState?,
): HomeViewModel.HomeUiState =
    cachedAllContent?.copy(selectedChip = null, isAllContentPending = false)
        ?: currentState.copy(
            data = null,
            discoverySections = emptyList(),
            trending = emptyList(),
            albumCharts = emptyList(),
            quickPicks = emptyList(),
            newReleases = emptyList(),
            keepListening = emptyList(),
            forgottenFavorites = emptyList(),
            communityPlaylist = emptyList(),
            moodsAndGenres = emptyList(),
            recentlyPlayed = emptyList(),
            recapPlaylists = emptyList(),
            podcasts = emptyList(),
            similarRecommendations = emptyList(),
            continuation = null,
            sections = emptyList(),
            chipFeeds = emptyList(),
            selectedChip = null,
            isContentLoading = true,
            isLoadingMore = false,
            isAllContentPending = true,
        )

internal fun isCacheableAllContent(
    state: HomeViewModel.HomeUiState,
    reloadRequired: Boolean = false,
): Boolean =
    !reloadRequired &&
        !state.isAllContentPending &&
        !isFilteredHome(state.selectedChip?.params, state.selectedChip?.browseId ?: "FEmusic_home")

internal fun shouldReloadIncompleteAllContent(
    reloadRequired: Boolean,
    state: HomeViewModel.HomeUiState,
): Boolean = state.isAllContentPending || reloadRequired

internal fun shouldKeepIncompleteAllContentPendingAfterFailure(
    primaryContentPublished: Boolean,
    cachedAllContent: HomeViewModel.HomeUiState?,
    hadIncompleteAllContent: Boolean,
): Boolean = !primaryContentPublished && (cachedAllContent == null || hadIncompleteAllContent || cachedAllContent.isAllContentPending)

internal fun HomeViewModel.HomeUiState.withLatestLibraryAndSpeedDialState(
    latestState: HomeViewModel.HomeUiState,
): HomeViewModel.HomeUiState =
    copy(
        userPlaylists = latestState.userPlaylists,
        speedDialItems = latestState.speedDialItems,
        speedDialPinnedIds = latestState.speedDialPinnedIds,
        speedDialSurprisePinned = latestState.speedDialSurprisePinned,
    )

internal fun HomeViewModel.HomeUiState.withLatestSongMetadataFrom(latestState: HomeViewModel.HomeUiState): HomeViewModel.HomeUiState =
    latestState.loadedHomeSongs().fold(this) { snapshot, song ->
        snapshot.withUpdatedSongMetadata(song)
    }

internal fun isCurrentHomeLibraryRevision(
    snapshotRevision: Long,
    publishedRevision: Long,
): Boolean = snapshotRevision == publishedRevision

internal fun shouldLoadMoreHomeContent(state: HomeViewModel.HomeUiState): Boolean =
    !state.isLoading &&
        !state.isRefreshing &&
        !state.isRestoringCache &&
        !state.isAllContentPending &&
        !state.isRevalidating &&
        !state.isContentLoading

internal fun cacheableAllContentSnapshot(
    state: HomeViewModel.HomeUiState,
    reloadRequired: Boolean = false,
): HomeViewModel.HomeUiState? =
    if (!isCacheableAllContent(state, reloadRequired)) {
        null
    } else {
        state.copy(
            isLoading = false,
            isRefreshing = false,
            isRevalidating = false,
            isContentLoading = false,
            isLoadingMore = false,
            error = null,
            selectedChip = null,
        )
    }

internal fun homeCacheAfterAssemblyFailure(
    cachedAllContent: HomeViewModel.HomeUiState?,
    failedState: HomeViewModel.HomeUiState,
    primaryContentPublished: Boolean,
): HomeViewModel.HomeUiState? =
    if (primaryContentPublished) cacheableAllContentSnapshot(failedState) ?: cachedAllContent else cachedAllContent

internal fun homeCacheAfterOfflineCancellation(
    cachedAllContent: HomeViewModel.HomeUiState?,
    primaryContent: HomeViewModel.HomeUiState,
    publishedGeneration: Long,
    cancelledGeneration: Long,
): HomeViewModel.HomeUiState? =
    if (publishedGeneration == cancelledGeneration) {
        cacheableAllContentSnapshot(primaryContent.copy(isAllContentPending = false)) ?: cachedAllContent
    } else {
        cachedAllContent
    }

internal fun homeSelectedChipCacheAfterAssemblyFailure(
    cachedSelectedChipContent: HomeViewModel.HomeUiState?,
    primaryContentSnapshot: HomeViewModel.HomeUiState?,
    failedState: HomeViewModel.HomeUiState,
    primaryContentPublished: Boolean,
): HomeViewModel.HomeUiState? =
    if (primaryContentPublished) {
        (primaryContentSnapshot ?: failedState).copy(
            userPlaylists = failedState.userPlaylists,
            isLoading = false,
            isRefreshing = false,
            isRevalidating = false,
            isContentLoading = false,
            isLoadingMore = false,
        )
    } else {
        cachedSelectedChipContent
    }

internal fun homeChipSnapshotWithRecoveredContinuation(
    selectedChipSnapshot: HomeViewModel.HomeUiState,
    primaryFailureSnapshot: HomeViewModel.HomeUiState?,
): HomeViewModel.HomeUiState =
    if (primaryFailureSnapshot ==
        null
    ) {
        selectedChipSnapshot
    } else {
        selectedChipSnapshot.copy(continuation = primaryFailureSnapshot.continuation)
    }

internal fun homeSelectedChipCacheAfterOfflineCancellation(
    cachedSelectedChipContent: HomeViewModel.HomeUiState?,
    interruptedState: HomeViewModel.HomeUiState,
): HomeViewModel.HomeUiState? {
    val selectedChip = interruptedState.selectedChip ?: return null
    val publishedContent =
        cachedSelectedChipContent?.takeIf { it.selectedChip == selectedChip }
            ?: interruptedState.takeIf { it.hasHomeFeedContent() }
    return publishedContent?.asOfflineSnapshot()
}

internal fun homeContinuationAfterAssemblyFailure(
    currentContinuation: String?,
    primaryContinuation: String?,
    primaryContentPublished: Boolean,
): String? = if (primaryContentPublished) primaryContinuation else currentContinuation

internal fun homeContentAfterBlockedContentChange(
    currentState: HomeViewModel.HomeUiState,
    cachedAllContent: HomeViewModel.HomeUiState?,
    cachedSelectedChipContent: HomeViewModel.HomeUiState?,
    pendingAllContent: HomeViewModel.HomeUiState? = null,
    pendingChipContent: HomeViewModel.HomeUiState? = null,
): HomeViewModel.HomeUiState =
    (
        when {
            currentState.selectedChip != null && pendingChipContent?.selectedChip == currentState.selectedChip -> {
                pendingChipContent.withCurrentHomeLoadState(currentState).copy(continuation = null)
            }

            currentState.selectedChip == null && pendingAllContent != null -> {
                pendingAllContent.withCurrentHomeLoadState(currentState)
            }

            currentState.selectedChip == null && currentState.isAllContentPending -> {
                pendingAllContent ?: currentState
            }

            currentState.selectedChip == null &&
                (currentState.isLoading || currentState.isRefreshing || currentState.isContentLoading || currentState.isRevalidating) -> {
                (cachedAllContent ?: currentState).withCurrentHomeLoadState(currentState)
            }

            else -> {
                homeContentForCurrentSelection(currentState, cachedAllContent, cachedSelectedChipContent)
                    .withCurrentHomeLoadState(currentState)
            }
        }
    ).withoutBlockedContent()

internal fun HomeViewModel.HomeUiState.withCurrentHomeLoadState(currentState: HomeViewModel.HomeUiState): HomeViewModel.HomeUiState =
    copy(
        isLoading = currentState.isLoading,
        isRefreshing = currentState.isRefreshing,
        isRevalidating = currentState.isRevalidating,
        isContentLoading = currentState.isContentLoading,
        isLoadingMore = currentState.isLoadingMore,
        isAllContentPending = currentState.isAllContentPending,
    )

internal fun HomeViewModel.HomeUiState.withoutBlockedContent(): HomeViewModel.HomeUiState {
    val snapshot = BlockedContent.snapshot()
    return copy(
        data = data?.let { BlockedContent.filterSections(it, snapshot) },
        discoverySections = BlockedContent.filterSections(discoverySections, snapshot),
        chips = chips.filterPodcastChips(Settings.shared.hidePodcasts),
        trending = BlockedContent.filterSongs(trending, snapshot),
        albumCharts = BlockedContent.filterItems(albumCharts, snapshot).filterIsInstance<AlbumItem>(),
        quickPicks = BlockedContent.filterSongs(quickPicks, snapshot),
        speedDialItems = BlockedContent.filterItems(speedDialItems, snapshot),
        newReleases = BlockedContent.filterItems(newReleases, snapshot).filterIsInstance<AlbumItem>(),
        keepListening = BlockedContent.filterSongs(keepListening, snapshot),
        forgottenFavorites = BlockedContent.filterSongs(forgottenFavorites, snapshot),
        communityPlaylist = BlockedContent.filterItems(communityPlaylist, snapshot).filterIsInstance<PlaylistItem>(),
        recapPlaylists = BlockedContent.filterItems(recapPlaylists, snapshot).filterIsInstance<PlaylistItem>(),
        podcasts = BlockedContent.filterItems(podcasts, snapshot).filterIsInstance<PodcastItem>(),
        similarRecommendations =
            similarRecommendations.mapNotNull { recommendation ->
                val items = BlockedContent.filterItems(recommendation.items, snapshot)
                if (BlockedContent.isBlocked(recommendation.artist, snapshot) || items.isEmpty()) {
                    null
                } else {
                    recommendation.copy(items = items)
                }
            },
        sections = BlockedContent.filterSections(sections.withLibraryMusicAlbumsOnly(), snapshot),
        chipFeeds =
            chipFeeds.map { feed ->
                if (Settings.shared.hidePodcasts && feed.chip.isPodcastChip) {
                    feed.copy(sections = emptyList(), continuation = null)
                } else {
                    feed.copy(sections = BlockedContent.filterSections(feed.sections.withLibraryMusicAlbumsOnly(), snapshot))
                }
            },
    )
}

internal fun SongItem.withUpdatedMetadata(updated: SongItem): SongItem =
    if (id != updated.id) {
        this
    } else {
        copy(
            title = updated.title.ifBlank { title },
            thumbnail = updated.thumbnail ?: thumbnail,
            artists = updated.artists.ifEmpty { artists },
            album = updated.album ?: album,
            duration = updated.duration?.takeIf { it > 0 } ?: duration,
            isExplicit = updated.isExplicit,
            endpointParams = updated.endpointParams ?: endpointParams,
            libraryAddToken = updated.libraryAddToken ?: libraryAddToken,
            libraryRemoveToken = updated.libraryRemoveToken ?: libraryRemoveToken,
            musicVideoType = updated.musicVideoType ?: musicVideoType,
            year = updated.year ?: year,
            trackNumber = updated.trackNumber ?: trackNumber,
            totalTrackCount = updated.totalTrackCount ?: totalTrackCount,
            genre = updated.genre ?: genre,
            customTitle = updated.customTitle,
            customArtist = updated.customArtist,
            canonicalTitle = updated.canonicalTitle,
            canonicalArtists = updated.canonicalArtists,
            explicitness = updated.artworkExplicitness,
        )
    }

private fun List<YTItem>.withUpdatedSongItems(updated: SongItem): List<YTItem> =
    map { item -> if (item is SongItem) item.withUpdatedMetadata(updated) else item }

private fun List<HomeSection>.withUpdatedSongSections(updated: SongItem): List<HomeSection> =
    map { section -> section.copy(items = section.items.withUpdatedSongItems(updated)) }

internal fun HomeViewModel.HomeUiState.withUpdatedSongMetadata(updated: SongItem): HomeViewModel.HomeUiState =
    copy(
        data = data?.withUpdatedSongSections(updated),
        discoverySections = discoverySections.withUpdatedSongSections(updated),
        trending = trending.map { it.withUpdatedMetadata(updated) },
        quickPicks = quickPicks.map { it.withUpdatedMetadata(updated) },
        speedDialItems = speedDialItems.withUpdatedSongItems(updated),
        keepListening = keepListening.map { it.withUpdatedMetadata(updated) },
        forgottenFavorites = forgottenFavorites.map { it.withUpdatedMetadata(updated) },
        recentlyPlayed = recentlyPlayed.map { it.withUpdatedMetadata(updated) },
        similarRecommendations =
            similarRecommendations.map { recommendation ->
                recommendation.copy(items = recommendation.items.withUpdatedSongItems(updated))
            },
        sections = sections.withUpdatedSongSections(updated),
        chipFeeds = chipFeeds.map { it.copy(sections = it.sections.withUpdatedSongSections(updated)) },
    )

internal data class HomeSessionIdentity(
    val cookie: String?,
    val visitorData: String?,
    val dataSyncId: String?,
    val authUser: String,
    val useLoginForBrowse: Boolean,
    val regionOverrideActive: Boolean,
)

internal fun InnerTube.SessionSnapshot.homeSessionIdentity(): HomeSessionIdentity =
    HomeSessionIdentity(
        cookie = cookie,
        visitorData = visitorData.takeUnless { regionOverrideActive },
        dataSyncId = dataSyncId,
        authUser = authUser,
        useLoginForBrowse = useLoginForBrowse,
        regionOverrideActive = regionOverrideActive,
    )

internal fun shouldApplyGlobalSpeedDialPins(selectedChip: Chip?): Boolean = selectedChip == null

internal fun homeChipsForSelection(
    chips: List<Chip>,
    selectedChip: Chip?,
): List<Chip> =
    chips.map { chip ->
        if (chip.title == selectedChip?.title) selectedChip else chip
    }

internal fun isFilteredHome(
    params: String?,
    browseId: String,
): Boolean = params != null || browseId != "FEmusic_home"

internal fun HomeSection.isMoodAndGenresSection(): Boolean =
    browseId?.startsWith("FEmusic_moods_and_genres") == true && items.any { it is BrowseItem }

internal fun List<HomeSection>.withLibraryMusicAlbumsOnly(savedAlbumIds: Set<String>? = null): List<HomeSection> =
    mapNotNull { section ->
        if (section.browseId?.startsWith("FEmusic_library") == true) {
            val isPodcastShelf =
                section.browseId.startsWith("FEmusic_library_non_music_audio") || section.browseId.startsWith("FEmusic_library_podcast")
            val items =
                section.items.filter {
                    if (isPodcastShelf) it.isPodcast else it is AlbumItem && (savedAlbumIds == null || it.id in savedAlbumIds)
                }
            section.copy(items = items).takeIf { items.isNotEmpty() }
        } else {
            section
        }
    }

fun uncategorizedHomeSections(sections: List<HomeSection>): List<HomeSection> =
    sections.withLibraryMusicAlbumsOnly().mapNotNull { section ->
        val remainingItems =
            when {
                section.browseId?.startsWith("FEmusic_charts") == true -> {
                    section.items.filterNot { it is SongItem }
                }

                section.browseId?.startsWith("FEmusic_new_releases") == true -> {
                    section.items.filterNot { it is AlbumItem }
                }

                section.browseId?.startsWith("FEmusic_listening_review") == true -> {
                    section.items.filterNot { it is PlaylistItem }
                }

                section.browseId?.startsWith("FEmusic_immersive") == true -> {
                    section.items.filterNot { it is PodcastItem }
                }

                section.browseId?.startsWith("FEmusic_community") == true -> {
                    section.items.filterNot { it is PlaylistItem }
                }

                section.isMoodAndGenresSection() -> {
                    section.items.filterNot { it is BrowseItem }
                }

                section.browseId?.startsWith("FEmusic_quickpicks") == true ||
                    section.browseId?.startsWith("FEmusic_listen_again") == true ||
                    section.browseId?.startsWith("FEmusic_forgotten_favorites") == true -> {
                    section.items.filterNot { it is SongItem }
                }

                section.browseId?.startsWith("FEmusic_similar") == true -> {
                    emptyList()
                }

                else -> {
                    section.items
                }
            }
        val uniqueItems = remainingItems.distinctBy(::canonicalHomeItemId)
        section.copy(items = uniqueItems).takeIf { uniqueItems.isNotEmpty() }
    }

internal data class HomeFilterIdentity(
    val songs: Set<String>,
    val artists: Set<String>,
    val settings: List<Boolean>,
    val minimumDuration: Long = 0L,
    val aiArtists: Set<String> = emptySet(),
) {
    fun requiresRefreshAfter(previous: HomeFilterIdentity): Boolean =
        !songs.containsAll(previous.songs) || !artists.containsAll(previous.artists) ||
            settings.zip(previous.settings).any { (current, old) -> old && !current } ||
            minimumDuration < previous.minimumDuration || !aiArtists.containsAll(previous.aiArtists)
}

private fun homeFilterIdentity() =
    HomeFilterIdentity(
        songs = BlockedContent.songIds(),
        artists = BlockedContent.artists().mapTo(mutableSetOf()) { "${it.id}:${it.name}" },
        settings =
            Settings.shared.run {
                listOf(explicitOnly, hideExplicit && !explicitOnly, hideVideoSongs, hideNonMusicalVideos, hidePodcasts)
            },
        minimumDuration = Settings.shared.run { if (hideShortSongs) minimumSongDurationSeconds.toLong() else 0L },
        aiArtists = Settings.shared.run { if (blockAiSlopArtists) AiSlopBlocklist.parse(aiSlopBlocklistCache) else emptySet() },
    )

internal fun <K, T> boundedHomeChipCache(
    cache: Map<K, T>,
    key: K,
    value: T,
): Map<K, T> =
    ((cache - key) + (key to value))
        .entries
        .toList()
        .takeLast(HOME_CHIP_CACHE_SIZE)
        .associate { it.toPair() }

internal fun isFreshHomeChip(
    updatedAt: Long?,
    now: Long,
): Boolean = updatedAt != null && now - updatedAt in 0 until HOME_CHIP_FRESHNESS_MS

private data class HomeChipKey(
    val locale: String,
    val session: HomeSessionIdentity,
    val browseId: String?,
    val params: String?,
)

internal fun InnerTube.SessionSnapshot.homeCacheSessionKey(): String =
    Base64.encode(
        DeviceCrypto.sha256(
            Json
                .encodeToString(
                    listOf(
                        cookie,
                        visitorData.takeUnless {
                            regionOverrideActive
                        },
                        dataSyncId,
                        authUser,
                        useLoginForBrowse.toString(),
                        regionOverrideActive.toString(),
                    ),
                ).encodeToByteArray(),
        ),
    )

internal fun HomeViewModel.HomeUiState.withoutHiddenHomeItems(filter: HomeContentFilter): HomeViewModel.HomeUiState =
    copy(
        data = data?.let(filter::filterSections),
        discoverySections = filter.filterSections(discoverySections),
        trending = filter.filterItems(trending),
        albumCharts = filter.filterItems(albumCharts),
        quickPicks = filter.filterItems(quickPicks),
        speedDialItems = speedDialItems.filter { it.id in speedDialPinnedIds || !filter.hides(it) },
        newReleases = filter.filterItems(newReleases),
        keepListening = filter.filterItems(keepListening),
        forgottenFavorites = filter.filterItems(forgottenFavorites),
        recentlyPlayed = filter.filterItems(recentlyPlayed),
        communityPlaylist = filter.filterItems(communityPlaylist),
        recapPlaylists = filter.filterItems(recapPlaylists),
        podcasts = filter.filterItems(podcasts),
        similarRecommendations =
            similarRecommendations.mapNotNull { recommendation ->
                val items = filter.filterItems(recommendation.items)
                if (filter.hides(recommendation.artist) || items.isEmpty()) null else recommendation.copy(items = items)
            },
        sections = filter.filterSections(sections),
    )

/**
 * Classic (13.x) Quick picks: songs related to what you played recently plus a few forgotten
 * favourites, all from the local listening history, shuffled.
 */
internal fun classicQuickPicks(
    localQuickPicks: List<SongItem>,
    localForgottenFavorites: List<SongItem>,
): List<SongItem> =
    // Filter before sampling so rejected rows do not consume the quota. Home
    // shelves always strip non-musical videos, so the local pool does too.
    BlockedContent
        .filterSongs(
            (localQuickPicks + localForgottenFavorites.take(8)).distinctBy { it.id },
            BlockedContent.snapshot(),
        ).filterNot { it.isNonMusicalVideo }
        .shuffled()
        .take(20)

private fun preferredQuickPicks(
    apiQuickPicks: List<SongItem>,
    current: List<SongItem>,
): List<SongItem> =
    if (Settings.shared.classicRecommendations) {
        current.ifEmpty { apiQuickPicks }
    } else {
        apiQuickPicks.ifEmpty { current }
    }
