package com.metrolist.shared.viewmodels

import androidx.compose.runtime.snapshotFlow
import com.metrolist.shared.api.YouTubeRepository
import com.metrolist.shared.auth.YouTubeSyncState
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.db.entities.PlaylistEntity
import com.metrolist.shared.db.entities.toSongItem
import com.metrolist.shared.db.preserveKnownPlaylistEditability
import com.metrolist.shared.db.refreshHistoryPlaylists
import com.metrolist.shared.db.repairRemotePlaylistMembership
import com.metrolist.shared.db.syncRemoteLibraryItems
import com.metrolist.shared.db.syncRemotePlaylists
import com.metrolist.shared.db.synchronizeUploadedSongs
import com.metrolist.shared.db.withDatabaseTransaction
import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.model.ArtistItem
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.BrowseItem
import com.metrolist.shared.model.EpisodeItem
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.model.PodcastItem
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.YTItem
import com.metrolist.shared.model.librarySearchKey
import com.metrolist.shared.model.parseDisplayCount
import com.metrolist.shared.playlist.LibraryBookmarkMutations
import com.metrolist.shared.playlist.LibraryBookmarkSnapshot
import com.metrolist.shared.playlist.LibraryDeletionTracker
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.state.GlobalYouTubeRepository
import com.metrolist.shared.state.PodcastLibrary
import com.metrolist.shared.youtube.logging.YtLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds

const val LIBRARY_REFRESH_TTL_MILLIS = 5 * 60 * 1000L
const val LIBRARY_SEARCH_DEBOUNCE_MILLIS = 300L
internal const val LIBRARY_SEARCH_PAGE_SIZE = 200

fun shouldRefreshLibrary(
    nowEpochMillis: Long,
    lastRefreshEpochMillis: Long,
): Boolean = lastRefreshEpochMillis <= 0L || nowEpochMillis - lastRefreshEpochMillis >= LIBRARY_REFRESH_TTL_MILLIS

fun mergeLibraryItems(
    local: List<YTItem>,
    remote: List<YTItem>,
    downloadedKeys: Set<String> = emptySet(),
): List<YTItem> {
    val localByKey = local.associateBy(::libraryItemKey)
    return (remote + local)
        .distinctBy(::libraryItemKey)
        .map { item -> item.withLibraryMetadata(localByKey[libraryItemKey(item)]).withDownloadedState(downloadedKeys) }
}

private fun YTItem.withDownloadedState(downloadedKeys: Set<String>): YTItem =
    when {
        this is PlaylistItem && !isDownloaded && "playlist:${id.removePrefix("VL")}" in downloadedKeys -> copy(isDownloaded = true)
        this is AlbumItem && !isDownloaded && "album:$id" in downloadedKeys -> copy(isDownloaded = true)
        else -> this
    }

private fun YTItem.withLibraryMetadata(local: YTItem?): YTItem =
    when {
        this is ArtistItem && local is ArtistItem -> {
            copy(
                songCount = local.songCount ?: songCount,
                playtime = local.playtime ?: playtime,
                libraryAddedAt = local.libraryAddedAt,
                libraryUpdatedAt = local.libraryUpdatedAt,
            )
        }

        this is AlbumItem && local is AlbumItem -> {
            copy(
                artists = artists.ifEmpty { local.artists },
                isDownloaded = local.isDownloaded,
                songCount = local.songCount ?: songCount,
                duration = local.duration ?: duration,
                playtime = local.playtime ?: playtime,
                libraryAddedAt = local.libraryAddedAt,
                libraryUpdatedAt = local.libraryUpdatedAt,
            )
        }

        this is PlaylistItem && local is PlaylistItem -> {
            copy(
                thumbnail = local.customThumbnail ?: thumbnail ?: local.thumbnail,
                isLocal = local.isLocal,
                artworkThumbnails = local.artworkThumbnails,
                customThumbnail = local.customThumbnail,
                isDownloaded = local.isDownloaded,
                author = author ?: local.author,
                songCount = local.songCount ?: songCount,
                playtime = local.playtime ?: playtime,
                libraryAddedAt = local.libraryAddedAt,
                libraryUpdatedAt = local.libraryUpdatedAt,
            )
        }

        this is PodcastItem && local is PodcastItem -> {
            copy(
                libraryAddedAt = local.libraryAddedAt,
                libraryUpdatedAt = local.libraryUpdatedAt,
            )
        }

        else -> {
            this
        }
    }

