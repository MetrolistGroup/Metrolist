package com.metrolist.shared.playlist

import androidx.compose.runtime.snapshotFlow
import com.metrolist.shared.api.PlaylistNotFoundException
import com.metrolist.shared.api.PlaylistSongAlreadyPresentException
import com.metrolist.shared.api.YouTubeRepository
import com.metrolist.shared.auth.YouTubeSyncState
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.db.entities.PlaylistSong
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.network.isHttpTransportException
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.state.GlobalYouTubeRepository
import com.metrolist.shared.viewmodels.currentLibraryAccountKey
import com.metrolist.shared.youtube.logging.YtLogger
import com.metrolist.shared.youtube.playback.PlaybackNetworkStateStore
import kotlinx.atomicfu.atomic
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
import kotlinx.coroutines.sync.Mutex
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

    // Counts the adds that left the queue, so a playlist snapshot fetched before one of them can be told apart.
    private val departures = atomic(0L)
    private val additions = MutableStateFlow(0L)
    private var boundDatabase: MusicDatabase? = null
    private var syncJob: Job? = null

    // A sync pass of a playlist and a removal on YouTube never overlap. So a removal cannot change the counts a pass sets
    // targets from, and a pass cannot send or confirm an add while a removal that cancels it is in flight.
    private val remoteEdits = Mutex()

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

    // Removes the selected songs, each with the row it matched, from the playlist on YouTube. A copy without an item ID is
    // still queued for YouTube, so it cancels an add of its song instead, one never sent first, which also works offline.
    // With no add of the song left, the sync confirmed it while the removal waited, so it takes a copy YouTube now lists
    // under an item ID no other row holds, and a row YouTube lists no such copy for only goes locally. The queue changes
    // only once the removal on YouTube succeeds, so a failed removal keeps every add queued. An add sent before but not
    // confirmed may already be on YouTube, and then returns with the next refresh.
    internal suspend fun removeFromPlaylist(
        database: MusicDatabase,
        repository: YouTubeRepository,
        remoteId: String,
        playlistId: String,
        selection: List<Pair<SongItem, PlaylistSong?>>,
    ) = remoteEdits.withLock {
        // Per song, an add never sent is cancelled first, then a sent one with the highest target, whose copy no other add counted.
        val adds =
            synchronized(lock) { read() }
                .filter { it.playlistId == playlistId }
                .sortedWith(compareBy<PendingPlaylistAdd> { it.expectedCount != null }.thenByDescending { it.expectedCount })
                .groupByTo(mutableMapOf()) { it.songId }
        val removals = mutableListOf<Pair<String, String>>()
        val cancelled = mutableSetOf<Long>()
        val confirmed = mutableListOf<String>()
        for ((song, row) in selection) {
            val itemId = song.playlistSetVideoId ?: row?.map?.setVideoId
            val add = if (itemId == null) adds[song.id]?.removeFirstOrNull() else null
            when {
                itemId != null -> removals += itemId to song.id
                add != null -> cancelled += add.id
                else -> confirmed += song.id
            }
        }
        if (confirmed.isNotEmpty()) {
            val removedRows = selection.mapNotNullTo(mutableSetOf()) { it.second?.map?.id }
            // A copy another row keeps, or one this removal already names, is not the one the sync confirmed.
            val held =
                database
                    .dao()
                    .playlistSongMaps(playlistId, Int.MIN_VALUE)
                    .filterNot { it.id in removedRows }
                    .mapNotNullTo(mutableSetOf()) { it.setVideoId } + removals.map { it.first }
            val unheld =
                checkNotNull(remoteSongs(repository, remoteId)) { "Missing playlist item identifier" }
                    .mapNotNullTo(mutableListOf()) { song -> song.playlistSetVideoId?.takeUnless(held::contains)?.let { it to song.id } }
            for (songId in confirmed) {
                unheld.firstOrNull { it.second == songId }?.let { item ->
                    unheld.remove(item)
                    removals += item
                }
            }
        }
        if (removals.isNotEmpty()) repository.removePlaylistSongs(remoteId, removals).getOrThrow()
        // Removing remote copies of a song lowers the copies its queued adds still wait for by the same number. An add that
        // waits for none after that had its own copy removed as well, so it goes instead of sending the song again.
        val removedCopies = removals.groupingBy { it.second }.eachCount()
        synchronized(lock) {
            write(
                read().filterNot { it.id in cancelled }.mapNotNull { entry ->
                    val target = entry.expectedCount
                    val removed = removedCopies[entry.songId]
                    when {
                        entry.playlistId != playlistId || target == null || removed == null -> entry
                        target > removed -> entry.copy(expectedCount = target - removed)
                        else -> null
                    }
                },
            )
        }
    }

    // Changes whenever an add leaves the queue. Read it before fetching a playlist snapshot and pass it to unconfirmedCopies.
    fun queueRevision(): Long = departures.value

    // Copies of each song the local playlist keeps beyond those YouTube lists until their queued adds are confirmed. Returns
    // null when an add left the queue since [fetchedAtRevision], because a snapshot fetched before then may lack its copy.
    internal fun unconfirmedCopies(
        playlistId: String,
        remoteCounts: Map<String, Int>,
        fetchedAtRevision: Long?,
    ): Map<String, Int>? =
        synchronized(lock) { read().takeIf { fetchedAtRevision == null || departures.value == fetchedAtRevision } }
            ?.filter { entry ->
                val copies = remoteCounts[entry.songId] ?: 0
                entry.playlistId == playlistId && (entry.expectedCount?.let { copies < it } ?: (entry.allowDuplicate || copies == 0))
            }?.groupingBy { it.songId }
            ?.eachCount()

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
        // Each read and send checks the account again once its session is fixed, so none runs under another account.
        val isAccountCurrent = { currentLibraryAccountKey() == accountKey }
        val queued = queuedFor(accountKey)
        if (queued.isEmpty()) return false
        if (!PlaybackNetworkStateStore.current.connected || !repository.canManagePlaylists()) return true
        val dao = database.dao()
        for (playlistId in queued.filter { it.nextAttemptAtMs <= nowMs() }.map { it.playlistId }.distinct()) {
            val remoteId = dao.findPlaylistEntity(playlistId)?.let { remotePlaylistId(it, playlistId) }
            remoteEdits.withLock {
                // Read again under the lock, since a removal just before it can cancel adds and lower their targets.
                val waiting = queuedFor(accountKey).filter { it.playlistId == playlistId }
                // The playlist was deleted or made local, so nothing is left to send.
                if (remoteId == null) {
                    waiting.forEach { remove(it.id) }
                    continue
                }
                val due = waiting.filter { it.nextAttemptAtMs <= nowMs() }
                // Checked under the add lock, so an add whose local row is not committed yet is never taken for removed.
                val active =
                    playlistAddMutex.withLock {
                        due.filter { entry ->
                            val listedLocally = dao.checkInPlaylist(playlistId, entry.songId) > 0
                            if (!listedLocally) remove(entry.id)
                            listedLocally
                        }
                    }
                if (active.isEmpty()) continue
                val listed = remoteSongCounts(repository, remoteId, isAccountCurrent)
                if (listed == null) {
                    // A playlist YouTube cannot list waits on its own, so it does not hold back the other playlists.
                    if (!PlaybackNetworkStateStore.current.connected) return true
                    active.forEach { postpone(it.id) }
                    continue
                }
                // Each queued copy of a song needs its own target, including adds postponed or sent in an earlier pass.
                val reserved =
                    waiting
                        .filter { it.expectedCount != null }
                        .groupBy { it.songId }
                        .mapValuesTo(mutableMapOf()) { (_, adds) -> adds.maxOf { checkNotNull(it.expectedCount) } }
                val sent = mutableListOf<Pair<PendingPlaylistAdd, Int>>()
                var confirmed = false
                for (entry in active) {
                    val copies = listed[entry.songId] ?: 0
                    val expected =
                        entry.expectedCount ?: if (entry.allowDuplicate) maxOf(copies, reserved[entry.songId] ?: 0) + 1 else 1
                    reserved[entry.songId] = maxOf(reserved[entry.songId] ?: 0, expected)
                    // YouTube already lists the copies this add needs, from before or from an earlier send.
                    if (copies >= expected) {
                        remove(entry.id)
                        confirmed = true
                        continue
                    }
                    // Only the account that queued the add may send it.
                    if (!isAccountCurrent()) return true
                    // Claiming records the target before sending, so a lost response, a failed read-back or a later failure
                    // cannot send it twice, and an add a sign-out cleared meanwhile is skipped.
                    if (!claim(entry.id, expected)) continue
                    val failure =
                        repository
                            .addToPlaylist(
                                remoteId,
                                entry.songId,
                                allowDuplicate = entry.allowDuplicate,
                                notifyChange = false,
                                isAccountCurrent = isAccountCurrent,
                            ).exceptionOrNull()
                    when {
                        failure == null -> {
                            listed[entry.songId] = copies + 1
                            sent += entry to expected
                        }

                        // Another add put the song there after the count, which is all an add without duplicates needs.
                        failure is PlaylistSongAlreadyPresentException -> {
                            remove(entry.id)
                            confirmed = true
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
                if (sent.isNotEmpty()) {
                    // Reading the playlist back verifies the adds; one YouTube lists too few copies of is sent again later.
                    val verified = remoteSongCounts(repository, remoteId, isAccountCurrent)
                    if (verified == null && !PlaybackNetworkStateStore.current.connected) return true
                    sent.forEach { (entry, expected) ->
                        if ((verified?.get(entry.songId) ?: 0) >= expected) remove(entry.id) else postpone(entry.id)
                    }
                }
                // Open playlists reload after a send or a confirmation, so a confirmed copy gets the item ID YouTube lists.
                if (confirmed || sent.isNotEmpty()) PlaylistChanges.notifyChanged(remoteId)
            }
        }
        val remaining = queuedFor(accountKey)
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

    // Lists the playlist's songs as YouTube lists them now, or returns null when YouTube could not be read.
    private suspend fun remoteSongs(
        repository: YouTubeRepository,
        remoteId: String,
        isAccountCurrent: () -> Boolean = { true },
    ): List<SongItem>? =
        try {
            val page =
                repository.completePlaylistPage(
                    repository.getPlaylistPageData(remoteId, applyContentFilters = false, isAccountCurrent),
                    applyContentFilters = false,
                )
            // The offline fallback answers from the local playlist, which already lists the queued songs.
            page.songs.takeUnless { page.isOffline }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            YtLogger.w(TAG, "Pending playlist read failed type=${error::class.simpleName}")
            null
        }

    // Counts each song's copies in the playlist as YouTube lists it now, or returns null when YouTube could not be read.
    private suspend fun remoteSongCounts(
        repository: YouTubeRepository,
        remoteId: String,
        isAccountCurrent: () -> Boolean,
    ): MutableMap<String, Int>? = remoteSongs(repository, remoteId, isAccountCurrent)?.groupingBy { it.id }?.eachCountTo(mutableMapOf())

    private fun queuedFor(accountKey: String) = synchronized(lock) { read() }.filter { it.accountKey == accountKey }

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
        val kept = entries.mapTo(HashSet()) { it.id }
        if (read().any { it.id !in kept }) departures.incrementAndGet()
        Settings.shared.pendingPlaylistAddsJson = json.encodeToString(entries)
    }
}
