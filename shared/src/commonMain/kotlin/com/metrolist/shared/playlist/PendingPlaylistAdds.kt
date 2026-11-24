package com.metrolist.shared.playlist

import com.metrolist.shared.api.PlaylistNotFoundException
import com.metrolist.shared.api.PlaylistOnlyAvailableOfflineException
import com.metrolist.shared.api.YouTubeRepository
import com.metrolist.shared.auth.YouTubeSyncState
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.network.isHttpTransportException
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.state.GlobalYouTubeRepository
import com.metrolist.shared.viewmodels.currentLibraryAccountKey
import com.metrolist.shared.youtube.logging.YtLogger
import com.metrolist.shared.youtube.playback.PlaybackNetworkStateStore
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

// A transport failure or a playlist read only from its stored copy both mean the request never reached YouTube.
internal fun Throwable.meansYouTubeUnreachable(): Boolean = isHttpTransportException() || this is PlaylistOnlyAvailableOfflineException

@Serializable
internal data class PendingPlaylistAdd(
    val id: Long,
    val accountKey: String,
    val playlistId: String,
    val songId: String,
    val allowDuplicate: Boolean,
    val failures: Int = 0,
    val nextAttemptAtMs: Long = 0L,
)

/**
 * Songs added to a YouTube playlist while YouTube could not be reached. The local playlist already lists them. Each
 * entry stays queued until YouTube accepts the add or lists the song in the playlist.
 */
object PendingPlaylistAdds {
    private const val TAG = "PendingPlaylistAdds"
    private const val SYNC_OPERATION = "playlist-adds"
    private const val REPORT_AFTER_REJECTIONS = 3
    private val RETRY_INTERVAL = 1.minutes
    private val MAX_RETRY_INTERVAL = 60.minutes

    private val json = Json { ignoreUnknownKeys = true }
    private val lock = SynchronizedObject()
    private val additions = MutableStateFlow(0L)
    private var boundDatabase: MusicDatabase? = null
    private var syncJob: Job? = null

    fun bind(
        database: MusicDatabase,
        scope: CoroutineScope,
    ) = synchronized(lock) {
        if (boundDatabase === database && syncJob?.isActive == true) return@synchronized
        syncJob?.cancel()
        boundDatabase = database
        syncJob = scope.launch(Dispatchers.Default) { syncWhileQueued(database) }
    }

    internal fun enqueue(
        playlistId: String,
        songId: String,
        allowDuplicate: Boolean,
    ) {
        synchronized(lock) {
            val entries = read()
            val id = (entries.maxOfOrNull { it.id } ?: 0L) + 1L
            write(entries + PendingPlaylistAdd(id, currentLibraryAccountKey(), playlistId, songId, allowDuplicate))
        }
        additions.update { it + 1L }
    }

    internal fun songIds(playlistId: String): Set<String> =
        synchronized(lock) { read() }.filter { it.playlistId == playlistId }.mapTo(mutableSetOf()) { it.songId }

    fun clear() {
        synchronized(lock) { write(emptyList()) }
        YouTubeSyncState.succeeded(SYNC_OPERATION)
    }

    private suspend fun syncWhileQueued(database: MusicDatabase) =
        coroutineScope {
            val wake = Channel<Unit>(Channel.CONFLATED)
            launch {
                combine(additions, PlaybackNetworkStateStore.state) { _, _ -> }.drop(1).collect { wake.trySend(Unit) }
            }
            while (true) {
                val waiting =
                    try {
                        sync(database, GlobalYouTubeRepository.instance)
                    } catch (_: CancellationException) {
                        // A session change cancels the request, not this loop, which only stops when its own job ends.
                        currentCoroutineContext().ensureActive()
                        true
                    } catch (error: Exception) {
                        YtLogger.w(TAG, "Pending playlist sync failed type=${error::class.simpleName}")
                        true
                    }
                // A new addition or connection retries at once; songs YouTube has not confirmed retry every minute.
                if (waiting) {
                    withTimeoutOrNull(RETRY_INTERVAL) { wake.receive() }
                } else {
                    wake.receive()
                }
            }
        }

