package com.metrolist.desktop.state.content

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.metrolist.desktop.state.app.AppState
import com.metrolist.desktop.storage.DownloadManager
import com.metrolist.desktop.storage.DownloadState
import com.metrolist.shared.auth.YouTubeSyncState
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.db.entities.PlaylistEntity
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.UserPlaylist
import com.metrolist.shared.model.YTItem
import com.metrolist.shared.playlist.LibraryDeletionTracker
import com.metrolist.shared.playlist.createStoredLocalPlaylist
import com.metrolist.shared.playlist.deleteStoredPlaylist
import com.metrolist.shared.playlist.renameStoredPlaylist
import com.metrolist.shared.settings.BlockedContent
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.state.GlobalYouTubeRepository
import com.metrolist.shared.viewmodels.LibraryViewModel
import com.metrolist.shared.youtube.logging.YtLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.context.GlobalContext
import kotlin.time.Duration.Companion.seconds

private val CREATE_PLAYLIST_TIMEOUT = 60.seconds

internal class UserPlaylistSourceCache {
    private var localPlaylists: List<PlaylistEntity> = emptyList()
    private var remotePlaylists: List<PlaylistItem> = emptyList()
    private var remoteAccount = ""

    @Synchronized
    fun updateLocal(playlists: List<PlaylistEntity>) {
        localPlaylists = playlists
    }

    @Synchronized
    fun updateRemote(
        playlists: List<PlaylistItem>,
        account: String,
    ) {
        remotePlaylists = playlists
        remoteAccount = account
    }

    @Synchronized
    fun snapshot(account: String): Pair<List<PlaylistEntity>, List<PlaylistItem>> =
        localPlaylists to remotePlaylists.takeIf { remoteAccount == account }.orEmpty()
}

object LibraryContentState {
    private val database: MusicDatabase by lazy { GlobalContext.get().get() }
    private val libraryViewModel =
        LibraryViewModel(
            AppState.scope,
            database,
            DownloadManager.downloadStates.map { states -> states.filterValues { it == DownloadState.DOWNLOADED }.keys },
        )
    var librarySections by mutableStateOf<Map<String, List<YTItem>>>(emptyMap())
        private set
    var userPlaylists by mutableStateOf<List<UserPlaylist>>(emptyList())
        private set
    var libraryError by mutableStateOf(false)
        private set
    var isLibraryLoading by mutableStateOf(true)
        private set
    var contentMatchedSongs by mutableStateOf<List<SongItem>>(emptyList())
        private set
    private var unfilteredLibraryItems: List<YTItem> = emptyList()
    private val playlistSources = UserPlaylistSourceCache()
    private var playlistObservationJob: Job? = null
    private var playlistRefreshJob: Job? = null
    private var playlistRefreshGeneration = 0L

    init {
        refreshUserPlaylists()
        AppState.scope.launch {
            libraryViewModel.uiState.collect { state ->
                unfilteredLibraryItems = state.items
                librarySections = mapOf("Library" to BlockedContent.filterItems(unfilteredLibraryItems))
                isLibraryLoading = state.isLoading || state.isRefreshing
                libraryError = state.error
            }
        }
        AppState.scope.launch {
            BlockedContent.version.drop(1).collect { applyBlockedContentFilter() }
        }
        AppState.scope.launch {
            libraryViewModel.contentMatchedSongs.collect { contentMatchedSongs = it }
        }
    }

    val contentSearchHasMore = libraryViewModel.contentSearchHasMore
    val presentation = libraryViewModel.presentation

    fun setActive(active: Boolean) {
        libraryViewModel.setActive(active)
        if (!active) {
            playlistObservationJob?.cancel()
            playlistObservationJob = null
        } else if (playlistObservationJob == null) {
            playlistObservationJob =
                AppState.scope.launch {
                    database.dao().allPlaylists().collect { playlists ->
                        playlistSources.updateLocal(playlists)
                        publishUserPlaylists()
                    }
                }
        }
    }

    fun loadMoreContentMatches() = libraryViewModel.loadMoreContentMatches()

    fun setSearchDescending(descending: Boolean) = libraryViewModel.setSearchDescending(descending)

    fun updateLibrarySearchQuery(query: String) {
        libraryViewModel.setSearchQuery(query)
    }

    fun fetchLibraryData(force: Boolean = false) {
        libraryViewModel.refresh(force)
        refreshUserPlaylists()
    }

    fun createPlaylist(
        name: String,
        onResult: (Result<String>) -> Unit,
    ) {
        AppState.scope.launch {
            val local = !GlobalYouTubeRepository.instance.canManagePlaylists()
            val result =
                withTimeoutOrNull(CREATE_PLAYLIST_TIMEOUT) {
                    withContext(Dispatchers.IO) {
                        if (local) {
                            createStoredLocalPlaylist(database, name).map { it.id }
                        } else {
                            GlobalYouTubeRepository.instance.createPlaylist(name)
                        }
                    }
                } ?: Result.failure(IllegalStateException("Creating the playlist timed out"))
            result.onSuccess { playlistId ->
                if (!local) {
                    withContext(Dispatchers.IO) {
                        database.dao().insert(
                            PlaylistEntity(
                                name = name.trim(),
                                browseId = playlistId.removePrefix("VL"),
                                isEditable = true,
                                isAutoSync = true,
                            ),
                        )
                    }
                }
                fetchLibraryData(force = true)
            }
            onResult(result)
        }
    }

