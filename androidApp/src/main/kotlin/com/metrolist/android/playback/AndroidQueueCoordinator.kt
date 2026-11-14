package com.metrolist.android.playback

import android.content.Context
import androidx.media3.common.Player
import com.metrolist.android.R
import com.metrolist.android.deviceconnect.AndroidDeviceConnect
import com.metrolist.android.listentogether.AndroidListenTogetherManager
import com.metrolist.android.models.PersistPlayerState
import com.metrolist.android.models.PersistRadioQueueChip
import com.metrolist.shared.api.PlaylistPage
import com.metrolist.shared.api.shouldLoadPlaylistPage
import com.metrolist.shared.cast.CastState
import com.metrolist.shared.cast.forQueueEntry
import com.metrolist.shared.deviceconnect.DcPlaybackAction
import com.metrolist.shared.deviceconnect.DcPlaybackCommand
import com.metrolist.shared.model.RadioQueueChip
import com.metrolist.shared.model.RadioQueuePage
import com.metrolist.shared.model.RadioRecommendationHistory
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.buildRadioQueueRecommendations
import com.metrolist.shared.model.isPodcast
import com.metrolist.shared.model.isVideoSong
import com.metrolist.shared.model.selectRadioQueueChip
import com.metrolist.shared.playback.MusicQueue
import com.metrolist.shared.playback.MusicQueueSnapshot
import com.metrolist.shared.playback.PlaybackSeekSource
import com.metrolist.shared.playback.QUEUE_SOURCE_ADDED_MANUALLY
import com.metrolist.shared.playback.QUEUE_SOURCE_AUTO_MIX
import com.metrolist.shared.playback.QUEUE_SOURCE_RECOMMENDED
import com.metrolist.shared.playback.isAutoMixQueueSource
import com.metrolist.shared.playback.isRecommendationGeneratedQueueSource
import com.metrolist.shared.playback.isRecommendedQueueSource
import com.metrolist.shared.settings.BlockedContent
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.state.GlobalYouTubeRepository
import com.metrolist.shared.youtube.playback.PlaybackNetworkStateStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.getString
import timber.log.Timber
import java.util.concurrent.atomic.AtomicInteger

