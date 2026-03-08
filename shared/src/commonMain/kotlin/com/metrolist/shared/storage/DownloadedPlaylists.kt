package com.metrolist.shared.storage

import androidx.compose.runtime.snapshotFlow
import com.metrolist.shared.api.YouTubeRepository
import com.metrolist.shared.api.playlistSongs
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.db.entities.PlaylistEntity
import com.metrolist.shared.db.entities.toSongItem
import com.metrolist.shared.db.loadAndCachePlaylistPage
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playlist.PlaylistChanges
import com.metrolist.shared.settings.BlockedContent
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.state.GlobalYouTubeRepository
import com.metrolist.shared.state.SongLikeState
import com.metrolist.shared.utils.Logger
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

fun trackedDownloadPlaylistId(id: String): String? =
    when (val normalized = id.removePrefix("VL")) {
        "auto:liked", PlaylistEntity.LIKED_PLAYLIST_ID, "LM" -> "LM"
        PlaylistEntity.DOWNLOADED_PLAYLIST_ID, PlaylistEntity.WEEKLY_MOST_PLAYLIST_ID, PlaylistEntity.MONTHLY_MOST_PLAYLIST_ID -> null
        else -> normalized.takeIf { it.startsWith("PL") || it.startsWith("LP") }
    }

object DownloadedPlaylists {
    private val lock = SynchronizedObject()

    fun ids(): Set<String> =
        synchronized(lock) {
            runCatching { Json.decodeFromString<Set<String>>(Settings.shared.keepDownloadedPlaylistsJson) }
                .getOrDefault(emptySet())
                .mapNotNullTo(mutableSetOf(), ::trackedDownloadPlaylistId)
        }

    fun contains(id: String): Boolean = trackedDownloadPlaylistId(id) in ids()

    fun track(id: String) {
        val normalized = trackedDownloadPlaylistId(id) ?: return
        synchronized(lock) { Settings.shared.keepDownloadedPlaylistsJson = Json.encodeToString(ids() + normalized) }
    }

    fun stop(id: String) {
        val normalized = trackedDownloadPlaylistId(id) ?: return
        synchronized(lock) { Settings.shared.keepDownloadedPlaylistsJson = Json.encodeToString(ids() - normalized) }
    }

    suspend fun songs(
        id: String,
        database: MusicDatabase,
        repository: YouTubeRepository = GlobalYouTubeRepository.instance,
    ): List<SongItem> {
        val normalized = trackedDownloadPlaylistId(id) ?: return emptyList()
        if (normalized == "LM") return SongLikeState.likedSongs
        val stored = database.dao().findPlaylistEntity(normalized)
        if (stored == null && normalized.startsWith("LP")) return emptyList()
        if (stored != null && (stored.isLocal || stored.browseId == null)) {
            return database
                .dao()
                .playlistSongs(stored.id)
                .first()
                .map { it.toSongItem() }
        }
        val page = database.loadAndCachePlaylistPage(repository, stored?.id ?: normalized)
        check(!page.isOffline) { "Playlist is only available offline" }
        return BlockedContent.filterItemSections(page.sections).playlistSongs()
    }

    fun observe(
        scope: CoroutineScope,
        database: MusicDatabase,
        enqueue: suspend (String, List<SongItem>) -> Unit,
    ) {
        scope.launch {
            observeDownloadedPlaylists(snapshotFlow { ids() }, { membership(database, it) }, enqueue)
        }
        scope.launch {
            PlaylistChanges.flow.collect { id ->
                if (contains(id)) reconcile(id, database, enqueue)
            }
        }
    }

    suspend fun sync(
        database: MusicDatabase,
        enqueue: suspend (String, List<SongItem>) -> Unit,
    ): Boolean {
        var success = true
        ids().forEach { if (!reconcile(it, database, enqueue)) success = false }
        return success
    }

    private suspend fun reconcile(
        id: String,
        database: MusicDatabase,
        enqueue: suspend (String, List<SongItem>) -> Unit,
    ): Boolean =
        try {
            val refreshed = trackedDownloadPlaylistId(id) != "LM" || SongLikeState.refreshLikesFromRemote(force = true)
            val songs = songs(id, database)
            if (contains(id)) enqueue(id, songs)
            refreshed
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Logger.w("DownloadedPlaylists", "Could not reconcile downloaded playlist", error)
            false
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    internal fun membership(
        database: MusicDatabase,
        id: String,
    ): Flow<List<SongItem>> {
        if (id == "LM") return SongLikeState.likedSongsFlow
        val dao = database.dao()
        return combine(dao.playlist(id), dao.playlistByBrowseId(id)) { local, remote -> (local ?: remote)?.id }
            .distinctUntilChanged()
            .flatMapLatest { storedId ->
                if (storedId == null) {
                    flowOf(emptyList())
                } else {
                    dao
                        .playlistDownloadMembers(storedId)
                        .map { rows -> rows.filterNot { it.isLocal }.map { it.songId }.distinct() }
                        .distinctUntilChanged()
                        .map { ids ->
                            val songs = dao.getSongsByIds(ids).associateBy { it.id }
                            ids.mapNotNull { songs[it]?.toSongItem() }
                        }
                }
            }
    }
}

internal suspend fun observeDownloadedPlaylists(
    ids: Flow<Set<String>>,
    membership: (String) -> Flow<List<SongItem>>,
    enqueue: suspend (String, List<SongItem>) -> Unit,
) {
    ids.collectLatest { tracked ->
        coroutineScope {
            tracked.forEach { id ->
                launch {
                    membership(id)
                        .map { songs -> songs.filterNot(SongItem::isLocal).distinctBy(SongItem::id) }
                        .distinctUntilChanged { old, new -> old.map(SongItem::id) == new.map(SongItem::id) }
                        .collect { songs ->
                            try {
                                enqueue(id, songs)
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: Exception) {
                                Logger.w("DownloadedPlaylists", "Could not enqueue playlist additions", error)
                            }
                        }
                }
            }
        }
    }
}
