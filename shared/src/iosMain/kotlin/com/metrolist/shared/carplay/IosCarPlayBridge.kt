package com.metrolist.shared.carplay

import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.db.entities.Playlist
import com.metrolist.shared.db.entities.toSongItem
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.liked_songs
import com.metrolist.shared.generated.resources.now_playing
import com.metrolist.shared.generated.resources.playlists
import com.metrolist.shared.generated.resources.songs
import com.metrolist.shared.model.RepeatMode
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.ui.playback.PlaybackController
import com.metrolist.shared.ui.playback.PlaybackUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.getString

data class CarPlayStrings(
    val likedSongs: String,
    val playlists: String,
    val songs: String,
    val nowPlaying: String,
)

data class CarPlayBrowseItem(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val isPlayable: Boolean = false,
    val isContainer: Boolean = false,
)

data class CarPlayNowPlayingState(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val isPlaying: Boolean = false,
    val canSkipNext: Boolean = false,
    val canSkipPrevious: Boolean = false,
    val isShuffled: Boolean = false,
    val repeatMode: String = RepeatMode.OFF.name,
)

object IosCarPlayBridge {
    private val lock = Any()
    private var controller: PlaybackController? = null
    private var database: MusicDatabase? = null
    private var scope: CoroutineScope? = null
    private var strings: CarPlayStrings? = null
    private val stateListeners = linkedSetOf<(CarPlayNowPlayingState) -> Unit>()
    private var stateJob: Job? = null

    fun register(
        controller: PlaybackController,
        database: MusicDatabase,
        parentScope: CoroutineScope,
    ) {
        val bridgeScope = CoroutineScope(SupervisorJob(parentScope.coroutineContext[Job]) + Dispatchers.Main.immediate)
        synchronized(lock) {
            unregisterLocked()
            this.controller = controller
            this.database = database
            this.scope = bridgeScope
            stateJob =
                bridgeScope.launch {
                    controller.state.collect { state ->
                        val snapshot = state.toCarPlayState()
                        val listeners = synchronized(lock) { stateListeners.toList() }
                        listeners.forEach { it(snapshot) }
                    }
                }
        }
        bridgeScope.launch {
            val loaded =
                CarPlayStrings(
                    likedSongs = getString(Res.string.liked_songs),
                    playlists = getString(Res.string.playlists),
                    songs = getString(Res.string.songs),
                    nowPlaying = getString(Res.string.now_playing),
                )
            synchronized(lock) { strings = loaded }
        }
    }

    fun unregister() {
        synchronized(lock) { unregisterLocked() }
    }

    private fun unregisterLocked() {
        stateJob?.cancel()
        stateJob = null
        scope?.cancel()
        scope = null
        controller = null
        database = null
        strings = null
        stateListeners.clear()
    }

    fun currentStrings(): CarPlayStrings? = synchronized(lock) { strings }

    fun loadStrings(completion: (CarPlayStrings) -> Unit) {
        val cached = currentStrings()
        if (cached != null) {
            completion(cached)
            return
        }
        val activeScope = synchronized(lock) { scope } ?: return
        activeScope.launch {
            val loaded =
                CarPlayStrings(
                    likedSongs = getString(Res.string.liked_songs),
                    playlists = getString(Res.string.playlists),
                    songs = getString(Res.string.songs),
                    nowPlaying = getString(Res.string.now_playing),
                )
            synchronized(lock) { strings = loaded }
            completion(loaded)
        }
    }

    fun addStateListener(listener: (CarPlayNowPlayingState) -> Unit): () -> Unit {
        val current =
            synchronized(lock) {
                stateListeners += listener
                controller?.state?.value?.toCarPlayState()
            }
        if (current != null) listener(current)
        return {
            synchronized(lock) { stateListeners -= listener }
        }
    }

    fun currentState(): CarPlayNowPlayingState = synchronized(lock) { controller?.state?.value?.toCarPlayState() } ?: CarPlayNowPlayingState()

    fun loadRootItems(completion: (List<CarPlayBrowseItem>) -> Unit) {
        loadStrings { s ->
            completion(
                listOf(
                    CarPlayBrowseItem(id = LIKED_SONGS_ID, title = s.likedSongs, isContainer = true),
                    CarPlayBrowseItem(id = PLAYLISTS_ID, title = s.playlists, isContainer = true),
                ),
            )
        }
    }