    fun renamePlaylist(
        playlist: PlaylistItem,
        name: String,
        onResult: (Result<Unit>) -> Unit,
    ) {
        AppState.scope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    renameStoredPlaylist(database, GlobalYouTubeRepository.instance, playlist.id, name)
                }
            if (result.isSuccess) fetchLibraryData(force = true)
            onResult(result)
        }
    }

    fun deletePlaylist(
        playlist: PlaylistItem,
        onResult: (Result<Unit>) -> Unit,
    ) {
        AppState.scope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    deleteStoredPlaylist(database, GlobalYouTubeRepository.instance, playlist.id)
                }
            if (result.isSuccess) {
                libraryViewModel.onPlaylistDeleted(playlist.id)
                val normalizedId = playlist.id.removePrefix("VL")
                userPlaylists = userPlaylists.filterNot { it.id.removePrefix("VL") == normalizedId }
                fetchLibraryData(force = true)
            }
            onResult(result)
        }
    }

    private fun applyBlockedContentFilter() {
        librarySections = mapOf("Library" to BlockedContent.filterItems(unfilteredLibraryItems))
    }

    private fun refreshUserPlaylists() {
        val generation = ++playlistRefreshGeneration
        val accountKey = currentPlaylistAccountKey()
        playlistRefreshJob?.cancel()
        playlistRefreshJob =
            AppState.scope.launch {
                val remotePlaylists =
                    try {
                        if (GlobalYouTubeRepository.instance.canManagePlaylists()) {
                            val result = GlobalYouTubeRepository.instance.getUserPlaylistsResult()
                            if (!result.complete) {
                                YouTubeSyncState.failed("playlists") { fetchLibraryData(force = true) }
                                return@launch
                            }
                            YouTubeSyncState.succeeded("playlists")
                            result.items.filterIsInstance<PlaylistItem>()
                        } else {
                            emptyList()
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        YtLogger.e("LibrarySync", "Playlist picker failed type=${error::class.simpleName}")
                        YouTubeSyncState.failed("playlists") { fetchLibraryData(force = true) }
                        return@launch
                    }
                if (generation != playlistRefreshGeneration || accountKey != currentPlaylistAccountKey()) return@launch
                playlistSources.updateRemote(remotePlaylists, accountKey)
                publishUserPlaylists()
            }
    }

    private fun publishUserPlaylists() {
        val (localPlaylists, cachedRemotePlaylists) = playlistSources.snapshot(currentPlaylistAccountKey())
        val remotePlaylists =
            cachedRemotePlaylists.filterNot { LibraryDeletionTracker.isDeleted("playlist:${it.id.removePrefix("VL")}") }
        val playlists = mutableListOf<UserPlaylist>()
        val likedPlaylist = remotePlaylists.firstOrNull { it.isLikedMusicPlaylist() }
        likedPlaylist?.let { playlists += it.toUserPlaylist(isAutoPlaylist = true) }
        remotePlaylists.filterNot { it.isLikedMusicPlaylist() }.forEach { playlist ->
            if (playlists.none { it.id.removePrefix("VL") == playlist.id.removePrefix("VL") }) {
                playlists += playlist.toUserPlaylist(isAutoPlaylist = false)
            }
        }
        localPlaylists
            .filter { it.id != "LM" && it.browseId != "LM" }
            .forEach { playlist ->
                val id = playlist.browseId ?: playlist.id
                if (playlists.none { it.id.removePrefix("VL") == id.removePrefix("VL") }) {
                    playlists +=
                        UserPlaylist(
                            id = playlist.browseId?.let { if (it.startsWith("VL")) it else "VL$it" } ?: playlist.id,
                            name = playlist.name,
                            thumbnailUrl = playlist.customThumbnailUrl ?: playlist.thumbnailUrl,
                            songCount = playlist.remoteSongCount,
                            isAutoPlaylist = playlist.remoteSongCount == null,
                        )
                }
            }
        userPlaylists = playlists
    }

    private fun currentPlaylistAccountKey(): String =
        Settings.shared.dataSyncId.ifBlank {
            Settings.shared.accountEmail.ifBlank { "signed-out" }
        } + "|${Settings.shared.innerTubeAuthUser}"

    private fun PlaylistItem.isLikedMusicPlaylist(): Boolean =
        id.equals("LM", ignoreCase = true) ||
            id.equals("VLLM", ignoreCase = true) ||
            id.startsWith("LM:")

    private fun PlaylistItem.toUserPlaylist(isAutoPlaylist: Boolean): UserPlaylist =
        UserPlaylist(id, title, thumbnail, null, author, isAutoPlaylist)
}
