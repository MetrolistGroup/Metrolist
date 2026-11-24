package com.metrolist.shared.playlist

import androidx.compose.runtime.snapshotFlow
import com.metrolist.shared.api.PlaylistNotFoundException
import com.metrolist.shared.api.PlaylistSongAlreadyPresentException
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

@Serializable
internal data class PendingPlaylistAdd(
    val id: Long,
    val accountKey: String,
    val playlistId: String,
    val songId: String,
    val allowDuplicate: Boolean,
    val failures: Int = 0,
    val nextAttemptAtMs: Long = 0L,
    // Copies of the song YouTube should list once this add is through; set before the first send.
    val expectedCount: Int? = null,
)

/**
 * Songs added to a saved YouTube playlist. The local playlist lists them at once, and each entry stays queued until a
 * read of the playlist from YouTube lists as many copies of the song as the add expects.
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
    ): Long {
        val id =
            synchronized(lock) {
                val entries = read()
                // A random ID is never reissued, so a sync pass that still holds a cleared entry cannot touch a new one.
                val id = generateSequence { Random.nextLong(1L, Long.MAX_VALUE) }.first { candidate -> entries.none { it.id == candidate } }
                write(entries + PendingPlaylistAdd(id, currentLibraryAccountKey(), playlistId, songId, allowDuplicate))
                id
            }
        additions.update { it + 1L }
        return id
    }

    internal fun discard(id: Long) = remove(id)

    internal fun isQueued(
        playlistId: String,
        songId: String,
    ): Boolean = synchronized(lock) { read() }.any { it.playlistId == playlistId && it.songId == songId }

    // Drops one queued add of the song, preferring one never sent, so removing it from the playlist needs no item ID.
    internal fun cancel(
        playlistId: String,
        songId: String,
    ): Boolean =
        synchronized(lock) {
            val entries = read()
            val matching = entries.filter { it.playlistId == playlistId && it.songId == songId }
            val cancelled = matching.lastOrNull { it.expectedCount == null } ?: matching.lastOrNull()
            if (cancelled != null) write(entries - cancelled)
            cancelled != null
        }

    // Removing remote copies of a song lowers the copies its queued duplicates still wait for by the same number.
    internal fun lowerTargets(
        playlistId: String,
        songId: String,
        removedCopies: Int,
    ) = synchronized(lock) {
        write(
            read().map { entry ->
                val target = entry.expectedCount
                if (entry.playlistId != playlistId || entry.songId != songId || target == null) {
                    entry
                } else {
                    entry.copy(expectedCount = (target - removedCopies).coerceAtLeast(1))
                }
            },
        )
    }

    // Copies of each song the local playlist keeps beyond those YouTube lists until their queued adds are confirmed.
    internal fun unconfirmedCopies(
        playlistId: String,
        remoteCounts: Map<String, Int>,
    ): Map<String, Int> =
        synchronized(lock) { read() }
            .filter { entry ->
                val copies = remoteCounts[entry.songId] ?: 0
                entry.playlistId == playlistId && (entry.expectedCount?.let { copies < it } ?: (entry.allowDuplicate || copies == 0))
            }.groupingBy { it.songId }
            .eachCount()

    fun clear() {
        synchronized(lock) { write(emptyList()) }
        YouTubeSyncState.succeeded(SYNC_OPERATION)
    }

    private suspend fun syncWhileQueued(database: MusicDatabase) =
        coroutineScope {
            val wake = Channel<Unit>(Channel.CONFLATED)
            launch {
                // Switching back to an account with queued adds must wake the loop as well.
                combine(additions, PlaybackNetworkStateStore.state, snapshotFlow { currentLibraryAccountKey() }) { _, _, _ -> }
                    .collect { wake.trySend(Unit) }
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
                // A new addition, connection or account retries at once; unconfirmed songs retry every minute.
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
        val due = queued.filter { it.nextAttemptAtMs <= nowMs() }
        for ((playlistId, entries) in due.groupBy { it.playlistId }) {
            val remoteId = dao.findPlaylistEntity(playlistId)?.let { remotePlaylistId(it, playlistId) }
            // The playlist was deleted or made local, so nothing is left to send.
            if (remoteId == null) {
                entries.forEach { remove(it.id) }
                continue
            }
            // Checked under the add lock, so an add whose local row is not committed yet is never taken for removed.
            val active =
                playlistAddMutex.withLock {
                    entries.filter { entry ->
                        val listedLocally = dao.checkInPlaylist(playlistId, entry.songId) > 0
                        if (!listedLocally) remove(entry.id)
                        listedLocally
                    }
                }
            if (active.isEmpty()) continue
            val listed = remoteSongCounts(repository, remoteId)
            if (listed == null) {
                // A playlist YouTube cannot list waits on its own, so it does not hold back the other playlists.
                if (!PlaybackNetworkStateStore.current.connected) return true
                active.forEach { postpone(it.id) }
                continue
            }
            // Each queued copy of a song needs its own target, including adds postponed or sent in an earlier pass.
            val reserved =
                queued
                    .filter { it.playlistId == playlistId && it.expectedCount != null }
                    .groupBy { it.songId }
                    .mapValuesTo(mutableMapOf()) { (_, adds) -> adds.maxOf { checkNotNull(it.expectedCount) } }
            val sent = mutableListOf<Pair<PendingPlaylistAdd, Int>>()
            for (entry in active) {
                val copies = listed[entry.songId] ?: 0
                val expected =
                    entry.expectedCount ?: if (entry.allowDuplicate) maxOf(copies, reserved[entry.songId] ?: 0) + 1 else 1
                reserved[entry.songId] = maxOf(reserved[entry.songId] ?: 0, expected)
                // YouTube already lists the copies this add needs, from before or from an earlier send.
                if (copies >= expected) {
                    remove(entry.id)
                    continue
                }
                // Only the account that queued the add may send it.
                if (currentLibraryAccountKey() != entry.accountKey) return true
                // Claiming records the target before sending, so a lost response, a failed read-back or a later failure
                // cannot send it twice, and an add cancelled while the playlist was read is skipped.
                if (!claim(entry.id, expected)) continue
                val failure =
                    repository
                        .addToPlaylist(
                            remoteId,
                            entry.songId,
                            allowDuplicate = entry.allowDuplicate,
                            notifyChange = false,
                        ).exceptionOrNull()
                when {
                    failure == null -> {
                        listed[entry.songId] = copies + 1
                        sent += entry to expected
                    }

                    // Another add put the song there after the count, which is all an add without duplicates needs.
                    failure is PlaylistSongAlreadyPresentException -> {
                        remove(entry.id)
                    }

                    failure is PlaylistNotFoundException -> {
                        remove(entry.id)
                    }

                    failure.isHttpTransportException() || !PlaybackNetworkStateStore.current.connected -> {
                        return true
                    }

                    else -> {
                        YtLogger.w(TAG, "Pending playlist add failed type=${failure::class.simpleName}")
                        postpone(entry.id)
                    }
                }
            }
            if (sent.isEmpty()) continue
            // Reading the playlist back verifies the adds; one YouTube lists too few copies of is sent again later.
            val verified = remoteSongCounts(repository, remoteId)
            if (verified == null) {
                if (!PlaybackNetworkStateStore.current.connected) return true
                sent.forEach { (entry, _) -> postpone(entry.id) }
                continue
            }
            sent.forEach { (entry, expected) -> if ((verified[entry.songId] ?: 0) >= expected) remove(entry.id) else postpone(entry.id) }
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

    // Counts each song's copies in the playlist as YouTube lists it now, or returns null when YouTube could not be read.
    private suspend fun remoteSongCounts(
        repository: YouTubeRepository,
        remoteId: String,
    ): MutableMap<String, Int>? =
        try {
            val page =
                repository.completePlaylistPage(
                    repository.getPlaylistPageData(remoteId, applyContentFilters = false),
                    applyContentFilters = false,
                )
            // The offline fallback answers from the local playlist, which already lists the queued songs.
            if (page.isOffline) null else page.songs.groupingBy { it.id }.eachCountTo(mutableMapOf())
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            YtLogger.w(TAG, "Pending playlist read failed type=${error::class.simpleName}")
            null
        }

    private fun remove(id: Long) = synchronized(lock) { write(read().filterNot { it.id == id }) }

    private fun claim(
        id: Long,
        expectedCount: Int,
    ): Boolean =
        synchronized(lock) {
            val entries = read()
            val entry = entries.firstOrNull { it.id == id } ?: return@synchronized false
            if (entry.expectedCount == null) write(entries.map { if (it.id == id) it.copy(expectedCount = expectedCount) else it })
            true
        }

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