internal class AndroidQueueCoordinator(
    private val context: Context,
    private val player: AndroidPlaybackPlayer,
    private val scope: CoroutineScope,
    private val _repeatMode: MutableStateFlow<Int>,
    private val _shuffleModeEnabled: MutableStateFlow<Boolean>,
    private val onSave: () -> Unit,
    private val onSessionReset: () -> Unit,
    private val onVideoSelected: (SongItem) -> Unit = {},
    private val castState: () -> CastState = { CastState() },
) {
    companion object {
        private const val TAG = "MusicService"
        private const val MAX_RADIO_CONTINUATION_PAGES = 10
    }

    lateinit var transitions: AndroidQueueTransitions
    val repeatMode: StateFlow<Int> = _repeatMode.asStateFlow()
    private val _isQueueLoading = MutableStateFlow(false)
    val isQueueLoading: StateFlow<Boolean> = _isQueueLoading.asStateFlow()
    private val queueLoadingOperations = AtomicInteger()
    private val _isPlaybackLoading = MutableStateFlow(false)
    val isPlaybackLoading: StateFlow<Boolean> = _isPlaybackLoading.asStateFlow()
    private val playbackLoadingOperations = AtomicInteger()

    private val queueTitleState = MutableStateFlow<String?>(null)
    val queueTitle: StateFlow<String?> = queueTitleState.asStateFlow()

    private val queueChipsState = MutableStateFlow<List<RadioQueueChip>>(emptyList())
    val queueChips: StateFlow<List<RadioQueueChip>> = queueChipsState.asStateFlow()

    var recommendationSeedSongId: String? = null
        private set
    private var radioQueueContinuation: String? = null
    private var radioQueuePlaylistId: String? = null
    private var radioQueueParams: String? = null
    private var isRadioQueue = false
    private val queuedSongIds = linkedSetOf<String>()
    private val recommendationHistory = RadioRecommendationHistory()
    private val queueSessionLock = Any()
    var isFiniteQueue = false
        private set
    private var recommendationRequestGeneration = 0L
    var recommendationBootstrapJob: Job? = null
        private set
    private var autoLoadMoreJob: Job? = null
    private val _playlistPage = MutableStateFlow<PlaylistPage?>(null)
    val playlistPageState = _playlistPage.asStateFlow()
    var playlistPage: PlaylistPage?
        get() = _playlistPage.value
        private set(value) {
            _playlistPage.value = value
        }
    private var playlistPageJob: Job? = null
    private var lastAutoLoadedSongId: String? = null

    @Volatile
    var queueSessionId = 0L
        private set
    private var pendingRestorePositionMs: Long? = null
    private var restoredSongId: String? = null
    private var remoteLoadGeneration = 0L

    fun saveShuffleMode() {
        val queue = player.queueSnapshots.value
        if (queue.displayedItems.isNotEmpty()) Settings.shared.shuffleEnabled = queue.isShuffled
    }

    fun restoreShuffleMode() {
        _shuffleModeEnabled.value =
            restoredShuffleMode(Settings.shared.shuffleEnabled, Settings.shared.rememberShuffleAndRepeat)
    }

    fun buildPersistPlayerState(): PersistPlayerState? {
        if (!Settings.shared.persistentQueue) return null
        val queue = player.queueSnapshots.value
        if (queue.displayedItems.isEmpty()) return null
        val currentIndex = queue.currentIndex.coerceIn(queue.displayedItems.indices)
        val persistedQueue = queue.copy(currentIndex = currentIndex)

        val remote = castState().takeIf { it.isConnected }?.forQueueEntry(player.queueState.value)
        val playing = remote?.isPlaying ?: player.isPlaying.value
        return PersistPlayerState(
            playWhenReady = playing,
            currentMediaItemIndex = currentIndex,
            currentPosition = remote?.positionMs ?: player.getRealtimePositionMs(),
            queueSnapshot = persistedQueue,
            playbackState = if (playing) 3 else 1,
            repeatMode = repeatMode.value,
            shuffleModeEnabled = persistedQueue.isShuffled,
            volume = 1.0f,
            queueTitle = queueTitleState.value,
            radioQueueSeedSongId = recommendationSeedSongId,
            radioQueueFinite = isFiniteQueue,
            radioQueue = isRadioQueue,
            queueSessionId = queueSessionId.takeIf { isRadioQueue },
            radioQueueChips =
                queueChipsState.value.map { chip ->
                    PersistRadioQueueChip(chip.id, chip.title, chip.playlistId, chip.params, chip.isSelected)
                },
            radioQueueSelectedChipId =
                queueChipsState.value
                    .firstOrNull { it.isSelected }
                    ?.id,
            radioQueueContinuation = radioQueueContinuation,
            radioQueuePlaylistId = radioQueuePlaylistId,
            radioQueueParams = radioQueueParams,
        )
    }

    fun restorePersistedQueue(
        state: PersistPlayerState,
        restoredQueue: MusicQueueSnapshot,
        expectedQueueSessionId: Long,
        restoreModes: Boolean,
    ): Boolean =
        synchronized(queueSessionLock) {
            if (queueSessionId != expectedQueueSessionId || player.songQueue.value.isNotEmpty()) return@synchronized false
            val restoredSongs = restoredQueue.displayedItems
            val restoredSeed =
                restoredQueue.originalItems.firstOrNull { !it.queueSourceTitle.isRecommendationGeneratedQueueSource() }
                    ?: restoredSongs.getOrNull(restoredQueue.currentIndex)
            val restoredMix = restoredQueue.originalItems.any { it.queueSourceTitle.isAutoMixQueueSource() }
            resetQueueSession(
                seedSongId = state.radioQueueSeedSongId ?: restoredSeed?.id,
                queueTitle =
                    state.queueTitle
                        ?: restoredSeed?.title?.takeIf { restoredMix }?.let { context.getString(R.string.song_mix_title, it) },
                finite = state.radioQueueFinite ?: !restoredMix,
                sessionId = state.queueSessionId,
            )
            val restoredPosition = state.currentPosition
            pendingRestorePositionMs = restoredPosition.takeIf { it > 0L }
            restoredSongId = restoredSongs.getOrNull(restoredQueue.currentIndex)?.id
            isRadioQueue = state.radioQueue || restoredMix
            radioQueueContinuation = state.radioQueueContinuation
            radioQueuePlaylistId = state.radioQueuePlaylistId
            radioQueueParams = state.radioQueueParams
            queueChipsState.value =
                state.radioQueueChips.map { chip ->
                    RadioQueueChip(
                        chip.id,
                        chip.title,
                        chip.playlistId,
                        chip.params,
                        state.radioQueueSelectedChipId?.let { it == chip.id } ?: chip.isSelected,
                    )
                }
            queuedSongIds.addAll(restoredSongs.map(SongItem::id))
            player.setQueue(
                songs = restoredSongs,
                startIndex = restoredQueue.currentIndex,
                startPlaying = false,
            )
            player.restoreQueue(restoredQueue)
            _shuffleModeEnabled.value = player.isShuffleEnabled
            _repeatMode.value = if (restoreModes) state.repeatMode else Player.REPEAT_MODE_OFF
            player.seekTo(restoredPosition, PlaybackSeekSource("queue_restore", mapOf("reason" to "saved_queue")))
            true
        }

    fun takeRestorePosition(
        song: SongItem,
        requestedPositionMs: Long,
    ): Long {
        val position = (pendingRestorePositionMs?.takeIf { song.id == restoredSongId } ?: requestedPositionMs).coerceAtLeast(0L)
        pendingRestorePositionMs = null
        restoredSongId = null
        return position
    }

    fun setTitle(title: String?) {
        queueTitleState.value = title
    }

    fun resetQueueSession(
        seedSongId: String? = null,
        queueTitle: String? = null,
        finite: Boolean = false,
        sessionId: Long? = null,
    ) {
        if (sessionId == null) {
            pendingRestorePositionMs = null
            restoredSongId = null
            Settings.shared.pendingContinuePlayback = false
        }
        recommendationBootstrapJob?.cancel()
        recommendationBootstrapJob = null
        playlistPageJob?.cancel()
        playlistPageJob = null
        playlistPage = null
        autoLoadMoreJob?.cancel()
        autoLoadMoreJob = null
        lastAutoLoadedSongId = null
        transitions.cancelAndroidCrossfade()
        transitions.cancelNextTrackPreload()
        transitions.cancelSongCache()
        queueSessionId = sessionId ?: (queueSessionId + 1)
        recommendationRequestGeneration++
        recommendationSeedSongId = seedSongId
        queueTitleState.value = queueTitle
        queueChipsState.value = emptyList()
        isFiniteQueue = finite
        isRadioQueue = false
        radioQueueContinuation = null
        radioQueuePlaylistId = null
        radioQueueParams = null
        queuedSongIds.clear()
        seedSongId?.let { queuedSongIds.add(it) }
        onSessionReset()
    }

    fun synchronizeMediaSessionQueue(replacesQueue: Boolean) {
        val songs = player.songQueue.value
        synchronized(queueSessionLock) {
            if (replacesQueue || songs.isEmpty()) {
                resetQueueSession(
                    seedSongId = songs.getOrNull(player.currentMediaItemIndex.value)?.id,
                    finite = true,
                )
            }
            queuedSongIds.clear()
            queuedSongIds.addAll(songs.map(SongItem::id))
        }
        onSave()
    }

    fun replaceQueueSession(
        songs: List<SongItem>,
        startIndex: Int,
        queueTitle: String?,
        finite: Boolean,
        playlistPage: PlaylistPage? = null,
        replacement: () -> Unit,
    ) {
        val seed = songs.getOrNull(startIndex) ?: return
        synchronized(queueSessionLock) {
            resetQueueSession(seed.id, queueTitle, finite)
            this.playlistPage = playlistPage
            queuedSongIds.addAll(songs.map(SongItem::id))
            replacement()
        }
        onSave()
    }

    fun playQueue(
        songs: List<SongItem>,
        startIndex: Int = 0,
        queueTitle: String? = null,
        finite: Boolean? = null,
        fromRemote: Boolean = false,
        shuffleModeEnabled: Boolean? = null,
        playlistPage: PlaylistPage? = null,
        songsOnly: Boolean = false,
        startPositionMs: Long = 0L,
        startPlaying: Boolean = true,
        startVideo: Boolean = false,
    ) {
        val (playableSongs, adjustedStartIndex) =
            filteredQueueSelection(songs, startIndex, BlockedContent.snapshot()::isBlocked) ?: return
        val shuffleEnabled =
            newQueueShuffleEnabled(
                requestedShuffle = shuffleModeEnabled,
                currentShuffle = if (player.songQueue.value.isEmpty()) _shuffleModeEnabled.value else player.isShuffleEnabled,
                persistentShuffle = Settings.shared.persistentShuffleAcrossQueues,
            )
        if (!fromRemote &&
            AndroidDeviceConnect.intercept(
                DcPlaybackCommand(
                    DcPlaybackAction.REPLACE_QUEUE,
                    songs = playableSongs,
                    index = adjustedStartIndex,
                    title = queueTitle,
                    finite = finite ?: true,
                    enabled = shuffleEnabled,
                    preserveHistory = shuffleModeEnabled != true,
                    positionMs = startPositionMs,
                ),
            )
        ) {
            return
        }
        replaceQueueSession(playableSongs, adjustedStartIndex, queueTitle, finite ?: true, playlistPage) {
            if (startVideo && !fromRemote) onVideoSelected(playableSongs[adjustedStartIndex])
            player.setQueue(
                songs = playableSongs,
                startIndex = adjustedStartIndex,
                startPlaying = startPlaying,
                preparePlayback = startPlaying || fromRemote,
                startPositionMs = startPositionMs,
                recommendationsSongsOnly = songsOnly,
            )
            if (shuffleEnabled) player.toggleShuffle(preserveHistory = shuffleModeEnabled != true)
            _shuffleModeEnabled.value = player.isShuffleEnabled
        }
    }

    suspend fun playQueueFromRemote(
        songs: List<SongItem>,
        startIndex: Int,
        title: String?,
        prepare: suspend (SongItem, () -> Boolean) -> Boolean,
    ): Boolean {
        val (playableSongs, adjustedStartIndex) =
            filteredQueueSelection(songs, startIndex, BlockedContent.snapshot()::isBlocked) ?: return false
        val generation = ++remoteLoadGeneration
        val sessionId = queueSessionId
        val context = currentCoroutineContext()
        val isCurrent = { context.isActive && generation == remoteLoadGeneration && sessionId == queueSessionId }
        return withQueueLoading(isPlaybackLoading = true) {
            if (!prepare(playableSongs[adjustedStartIndex], isCurrent) || !isCurrent()) return@withQueueLoading false
            val shuffleEnabled =
                newQueueShuffleEnabled(
                    requestedShuffle = null,
                    currentShuffle = if (player.songQueue.value.isEmpty()) _shuffleModeEnabled.value else player.isShuffleEnabled,
                    persistentShuffle = Settings.shared.persistentShuffleAcrossQueues,
                )
            val preparedSongs = playableSongs.toMutableList()
            player.currentSong.value?.let {
                preparedSongs[adjustedStartIndex] = it
            }
            val preparedQueue =
                MusicQueue().apply {
                    setQueue(preparedSongs, adjustedStartIndex)
                    if (shuffleEnabled) toggleShuffle(legacyShuffle = Settings.shared.useLegacyShuffle)
                }
            replaceQueueSession(preparedSongs, adjustedStartIndex, title, finite = true) {
                player.restoreQueue(preparedQueue.snapshot())
                _shuffleModeEnabled.value = player.isShuffleEnabled
            }
            true
        }
    }

    fun extendPlaylistBeforeSkip(resumePlayback: Boolean? = null): Boolean {
        if (playlistPage?.hasMore != true || player.currentMediaItemIndex.value < player.songQueue.value.lastIndex) return false
        val sessionId = queueSessionId
        schedulePlaylistPage()
        scope.launch {
            playlistPageJob?.join()
            if (sessionId == queueSessionId && player.currentMediaItemIndex.value < player.songQueue.value.lastIndex) {
                player.skipToNext(resumePlayback ?: player.wantsPlayback)
            }
        }
        return true
    }

    private fun appendPlaylistSongs(songs: List<SongItem>) {
        player.appendPlaylistSongs(songs)
        queuedSongIds.addAll(songs.map(SongItem::id))
        onSave()
    }

    fun schedulePlaylistPage() {
        if (!PlaybackNetworkStateStore.current.connected) return
        if (playlistPageJob?.isActive == true || playlistPage?.hasMore != true) return
        val sessionId = queueSessionId
        playlistPageJob =
            scope.launch {
                try {
                    do {
                        val previous = playlistPage ?: break
                        val next =
                            withContext(Dispatchers.IO) {
                                GlobalYouTubeRepository.instance.continuePlaylistPage(previous, applyContentFilters = false)
                            }
                        if (!PlaybackNetworkStateStore.current.connected || sessionId != queueSessionId ||
                            AndroidListenTogetherManager.isGuestPlaybackLocked ||
                            AndroidDeviceConnect.manager.remote.isFollowing
                        ) {
                            return@launch
                        }
                        playlistPage = next
                        val additions = BlockedContent.filterPlayableSongs(next.songs.drop(previous.songs.size))
                        appendPlaylistSongs(additions)
                    } while (playlistPage?.hasMore == true &&
                        shouldLoadPlaylistPage(player.currentMediaItemIndex.value, player.songQueue.value.size)
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.tag(TAG).w("Playlist page failed type=%s", e::class.simpleName)
                }
            }
    }

    fun playMix(seed: SongItem) {
        if (seed.isUnavailable || BlockedContent.isBlocked(seed)) return
        val title = context.getString(R.string.song_mix_title, seed.title)
        val current = player.currentSong.value?.takeIf { it.id == seed.id }
        if (AndroidDeviceConnect.intercept(
                DcPlaybackCommand(
                    DcPlaybackAction.REPLACE_QUEUE,
                    songs = listOf(seed),
                    index = 0,
                    title = title,
                    finite = false,
                    positionMs = if (current != null) player.getRealtimePositionMs() else 0L,
                    preserveCurrentTrack = true,
                ),
            )
        ) {
            return
        }
        _repeatMode.value = Player.REPEAT_MODE_OFF
        val preserveCurrent = current != null && player.isReady && !player.playbackTransitionPending.value
        if (preserveCurrent) {
            replaceQueueSession(listOf(requireNotNull(current)), 0, title, finite = false) {
                val queue = MusicQueue().apply { setQueue(listOf(current)) }
                if (Settings.shared.persistentShuffleAcrossQueues &&
                    player.isShuffleEnabled
                ) {
                    queue.toggleShuffle(legacyShuffle = Settings.shared.useLegacyShuffle)
                }
                player.restoreQueue(queue.snapshot())
                _shuffleModeEnabled.value = player.isShuffleEnabled
            }
        } else {
            playQueue(songs = listOf(seed), queueTitle = title, finite = false, fromRemote = true)
        }
        isRadioQueue = true
        radioQueuePlaylistId = "RDAMVM${seed.id}"
        radioQueueParams = null
        if (preserveCurrent) bootstrapRecommendations(seed.id)
        onSave()
    }

    fun updatePendingRestorePosition(position: Long) {
        if (restoredSongId != null) {
            pendingRestorePositionMs = position.coerceAtLeast(0L)
        }
    }

    fun bootstrapRecommendations(seedSongId: String) {
        if (!PlaybackNetworkStateStore.current.connected) return
        if (!canBootstrapRecommendations()) return
        if (AndroidDeviceConnect.manager.remote.isFollowing || AndroidListenTogetherManager.isGuestPlaybackLocked) return
        val sessionId = queueSessionId
        val requestGeneration = ++recommendationRequestGeneration
        recommendationBootstrapJob?.cancel()
        recommendationBootstrapJob =
            scope.launch {
                try {
                    bootstrapRadioQueue(
                        loadPage = { continuation ->
                            withQueueLoading {
                                withContext(Dispatchers.IO) {
                                    if (continuation == null) {
                                        GlobalYouTubeRepository.instance.startRadioQueue(seedSongId)
                                    } else {
                                        GlobalYouTubeRepository.instance.continueRadioQueue(continuation)
                                    }
                                }
                            }
                        },
                        isCurrent = {
                            isCurrentRecommendationRequest(
                                sessionId,
                                requestGeneration,
                                queueSessionId,
                                recommendationRequestGeneration,
                            ) && PlaybackNetworkStateStore.current.connected && repeatMode.value == Player.REPEAT_MODE_OFF &&
                                canBootstrapRecommendations() &&
                                !AndroidDeviceConnect.manager.remote.isFollowing &&
                                !AndroidListenTogetherManager.isGuestPlaybackLocked
                        },
                        nextContinuation = { radioQueueContinuation },
                        applyPage = { page, initial ->
                            applyMixPage(
                                firstPage = page,
                                expectedSessionId = sessionId,
                                expectedRequestGeneration = requestGeneration,
                                replaceGeneratedSongs = false,
                                generatedQueueSourceTitle = if (initial) QUEUE_SOURCE_AUTO_MIX else QUEUE_SOURCE_RECOMMENDED,
                                playlistId = "RDAMVM$seedSongId",
                                params = null,
                            )
                        },
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.tag(TAG).e("Failed to load Mix recommendations type=%s", e::class.simpleName)
                }
            }
    }

    private suspend fun freshRadioPage(
        page: RadioQueuePage,
        replaceGeneratedSongs: Boolean = false,
        isCurrent: () -> Boolean,
    ): RadioQueuePage? =
        recommendationHistory.select(
            firstPage = page,
            filter = { songs ->
                val currentIndex = player.currentMediaItemIndex.value
                val retainedIds =
                    player.songQueue.value
                        .filterIndexed { index, song ->
                            !replaceGeneratedSongs || index == currentIndex || !song.queueSourceTitle.isRecommendationGeneratedQueueSource()
                        }.mapTo(mutableSetOf(), SongItem::id)
                songs.filter { song ->
                    song.id !in retainedIds && !isSongBlocked(song.id) &&
                        (!player.queueSnapshots.value.recommendationsSongsOnly || (!song.isVideoSong && !song.isPodcast))
                }
            },
            isCurrent = isCurrent,
            loadMore = { token ->
                withQueueLoading {
                    withContext(Dispatchers.IO) { GlobalYouTubeRepository.instance.continueRadioQueue(token) }
                }
            },
        )

    private fun canBootstrapRecommendations(): Boolean =
        shouldBootstrapQueueRecommendations(isFiniteQueue, Settings.shared.autoLoadMore, playlistPage?.hasMore == true)

    fun selectQueueChip(chipId: String) {
        if (AndroidDeviceConnect.manager.remote.isFollowing || AndroidListenTogetherManager.isGuestPlaybackLocked) return
        val chip = queueChipsState.value.firstOrNull { it.id == chipId } ?: return
        if (chip.isSelected) return
        val currentSong = player.currentSong.value ?: return
        val sessionId = queueSessionId
        val requestGeneration = ++recommendationRequestGeneration
        recommendationBootstrapJob?.cancel()
        recommendationBootstrapJob =
            scope.launch {
                try {
                    val page =
                        withQueueLoading {
                            withContext(Dispatchers.IO) {
                                GlobalYouTubeRepository.instance.startRadioQueue(
                                    videoId = recommendationSeedSongId ?: currentSong.id,
                                    playlistId = chip.playlistId,
                                    params = chip.params,
                                )
                            }
                        }
                    applyMixPage(
                        firstPage = page,
                        expectedSessionId = sessionId,
                        expectedRequestGeneration = requestGeneration,
                        replaceGeneratedSongs = true,
                        generatedQueueSourceTitle = QUEUE_SOURCE_AUTO_MIX,
                        selectedChipId = chip.id,
                        playlistId = chip.playlistId,
                        params = chip.params,
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.tag(TAG).e("Failed to switch Mix recommendations type=%s", e::class.simpleName)
                }
            }
    }

    private suspend fun applyMixPage(
        firstPage: RadioQueuePage,
        expectedSessionId: Long,
        expectedRequestGeneration: Long,
        replaceGeneratedSongs: Boolean,
        generatedQueueSourceTitle: String = QUEUE_SOURCE_RECOMMENDED,
        selectedChipId: String? = null,
        playlistId: String? = null,
        params: String? = null,
        limit: Int? = null,
    ): Boolean {
        fun isCurrent() =
            PlaybackNetworkStateStore.current.connected &&
                repeatMode.value == Player.REPEAT_MODE_OFF &&
                !AndroidDeviceConnect.manager.remote.isFollowing &&
                !AndroidListenTogetherManager.isGuestPlaybackLocked &&
                isCurrentRecommendationRequest(
                    expectedSessionId,
                    expectedRequestGeneration,
                    queueSessionId,
                    recommendationRequestGeneration,
                ) && canBootstrapRecommendations()

        val page = freshRadioPage(firstPage, replaceGeneratedSongs, ::isCurrent) ?: return false

        val currentIndex = player.currentMediaItemIndex.value
        val currentQueue = player.songQueue.value
        val generatedIndices =
            if (replaceGeneratedSongs) {
                currentQueue.indices.filter { index ->
                    index != currentIndex && currentQueue[index].queueSourceTitle.isRecommendationGeneratedQueueSource()
                }
            } else {
                emptyList()
            }
        val generatedIndexSet = generatedIndices.toSet()
        val retainedIds =
            currentQueue
                .filterIndexed { index, _ -> index !in generatedIndexSet }
                .mapTo(linkedSetOf(), SongItem::id)
        val rawAdditions =
            buildRadioQueueRecommendations(
                songs = page.songs,
                queuedSongIds = retainedIds,
                generatedQueueSourceTitle = generatedQueueSourceTitle,
                isBlocked = { song -> isSongBlocked(song.id) },
                songsOnly = player.queueSnapshots.value.recommendationsSongsOnly,
            )
        val additions = if (limit != null && limit > 0) rawAdditions.take(limit) else rawAdditions

        val chips = page.chips.ifEmpty { queueChipsState.value }
        queueChipsState.value = selectedChipId?.let(chips::selectRadioQueueChip) ?: chips
        radioQueueContinuation = page.continuation
        radioQueuePlaylistId = playlistId ?: radioQueuePlaylistId
        radioQueueParams = if (playlistId != null) params else radioQueueParams
        if (additions.isEmpty()) {
            onSave()
            return false
        }

        transitions.cancelNextTrackPreload()
        generatedIndices.asReversed().forEach(player::removeQueueItem)
        player.addToQueue(additions)
        recommendationHistory.remember(additions)
        queuedSongIds.clear()
        queuedSongIds.addAll(player.songQueue.value.map(SongItem::id))
        isFiniteQueue = false
        onSave()

        return additions.isNotEmpty()
    }

    fun observeAutoLoadMore() {
        scope.launch {
            combine(repeatMode, player.songQueue) { mode, _ -> mode }.collect { mode ->
                if (mode == Player.REPEAT_MODE_ALL) {
                    autoLoadMoreJob?.cancel()
                    recommendationBootstrapJob?.cancel()
                    transitions.cancelNextTrackPreload()
                    generatedQueueIndicesToRemoveForRepeat(player.songQueue.value, player.currentMediaItemIndex.value)
                        .asReversed()
                        .forEach(player::removeQueueItem)
                    queuedSongIds.clear()
                    queuedSongIds.addAll(player.songQueue.value.map(SongItem::id))
                    onSave()
                }
            }
        }
        scope.launch {
            combine(
                player.currentMediaItemIndex,
                player.songQueue,
                repeatMode,
            ) { currentIndex, queue, repeatMode ->
                AutoLoadMoreTrigger(
                    sessionId = queueSessionId,
                    currentIndex = currentIndex,
                    queueSize = queue.size,
                    currentSongId = queue.getOrNull(currentIndex)?.id,
                    shouldLoadMore =
                        shouldAutoLoadMore(
                            currentIndex = currentIndex,
                            queue = queue,
                            autoLoadMore = Settings.shared.autoLoadMore,
                            repeatMode = repeatMode,
                        ),
                )
            }.distinctUntilChanged()
                .collectLatest { trigger ->
                    if (playlistPage?.hasMore == true) {
                        if (shouldLoadPlaylistPage(trigger.currentIndex, trigger.queueSize)) schedulePlaylistPage()
                    } else if (trigger.shouldLoadMore && trigger.currentSongId != null) {
                        scheduleAutoLoadMore(trigger)
                    }
                }
        }
    }

    fun scheduleAutoLoadMore(trigger: AutoLoadMoreTrigger) {
        if (!PlaybackNetworkStateStore.current.connected) return
        if (!trigger.shouldLoadMore) return
        val currentSongId = trigger.currentSongId ?: return
        if (lastAutoLoadedSongId == currentSongId) return
        if (autoLoadMoreJob?.isActive == true) return
        val sessionId = trigger.sessionId
        if (sessionId != queueSessionId) return

        autoLoadMoreJob =
            scope.launch {
                try {
                    lastAutoLoadedSongId = currentSongId
                    performAutoLoadMore(sessionId, currentSongId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Failed to auto-load recommendations")
                }
            }
    }

    private suspend fun performAutoLoadMore(
        sessionId: Long,
        seedSongId: String?,
    ): Boolean {
        if (!PlaybackNetworkStateStore.current.connected) return false
        recommendationBootstrapJob?.join()
        if (!PlaybackNetworkStateStore.current.connected || sessionId != queueSessionId) return false
        if (!Settings.shared.autoLoadMore || repeatMode.value != Player.REPEAT_MODE_OFF) return false

        val currentQueue = player.songQueue.value
        val currentIndex = player.currentMediaItemIndex.value
        if (currentIndex !in currentQueue.indices) return false

        val suggestedIndices =
            currentQueue.indices.filter { index ->
                index > currentIndex && currentQueue[index].queueSourceTitle.isRecommendedQueueSource()
            }

        if (suggestedIndices.isNotEmpty()) {
            val indicesToPromote = suggestedIndices.take(QUEUE_AUTO_LOAD_LIMIT)
            val updates =
                indicesToPromote.associateWith { index ->
                    currentQueue[index].copy(queueSourceTitle = QUEUE_SOURCE_AUTO_MIX)
                }
            player.updateQueueItems(updates)
            queuedSongIds.addAll(updates.values.map(SongItem::id))
            isFiniteQueue = false
            onSave()

            val remainingSuggested = suggestedIndices.size - indicesToPromote.size
            if (remainingSuggested < 10) {
                fetchMoreSuggestions(sessionId, seedSongId ?: player.currentSong.value?.id)
            }
            return true
        }

        val seedId = seedSongId ?: player.currentSong.value?.id ?: recommendationSeedSongId ?: return false
        return fetchInitialOrMoreRecommendations(sessionId, seedId, promoteFirst = true)
    }

    private fun fetchMoreSuggestions(
        sessionId: Long,
        seedSongId: String?,
    ) {
        if (!PlaybackNetworkStateStore.current.connected) return
        val continuation = radioQueueContinuation
        val seedId = seedSongId ?: recommendationSeedSongId ?: return
        scope.launch {
            try {
                val requestGeneration = ++recommendationRequestGeneration
                val page =
                    withQueueLoading {
                        withContext(Dispatchers.IO) {
                            if (continuation != null) {
                                GlobalYouTubeRepository.instance.continueRadioQueue(continuation)
                            } else {
                                GlobalYouTubeRepository.instance.startRadioQueue(seedId)
                            }
                        }
                    }
                applyMixPage(
                    firstPage = page,
                    expectedSessionId = sessionId,
                    expectedRequestGeneration = requestGeneration,
                    replaceGeneratedSongs = false,
                    playlistId = radioQueuePlaylistId ?: "RDAMVM$seedId",
                    params = radioQueueParams,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.tag(TAG).w("Failed to fetch more suggestions type=%s", e::class.simpleName)
            }
        }
    }

    private suspend fun fetchInitialOrMoreRecommendations(
        sessionId: Long,
        seedSongId: String,
        promoteFirst: Boolean,
    ): Boolean {
        val continuation = radioQueueContinuation

        var page: RadioQueuePage? = null
        if (continuation != null) {
            val seenContinuations = mutableSetOf<String>()
            var currentContinuation: String = continuation
            while (
                seenContinuations.size < MAX_RADIO_CONTINUATION_PAGES &&
                seenContinuations.add(currentContinuation) &&
                sessionId == queueSessionId
            ) {
                val token = currentContinuation
                val fetched =
                    try {
                        withQueueLoading {
                            withContext(Dispatchers.IO) {
                                GlobalYouTubeRepository.instance.continueRadioQueue(token)
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        null
                    }
                if (fetched != null && fetched.songs.isNotEmpty()) {
                    page = fetched
                    break
                }
                currentContinuation = fetched?.continuation ?: break
            }
        }

        if (page == null && sessionId == queueSessionId) {
            page =
                try {
                    withQueueLoading {
                        withContext(Dispatchers.IO) {
                            GlobalYouTubeRepository.instance.startRadioQueue(seedSongId)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
        }

        if (!PlaybackNetworkStateStore.current.connected || page == null || sessionId != queueSessionId ||
            repeatMode.value != Player.REPEAT_MODE_OFF
        ) {
            return false
        }

        page = freshRadioPage(page) {
            PlaybackNetworkStateStore.current.connected && sessionId == queueSessionId &&
                repeatMode.value == Player.REPEAT_MODE_OFF && canBootstrapRecommendations() &&
                !AndroidDeviceConnect.manager.remote.isFollowing && !AndroidListenTogetherManager.isGuestPlaybackLocked
        } ?: return false

        val currentQueue = player.songQueue.value
        val retainedIds = currentQueue.mapTo(linkedSetOf(), SongItem::id)
        val rawAdditions =
            buildRadioQueueRecommendations(
                songs = page.songs,
                queuedSongIds = retainedIds,
                generatedQueueSourceTitle = QUEUE_SOURCE_RECOMMENDED,
                isBlocked = { song -> isSongBlocked(song.id) },
                songsOnly = player.queueSnapshots.value.recommendationsSongsOnly,
            )

        if (rawAdditions.isEmpty()) return false

        radioQueueContinuation = page.continuation
        radioQueuePlaylistId = radioQueuePlaylistId ?: "RDAMVM$seedSongId"

        val (toQueue, toSuggest) =
            if (promoteFirst) {
                rawAdditions.take(QUEUE_AUTO_LOAD_LIMIT).map { it.copy(queueSourceTitle = QUEUE_SOURCE_AUTO_MIX) } to
                    rawAdditions.drop(QUEUE_AUTO_LOAD_LIMIT)
            } else {
                emptyList<SongItem>() to rawAdditions
            }

        transitions.cancelNextTrackPreload()
        player.addToQueue(toQueue + toSuggest)
        recommendationHistory.remember(rawAdditions)
        queuedSongIds.clear()
        queuedSongIds.addAll(player.songQueue.value.map(SongItem::id))
        isFiniteQueue = false
        onSave()

        return true
    }

    fun tryStartPreloadedNextAtEof(completedPlaybackInstanceId: Long): Boolean {
        if (transitions.isCrossfading || AndroidDeviceConnect.manager.remote.isActive ||
            AndroidListenTogetherManager.isGuestPlaybackLocked
        ) {
            return false
        }
        if (
            repeatedQueueIndex(
                repeatMode = repeatMode.value,
                currentIndex = player.currentMediaItemIndex.value,
                lastIndex = player.songQueue.value.lastIndex,
                hasMore = playlistPage?.hasMore == true,
            ) != null
        ) {
            return false
        }
        return transitions.startPreloadedNextAtEof(completedPlaybackInstanceId)
    }

    suspend fun advanceQueueAfterCurrent(completedPlaybackInstanceId: Long? = null) {
        if (transitions.consumeCrossfadeCompletion(completedPlaybackInstanceId) || transitions.isCrossfading) return
        if (AndroidDeviceConnect.manager.remote.isActive) {
            AndroidDeviceConnect.manager.remote.playbackEnded()
            return
        }
        if (AndroidListenTogetherManager.isGuestPlaybackLocked) return
        repeatedQueueIndex(
            repeatMode = repeatMode.value,
            currentIndex = player.currentMediaItemIndex.value,
            lastIndex = player.songQueue.value.lastIndex,
            hasMore = playlistPage?.hasMore == true,
        )?.let { index ->
            transitions.cancelNextTrackPreload()
            player.skipToQueueItem(index)
            return
        }
        if (completedPlaybackInstanceId != null && transitions.consumePreloadedNext(completedPlaybackInstanceId)) return

        if (player.currentMediaItemIndex.value < player.songQueue.value.lastIndex) {
            player.skipToNext()
            return
        }

        if (playlistPage?.hasMore == true) {
            val sessionId = queueSessionId
            schedulePlaylistPage()
            playlistPageJob?.join()
            if (sessionId != queueSessionId) return
            if (player.currentMediaItemIndex.value < player.songQueue.value.lastIndex) {
                player.skipToNext()
                return
            }
        }

        repeatedQueueIndex(
            repeatMode.value,
            player.currentMediaItemIndex.value,
            player.songQueue.value.lastIndex,
            hasMore = playlistPage?.hasMore == true,
        )?.let { index ->
            player.skipToQueueItem(index)
            return
        }

        if (isFiniteQueue && !Settings.shared.autoLoadMore) {
            player.skipToNext()
            return
        }

        autoLoadMoreJob?.join()
        if (player.currentMediaItemIndex.value < player.songQueue.value.lastIndex) {
            player.skipToNext()
            return
        }

        if (Settings.shared.autoLoadMore) {
            val sessionId = queueSessionId
            val loaded = performAutoLoadMore(sessionId, player.currentSong.value?.id)
            if (loaded && sessionId == queueSessionId && player.currentMediaItemIndex.value < player.songQueue.value.lastIndex) {
                player.skipToNext()
                return
            }
        }

        player.skipToNext()
    }

    suspend fun extendRadioQueue(shouldCommit: () -> Boolean): Boolean {
        if (!PlaybackNetworkStateStore.current.connected) return false
        val sessionId = queueSessionId
        if (playlistPage?.hasMore == true && shouldCommit()) {
            schedulePlaylistPage()
            playlistPageJob?.join()
            return shouldCommit() && sessionId == queueSessionId &&
                player.currentMediaItemIndex.value < player.songQueue.value.lastIndex
        }

        fun isCurrent(): Boolean =
            PlaybackNetworkStateStore.current.connected && shouldCommit() && sessionId == queueSessionId && !isFiniteQueue &&
                !AndroidDeviceConnect.manager.remote.isFollowing && !AndroidListenTogetherManager.isGuestPlaybackLocked
        recommendationBootstrapJob?.join()
        if (!isCurrent()) return false
        if (player.currentMediaItemIndex.value < player.songQueue.value.lastIndex) return true
        if (!Settings.shared.autoLoadMore || repeatMode.value != Player.REPEAT_MODE_OFF) return false
        val requestGeneration = ++recommendationRequestGeneration
        val seenContinuations = mutableSetOf<String>()
        var continuation = radioQueueContinuation
        while (isCurrent() && seenContinuations.size < MAX_RADIO_CONTINUATION_PAGES) {
            val nextContinuation = continuation
            if (nextContinuation != null && !seenContinuations.add(nextContinuation)) break
            val seedId = recommendationSeedSongId ?: player.currentSong.value?.id ?: return false
            val page =
                withQueueLoading {
                    withContext(Dispatchers.IO) {
                        if (nextContinuation == null) {
                            GlobalYouTubeRepository.instance.startRadioQueue(seedId)
                        } else {
                            GlobalYouTubeRepository.instance.continueRadioQueue(nextContinuation)
                        }
                    }
                }
            if (!isCurrent()) return false
            val appended =
                applyMixPage(
                    firstPage = page,
                    expectedSessionId = sessionId,
                    expectedRequestGeneration = requestGeneration,
                    replaceGeneratedSongs = false,
                    playlistId = radioQueuePlaylistId ?: "RDAMVM$seedId",
                    params = radioQueueParams,
                )
            if (appended) return player.currentMediaItemIndex.value < player.songQueue.value.lastIndex
            continuation = radioQueueContinuation ?: break
        }
        return false
    }

    private fun isSongBlocked(id: String): Boolean = BlockedContent.isSongBlocked(id)

    suspend fun <T> withQueueLoading(
        isPlaybackLoading: Boolean = false,
        block: suspend () -> T,
    ): T {
        queueLoadingOperations.incrementAndGet()
        _isQueueLoading.value = true
        if (isPlaybackLoading) {
            playbackLoadingOperations.incrementAndGet()
            _isPlaybackLoading.value = true
        }
        return try {
            block()
        } finally {
            if (queueLoadingOperations.decrementAndGet() == 0) _isQueueLoading.value = false
            if (isPlaybackLoading && playbackLoadingOperations.decrementAndGet() == 0) {
                _isPlaybackLoading.value = false
            }
        }
    }
}