    // Returns whether songs of the current account are still waiting for YouTube.
    private suspend fun sync(
        database: MusicDatabase,
        repository: YouTubeRepository,
    ): Boolean {
        val accountKey = currentLibraryAccountKey()
        val queued = synchronized(lock) { read() }.filter { it.accountKey == accountKey }
        if (queued.isEmpty()) return false
        if (!PlaybackNetworkStateStore.current.connected || !repository.canManagePlaylists()) return true
        val dao = database.dao()
        for (entry in queued) {
            if (entry.nextAttemptAtMs > nowMs()) continue
            val remoteId = dao.findPlaylistEntity(entry.playlistId)?.let { remotePlaylistId(it, entry.playlistId) }
            // The playlist was deleted or made local, or the song was removed from it again.
            if (remoteId == null || dao.checkInPlaylist(entry.playlistId, entry.songId) == 0) {
                remove(entry.id)
                continue
            }
            val present =
                try {
                    // The offline fallback would answer from the local playlist, which already lists the song.
                    !entry.allowDuplicate && repository.playlistContainsSong(remoteId, entry.songId, allowOfflineFallback = false)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: PlaylistNotFoundException) {
                    remove(entry.id)
                    continue
                } catch (error: Exception) {
                    YtLogger.w(TAG, "Pending playlist add check failed type=${error::class.simpleName}")
                    return true
                }
            if (!present) {
                val failure =
                    repository
                        .addToPlaylist(
                            remoteId,
                            entry.songId,
                            allowDuplicate = true,
                            notifyChange = false,
                        ).exceptionOrNull()
                if (failure is PlaylistNotFoundException) {
                    remove(entry.id)
                    continue
                }
                if (failure != null) {
                    YtLogger.w(TAG, "Pending playlist add failed type=${failure::class.simpleName}")
                    if (failure.meansYouTubeUnreachable() || !PlaybackNetworkStateStore.current.connected) return true
                    postpone(entry.id)
                    continue
                }
            }
            remove(entry.id)
            PlaylistChanges.notifyChanged(remoteId)
        }
        val remaining = synchronized(lock) { read() }.filter { it.accountKey == accountKey }
        if (remaining.any { it.failures >= REPORT_AFTER_REJECTIONS }) {
            // The songs stay listed locally, so a lasting rejection must not look like a finished sync.
            YouTubeSyncState.failed(SYNC_OPERATION) { retryRejected() }
        } else {
            YouTubeSyncState.succeeded(SYNC_OPERATION)
        }
        return remaining.isNotEmpty()
    }

    private fun retryRejected() {
        synchronized(lock) { write(read().map { it.copy(failures = 0, nextAttemptAtMs = 0L) }) }
        additions.update { it + 1L }
    }

    private fun remove(id: Long) = synchronized(lock) { write(read().filterNot { it.id == id }) }

    // A rejected add backs off up to an hour instead of being dropped, so a YouTube outage cannot lose it.
    private fun postpone(id: Long) =
        synchronized(lock) {
            write(
                read().map { entry ->
                    if (entry.id != id) return@map entry
                    val failures = entry.failures + 1
                    val delay = (RETRY_INTERVAL * (1 shl (failures - 1).coerceIn(0, 6))).coerceAtMost(MAX_RETRY_INTERVAL)
                    entry.copy(failures = failures, nextAttemptAtMs = nowMs() + delay.inWholeMilliseconds)
                },
            )
        }

    private fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()

    private fun read(): List<PendingPlaylistAdd> =
        runCatching { json.decodeFromString<List<PendingPlaylistAdd>>(Settings.shared.pendingPlaylistAddsJson.ifBlank { "[]" }) }
            .getOrDefault(emptyList())

    private fun write(entries: List<PendingPlaylistAdd>) {
        Settings.shared.pendingPlaylistAddsJson = json.encodeToString(entries)
    }
}
