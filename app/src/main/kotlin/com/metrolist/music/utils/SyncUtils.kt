/**
 * Metrolist Project (C) 2026
 * OuterTune Project Copyright (C) 2025
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import android.content.Context
import android.widget.Toast
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.ArtistItem
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.innertube.models.PodcastItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.utils.completed
import com.metrolist.lastfm.LastFM
import com.metrolist.music.R
import com.metrolist.music.constants.InnerTubeCookieKey
import com.metrolist.music.constants.LastFMUseSendLikes
import com.metrolist.music.constants.LastFullSyncKey
import com.metrolist.music.constants.SYNC_COOLDOWN
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.ArtistEntity
import com.metrolist.music.db.entities.PlaylistEntity
import com.metrolist.music.db.entities.PlaylistSongMap
import com.metrolist.music.db.entities.PodcastEntity
import com.metrolist.music.db.entities.SetVideoIdEntity
import com.metrolist.music.db.entities.SongEntity
import com.metrolist.music.extensions.collectLatest
import com.metrolist.music.extensions.isInternetConnected
import com.metrolist.music.extensions.isSyncEnabled
import com.metrolist.music.models.toMediaMetadata
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.time.LocalDateTime
import java.time.ZoneOffset
import javax.inject.Inject
import javax.inject.Singleton
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

sealed class SyncOperation {
    data object FullSync : SyncOperation()
    data object LikedSongs : SyncOperation()
    data object LibrarySongs : SyncOperation()
    data object UploadedSongs : SyncOperation()
    data object LikedAlbums : SyncOperation()
    data object UploadedAlbums : SyncOperation()
    data object ArtistsSubscriptions : SyncOperation()
    data object PodcastSubscriptions : SyncOperation()
    data object EpisodesForLater : SyncOperation()
    data object SavedPlaylists : SyncOperation()
    data object AutoSyncPlaylists : SyncOperation()
    data class SinglePlaylist(val browseId: String, val playlistId: String) : SyncOperation()
    data class LikeSong(val song: SongEntity) : SyncOperation()
    data class SubscribeChannel(val channelId: String, val subscribe: Boolean) : SyncOperation()
    data class SavePodcast(val podcastId: String, val save: Boolean) : SyncOperation()
    data class SaveEpisode(val episodeId: String, val save: Boolean, val setVideoId: String?) : SyncOperation()
    data object ClearPodcastData : SyncOperation()
}

@Singleton
class SyncUtils @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: MusicDatabase,
) {
    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        if (throwable !is CancellationException) {
            Timber.e(throwable, "Sync coroutine exception")
        }
    }

    private val syncJob = SupervisorJob()
    private val syncScope = CoroutineScope(Dispatchers.IO + syncJob + exceptionHandler)

    private val syncChannel = Channel<SyncOperation>(Channel.BUFFERED)
    private val syncExecutionMutex = Mutex()
    private val queuedOperationKeys = ConcurrentHashMap.newKeySet<String>()

    private var lastfmSendLikes = false
    @Volatile private var cachedLastSyncEpoch: Long = 0L
    private val playlistsBeingModified = ConcurrentHashMap<String, AtomicInteger>()
    private val playlistEditMutex = Mutex()
    private var lastPlaylistEditAtMs = 0L
    // Guards against creating the same playlist on YouTube twice: the dialog asks for its own
    // upload while a sync may be walking the pending ones at the same moment.
    private val playlistsBeingCreated = ConcurrentHashMap.newKeySet<String>()

    companion object {
        private const val MAX_RETRIES = 3
        private const val INITIAL_RETRY_DELAY_MS = 1000L
        private const val DB_OPERATION_DELAY_MS = 50L
        private const val DB_QUERY_BATCH_SIZE = 500
        private const val PLAYLIST_EDIT_THROTTLE_MS = 500L
    }
    private fun markPlaylistModifying(playlistId: String) {
        playlistsBeingModified.getOrPut(playlistId) { AtomicInteger(0) }.incrementAndGet()
    }

    private fun unmarkPlaylistModifying(playlistId: String) {
        playlistsBeingModified[playlistId]?.let { counter ->
            if (counter.decrementAndGet() <= 0) playlistsBeingModified.remove(playlistId)
        }
    }

    private fun isPlaylistBeingModified(playlistId: String): Boolean =
        (playlistsBeingModified[playlistId]?.get() ?: 0) > 0

    private fun findSongIdsWithoutArtists(songIds: Collection<String>): Set<String> =
        songIds
            .chunked(DB_QUERY_BATCH_SIZE)
            .flatMap(database::songIdsWithoutArtists)
            .toSet()

    private suspend fun <T> runQueuedPlaylistEdit(block: suspend () -> T): T =
        playlistEditMutex.withLock {
            val remainingDelay = PLAYLIST_EDIT_THROTTLE_MS -
                (System.currentTimeMillis() - lastPlaylistEditAtMs)
            if (remainingDelay > 0) delay(remainingDelay)
            try {
                block()
            } finally {
                lastPlaylistEditAtMs = System.currentTimeMillis()
            }
        }

    init {
        context.dataStore.data
            .map { it[LastFMUseSendLikes] ?: false }
            .distinctUntilChanged()
            .collectLatest(syncScope) {
                lastfmSendLikes = it
            }

        syncScope.launch {
            val loaded = context.dataStore.get(LastFullSyncKey, 0L)
            cachedLastSyncEpoch = maxOf(cachedLastSyncEpoch, loaded)
        }

        startProcessingQueue()
    }

    private fun startProcessingQueue() {
        syncScope.launch {
            for (operation in syncChannel) {
                try {
                    if (operation.isCoveredByFullSync() && "full" in queuedOperationKeys) {
                        Timber.d("Skipping $operation because a full sync is queued or running")
                    } else {
                        syncExecutionMutex.withLock {
                            processOperation(operation)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.e(e, "Error processing sync operation: $operation")
                } finally {
                    operation.coalescingKey()?.let(queuedOperationKeys::remove)
                }
            }
        }
    }

    private fun enqueue(operation: SyncOperation) {
        val key = operation.coalescingKey()
        if (key != null && !queuedOperationKeys.add(key)) {
            Timber.d("Skipping duplicate sync operation: $operation")
            return
        }
        syncScope.launch {
            try {
                syncChannel.send(operation)
            } catch (e: Exception) {
                key?.let(queuedOperationKeys::remove)
                throw e
            }
        }
    }

    private fun SyncOperation.coalescingKey(): String? = when (this) {
        SyncOperation.FullSync -> "full"
        SyncOperation.LikedSongs -> "likedSongs"
        SyncOperation.LibrarySongs -> "librarySongs"
        SyncOperation.UploadedSongs -> "uploadedSongs"
        SyncOperation.LikedAlbums -> "likedAlbums"
        SyncOperation.UploadedAlbums -> "uploadedAlbums"
        SyncOperation.ArtistsSubscriptions -> "artistsSubscriptions"
        SyncOperation.PodcastSubscriptions -> "podcastSubscriptions"
        SyncOperation.EpisodesForLater -> "episodesForLater"
        SyncOperation.SavedPlaylists -> "savedPlaylists"
        SyncOperation.AutoSyncPlaylists -> "autoSyncPlaylists"
        is SyncOperation.SinglePlaylist -> "playlist:$browseId"
        SyncOperation.ClearPodcastData -> "clearPodcastData"
        is SyncOperation.LikeSong,
        is SyncOperation.SubscribeChannel,
        is SyncOperation.SavePodcast,
        is SyncOperation.SaveEpisode,
        -> null
    }

    private fun SyncOperation.isCoveredByFullSync(): Boolean = when (this) {
        SyncOperation.LikedSongs,
        SyncOperation.LibrarySongs,
        SyncOperation.UploadedSongs,
        SyncOperation.LikedAlbums,
        SyncOperation.UploadedAlbums,
        SyncOperation.ArtistsSubscriptions,
        SyncOperation.PodcastSubscriptions,
        SyncOperation.EpisodesForLater,
        SyncOperation.SavedPlaylists,
        SyncOperation.AutoSyncPlaylists,
        is SyncOperation.SinglePlaylist,
        -> true
        else -> false
    }

    private suspend fun processOperation(operation: SyncOperation) {
        when (operation) {
            is SyncOperation.FullSync -> executeFullSync()
            is SyncOperation.LikedSongs -> executeSyncLikedSongs()
            is SyncOperation.LibrarySongs -> executeSyncLibrarySongs()
            is SyncOperation.UploadedSongs -> executeSyncUploadedSongs()
            is SyncOperation.LikedAlbums -> executeSyncLikedAlbums()
            is SyncOperation.UploadedAlbums -> executeSyncUploadedAlbums()
            is SyncOperation.ArtistsSubscriptions -> executeSyncArtistsSubscriptions()
            is SyncOperation.PodcastSubscriptions -> executeSyncPodcastSubscriptions()
            is SyncOperation.EpisodesForLater -> executeSyncEpisodesForLater()
            is SyncOperation.SavedPlaylists -> executeSyncSavedPlaylists()
            is SyncOperation.AutoSyncPlaylists -> executeSyncAutoSyncPlaylists()
            is SyncOperation.SinglePlaylist -> executeSyncPlaylist(operation.browseId, operation.playlistId)
            is SyncOperation.LikeSong -> executeLikeSong(operation.song)
            is SyncOperation.SubscribeChannel -> executeSubscribeChannel(operation.channelId, operation.subscribe)
            is SyncOperation.SavePodcast -> executeSavePodcast(operation.podcastId, operation.save)
            is SyncOperation.SaveEpisode -> executeSaveEpisode(operation.episodeId, operation.save, operation.setVideoId)
            is SyncOperation.ClearPodcastData -> executeClearPodcastData()
        }
    }

    private suspend fun isLoggedIn(): Boolean {
        return try {
            val cookie = context.dataStore.data
                .map { it[InnerTubeCookieKey] }
                .first()
            isSignedInToYouTube(cookie)
        } catch (e: Exception) {
            Timber.e(e, "Error checking login status")
            false
        }
    }

    private suspend fun <T> withRetry(
        maxRetries: Int = MAX_RETRIES,
        initialDelay: Long = INITIAL_RETRY_DELAY_MS,
        block: suspend () -> T
    ): Result<T> {
        var currentDelay = initialDelay
        repeat(maxRetries) { attempt ->
            try {
                return Result.success(block())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Attempt ${attempt + 1}/$maxRetries failed")
                if (attempt == maxRetries - 1) {
                    return Result.failure(e)
                }
                delay(currentDelay)
                currentDelay *= 2
            }
        }
        return Result.failure(Exception("Max retries exceeded"))
    }

    // Public API methods - Queue operations

    fun performFullSync() {
        enqueue(SyncOperation.FullSync)
    }

    suspend fun performFullSyncSuspend() {
        if (!isLoggedIn()) {
            Timber.w("Skipping full sync - user not logged in")
            return
        }
        syncExecutionMutex.withLock { executeFullSync() }
    }

    fun tryAutoSync() {
        syncScope.launch {
            if (!isLoggedIn()) {
                Timber.d("Skipping auto sync - user not logged in")
                return@launch
            }

            if (!context.isSyncEnabled() || !context.isInternetConnected()) {
                return@launch
            }

            val lastSync = context.dataStore.get(LastFullSyncKey, 0L)
            val effectiveLastSync = maxOf(lastSync, cachedLastSyncEpoch)
            val currentTime = LocalDateTime.now().toEpochSecond(ZoneOffset.UTC)
            if (effectiveLastSync > 0 && (currentTime - effectiveLastSync) < SYNC_COOLDOWN) {
                return@launch
            }

            enqueue(SyncOperation.FullSync)

            val now = LocalDateTime.now().toEpochSecond(ZoneOffset.UTC)
            cachedLastSyncEpoch = now
            context.safeDataStoreEdit { settings ->
                settings[LastFullSyncKey] = now
            }
        }
    }

    fun likeSong(s: SongEntity) {
        enqueue(SyncOperation.LikeSong(s))
    }

    fun subscribeChannel(channelId: String, subscribe: Boolean) {
        enqueue(SyncOperation.SubscribeChannel(channelId, subscribe))
    }

    fun savePodcast(podcastId: String, save: Boolean) {
        Timber.d("[PODCAST_TOGGLE] SyncUtils.savePodcast called: podcastId=$podcastId, save=$save")
        enqueue(SyncOperation.SavePodcast(podcastId, save))
    }

    fun saveEpisode(episodeId: String, save: Boolean, setVideoId: String? = null) {
        enqueue(SyncOperation.SaveEpisode(episodeId, save, setVideoId))
    }

    fun syncLikedSongs() {
        enqueue(SyncOperation.LikedSongs)
    }

    fun syncLibrarySongs() {
        enqueue(SyncOperation.LibrarySongs)
    }

    fun syncUploadedSongs() {
        enqueue(SyncOperation.UploadedSongs)
    }

    fun syncLikedAlbums() {
        enqueue(SyncOperation.LikedAlbums)
    }

    fun syncUploadedAlbums() {
        enqueue(SyncOperation.UploadedAlbums)
    }

    fun syncArtistsSubscriptions() {
        enqueue(SyncOperation.ArtistsSubscriptions)
    }

    fun syncSavedPlaylists() {
        enqueue(SyncOperation.SavedPlaylists)
    }

    fun syncAutoSyncPlaylists() {
        enqueue(SyncOperation.AutoSyncPlaylists)
    }

    fun syncPodcastSubscriptions() {
        enqueue(SyncOperation.PodcastSubscriptions)
    }

    fun syncEpisodesForLater() {
        enqueue(SyncOperation.EpisodesForLater)
    }

    fun clearPodcastData() {
        enqueue(SyncOperation.ClearPodcastData)
    }

    // Suspend versions for direct calls

    suspend fun syncLikedSongsSuspend() = syncExecutionMutex.withLock { executeSyncLikedSongs() }
    suspend fun syncUploadedSongsSuspend() = syncExecutionMutex.withLock { executeSyncUploadedSongs() }
    suspend fun syncPodcastSubscriptionsSuspend() = syncExecutionMutex.withLock { executeSyncPodcastSubscriptions() }
    suspend fun syncEpisodesForLaterSuspend() = syncExecutionMutex.withLock { executeSyncEpisodesForLater() }

    suspend fun clearAllLibraryData() = syncExecutionMutex.withLock {
        executeClearAllLibraryData()
    }

    private suspend fun executeClearAllLibraryData() = withContext(Dispatchers.IO) {
        Timber.d("[LOGOUT_CLEAR] Starting complete library data cleanup")
        try {

            // Clear podcast data first (subscribed podcasts + saved episodes)
            Timber.d("[LOGOUT_CLEAR] Clearing podcast data")
            executeClearPodcastData()

            // Clear history
            Timber.d("[LOGOUT_CLEAR] Clearing listen history and search history")
            database.clearListenHistory()
            database.clearSearchHistory()

            // Clear all tables using Room's transaction layer to ensure proper
            // InvalidationTracker notifications and avoid direct SQLite access
            // that could bypass Room's connection management.
            database.withTransaction {
                // Get all user tables from the database (auto-detect)
                val allTables = getAllUserTables()
                Timber.d("[LOGOUT_CLEAR] Found ${allTables.size} tables: $allTables")

                // Tables to skip (system tables and tables we handle specially)
                val skipTables = setOf(
                    "android_metadata",
                    "room_master_table",
                    "sqlite_sequence",
                    "search_history",  // Already cleared above
                    "listen_history"   // Already cleared above
                )

                // Tables with foreign key references - delete these first (mapping tables)
                val mappingTables = listOf(
                    "playlist_song_map",
                    "song_album_map",
                    "album_artist_map",
                    "related_song_map"
                )

                // Downloaded songs need their normalized artist rows to remain usable offline.
                val retainedDownloadTables = setOf("song", "artist", "song_artist_map")

                // Delete mapping tables first
                Timber.d("[LOGOUT_CLEAR] Deleting mapping tables")
                for (table in mappingTables) {
                    if (table in allTables) {
                        safeDeleteTable(table)
                    }
                }

                // Delete all other tables except song (handled specially to keep downloads)
                Timber.d("[LOGOUT_CLEAR] Deleting remaining tables")
                for (table in allTables) {
                    if (table in skipTables || table in mappingTables || table in retainedDownloadTables) {
                        continue
                    }
                    safeDeleteTable(table)
                }

                // Finally, delete songs but keep downloaded ones
                if ("song" in allTables) {
                    Timber.d("[LOGOUT_CLEAR] Deleting songs (keeping downloaded)")
                    database.deleteSongsNotDownloaded()
                }
                if ("artist" in allTables) {
                    database.clearArtistBookmarks()
                    database.deleteOrphanArtists()
                }
            }


            Timber.d("[LOGOUT_CLEAR] All library data cleared successfully")
        } catch (e: Exception) {
            Timber.e(e, "[LOGOUT_CLEAR] Error clearing library data")
            throw e
        }
    }

    private fun getAllUserTables(): List<String> {
        val tables = mutableListOf<String>()
        try {
            database.openHelper.writableDatabase.query(
                "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'"
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    tables.add(cursor.getString(0))
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "[LOGOUT_CLEAR] Error getting table list")
        }
        return tables
    }

    private fun safeDeleteTable(tableName: String) {
        try {
            database.raw(androidx.sqlite.db.SimpleSQLiteQuery("DELETE FROM $tableName"))
            Timber.d("[LOGOUT_CLEAR] Cleared table: $tableName")
        } catch (e: Exception) {
            Timber.w("[LOGOUT_CLEAR] Table $tableName error: ${e.message}")
        }
    }

    // Private execution methods

    private suspend fun executeFullSync() = withContext(Dispatchers.IO) {
        if (!isLoggedIn()) {
            Timber.w("Skipping full sync - user not logged in")
            return@withContext
        }


        try {
            // Sync in sequence to avoid overwhelming the API and database
            executeSyncLikedSongs()
            delay(DB_OPERATION_DELAY_MS)

            executeSyncLibrarySongs()
            delay(DB_OPERATION_DELAY_MS)

            executeSyncUploadedSongs()
            delay(DB_OPERATION_DELAY_MS)

            executeSyncLikedAlbums()
            delay(DB_OPERATION_DELAY_MS)

            executeSyncUploadedAlbums()
            delay(DB_OPERATION_DELAY_MS)

            executeSyncArtistsSubscriptions()
            delay(DB_OPERATION_DELAY_MS)

            executeSyncPodcastSubscriptions()
            delay(DB_OPERATION_DELAY_MS)

            executeSyncEpisodesForLater()
            delay(DB_OPERATION_DELAY_MS)

            executeSyncSavedPlaylists()
            delay(DB_OPERATION_DELAY_MS)

            Timber.d("Full sync completed successfully")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Error during full sync")
        }
    }

    private suspend fun executeLikeSong(s: SongEntity) = withContext(Dispatchers.IO) {
        if (!isLoggedIn()) {
            Timber.w("Skipping likeSong - user not logged in")
            return@withContext
        }

        withRetry {
            YouTube.likeVideo(s.id, s.liked)
        }.onFailure { e ->
            Timber.e(e, "Failed to like song on YouTube: ${s.id}")
        }

        if (lastfmSendLikes) {
            try {
                val dbSong = database.song(s.id).firstOrNull()
                LastFM.setLoveStatus(
                    artist = dbSong?.artists?.joinToString { a -> a.name } ?: "",
                    track = s.title,
                    love = s.liked
                )
            } catch (e: Exception) {
                Timber.e(e, "Failed to update LastFM love status")
            }
        }
    }

    private suspend fun executeSubscribeChannel(channelId: String, subscribe: Boolean) = withContext(Dispatchers.IO) {
        Timber.d("[CHANNEL_TOGGLE] executeSubscribeChannel called: channelId=$channelId, subscribe=$subscribe")
        if (!isLoggedIn()) {
            Timber.d("[CHANNEL_TOGGLE] Skipping subscribeChannel - user not logged in")
            return@withContext
        }

        Timber.d("[CHANNEL_TOGGLE] User is logged in, calling YouTube.subscribeChannel")
        withRetry {
            YouTube.subscribeChannel(channelId, subscribe)
        }.onSuccess {
            Timber.d("[CHANNEL_TOGGLE] Successfully subscribed/unsubscribed channel: $channelId")
            PodcastRefreshTrigger.triggerRefresh()
        }.onFailure { e ->
            Timber.e(e, "[CHANNEL_TOGGLE] Failed to subscribe/unsubscribe channel: $channelId")
        }
    }

    private suspend fun executeSavePodcast(podcastId: String, save: Boolean) = withContext(Dispatchers.IO) {
        Timber.d("[PODCAST_TOGGLE] executeSavePodcast called: podcastId=$podcastId, save=$save")
        if (!isLoggedIn()) {
            Timber.d("[PODCAST_TOGGLE] Skipping savePodcast - user not logged in")
            return@withContext
        }

        Timber.d("[PODCAST_TOGGLE] User is logged in, calling YouTube.savePodcast")
        withRetry {
            YouTube.savePodcast(podcastId, save)
        }.onSuccess {
            Timber.d("[PODCAST_TOGGLE] Successfully saved/unsaved podcast: $podcastId")
        }.onFailure { e ->
            Timber.e(e, "[PODCAST_TOGGLE] Failed to save/unsave podcast: $podcastId")
        }
    }

    private suspend fun executeSaveEpisode(episodeId: String, save: Boolean, setVideoId: String?) = withContext(Dispatchers.IO) {
        if (!isLoggedIn()) {
            Timber.d("Skipping saveEpisode - user not logged in")
            return@withContext
        }

        if (save) {
            withRetry {
                YouTube.addEpisodeToSavedEpisodes(episodeId)
            }.onFailure { e ->
                Timber.e(e, "Failed to save episode: $episodeId")
            }
        } else {
            if (setVideoId != null) {
                withRetry {
                    YouTube.removeEpisodeFromSavedEpisodes(episodeId, setVideoId)
                }.onFailure { e ->
                    Timber.e(e, "Failed to remove episode: $episodeId")
                }
            }
        }
    }

    private suspend fun executeSyncLikedSongs() = withContext(Dispatchers.IO) {
        if (!isLoggedIn()) {
            Timber.w("Skipping syncLikedSongs - user not logged in")
            return@withContext
        }


        withRetry {
            YouTube.playlist("LM").completed()
        }.onSuccess { result ->
            result.onSuccess { page ->
                try {
                    val remoteSongs = page.songs
                    val remoteIds = remoteSongs.map { it.id }.toSet()
                    val localSongs = database.likedSongEntitiesByNameAsc()
                    val advertisedCount = page.playlist.songCountText?.filter { it.isDigit() }?.toIntOrNull()
                    check(advertisedCount == null || remoteSongs.size >= advertisedCount) {
                        "Liked-song response was incomplete (${remoteSongs.size}/$advertisedCount)"
                    }
                    val songIdsWithoutArtists = findSongIdsWithoutArtists(remoteIds)
                    val now = LocalDateTime.now()

                    database.withTransaction {
                        localSongs.filterNot { it.id in remoteIds }.forEach { song ->
                            update(song.localToggleLike())
                        }

                        remoteSongs.forEachIndexed { index, song ->
                            val dbSong = songEntity(song.id)
                            val timestamp = dbSong?.likedDate ?: now.minusSeconds(index.toLong())
                            val isVideoSong = song.isVideoSong
                            if (dbSong == null) {
                                insert(song.toMediaMetadata()) {
                                    it.copy(liked = true, likedDate = timestamp, isVideo = isVideoSong)
                                }
                            } else {
                                if (song.id in songIdsWithoutArtists) {
                                    insert(song.toMediaMetadata())
                                }
                                if (!dbSong.liked || dbSong.likedDate == null || dbSong.isVideo != isVideoSong) {
                                    update(dbSong.copy(liked = true, likedDate = timestamp, isVideo = isVideoSong))
                                }
                            }
                        }
                    }

                    Timber.d("Synced ${remoteSongs.size} liked songs")
                } catch (e: Exception) {
                    Timber.e(e, "Error processing liked songs")
                }
            }.onFailure { e ->
                Timber.e(e, "Failed to fetch liked songs from YouTube")
            }
        }.onFailure { e ->
            Timber.e(e, "Failed to sync liked songs after retries")
        }
    }

    private suspend fun executeSyncLibrarySongs() = withContext(Dispatchers.IO) {
        if (!isLoggedIn()) {
            Timber.w("Skipping syncLibrarySongs - user not logged in")
            return@withContext
        }


        withRetry {
            YouTube.library("FEmusic_liked_videos").completed()
        }.onSuccess { result ->
            result.onSuccess { page ->
                try {
                    val remoteSongs = page.items.filterIsInstance<SongItem>().reversed()
                    val remoteIds = remoteSongs.map { it.id }.toSet()
                    val localSongs = database.librarySongEntitiesByNameAsc()
                    val songIdsWithoutArtists = findSongIdsWithoutArtists(remoteIds)
                    val now = LocalDateTime.now()

                    database.withTransaction {
                        localSongs.filterNot { it.id in remoteIds }.forEach { song ->
                            update(song.withLibraryMembership(isInLibrary = false))
                        }

                        remoteSongs.forEachIndexed { index, song ->
                            val dbSong = songEntity(song.id)
                            val timestamp = now.minusSeconds((remoteSongs.lastIndex - index).toLong())
                            if (dbSong == null) {
                                insert(song.toMediaMetadata()) {
                                    it.withLibraryMembership(isInLibrary = true, addedAt = timestamp)
                                }
                            } else {
                                if (song.id in songIdsWithoutArtists) {
                                    insert(song.toMediaMetadata())
                                }
                                if (dbSong.inLibrary == null) {
                                    update(dbSong.withLibraryMembership(isInLibrary = true, addedAt = timestamp))
                                }
                            }
                        }
                    }

                    Timber.d("Synced ${remoteSongs.size} library songs")
                } catch (e: Exception) {
                    Timber.e(e, "Error processing library songs")
                }
            }.onFailure { e ->
                Timber.e(e, "Failed to fetch library songs from YouTube")
            }
        }.onFailure { e ->
            Timber.e(e, "Failed to sync library songs after retries")
        }
    }

    private suspend fun executeSyncUploadedSongs() = withContext(Dispatchers.IO) {
        if (!isLoggedIn()) {
            Timber.w("Skipping syncUploadedSongs - user not logged in")
            return@withContext
        }


        withRetry {
            // Uploaded songs are in Tab 1 ("Uploads"), not Tab 0 ("Library")
            YouTube.library("FEmusic_library_privately_owned_tracks", tabIndex = 1).completed()
        }.onSuccess { result ->
            result.onSuccess { page ->
                try {
                    val remoteSongs = page.items.filterIsInstance<SongItem>().reversed()
                    val remoteIds = remoteSongs.map { it.id }.toSet()
                    val localSongs = database.uploadedSongEntitiesByNameAsc()
                    val songIdsWithoutArtists = findSongIdsWithoutArtists(remoteIds)

                    // Remove uploaded flag from songs no longer in remote
                    localSongs.filterNot { it.id in remoteIds }.forEach { song ->
                        try {
                            database.update(song.toggleUploaded())
                            delay(DB_OPERATION_DELAY_MS)
                        } catch (e: Exception) {
                            Timber.e(e, "Failed to update song: ${song.id}")
                        }
                    }

                    // Sync remote songs to local database
                    remoteSongs.forEach { song ->
                        try {
                            val dbSong = database.songEntity(song.id)
                            database.withTransaction {
                                if (dbSong == null) {
                                    insert(song.toMediaMetadata()) { it.toggleUploaded() }
                                } else {
                                    if (song.id in songIdsWithoutArtists) {
                                        insert(song.toMediaMetadata())
                                    }
                                    if (!dbSong.isUploaded) {
                                        update(dbSong.copy(isUploaded = true, uploadEntityId = song.uploadEntityId))
                                    } else if (dbSong.uploadEntityId != song.uploadEntityId && song.uploadEntityId != null) {
                                        // Update uploadEntityId if it differs from remote
                                        update(dbSong.copy(uploadEntityId = song.uploadEntityId))
                                    }
                                }
                            }
                            delay(DB_OPERATION_DELAY_MS)
                        } catch (e: Exception) {
                            Timber.e(e, "Failed to process song: ${song.id}")
                        }
                    }

                    Timber.d("Synced ${remoteSongs.size} uploaded songs")
                } catch (e: Exception) {
                    Timber.e(e, "Error processing uploaded songs")
                }
            }.onFailure { e ->
                Timber.e(e, "Failed to fetch uploaded songs from YouTube")
            }
        }.onFailure { e ->
            Timber.e(e, "Failed to sync uploaded songs after retries")
        }
    }

    private suspend fun executeSyncLikedAlbums() = withContext(Dispatchers.IO) {
        if (!isLoggedIn()) {
            Timber.w("Skipping syncLikedAlbums - user not logged in")
            return@withContext
        }


        withRetry {
            YouTube.library("FEmusic_liked_albums").completed()
        }.onSuccess { result ->
            result.onSuccess { page ->
                try {
                    val remoteAlbums = page.items.filterIsInstance<AlbumItem>().reversed()
                    val remoteIds = remoteAlbums.map { it.id }.toSet()
                    val localAlbums = database.likedAlbumEntitiesByNameAsc()

                    if (remoteIds.isNotEmpty()) {
                        localAlbums.filterNot { it.id in remoteIds }.forEach { album ->
                            try {
                                database.update(album.localToggleLike())
                                delay(DB_OPERATION_DELAY_MS)
                            } catch (e: Exception) {
                                Timber.e(e, "Failed to update album: ${album.id}")
                            }
                        }
                    }

                    remoteAlbums.forEach { album ->
                        try {
                            val dbAlbum = database.albumEntity(album.id)
                            if (dbAlbum == null) {
                                YouTube.album(album.browseId).onSuccess { albumPage ->
                                    database.insert(albumPage)
                                    database.albumEntity(album.id)?.let { newDbAlbum ->
                                        database.update(newDbAlbum.localToggleLike())
                                    }
                                }
                            } else if (dbAlbum.bookmarkedAt == null) {
                                database.update(dbAlbum.localToggleLike())
                            }
                            delay(DB_OPERATION_DELAY_MS)
                        } catch (e: Exception) {
                            Timber.e(e, "Failed to process album: ${album.id}")
                        }
                    }

                    Timber.d("Synced ${remoteAlbums.size} liked albums")
                } catch (e: Exception) {
                    Timber.e(e, "Error processing liked albums")
                }
            }.onFailure { e ->
                Timber.e(e, "Failed to fetch liked albums from YouTube")
            }
        }.onFailure { e ->
            Timber.e(e, "Failed to sync liked albums after retries")
        }
    }

    private suspend fun executeSyncUploadedAlbums() = withContext(Dispatchers.IO) {
        if (!isLoggedIn()) {
            Timber.w("Skipping syncUploadedAlbums - user not logged in")
            return@withContext
        }


        withRetry {
            YouTube.library("FEmusic_library_privately_owned_releases").completed()
        }.onSuccess { result ->
            result.onSuccess { page ->
                try {
                    val remoteAlbums = page.items.filterIsInstance<AlbumItem>().reversed()
                    val remoteIds = remoteAlbums.map { it.id }.toSet()
                    val localAlbums = database.uploadedAlbumEntitiesByNameAsc()

                    localAlbums.filterNot { it.id in remoteIds }.forEach { album ->
                        try {
                            database.update(album.toggleUploaded())
                            delay(DB_OPERATION_DELAY_MS)
                        } catch (e: Exception) {
                            Timber.e(e, "Failed to update album: ${album.id}")
                        }
                    }

                    remoteAlbums.forEach { album ->
                        try {
                            val dbAlbum = database.albumEntity(album.id)
                            if (dbAlbum == null) {
                                YouTube.album(album.browseId).onSuccess { albumPage ->
                                    database.insert(albumPage)
                                    database.albumEntity(album.id)?.let { newDbAlbum ->
                                        database.update(newDbAlbum.toggleUploaded())
                                    }
                                }.onFailure { reportException(it) }
                            } else if (!dbAlbum.isUploaded) {
                                database.update(dbAlbum.toggleUploaded())
                            }
                            delay(DB_OPERATION_DELAY_MS)
                        } catch (e: Exception) {
                            Timber.e(e, "Failed to process album: ${album.id}")
                        }
                    }

                    Timber.d("Synced ${remoteAlbums.size} uploaded albums")
                } catch (e: Exception) {
                    Timber.e(e, "Error processing uploaded albums")
                }
            }.onFailure { e ->
                Timber.e(e, "Failed to fetch uploaded albums from YouTube")
            }
        }.onFailure { e ->
            Timber.e(e, "Failed to sync uploaded albums after retries")
        }
    }

    private suspend fun executeSyncArtistsSubscriptions() = withContext(Dispatchers.IO) {
        if (!isLoggedIn()) {
            Timber.w("Skipping syncArtistsSubscriptions - user not logged in")
            return@withContext
        }


        withRetry {
            YouTube.library("FEmusic_library_corpus_artists").completed()
        }.onSuccess { result ->
            result.onSuccess { page ->
                try {
                    val remoteArtists = page.items.filterIsInstance<ArtistItem>()
                    val remoteIds = remoteArtists.map { it.id }.toSet()
                    val localArtists = database.bookmarkedArtistEntitiesByNameAsc()

                    localArtists.filterNot { it.id in remoteIds }.forEach { artist ->
                        try {
                            database.update(artist.localToggleLike())
                            delay(DB_OPERATION_DELAY_MS)
                        } catch (e: Exception) {
                            Timber.e(e, "Failed to update artist: ${artist.id}")
                        }
                    }

                    remoteArtists.forEach { artist ->
                        try {
                            val dbArtist = database.artistEntity(artist.id)
                            val channelId = artist.channelId ?: artist.id.takeIf { it.startsWith("UC") }

                            database.withTransaction {
                                if (dbArtist == null) {
                                    insert(
                                        ArtistEntity(
                                            id = artist.id,
                                            name = ArtistNameAliases.resolve(artist.id, artist.title),
                                            thumbnailUrl = artist.thumbnail,
                                            channelId = channelId,
                                            bookmarkedAt = LocalDateTime.now()
                                        )
                                    )
                                } else {
                                    val existing = dbArtist
                                    val needsChannelIdUpdate = existing.channelId == null && channelId != null
                                    val resolvedName = ArtistNameAliases.resolve(existing.id, artist.title)
                                    if (existing.bookmarkedAt == null || needsChannelIdUpdate ||
                                        existing.name != resolvedName || existing.thumbnailUrl != artist.thumbnail) {
                                        update(
                                            existing.copy(
                                                name = resolvedName,
                                                thumbnailUrl = artist.thumbnail,
                                                channelId = channelId ?: existing.channelId,
                                                bookmarkedAt = existing.bookmarkedAt ?: LocalDateTime.now(),
                                                lastUpdateTime = LocalDateTime.now()
                                            )
                                        )
                                    }
                                }
                            }
                            delay(DB_OPERATION_DELAY_MS)
                        } catch (e: Exception) {
                            Timber.e(e, "Failed to process artist: ${artist.id}")
                        }
                    }

                    Timber.d("Synced ${remoteArtists.size} artist subscriptions")
                } catch (e: Exception) {
                    Timber.e(e, "Error processing artist subscriptions")
                }
            }.onFailure { e ->
                Timber.e(e, "Failed to fetch artist subscriptions from YouTube")
            }
        }.onFailure { e ->
            Timber.e(e, "Failed to sync artist subscriptions after retries")
        }
    }

    private suspend fun executeSyncPodcastSubscriptions() = withContext(Dispatchers.IO) {
        Timber.d("[PODCAST_SYNC] executeSyncPodcastSubscriptions() started")
        if (!isLoggedIn()) {
            Timber.w("[PODCAST_SYNC] Skipping syncPodcastSubscriptions - user not logged in")
            return@withContext
        }
        Timber.d("[PODCAST_SYNC] User is logged in, proceeding with sync")

        val allRemoteIds = mutableSetOf<String>()
        var fetchedSavedShows = false
        var fetchedSubscribedChannels = false

        // Sync saved podcast shows (most common - saved via likePlaylist)
        withRetry {
            Timber.d("[PODCAST_SYNC] Calling YouTube.savedPodcastShows()")
            YouTube.savedPodcastShows()
        }.onSuccess { result ->
            Timber.d("[PODCAST_SYNC] savedPodcastShows succeeded, result isSuccess=${result.isSuccess}")
            result.onSuccess { remotePodcasts ->
                fetchedSavedShows = true
                allRemoteIds.addAll(remotePodcasts.map { it.id })
                try {
                    Timber.d("[PODCAST_SYNC] Fetched ${remotePodcasts.size} saved podcast shows")

                    remotePodcasts.forEachIndexed { index, podcast ->
                        Timber.d("[PODCAST_SYNC] Remote podcast $index: id=${podcast.id}, title=${podcast.title}, author=${podcast.author?.name}")
                    }

                    // Server-first: YouTube Music is the source of truth
                    // Add/update podcasts from remote
                    remotePodcasts.forEach { podcast ->
                        try {
                            val dbPodcast = database.podcast(podcast.id).firstOrNull()
                            Timber.d("[PODCAST_SYNC] Processing remote podcast ${podcast.id}: exists in db=${dbPodcast != null}, isSubscribed=${dbPodcast?.bookmarkedAt != null}")

                            database.withTransaction {
                                if (dbPodcast == null) {
                                    // Only add truly new podcasts from server
                                    Timber.d("[PODCAST_SYNC] Inserting new podcast: ${podcast.id}")
                                    insert(
                                        PodcastEntity(
                                            id = podcast.id,
                                            title = podcast.title,
                                            author = podcast.author?.name,
                                            thumbnailUrl = podcast.thumbnail,
                                            channelId = podcast.channelId ?: podcast.author?.id,
                                            bookmarkedAt = LocalDateTime.now(),
                                        )
                                    )
                                } else if (dbPodcast.bookmarkedAt != null) {
                                    // Update metadata for already-saved podcasts, but don't re-bookmark
                                    // ones that user has removed locally (respect local state)
                                    Timber.d("[PODCAST_SYNC] Updating metadata for saved podcast: ${podcast.id}")
                                    update(
                                        dbPodcast.copy(
                                            title = podcast.title,
                                            author = podcast.author?.name,
                                            thumbnailUrl = podcast.thumbnail,
                                            channelId = podcast.channelId ?: podcast.author?.id ?: dbPodcast.channelId,
                                            lastUpdateTime = LocalDateTime.now(),
                                        )
                                    )
                                } else {
                                    // Podcast exists locally but is unbookmarked - user removed it
                                    // Don't re-add; the server removal is likely still pending
                                    Timber.d("[PODCAST_SYNC] Skipping unbookmarked podcast: ${podcast.id}")
                                }
                            }
                            delay(DB_OPERATION_DELAY_MS)
                        } catch (e: Exception) {
                            Timber.e(e, "[PODCAST_SYNC] Failed to process podcast: ${podcast.id}")
                        }
                    }

                    Timber.d("[PODCAST_SYNC] Synced ${remotePodcasts.size} saved podcast shows successfully")
                } catch (e: Exception) {
                    Timber.e(e, "[PODCAST_SYNC] Error processing saved podcast shows")
                }
            }.onFailure { e ->
                Timber.e(e, "[PODCAST_SYNC] Failed to fetch saved podcast shows from YouTube")
            }
        }.onFailure { e ->
            Timber.e(e, "[PODCAST_SYNC] Failed to sync saved podcast shows after retries")
        }

        // Also sync subscribed podcast channels (subscribed via subscribeChannel API)
        withRetry {
            Timber.d("[PODCAST_SYNC] Calling YouTube.libraryPodcastChannels()")
            YouTube.libraryPodcastChannels()
        }.onSuccess { result ->
            Timber.d("[PODCAST_SYNC] libraryPodcastChannels succeeded, result isSuccess=${result.isSuccess}")
            result.onSuccess { page ->
                try {
                    val remotePodcasts = page.items.filterIsInstance<PodcastItem>()
                    fetchedSubscribedChannels = true
                    allRemoteIds.addAll(remotePodcasts.map { it.id })
                    Timber.d("[PODCAST_SYNC] Fetched ${remotePodcasts.size} subscribed podcast channels")

                    // Add/update podcasts from remote channels
                    remotePodcasts.forEach { podcast ->
                        try {
                            val dbPodcast = database.podcast(podcast.id).firstOrNull()
                            Timber.d("[PODCAST_SYNC] Processing subscribed channel ${podcast.id}: exists in db=${dbPodcast != null}")

                            database.withTransaction {
                                if (dbPodcast == null) {
                                    // Only add truly new podcasts from server
                                    Timber.d("[PODCAST_SYNC] Inserting new subscribed channel: ${podcast.id}")
                                    insert(
                                        PodcastEntity(
                                            id = podcast.id,
                                            title = podcast.title,
                                            author = podcast.author?.name,
                                            thumbnailUrl = podcast.thumbnail,
                                            channelId = podcast.channelId ?: podcast.author?.id,
                                            bookmarkedAt = LocalDateTime.now(),
                                        )
                                    )
                                } else if (dbPodcast.bookmarkedAt != null) {
                                    // Update metadata for already-saved podcasts
                                    Timber.d("[PODCAST_SYNC] Updating metadata for subscribed channel: ${podcast.id}")
                                    update(
                                        dbPodcast.copy(
                                            title = podcast.title,
                                            author = podcast.author?.name,
                                            thumbnailUrl = podcast.thumbnail,
                                            channelId = podcast.channelId ?: podcast.author?.id ?: dbPodcast.channelId,
                                            lastUpdateTime = LocalDateTime.now(),
                                        )
                                    )
                                } else {
                                    // Podcast exists locally but is unbookmarked - don't re-add
                                    Timber.d("[PODCAST_SYNC] Skipping unbookmarked channel: ${podcast.id}")
                                }
                            }
                            delay(DB_OPERATION_DELAY_MS)
                        } catch (e: Exception) {
                            Timber.e(e, "[PODCAST_SYNC] Failed to process subscribed channel: ${podcast.id}")
                        }
                    }

                    Timber.d("[PODCAST_SYNC] Synced ${remotePodcasts.size} subscribed podcast channels successfully")
                } catch (e: Exception) {
                    Timber.e(e, "[PODCAST_SYNC] Error processing subscribed podcast channels")
                }
            }.onFailure { e ->
                Timber.e(e, "[PODCAST_SYNC] Failed to fetch subscribed podcast channels from YouTube")
            }
        }.onFailure { e ->
            Timber.e(e, "[PODCAST_SYNC] Failed to sync subscribed podcast channels after retries")
        }

        // Cleanup: Remove local podcasts that are no longer subscribed on YouTube Music
        try {
            if (fetchedSavedShows && fetchedSubscribedChannels) {
                val localPodcasts = database.subscribedPodcasts().first()
                val localOnlyPodcasts = localPodcasts.filterNot { it.id in allRemoteIds }
                Timber.d("[PODCAST_SYNC] Cleanup: removing ${localOnlyPodcasts.size} podcasts not on YTM")

                localOnlyPodcasts.forEach { podcast ->
                    try {
                        // Remove subscription (set bookmarkedAt to null)
                        database.withTransaction {
                            update(podcast.copy(bookmarkedAt = null))
                        }
                        Timber.d("[PODCAST_SYNC] Unsubscribed from local podcast: ${podcast.id}")
                    } catch (e: Exception) {
                        Timber.e(e, "[PODCAST_SYNC] Failed to cleanup podcast: ${podcast.id}")
                    }
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "[PODCAST_SYNC] Error during cleanup")
        }
    }

    private suspend fun executeSyncEpisodesForLater() = withContext(Dispatchers.IO) {
        Timber.d("[EPISODES_SYNC] executeSyncEpisodesForLater() started")
        if (!isLoggedIn()) {
            Timber.w("[EPISODES_SYNC] Skipping syncEpisodesForLater - user not logged in")
            return@withContext
        }
        Timber.d("[EPISODES_SYNC] User is logged in, proceeding with sync")


        withRetry {
            Timber.d("[EPISODES_SYNC] Calling YouTube.episodesForLater() (VLSE playlist)")
            YouTube.episodesForLater()
        }.onSuccess { result ->
            result.onSuccess { remoteEpisodes ->
                try {
                    Timber.d("[EPISODES_SYNC] Fetched ${remoteEpisodes.size} episodes from VLSE playlist")
                    val remoteIds = remoteEpisodes.map { it.id }.toSet()

                    // Get local episodes that are saved (for cleanup later)
                    val localSavedEpisodes = database.savedEpisodeEntitiesByCreateDateAsc()
                    Timber.d("[EPISODES_SYNC] Local saved episodes: ${localSavedEpisodes.size}")

                    // Server-first: YouTube Music is the source of truth
                    // Sync remote episodes to local database
                    remoteEpisodes.forEach { episode ->
                        try {
                            val dbSong = database.songEntity(episode.id)
                            Timber.d("[EPISODES_SYNC] Processing remote episode ${episode.id}: exists in db=${dbSong != null}")

                            database.withTransaction {
                                if (dbSong == null) {
                                    Timber.d("[EPISODES_SYNC] Inserting new episode: ${episode.id}")
                                    val mediaMetadata = episode.toMediaMetadata()
                                    insert(mediaMetadata.toSongEntity().copy(
                                        inLibrary = LocalDateTime.now(),
                                        isEpisode = true
                                    ))
                                    // Insert artists
                                    mediaMetadata.artists.forEach { artist ->
                                        artist.id?.let { artistId ->
                                            insert(
                                                ArtistEntity(
                                                    id = artistId,
                                                    name = artist.name,
                                                )
                                            )
                                        }
                                    }
                                } else if (!dbSong.isEpisode || dbSong.inLibrary == null) {
                                    Timber.d("[EPISODES_SYNC] Updating existing song to episode in library: ${episode.id}")
                                    update(
                                        dbSong.copy(
                                            isEpisode = true,
                                            inLibrary = dbSong.inLibrary ?: LocalDateTime.now(),
                                            libraryAddToken = episode.libraryAddToken ?: dbSong.libraryAddToken,
                                            libraryRemoveToken = episode.libraryRemoveToken ?: dbSong.libraryRemoveToken,
                                        )
                                    )
                                } else {
                                    // Update tokens if we got new ones
                                    if (episode.libraryAddToken != null || episode.libraryRemoveToken != null) {
                                        update(
                                            dbSong.copy(
                                                libraryAddToken = episode.libraryAddToken ?: dbSong.libraryAddToken,
                                                libraryRemoveToken = episode.libraryRemoveToken ?: dbSong.libraryRemoveToken,
                                            )
                                        )
                                    }
                                    Timber.d("[EPISODES_SYNC] Episode already in library: ${episode.id}")
                                }
                                // Store setVideoId for removal capability
                                episode.setVideoId?.let { svid ->
                                    Timber.d("[EPISODES_SYNC] Storing setVideoId for ${episode.id}: $svid")
                                    insert(SetVideoIdEntity(videoId = episode.id, setVideoId = svid))
                                }
                            }
                            delay(DB_OPERATION_DELAY_MS)
                        } catch (e: Exception) {
                            Timber.e(e, "[EPISODES_SYNC] Failed to process episode: ${episode.id}")
                        }
                    }

                    // Cleanup: Remove local episodes that are no longer in Episodes for Later
                    val localToRemove = localSavedEpisodes.filterNot { it.id in remoteIds }
                    Timber.d("[EPISODES_SYNC] Cleanup: removing ${localToRemove.size} episodes not in VLSE")
                    localToRemove.forEach { song ->
                        try {
                            database.withTransaction {
                                update(song.copy(inLibrary = null))
                            }
                            Timber.d("[EPISODES_SYNC] Removed episode from library: ${song.id}")
                        } catch (e: Exception) {
                            Timber.e(e, "[EPISODES_SYNC] Failed to cleanup episode: ${song.id}")
                        }
                    }

                    Timber.d("[EPISODES_SYNC] Synced ${remoteEpisodes.size} episodes successfully")
                } catch (e: Exception) {
                    Timber.e(e, "[EPISODES_SYNC] Error processing episodes")
                }
            }.onFailure { e ->
                Timber.e(e, "[EPISODES_SYNC] Failed to fetch episodes from YouTube")
            }
        }.onFailure { e ->
            Timber.e(e, "[EPISODES_SYNC] Failed to sync episodes after retries")
        }
    }

    private suspend fun executeSyncSavedPlaylists() = withContext(Dispatchers.IO) {
        if (!isLoggedIn()) {
            Timber.w("Skipping syncSavedPlaylists - user not logged in")
            return@withContext
        }


        // Before reading the remote library, so a playlist created here is already listed below
        // and gets matched to its local row instead of looking like a stranger.
        createPendingPlaylists()

        withRetry {
            YouTube.library("FEmusic_liked_playlists").completed()
        }.onSuccess { result ->
            result.onSuccess { page ->
                try {
                    val remotePlaylists = page.items.filterIsInstance<PlaylistItem>()
                        .filterNot { it.id == "LM" || it.id == "SE" }
                        .reversed()
                        .distinctBy { it.id }
                    val remoteIds = remotePlaylists.map { it.id }.toSet()

                    executeCleanupDuplicatePlaylists()

                    val localPlaylists = database.playlistEntitiesByNameAsc().toMutableList()
                    localPlaylists.filterNot { it.browseId in remoteIds }
                        .filterNot { it.browseId == null }
                        .forEach { playlist ->
                            try {
                                database.update(playlist.localToggleLike())
                                delay(DB_OPERATION_DELAY_MS)
                            } catch (e: Exception) {
                                Timber.e(e, "Failed to update playlist: ${playlist.id}")
                            }
                        }

                    for (playlist in remotePlaylists) {
                        try {
                            var playlistEntity = localPlaylists.find { it.browseId == playlist.id }

                            if (playlistEntity == null) {
                                playlistEntity = PlaylistEntity(
                                    name = playlist.title,
                                    browseId = playlist.id,
                                    thumbnailUrl = playlist.thumbnail,
                                    isEditable = playlist.isEditable,
                                    bookmarkedAt = LocalDateTime.now(),
                                    remoteSongCount = playlist.songCountText?.let {
                                        Regex("""\d+""").find(it)?.value?.toIntOrNull()
                                    },
                                    playEndpointParams = playlist.playEndpoint?.params,
                                    shuffleEndpointParams = playlist.shuffleEndpoint?.params,
                                    radioEndpointParams = playlist.radioEndpoint?.params
                                )
                                database.insert(playlistEntity)
                                localPlaylists.add(playlistEntity)
                                Timber.d("syncSavedPlaylists: Created new playlist ${playlist.title} (${playlist.id})")
                            } else {
                                database.update(playlistEntity, playlist)
                                Timber.d("syncSavedPlaylists: Updated existing playlist ${playlist.title} (${playlist.id})")
                            }

                            if (!isPlaylistBeingModified(playlistEntity.id)) {
                                executeSyncPlaylist(playlist.id, playlistEntity.id)
                                delay(DB_OPERATION_DELAY_MS)
                            } else {
                                Timber.d("Skipping playlist ${playlist.title} — remove in progress")
                            }
                            delay(DB_OPERATION_DELAY_MS)
                        } catch (e: Exception) {
                            Timber.e(e, "Failed to sync playlist ${playlist.title}")
                        }
                    }

                    Timber.d("Synced ${remotePlaylists.size} saved playlists")
                } catch (e: Exception) {
                    Timber.e(e, "Error processing saved playlists")
                }
            }.onFailure { e ->
                Timber.e(e, "syncSavedPlaylists: Failed to fetch playlists from YouTube")
            }
        }.onFailure { e ->
            Timber.e(e, "Failed to sync saved playlists after retries")
        }
    }

    private suspend fun executeSyncAutoSyncPlaylists() = withContext(Dispatchers.IO) {
        if (!isLoggedIn()) {
            Timber.w("Skipping syncAutoSyncPlaylists - user not logged in")
            return@withContext
        }

        try {
            val autoSyncPlaylists = database.playlistEntitiesByNameAsc()
                .filter { it.isAutoSync && it.browseId != null }

            Timber.d("syncAutoSyncPlaylists: Found ${autoSyncPlaylists.size} playlists to sync")

            autoSyncPlaylists.forEach { playlist ->
                // Skip playlists with a pending remove operation
                if (isPlaylistBeingModified(playlist.id)) {
                    Timber.d("Skipping playlist ${playlist.name} — remove in progress")
                    return@forEach
                }
                try {
                    executeSyncPlaylist(playlist.browseId!!, playlist.id)
                    delay(DB_OPERATION_DELAY_MS)
                } catch (e: Exception) {
                    Timber.e(e, "Failed to sync playlist ${playlist.name}")
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Error syncing auto-sync playlists")
        }
    }

    private suspend fun executeSyncPlaylist(browseId: String, playlistId: String) = withContext(Dispatchers.IO) {
        Timber.d("syncPlaylist: Starting sync for browseId=$browseId, playlistId=$playlistId")

        withRetry {
            YouTube.playlist(browseId).completed()
        }.onSuccess { result ->
            result.onSuccess { page ->
                try {
                    val songs = page.songs.map(SongItem::toMediaMetadata)
                    Timber.d("syncPlaylist: Fetched ${songs.size} songs from remote")

                    if (songs.isEmpty()) {
                        Timber.w("syncPlaylist: Remote playlist is empty, skipping sync")
                        return@onSuccess
                    }

                    val remoteIds = songs.map { it.id }
                    val localIds = database.playlistSongIds(playlistId)
                    val songIdsWithoutArtists = database.playlistSongIdsWithoutArtists(playlistId).toSet()

                    if (remoteIds == localIds) {
                        val metadataRepairs = songs.filter {
                            it.id in songIdsWithoutArtists && it.artists.isNotEmpty()
                        }
                        if (metadataRepairs.isNotEmpty()) {
                            database.withTransaction {
                                metadataRepairs.forEach(::insert)
                            }
                        }
                        Timber.d("syncPlaylist: Local and remote are in sync, no changes needed")
                        return@onSuccess
                    }

                    Timber.d("syncPlaylist: Updating local playlist (remote: ${remoteIds.size}, local: ${localIds.size})")

                    val remoteIdSet = remoteIds.toSet()
                    val localIdSet = localIds.toSet()
                    val downloadedSongIds = database.downloadedPlaylistSongIds(playlistId).toSet()
                    val metadataInserts = songs.filter {
                        it.id !in localIdSet || it.id in songIdsWithoutArtists
                    }

                    database.withTransaction {
                        database.clearPlaylist(playlistId)
                        metadataInserts.forEach(database::insert)

                        downloadedSongIds.forEach { songId ->
                            if (songId !in remoteIdSet) {
                                val existingSong = database.getSongByIdBlocking(songId)
                                if (existingSong != null) {
                                    val maxPosition = database.playlistSongsBlocking(playlistId)
                                        .maxOfOrNull { it.map.position } ?: -1
                                    database.insert(
                                        PlaylistSongMap(
                                            songId = songId,
                                            playlistId = playlistId,
                                            position = maxPosition + 1
                                        )
                                    )
                                    Timber.d("syncPlaylist: Preserved downloaded song $songId in playlist")
                                }
                            }
                        }

                        val playlistEntity = database.playlistBlocking(playlistId)
                        if (playlistEntity != null) {
                            database.addSongsToPlaylist(
                                playlistEntity,
                                songs.map { it.id to it.setVideoId }
                            )
                        }
                    }
                    Timber.d("syncPlaylist: Successfully synced playlist")
                } catch (e: Exception) {
                    Timber.e(e, "Error processing playlist sync")
                }
            }.onFailure { e ->
                Timber.e(e, "syncPlaylist: Failed to fetch playlist from YouTube")
            }
        }.onFailure { e ->
            Timber.e(e, "syncPlaylist: Failed after retries")
        }
    }

    private suspend fun executeCleanupDuplicatePlaylists() = withContext(Dispatchers.IO) {
        try {
            val allPlaylists = database.playlistEntitiesByNameAsc()
            val browseIdGroups = allPlaylists
                .filter { it.browseId != null }
                .groupBy { it.browseId }

            for ((browseId, playlists) in browseIdGroups) {
                if (playlists.size > 1) {
                    Timber.w("Found ${playlists.size} duplicate playlists for browseId: $browseId")
                    val toKeep = playlists.maxByOrNull { it.remoteSongCount ?: 0 } ?: playlists.first()

                    playlists.filter { it.id != toKeep.id }.forEach { duplicate ->
                        try {
                            Timber.d("Removing duplicate playlist: ${duplicate.name} (${duplicate.id})")
                            database.clearPlaylist(duplicate.id)
                            database.delete(duplicate)
                            delay(DB_OPERATION_DELAY_MS)
                        } catch (e: Exception) {
                            Timber.e(e, "Failed to remove duplicate playlist: ${duplicate.id}")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Error cleaning up duplicate playlists")
        }
    }

    private suspend fun executeClearPodcastData() = withContext(Dispatchers.IO) {
        Timber.d("[PODCAST_CLEAR] Starting podcast data cleanup")

        try {
            // Read data FIRST, outside any transaction. Room Flows must be collected outside
            // withTransaction to avoid deadlocks (Room's InvalidationTracker may block on
            // the transaction executor when collecting a DAO Flow inside a transaction).
            val subscribedPodcasts = database.subscribedPodcasts().first()
            val allEpisodes = database.podcastEpisodesByCreateDateAsc().first()
            val savedEpisodes = allEpisodes.filter { it.song.inLibrary != null }

            Timber.d("[PODCAST_CLEAR] Clearing ${subscribedPodcasts.size} subscribed podcasts " +
                    "and ${savedEpisodes.size} saved episodes")

            // Now perform the writes in a transaction for atomicity
            database.withTransaction {
                subscribedPodcasts.forEach { podcast ->
                    database.update(podcast.copy(bookmarkedAt = null))
                }
                savedEpisodes.forEach { song ->
                    database.update(song.song.copy(inLibrary = null))
                }
            }

            Timber.d("[PODCAST_CLEAR] Podcast data cleared successfully")
        } catch (e: Exception) {
            Timber.e(e, "[PODCAST_CLEAR] Error during cleanup")
        }
    }

    /**
     * Creates a playlist locally and, when the user asked for it to be synced, on YouTube as well.
     *
     * Both steps run on [syncScope] rather than on the caller's composition scope, because the
     * dialog that starts this is dismissed before the work begins and anything tied to it would be
     * cancelled halfway. The local row is written first and never depends on the network, so a
     * failed upload costs the playlist nothing: it stays pending and a later sync creates it.
     *
     * [onCreated] is delivered on the main thread once the row exists, since its callers navigate.
     */
    fun createPlaylist(playlist: PlaylistEntity, onCreated: ((String) -> Unit)? = null) {
        syncScope.launch {
            database.insert(playlist)
            withContext(Dispatchers.Main) { onCreated?.invoke(playlist.id) }
            // The account's playlists as they stand before this one is asked for, so a lost
            // answer can be told from a request that never arrived. There is nothing else to
            // match on: a creation carries a title and no key, and a title is not an identity.
            createPendingPlaylist(
                playlist,
                idsBefore = remotePlaylistListing()?.map { (id, _) -> id }?.toSet(),
                notifyIfStillPending = true,
            )
        }
    }

    /**
     * Creates on YouTube every playlist the user asked to sync that never reached it. This one
     * stays quiet: the user is not waiting on it, and the entry point runs on every playlist sync.
     */
    private suspend fun createPendingPlaylists() {
        val pending = database.playlistEntitiesByNameAsc().pendingRemoteCreations()
        if (pending.isEmpty()) return

        // Read once for the whole batch rather than once per playlist: it is the same snapshot
        // for all of them, and none of them has been asked for yet.
        val idsBefore = remotePlaylistListing()?.map { (id, _) -> id }?.toSet()
        pending.forEach { playlist ->
            createPendingPlaylist(playlist, idsBefore, notifyIfStillPending = false)
            delay(DB_OPERATION_DELAY_MS)
        }
    }

    /**
     * [idsBefore] is the account's playlists as they were before this attempt, which is what tells
     * a playlist this request created from the ones that were already there. Null when the list
     * could not be read, and then a lost answer cannot be reconciled and the playlist stays
     * pending rather than being asked for twice.
     */
    private suspend fun createPendingPlaylist(
        playlist: PlaylistEntity,
        idsBefore: Set<String>?,
        notifyIfStillPending: Boolean,
    ) {
        if (!playlist.isPendingRemoteCreation() || !isLoggedIn()) return

        if (!context.isInternetConnected()) {
            Timber.d("createPlaylist: offline, ${playlist.name} stays pending")
            if (notifyIfStillPending) notifyPlaylistStillPending(playlist.name)
            return
        }

        // add() is the test and the claim in one step, so the dialog's own upload and a sync
        // walking the pending playlists cannot both create this one.
        if (!playlistsBeingCreated.add(playlist.id)) return
        try {
            // One attempt, never replayed. A creation that reached YouTube and lost its answer
            // fails exactly like one that never arrived, and asking again would leave the user
            // with two playlists.
            runCatching {
                runQueuedPlaylistEdit {
                    YouTube.createPlaylist(playlist.name).getOrThrow()
                }
            }.onSuccess { browseId ->
                database.update(playlist.copy(browseId = browseId))
                Timber.d("createPlaylist: created ${playlist.name} on YouTube as $browseId")
            }.onFailure { e ->
                Timber.w(e, "createPlaylist: ${playlist.name} failed, looking for what it left behind")
                val created = idsBefore?.let { before ->
                    remotePlaylistListing()?.let { after ->
                        playlistCreatedByLostRequest(playlist.name, before, after)
                    }
                }
                if (created != null) {
                    database.update(playlist.copy(browseId = created))
                    Timber.d("createPlaylist: ${playlist.name} had reached YouTube as $created")
                } else {
                    Timber.e(e, "createPlaylist: ${playlist.name} stays pending")
                    if (notifyIfStillPending) notifyPlaylistStillPending(playlist.name)
                }
            }
        } finally {
            playlistsBeingCreated.remove(playlist.id)
        }
    }

    /** The account's playlists as browse id and title, or null when the list could not be read. */
    private suspend fun remotePlaylistListing(): List<Pair<String, String>>? =
        withRetry {
            YouTube.library("FEmusic_liked_playlists").completed()
        }.getOrNull()?.getOrNull()?.items
            ?.filterIsInstance<PlaylistItem>()
            ?.map { it.id to it.title }

    private suspend fun notifyPlaylistStillPending(playlistName: String) {
        val message = context.getString(R.string.playlist_pending_youtube, playlistName)
        withContext(Dispatchers.Main) {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    suspend fun addToPlaylist(
        browseId: String,
        playlistId: String,
        songId: String,
    ): Boolean {
        markPlaylistModifying(playlistId)
        return try {
            runQueuedPlaylistEdit {
                YouTube.addToPlaylist(browseId, songId).getOrThrow()
            }
            true
        } catch (e: Exception) {
            Timber.e(e, "Failed to add song $songId to playlist $browseId")
            false
        } finally {
            unmarkPlaylistModifying(playlistId)
        }
    }

    fun scheduleRemoveFromPlaylist(
        browseId: String,
        songId: String,
        playlistId: String,
        getSetVideoId: suspend () -> String?
    ) {
        markPlaylistModifying(playlistId)
        syncScope.launch {
            try {
                runQueuedPlaylistEdit {
                    var setVideoId = getSetVideoId()

                    if (setVideoId == null) {
                        Timber.w("scheduleRemoveFromPlaylist: setVideoId not in DB, fetching from YouTube")
                        for (attempt in 0 until 3) {
                            setVideoId = runCatching {
                                YouTube.playlist(browseId).completed().getOrThrow()
                                    .songs.lastOrNull { it.id == songId }?.setVideoId
                            }.getOrNull()
                            if (setVideoId != null) break
                            if (attempt < 2) delay(2_000L)
                        }
                    }

                    if (setVideoId == null) {
                        Timber.w("scheduleRemoveFromPlaylist: setVideoId not found on YouTube, skipping remove for songId=$songId")
                        return@runQueuedPlaylistEdit
                    }

                    YouTube.removeFromPlaylist(browseId, songId, setVideoId).getOrThrow()
                }
                delay(3_000L)
            } catch (e: Exception) {
                Timber.e(e, "Failed to remove song $songId from playlist $browseId")
            } finally {
                unmarkPlaylistModifying(playlistId)
            }
        }
    }

}