    fun loadChildren(
        parentId: String,
        completion: (List<CarPlayBrowseItem>) -> Unit,
    ) {
        val activeScope = synchronized(lock) { scope }
        val db = synchronized(lock) { database }
        if (activeScope == null || db == null) {
            completion(emptyList())
            return
        }
        activeScope.launch {
            val items =
                when (parentId) {
                    LIKED_SONGS_ID -> loadLikedSongs(db)
                    PLAYLISTS_ID -> loadPlaylists(db)
                    else -> loadPlaylistSongs(db, parentId)
                }
            completion(items)
        }
    }

    fun playSongFromContainer(
        containerId: String,
        songId: String,
    ) {
        val activeScope = synchronized(lock) { scope }
        val db = synchronized(lock) { database }
        val playback = synchronized(lock) { controller }
        if (activeScope == null || db == null || playback == null) return
        activeScope.launch {
            val songs =
                if (containerId == LIKED_SONGS_ID) {
                    loadLikedSongItems(db)
                } else {
                    loadPlaylistSongItems(db, containerId)
                }
            val index = songs.indexOfFirst { it.id == songId }.takeIf { it >= 0 } ?: 0
            if (songs.isNotEmpty()) playback.playQueue(songs, index)
        }
    }

    fun togglePlayPause() {
        synchronized(lock) { controller }?.togglePlayPause()
    }

    fun skipToNext() {
        synchronized(lock) { controller }?.skipToNext()
    }

    fun skipToPrevious() {
        synchronized(lock) { controller }?.skipToPrevious()
    }

    private suspend fun loadLikedSongs(db: MusicDatabase): List<CarPlayBrowseItem> =
        withContext(Dispatchers.Default) {
            db.dao().likedSongsByCreateDateAsc().first().map { song ->
                val item = song.toSongItem()
                CarPlayBrowseItem(
                    id = "$LIKED_SONGS_ID|${item.id}",
                    title = item.title,
                    subtitle = item.artists.joinToString { it.name },
                    isPlayable = true,
                )
            }
        }

    private suspend fun loadLikedSongItems(db: MusicDatabase): List<SongItem> =
        withContext(Dispatchers.Default) {
            db.dao().likedSongsByCreateDateAsc().first().map { it.toSongItem() }
        }

    private suspend fun loadPlaylists(db: MusicDatabase): List<CarPlayBrowseItem> =
        withContext(Dispatchers.Default) {
            db.dao().playlistsByCreateDateAsc().first().map { it.toBrowseItem() }
        }

    private suspend fun loadPlaylistSongs(
        db: MusicDatabase,
        playlistId: String,
    ): List<CarPlayBrowseItem> =
        withContext(Dispatchers.Default) {
            db.dao().playlistSongs(playlistId).first().map { ps ->
                val item = ps.toSongItem()
                CarPlayBrowseItem(
                    id = "$playlistId|${item.id}",
                    title = item.title,
                    subtitle = item.artists.joinToString { it.name },
                    isPlayable = true,
                )
            }
        }

    private suspend fun loadPlaylistSongItems(
        db: MusicDatabase,
        playlistId: String,
    ): List<SongItem> =
        withContext(Dispatchers.Default) {
            db.dao().playlistSongs(playlistId).first().map { it.toSongItem() }
        }

    private fun Playlist.toBrowseItem(): CarPlayBrowseItem =
        CarPlayBrowseItem(
            id = playlist.id,
            title = playlist.name,
            subtitle = songCount.takeIf { it > 0 }?.toString(),
            isContainer = true,
        )

    private fun PlaybackUiState.toCarPlayState(): CarPlayNowPlayingState =
        CarPlayNowPlayingState(
            title = currentSong?.title,
            artist = currentSong?.artists?.joinToString { it.name },
            album = currentSong?.album?.name,
            isPlaying = isPlaying,
            canSkipNext = canSkipNext(),
            canSkipPrevious = canSkipPrevious,
            isShuffled = isShuffled,
            repeatMode = repeatMode.name,
        )

    private const val LIKED_SONGS_ID = "liked_songs"
    private const val PLAYLISTS_ID = "playlists"
}
