package com.metrolist.android.viewmodels

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.room3.immediateTransaction
import androidx.room3.useWriterConnection
import com.metrolist.shared.api.PlaylistNotFoundException
import com.metrolist.shared.api.PlaylistPage
import com.metrolist.shared.api.PlaylistSongAlreadyPresentException
import com.metrolist.shared.api.YouTubeRepository
import com.metrolist.shared.api.playlistSongs
import com.metrolist.shared.api.playlistSuggestions
import com.metrolist.shared.api.shouldLoadPlaylistPage
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.db.cacheRemotePlaylist
import com.metrolist.shared.db.emptyHistoryPlaylist
import com.metrolist.shared.db.entities.KIND_DOWNLOAD
import com.metrolist.shared.db.entities.Playlist
import com.metrolist.shared.db.entities.PlaylistEntity
import com.metrolist.shared.db.entities.PlaylistSongMap
import com.metrolist.shared.db.entities.toSongItem
import com.metrolist.shared.db.refreshHistoryPlaylist
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.added_song
import com.metrolist.shared.generated.resources.could_not_finish_adding_song
import com.metrolist.shared.generated.resources.could_not_finish_removing_songs
import com.metrolist.shared.generated.resources.error_playlist_name_empty
import com.metrolist.shared.generated.resources.error_unknown
import com.metrolist.shared.generated.resources.liked_songs
import com.metrolist.shared.generated.resources.playlist_metadata_unavailable
import com.metrolist.shared.generated.resources.playlist_removed_from_youtube
import com.metrolist.shared.generated.resources.playlist_unavailable
import com.metrolist.shared.generated.resources.removed_songs_from_playlist
import com.metrolist.shared.generated.resources.skipped_duplicate
import com.metrolist.shared.generated.resources.you
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.model.PlaylistPresentation
import com.metrolist.shared.model.PlaylistPresentationOptions
import com.metrolist.shared.model.PlaylistSongSort
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.moveSelection
import com.metrolist.shared.model.preparePlaylistPresentation
import com.metrolist.shared.model.selectionReorderMoves
import com.metrolist.shared.model.withNavigationThumbnail
import com.metrolist.shared.playlist.PlaylistChanges
import com.metrolist.shared.playlist.PlaylistPartiallyUpdatedException
import com.metrolist.shared.playlist.deleteStoredPlaylist
import com.metrolist.shared.playlist.removeSongsFromStoredPlaylist
import com.metrolist.shared.playlist.removeStoredPlaylistCover
import com.metrolist.shared.playlist.renameStoredPlaylist
import com.metrolist.shared.playlist.setStoredPlaylistBookmarked
import com.metrolist.shared.playlist.setStoredPlaylistCover
import com.metrolist.shared.playlist.updateStoredPlaylist
import com.metrolist.shared.playlist.uploadStoredPlaylistCover
import com.metrolist.shared.settings.BlockedContent
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.state.GlobalYouTubeRepository
import com.metrolist.shared.state.SongLikeState
import com.metrolist.shared.state.SongMetadataState
import com.metrolist.shared.utils.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString
import java.util.IdentityHashMap

