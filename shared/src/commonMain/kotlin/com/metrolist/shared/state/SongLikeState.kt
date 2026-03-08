/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.metrolist.innertubex.InnerTube
import com.metrolist.shared.api.PlaylistPage
import com.metrolist.shared.api.SongVote
import com.metrolist.shared.api.YouTubeRepository
import com.metrolist.shared.auth.YouTubeSyncState
import com.metrolist.shared.auth.supportedYouTubeAuthCredential
import com.metrolist.shared.deviceconnect.DeviceCrypto
import com.metrolist.shared.deviceconnect.toBase64
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.moveSelection
import com.metrolist.shared.settings.BlockedContent
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.viewmodels.LIBRARY_REFRESH_TTL_MILLIS
import com.metrolist.shared.youtube.logging.YtLogger
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.ReentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlin.time.Clock

object SongLikeState {
    private const val TAG = "SongLikeState"

    private val scope =
        CoroutineScope(
            Dispatchers.Default + SupervisorJob() +
                CoroutineExceptionHandler { _, error -> YtLogger.e(TAG, "Background like sync failed", error) },
        )
    private val _newLikes = MutableSharedFlow<SongItem>(extraBufferCapacity = 16)
    private val _likedSongsFlow = MutableStateFlow(loadLikedSongs())
    var likedSongRecency by mutableStateOf(loadLikedSongRecency())
        private set
    private val remoteSyncMutex = Mutex()

    private data class RemoteRefresh(
        val session: InnerTube.SessionSnapshot,
        val account: String,
        val generation: Long,
        val localSnapshot: List<SongItem>,
        val voteRevision: Long,
        val completion: CompletableDeferred<Boolean> = CompletableDeferred(),
        val removedIds: MutableSet<String> = mutableSetOf(),
        val likedIds: MutableSet<String> = mutableSetOf(),
    )

    private var activeRefresh: RemoteRefresh? = null

    private data class SuccessfulRefresh(
        val session: InnerTube.SessionSnapshot,
        val account: String,
        val completedAt: Long,
    )

    private var successfulRefresh: SuccessfulRefresh? = null
    private var remotePreview by mutableStateOf<Map<String, SongItem>?>(null)
    val hasPartialLikes: Boolean get() = remotePreview != null
    val likedSongsForDisplay: List<SongItem>
        get() = remotePreview?.let { (likedSongs + it.values).distinctBy(SongItem::id) } ?: likedSongs

    var isRefreshing by mutableStateOf(false)
        private set
    private val voteSyncMutex = Mutex()
    private var importVoteChanges: MutableSet<String>? = null
    private val voteRevision = atomic(0L)
    private val remoteSyncGate = ReentrantLock()
    private val remoteSyncGeneration = LatestCallGate()
    private val persistenceGate = ReentrantLock()
    private val persistenceRevision = atomic(0L)
    private var customOrdersSettings: Settings? = null
    private var customOrdersCache: Map<String, List<String>>? = null
    private var customOrderUpdates = emptyMap<String, List<SongItem>?>()
    private var pendingWrite = CompletableDeferred(Unit)
    private val snapshotWriter = SongLikeSnapshotWriter(scope, ::persistSnapshot)
    val newLikes = _newLikes.asSharedFlow()
    val likedSongsFlow: StateFlow<List<SongItem>> = _likedSongsFlow.asStateFlow()

    var likedSongs by mutableStateOf(_likedSongsFlow.value)
        private set

    var likedSongIds: Set<String> by mutableStateOf(_likedSongsFlow.value.mapTo(hashSetOf(), SongItem::id))
        private set

    val likedSongIdsForDisplay: Flow<Set<String>> = snapshotFlow { likedSongIds + remotePreview.orEmpty().keys }

    var dislikedSongIds by mutableStateOf(loadDislikedSongIds())
        private set

    fun initialize() = Unit

    fun refreshFromSettings() =
        remoteSyncGate.withLock {
            remoteSyncGeneration.begin()
            successfulRefresh = null
            remotePreview = null
            voteRevision.incrementAndGet()
            persistenceGate.withLock {
                persistenceRevision.incrementAndGet()
                customOrdersSettings = null
                customOrdersCache = null
                customOrderUpdates = emptyMap()
                likedSongRecency = loadLikedSongRecency()
                updateLikedSongs(loadLikedSongs())
                dislikedSongIds = loadDislikedSongIds()
            }
        }

