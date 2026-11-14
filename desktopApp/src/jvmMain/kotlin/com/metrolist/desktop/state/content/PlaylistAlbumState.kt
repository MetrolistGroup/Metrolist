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
import com.metrolist.desktop.state.playback.PlayerState
import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.InnerTubeHttpException
import com.metrolist.shared.api.PlaylistNotFoundException
import com.metrolist.shared.api.playlistSongs
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.db.emptyHistoryPlaylist
import com.metrolist.shared.db.entities.toSongItem
import com.metrolist.shared.db.loadAndCachePlaylistPage
import com.metrolist.shared.db.refreshHistoryPlaylist
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.error_occurred
import com.metrolist.shared.generated.resources.playlist_removed_from_youtube
import com.metrolist.shared.model.AlbumPage
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.YTItem
import com.metrolist.shared.model.moveSelection
import com.metrolist.shared.playlist.PlaylistChanges
import com.metrolist.shared.playlist.PlaylistPartiallyUpdatedException
import com.metrolist.shared.playlist.addSongToStoredPlaylist
import com.metrolist.shared.playlist.deleteStoredPlaylist
import com.metrolist.shared.playlist.moveSongInLocalPlaylist
import com.metrolist.shared.playlist.removeSongsFromStoredPlaylist
import com.metrolist.shared.playlist.removeStoredPlaylistCover
import com.metrolist.shared.playlist.setStoredPlaylistBookmarked
import com.metrolist.shared.playlist.setStoredPlaylistCover
import com.metrolist.shared.playlist.updateStoredPlaylist
import com.metrolist.shared.playlist.uploadStoredPlaylistCover
import com.metrolist.shared.settings.BlockedContent
import com.metrolist.shared.state.GlobalYouTube
import com.metrolist.shared.state.GlobalYouTubeRepository
import com.metrolist.shared.ui.navigation.Screen
import com.metrolist.shared.youtube.logging.YtLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.getString
import org.koin.core.context.GlobalContext

object PlaylistAlbumState {
    private val database: MusicDatabase by lazy { GlobalContext.get().get() }
    private var playlistLoadJob: Job? = null
    private var albumLoadJob: Job? = null
    private var albumSession: InnerTube.SessionSnapshot? = null

    var selectedPlaylistId by mutableStateOf<String?>(null)
    var playlistData by mutableStateOf<Map<String, List<YTItem>>>(emptyMap())
    private var unfilteredPlaylistData: Map<String, List<YTItem>> = emptyMap()
    private var playlistDurationComplete by mutableStateOf(false)
    val playlistDurationSongs: List<SongItem> get() = if (playlistDurationComplete) unfilteredPlaylistData.playlistSongs() else emptyList()

    private data class PendingPlaylistReorder(
        val playlistId: String,
        val moves: List<PlaylistSongReorder>,
        val movingSongs: Set<SongItem>,
        val previousData: Map<String, List<YTItem>>,
    )

    private var pendingPlaylistReorder: PendingPlaylistReorder? = null
    var isPlaylistReordering by mutableStateOf(false)
        private set
    var playlistTrackCount by mutableStateOf(0)
        private set
    var isPlaylistBookmarked by mutableStateOf(false)
        private set
    var playlistHasMore by mutableStateOf(false)
        private set
    var isPlaylistLoading by mutableStateOf(false)
    var playlistLoadError by mutableStateOf<Exception?>(null)
        private set
    var pendingPlaylistThumbnail by mutableStateOf<String?>(null)
    var selectedPlaylistIdSetter: String? = null

    var selectedLocalPlaylist: String? by mutableStateOf(null)

    var selectedAlbumId by mutableStateOf<String?>(null)
    var albumPageData by mutableStateOf<AlbumPage?>(null)
    private var unfilteredAlbumPageData: AlbumPage? = null
    var albumLoadFailed by mutableStateOf(false)
        private set
    var isAlbumLoading by mutableStateOf(false)
    var selectedAlbumIdSetter: String? = null

