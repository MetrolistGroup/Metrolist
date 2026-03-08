package com.metrolist.desktop.state.playback

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.metrolist.desktop.history.HistoryEntry
import com.metrolist.desktop.history.HistoryRepository
import com.metrolist.desktop.history.HistoryStat
import com.metrolist.desktop.state.content.PlaylistAlbumState
import com.metrolist.shared.api.PlaylistSongAlreadyPresentException
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.db.distinctPlaylistItems
import com.metrolist.shared.db.preserveKnownPlaylistEditability
import com.metrolist.shared.db.selectablePlaylistItems
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playlist.LibraryDeletionTracker
import com.metrolist.shared.playlist.PlaylistAddBatchResult
import com.metrolist.shared.playlist.addSongToStoredPlaylist
import com.metrolist.shared.playlist.addSongsToLocalPlaylist
import com.metrolist.shared.state.GlobalYouTubeRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

internal class DesktopPlaybackLibrary(
    private val scope: CoroutineScope,
    database: () -> MusicDatabase,
    private val loadPlaylists: suspend (Boolean, (List<PlaylistItem>) -> Unit) -> List<PlaylistItem> = { signedIn, publishLocal ->
        val local =
            withContext(Dispatchers.IO) {
                val dao = database().dao()
                val playlists = dao.editablePlaylistsByCreateDateAsc().first()
                val playtimes = dao.playlistLibraryStats(playlists.map { it.id }).associate { it.playlistId to it.playtime }
                playlists
                    .selectablePlaylistItems(signedIn, playtimes = playtimes)
                    .filterNot { LibraryDeletionTracker.isDeleted(it) }
            }
        publishLocal(local)
        if (!signedIn) {
            local
        } else {
            val result = GlobalYouTubeRepository.instance.getUserPlaylistsResult()
            local +
                database()
                    .preserveKnownPlaylistEditability(result.items, result.unresolvedPlaylistEditabilityIds)
                    .filterIsInstance<PlaylistItem>()
                    .filter(PlaylistItem::isEditable)
        }
    },
    private val readRecentHistory: suspend () -> List<HistoryEntry> = { HistoryRepository.getRecentHistory(50) },
    private val readTopSongs: suspend () -> List<HistoryStat> = { HistoryRepository.getMostPlayed(100) },
    private val syncHistory: suspend () -> Boolean = { HistoryRepository.syncFromYouTubeHistory() },
    private val playlistSongIds: suspend (String) -> Set<String> = { playlistId ->
        if (playlistId.startsWith("LP")) {
            database()
                .dao()
                .playlistSongs(playlistId)
                .first()
                .mapTo(mutableSetOf()) { it.song.id }
        } else {
            GlobalYouTubeRepository.instance.playlistSongIds(playlistId)
        }
    },
    private val addLocalBatch: (suspend (String, List<SongItem>) -> PlaylistAddBatchResult)? = { playlistId, songs ->
        addSongsToLocalPlaylist(database(), playlistId, songs)
    },
    private val addSong: suspend (String, SongItem, Boolean) -> Result<Unit> = { playlistId, song, local ->
        if (local) {
            addSongToStoredPlaylist(database(), GlobalYouTubeRepository.instance, playlistId, song)
        } else {
            GlobalYouTubeRepository.instance.addToPlaylist(playlistId, song.id, allowDuplicate = true)
        }
    },
) {
    var userPlaylists by mutableStateOf<List<PlaylistItem>>(emptyList())
        private set
    var userPlaylistMembership by mutableStateOf<Set<String>>(emptySet())
        private set
    var isUserPlaylistsLoading by mutableStateOf(false)
        private set
    var showAddToPlaylistSongs by mutableStateOf<List<SongItem>>(emptyList())
    var showAddToPlaylistSong: SongItem?
        get() = showAddToPlaylistSongs.firstOrNull()
        set(value) {
            showAddToPlaylistSongs = listOfNotNull(value)
        }
    var historyEntries by mutableStateOf<List<HistoryEntry>>(emptyList())
        private set
    var topSongs by mutableStateOf<List<HistoryStat>>(emptyList())
        private set

    fun refreshHistory(): Job =
        scope.launch {
            suspend fun publishLocal() {
                val (recent, top) = withContext(Dispatchers.IO) { readRecentHistory() to readTopSongs() }
                historyEntries = recent
                topSongs = top
            }
            publishLocal()
            try {
                if (withContext(Dispatchers.IO) { syncHistory() }) publishLocal()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
            }
        }

    private var fetchUserPlaylistsJob: Job? = null
    private var playlistRequestGeneration = 0L

    fun dismissAddToPlaylist() {
        playlistRequestGeneration++
        fetchUserPlaylistsJob?.cancel()
        showAddToPlaylistSong = null
        isUserPlaylistsLoading = false
    }

    fun fetchUserPlaylists(
        song: SongItem,
        signedIn: Boolean,
    ) = fetchUserPlaylists(listOf(song), signedIn)

    fun fetchUserPlaylists(
        songs: List<SongItem>,
        signedIn: Boolean,
    ) {
        val generation = ++playlistRequestGeneration
        fetchUserPlaylistsJob?.cancel()
        isUserPlaylistsLoading = true
        userPlaylistMembership = emptySet()

        fun isCurrent() = generation == playlistRequestGeneration && showAddToPlaylistSongs == songs
        fetchUserPlaylistsJob =
            scope.launch {
                try {
                    val playlists =
                        withTimeoutOrNull(PLAYLIST_LOAD_TIMEOUT) {
                            loadPlaylists(signedIn) { local ->
                                if (isCurrent()) {
                                    userPlaylists = local
                                    isUserPlaylistsLoading = false
                                }
                            }
                        } ?: userPlaylists
                    if (!isCurrent()) return@launch
                    userPlaylists = playlists.distinctPlaylistItems()
                    isUserPlaylistsLoading = false
                    val permits = Semaphore(4)
                    val selectedIds = songs.mapTo(mutableSetOf(), SongItem::id)
                    val membership =
                        userPlaylists
                            .map { playlist ->
                                async {
                                    val contains =
                                        try {
                                            permits.withPermit { playlistSongIds(playlist.id).containsAll(selectedIds) }
                                        } catch (e: CancellationException) {
                                            throw e
                                        } catch (_: Exception) {
                                            false
                                        }
                                    if (contains) playlist.id.removePrefix("VL") else null
                                }
                            }.awaitAll()
                            .filterNotNull()
                            .toSet()
                    if (isCurrent()) userPlaylistMembership = membership
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    if (isCurrent()) {
                        userPlaylistMembership = emptySet()
                    }
                } finally {
                    if (isCurrent()) isUserPlaylistsLoading = false
                }
            }
    }

    fun addToPlaylist(
        playlistId: String,
        song: SongItem,
        onResult: (Result<Unit>) -> Unit,
    ) = addToPlaylist(playlistId, listOf(song), onResult)

    fun addToPlaylists(
        playlistIds: List<String>,
        songs: List<SongItem>,
        onPlaylistAdded: (String) -> Unit,
        onResult: (String?, Result<Unit>) -> Unit,
    ) {
        val destinations = playlistIds.distinct()

        fun addNext(index: Int) {
            val id = destinations.getOrNull(index) ?: return onResult(null, Result.success(Unit))
            if (index > 0 && cancelAddition) {
                return onResult(id, Result.failure(PlaylistAdditionStopped(0, songs.size, false)))
            }
            addToPlaylist(id, songs) { result ->
                result.fold(
                    onSuccess = {
                        onPlaylistAdded(id)
                        addNext(index + 1)
                    },
                    onFailure = { onResult(id, Result.failure(it)) },
                )
            }
        }
        addNext(0)
    }

    var playlistAddCompleted by mutableStateOf(0)
        private set
    var playlistAddTotal by mutableStateOf(0)
        private set
    private var cancelAddition = false

    fun cancelPlaylistAddition() {
        cancelAddition = true
    }

    fun addToPlaylist(
        playlistId: String,
        songs: List<SongItem>,
        onResult: (Result<Unit>) -> Unit,
    ) {
        if (playlistAddTotal != 0) return onResult(Result.failure(IllegalStateException()))
        playlistAddCompleted = 0
        playlistAddTotal = songs.size
        cancelAddition = false
        scope.launch {
            var uncertain = false
            val result =
                try {
                    require(songs.isNotEmpty())
                    val local = playlistId.startsWith("LP") || userPlaylists.any { it.id == playlistId && it.isLocal }
                    val existingIds =
                        if (local) {
                            null
                        } else {
                            withTimeoutOrNull(PLAYLIST_ADD_TIMEOUT) { playlistSongIds(playlistId).toMutableSet() }
                                ?: throw PlaylistAdditionStopped(0, songs.size, false)
                        }
                    while (playlistAddCompleted < songs.size) {
                        currentCoroutineContext().ensureActive()
                        if (cancelAddition) throw PlaylistAdditionStopped(playlistAddCompleted, songs.size, false)
                        if (local && addLocalBatch != null) {
                            val addition = addLocalBatch(playlistId, songs.subList(playlistAddCompleted, songs.size))
                            playlistAddCompleted += addition.addedCount
                            when (val failure = addition.failure) {
                                null -> {}

                                is PlaylistSongAlreadyPresentException -> {
                                    playlistAddCompleted++
                                }

                                else -> {
                                    throw failure
                                }
                            }
                            continue
                        }
                        val song = songs[playlistAddCompleted]
                        if (existingIds?.contains(song.id) == true) {
                            playlistAddCompleted++
                            continue
                        }
                        uncertain = true
                        val addition =
                            withTimeoutOrNull(PLAYLIST_ADD_TIMEOUT) { addSong(playlistId, song, local) }
                                ?: throw PlaylistAdditionStopped(playlistAddCompleted, songs.size, true)
                        if (addition.exceptionOrNull() !is PlaylistSongAlreadyPresentException) addition.getOrThrow()
                        uncertain = false
                        existingIds?.add(song.id)
                        playlistAddCompleted++
                    }
                    Result.success(Unit)
                } catch (error: CancellationException) {
                    if (!currentCoroutineContext().isActive) throw error
                    Result.failure(PlaylistAdditionStopped(playlistAddCompleted, songs.size, uncertain, error))
                } catch (error: Exception) {
                    Result.failure(
                        error as? PlaylistAdditionStopped ?: PlaylistAdditionStopped(playlistAddCompleted, songs.size, uncertain, error),
                    )
                } finally {
                    playlistAddTotal = 0
                }
            if (result.isSuccess && PlaylistAlbumState.selectedPlaylistId == playlistId) {
                PlaylistAlbumState.fetchPlaylistData(playlistId, addToHistory = false)
            }
            onResult(result)
        }
    }
}

private val PLAYLIST_LOAD_TIMEOUT = 20.seconds
private val PLAYLIST_ADD_TIMEOUT = 60.seconds

internal class PlaylistAdditionStopped(
    val completed: Int,
    val total: Int,
    val uncertain: Boolean,
    cause: Throwable? = null,
) : Exception(cause)