class PlaylistViewModel(
    playlistId: String? = null,
    private val database: MusicDatabase,
    private val applicationScope: CoroutineScope,
    private val findLocalPlaylist: suspend (String) -> Playlist? = { id ->
        database.dao().findPlaylistEntity(id)?.let { database.dao().playlist(it.id).first() }
    },
    private val moveRemoteSong: suspend (String, String, String?) -> Result<Unit> = { id, song, successor ->
        GlobalYouTubeRepository.instance.movePlaylistSong(id, song, successor)
    },
    private val repositoryProvider: () -> YouTubeRepository = { GlobalYouTubeRepository.instance },
    private var thumbnailHint: String? = null,
) : ViewModel() {
    private val _playlistId = mutableStateOf(playlistId)
    val playlistId: State<String?> = _playlistId

    private val _playlistInfo = mutableStateOf<PlaylistItem?>(null)
    val playlistInfo: State<PlaylistItem?> = _playlistInfo

    private val _songs = mutableStateOf<List<SongItem>>(emptyList())
    val songs: State<List<SongItem>> = _songs
    private val _durationSongs = mutableStateOf<List<SongItem>>(emptyList())
    val durationSongs: State<List<SongItem>> = _durationSongs

    private val _page = mutableStateOf<PlaylistPage?>(null)
    val page: State<PlaylistPage?> = _page
    private val _isLoadingMore = mutableStateOf(false)
    val isLoadingMore: State<Boolean> = _isLoadingMore
    private var pageLoadJob: Job? = null
    private var searchLoadJob: Job? = null

    private val _suggestions = mutableStateOf<List<SongItem>>(emptyList())
    val suggestions: State<List<SongItem>> = _suggestions

    private val _playlistTrackCount = mutableIntStateOf(0)
    val playlistTrackCount: State<Int> = _playlistTrackCount

    private val _isFavorite = mutableStateOf(false)
    val isFavorite: State<Boolean> = _isFavorite

    private val _isLoading = mutableStateOf(false)
    val isLoading: State<Boolean> = _isLoading

    private val _errorMessage = mutableStateOf<String?>(null)
    val errorMessage: State<String?> = _errorMessage

    private val _operationState = mutableStateOf(PlaylistOperationState())
    val operationState: State<PlaylistOperationState> = _operationState

    private val _downloadedSongIds = mutableStateOf<Set<String>>(emptySet())
    val downloadedSongIds: State<Set<String>> = _downloadedSongIds

    private var pendingReorder: (suspend () -> Unit)? = null
    private var remoteMovePreparationGeneration = 0L
    private val mutationMutex = Mutex()
    private val reorderMutex = mutationMutex
    private var playlistLoadJob: Job? = null

    val presentationOptions = mutableStateOf(PlaylistPresentationOptions())
    private val _presentation = mutableStateOf(PlaylistPresentation())
    val presentation: State<PlaylistPresentation> = _presentation
    var sourceIndices: Map<SongItem, Int> = emptyMap()
        private set

    private val localSongMapIds = IdentityHashMap<SongItem, Int>()
    private var reorderOriginalSongs: List<SongItem>? = null
    private var reorderMovingIndices = emptySet<Int>()
    private var preparedSongs: List<SongItem>? = null
    private var preparedMetadataRevision = -1L
    private var preparedOptions: PlaylistPresentationOptions? = null

    private suspend fun publishLoadedSongs(
        songs: List<SongItem>,
        suggestions: List<SongItem> = emptyList(),
        hasMore: Boolean = false,
        metadata: () -> Unit,
    ) {
        val options = presentationOptions.value
        val metadataRevision = SongMetadataState.revision.value
        val loaded =
            withContext(Dispatchers.Default) {
                prepareLoadedPlaylist(BlockedContent.filterSongs(songs), BlockedContent.filterSongs(suggestions), options, hasMore)
            }
        Snapshot.withMutableSnapshot {
            metadata()
            pendingReorder = null
            reorderOriginalSongs = null
            reorderMovingIndices = emptySet()
            _songs.value = loaded.songs
            _durationSongs.value = songs
            _suggestions.value = loaded.suggestions
            if (presentationOptions.value == options) {
                sourceIndices = loaded.sourceIndices
                _presentation.value = loaded.presentation
                preparedSongs = loaded.songs
                preparedOptions = options
                preparedMetadataRevision = metadataRevision
            }
            _isLoading.value = false
        }
    }

    init {
        viewModelScope.launch {
            PlaylistChanges.flow.collect { changedId ->
                val id = _playlistId.value ?: return@collect
                val remoteId = database.dao().findPlaylistEntity(id)?.browseId ?: id
                if (_playlistId.value == id && remoteId.removePrefix("VL") == changedId) loadPlaylist(id)
            }
        }
        viewModelScope.launch {
            combine(
                snapshotFlow { Triple(_songs.value, presentationOptions.value, _page.value?.hasMore == true) },
                SongMetadataState.revision,
            ) { (songs, options, hasMore), revision ->
                PlaylistPresentationInput(songs, options, hasMore, revision)
            }.collectLatest { (songs, options, hasMore, revision) ->
                if (hasMore && options.requiresCompletePlaylist()) {
                    _isLoadingMore.value = true
                    try {
                        playlistLoadJob?.join()
                        mutationMutex.withLock { completePage() }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        _errorMessage.value = getString(Res.string.error_unknown)
                    } finally {
                        _isLoadingMore.value = false
                    }
                    return@collectLatest
                }
                if (songs === preparedSongs && options == preparedOptions && revision == preparedMetadataRevision) return@collectLatest
                val (prepared, indices) =
                    withContext(Dispatchers.Default) {
                        preparePlaylistPresentation(songs, options) to
                            IdentityHashMap<SongItem, Int>().apply { songs.forEachIndexed { index, song -> put(song, index) } }
                    }
                if (_songs.value !== songs || presentationOptions.value != options) return@collectLatest
                sourceIndices = indices
                _presentation.value = prepared
                preparedSongs = songs
                preparedOptions = options
                preparedMetadataRevision = revision
            }
        }
        loadPlaylist()
        loadDownloadedSongIds()
    }

    fun loadPlaylist(playlistId: String? = _playlistId.value) {
        if (_playlistId.value != playlistId) {
            thumbnailHint = null
            _playlistInfo.value = null
            _songs.value = emptyList()
            _durationSongs.value = emptyList()
            _suggestions.value = emptyList()
            _playlistTrackCount.intValue = 0
            _isFavorite.value = false
        }
        _playlistId.value = playlistId
        cancelSearchLoad()
        pageLoadJob?.cancel()
        _page.value = null
        playlistLoadJob?.cancel()
        playlistLoadJob =
            viewModelScope.launch {
                mutationMutex.withLock {
                    _isLoading.value = _playlistInfo.value == null && _songs.value.isEmpty()
                    _errorMessage.value = null
                    try {
                        val id = playlistId ?: return@launch

                        if (id == "liked") {
                            val liked = SongLikeState.likedSongs.toList()
                            val likedTitle = getString(Res.string.liked_songs)
                            val author = getString(Res.string.you)
                            publishLoadedSongs(liked) {
                                _playlistInfo.value =
                                    PlaylistItem(
                                        id = "liked",
                                        title = likedTitle,
                                        author = author,
                                        thumbnail = Settings.shared.collectionCovers["liked"] ?: liked.firstOrNull()?.thumbnail,
                                        customThumbnail = Settings.shared.collectionCovers["liked"],
                                    )
                                _playlistTrackCount.intValue = liked.size
                            }
                            return@launch
                        }

                        database.refreshHistoryPlaylist(id)
                        val localEntity = database.dao().findPlaylistEntity(id)
                        if (localEntity == null) {
                            emptyHistoryPlaylist(id)?.let { header ->
                                publishLoadedSongs(emptyList()) {
                                    _playlistInfo.value = header
                                    _playlistTrackCount.intValue = 0
                                }
                                return@launch
                            }
                        }
                        if (localEntity != null && localEntity.browseId == null) {
                            loadLocalPlaylist(localEntity.id)
                            _suggestions.value = emptyList()
                            return@launch
                        }

                        val hadCachedPlaylist = loadLocalPlaylist(id, preferBrowseId = true)
                        if (!hadCachedPlaylist || _songs.value.isEmpty()) {
                            _isLoading.value = true
                        }

                        val page =
                            withContext(Dispatchers.IO) {
                                repositoryProvider().getPlaylistPageData(localEntity?.browseId ?: id, applyContentFilters = false)
                            }
                        val data = page.sections
                        if (data.isEmpty()) {
                            if (!hadCachedPlaylist) _errorMessage.value = getString(Res.string.playlist_unavailable)
                            return@launch
                        }

                        val customCover = database.dao().findPlaylistEntity(id)?.customThumbnailUrl
                        val playlistInfo =
                            data["header"]
                                ?.filterIsInstance<PlaylistItem>()
                                ?.firstOrNull()
                                ?.withNavigationThumbnail(thumbnailHint)
                                ?.copy(customThumbnail = customCover)
                        val songs = data.playlistSongs()
                        if (!page.hasMore && !page.isOffline) cachePlaylist(id, playlistInfo, songs)
                        publishLoadedSongs(songs, data.playlistSuggestions(), page.hasMore) {
                            _playlistInfo.value = playlistInfo
                            _page.value = page
                            _playlistTrackCount.intValue = songs.size
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        if (showMissingPlaylist(t, playlistId)) return@withLock
                        if (playlistId == null || !loadLocalPlaylist(playlistId, preferBrowseId = true)) {
                            Logger.e("PlaylistViewModel", "Error loading playlist", t)
                            _errorMessage.value = t.message ?: getString(Res.string.error_unknown)
                        }
                    } finally {
                        _isLoading.value = false
                    }
                }
            }
    }

    fun loadForSearch() {
        if (searchLoadJob?.isActive == true || _page.value?.hasMore != true) return
        searchLoadJob =
            viewModelScope.launch {
                var searchPageJob: Job? = null
                try {
                    playlistLoadJob?.join()
                    pageLoadJob?.join()
                    while (_page.value?.hasMore == true && _errorMessage.value == null) {
                        searchPageJob = loadMore(Int.MAX_VALUE, retry = true) ?: break
                        if (!searchPageJob.joinSuccessfully()) {
                            _errorMessage.value = getString(Res.string.error_unknown)
                            break
                        }
                    }
                } finally {
                    searchPageJob?.cancel()
                }
            }
    }

    fun cancelSearchLoad() {
        searchLoadJob?.cancel()
    }

    fun loadMore(
        lastVisibleSongIndex: Int,
        retry: Boolean = false,
    ): Job? {
        val page = _page.value ?: return null
        if (!page.hasMore || pageLoadJob?.isActive == true || playlistLoadJob?.isActive == true) return null
        if (!retry && !shouldLoadPlaylistPage(lastVisibleSongIndex, _songs.value.size) && _songs.value.isNotEmpty()) return null
        pageLoadJob =
            viewModelScope.launch {
                _isLoadingMore.value = true
                _errorMessage.value = null
                try {
                    mutationMutex.withLock {
                        if (_page.value !== page) return@withLock
                        val next =
                            withContext(Dispatchers.IO) {
                                repositoryProvider().continuePlaylistPage(page, applyContentFilters = false)
                            }
                        if (_page.value !== page) return@withLock
                        publishLoadedSongs(next.songs, next.sections.playlistSuggestions(), next.hasMore) {
                            _page.value = next
                            _playlistTrackCount.intValue = next.songs.size
                        }
                        if (!next.hasMore) cachePlaylist(_playlistId.value ?: return@withLock, _playlistInfo.value, next.songs)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _errorMessage.value = getString(Res.string.error_unknown)
                } finally {
                    _isLoadingMore.value = false
                }
            }
        return pageLoadJob
    }

    private suspend fun completePage(): List<SongItem> {
        val initial = _page.value?.takeIf { it.hasMore } ?: return _songs.value
        val page =
            withContext(Dispatchers.IO) {
                repositoryProvider().completePlaylistPage(initial, applyContentFilters = false)
            }
        cachePlaylist(_playlistId.value ?: return _songs.value, _playlistInfo.value, page.songs)
        publishLoadedSongs(page.songs, page.sections.playlistSuggestions()) {
            _page.value = page
            _playlistTrackCount.intValue = page.songs.size
        }
        return _songs.value
    }

    fun withAllSongs(action: suspend (List<SongItem>) -> Unit) {
        viewModelScope.launch {
            _isLoadingMore.value = true
            try {
                val songs = mutationMutex.withLock { completePage() }
                action(songs)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _operationState.value = PlaylistOperationState(message = getString(Res.string.error_unknown))
            } finally {
                _isLoadingMore.value = false
            }
        }
    }

    fun withPlaybackSongs(
        options: PlaylistPresentationOptions,
        action: suspend (List<SongItem>, PlaylistPage?) -> Unit,
    ) {
        if (options.requiresCompletePlaylist()) {
            withAllSongs { action(it, null) }
        } else {
            val (songs, page) = Snapshot.withMutableSnapshot { _songs.value to _page.value }
            viewModelScope.launch {
                try {
                    action(songs, page)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    _operationState.value = PlaylistOperationState(message = getString(Res.string.error_unknown))
                }
            }
        }
    }

    private suspend fun loadLocalPlaylist(
        id: String,
        preferBrowseId: Boolean = false,
    ): Boolean {
        val localId = if (preferBrowseId) database.dao().findPlaylistEntity(id)?.id ?: return false else id
        val playlist = database.dao().playlist(localId).first() ?: return false
        val playlistSongs = database.dao().playlistSongs(playlist.id).first()
        val songs =
            withContext(Dispatchers.Default) {
                playlistSongs.map { it.toSongItem() }
            }
        localSongMapIds.clear()
        songs.forEachIndexed { index, song -> localSongMapIds[song] = playlistSongs[index].map.id }
        val author = getString(Res.string.you)
        publishLoadedSongs(songs) {
            _playlistTrackCount.intValue = playlistSongs.size
            _isFavorite.value = playlist.playlist.bookmarkedAt != null
            _playlistInfo.value =
                PlaylistItem(
                    id = playlist.playlist.browseId ?: playlist.id,
                    title = playlist.title,
                    thumbnail = playlist.thumbnailUrl,
                    author = author,
                    songCount = playlist.songCount.toString(),
                    isEditable = playlist.playlist.isEditable,
                    description = playlist.playlist.description,
                    isLocal = playlist.playlist.isLocal || playlist.playlist.browseId == null,
                    customThumbnail = playlist.playlist.customThumbnailUrl,
                ).withNavigationThumbnail(thumbnailHint)
        }
        return true
    }

    private suspend fun showMissingPlaylist(
        error: Throwable,
        expectedPlaylistId: String?,
    ): Boolean {
        if (error !is PlaylistNotFoundException || _playlistId.value != expectedPlaylistId) return false
        publishLoadedSongs(emptyList()) {
            _playlistInfo.value = null
            _page.value = null
            _playlistTrackCount.intValue = 0
            _isFavorite.value = false
        }
        val message = getString(Res.string.playlist_removed_from_youtube)
        _errorMessage.value = message
        _operationState.value = PlaylistOperationState(message = message)
        return true
    }

    fun removeSong(song: SongItem) = removeSongs(listOf(song))

    fun removeSongs(songs: List<SongItem>) {
        val playlistId = _playlistId.value ?: return
        if (songs.isEmpty() || _operationState.value.inProgressSongIds.isNotEmpty()) return
        _operationState.value = PlaylistOperationState(inProgressSongIds = songs.mapTo(mutableSetOf()) { it.id })
        applicationScope.launch(Dispatchers.Main.immediate) {
            mutationMutex.withLock {
                try {
                    val songs = if (_playlistId.value == playlistId) currentOccurrences(songs) else emptyList()
                    if (songs.isEmpty()) {
                        _operationState.value = PlaylistOperationState()
                        return@withLock
                    }
                    if (playlistId == "liked") {
                        SongLikeState.removeLikes(songs.map { it.id })
                        if (_playlistId.value == playlistId) {
                            val liked = SongLikeState.likedSongs.toList()
                            publishLoadedSongs(liked) { _playlistTrackCount.intValue = liked.size }
                        }
                    } else {
                        removeSongsFromStoredPlaylist(
                            database = database,
                            repository = repositoryProvider(),
                            playlistId = playlistId,
                            songs = songs,
                        ).getOrThrow()

                        if (_playlistId.value == playlistId) {
                            val local = localPlaylistFor(playlistId)?.playlist
                            val remoteId =
                                if (local != null) {
                                    local.browseId.takeUnless { local.isLocal }
                                } else {
                                    playlistId.takeUnless { it.startsWith("LP") }
                                }
                            if (remoteId != null) {
                                val removed = songs.toSet()
                                val remaining = _durationSongs.value.filterNot { it in removed }
                                publishLoadedSongs(remaining, _suggestions.value, _page.value?.hasMore == true) {
                                    _playlistTrackCount.intValue = remaining.size
                                }
                                loadPlaylist(playlistId)
                            } else {
                                loadLocalPlaylist(playlistId, preferBrowseId = true)
                            }
                        }
                    }
                    _operationState.value =
                        PlaylistOperationState(
                            message =
                                getPluralString(
                                    Res.plurals.removed_songs_from_playlist,
                                    songs.size,
                                    songs.size,
                                ),
                        )
                } catch (e: CancellationException) {
                    _operationState.value = PlaylistOperationState()
                    throw e
                } catch (e: Exception) {
                    if (showMissingPlaylist(e, playlistId)) return@withLock
                    _operationState.value =
                        PlaylistOperationState(
                            message =
                                getString(
                                    Res.string.could_not_finish_removing_songs,
                                    e.message ?: getString(Res.string.error_unknown),
                                ),
                        )
                }
            }
        }
    }

    private fun currentOccurrences(requested: List<SongItem>): List<SongItem> =
        requested
            .mapNotNull { song ->
                _songs.value.firstOrNull { current ->
                    current.id == song.id &&
                        when {
                            song.playlistSetVideoId != null -> current.playlistSetVideoId == song.playlistSetVideoId
                            song.playlistLocalMappingId != null -> current.playlistLocalMappingId == song.playlistLocalMappingId
                            else -> current == song
                        }
                }
            }.distinct()

    fun addSong(
        song: SongItem,
        allowDuplicate: Boolean = false,
    ) {
        val playlistId = _playlistId.value ?: return
        if (_operationState.value.inProgressSongIds.isNotEmpty()) return
        applicationScope.launch(Dispatchers.Main.immediate) {
            _operationState.value = PlaylistOperationState(inProgressSongIds = setOf(song.id))
            mutationMutex.withLock {
                try {
                    if (!allowDuplicate && _songs.value.any { it.id == song.id }) {
                        _operationState.value = PlaylistOperationState(message = getString(Res.string.skipped_duplicate))
                        return@withLock
                    }
                    if (playlistId == "liked") {
                        SongLikeState.addLike(song)
                        if (_playlistId.value == playlistId) {
                            val liked = SongLikeState.likedSongs.toList()
                            publishLoadedSongs(liked) { _playlistTrackCount.intValue = liked.size }
                        }
                    } else {
                        val playlist = localPlaylistFor(playlistId)
                        val remoteId = playlist?.playlist?.browseId ?: playlistId.takeUnless { it.startsWith("LP") }
                        if (playlist != null) {
                            val result = addSongToPlaylist(database, playlist, song, allowDuplicate)
                            if (result == AddSongToPlaylistResult.InProgress) {
                                _operationState.value = PlaylistOperationState()
                                return@withLock
                            }
                            if (result == AddSongToPlaylistResult.AlreadyPresent) {
                                _operationState.value = PlaylistOperationState(message = getString(Res.string.skipped_duplicate))
                                return@withLock
                            }
                            if (_playlistId.value == playlistId) loadLocalPlaylist(playlistId, preferBrowseId = true)
                        } else if (remoteId != null) {
                            repositoryProvider().addToPlaylist(remoteId, song.id, allowDuplicate = allowDuplicate).getOrThrow()
                        }
                        if (remoteId != null && _playlistId.value == playlistId) loadPlaylist(remoteId)
                    }
                    _operationState.value =
                        PlaylistOperationState(
                            message = getString(Res.string.added_song, song.title),
                        )
                } catch (e: CancellationException) {
                    _operationState.value = PlaylistOperationState()
                    throw e
                } catch (e: PlaylistSongAlreadyPresentException) {
                    _operationState.value = PlaylistOperationState(message = getString(Res.string.skipped_duplicate))
                } catch (e: Exception) {
                    if (showMissingPlaylist(e, playlistId)) return@withLock
                    _operationState.value =
                        PlaylistOperationState(
                            message =
                                getString(
                                    Res.string.could_not_finish_adding_song,
                                    song.title,
                                    e.message ?: getString(Res.string.error_unknown),
                                ),
                        )
                }
            }
        }
    }

    fun consumeOperationMessage() {
        _operationState.value = _operationState.value.copy(message = null)
    }

    private var bookmarkJob: Job? = null

    fun toggleFavorite(downloadSongs: suspend (List<SongItem>) -> Unit): Job? {
        if (bookmarkJob?.isActive == true) return null
        val id = _playlistId.value ?: return null
        val playlist = _playlistInfo.value ?: return null
        val bookmarked = !_isFavorite.value
        val songCount = _playlistTrackCount.intValue.takeUnless { _page.value?.hasMore == true }
        bookmarkJob =
            applicationScope.launch(Dispatchers.Main.immediate) {
                val result =
                    withContext(Dispatchers.IO) {
                        setStoredPlaylistBookmarked(database, repositoryProvider(), id, playlist, bookmarked, songCount)
                    }
                if (_playlistId.value != id) return@launch
                if (result.isSuccess) {
                    _isFavorite.value = bookmarked
                    if (bookmarked && Settings.shared.autoDownloadOnLike) {
                        try {
                            val songs =
                                mutationMutex.withLock {
                                    if (_playlistId.value != id) return@launch
                                    completePage()
                                }
                            if (_playlistId.value == id) downloadSongs(songs)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            _operationState.value = PlaylistOperationState(message = getString(Res.string.error_unknown))
                        }
                    }
                } else {
                    _operationState.value = PlaylistOperationState(message = getString(Res.string.error_unknown))
                }
            }
        return bookmarkJob
    }

    fun moveDisplayedSong(
        fromIndex: Int,
        toIndex: Int,
        selectedSongs: List<SongItem> = emptyList(),
    ) {
        val displayed = presentation.value.songs
        val moving = displayed.getOrNull(fromIndex) ?: return
        val target = displayed.getOrNull(toIndex) ?: return
        val source = _songs.value
        val selected = IdentityHashMap<SongItem, Boolean>().apply { selectedSongs.forEach { put(it, true) } }
        moveSong(
            source.indexOfFirst { it === moving },
            source.indexOfFirst { it === target },
            source.indices.filterTo(mutableSetOf()) { index -> selected.containsKey(source[index]) },
        )
    }

    fun moveSong(
        fromIndex: Int,
        toIndex: Int,
        selectedIndices: Set<Int> = emptySet(),
    ) {
        val playlistId = _playlistId.value ?: return
        val currentSongs = _songs.value
        if (fromIndex !in currentSongs.indices || toIndex !in currentSongs.indices || fromIndex == toIndex) return
        val order = currentSongs.indices.toList().moveSelection(fromIndex, toIndex, selectedIndices)
        if (order == currentSongs.indices.toList()) return
        val reorderedSongs = order.map(currentSongs::get)
        if (reorderOriginalSongs == null) reorderOriginalSongs = currentSongs
        applyMoveToPresentation(currentSongs, reorderedSongs, order)
        val preparationGeneration = ++remoteMovePreparationGeneration

        val originalSongs = reorderOriginalSongs ?: currentSongs
        val originalIndices = IdentityHashMap<SongItem, Int>().apply { originalSongs.forEachIndexed { index, song -> put(song, index) } }
        val moving = if (fromIndex in selectedIndices) selectedIndices.intersect(currentSongs.indices.toSet()) else setOf(fromIndex)
        reorderMovingIndices = reorderMovingIndices + moving.map { index -> originalIndices.getValue(currentSongs[index]) }
        val finalOrder = reorderedSongs.map(originalIndices::getValue)
        val moves = selectionReorderMoves(originalSongs.indices.toList(), finalOrder, reorderMovingIndices)
        val preparedMoves = mutableListOf<suspend () -> Unit>()
        val preview = originalSongs.toMutableList()
        for ((from, to) in moves) {
            val movingSong = preview[from]
            val targetSong = preview[to]
            preview.add(to, preview.removeAt(from))
            preparedMoves += prepareSongMove(playlistId, movingSong, targetSong, from < to, preview.getOrNull(to + 1))
        }
        pendingReorder = {
            reorderMutex.withLock {
                try {
                    preparedMoves.forEach { it() }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Logger.e("PlaylistViewModel", "Playlist reorder failed", error)
                    if (showMissingPlaylist(error, playlistId)) return@withLock
                    if (_playlistId.value == playlistId) {
                        if (preparationGeneration == remoteMovePreparationGeneration) {
                            publishLoadedSongs(originalSongs, _suggestions.value) { }
                        }
                        _operationState.value = PlaylistOperationState(message = getString(Res.string.error_unknown))
                        loadPlaylist()
                    }
                }
            }
        }
    }

    private fun prepareSongMove(
        playlistId: String,
        movingSong: SongItem,
        targetSong: SongItem,
        afterTarget: Boolean,
        successorSong: SongItem?,
    ): suspend () -> Unit {
        val movingMapId = localSongMapIds[movingSong]
        val targetMapId = localSongMapIds[targetSong]
        val movingSetVideoId = movingSong.playlistSetVideoId
        val directRemoteId = playlistId.takeUnless { it.startsWith("LP") }
        return {
            val playlist = localPlaylistFor(playlistId)
            val playlistSongs = playlist?.let { database.dao().playlistSongs(it.id).first() }.orEmpty()
            val maps = playlistSongs.map { it.map }
            val moving = findPlaylistSongMap(movingSong, movingMapId, maps)
            val target = findPlaylistSongMap(targetSong, targetMapId, maps)
            var successor = successorSong?.playlistSetVideoId
            if (moving != null && target != null) {
                val rawOrder = playlistSongs.map { it.map }.toMutableList()
                val rawMovingIndex = rawOrder.indexOfFirst { it.id == moving.id }
                if (rawMovingIndex >= 0) {
                    val rawMoving = rawOrder.removeAt(rawMovingIndex)
                    val adjustedTarget = rawOrder.indexOfFirst { it.id == target.id }
                    if (adjustedTarget >= 0) {
                        val insertionIndex = if (!afterTarget) adjustedTarget else adjustedTarget + 1
                        rawOrder.add(insertionIndex.coerceIn(0, rawOrder.size), rawMoving)
                        successor = rawOrder.getOrNull(rawOrder.indexOf(rawMoving) + 1)?.setVideoId
                    }
                }
            }
            val remoteId = playlist?.playlist?.browseId ?: directRemoteId
            val localMovingSetVideoId = moving?.setVideoId ?: movingSetVideoId
            if (remoteId != null) {
                checkNotNull(localMovingSetVideoId)
                moveRemoteSong(remoteId, localMovingSetVideoId, successor).getOrThrow()
            }
            if (playlist != null && moving != null && target != null) {
                database.dao().move(playlist.id, moving.position, target.position)
            }
        }
    }

    /** Update order and keys synchronously during dragging to avoid jitter from background rebuilds. */
    private fun applyMoveToPresentation(
        previousSongs: List<SongItem>,
        reorderedSongs: List<SongItem>,
        order: List<Int>,
    ) {
        val options = presentationOptions.value
        val current = _presentation.value
        val canMoveInPlace =
            options.reorder &&
                options.query.isBlank() &&
                current.songs === previousSongs &&
                current.keys.size == previousSongs.size
        if (!canMoveInPlace) {
            _songs.value = reorderedSongs
            return
        }
        val keys = order.map(current.keys::get)
        val indices = IdentityHashMap<SongItem, Int>().apply { reorderedSongs.forEachIndexed { index, song -> put(song, index) } }
        Snapshot.withMutableSnapshot {
            _songs.value = reorderedSongs
            sourceIndices = indices
            _presentation.value = PlaylistPresentation(reorderedSongs, keys, keys.withIndex().associate { (index, key) -> key to index })
            preparedSongs = reorderedSongs
            preparedOptions = options
        }
    }

    fun commitPendingReorder() {
        val reorder = pendingReorder ?: return
        pendingReorder = null
        reorderOriginalSongs = null
        reorderMovingIndices = emptySet()
        applicationScope.launch(Dispatchers.Main.immediate) { reorder() }
    }

    override fun onCleared() {
        commitPendingReorder()
    }

    fun renamePlaylist(
        name: String,
        onResult: (Result<Unit>) -> Unit,
    ) {
        val id = _playlistId.value
        launchMutation(onResult) {
            if (id == null) {
                return@launchMutation Result.failure(IllegalStateException(getString(Res.string.playlist_unavailable)))
            }
            val result = renameStoredPlaylist(database, repositoryProvider(), id, name)
            result.onSuccess { _playlistInfo.value = _playlistInfo.value?.copy(title = name.trim()) }
            result
        }
    }

    fun updatePlaylist(
        name: String,
        description: String,
        onResult: (Result<Unit>) -> Unit,
    ) {
        val id = _playlistId.value
        val current = _playlistInfo.value
        launchMutation(onResult) {
            if (id == null) {
                return@launchMutation Result.failure(IllegalStateException(getString(Res.string.playlist_unavailable)))
            }
            if (current == null) {
                return@launchMutation Result.failure(IllegalStateException(getString(Res.string.playlist_metadata_unavailable)))
            }
            val normalizedName = name.trim()
            val normalizedDescription = description.trim()
            if (normalizedName.isEmpty()) {
                return@launchMutation Result.failure(IllegalArgumentException(getString(Res.string.error_playlist_name_empty)))
            }
            val result =
                updateStoredPlaylist(
                    database = database,
                    repository = repositoryProvider(),
                    id = id,
                    currentName = current.title,
                    currentDescription = current.description,
                    name = normalizedName,
                    description = normalizedDescription,
                )
            val partial = result.exceptionOrNull() as? PlaylistPartiallyUpdatedException
            if (partial != null) {
                _playlistInfo.value = _playlistInfo.value?.copy(title = partial.updatedName)
            }
            if (result.isSuccess) {
                _playlistInfo.value =
                    _playlistInfo.value?.copy(
                        title = normalizedName,
                        description = normalizedDescription.ifEmpty { null },
                    )
            }
            result
        }
    }

    fun deletePlaylist(onResult: (Result<Unit>) -> Unit) {
        val id = _playlistId.value
        launchMutation(onResult) {
            if (id == null) {
                return@launchMutation Result.failure(IllegalStateException(getString(Res.string.playlist_unavailable)))
            }
            val result = deleteStoredPlaylist(database, repositoryProvider(), id)
            result.onSuccess {
                _playlistInfo.value = null
                _songs.value = emptyList()
                _durationSongs.value = emptyList()
                _suggestions.value = emptyList()
            }
            result
        }
    }

    fun setPlaylistCover(
        coverUrl: String,
        onResult: (Result<Unit>) -> Unit,
    ) {
        val id = _playlistId.value
        launchMutation(onResult) {
            if (id == null) {
                return@launchMutation Result.failure(IllegalStateException(getString(Res.string.playlist_unavailable)))
            }
            val result =
                if (id == "liked") {
                    runCatching { Settings.shared.setCollectionCover(id, coverUrl.trim()) }
                } else {
                    setStoredPlaylistCover(database, repositoryProvider(), id, coverUrl, _playlistInfo.value)
                }
            result.onSuccess {
                thumbnailHint = null
                _playlistInfo.value = _playlistInfo.value?.copy(thumbnail = coverUrl.trim(), customThumbnail = coverUrl.trim())
            }
            result
        }
    }

    fun uploadPlaylistCover(
        image: ByteArray,
        onResult: (Result<String?>) -> Unit,
    ) {
        val id = _playlistId.value
        launchMutation(onResult) {
            if (id == null) {
                return@launchMutation Result.failure(IllegalStateException(getString(Res.string.playlist_unavailable)))
            }
            val result = uploadStoredPlaylistCover(database, repositoryProvider(), id, image, _playlistInfo.value)
            result.onSuccess { remoteThumbnail ->
                thumbnailHint = null
                val current = _playlistInfo.value
                if (current != null) {
                    val effective = remoteThumbnail?.takeIf { it.isNotBlank() } ?: current.thumbnail
                    _playlistInfo.value = current.copy(thumbnail = effective, customThumbnail = effective)
                }
            }
            result
        }
    }

    fun removePlaylistCover(onResult: (Result<String?>) -> Unit) {
        val id = _playlistId.value
        launchMutation(onResult) {
            if (id == null) {
                return@launchMutation Result.failure(IllegalStateException(getString(Res.string.playlist_unavailable)))
            }
            val result =
                if (id == "liked") {
                    runCatching {
                        Settings.shared.setCollectionCover(id, null)
                        null
                    }
                } else {
                    removeStoredPlaylistCover(database, repositoryProvider(), id)
                }
            result.onSuccess { fallback ->
                thumbnailHint = null
                val current = _playlistInfo.value
                if (current != null) {
                    _playlistInfo.value = current.copy(thumbnail = fallback ?: current.thumbnail, customThumbnail = null)
                }
                loadPlaylist(id)
            }
            result
        }
    }

    private fun <T> launchMutation(
        onResult: (Result<T>) -> Unit,
        mutation: suspend () -> Result<T>,
    ) {
        val result = applicationScope.async(Dispatchers.Main.immediate) { mutation() }
        viewModelScope.launch { onResult(result.await()) }
    }

    private fun loadDownloadedSongIds() {
        viewModelScope.launch {
            database.dao().cachedAudioIdsByKind(KIND_DOWNLOAD).distinctUntilChanged().collect { ids ->
                _downloadedSongIds.value = withContext(Dispatchers.Default) { ids.toSet() }
            }
        }
    }

    private suspend fun localPlaylistFor(id: String): Playlist? = findLocalPlaylist(id)

    private suspend fun cachePlaylist(
        requestedId: String,
        playlistInfo: PlaylistItem?,
        songs: List<SongItem>,
    ) {
        if (playlistInfo != null) withContext(Dispatchers.IO) { database.cacheRemotePlaylist(requestedId, playlistInfo, songs) }
    }
}

internal suspend fun Job.joinSuccessfully(): Boolean {
    join()
    return !isCancelled
}

data class PlaylistOperationState(
    val inProgressSongIds: Set<String> = emptySet(),
    val message: String? = null,
)

internal data class LoadedPlaylist(
    val songs: List<SongItem>,
    val suggestions: List<SongItem>,
    val presentation: PlaylistPresentation,
    val sourceIndices: Map<SongItem, Int>,
)

internal suspend fun prepareLoadedPlaylist(
    songs: List<SongItem>,
    suggestions: List<SongItem>,
    options: PlaylistPresentationOptions,
    hasMore: Boolean = false,
): LoadedPlaylist =
    LoadedPlaylist(
        songs,
        suggestions,
        if (hasMore && options.requiresCompletePlaylist()) PlaylistPresentation() else preparePlaylistPresentation(songs, options),
        IdentityHashMap<SongItem, Int>().apply { songs.forEachIndexed { index, song -> put(song, index) } },
    )

private data class PlaylistPresentationInput(
    val songs: List<SongItem>,
    val options: PlaylistPresentationOptions,
    val hasMore: Boolean,
    val revision: Long,
)

private fun PlaylistPresentationOptions.requiresCompletePlaylist(): Boolean = !reorder && sort != PlaylistSongSort.ORIGINAL

internal fun findPlaylistSongMap(
    song: SongItem,
    mapId: Int?,
    maps: List<PlaylistSongMap>,
): PlaylistSongMap? =
    when {
        mapId != null -> maps.firstOrNull { it.id == mapId }
        song.playlistSetVideoId != null -> maps.firstOrNull { it.setVideoId == song.playlistSetVideoId }
        else -> maps.singleOrNull { it.songId == song.id }
    }