    init {
        AppState.scope.launch {
            PlaylistChanges.flow.collect { changedId ->
                val id = selectedPlaylistId ?: return@collect
                val remoteId = database.dao().findPlaylistEntity(id)?.browseId ?: id
                if (selectedPlaylistId == id && remoteId.removePrefix("VL") == changedId) {
                    fetchPlaylistData(id, pendingPlaylistThumbnail, addToHistory = false)
                }
            }
        }
        AppState.scope.launch {
            BlockedContent.version.drop(1).collect {
                applyBlockedContentFilter()
            }
        }
    }

    fun fetchPlaylistData(
        id: String,
        thumbnailHint: String? = null,
        addToHistory: Boolean = true,
    ) {
        pendingPlaylistReorder = null
        PodcastState.clear()
        if (addToHistory) DesktopNavigation.navigate(Screen.Playlist(id, thumbnailHint = thumbnailHint))
        PlayerState.exitExpanded()
        if (selectedPlaylistId != id) {
            playlistData = emptyMap()
            unfilteredPlaylistData = emptyMap()
        }
        selectedPlaylistId = id
        playlistLoadError = null
        playlistHasMore = unfilteredPlaylistData.isEmpty() || playlistHasMore
        selectedLocalPlaylist = null
        ArtistState.selectedArtistId = null
        selectedAlbumId = null
        pendingPlaylistThumbnail = thumbnailHint
        isPlaylistBookmarked = false
        isPlaylistLoading = true
        playlistLoadJob?.cancel()
        playlistLoadJob =
            AppState.scope.launch {
                try {
                    val localPlaylist =
                        withContext(Dispatchers.IO) {
                            database.refreshHistoryPlaylist(id)
                            database.dao().playlist(id).first()
                                ?: database.dao().playlistByBrowseId(id.removePrefix("VL")).first()
                                ?: database.dao().playlistByBrowseId(id).first()
                        }
                    if (localPlaylist != null) {
                        val localSongs =
                            withContext(Dispatchers.IO) {
                                database.dao().playlistSongs(localPlaylist.id).first()
                            }
                        val headerItem =
                            PlaylistItem(
                                id = localPlaylist.playlist.browseId ?: localPlaylist.id,
                                title = localPlaylist.title,
                                thumbnail = localPlaylist.playlist.customThumbnailUrl ?: thumbnailHint ?: localPlaylist.thumbnailUrl,
                                customThumbnail = localPlaylist.playlist.customThumbnailUrl,
                                author = null,
                                songCount = localSongs.size.toString(),
                                isEditable = localPlaylist.playlist.isEditable,
                                description = localPlaylist.playlist.description,
                                isLocal = localPlaylist.playlist.isLocal || localPlaylist.playlist.browseId == null,
                            )
                        val songItems =
                            localSongs.map {
                                it.toSongItem().copy(
                                    playlistLocalMappingId = if (headerItem.isLocal) it.map.id else null,
                                )
                            }
                        if (selectedPlaylistId == id) {
                            playlistHasMore = !hasCompleteStoredPlaylist(localPlaylist.playlist, songItems.size)
                            playlistDurationComplete = !playlistHasMore
                            playlistTrackCount = songItems.size
                            isPlaylistBookmarked = localPlaylist.playlist.bookmarkedAt != null
                            unfilteredPlaylistData = mapOf("header" to listOf(headerItem), "songs" to songItems)
                            playlistData = unfilteredPlaylistData.withoutBlockedContent()
                        }
                        if (localPlaylist.playlist.isLocal || localPlaylist.playlist.browseId == null) return@launch
                    }

                    if (localPlaylist == null) {
                        emptyHistoryPlaylist(id)?.let { header ->
                            if (selectedPlaylistId == id) {
                                playlistHasMore = false
                                playlistTrackCount = 0
                                isPlaylistBookmarked = false
                                playlistDurationComplete = true
                                unfilteredPlaylistData = mapOf("header" to listOf(header), "songs" to emptyList())
                                playlistData = unfilteredPlaylistData
                            }
                            return@launch
                        }
                    }
                    val targetFetchId = localPlaylist?.playlist?.browseId ?: id
                    val loadedPage =
                        withContext(Dispatchers.IO) {
                            database.loadAndCachePlaylistPage(GlobalYouTubeRepository.instance, targetFetchId) { firstPage ->
                                val customCover = database.dao().findPlaylistEntity(id)?.customThumbnailUrl
                                val header = firstPage.sections["header"]?.firstOrNull() as? PlaylistItem
                                val sections =
                                    if (customCover != null && header != null) {
                                        firstPage.sections +
                                            ("header" to listOf(header.copy(thumbnail = customCover, customThumbnail = customCover)))
                                    } else {
                                        firstPage.sections
                                    }
                                withContext(Dispatchers.Main) {
                                    if (selectedPlaylistId == id) {
                                        if (playlistHasMore || unfilteredPlaylistData.playlistSongs().isEmpty()) {
                                            playlistHasMore = firstPage.hasMore
                                            playlistDurationComplete = !firstPage.hasMore && !firstPage.isOffline
                                            playlistTrackCount = firstPage.songs.size
                                            unfilteredPlaylistData = sections
                                            playlistData = unfilteredPlaylistData.withoutBlockedContent()
                                        }
                                    }
                                }
                            }
                        }
                    val unfilteredResult = loadedPage.sections
                    val playlist = unfilteredResult["header"]?.firstOrNull() as? PlaylistItem
                    val storedCustom =
                        withContext(Dispatchers.IO) {
                            val entity =
                                localPlaylist?.playlist
                                    ?: database.dao().findPlaylistEntity(id)
                                    ?: database.dao().findPlaylistEntity(id.removePrefix("VL"))
                            entity?.customThumbnailUrl
                        }
                    val effectivePlaylist =
                        if (storedCustom.isNullOrBlank()) {
                            playlist
                        } else {
                            playlist?.copy(thumbnail = storedCustom, customThumbnail = storedCustom)
                        }
                    val isBookmarked =
                        if (effectivePlaylist == null) {
                            false
                        } else {
                            runCatching {
                                withContext(Dispatchers.IO) {
                                    val entity =
                                        localPlaylist?.playlist
                                            ?: database.dao().findPlaylistEntity(id)
                                            ?: database.dao().findPlaylistEntity(id.removePrefix("VL"))
                                    entity?.let {
                                        database.dao().update(
                                            it.copy(
                                                name = effectivePlaylist.title,
                                                description = effectivePlaylist.description,
                                                thumbnailUrl = playlist?.thumbnail ?: it.thumbnailUrl,
                                                isEditable = effectivePlaylist.isEditable,
                                            ),
                                        )
                                    }
                                    entity?.bookmarkedAt != null
                                }
                            }.getOrDefault(false)
                        }
                    if (selectedPlaylistId == id) {
                        playlistHasMore = false
                        playlistDurationComplete = !loadedPage.hasMore && !loadedPage.isOffline
                        playlistTrackCount = unfilteredResult.playlistSongs().size
                        isPlaylistBookmarked = isBookmarked
                        unfilteredPlaylistData =
                            if (effectivePlaylist == null) {
                                unfilteredResult
                            } else {
                                unfilteredResult + ("header" to listOf(effectivePlaylist))
                            }
                        playlistData = unfilteredPlaylistData.withoutBlockedContent()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (selectedPlaylistId == id) {
                        if (e is PlaylistNotFoundException) {
                            playlistData = emptyMap()
                            unfilteredPlaylistData = emptyMap()
                            isPlaylistBookmarked = false
                        }
                        playlistLoadError = e
                    }
                } finally {
                    if (selectedPlaylistId == id && currentCoroutineContext().isActive) isPlaylistLoading = false
                }
            }
    }

    fun withCompletePlaylist(action: (List<SongItem>) -> Unit) {
        val id = selectedPlaylistId ?: return
        AppState.scope.launch {
            val complete = awaitCompletePlaylist(playlistLoadJob) { playlistHasMore }
            if (selectedPlaylistId == id && complete) {
                action(playlistData.playlistSongs())
            }
        }
    }

    private var bookmarkJob: Job? = null

    fun toggleSelectedPlaylistBookmark() {
        if (bookmarkJob?.isActive == true) return
        val id = selectedPlaylistId ?: return
        val playlist = playlistData["header"]?.firstOrNull() as? PlaylistItem ?: return
        val bookmarked = !isPlaylistBookmarked
        val songCount = playlistTrackCount.takeUnless { playlistHasMore }
        bookmarkJob =
            AppState.scope.launch {
                val result =
                    withContext(Dispatchers.IO) {
                        setStoredPlaylistBookmarked(database, GlobalYouTubeRepository.instance, id, playlist, bookmarked, songCount)
                    }
                if (selectedPlaylistId != id) return@launch
                if (result.isSuccess) {
                    isPlaylistBookmarked = bookmarked
                    LibraryContentState.fetchLibraryData()
                } else {
                    AppState.snackbarHostState.showSnackbar(getString(Res.string.error_occurred))
                }
            }
    }

    fun restoreAlbum(id: String): Boolean {
        if (albumLoadFailed || !canRestoreAlbum(albumPageData, id, albumSession, GlobalYouTube.innerTube.sessionSnapshot())) return false
        selectedAlbumId = id
        return true
    }

    fun openSongAlbum(song: SongItem) {
        val session = GlobalYouTube.innerTube.sessionSnapshot()
        AppState.scope.launch {
            val album = withContext(Dispatchers.IO) { GlobalYouTubeRepository.instance.resolveSongAlbum(song) }
            if (GlobalYouTube.innerTube.sessionSnapshot() != session || PlayerState.currentTrack?.id != song.id) return@launch
            val id = album?.id
            if (id == null) {
                AppState.snackbarHostState.showSnackbar(getString(Res.string.error_occurred))
            } else {
                fetchAlbumData(id)
            }
        }
    }

    fun fetchAlbumData(
        id: String,
        addToHistory: Boolean = true,
        artworkSource: String? = null,
    ) {
        PodcastState.clear()
        if (addToHistory) DesktopNavigation.navigate(Screen.Album(id, artworkSource = artworkSource))
        PlayerState.exitExpanded()
        selectedAlbumId = id
        val session = GlobalYouTube.innerTube.sessionSnapshot()
        albumSession = null
        albumPageData = null
        unfilteredAlbumPageData = null
        selectedLocalPlaylist = null
        ArtistState.selectedArtistId = null
        selectedPlaylistId = null
        albumLoadFailed = false
        isAlbumLoading = true
        albumLoadJob?.cancel()
        albumLoadJob =
            AppState.scope.launch {
                var firstPage: AlbumPage? = null
                try {
                    val result =
                        withContext(Dispatchers.IO) {
                            GlobalYouTubeRepository.instance.getAlbum(id, loadAllPages = false, applyContentFilters = false)
                        }
                    if (selectedAlbumId != id || GlobalYouTube.innerTube.sessionSnapshot() != session) return@launch
                    albumLoadFailed = result == null
                    firstPage = result
                    val cursor = result?.playlistPage
                    val page =
                        if (cursor?.hasMore == true) {
                            val complete =
                                withContext(Dispatchers.IO) {
                                    GlobalYouTubeRepository.instance.completePlaylistPage(cursor, applyContentFilters = false)
                                }
                            result.copy(songs = complete.songs, playlistPage = complete)
                        } else {
                            result
                        }
                    if (selectedAlbumId == id && GlobalYouTube.innerTube.sessionSnapshot() == session) {
                        unfilteredAlbumPageData = page
                        albumSession = session
                        albumPageData = page?.let(BlockedContent::filterAlbumPage)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (error: Exception) {
                    YtLogger.e(
                        "AlbumBrowse",
                        "Album browse id=$id continuation=${firstPage != null} " +
                            "status=${(error as? InnerTubeHttpException)?.status?.value} type=${error::class.simpleName}",
                    )
                    if (selectedAlbumId == id && GlobalYouTube.innerTube.sessionSnapshot() == session) {
                        albumLoadFailed = true
                        unfilteredAlbumPageData = firstPage
                        albumSession = session
                        albumPageData = firstPage?.let(BlockedContent::filterAlbumPage)
                        if (firstPage != null) {
                            AppState.snackbarHostState.showSnackbar(getString(Res.string.error_occurred))
                        }
                    }
                } finally {
                    if (selectedAlbumId == id && currentCoroutineContext().isActive) isAlbumLoading = false
                }
            }
    }

    fun updateSelectedPlaylist(
        name: String,
        description: String,
        onResult: (Result<Unit>) -> Unit,
    ) {
        val id =
            selectedPlaylistId
                ?: return onResult(Result.failure(IllegalStateException("Playlist is unavailable")))
        val playlist =
            playlistData["header"]?.firstOrNull() as? PlaylistItem
                ?: return onResult(Result.failure(IllegalStateException("Playlist metadata is unavailable")))
        AppState.scope.launch {
            val normalizedName = name.trim()
            val normalizedDescription = description.trim()
            if (normalizedName.isEmpty()) {
                onResult(Result.failure(IllegalArgumentException()))
                return@launch
            }
            val result =
                withContext(Dispatchers.IO) {
                    updateStoredPlaylist(
                        database = database,
                        repository = GlobalYouTubeRepository.instance,
                        id = id,
                        currentName = playlist.title,
                        currentDescription = playlist.description,
                        name = normalizedName,
                        description = normalizedDescription,
                    )
                }
            if (result.isSuccess || result.exceptionOrNull() is PlaylistPartiallyUpdatedException) {
                val thumbnail = pendingPlaylistThumbnail
                fetchPlaylistData(id, thumbnail, addToHistory = false)
                LibraryContentState.fetchLibraryData()
            }
            onResult(result)
        }
    }

    fun deleteSelectedPlaylist(onResult: (Result<Unit>) -> Unit) {
        playlistLoadJob?.cancel()
        isPlaylistLoading = false
        val id =
            selectedPlaylistId
                ?: return onResult(Result.failure(IllegalStateException("Playlist is unavailable")))
        AppState.scope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    deleteStoredPlaylist(database, GlobalYouTubeRepository.instance, id)
                }
            if (result.isSuccess) {
                selectedPlaylistId = null
                unfilteredPlaylistData = emptyMap()
                playlistData = emptyMap()
                LibraryContentState.fetchLibraryData()
            }
            onResult(result)
        }
    }

    fun setSelectedPlaylistCover(
        coverUrl: String,
        onResult: (Result<Unit>) -> Unit,
    ) {
        val id =
            selectedPlaylistId
                ?: return onResult(Result.failure(IllegalStateException("Playlist is unavailable")))
        AppState.scope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    setStoredPlaylistCover(
                        database,
                        GlobalYouTubeRepository.instance,
                        id,
                        coverUrl,
                        playlistData["header"]?.firstOrNull() as? PlaylistItem,
                    )
                }
            if (result.isSuccess) {
                fetchPlaylistData(id, pendingPlaylistThumbnail, addToHistory = false)
                LibraryContentState.fetchLibraryData()
            }
            onResult(result)
        }
    }

    fun uploadSelectedPlaylistCover(
        image: ByteArray,
        onResult: (Result<String?>) -> Unit,
    ) {
        val id =
            selectedPlaylistId
                ?: return onResult(Result.failure(IllegalStateException("Playlist is unavailable")))
        AppState.scope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    uploadStoredPlaylistCover(
                        database,
                        GlobalYouTubeRepository.instance,
                        id,
                        image,
                        playlistData["header"]?.firstOrNull() as? PlaylistItem,
                    )
                }
            if (result.isSuccess) {
                fetchPlaylistData(id, pendingPlaylistThumbnail, addToHistory = false)
                LibraryContentState.fetchLibraryData()
            }
            onResult(result)
        }
    }

    fun removeSelectedPlaylistCover(onResult: (Result<String?>) -> Unit) {
        val id =
            selectedPlaylistId
                ?: return onResult(Result.failure(IllegalStateException("Playlist is unavailable")))
        AppState.scope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    removeStoredPlaylistCover(database, GlobalYouTubeRepository.instance, id)
                }
            if (result.isSuccess) {
                fetchPlaylistData(id, addToHistory = false)
                LibraryContentState.fetchLibraryData()
            }
            onResult(result)
        }
    }

    fun removeSong(song: SongItem) = removeSongs(listOf(song))

    fun removeSongs(
        songs: List<SongItem>,
        onResult: (Result<Unit>) -> Unit = {},
    ) {
        val playlistId = selectedPlaylistId ?: return onResult(Result.failure(IllegalStateException("Playlist is unavailable")))
        if (songs.isEmpty()) return onResult(Result.success(Unit))
        AppState.scope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    removeSongsFromStoredPlaylist(
                        database = database,
                        repository = GlobalYouTubeRepository.instance,
                        playlistId = playlistId,
                        songs = songs,
                    )
                }
            if (result.isSuccess && selectedPlaylistId == playlistId) {
                val previousCount = unfilteredPlaylistData.playlistSongs().size
                unfilteredPlaylistData = unfilteredPlaylistData.removePlaylistSongs(songs)
                val removedCount = previousCount - unfilteredPlaylistData.playlistSongs().size
                playlistTrackCount = (playlistTrackCount - removedCount).coerceAtLeast(0)
                playlistData = unfilteredPlaylistData.withoutBlockedContent()
            }
            onResult(result)
            if (result.exceptionOrNull() is PlaylistNotFoundException && selectedPlaylistId == playlistId) {
                playlistData = emptyMap()
                unfilteredPlaylistData = emptyMap()
                isPlaylistBookmarked = false
                playlistLoadError = result.exceptionOrNull() as? PlaylistNotFoundException
            }
            if (result.isFailure) {
                AppState.snackbarHostState.showSnackbar(
                    getString(
                        when (result.exceptionOrNull()) {
                            is PlaylistNotFoundException -> Res.string.playlist_removed_from_youtube
                            else -> Res.string.error_occurred
                        },
                    ),
                )
            }
        }
    }

    fun moveSong(
        song: SongItem,
        offset: Int,
    ) {
        val songs = playlistData.playlistSongs()
        val from =
            songs.indexOfFirst {
                when {
                    song.playlistLocalMappingId != null -> it.playlistLocalMappingId == song.playlistLocalMappingId
                    song.playlistSetVideoId != null -> it.playlistSetVideoId == song.playlistSetVideoId
                    else -> it.id == song.id
                }
            }
        if (from < 0) return
        val to = (from + offset).coerceIn(0, songs.lastIndex)
        moveDisplayedSong(from, to)
        commitPendingReorder()
    }

    fun moveDisplayedSong(
        from: Int,
        to: Int,
        selectedSongs: List<SongItem> = emptyList(),
    ) {
        val playlistId = selectedPlaylistId ?: return
        if (playlistHasMore || isPlaylistReordering) return
        val songs = playlistData.playlistSongs()
        if (from == to) return
        val song = songs.getOrNull(from) ?: return
        val target = songs.getOrNull(to) ?: return
        val source = unfilteredPlaylistData.playlistSongs()
        val selected = source.indices.filterTo(mutableSetOf()) { source[it] in selectedSongs }
        val reordered = source.moveSelection(source.indexOf(song), source.indexOf(target), selected)
        if (reordered == source) return
        val previous = pendingPlaylistReorder?.takeIf { it.playlistId == playlistId }
        val previousData = previous?.previousData ?: unfilteredPlaylistData
        val moving = previous?.movingSongs.orEmpty() + if (song in selectedSongs) selectedSongs else listOf(song)
        val moves = previousData.reorderPlaylistSongs(reordered, moving) ?: return
        val preview = moves.lastOrNull()?.sections ?: previousData
        pendingPlaylistReorder = PendingPlaylistReorder(playlistId, moves, moving, previousData)
        unfilteredPlaylistData = preview
        playlistData = unfilteredPlaylistData.withoutBlockedContent()
    }

    fun commitPendingReorder() {
        if (isPlaylistReordering) return
        val pending = pendingPlaylistReorder ?: return
        pendingPlaylistReorder = null
        isPlaylistReordering = true
        AppState.scope.launch {
            try {
                val result =
                    withContext(Dispatchers.IO) {
                        try {
                            for (move in pending.moves) {
                                if (move.localMappingId != null) {
                                    moveSongInLocalPlaylist(
                                        database,
                                        pending.playlistId,
                                        move.localMappingId,
                                        move.successorLocalMappingId,
                                    )
                                } else {
                                    commitRemotePlaylistReorder(database, pending.playlistId, move) {
                                        GlobalYouTubeRepository.instance.movePlaylistSong(
                                            pending.playlistId,
                                            checkNotNull(move.setVideoId),
                                            move.successorSetVideoId,
                                        )
                                    }
                                }.getOrThrow()
                            }
                            Result.success(Unit)
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            Result.failure(error)
                        }
                    }
                if (selectedPlaylistId != pending.playlistId) return@launch
                if (result.isFailure) {
                    if (unfilteredPlaylistData === pending.moves.lastOrNull()?.sections) {
                        unfilteredPlaylistData = pending.previousData
                        playlistData = unfilteredPlaylistData.withoutBlockedContent()
                    }
                    if (pending.moves.size > 1) fetchPlaylistData(pending.playlistId, addToHistory = false)
                    playlistLoadError = result.exceptionOrNull() as? Exception
                }
            } finally {
                isPlaylistReordering = false
            }
        }
    }

    fun addSong(
        song: SongItem,
        allowDuplicate: Boolean,
        onResult: (Result<Unit>) -> Unit,
    ) {
        val playlistId =
            selectedPlaylistId
                ?: return onResult(Result.failure(IllegalStateException("Playlist is unavailable")))
        AppState.scope.launch {
            val result =
                try {
                    withContext(Dispatchers.IO) {
                        val entity = database.dao().findPlaylistEntity(playlistId)
                        if (entity != null || playlistId.startsWith("LP")) {
                            addSongToStoredPlaylist(database, GlobalYouTubeRepository.instance, playlistId, song, allowDuplicate)
                        } else {
                            GlobalYouTubeRepository.instance.addToPlaylist(playlistId, song.id, allowDuplicate = allowDuplicate)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Result.failure(e)
                }
            if (result.isSuccess && selectedPlaylistId == playlistId) {
                fetchPlaylistData(playlistId, pendingPlaylistThumbnail, addToHistory = false)
            }
            onResult(result)
        }
    }

    fun playAlbum(albumId: String) {
        AppState.scope.launch(Dispatchers.IO) {
            try {
                val albumPage = GlobalYouTubeRepository.instance.getAlbum(albumId)
                val songs = albumPage?.songs ?: emptyList()
                if (songs.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        PlayerState.playTrack(songs.first(), songs)
                    }
                }
            } catch (e: Exception) {
            }
        }
    }

    private fun applyBlockedContentFilter() {
        playlistData = unfilteredPlaylistData.withoutBlockedContent()
        albumPageData = unfilteredAlbumPageData?.let(BlockedContent::filterAlbumPage)
    }

    private fun Map<String, List<YTItem>>.withoutBlockedContent(): Map<String, List<YTItem>> =
        mapValues { (title, items) -> if (title == "header") items else BlockedContent.filterItems(items) }
}

internal fun canRestoreAlbum(
    page: AlbumPage?,
    id: String,
    loadedSession: InnerTube.SessionSnapshot?,
    currentSession: InnerTube.SessionSnapshot,
): Boolean = page?.album?.id == id && page?.playlistPage?.hasMore != true && loadedSession == currentSession