@OptIn(FlowPreview::class)
class LibraryViewModel(
    private val scope: CoroutineScope,
    private val database: MusicDatabase? = null,
    private val downloadedSongIds: Flow<Set<String>>? = null,
    private val repository: YouTubeRepository = GlobalYouTubeRepository.instance,
) {
    private val _uiState = MutableStateFlow(LibraryUiState(isLoading = database != null))
    private var downloadedKeys: Set<String> = emptySet()
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()
    val presentation = LibraryPresentation()
    private val localActive = MutableStateFlow(false)
    private val localReloads = Channel<Unit>(Channel.CONFLATED)
    private var reconciliationFilter: LibraryReconciliationFilter? = null

    private data class SearchRequest(
        val query: String = "",
        val descending: Boolean = false,
        val revision: Long = 0L,
    )

    private val searchRequest = MutableStateFlow(SearchRequest())
    private var loadedSearchRequest: SearchRequest? = null
    private var searchPageJob: Job? = null
    private val _contentSearchHasMore = MutableStateFlow(false)
    val contentSearchHasMore = _contentSearchHasMore.asStateFlow()
    private val _contentMatchedSongs = MutableStateFlow<List<SongItem>>(emptyList())
    val contentMatchedSongs: StateFlow<List<SongItem>> = _contentMatchedSongs.asStateFlow()
    private var hasRefreshedAfterHomeLoaded = false
    private var refreshInProgress = false
    private var countResolutionJob: Job? = null
    private var pendingRefresh = false
    private var refreshGeneration = 0L
    private var playlistDeletionVersion = 0L
    private var cachedRemoteItems: List<YTItem> = emptyList()
    private var cachedRemoteAccountKey: String? = null
    private var observedAccountKey = currentLibraryAccountKey()
    private var cachedBookmarkSnapshot = LibraryBookmarkMutations.capture()

    fun setActive(active: Boolean) {
        localActive.value = active
        if (!active) searchPageJob?.cancel()
    }

    fun setSearchQuery(query: String) {
        updateSearchRequest(searchRequest.value.copy(query = query))
    }

    fun setSearchDescending(descending: Boolean) {
        updateSearchRequest(searchRequest.value.copy(descending = descending))
    }

    private fun updateSearchRequest(request: SearchRequest) {
        if (searchRequest.value == request) return
        searchPageJob?.cancel()
        loadedSearchRequest = null
        _contentMatchedSongs.value = emptyList()
        _contentSearchHasMore.value = false
        searchRequest.value = request.copy(revision = request.revision + 1L)
    }

    fun loadMoreContentMatches() {
        val request = loadedSearchRequest ?: return
        if (!localActive.value || !_contentSearchHasMore.value || searchPageJob?.isActive == true) return
        searchPageJob =
            scope.launch {
                val page =
                    database
                        ?.let {
                            contentMatchedSongs(
                                it,
                                request.query,
                                offset = _contentMatchedSongs.value.size,
                                descending = request.descending,
                            )
                        }.orEmpty()
                if (loadedSearchRequest == request && searchRequest.value == request) {
                    _contentMatchedSongs.value += page
                    _contentSearchHasMore.value = page.size == LIBRARY_SEARCH_PAGE_SIZE
                }
            }
    }

    fun onItemDeleted(item: YTItem) {
        if (item is PlaylistItem) {
            onPlaylistDeleted(item.id)
            return
        }
        val key = libraryItemKey(item)
        cachedRemoteItems = cachedRemoteItems.filterNot { libraryItemKey(it) == key }
        _uiState.update { current ->
            current.copy(items = current.items.filterNot { libraryItemKey(it) == key })
        }
    }

    fun onPlaylistDeleted(id: String) {
        playlistDeletionVersion++
        val normalizedId = id.removePrefix("VL")
        cachedRemoteItems =
            cachedRemoteItems.filterNot {
                it is PlaylistItem && it.id.removePrefix("VL") == normalizedId
            }
        _uiState.update { current ->
            current.copy(
                items =
                    current.items.filterNot {
                        it is PlaylistItem && it.id.removePrefix("VL") == normalizedId
                    },
            )
        }
    }

    init {
        scope.launch {
            localActive.collectLatest { active ->
                if (active) observeLocal()
            }
        }
        scope.launch {
            val accountKey = currentLibraryAccountKey()
            val cachedAccount = Settings.shared.lastLibraryRefreshAccount
            val accountChanged = cachedAccount.isNotBlank() && cachedAccount != accountKey
            if (accountChanged) {
                LibraryDeletionTracker.clear()
                cachedRemoteItems = emptyList()
                Settings.shared.lastLibraryRefreshEpoch = 0L
                _uiState.update { it.copy(isLoading = true, items = emptyList()) }
            }
            if (cachedAccount.isBlank()) Settings.shared.lastLibraryRefreshAccount = accountKey
            refresh(force = accountChanged)
        }
    }

    private suspend fun observeLocal() =
        coroutineScope {
            launch {
                localReloads.receiveAsFlow().debounce(100.milliseconds).collect { loadLocal() }
            }
            launch {
                PodcastLibrary.libraryChanges.collect {
                    localReloads.trySend(Unit)
                }
            }
            launch {
                database?.dao()?.songsByCreateDateAsc()?.distinctUntilChanged()?.collect {
                    localReloads.trySend(Unit)
                }
            }
            launch {
                database?.dao()?.allPlaylists()?.distinctUntilChanged()?.collect {
                    localReloads.trySend(Unit)
                }
            }
            launch {
                database?.dao()?.albumsLikedByCreateDateAsc()?.distinctUntilChanged()?.collect {
                    localReloads.trySend(Unit)
                }
            }
            launch {
                val dao = database?.dao() ?: return@launch
                if (downloadedSongIds == null) {
                    dao.downloadedCollectionKeys().distinctUntilChanged().collect { localReloads.trySend(Unit) }
                } else {
                    downloadedSongIds.collectLatest { ids ->
                        dao.downloadedCollectionKeys(ids.toList()).distinctUntilChanged().collect { localReloads.trySend(Unit) }
                    }
                }
            }
            launch {
                snapshotFlow { Settings.shared.historyPlaylistsEnabled }
                    .drop(1)
                    .collect { localReloads.trySend(Unit) }
            }
            launch {
                searchRequest
                    .debounce(LIBRARY_SEARCH_DEBOUNCE_MILLIS.milliseconds)
                    .distinctUntilChanged()
                    .collectLatest { request ->
                        val page = database?.let { contentMatchedSongs(it, request.query, descending = request.descending) }.orEmpty()
                        if (searchRequest.value == request) {
                            loadedSearchRequest = request
                            _contentMatchedSongs.value = page
                            _contentSearchHasMore.value = page.size == LIBRARY_SEARCH_PAGE_SIZE
                        }
                    }
            }
            localReloads.trySend(Unit)
        }

    fun refresh(force: Boolean = true) {
        val accountKey = currentLibraryAccountKey()
        val accountChanged =
            observedAccountKey != accountKey ||
                Settings.shared.lastLibraryRefreshAccount.let { it.isNotBlank() && it != accountKey }
        if (accountChanged) {
            observedAccountKey = accountKey
            cachedRemoteItems = emptyList()
            cachedRemoteAccountKey = null
            reconciliationFilter = null
            LibraryDeletionTracker.clear()
            Settings.shared.lastLibraryRefreshEpoch = 0L
            _uiState.update { it.copy(items = emptyList(), isLoading = true) }
        }
        if (refreshInProgress) {
            if (accountChanged) _uiState.update { it.copy(items = emptyList(), isLoading = true, isRefreshing = false) }
            if (force || accountChanged) pendingRefresh = true
            return
        }
        if (!force && !accountChanged &&
            !shouldRefreshLibrary(Clock.System.now().toEpochMilliseconds(), Settings.shared.lastLibraryRefreshEpoch)
        ) {
            return
        }
        refreshInProgress = true
        val generation = ++refreshGeneration
        countResolutionJob?.cancel()
        val deletionVersion = playlistDeletionVersion
        val scanStartedAt = Clock.System.now().toEpochMilliseconds()
        val bookmarkSnapshot = LibraryBookmarkMutations.capture()
        scope.launch {
            _uiState.update { it.copy(isLoading = it.items.isEmpty(), isRefreshing = it.items.isNotEmpty(), error = false) }
            try {
                val canReconcile = repository.canManagePlaylists()
                val musicResult = repository.getCompleteLibraryItemsResult(force = force)
                val (podcastsResult, episodesResult) =
                    if (canReconcile) {
                        loadPodcastLibrary(repository, canReconcile = true)
                    } else {
                        Result.success(emptyList<PodcastItem>()) to Result.success(emptyList<EpisodeItem>())
                    }
                if (musicResult.items.isEmpty() && musicResult.uploadedSongs == null && podcastsResult.isFailure &&
                    episodesResult.isFailure
                ) {
                    throw podcastsResult.exceptionOrNull() ?: episodesResult.exceptionOrNull()
                        ?: IllegalStateException("Library refresh failed")
                }
                val podcasts = podcastsResult.getOrDefault(emptyList())
                val episodes = episodesResult.getOrDefault(emptyList())
                if (generation != refreshGeneration || currentLibraryAccountKey() != accountKey) {
                    pendingRefresh = true
                    return@launch
                }
                val musicItems =
                    database?.preserveKnownPlaylistEditability(
                        musicResult.items,
                        musicResult.unresolvedPlaylistEditabilityIds,
                    ) ?: musicResult.items
                val remote =
                    (musicItems + podcasts + episodes)
                        .let(LibraryDeletionTracker::filterDeleted)
                        .distinctBy(::libraryItemKey)
                if (generation != refreshGeneration || currentLibraryAccountKey() != accountKey) {
                    pendingRefresh = true
                    return@launch
                }
                val completeRefresh = musicResult.complete && podcastsResult.isSuccess && episodesResult.isSuccess
                if (canReconcile && completeRefresh) {
                    val allRemoteKeys = (musicItems + podcasts + episodes).mapTo(mutableSetOf(), ::libraryItemKey)
                    LibraryDeletionTracker.reconcile(allRemoteKeys)
                }
                database?.let {
                    synchronizeRemote(
                        it,
                        remote,
                        podcasts,
                        episodes,
                        reconcile = canReconcile && completeRefresh,
                        uploadedSongs = musicResult.uploadedSongs.takeIf { canReconcile },
                        bookmarkSnapshot = bookmarkSnapshot,
                    )
                }
                if (generation != refreshGeneration || currentLibraryAccountKey() != accountKey) {
                    pendingRefresh = true
                    return@launch
                }
                val hydratedRemote = database?.let { hydrateSongLibraryTimestamps(it, remote) } ?: remote
                if (deletionVersion != playlistDeletionVersion || generation != refreshGeneration ||
                    currentLibraryAccountKey() != accountKey
                ) {
                    pendingRefresh = true
                    return@launch
                }
                cachedRemoteItems =
                    (if (completeRefresh) hydratedRemote else mergeLibraryItems(cachedRemoteItems, hydratedRemote))
                        .let(LibraryDeletionTracker::filterDeleted)
                cachedRemoteAccountKey = accountKey
                cachedBookmarkSnapshot = bookmarkSnapshot
                if (canReconcile && completeRefresh) {
                    val trackedKeys =
                        runCatching {
                            Json.decodeFromString<Set<String>>(Settings.shared.lastRemoteLibraryItemKeysJson)
                        }.getOrDefault(emptySet())
                    reconciliationFilter =
                        LibraryReconciliationFilter(
                            accountKey,
                            trackedKeys,
                            remote.mapTo(mutableSetOf(), ::libraryItemKey),
                            scanStartedAt,
                            bookmarkSnapshot,
                        )
                }
                YtLogger.w(
                    "LibrarySync",
                    "Refresh complete=$completeRefresh music=${musicResult.items.size} " +
                        "musicComplete=${musicResult.complete} podcastsOk=${podcastsResult.isSuccess} episodesOk=${episodesResult.isSuccess}",
                )
                _uiState.update { it.copy(isLoading = false, isRefreshing = false, error = !completeRefresh) }
                if (completeRefresh) {
                    Settings.shared.lastLibraryRefreshEpoch = Clock.System.now().toEpochMilliseconds()
                    Settings.shared.lastLibraryRefreshAccount = accountKey
                    YouTubeSyncState.succeeded("library")
                } else {
                    YouTubeSyncState.failed("library") { if (currentLibraryAccountKey() == accountKey) refresh(force = true) }
                }
                localReloads.trySend(Unit)
                countResolutionJob =
                    scope.launch {
                        try {
                            repository.resolveMissingPlaylistCounts(musicItems) { resolved ->
                                if (generation != refreshGeneration ||
                                    currentLibraryAccountKey() != accountKey
                                ) {
                                    return@resolveMissingPlaylistCounts
                                }

                                database?.let { db ->
                                    val count = parseDisplayCount(resolved.songCount) ?: return@let
                                    db.withDatabaseTransaction {
                                        val existing = db.dao().findPlaylistEntity(resolved.id) ?: return@withDatabaseTransaction
                                        if (generation == refreshGeneration && currentLibraryAccountKey() == accountKey &&
                                            !LibraryDeletionTracker.isDeleted(resolved)
                                        ) {
                                            db.dao().update(existing.copy(remoteSongCount = count))
                                        }
                                    }
                                }
                                if (generation != refreshGeneration || currentLibraryAccountKey() != accountKey) {
                                    return@resolveMissingPlaylistCounts
                                }

                                fun YTItem.withResolvedCount(): YTItem =
                                    if (this is PlaylistItem && libraryItemKey(this) == libraryItemKey(resolved)) {
                                        copy(
                                            songCount = resolved.songCount?.takeIf { parseDisplayCount(it) != null } ?: songCount,
                                            author = author ?: resolved.author,
                                        )
                                    } else {
                                        this
                                    }
                                cachedRemoteItems = cachedRemoteItems.map { it.withResolvedCount() }
                                _uiState.update { current -> current.copy(items = current.items.map { it.withResolvedCount() }) }
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                        }
                        database?.let { db ->
                            val hasDownloads =
                                downloadedSongIds?.first()?.isNotEmpty()
                                    ?: db
                                        .dao()
                                        .cachedAudioIdsByKind("DOWNLOAD")
                                        .first()
                                        .isNotEmpty()
                            if (hasDownloads) {
                                db.repairRemotePlaylistMembership(repository, musicItems.filterIsInstance<PlaylistItem>()) {
                                    generation == refreshGeneration && currentLibraryAccountKey() == accountKey
                                }
                            }
                        }
                    }
            } catch (e: CancellationException) {
                if (generation == refreshGeneration) _uiState.update { it.copy(isLoading = false, isRefreshing = false) }
                throw e
            } catch (e: Exception) {
                if (generation == refreshGeneration && currentLibraryAccountKey() == accountKey) {
                    YtLogger.e("LibrarySync", "Refresh failed type=${e::class.simpleName}")
                    _uiState.update { it.copy(isLoading = false, isRefreshing = false, error = true) }
                    YouTubeSyncState.failed("library") { if (currentLibraryAccountKey() == accountKey) refresh(force = true) }
                }
            } finally {
                refreshInProgress = false
                if (pendingRefresh || currentLibraryAccountKey() != accountKey) {
                    pendingRefresh = false
                    refresh(force = true)
                }
            }
        }
    }

    fun refreshAfterHomeLoaded() {
        if (!hasRefreshedAfterHomeLoaded) {
            hasRefreshedAfterHomeLoaded = true
            refresh(force = false)
        }
    }

    internal fun publishRefreshedItems(
        items: List<YTItem>,
        deletionVersion: Long,
    ): Boolean {
        if (deletionVersion != playlistDeletionVersion) return false
        _uiState.update { it.copy(items = LibraryDeletionTracker.filterDeleted(items), isLoading = false) }
        return true
    }

    private suspend fun loadLocal() {
        val deletionVersion = playlistDeletionVersion
        val accountKey = currentLibraryAccountKey()
        val localReadSnapshot = LibraryBookmarkMutations.capture()
        val loadedLocal = database?.let { loadLocalItemsWithHistory(it) }.orEmpty().let(LibraryDeletionTracker::filterDeleted)
        if (deletionVersion != playlistDeletionVersion || currentLibraryAccountKey() != accountKey) return
        val accountRemoteItems =
            LibraryBookmarkMutations.filterRemoved(
                cachedRemoteItems.takeIf { cachedRemoteAccountKey == accountKey }.orEmpty(),
                cachedBookmarkSnapshot,
            )
        val currentLocal = LibraryBookmarkMutations.filterRemoved(loadedLocal, localReadSnapshot)
        val local = reconciliationFilter?.filter(currentLocal, accountKey) ?: currentLocal
        val localKeys = local.mapTo(mutableSetOf(), ::libraryItemKey)
        val remote =
            accountRemoteItems.filterNot {
                database != null && it is SongItem && it.isUploaded && libraryItemKey(
                    it,
                ) !in localKeys
            }
        val items =
            if (accountRemoteItems.isNotEmpty()) {
                mergeLibraryItems(local, remote.let(LibraryDeletionTracker::filterDeleted), downloadedKeys)
            } else {
                local
            }
        publishRefreshedItems(items, deletionVersion)
    }

    private suspend fun loadLocalItemsWithHistory(database: MusicDatabase): List<YTItem> =
        withContext(Dispatchers.Default) {
            database.refreshHistoryPlaylists()
            loadLocalItems(database)
        }

    internal suspend fun loadLocalItems(database: MusicDatabase): List<YTItem> {
        val dao = database.dao()
        val autoPlaylistIds =
            buildSet {
                add(PlaylistEntity.LIKED_PLAYLIST_ID)
                add(PlaylistEntity.DOWNLOADED_PLAYLIST_ID)
                if (!Settings.shared.historyPlaylistsEnabled) {
                    add(PlaylistEntity.WEEKLY_MOST_PLAYLIST_ID)
                    add(PlaylistEntity.MONTHLY_MOST_PLAYLIST_ID)
                }
            }
        val playlistEntities =
            dao
                .allPlaylists()
                .first()
                .filter { entity ->
                    entity.id !in autoPlaylistIds && (entity.bookmarkedAt != null || entity.isLocal)
                }
        downloadedKeys =
            (
                downloadedSongIds?.first()?.let { dao.downloadedCollectionKeys(it.toList()) }
                    ?: dao.downloadedCollectionKeys()
            ).first().toSet()
        val playlistStats =
            playlistEntities
                .takeIf { it.isNotEmpty() }
                ?.let { dao.playlistLibraryStats(it.map(PlaylistEntity::id)) }
                .orEmpty()
                .associateBy { it.playlistId }
        val previews =
            playlistEntities
                .filter { it.isLocal || it.browseId == null }
                .map { it.id }
                .let { dao.playlistPreviewThumbnails(it) }
                .groupBy({ it.playlistId }, { it.thumbnailUrl })
        val playlists =
            playlistEntities.map { entity ->
                val stats = playlistStats[entity.id]
                PlaylistItem(
                    id = entity.browseId ?: entity.id,
                    isDownloaded = "playlist:${entity.browseId ?: entity.id}" in downloadedKeys,
                    title = entity.name,
                    thumbnail = entity.customThumbnailUrl ?: entity.thumbnailUrl,
                    author = if (entity.isLocal || entity.browseId == null) "You" else null,
                    songCount =
                        if (entity.isLocal || entity.browseId == null) {
                            stats?.songCount?.toString()
                        } else {
                            (
                                entity.remoteSongCount?.takeIf { it > 0 }
                                    ?: stats?.songCount?.takeIf { it > 0 }
                                    ?: entity.remoteSongCount
                            )?.toString()
                        },
                    playtime = stats?.playtime,
                    libraryAddedAt = (entity.bookmarkedAt ?: entity.createdAt).toEpochMilliseconds(),
                    libraryUpdatedAt = entity.lastUpdateTime.toEpochMilliseconds(),
                    playEndpointParams = entity.playEndpointParams,
                    radioEndpointParams = entity.radioEndpointParams,
                    isEditable = entity.isEditable,
                    description = entity.description,
                    isLocal = entity.isLocal || entity.browseId == null,
                    artworkThumbnails =
                        previews[entity.id].orEmpty(),
                    customThumbnail = entity.customThumbnailUrl,
                )
            }
        val songs =
            dao.songsByCreateDateAsc().first().filterNot { it.song.isEpisode }.map { song ->
                song.toSongItem().copy(
                    libraryAddedAt = song.song.inLibrary.toEpochMilliseconds(),
                    libraryUpdatedAt = song.song.inLibrary.toEpochMilliseconds(),
                )
            }
        val episodes =
            dao.savedPodcastEpisodesByCreateDateAsc().first().asReversed().map { song ->
                EpisodeItem(
                    id = song.song.id,
                    title = song.song.title,
                    thumbnail = song.song.thumbnailUrl,
                    author = song.orderedArtists.firstOrNull()?.let { ArtistTiny(it.id, it.name, it.thumbnailUrl) },
                    duration =
                        song.song.duration
                            .takeIf { it > 0 }
                            ?.toLong(),
                    podcastId = song.song.albumId,
                    saved = true,
                    libraryAddedAt = song.song.inLibrary.toEpochMilliseconds(),
                    libraryUpdatedAt = song.song.inLibrary.toEpochMilliseconds(),
                )
            }
        val artists =
            dao.artistsBookmarkedByCreateDateAsc().first().map {
                ArtistItem(
                    id = it.artist.id,
                    title = it.artist.name,
                    channelId = it.artist.channelId,
                    thumbnail = it.artist.thumbnailUrl,
                    isSubscribed = true,
                    songCount = it.songCount,
                    playtime = it.timeListened,
                    libraryAddedAt = it.artist.bookmarkedAt.toEpochMilliseconds(),
                    libraryUpdatedAt = it.artist.lastUpdateTime.toEpochMilliseconds(),
                )
            }
        val albums =
            dao.albumsLikedByCreateDateAsc().first().map {
                AlbumItem(
                    id = it.album.id,
                    isDownloaded = "album:${it.album.id}" in downloadedKeys,
                    title = it.album.title,
                    thumbnail = it.album.thumbnailUrl,
                    artists =
                        it.artists.map { artist ->
                            ArtistTiny(artist.id.takeUnless { artist.isLocal }, artist.name, artist.thumbnailUrl)
                        },
                    year = it.album.year?.toString(),
                    playlistId = it.album.playlistId,
                    isExplicit = it.album.explicit,
                    songCount = it.album.songCount.takeIf { count -> count > 0 },
                    duration =
                        it.album.duration
                            .takeIf { duration -> duration > 0 }
                            ?.toLong(),
                    playtime = it.timeListened,
                    libraryAddedAt = it.album.bookmarkedAt.toEpochMilliseconds(),
                    libraryUpdatedAt = it.album.lastUpdateTime.toEpochMilliseconds(),
                )
            }
        val podcasts =
            dao.subscribedPodcasts().first().map {
                PodcastItem(
                    it.id,
                    it.title,
                    it.thumbnailUrl,
                    author = it.author,
                    channelId = it.channelId,
                    libraryAddedAt = it.bookmarkedAt.toEpochMilliseconds(),
                    libraryUpdatedAt = it.lastUpdateTime.toEpochMilliseconds(),
                )
            }
        return (playlists + songs + episodes + artists + albums + podcasts).distinctBy(::libraryItemKey)
    }

    internal suspend fun synchronizeRemote(
        database: MusicDatabase,
        items: List<YTItem>,
        podcasts: List<PodcastItem>,
        episodes: List<EpisodeItem>,
        reconcile: Boolean,
        uploadedSongs: List<SongItem>? = null,
        bookmarkSnapshot: LibraryBookmarkSnapshot = LibraryBookmarkMutations.capture(),
    ): Unit =
        LibraryBookmarkMutations.withLock {
            val newerChoices = LibraryBookmarkMutations.newerChoices(bookmarkSnapshot)
            val dao = database.dao()
            val now = Clock.System.now().toLocalDateTime(TimeZone.UTC)
            PodcastLibrary.synchronize(database, podcasts, episodes)
            database.syncRemotePlaylists(items.filterIsInstance<PlaylistItem>(), newerChoices.keys)
            database.syncRemoteLibraryItems(items, now, newerChoices.keys)
            uploadedSongs?.let { database.synchronizeUploadedSongs(it) }
            if (reconcile) {
                val librarySongs = dao.songsByCreateDateAsc().first()
                val uploadedKeys = librarySongs.filter { it.song.isUploaded && !it.song.isLocal }.map { "song:${it.song.id}" }
                val previousRemoteKeys =
                    runCatching {
                        Json.decodeFromString<Set<String>>(Settings.shared.lastRemoteLibraryItemKeysJson)
                    }.getOrDefault(emptySet()) + uploadedKeys
                val previousPendingRemovals =
                    runCatching {
                        Json.decodeFromString<Set<String>>(Settings.shared.pendingRemoteLibraryRemovalKeysJson)
                    }.getOrDefault(emptySet())
                val currentRemoteKeys =
                    items.mapTo(mutableSetOf(), ::libraryItemKey).apply {
                        removeAll(newerChoices.filterValues { !it }.keys)
                        addAll(newerChoices.filterValues { it }.keys)
                    }
                val absentRemoteKeys = previousRemoteKeys - currentRemoteKeys
                val confirmedRemovals = absentRemoteKeys intersect previousPendingRemovals
                val remotePlaylistIds = items.filterIsInstance<PlaylistItem>().mapTo(mutableSetOf()) { it.id.removePrefix("VL") }
                dao.allPlaylists().first().forEach { playlist ->
                    val remoteId = playlist.browseId?.removePrefix("VL")
                    if (!playlist.isLocal && playlist.bookmarkedAt != null && remoteId != null &&
                        remoteId !in remotePlaylistIds && "playlist:$remoteId" !in newerChoices
                    ) {
                        dao.update(playlist.copy(bookmarkedAt = null))
                    }
                }

                val remoteSongIds = items.filterIsInstance<SongItem>().mapTo(mutableSetOf()) { it.id }
                remoteSongIds += episodes.map { it.id }
                librarySongs.forEach { song ->
                    val key = if (song.song.isEpisode) "episode:${song.song.id}" else "song:${song.song.id}"
                    if (!song.song.isLocal && key in confirmedRemovals && song.song.id !in remoteSongIds) {
                        dao.inLibrary(song.song.id, null)
                    }
                }

                val remoteArtistIds = items.filterIsInstance<ArtistItem>().mapTo(mutableSetOf()) { it.id }
                dao.artistsBookmarkedByCreateDateAsc().first().forEach { artist ->
                    if (!artist.artist.isLocal && "artist:${artist.artist.id}" in confirmedRemovals &&
                        artist.artist.id !in remoteArtistIds
                    ) {
                        dao.upsert(artist.artist.copy(bookmarkedAt = null))
                    }
                }

                val remoteAlbumIds = items.filterIsInstance<AlbumItem>().mapTo(mutableSetOf()) { it.id }
                dao.albumsLikedByCreateDateAsc().first().forEach { album ->
                    if (!album.album.isLocal && "album:${album.album.id}" in confirmedRemovals &&
                        album.album.id !in remoteAlbumIds &&
                        "album:${album.album.id}" !in newerChoices
                    ) {
                        dao.upsert(album.album.copy(bookmarkedAt = null))
                    }
                }

                val remotePodcastIds = podcasts.mapTo(mutableSetOf()) { it.id }
                dao.subscribedPodcasts().first().forEach { podcast ->
                    if ("podcast:${podcast.id}" in confirmedRemovals && podcast.id !in remotePodcastIds) {
                        dao.upsert(podcast.copy(bookmarkedAt = null))
                    }
                }
                Settings.shared.lastRemoteLibraryItemKeysJson =
                    Json.encodeToString((previousRemoteKeys - confirmedRemovals) + currentRemoteKeys)
                Settings.shared.pendingRemoteLibraryRemovalKeysJson = Json.encodeToString(absentRemoteKeys - confirmedRemovals)
            }
        }

    data class LibraryUiState(
        val items: List<YTItem> = emptyList(),
        val isLoading: Boolean = false,
        val isRefreshing: Boolean = false,
        val error: Boolean = false,
    )
}

internal suspend fun loadPodcastLibrary(
    repository: YouTubeRepository,
    canReconcile: Boolean,
): Pair<Result<List<PodcastItem>>, Result<List<EpisodeItem>>> =
    coroutineScope {
        val podcasts =
            async {
                try {
                    Result.success(if (canReconcile) repository.getLibraryPodcastsForSync() else repository.getLibraryPodcasts())
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Result.failure(error)
                }
            }
        val episodes =
            async {
                try {
                    Result.success(if (canReconcile) repository.getSavedEpisodesForSync() else repository.getSavedEpisodes())
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Result.failure(error)
                }
            }
        podcasts.await() to episodes.await()
    }

internal fun libraryAccountKey(
    dataSyncId: String,
    accountEmail: String,
    authUser: String,
): String = "${dataSyncId.ifBlank { accountEmail.ifBlank { "signed-out" } }}|$authUser"

internal fun currentLibraryAccountKey(): String =
    libraryAccountKey(
        Settings.shared.dataSyncId,
        Settings.shared.accountEmail,
        Settings.shared.innerTubeAuthUser,
    )

internal class LibraryReconciliationFilter(
    private val accountKey: String,
    private val trackedRemoteKeys: Set<String>,
    private val currentRemoteKeys: Set<String>,
    private val scanStartedAt: Long,
    private val bookmarkSnapshot: LibraryBookmarkSnapshot = LibraryBookmarkMutations.capture(),
) {
    fun filter(
        local: List<YTItem>,
        currentAccountKey: String,
    ): List<YTItem> {
        if (accountKey != currentAccountKey) return local
        val newerChoices = LibraryBookmarkMutations.newerChoices(bookmarkSnapshot)
        return local.filter { item ->
            val key = libraryItemKey(item)
            newerChoices[key]
                ?: (key !in trackedRemoteKeys || key in currentRemoteKeys || (item.libraryAddedAt ?: Long.MIN_VALUE) > scanStartedAt)
        }
    }
}

private fun libraryItemKey(item: YTItem): String = item.librarySearchKey()

private fun LocalDateTime?.toEpochMilliseconds(): Long? = this?.toInstant(TimeZone.UTC)?.toEpochMilliseconds()

internal suspend fun hydrateSongLibraryTimestamps(
    database: MusicDatabase,
    items: List<YTItem>,
): List<YTItem> {
    val ids = items.filter { it is SongItem || it is EpisodeItem }.map(YTItem::id)
    if (ids.isEmpty()) return items
    val timestamps =
        database
            .dao()
            .getSongsByIds(ids)
            .mapNotNull { song ->
                song.song.inLibrary
                    .toEpochMilliseconds()
                    ?.let { song.id to it }
            }.toMap()
    return items.map { item ->
        val timestamp = timestamps[item.id] ?: return@map item
        when (item) {
            is SongItem -> {
                item.copy(
                    libraryAddedAt = item.libraryAddedAt ?: timestamp,
                    libraryUpdatedAt = item.libraryUpdatedAt ?: timestamp,
                )
            }

            is EpisodeItem -> {
                item.copy(
                    libraryAddedAt = item.libraryAddedAt ?: timestamp,
                    libraryUpdatedAt = item.libraryUpdatedAt ?: timestamp,
                )
            }

            else -> {
                item
            }
        }
    }
}

internal suspend fun contentMatchedSongs(
    database: MusicDatabase,
    query: String,
    offset: Int = 0,
    descending: Boolean = false,
): List<SongItem> =
    withContext(Dispatchers.Default) {
        val term = query.trim()
        if (term.length < 2) return@withContext emptyList()
        database.dao().contentMatchedSongs(term, LIBRARY_SEARCH_PAGE_SIZE, offset, descending).map { it.toSongItem() }
    }