    private fun loadLikedSongs(): List<SongItem> {
        val jsonString = Settings.shared.likedSongsJson
        return try {
            Json.decodeFromString<List<SongItem>>(jsonString)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun loadLikedSongRecency(): List<String> =
        runCatching { Json.decodeFromString<List<String>>(Settings.shared.likedSongsRecencyJson) }
            .getOrDefault(emptyList())
            .ifEmpty { loadLikedSongs().map(SongItem::id) }

    private fun updateLikedSongRecency(songs: List<SongItem>) {
        val ids = songs.mapTo(linkedSetOf(), SongItem::id)
        val retained = likedSongRecency.filter { it in ids }
        val retainedIds = retained.toHashSet()
        likedSongRecency = ids.filterNot { it in retainedIds } + retained
    }

    private fun loadCustomOrders(): Map<String, List<String>> {
        val settings = Settings.shared
        if (customOrdersSettings !== settings) {
            persistenceRevision.incrementAndGet()
            customOrdersSettings = settings
            customOrdersCache = null
            customOrderUpdates = emptyMap()
        }
        return customOrdersCache ?: runCatching {
            Json.decodeFromString<Map<String, List<String>>>(settings.likedSongsCustomOrdersJson)
        }.getOrDefault(emptyMap()).also { customOrdersCache = it }
    }

    private fun savedCustomOrder(account: String): List<String>? =
        if (account in customOrderUpdates) customOrderUpdates[account]?.map(SongItem::id) else loadCustomOrders()[account]

    private fun currentOrderAccount(settings: Settings = Settings.shared): String {
        val authUser = settings.innerTubeAuthUser
        val dataSyncId = settings.dataSyncId
        if (dataSyncId.isNotBlank()) return "$dataSyncId|$authUser"
        val credential = supportedYouTubeAuthCredential(settings.innerTubeCookie)
        val identity =
            credential?.let {
                DeviceCrypto
                    .hmacSha256(it.encodeToByteArray(), LIKED_ORDER_ACCOUNT_LABEL.encodeToByteArray())
                    .toBase64()
            } ?: "signed-out"
        return "$identity|$authUser"
    }

    fun activateCurrentAccount() =
        remoteSyncGate.withLock {
            activateOrderAccount()
            Unit
        }

    private fun activateOrderAccount(): Boolean {
        val current = currentOrderAccount()
        val previous = Settings.shared.likedSongsOrderAccount
        loadCustomOrders()
        if (previous.isBlank()) {
            Settings.shared.likedSongsOrderAccount = current
            if (Settings.shared.likedSongsOrderCustomized && savedCustomOrder(current) == null) {
                saveLikedSongs()
            }
            return false
        }
        if (previous == current) return false

        remoteSyncGeneration.begin()
        successfulRefresh = null
        remotePreview = null
        Settings.shared.pendingSongVotesJson = "{}"
        voteRevision.incrementAndGet()
        customOrdersCache = null
        val savedOrder = savedCustomOrder(current)
        updateLikedSongs(emptyList())
        persistenceGate.withLock {
            persistenceRevision.incrementAndGet()
            Settings.shared.likedSongsOrderAccount = current
            Settings.shared.likedSongsOrderCustomized = savedOrder != null
            Settings.shared.likedSongsJson = "[]"
            Settings.shared.likedSongsRecencyJson = "[]"
            Settings.shared.likedSongIds = ""
        }
        enqueueSnapshot()
        return true
    }

    private fun saveLikedSongs() {
        val account = Settings.shared.likedSongsOrderAccount
        customOrderUpdates = customOrderUpdates + (account to _likedSongsFlow.value.takeIf { Settings.shared.likedSongsOrderCustomized })
        enqueueSnapshot()
    }

    private fun enqueueSnapshot() {
        val orders = loadCustomOrders()
        pendingWrite =
            snapshotWriter.submit(
                SongLikeSnapshot(
                    Settings.shared,
                    Settings.shared.likedSongsOrderAccount,
                    persistenceRevision.incrementAndGet(),
                    _likedSongsFlow.value,
                    orders,
                    customOrderUpdates,
                    likedSongRecency,
                ),
            )
    }

    private fun persistSnapshot(snapshot: SongLikeSnapshot) {
        try {
            if (persistenceRevision.value != snapshot.revision) return
            val songsJson = Json.encodeToString(snapshot.songs)
            val recencyJson = Json.encodeToString(snapshot.recency)
            val songIds = snapshot.songs.joinToString(",", transform = SongItem::id)
            val orders = snapshot.customOrders.toMutableMap()
            snapshot.customOrderUpdates.forEach { (account, songs) ->
                if (songs == null) orders.remove(account) else orders[account] = songs.map(SongItem::id)
            }
            val ordersJson = Json.encodeToString(orders)
            persistenceGate.withLock {
                if (persistenceRevision.value != snapshot.revision || Settings.shared !== snapshot.settings ||
                    snapshot.settings.likedSongsOrderAccount != snapshot.account ||
                    currentOrderAccount(snapshot.settings) != snapshot.account
                ) {
                    return
                }
                snapshot.settings.likedSongsJson = songsJson
                snapshot.settings.likedSongsRecencyJson = recencyJson
                snapshot.settings.likedSongIds = songIds
                snapshot.settings.likedSongsCustomOrdersJson = ordersJson
            }
            remoteSyncGate.withLock {
                if (persistenceRevision.value == snapshot.revision && Settings.shared === snapshot.settings) {
                    customOrdersCache = orders
                    customOrderUpdates = emptyMap()
                }
            }
        } catch (_: Exception) {
        }
    }

    internal suspend fun awaitPersistence() = remoteSyncGate.withLock { pendingWrite }.await()

    private fun updateLikedSongs(
        songs: List<SongItem>,
        ids: Set<String> = songs.mapTo(hashSetOf(), SongItem::id),
    ) {
        updateLikedSongRecency(songs)
        _likedSongsFlow.value = songs
        likedSongs = songs
        likedSongIds = ids
    }

    private fun loadDislikedSongIds(): Set<String> =
        Settings.shared.dislikedSongIds
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()

    private fun saveDislikedSongIds() {
        Settings.shared.dislikedSongIds = dislikedSongIds.joinToString(",")
    }

    fun isLiked(songId: String): Boolean = songId.isNotBlank() && (songId in likedSongIds || remotePreview?.containsKey(songId) == true)

    fun isDisliked(songId: String): Boolean = songId.isNotBlank() && songId in dislikedSongIds

    internal fun replaceLikesFromRemote(
        songs: List<SongItem>,
        localSnapshot: List<SongItem> = _likedSongsFlow.value,
    ) = remoteSyncGate.withLock {
        val accountChanged = activateOrderAccount()
        val savedOrder =
            if (Settings.shared.likedSongsOrderCustomized) savedCustomOrder(Settings.shared.likedSongsOrderAccount) else null
        val pending = pendingVotes().toMutableMap()
        val locallyLikedIds = pending.filterValues { it == SongVote.LIKE }.keys + activeRefresh?.likedIds.orEmpty()
        val remoteIds = songs.mapTo(mutableSetOf(), SongItem::id)
        pending.entries.removeAll { (id, vote) ->
            (vote == SongVote.LIKE && id in remoteIds) || (vote == SongVote.INDIFFERENT && id !in remoteIds)
        }
        savePendingVotes(pending)
        songs.filter { pending[it.id] == null }.forEach { removeDislike(it.id, sync = false) }
        val pendingLikes = _likedSongsFlow.value.filter { pending[it.id] == SongVote.LIKE }
        val reconciledRemote = (pendingLikes + songs.filter { pending[it.id] == null }).distinctBy(SongItem::id)
        val snapshotIds = localSnapshot.mapTo(hashSetOf(), SongItem::id)
        val concurrentLikes =
            likedSongRecency
                .filter { it !in snapshotIds || it in locallyLikedIds }
                .takeUnless { accountChanged }
                .orEmpty()
        likedSongRecency =
            if (accountChanged) {
                reconciledRemote
            } else {
                reconcileLikedSongsAfterRefresh(
                    localSnapshot,
                    _likedSongsFlow.value,
                    reconciledRemote,
                )
            }.map(SongItem::id)
        val concurrentIds = concurrentLikes.toHashSet()
        likedSongRecency = concurrentLikes + likedSongRecency.filterNot { it in concurrentIds }
        if (reconciledRemote.isEmpty()) Settings.shared.likedSongsOrderCustomized = false
        updateLikedSongs(
            when {
                accountChanged -> {
                    reconciledRemote.applyCustomOrder(savedOrder)
                }

                Settings.shared.likedSongsOrderCustomized -> {
                    reconcileLikedSongsAfterRefresh(
                        localSnapshot,
                        _likedSongsFlow.value,
                        reconciledRemote.applyCustomOrder(savedOrder),
                        preserveLocalOrder = true,
                    )
                }

                else -> {
                    reconcileLikedSongsAfterRefresh(localSnapshot, _likedSongsFlow.value, reconciledRemote)
                }
            },
        )
        saveLikedSongs()
    }

    fun replaceLikesLocally(
        songs: List<SongItem>,
        orderCustomized: Boolean? = null,
    ) = remoteSyncGate.withLock {
        activateOrderAccount()
        successfulRefresh = null
        voteRevision.incrementAndGet()
        Settings.shared.likedSongsOrderCustomized = orderCustomized ?: Settings.shared.likedSongsOrderCustomized
        updateLikedSongs(songs.distinctBy(SongItem::id))
        saveLikedSongs()
    }

    fun moveLike(
        songId: String,
        targetSongId: String,
        selectedSongIds: Set<String> = emptySet(),
    ) = remoteSyncGate.withLock {
        activateOrderAccount()
        val reordered = moveLikedSong(_likedSongsFlow.value, songId, targetSongId, selectedSongIds)
        if (reordered != _likedSongsFlow.value) {
            Settings.shared.likedSongsOrderCustomized = true
            updateLikedSongs(reordered, likedSongIds)
            saveLikedSongs()
        }
    }

    internal fun beginRemoteRefresh(): Pair<Long, List<SongItem>> =
        remoteSyncGate.withLock {
            activateOrderAccount()
            remoteSyncGeneration.begin() to _likedSongsFlow.value
        }

    internal fun applyRemoteRefresh(
        generation: Long,
        songs: List<SongItem>,
        localSnapshot: List<SongItem>,
    ): Boolean =
        remoteSyncGate.withLock {
            activateOrderAccount()
            if (!remoteSyncGeneration.isCurrent(generation)) return false
            replaceLikesFromRemote(songs, localSnapshot)
            true
        }

    suspend fun refreshLikesFromRemote(
        force: Boolean = true,
        nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
        loadSongs: suspend (onProgress: (List<SongItem>) -> Unit) -> List<SongItem>? = { onProgress ->
            if (GlobalYouTubeRepository.instance.isYouTubeHistoryAvailable()) {
                loadLikedSongPages(
                    GlobalYouTubeRepository.instance.getPlaylistPages("VLLM", applyContentFilters = false),
                    onProgress,
                )
            } else {
                null
            }
        },
    ): Boolean {
        val requestSession = GlobalInnerTube.instance.sessionSnapshot()
        val requestAccount = remoteSyncGate.withLock { currentOrderAccount() }
        while (true) {
            currentCoroutineContext().ensureActive()
            val (refresh, ownsRefresh) =
                remoteSyncGate.withLock {
                    activateOrderAccount()
                    val account = currentOrderAccount()
                    if (account != requestAccount || GlobalInnerTube.instance.sessionSnapshot() != requestSession) return false
                    val fresh =
                        successfulRefresh?.let {
                            !force && it.session == requestSession && it.account == account &&
                                nowMs() - it.completedAt in 0 until LIBRARY_REFRESH_TTL_MILLIS && pendingVotes().isEmpty()
                        } == true
                    if (fresh) return@withLock null to false
                    val active = activeRefresh
                    if (active?.session == requestSession && active.account == account &&
                        remoteSyncGeneration.isCurrent(active.generation)
                    ) {
                        active to false
                    } else {
                        val (generation, snapshot) = beginRemoteRefresh()
                        successfulRefresh = null
                        val refresh = RemoteRefresh(requestSession, account, generation, snapshot, voteRevision.value)
                        activeRefresh = refresh
                        remotePreview = emptyMap<String, SongItem>().takeIf { snapshot.isEmpty() }
                        isRefreshing = true
                        refresh to true
                    }
                }
            if (refresh == null) {
                syncPendingVotes()
                return true
            }
            if (!ownsRefresh) {
                try {
                    return refresh.completion.await()
                } catch (e: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    continue
                }
            }
            try {
                val result =
                    remoteSyncMutex.withLock {
                        if (!remoteSyncGeneration.isCurrent(refresh.generation)) return@withLock false
                        try {
                            syncPendingVotes()
                            val remoteSongs =
                                withContext(Dispatchers.Default) {
                                    loadSongs { songs ->
                                        remoteSyncGate.withLock {
                                            activateOrderAccount()
                                            if (remoteSyncGeneration.isCurrent(refresh.generation) &&
                                                GlobalInnerTube.instance.sessionSnapshot() == requestSession &&
                                                refresh.localSnapshot.isEmpty()
                                            ) {
                                                val pending = pendingVotes()
                                                remotePreview =
                                                    songs
                                                        .filter {
                                                            it.id !in refresh.removedIds &&
                                                                pending[it.id].let { vote -> vote == null || vote == SongVote.LIKE }
                                                        }.associateBy(SongItem::id)
                                            }
                                        }
                                    }
                                } ?: run {
                                    notifySyncFailure("likes", requestSession)
                                    return@withLock false
                                }
                            remoteSyncGate.withLock {
                                currentCoroutineContext().ensureActive()
                                val applied =
                                    GlobalInnerTube.instance.sessionSnapshot() == requestSession &&
                                        applyRemoteRefresh(
                                            refresh.generation,
                                            remoteSongs.filterNot { it.id in refresh.removedIds },
                                            refresh.localSnapshot,
                                        )
                                if (applied && voteRevision.value == refresh.voteRevision && pendingVotes().isEmpty()) {
                                    successfulRefresh = SuccessfulRefresh(requestSession, refresh.account, nowMs())
                                    YouTubeSyncState.succeeded("likes")
                                }
                                applied
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            YtLogger.w(TAG, "Unable to refresh liked songs")
                            notifySyncFailure("likes", requestSession)
                            false
                        }
                    }
                refresh.completion.complete(result)
                return result
            } finally {
                remoteSyncGate.withLock {
                    if (activeRefresh === refresh) {
                        activeRefresh = null
                        remotePreview = null
                        isRefreshing = false
                    }
                }
                refresh.completion.cancel()
            }
        }
    }

    suspend fun awaitCompleteLikes(): List<SongItem>? {
        val refresh = remoteSyncGate.withLock { activeRefresh?.takeIf { it.localSnapshot.isEmpty() } }
        if (refresh == null) return likedSongs
        try {
            if (!refresh.completion.await()) return null
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            return null
        }
        return remoteSyncGate.withLock {
            likedSongs.takeIf {
                remoteSyncGeneration.isCurrent(refresh.generation) && currentOrderAccount() == refresh.account &&
                    GlobalInnerTube.instance.sessionSnapshot() == refresh.session
            }
        }
    }

    fun refreshLikesFromRemoteAsync() {
        scope.launch { refreshLikesFromRemote(force = false) }
    }

    fun toggleLike(song: SongItem): Boolean =
        remoteSyncGate.withLock {
            activateOrderAccount()
            val currentList = _likedSongsFlow.value.toMutableList()
            val index = currentList.indexOfFirst { it.id == song.id }
            val liked = !isLiked(song.id)

            if (liked) {
                currentList.add(0, song)
                removeDislike(song.id, sync = false)
            } else if (index >= 0) {
                currentList.removeAt(index)
            }

            updateLikedSongs(currentList)
            saveLikedSongs()
            syncLike(song.id, liked)
            if (liked) _newLikes.tryEmit(song)
            return liked
        }

    fun addLike(song: SongItem): Boolean = addLikes(listOf(song)) > 0

    fun addLikes(songs: List<SongItem>): Int =
        remoteSyncGate.withLock {
            activateOrderAccount()
            val ids = _likedSongsFlow.value.mapTo(hashSetOf(), SongItem::id)
            val added = songs.filter { it.id.isNotBlank() && ids.add(it.id) }
            if (added.isEmpty()) return 0
            updateLikedSongs(added.asReversed() + _likedSongsFlow.value, ids)
            added.forEach { removeDislike(it.id, sync = false) }
            saveLikedSongs()
            queueVotes(added.associate { it.id to SongVote.LIKE })
            scope.launch { added.forEach { _newLikes.emit(it) } }
            added.size
        }

    internal suspend fun <T> withImportVoteSync(block: suspend () -> T): T =
        voteSyncMutex.withLock {
            remoteSyncGate.withLock { importVoteChanges = mutableSetOf() }
            try {
                block()
            } finally {
                remoteSyncGate.withLock { importVoteChanges = null }
            }
        }

    fun appendLikes(
        songs: List<SongItem>,
        account: String = Settings.shared.likedSongsOrderAccount,
    ) = remoteSyncGate.withLock {
        activateOrderAccount()
        if (Settings.shared.likedSongsOrderAccount != account) return@withLock
        val acknowledged = songs.filterNot { importVoteChanges?.contains(it.id) == true }
        val currentList = _likedSongsFlow.value.toMutableList()
        val existingIds = currentList.mapTo(HashSet()) { it.id }
        val toAdd = acknowledged.filter { existingIds.add(it.id) }
        if (toAdd.isNotEmpty()) {
            successfulRefresh = null
            voteRevision.incrementAndGet()
            currentList.addAll(toAdd)
            updateLikedSongs(currentList, existingIds)
            toAdd.forEach { removeDislike(it.id, sync = false) }
            saveLikedSongs()
        }
        val votes = acknowledged.filterNot(SongItem::isLocal).associate { it.id to SongVote.LIKE }
        if (votes.isNotEmpty()) queueVotes(votes, sync = false)
    }

    fun removeLike(songId: String): Boolean = removeLikes(listOf(songId)) > 0

    fun removeLikes(songIds: Collection<String>): Int =
        remoteSyncGate.withLock {
            activateOrderAccount()
            val ids = songIds.toHashSet()
            val removed = likedSongsForDisplay.filter { it.id in ids }
            if (removed.isEmpty()) return 0
            updateLikedSongs(_likedSongsFlow.value.filterNot { it.id in ids })
            saveLikedSongs()
            queueVotes(removed.associate { it.id to SongVote.INDIFFERENT })
            removed.size
        }

    fun dislike(song: SongItem): Boolean =
        remoteSyncGate.withLock {
            activateOrderAccount()
            if (song.id.isBlank()) return false
            if (isDisliked(song.id)) return false

            updateLikedSongs(_likedSongsFlow.value.filterNot { it.id == song.id })
            saveLikedSongs()

            dislikedSongIds = dislikedSongIds + song.id
            saveDislikedSongIds()
            BlockedContent.rememberDislikedSong(song)
            syncDislike(song.id, disliked = true)
            return true
        }

    fun toggleDislike(song: SongItem): Boolean {
        if (isDisliked(song.id)) {
            removeDislike(song.id)
            return false
        }
        return dislike(song)
    }

    fun removeDislike(
        songId: String,
        sync: Boolean = true,
    ): Boolean {
        if (songId.isBlank() || !isDisliked(songId)) return false
        dislikedSongIds = dislikedSongIds - songId
        saveDislikedSongIds()
        BlockedContent.unblockSong(songId)
        if (sync) syncDislike(songId, disliked = false)
        return true
    }

    internal fun voteObservationRevision(): Long = voteRevision.value

    internal fun reconcileRemoteVotes(
        votes: Map<String, SongVote>,
        revision: Long,
        dislikedSongs: Map<String, SongItem> = emptyMap(),
    ) = remoteSyncGate.withLock {
        if (votes.isEmpty() || voteRevision.value != revision) return@withLock
        val previousDislikedSongIds = dislikedSongIds
        val pending = pendingVotes().toMutableMap()
        votes.forEach { (id, vote) ->
            if (pending[id] != null && pending[id] != vote) return@forEach
            if (pending[id] == SongVote.DISLIKE) pending.remove(id)
            when (vote) {
                SongVote.DISLIKE -> {
                    dislikedSongIds = dislikedSongIds + id
                    if (BlockedContent.songs().none { it.id == id && it.title != id }) {
                        (_likedSongsFlow.value.firstOrNull { it.id == id } ?: dislikedSongs[id])
                            ?.let(BlockedContent::rememberDislikedSong)
                    }
                    updateLikedSongs(_likedSongsFlow.value.filterNot { it.id == id })
                }

                SongVote.INDIFFERENT -> {
                    removeDislike(id, sync = false)
                    updateLikedSongs(_likedSongsFlow.value.filterNot { it.id == id })
                }

                SongVote.LIKE -> {
                    removeDislike(id, sync = false)
                }
            }
        }
        savePendingVotes(pending)
        saveLikedSongs()
        saveDislikedSongIds()
        if (dislikedSongIds != previousDislikedSongIds) BlockedContent.contentFiltersChanged()
    }

    private fun pendingVotes(): Map<String, SongVote> =
        runCatching { Json.decodeFromString<Map<String, SongVote>>(Settings.shared.pendingSongVotesJson) }.getOrDefault(emptyMap())

    private fun savePendingVotes(votes: Map<String, SongVote>) {
        Settings.shared.pendingSongVotesJson = Json.encodeToString(votes)
    }

    private fun syncLike(
        songId: String,
        liked: Boolean,
    ) = queueVote(songId, if (liked) SongVote.LIKE else SongVote.INDIFFERENT)

    private fun syncDislike(
        songId: String,
        disliked: Boolean,
    ) = queueVote(songId, if (disliked) SongVote.DISLIKE else SongVote.INDIFFERENT)

    private fun queueVote(
        songId: String,
        vote: SongVote,
    ) = queueVotes(mapOf(songId to vote))

    private fun queueVotes(
        votes: Map<String, SongVote>,
        sync: Boolean = true,
    ) {
        remoteSyncGate.withLock {
            importVoteChanges?.addAll(votes.keys)
            successfulRefresh = null
            voteRevision.incrementAndGet()
            activeRefresh?.let { refresh ->
                votes.forEach { (id, vote) ->
                    if (vote == SongVote.LIKE) {
                        refresh.removedIds.remove(id)
                        refresh.likedIds.add(id)
                    } else {
                        refresh.removedIds.add(id)
                        refresh.likedIds.remove(id)
                    }
                }
            }
            remotePreview = remotePreview?.filterKeys { votes[it].let { vote -> vote == null || vote == SongVote.LIKE } }
            savePendingVotes(pendingVotes() + votes)
        }
        if (sync) scope.launch { syncPendingVotes() }
    }

    private fun notifySyncFailure(
        operation: String,
        session: InnerTube.SessionSnapshot,
    ) {
        if (GlobalInnerTube.instance.sessionSnapshot() != session || !GlobalInnerTube.instance.hasSapCookieAuth(session)) return
        YouTubeSyncState.failed(operation) {
            if (GlobalInnerTube.instance.sessionSnapshot() == session) {
                if (operation == "votes") syncPendingVotes() else refreshLikesFromRemote(force = true)
            }
        }
    }

    internal suspend fun syncPendingVotes(repository: YouTubeRepository = GlobalYouTubeRepository.instance) =
        voteSyncMutex.withLock {
            val account = Settings.shared.likedSongsOrderAccount
            val requestSession = GlobalInnerTube.instance.sessionSnapshot()
            val pending = remoteSyncGate.withLock { pendingVotes() }
            if (pending.isEmpty() || !runCatching { repository.isYouTubeHistoryAvailable() }.getOrDefault(false)) {
                return@withLock
            }
            var allSucceeded = true
            pending.forEach { (id, vote) ->
                if (Settings.shared.likedSongsOrderAccount != account) return@withLock
                if (remoteSyncGate.withLock { pendingVotes()[id] } != vote) return@forEach
                try {
                    val succeeded =
                        when (vote) {
                            SongVote.LIKE -> repository.likeVideo(id)
                            SongVote.DISLIKE -> repository.dislikeVideo(id)
                            SongVote.INDIFFERENT -> repository.unlikeVideo(id)
                        }
                    if (!succeeded) {
                        allSucceeded = false
                        notifySyncFailure("votes", requestSession)
                    }
                    if (succeeded && vote == SongVote.DISLIKE) {
                        remoteSyncGate.withLock {
                            if (Settings.shared.likedSongsOrderAccount == account && pendingVotes()[id] == vote) {
                                savePendingVotes(pendingVotes() - id)
                            }
                        }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    YtLogger.w(TAG, "Unable to sync song vote")
                    allSucceeded = false
                    notifySyncFailure("votes", requestSession)
                }
            }
            if (allSucceeded) YouTubeSyncState.succeeded("votes")
        }
}

internal suspend fun loadLikedSongPages(
    pages: Flow<PlaylistPage>,
    onProgress: (List<SongItem>) -> Unit,
): List<SongItem>? {
    val songs = linkedMapOf<String, SongItem>()
    var last: PlaylistPage? = null
    var hasSections = false
    pages.collect { page ->
        check(!page.isOffline) { "Liked songs are only available offline" }
        hasSections = hasSections || page.sections.isNotEmpty()
        page.songs.forEach { songs.getOrPut(it.id) { it } }
        last = page
        onProgress(songs.values.toList())
    }
    check(last?.hasMore != true) { "Liked songs are incomplete" }
    return songs.values.toList().takeIf { hasSections }
}

internal data class SongLikeSnapshot(
    val settings: Settings,
    val account: String,
    val revision: Long,
    val songs: List<SongItem>,
    val customOrders: Map<String, List<String>>,
    val customOrderUpdates: Map<String, List<SongItem>?>,
    val recency: List<String> = songs.map(SongItem::id),
)

internal class SongLikeSnapshotWriter(
    scope: CoroutineScope,
    write: suspend (SongLikeSnapshot) -> Unit,
) {
    private data class PendingWrite(
        val snapshot: SongLikeSnapshot,
        val completion: CompletableDeferred<Unit> = CompletableDeferred(),
    )

    private val pending = Channel<PendingWrite>(Channel.CONFLATED, onUndeliveredElement = { it.completion.complete(Unit) })

    init {
        scope.launch {
            for (request in pending) {
                try {
                    write(request.snapshot)
                } finally {
                    request.completion.complete(Unit)
                }
            }
        }
    }

    fun submit(snapshot: SongLikeSnapshot): CompletableDeferred<Unit> =
        PendingWrite(snapshot).also { pending.trySend(it).getOrThrow() }.completion
}

internal class LatestCallGate {
    private val generation = atomic(0L)

    fun begin(): Long = generation.incrementAndGet()

    fun isCurrent(candidate: Long): Boolean = generation.value == candidate
}

private const val LIKED_ORDER_ACCOUNT_LABEL = "metrolist-liked-order-v1/account"

private fun List<SongItem>.applyCustomOrder(order: List<String>?): List<SongItem> {
    if (order == null) return distinctBy(SongItem::id)
    val remote = distinctBy(SongItem::id)
    val byId = remote.associateBy(SongItem::id)
    val ordered = order.mapNotNull(byId::get)
    val orderedIds = ordered.mapTo(mutableSetOf(), SongItem::id)
    return remote.filterNot { it.id in orderedIds } + ordered
}

fun reconcileLikedSongs(
    localSongs: List<SongItem>,
    remoteSongs: List<SongItem>,
): List<SongItem> {
    val remote = remoteSongs.distinctBy { it.id }
    val remoteById = remote.associateBy { it.id }
    val retained = localSongs.mapNotNull { remoteById[it.id] }.distinctBy { it.id }
    val retainedIds = retained.mapTo(mutableSetOf()) { it.id }
    return remote.filterNot { it.id in retainedIds } + retained
}

fun reconcileLikedSongsAfterRefresh(
    localSnapshot: List<SongItem>,
    currentLocalSongs: List<SongItem>,
    remoteSongs: List<SongItem>,
    preserveLocalOrder: Boolean = false,
): List<SongItem> {
    val snapshotIds = localSnapshot.mapTo(mutableSetOf(), SongItem::id)
    val currentIds = currentLocalSongs.mapTo(mutableSetOf(), SongItem::id)
    val locallyRemovedIds = snapshotIds - currentIds
    val locallyAdded = currentLocalSongs.filter { it.id !in snapshotIds }
    val currentRemote = remoteSongs.filterNot { it.id in locallyRemovedIds } + locallyAdded
    return if (preserveLocalOrder) {
        reconcileLikedSongs(currentLocalSongs, currentRemote)
    } else {
        (locallyAdded + currentRemote).distinctBy(SongItem::id)
    }
}

fun moveLikedSong(
    songs: List<SongItem>,
    songId: String,
    targetSongId: String,
    selectedSongIds: Set<String> = emptySet(),
): List<SongItem> {
    val fromIndex = songs.indexOfFirst { it.id == songId }
    val toIndex = songs.indexOfFirst { it.id == targetSongId }
    if (fromIndex < 0 || toIndex < 0 || fromIndex == toIndex) return songs
    return songs.moveSelection(fromIndex, toIndex, songs.indices.filterTo(mutableSetOf()) { songs[it].id in selectedSongIds })
}
