package com.metrolist.shared.playback

import androidx.compose.runtime.snapshotFlow
import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.shared.api.YouTubeRepository
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.db.entities.Event
import com.metrolist.shared.db.upsertHistorySong
import com.metrolist.shared.db.withDatabaseTransaction
import com.metrolist.shared.deviceconnect.DcScrobbleSession
import com.metrolist.shared.listentogether.ListenTogetherManager
import com.metrolist.shared.model.RadioQueueChip
import com.metrolist.shared.model.RadioQueuePage
import com.metrolist.shared.model.RadioRecommendationHistory
import com.metrolist.shared.model.RepeatMode
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.buildRadioQueueRecommendations
import com.metrolist.shared.model.isPodcast
import com.metrolist.shared.model.selectRadioQueueChip
import com.metrolist.shared.playback.QUEUE_SOURCE_AUTO_MIX
import com.metrolist.shared.scrobble.ScrobbleManager
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.state.GlobalYouTubeRepository
import com.metrolist.shared.state.PodcastLibrary
import com.metrolist.shared.state.PodcastPlaybackProgress
import com.metrolist.shared.storage.IosAudioDownloadManager
import com.metrolist.shared.ui.playback.PlaybackController
import com.metrolist.shared.ui.playback.PlaybackError
import com.metrolist.shared.ui.playback.PlaybackUiState
import com.metrolist.shared.ui.playback.isQualifiedLocalPlay
import com.metrolist.shared.utils.Logger
import com.metrolist.shared.utils.normalizePlaybackTempo
import com.metrolist.shared.youtube.extraction.models.toContentHints
import com.metrolist.shared.youtube.playback.PlaybackEventKind
import com.metrolist.shared.youtube.playback.PlaybackEventLog
import com.metrolist.shared.youtube.playback.PlaybackNetworkStateStore
import com.metrolist.shared.youtube.playback.PlaybackResource
import com.metrolist.shared.youtube.playback.StreamResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIApplicationWillTerminateNotification
import platform.UIKit.UIBackgroundTaskIdentifier
import platform.UIKit.UIBackgroundTaskInvalid
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

internal class IosPlaybackController(
    private val player: MusicPlayer,
    private val resolver: StreamResolver,
    private val database: MusicDatabase,
    private val scope: CoroutineScope,
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val audioStorage: IosAudioDownloadManager? = null,
    private val persistenceDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val repository: () -> YouTubeRepository = { GlobalYouTubeRepository.instance },
) : PlaybackController {
    override val supportsVideoPlayback = false
    override val supportsCasting = false
    override val canSkipNext: Boolean
        get() = !guestLocked && state.value.canSkipNext(allowSuggestions = !isFiniteQueue || canAutoLoadMore)
    override val canSkipPrevious: Boolean
        get() = !guestLocked && state.value.canSkipPrevious

    private var listenTogether: ListenTogetherManager? = null
    private val guestLocked: Boolean get() = listenTogether?.isGuestPlaybackLocked == true
    private val inListenTogether: Boolean get() = listenTogether?.isInRoom?.value == true

    internal fun bindListenTogether(manager: ListenTogetherManager) {
        listenTogether = manager
        player.isListenTogetherGuest = { manager.isGuestPlaybackLocked }
        player.isListenTogetherActive = { manager.isInRoom.value }
        scope.launch {
            combine(manager.role, manager.isInRoom) { _, active -> active }.collect { active ->
                if (active) {
                    preResolveJob?.cancel()
                    preResolveKey = null
                    player.cancelCrossfadeTransition()
                    sleepTimerController.cancel()
                } else if (pendingPlayback.value == null && player.loadError.value == null) {
                    playbackError.value = null
                }
                player.updateRemotePlaybackLock()
                player.updateRemoteSkipAvailability(!guestLocked && canSkipNext, !guestLocked && canSkipPrevious)
            }
        }
    }

    private val scrobbleManager = ScrobbleManager(scope)
    private val podcastProgress =
        PodcastPlaybackProgress(
            CoroutineScope(scope.coroutineContext + persistenceDispatcher),
            save = { song, position, duration, metadata ->
                PodcastLibrary.updatePlaybackPosition(database, song, position, duration, metadata)
            },
            onError = { Logger.e("IosPlaybackController", "Failed to persist podcast progress", it) },
        )

    private val persistenceJson =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    private val checkpointPersistence =
        QueueCheckpointPersistence(
            writeQueue = { Settings.shared.persistentPlaybackState = it },
            writePosition = { Settings.shared.persistentPlaybackPosition = it },
        )

    private data class CheckpointWrite(
        val state: QueuePlaybackState?,
        val completion: CompletableDeferred<Unit>,
    )

    private val checkpointRequests = Channel<CheckpointWrite>(Channel.UNLIMITED)
    private val checkpointWriter =
        scope.launch(persistenceDispatcher) {
            suspend fun persistBatch(first: CheckpointWrite) {
                var latest = first
                val completions = mutableListOf(first.completion)
                while (true) {
                    val next = checkpointRequests.tryReceive().getOrNull() ?: break
                    latest = next
                    completions += next.completion
                }
                try {
                    checkpointPersistence.persist(latest.state)
                    completions.forEach { it.complete(Unit) }
                } catch (error: Exception) {
                    completions.forEach { it.completeExceptionally(error) }
                    logIosPlaybackError("Playback checkpoint failed", error)
                    Logger.w("IosPlaybackController", "Playback checkpoint failed type=${error::class.simpleName}")
                }
            }
            try {
                for (write in checkpointRequests) persistBatch(write)
            } finally {
                checkpointRequests.close()
                withContext(NonCancellable) {
                    checkpointRequests.tryReceive().getOrNull()?.let { persistBatch(it) }
                }
            }
        }

    private fun submitCheckpoint(state: QueuePlaybackState?): CompletableDeferred<Unit> {
        val completion = CompletableDeferred<Unit>()
        if (checkpointRequests.trySend(CheckpointWrite(state, completion)).isFailure) completion.cancel()
        return completion
    }

    private data class PendingPlayback(
        val generation: Long,
        val song: SongItem,
        val queueSnapshot: MusicQueueSnapshot,
        val startPositionMs: Long,
        val isLoading: Boolean = true,
        val startPlaying: Boolean = true,
    ) {
        val queue: List<SongItem>
            get() = queueSnapshot.displayedItems
        val index: Int
            get() = queueSnapshot.currentIndex
    }

    private val baseState =
        combine(
            player.currentSong,
            player.isPlaying,
            player.currentPosition,
            player.duration,
            player.songQueue,
        ) { song, isPlaying, position, duration, queue ->
            PlaybackUiState(song, isPlaying, position, duration, queue)
        }.combine(player.bufferedPosition) { state, buffered ->
            state.copy(bufferedPositionMs = buffered)
        }.combine(player.currentPlaybackResource) { state, resource ->
            state.copy(
                isAudioProcessingAvailable = canUseIosAudioProcessingTap(resource, resource?.url.orEmpty()),
                playbackResource = resource,
            )
        }.combine(player.playbackSpeed) { state, speed ->
            state.copy(playbackSpeed = speed)
        }.combine(player.playbackPitch) { state, pitch ->
            state.copy(playbackPitch = pitch)
        }.combine(player.isPitchProcessingAvailable) { state, available ->
            state.copy(isPitchProcessingAvailable = available)
        }.combine(player.isWaitingToPlay) { state, waiting ->
            state.copy(isLoading = waiting && state.isPlaying)
        }
    private val pendingPlayback = MutableStateFlow<PendingPlayback?>(null)
    private val activeSong: SongItem?
        get() = pendingPlayback.value?.song ?: player.currentSong.value
    private val activeQueue: List<SongItem>
        get() = pendingPlayback.value?.queue ?: player.songQueue.value
    private val activeIndex: Int
        get() = pendingPlayback.value?.index ?: player.currentMediaItemIndex.value
    private val failureSkipBudget = PlaybackFailureSkipBudget()
    private val playbackError = MutableStateFlow<PlaybackError?>(null)
    private val canApplyListenTogetherPlayback: Boolean
        get() {
            if (!guestLocked) return true
            val roomTrackId =
                listenTogether
                    ?.roomState
                    ?.value
                    ?.currentTrack
                    ?.id ?: return false
            val pending = pendingPlayback.value
            if (pending != null) return pending.isLoading && pending.song.id == roomTrackId
            return player.currentSong.value?.id == roomTrackId &&
                player.currentPlaybackResource.value?.videoId == roomTrackId &&
                playbackError.value == null && player.loadError.value == null
        }
    private val isShuffled = MutableStateFlow(player.isShuffleEnabled)
    private val repeatMode = MutableStateFlow(RepeatMode.OFF)
    private val queueTitle = MutableStateFlow<String?>(null)
    private val queueChips = MutableStateFlow<List<RadioQueueChip>>(emptyList())
    private val isQueueLoading = MutableStateFlow(false)
    private val suggestedTracks = MutableStateFlow<List<SongItem>>(emptyList())

    private val coreState =
        combine(baseState, player.currentMediaItemIndex, pendingPlayback, playbackError) { base, index, pending, error ->
            base.copy(
                currentSong = pending?.song ?: base.currentSong,
                isPlaying = base.isPlaying && pending == null,
                positionMs = pending?.startPositionMs ?: base.positionMs,
                bufferedPositionMs = if (pending != null) 0L else base.bufferedPositionMs,
                durationMs = if (pending != null) (pending.song.duration?.times(1000L) ?: 0L) else base.durationMs,
                queue = pending?.queue ?: base.queue,
                currentQueueIndex = pending?.index ?: index,
                isLoading = pending?.isLoading == true || (pending == null && base.isLoading),
                error = error,
            )
        }

    private val extendedState =
        combine(coreState, queueTitle, queueChips, isQueueLoading, suggestedTracks) { base, title, chips, loading, suggested ->
            base.copy(
                queueTitle = title,
                queueChips = chips,
                isQueueLoading = loading,
                suggestedTracks = suggested,
            )
        }

    private val sleepTimerPlayer =
        object : SleepTimerPlayer {
            override val isAvailable: Boolean
                get() = player.currentSong.value != null
            override val currentPositionMs: Long
                get() = player.currentPositionMs()
            override val durationMs: Long
                get() = player.duration.value
            override val playbackProgress = player.sleepTimerProgress
            override var pauseAtEndOfMediaItems: Boolean = false

            override fun pause() {
                player.pause()
            }

            override fun cancelCrossfadeTransition() {
                player.cancelCrossfadeTransition()
            }

            override fun setSleepTimerGain(gain: Float) {
                player.setSleepTimerGain(gain)
            }
        }

    private val sleepTimerController = SleepTimerController(scope, sleepTimerPlayer)

    override val state: StateFlow<PlaybackUiState> =
        combine(
            extendedState,
            isShuffled,
            repeatMode,
            sleepTimerController.state,
        ) { base, shuffled, repeat, sleepState ->
            base.copy(
                isShuffled = shuffled,
                repeatMode = repeat,
                sleepTimerState = sleepState,
            )
        }.stateIn(scope, SharingStarted.Eagerly, PlaybackUiState())

    private var resolveJob: Job? = null
    private val audioRecovery = IosAudioRecovery()

    override fun currentPositionMs(): Long = pendingPlayback.value?.startPositionMs ?: player.currentPositionMs()

    private var audioQualityReloadJob: Job? = null
    private var preResolveJob: Job? = null
    private val recommendationHistory = RadioRecommendationHistory()
    private var radioJob: Job? = null
    private var radioContinuation: String? = null
    private var deferredRadioBootstrap = false
    private var currentRadioSeedId: String? = null
    private var currentRadioPlaylistId: String? = null
    private var currentRadioParams: String? = null
    private var isFiniteQueue = false
    private var recommendationsSongsOnly = false
    private val canAutoLoadMore: Boolean
        get() =
            Settings.shared.autoLoadMore &&
                repeatMode.value != RepeatMode.ONE &&
                !(repeatMode.value == RepeatMode.ALL && Settings.shared.disableLoadMoreWhenRepeatAll)
    private var preResolveKey: String? = null
    private var requestGeneration = 0L

    private data class ListeningSession(
        var instanceId: Long,
        var song: SongItem,
        val clock: ListeningHistoryClock,
    )

    private data class CrossfadeScrobbleSnapshot(
        val song: SongItem,
        val session: DcScrobbleSession?,
        val startedAt: TimeMark,
    )

    private var listeningSession: ListeningSession? = null
    private var crossfadeOutgoingListeningSession: ListeningSession? = null
    private var crossfadeScrobbleSnapshot: CrossfadeScrobbleSnapshot? = null

    init {
        player.playbackCache = audioStorage?.playbackCache
        restorePersistedPlayback()
        scope.launch {
            sleepTimerController.state.collect { timer ->
                player.setStopAfterSongFade(timer.stopAfterCurrentSong && timer.fadeOut)
            }
        }
        scope.launch {
            val center = NSNotificationCenter.defaultCenter
            val observer =
                center.addObserverForName(UIApplicationDidEnterBackgroundNotification, null, NSOperationQueue.mainQueue) {
                    var task = UIBackgroundTaskInvalid

                    fun finish() {
                        if (task != UIBackgroundTaskInvalid) {
                            UIApplication.sharedApplication.endBackgroundTask(task)
                            task = UIBackgroundTaskInvalid
                        }
                    }
                    task = UIApplication.sharedApplication.beginBackgroundTaskWithName("MetrolistPlaybackCheckpoint") { finish() }
                    val checkpoint = savePlaybackState()
                    val podcastWrite = podcastProgress.flush()
                    scope.launch {
                        try {
                            checkpoint.await()
                            podcastWrite?.join()
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Exception) {
                        } finally {
                            finish()
                        }
                    }
                }
            val terminationObserver =
                center.addObserverForName(UIApplicationWillTerminateNotification, null, NSOperationQueue.mainQueue) {
                    val checkpoint = savePlaybackState()
                    val podcastWrite = podcastProgress.flush()
                    runBlocking {
                        runCatching { checkpoint.await() }
                        podcastWrite?.join()
                    }
                }
            try {
                awaitCancellation()
            } finally {
                center.removeObserver(observer)
                center.removeObserver(terminationObserver)
            }
        }
        scope.launch {
            TokenlessStreamRenewal.observe(
                player.currentPlaybackResource,
                player.currentPosition,
                isActive = { player.isPlaying.value && pendingPlayback.value == null && !player.isCrossfadeReportingActive.value },
            ) { resource ->
                val song = player.currentSong.value ?: return@observe false
                val instance = player.playbackInstance.value?.first
                val generation = requestGeneration
                val isCurrent = {
                    requestGeneration == generation && player.playbackInstance.value?.first == instance &&
                        player.currentPlaybackResource.value === resource && !player.isCrossfadeReportingActive.value
                }
                TokenlessStreamRenewal.renew(
                    resource,
                    player::currentPositionMs,
                    isCurrent = isCurrent,
                    resolve = { excluded ->
                        resolveIosAudioResource(
                            iosPlaybackAudioQuality(Settings.shared.audioQuality),
                            refresh = true,
                            forceAac = audioRecovery.forceAac(song.id),
                        ) { quality, _, allowHls ->
                            resolver.resolveRemote(
                                song.id,
                                song
                                    .toContentHints(wantVideo = false)
                                    .withIosAudioCapabilities(allowHls)
                                    .copy(playbackClientOverrideId = null),
                                quality,
                                song,
                                refresh = true,
                                excludedProfiles = excluded + audioRecovery.excludedProfiles(song.id),
                            )
                        }
                    },
                    replace = { fresh -> player.replacePlaybackResourceInBackground(song, fresh, isCurrent) },
                )
            }
        }
        scope.launch {
            var lastAudioQuality = Settings.shared.audioQuality
            snapshotFlow { Settings.shared.audioQuality }.distinctUntilChanged().collect { quality ->
                if (quality == lastAudioQuality) return@collect
                lastAudioQuality = quality
                preResolveJob?.cancel()
                preResolveKey = null
                audioQualityReloadJob?.cancel()
                player.cancelCrossfadeTransition()
                val pending = pendingPlayback.value
                if (pending != null) {
                    requestPlayback(pending.song, pending.queue, pending.index, pending.startPositionMs)
                    return@collect
                }
                val song = player.currentSong.value ?: return@collect
                if (player.currentPlaybackResource.value == null || player.currentPlaybackResource.value?.clientName == "local" ||
                    player.loadError.value != null
                ) {
                    return@collect
                }
                val playbackInstanceId = player.playbackInstance.value?.first ?: return@collect
                audioQualityReloadJob =
                    scope.launch {
                        try {
                            val resource = resolveAudioResource(song)
                            currentCoroutineContext().ensureActive()
                            if (player.playbackInstance.value?.first != playbackInstanceId || player.currentSong.value?.id != song.id) {
                                return@launch
                            }
                            player.replacePlaybackResource(song, resource)
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            logIosPlaybackError("Audio quality update failed for ${song.id}", error)
                            Logger.w("IosPlaybackController", "Audio quality update failed id=${song.id} type=${error::class.simpleName}")
                        }
                    }
            }
        }
        scope.launch {
            combine(player.isPlaying, player.currentSong, player.isCrossfadeReportingActive) { playing, song, overlapping ->
                Triple(playing, song, overlapping)
            }.collectLatest { (playing, song, overlapping) ->
                if (!overlapping && crossfadeScrobbleSnapshot == null) scrobbleManager.onPlayerStateChanged(playing, song)
            }
        }
        scope.launch {
            combine(player.currentSong, player.currentPosition, player.isPlaying) { _, _, _ -> Unit }
                .collect { updatePodcastProgress() }
        }
        scope.launch {
            observePlaybackCheckpoints(player.isPlaying, snapshotFlow { Settings.shared.persistentQueue }) {
                try {
                    savePlaybackState().await()
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                }
            }
        }
        scope.launch {
            combine(player.isPlaying, player.currentPosition) { playing, position -> position.takeUnless { playing } }
                .distinctUntilChanged()
                .collect { position -> if (position != null) savePlaybackState() }
        }
        scope.launch {
            combine(
                player.songQueue,
                player.currentMediaItemIndex,
                repeatMode,
                pendingPlayback,
                snapshotFlow { Settings.shared.persistentQueue },
            ) { _, _, _, _, _ ->
                savePlaybackState()
            }.collect()
        }
        scope.launch {
            player.queuePlaybackRequests.collect { request ->
                val queue = player.songQueue.value
                val index =
                    player.currentMediaItemIndex.value.takeIf { queue.getOrNull(it) == request.song }
                        ?: queue.indexOfFirst { it === request.song }.takeIf { it >= 0 } ?: queue.indexOf(request.song)
                val resumePositionMs =
                    if ((player.currentPlaybackResource.value == null || player.loadError.value != null) &&
                        player.currentSong.value == request.song
                    ) {
                        player.currentPositionMs()
                    } else {
                        null
                    }
                requestPlayback(request.song, queue, index.coerceAtLeast(0), resumePositionMs)
            }
        }
        scope.launch {
            snapshotFlow { Settings.shared.pauseListenHistory }.collect(::updateListeningHistoryPrivacy)
        }
        scope.launch {
            combine(player.playbackInstance, player.isPlaying, player.isCrossfadeReportingActive) { instance, playing, overlapping ->
                Triple(instance, playing, overlapping)
            }.collect { (instance, playing, overlapping) ->
                if (!overlapping && crossfadeOutgoingListeningSession == null) {
                    updateListeningPlayback(instance, playing)
                } else {
                    listeningSession?.clock?.update(playing, Settings.shared.pauseListenHistory)
                    crossfadeOutgoingListeningSession?.clock?.update(playing, Settings.shared.pauseListenHistory)
                }
            }
        }
        scope.launch {
            player.playbackOverlapEvents.collect(::handlePlaybackOverlapEvent)
        }
        scope.launch {
            snapshotFlow { Settings.shared.skipSilence }.distinctUntilChanged().collect { enabled ->
                if (enabled) {
                    val song = player.currentSong.value ?: return@collect
                    try {
                        audioStorage?.getCachedPlaybackResource(song)?.let { player.analyzeSilenceFromCache(song, it) }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        logIosPlaybackError("Cached audio lookup failed for ${song.id}", error)
                        Logger.w("IosPlaybackController", "Cached audio lookup failed id=${song.id} type=${error::class.simpleName}")
                    }
                }
            }
        }
        val completedPlaybackTargets = mutableMapOf<Long, Pair<SongItem, PlaybackResource>>()
        val completedPlaybackTargetsLock = Mutex()

        fun rememberPlaybackTarget(
            instance: Pair<Long, SongItem>,
            resource: PlaybackResource,
        ) {
            completedPlaybackTargets[instance.first] = instance.second to resource
            while (completedPlaybackTargets.size > 8) completedPlaybackTargets.remove(completedPlaybackTargets.keys.first())
        }
        scope.launch {
            combine(player.playbackInstance, player.currentPlaybackResource) { instance, resource ->
                if (instance != null && resource != null && resource.videoId == instance.second.id) {
                    instance to resource
                } else {
                    null
                }
            }.collect { target ->
                target ?: return@collect
                completedPlaybackTargetsLock.withLock { rememberPlaybackTarget(target.first, target.second) }
            }
        }
        scope.launch {
            var outgoingTarget: Pair<Long, SongItem>? = null
            combine(player.crossfadeCacheInstance, player.isCrossfadeReportingActive) { outgoing, active ->
                outgoing to active
            }.collect { (outgoing, active) ->
                if (outgoing != null) {
                    outgoingTarget = outgoing
                } else if (!active) {
                    val finished = outgoingTarget ?: return@collect
                    outgoingTarget = null
                    if (player.playbackInstance.value?.first == finished.first) return@collect
                    val target =
                        completedPlaybackTargetsLock.withLock { completedPlaybackTargets.remove(finished.first) }
                            ?: return@collect
                    scope.launch { cachePlaybackAudio(target.first, target.second) }
                }
            }
        }
        scope.launch {
            player.completedPlaybackInstances.collect { completedInstanceId ->
                val target =
                    completedPlaybackTargetsLock.withLock { completedPlaybackTargets.remove(completedInstanceId) }
                        ?: return@collect
                scope.launch { cachePlaybackAudio(target.first, target.second) }
            }
        }
        scope.launch {
            player.onEOF.collect { endedInstanceId ->
                val target =
                    completedPlaybackTargetsLock.withLock { completedPlaybackTargets.remove(endedInstanceId) }
                        ?: return@collect
                scope.launch { cachePlaybackAudio(target.first, target.second) }
            }
        }
        scope.launch {
            var preResolveInstance: Long? = null
            combine(player.playbackInstance, player.currentPosition, player.duration, suggestedTracks) { instance, pos, dur, _ ->
                Triple(instance?.first, pos, dur)
            }.collect { (instance, pos, dur) ->
                if (preResolveInstance != instance) {
                    preResolveJob?.cancel()
                    preResolveKey = null
                    preResolveInstance = instance
                }
                if (!inListenTogether && !sleepTimerController.isStopAfterCurrentSong && dur > 0L &&
                    (pos >= 15_000L || pos >= dur - 30_000L)
                ) {
                    val queue = state.value.queue
                    val nextIndex = state.value.currentQueueIndex + 1
                    val nextQueuedSong =
                        queue.getOrNull(nextIndex) ?: if (repeatMode.value == RepeatMode.ALL) queue.firstOrNull() else null
                    val nextSong =
                        nextQueuedSong ?: suggestedTracks.value.firstOrNull().takeIf {
                            repeatMode.value != RepeatMode.ONE && (!isFiniteQueue || canAutoLoadMore)
                        }
                    val nextQueueIndex = if (nextIndex <= queue.lastIndex) nextIndex else 0
                    val playbackInstanceId = instance ?: return@collect
                    val nextSongKey = nextSong?.let { "$playbackInstanceId:$nextQueueIndex:${it.id}" }
                    if (nextSong != null && (nextQueuedSong == null || nextQueueIndex != state.value.currentQueueIndex) &&
                        nextSongKey != preResolveKey
                    ) {
                        preResolveKey = nextSongKey
                        preResolveJob?.cancel()
                        preResolveJob =
                            scope.launch {
                                val expectedCurrentSongId = player.currentSong.value?.id ?: return@launch
                                val expectedIndex = player.currentMediaItemIndex.value
                                val preparedQueueIndex = if (nextIndex <= state.value.queue.lastIndex) nextIndex else 0
                                try {
                                    val resource = resolveAudioResource(nextSong)
                                    currentCoroutineContext().ensureActive()
                                    if (nextQueuedSong == null) return@launch
                                    if (nextSong.isPodcast && PodcastLibrary.resumePosition(
                                            database,
                                            nextSong,
                                            durationMs = resource.durationSeconds?.times(1000L) ?: 0L,
                                        ) > 0L
                                    ) {
                                        return@launch
                                    }
                                    val wrapsQueue = preparedQueueIndex == 0 && expectedIndex == player.songQueue.value.lastIndex
                                    if (player.playbackInstance.value?.first == playbackInstanceId &&
                                        player.currentSong.value?.id == expectedCurrentSongId &&
                                        player.currentMediaItemIndex.value == expectedIndex &&
                                        player.songQueue.value
                                            .getOrNull(preparedQueueIndex)
                                            ?.id == nextSong.id &&
                                        repeatMode.value != RepeatMode.ONE &&
                                        (!wrapsQueue || repeatMode.value == RepeatMode.ALL)
                                    ) {
                                        player.prepareNext(nextSong, resource, preparedQueueIndex)
                                    }
                                } catch (error: CancellationException) {
                                    throw error
                                } catch (_: Exception) {
                                }
                            }
                    }
                }
            }
        }
        scope.launch {
            repeatMode.collect { mode ->
                val index = player.currentMediaItemIndex.value
                val lastIndex = player.songQueue.value.lastIndex
                if (mode == RepeatMode.ONE || (mode == RepeatMode.OFF && index == lastIndex)) {
                    preResolveJob?.cancel()
                    preResolveKey = null
                    player.cancelCrossfadeTransition()
                }
            }
        }
        scope.launch {
            combine(state, snapshotFlow { Settings.shared.autoLoadMore to Settings.shared.disableLoadMoreWhenRepeatAll }) { _, _ ->
                (!guestLocked && canSkipNext) to (!guestLocked && canSkipPrevious)
            }.distinctUntilChanged().collect { (next, previous) ->
                player.updateRemoteSkipAvailability(next, previous)
            }
        }
        scope.launch {
            player.transitionRequests.collect { song ->
                scrobbleManager.onPlaybackInstanceStart(song)
                savePlaybackState()
            }
        }
        scope.launch {
            player.nextTrackRequests.collect { if (!guestLocked) skipToNextFromListenTogether() }
        }
        scope.launch {
            player.previousTrackRequests.collect { if (!guestLocked) skipToPreviousFromListenTogether() }
        }
        scope.launch {
            player.seekRequests.collect { (position, relative) ->
                val base = if (relative) pendingPlayback.value?.startPositionMs ?: player.currentPositionMs() else 0L
                seekTo(base + position.coerceIn(-base, Long.MAX_VALUE - base))
            }
        }
        scope.launch {
            player.onEOF.collect { endedPlaybackInstanceId ->
                if (!player.consumePendingEof(endedPlaybackInstanceId)) return@collect
                savePlaybackState()
                if (guestLocked) return@collect
                if (sleepTimerController.isStopAfterCurrentSong) {
                    sleepTimerController.onEndOfMediaItem()
                    return@collect
                }
                if (pendingPlayback.value != null) return@collect
                if (repeatMode.value == RepeatMode.ONE) {
                    activeSong?.let { requestPlayback(it, activeQueue, activeIndex, startPlaying = true) }
                } else {
                    if (activeIndex == activeQueue.lastIndex && repeatMode.value != RepeatMode.ALL && canAutoLoadMore &&
                        suggestedTracks.value.isEmpty()
                    ) {
                        val generation = requestGeneration
                        val song = player.currentSong.value
                        val index = player.currentMediaItemIndex.value

                        fun completionIsCurrent(): Boolean =
                            generation == requestGeneration && pendingPlayback.value == null && player.playbackInstance.value == null &&
                                player.currentSong.value == song && player.currentMediaItemIndex.value == index

                        radioJob?.join()
                        if (!completionIsCurrent()) return@collect
                        if (suggestedTracks.value.isEmpty() && canAutoLoadMore) {
                            currentRadioSeedId?.let { seedId ->
                                if (radioContinuation != null) {
                                    continueRadioIfNeeded(seedId)
                                } else {
                                    bootstrapRecommendations(seedId, currentRadioPlaylistId ?: "RDAMVM$seedId", currentRadioParams)
                                }
                                radioJob?.join()
                            }
                        }
                        if (!completionIsCurrent()) return@collect
                    }
                    skipToNextFromListenTogether(startPlaying = true)
                }
            }
        }
        scope.launch {
            combine(
                player.currentMediaItemIndex,
                player.songQueue,
                repeatMode,
                snapshotFlow { Settings.shared.autoLoadMore to Settings.shared.disableLoadMoreWhenRepeatAll },
            ) { idx, q, _, _ -> idx to q }.collect { (idx, q) ->
                if (deferredRadioBootstrap && canAutoLoadMore && radioJob?.isActive != true) {
                    currentRadioSeedId?.let { seedId ->
                        bootstrapRecommendations(seedId, currentRadioPlaylistId ?: "RDAMVM$seedId", currentRadioParams)
                    }
                }
                if (q.isNotEmpty() && idx >= q.size - 3) {
                    currentRadioSeedId?.let { seedId ->
                        continueRadioIfNeeded(seedId)
                    }
                }
            }
        }
        scope.launch {
            player.sabrPreparationFailures.collect { failure ->
                reportAudioCaptureFailure(failure)
                val song = player.currentSong.value ?: return@collect
                if (song.id != failure.resource.videoId || pendingPlayback.value != null) return@collect
                audioRecovery.select(song.id)
                if (!audioRecovery.retry(failure)) return@collect
                val instance = player.playbackInstance.value?.first
                val current = player.currentPlaybackResource.value
                audioQualityReloadJob?.cancel()
                audioQualityReloadJob =
                    scope.launch {
                        try {
                            val fresh =
                                resolveAudioResource(
                                    song,
                                    skipCachedAudio = true,
                                    refresh = failure.category == PlaybackFailureCategory.URL_EXPIRED,
                                )
                            player.replacePlaybackResourceInBackground(song, fresh) {
                                player.playbackInstance.value?.first == instance && player.currentPlaybackResource.value === current &&
                                    pendingPlayback.value == null
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            Logger.w("IosPlaybackController", "SABR preparation recovery failed")
                        }
                    }
            }
        }
        scope.launch {
            player.currentPosition.collect { position ->
                if (player.isPlaying.value) failureSkipBudget.observeProgress(requestGeneration, position)
            }
        }
        scope.launch {
            player.loadError.collect { error ->
                if (error == null) return@collect
                yield()
                if (player.loadError.value == null) return@collect
                val failure = player.audioCaptureFailure
                val pending = pendingPlayback.value
                if (pending?.isLoading == true) return@collect
                val song = pending?.song ?: player.currentSong.value
                if (failure != null && song != null && song.id == failure.resource.videoId &&
                    player.currentPlaybackResource.value === failure.resource && audioRecovery.also { it.select(song.id) }.retry(failure)
                ) {
                    requestPlayback(
                        song,
                        pending?.queue ?: player.songQueue.value,
                        pending?.index ?: player.currentMediaItemIndex.value,
                        pending?.startPositionMs ?: player.currentPositionMs(),
                        startPlaying = pending?.startPlaying ?: failure.startPlaying,
                    )
                } else {
                    handleTerminalPlaybackFailure(
                        pending?.startPlaying ?: player.failedPlaybackWanted,
                        failure?.category ?: player.failedPlaybackCategory,
                    )
                }
            }
        }
    }

    private fun handleTerminalPlaybackFailure(
        wantedPlayback: Boolean,
        category: PlaybackFailureCategory,
    ) {
        val advance =
            !guestLocked &&
                shouldSkipAfterPlaybackFailure(
                    Settings.shared.autoSkipNextOnError,
                    wantedPlayback,
                    PlaybackNetworkStateStore.current.connected,
                    repeatMode.value == RepeatMode.ONE,
                    category,
                ) && failureSkipBudget.tryAdvance(requestGeneration)
        val generation = requestGeneration
        player.pause()
        pendingPlayback.value = pendingPlayback.value?.copy(isLoading = false, startPlaying = false)
        playbackError.value = PlaybackError.STREAM_UNAVAILABLE
        if (advance) {
            scope.launch {
                if (generation == requestGeneration) skipToNextFromListenTogether(startPlaying = true)
            }
        }
    }

    suspend fun resolveAndPlayVideo(videoId: String): Boolean {
        if (guestLocked) return false
        val generation = requestGeneration
        val song = repository().getSongMetadata(videoId)
        currentCoroutineContext().ensureActive()
        if (generation != requestGeneration) throw CancellationException()
        if (song == null) return false
        play(song)
        return true
    }

    private fun localSongChangeStartsPlayback(): Boolean =
        shouldPlayAfterSongChange(
            keepPaused = Settings.shared.keepPausedOnSongChange,
            hasCurrentSong = activeSong != null,
            playbackRequested = pendingPlayback.value?.startPlaying ?: player.wantsPlayback,
        )

    override fun play(
        song: SongItem,
        queue: List<SongItem>,
        songsOnly: Boolean,
    ) {
        val songs = queue.ifEmpty { listOf(song) }
        val index = songs.indexOfFirst { it === song }.takeIf { it >= 0 } ?: songs.indexOf(song).coerceAtLeast(0)
        play(song, queue, songsOnly, index)
    }

    private fun play(
        song: SongItem,
        queue: List<SongItem>,
        songsOnly: Boolean,
        index: Int,
    ) {
        if (guestLocked) return
        val songs = queue.ifEmpty { listOf(song) }
        if (songs.none { !it.isUnavailable }) return
        clearRadioState()
        recommendationsSongsOnly = songsOnly
        isFiniteQueue = queue.isNotEmpty()
        val selectedSong = requestPlayback(song, songs, index, startPlaying = localSongChangeStartsPlayback()) ?: return
        if (queue.size <= 1) bootstrapRecommendations(selectedSong.id)
    }

    override fun playQueue(
        songs: List<SongItem>,
        startIndex: Int,
        queueTitle: String?,
    ) {
        if (guestLocked) return
        if (songs.none { !it.isUnavailable }) return
        val adjustedIndex = startIndex.coerceIn(0, songs.lastIndex.coerceAtLeast(0))
        val seed = songs.getOrNull(adjustedIndex) ?: return
        play(seed, songs, songsOnly = false, index = adjustedIndex)
        this.queueTitle.value = queueTitle
        if (songs.size > 1) activeSong?.let { bootstrapRecommendations(it.id) }
    }

    override fun togglePlayPause() {
        if (guestLocked) return
        if (pendingPlayback.value?.isLoading == true) return
        val snapshot = state.value
        if (snapshot.error == null && player.isPlaying.value) {
            player.pause()
        } else if (snapshot.error == null && player.currentPlaybackResource.value != null && player.loadError.value == null) {
            player.resume()
        } else {
            val current = activeSong ?: snapshot.currentSong
            current?.let {
                requestPlayback(
                    it,
                    activeQueue,
                    activeIndex.coerceAtLeast(0),
                    pendingPlayback.value?.startPositionMs ?: player.currentPositionMs(),
                )
            }
        }
    }

    override fun seekTo(positionMs: Long) {
        if (guestLocked) return
        seekFromListenTogether(positionMs)
        listenTogether?.sendSeek(pendingPlayback.value?.startPositionMs ?: player.currentPositionMs())
    }

    internal fun seekFromListenTogether(positionMs: Long) {
        if (!canApplyListenTogetherPlayback) return
        val pending = pendingPlayback.value
        val duration = if (pending != null) pending.song.duration?.times(1000L) else player.duration.value
        val target = positionMs.coerceIn(0L, duration?.takeIf { it > 0L } ?: Long.MAX_VALUE)
        if (pending != null) {
            pendingPlayback.value = pending.copy(startPositionMs = target)
        } else {
            player.seekTo(target)
        }
        savePlaybackState()
    }

    override fun skipToNext() {
        if (guestLocked) return
        skipToNextFromListenTogether(startPlaying = localSongChangeStartsPlayback())
    }

    internal fun skipToNextFromListenTogether(startPlaying: Boolean = pendingPlayback.value?.startPlaying ?: player.isPlaying.value) {
        if (!canApplyListenTogetherPlayback) return
        val queue = activeQueue
        val index = activeIndex + 1
        val nextSong =
            queue.getOrNull(index)
                ?: if (repeatMode.value == RepeatMode.ALL) queue.firstOrNull() else null
        val nextIndex = if (index <= queue.lastIndex) index else 0
        if (nextSong != null) {
            requestPlayback(nextSong, queue, nextIndex, startPlaying = startPlaying)
        } else if (!isFiniteQueue || canAutoLoadMore) {
            suggestedTracks.value.firstOrNull()?.let { playSuggestedTrack(it, startPlaying) }
        }
    }

    override fun skipToPrevious() {
        if (guestLocked) return
        if ((pendingPlayback.value?.startPositionMs ?: player.currentPositionMs()) > 3000L) {
            seekTo(0L)
        } else {
            skipToPreviousFromListenTogether(startPlaying = localSongChangeStartsPlayback())
        }
    }

    internal fun skipToPreviousFromListenTogether(startPlaying: Boolean = pendingPlayback.value?.startPlaying ?: player.isPlaying.value) {
        if (!canApplyListenTogetherPlayback) return
        if ((pendingPlayback.value?.startPositionMs ?: player.currentPositionMs()) > 3000L) {
            seekFromListenTogether(0L)
            return
        }
        val queue = activeQueue
        val index = activeIndex - 1
        val prevSong =
            queue.getOrNull(index)
                ?: if (repeatMode.value == RepeatMode.ALL) queue.lastOrNull() else null
        val prevIndex = if (index >= 0) index else queue.lastIndex
        prevSong?.let {
            requestPlayback(it, queue, prevIndex, startPlaying = startPlaying)
        }
    }

    override fun setPlaybackSpeed(speed: Float) {
        if (inListenTogether) return
        val normalized = normalizePlaybackTempo(speed) ?: return
        Settings.shared.playbackSpeed = normalized
        player.setPlaybackSpeed(normalized)
        if (Settings.shared.varispeed) setPlaybackPitch(normalized)
    }

    override fun setPlaybackPitch(pitch: Float) {
        val normalized = normalizePlaybackTempo(pitch) ?: return
        Settings.shared.playbackPitch = normalized
        player.setPlaybackPitch(normalized)
    }

    override fun startRadio(song: SongItem) {
        if (guestLocked) return
        val current = player.currentSong.value
        if (pendingPlayback.value != null || current?.id != song.id || player.currentPlaybackResource.value == null ||
            player.loadError.value != null
        ) {
            play(song, emptyList())
            return
        }
        preResolveJob?.cancel()
        preResolveKey = null
        player.cancelCrossfadeTransition()
        clearRadioState()
        recommendationsSongsOnly = false
        editQueue { syncQueue(listOf(current), 0, Settings.shared.persistentShuffleAcrossQueues, Settings.shared.useLegacyShuffle) }
        bootstrapRecommendations(song.id)
    }

    override fun selectQueueChip(chipId: String) {
        if (guestLocked) return
        val chip = queueChips.value.firstOrNull { it.id == chipId } ?: return
        if (chip.isSelected) return
        val seedId = currentRadioSeedId ?: state.value.currentSong?.id ?: return
        bootstrapRecommendations(
            seedSongId = seedId,
            playlistId = chip.playlistId,
            params = chip.params,
            selectedChipId = chip.id,
        )
    }

    private fun <T> editQueue(edit: MusicQueue.() -> T): T {
        val pending = pendingPlayback.value
        val result =
            if (pending == null) {
                player.editQueue(edit).also { isShuffled.value = player.isShuffleEnabled }
            } else {
                val queue = MusicQueue().apply { restore(pending.queueSnapshot) }
                queue.edit().also {
                    pendingPlayback.value = pending.copy(queueSnapshot = queue.snapshot())
                    isShuffled.value = queue.isShuffled
                }
            }
        savePlaybackState()
        return result
    }

    override fun playNext(songs: List<SongItem>) {
        if (guestLocked) return
        editQueue { playNext(songs, Settings.shared.preventDuplicateTracksInQueue) }
        selectIdleQueue()
    }

    override fun addToQueue(songs: List<SongItem>) {
        if (guestLocked) return
        editQueue { addToQueue(songs, Settings.shared.preventDuplicateTracksInQueue) }
        selectIdleQueue()
    }

    private fun selectIdleQueue() {
        if (pendingPlayback.value != null || player.currentSong.value != null) return
        val queue = player.songQueue.value
        val first = queue.firstOrNull() ?: return
        requestPlayback(first, queue, 0, startPlaying = false)
    }

    internal fun addToQueueFromListenTogether(song: SongItem) {
        if (canApplyListenTogetherPlayback) editQueue { addToQueue(listOf(song), false) }
    }

    internal fun setQueueTitleFromListenTogether(title: String?) {
        if (!canApplyListenTogetherPlayback) return
        queueTitle.value = title
        savePlaybackState()
    }

    internal fun replaceUpcomingFromListenTogether(songs: List<SongItem>) {
        if (canApplyListenTogetherPlayback) editQueue { replaceUpcomingQueue(songs) }
    }

    internal fun setPlayingFromListenTogether(playing: Boolean) {
        val pending = pendingPlayback.value
        if (pending != null) {
            if (canApplyListenTogetherPlayback) pendingPlayback.value = pending.copy(startPlaying = playing)
        } else if (playing && canApplyListenTogetherPlayback && playbackError.value == null && player.loadError.value == null) {
            player.resume()
        } else {
            player.pause()
        }
    }

    internal suspend fun playQueueFromListenTogether(
        songs: List<SongItem>,
        startIndex: Int,
        title: String?,
    ) {
        val song = songs.getOrNull(startIndex) ?: return
        requestPlayback(song, songs, startIndex, startPositionMs = 0L, startPlaying = false, preserveShuffle = false) ?: return
        val job = resolveJob
        job?.invokeOnCompletion { error ->
            if (error == null && resolveJob === job) {
                if (pendingPlayback.value != null) {
                    discardPendingPlayback()
                } else {
                    clearRadioState()
                    isFiniteQueue = true
                    queueTitle.value = title
                    savePlaybackState()
                }
            }
        }
        try {
            job?.join()
        } catch (error: CancellationException) {
            if (resolveJob === job && pendingPlayback.value != null) {
                ++requestGeneration
                job?.cancel()
                if (player.loadError.value == null) playbackError.value = null
                discardPendingPlayback()
            }
            throw error
        }
    }

    private fun discardPendingPlayback() {
        pendingPlayback.value = null
        isShuffled.value = player.isShuffleEnabled
        player.cancelQueueTransition()
        player.pause()
        savePlaybackState()
    }

    override fun playQueueIndex(index: Int) {
        if (guestLocked) return
        val queue = activeQueue
        queue.getOrNull(index)?.let { requestPlayback(it, queue, index, startPlaying = localSongChangeStartsPlayback()) }
    }

    override fun playSuggestedTrack(song: SongItem) = playSuggestedTrack(song, startPlaying = localSongChangeStartsPlayback())

    private fun playSuggestedTrack(
        song: SongItem,
        startPlaying: Boolean,
    ) {
        if (guestLocked) return
        if (suggestedTracks.value.none { it.id == song.id }) return
        val queue = activeQueue
        suggestedTracks.value = suggestedTracks.value.filterNot { it.id == song.id }
        requestPlayback(song, queue + song, queue.size, startPlaying = startPlaying)
    }

    override fun removeQueueItem(index: Int) {
        removeQueueItems(setOf(index))
    }

    override fun removeQueueItems(indices: Set<Int>): QueueRemoval? =
        if (guestLocked) null else editQueue { removeAt(indices - currentIndex.value) }

    override fun restoreQueueItems(removals: List<QueueRemoval>) {
        if (!guestLocked) editQueue { restore(removals) }
    }

    override fun moveQueueItem(
        from: Int,
        to: Int,
    ) {
        if (!guestLocked) editQueue { move(from, to) }
    }

    override fun reorderQueue(order: List<Int>): Boolean {
        if (guestLocked) return false
        editQueue { reorder(order) }
        return true
    }

    override fun toggleShuffle() {
        if (!guestLocked) editQueue { toggleShuffle(legacyShuffle = Settings.shared.useLegacyShuffle) }
    }

    override fun toggleRepeat() {
        if (guestLocked) return
        val newMode =
            when (repeatMode.value) {
                RepeatMode.OFF -> RepeatMode.ALL
                RepeatMode.ALL -> RepeatMode.ONE
                RepeatMode.ONE -> RepeatMode.OFF
            }
        repeatMode.value = newMode
    }

    override fun startSleepTimer(
        durationMs: Long,
        fadeOut: Boolean,
        finishCurrentSong: Boolean,
    ) {
        if (guestLocked) return
        sleepTimerController.start(durationMs, fadeOut, finishCurrentSong)
    }

    override fun stopSleepTimerAfterCurrentSong(fadeOut: Boolean) {
        if (guestLocked) return
        preResolveJob?.cancel()
        preResolveKey = null
        player.cancelCrossfadeTransition()
        sleepTimerController.stopAfterCurrentSong(fadeOut)
    }

    override fun cancelSleepTimer() {
        sleepTimerController.cancel()
    }

    private fun clearRadioState() {
        radioJob?.cancel()
        radioJob = null
        currentRadioSeedId = null
        currentRadioPlaylistId = null
        currentRadioParams = null
        isFiniteQueue = false
        radioContinuation = null
        deferredRadioBootstrap = false
        queueTitle.value = null
        queueChips.value = emptyList()
        suggestedTracks.value = emptyList()
        isQueueLoading.value = false
    }

    private fun bootstrapRecommendations(
        seedSongId: String,
        playlistId: String = "RDAMVM$seedSongId",
        params: String? = null,
        selectedChipId: String? = null,
    ) {
        radioJob?.cancel()
        if (selectedChipId == null) {
            currentRadioSeedId = seedSongId
            currentRadioPlaylistId = playlistId
            currentRadioParams = params
            radioContinuation = null
            suggestedTracks.value = emptyList()
            deferredRadioBootstrap = isFiniteQueue && !canAutoLoadMore
            if (deferredRadioBootstrap) {
                isQueueLoading.value = false
                return
            }
        }
        radioJob =
            scope.launch {
                isQueueLoading.value = true
                try {
                    val page =
                        loadRadioRecommendations(
                            repository().startRadioQueue(
                                videoId = seedSongId,
                                playlistId = playlistId,
                                params = params,
                            ),
                            seedSongId,
                            isBootstrap = true,
                            finiteQueue = selectedChipId == null && isFiniteQueue,
                        ) ?: return@launch
                    currentRadioSeedId = seedSongId
                    currentRadioPlaylistId = playlistId
                    currentRadioParams = params
                    if (selectedChipId != null) isFiniteQueue = false
                    deferredRadioBootstrap = false
                    suggestedTracks.value = page.songs
                    recommendationHistory.remember(page.songs)
                    radioContinuation = page.continuation
                    val chips =
                        if (selectedChipId != null) {
                            page.chips.selectRadioQueueChip(selectedChipId)
                        } else {
                            page.chips
                        }
                    queueChips.value = chips
                    savePlaybackState()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logIosPlaybackError("Failed to load radio recommendations", e)
                    Logger.w("IosPlaybackController", "Failed to load radio recommendations type=${e::class.simpleName}")
                } finally {
                    if (currentCoroutineContext().isActive) isQueueLoading.value = false
                }
            }
    }

    private fun continueRadioIfNeeded(seedSongId: String) {
        if (!canAutoLoadMore) return
        val continuation = radioContinuation ?: return
        if (radioJob?.isActive == true) return
        radioJob =
            scope.launch {
                isQueueLoading.value = true
                try {
                    val page =
                        loadRadioRecommendations(repository().continueRadioQueue(continuation), seedSongId, isBootstrap = false)
                            ?: return@launch
                    suggestedTracks.value += page.songs
                    recommendationHistory.remember(page.songs)
                    radioContinuation = page.continuation
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logIosPlaybackError("Failed to continue radio queue", e)
                    Logger.w("IosPlaybackController", "Failed to continue radio queue type=${e::class.simpleName}")
                } finally {
                    if (currentCoroutineContext().isActive) isQueueLoading.value = false
                }
            }
    }

    private suspend fun loadRadioRecommendations(
        firstPage: RadioQueuePage,
        seedSongId: String,
        isBootstrap: Boolean,
        finiteQueue: Boolean = isFiniteQueue,
    ): RadioQueuePage? =
        recommendationHistory.select(
            firstPage = firstPage,
            isCurrent = {
                val allowed = canAutoLoadMore || (isBootstrap && !finiteQueue)
                if (!allowed && isBootstrap) deferredRadioBootstrap = true
                allowed
            },
            filter = { songs ->
                val retainedSuggestions = if (isBootstrap) emptyList() else suggestedTracks.value
                val excludedIds =
                    (player.songQueue.value + activeQueue + retainedSuggestions).mapTo(mutableSetOf()) { it.id } +
                        setOfNotNull(seedSongId, player.currentSong.value?.id, pendingPlayback.value?.song?.id)
                buildRadioQueueRecommendations(
                    songs,
                    excludedIds,
                    QUEUE_SOURCE_AUTO_MIX,
                    { false },
                    songsOnly = recommendationsSongsOnly,
                )
            },
            loadMore = { repository().continueRadioQueue(it) },
        )

    private fun newListeningSession(
        instanceId: Long,
        song: SongItem,
        playing: Boolean = false,
    ): ListeningSession {
        val origin = timeSource.markNow()
        val clock = ListeningHistoryClock { origin.elapsedNow().inWholeMilliseconds }
        clock.update(playing, Settings.shared.pauseListenHistory)
        return ListeningSession(instanceId, song, clock)
    }

    internal fun updateListeningHistoryPrivacy(paused: Boolean) {
        listeningSession?.clock?.setHistoryPaused(paused)
        crossfadeOutgoingListeningSession?.clock?.setHistoryPaused(paused)
    }

    internal fun updateListeningPlayback(
        instance: Pair<Long, SongItem>?,
        playing: Boolean,
    ) {
        val current = listeningSession
        val record = if (instance?.first != current?.instanceId) takeListeningRecord(current) else null
        if (instance?.first != current?.instanceId) {
            listeningSession = instance?.let { newListeningSession(it.first, it.second) }
        } else if (instance != null) {
            current?.song = instance.second
        }
        listeningSession?.clock?.update(playing, Settings.shared.pauseListenHistory)
        record?.let(::recordListeningPlay)
    }

    internal fun handlePlaybackOverlapEvent(event: IosPlaybackOverlapEvent) {
        when (event) {
            is IosPlaybackOverlapEvent.Started -> {
                updateListeningPlayback(event.outgoing, playing = true)
                crossfadeOutgoingListeningSession = listeningSession
                listeningSession = newListeningSession(event.incoming.first, event.incoming.second, playing = true)
                if (scrobbleManager.session()?.songId != event.outgoing.second.id) {
                    scrobbleManager.onPlayerStateChanged(isPlaying = true, song = event.outgoing.second)
                }
                crossfadeScrobbleSnapshot =
                    CrossfadeScrobbleSnapshot(
                        song = event.outgoing.second,
                        session = scrobbleManager.pauseAndGetSession(),
                        startedAt = timeSource.markNow(),
                    )
                scrobbleManager.onPlaybackInstanceStart(event.incoming.second)
            }

            is IosPlaybackOverlapEvent.Finished -> {
                takeListeningRecord(crossfadeOutgoingListeningSession)?.let(::recordListeningPlay)
                crossfadeOutgoingListeningSession = null
                crossfadeScrobbleSnapshot?.let { snapshot ->
                    snapshot.session?.let { session ->
                        val overlapMs =
                            snapshot.startedAt
                                .elapsedNow()
                                .inWholeMilliseconds
                                .coerceAtLeast(0L)
                        scrobbleManager.scrobbleSessionIfThresholdReached(
                            snapshot.song,
                            session.copy(accumulatedPlayedMs = session.accumulatedPlayedMs + overlapMs),
                        )
                    }
                }
                crossfadeScrobbleSnapshot = null
            }

            is IosPlaybackOverlapEvent.Cancelled -> {
                takeListeningRecord(listeningSession)?.let(::recordListeningPlay)
                listeningSession =
                    crossfadeOutgoingListeningSession?.apply {
                        instanceId = event.resumed.first
                        song = event.resumed.second
                    }
                if (!player.isPlaying.value) listeningSession?.let(::accumulatePlayTime)
                crossfadeOutgoingListeningSession = null
                crossfadeScrobbleSnapshot?.let { snapshot ->
                    val overlapMs =
                        snapshot.startedAt
                            .elapsedNow()
                            .inWholeMilliseconds
                            .coerceAtLeast(0L)
                    val session = snapshot.session
                    if (session != null) {
                        scrobbleManager.resumePlaybackSession(
                            snapshot.song,
                            session.copy(accumulatedPlayedMs = session.accumulatedPlayedMs + overlapMs),
                            playing = player.isPlaying.value,
                        )
                    } else {
                        scrobbleManager.onPlayerStateChanged(isPlaying = player.isPlaying.value, song = event.resumed.second)
                    }
                }
                crossfadeScrobbleSnapshot = null
            }
        }
    }

    private fun accumulatePlayTime(session: ListeningSession) {
        session.clock.update(playing = false, historyPaused = Settings.shared.pauseListenHistory)
    }

    private fun takeListeningRecord(session: ListeningSession?): Pair<SongItem, Long>? {
        session ?: return null
        accumulatePlayTime(session)
        return (session.song to session.clock.elapsedMs()).also { session.clock.reset() }
    }

    private fun recordListeningPlay(record: Pair<SongItem, Long>) {
        scope.launch { recordQualifiedPlay(record) }
    }

    private suspend fun recordQualifiedPlay(record: Pair<SongItem, Long>?) {
        val (song, playTimeMs) = record ?: return
        if (!isQualifiedLocalPlay(playTimeMs, Settings.shared.historyDuration, Settings.shared.pauseListenHistory)) return
        try {
            database.withDatabaseTransaction {
                val dao = database.dao()
                dao.upsertHistorySong(song)
                dao.incrementTotalPlayTime(song.id, playTimeMs)
                dao.insert(
                    Event(
                        songId = song.id,
                        timestamp = Clock.System.now().toLocalDateTime(TimeZone.UTC),
                        playTime = playTimeMs,
                        source = "LOCAL",
                    ),
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logIosPlaybackError("Failed to record local play for ${song.id}", error)
            Logger.w("IosPlaybackController", "Failed to record local play id=${song.id}")
        }
    }

    private fun requestPlayback(
        requestedSong: SongItem,
        queue: List<SongItem>,
        index: Int,
        startPositionMs: Long? = null,
        startPlaying: Boolean = true,
        preserveShuffle: Boolean = true,
    ): SongItem? {
        val pendingQueue =
            MusicQueue().apply {
                restore(pendingPlayback.value?.queueSnapshot ?: player.queueSnapshot())
                val songs = queue.ifEmpty { listOf(requestedSong) }
                if (preserveShuffle) {
                    syncQueue(songs, index, Settings.shared.persistentShuffleAcrossQueues, Settings.shared.useLegacyShuffle)
                } else {
                    setQueue(songs, index)
                }
            }
        val song = pendingQueue.currentItem() ?: return null
        audioRecovery.select(song.id)
        resolveJob?.cancel()
        audioQualityReloadJob?.cancel()
        preResolveJob?.cancel()
        preResolveKey = null
        val generation = ++requestGeneration
        val initialPosition = startPositionMs ?: 0L
        val podcastWrite = flushPodcastProgress()
        val previousSong = player.currentSong.value
        val failedResource = player.currentPlaybackResource.value.takeIf { player.loadError.value != null }
        val failedCaptureResource = player.audioCaptureFailure?.resource
        val refreshExpiredUrl =
            failedResource === failedCaptureResource && player.audioCaptureFailure?.category == PlaybackFailureCategory.URL_EXPIRED
        player.suspendForQueueTransition(song.id)
        pendingPlayback.value =
            PendingPlayback(generation, song, pendingQueue.snapshot(), initialPosition, startPlaying = startPlaying)
        isShuffled.value = pendingQueue.isShuffled
        playbackError.value = null
        resolveJob =
            scope.launch {
                var bgTaskId: UIBackgroundTaskIdentifier = UIBackgroundTaskInvalid
                try {
                    bgTaskId =
                        UIApplication.sharedApplication.beginBackgroundTaskWithName("MetrolistPlaybackResolution") {
                            if (bgTaskId != UIBackgroundTaskInvalid) {
                                UIApplication.sharedApplication.endBackgroundTask(bgTaskId)
                                bgTaskId = UIBackgroundTaskInvalid
                            }
                        }
                    podcastWrite?.join()
                    val resumePosition = PodcastLibrary.resumePosition(database, song, startPositionMs ?: 0L.takeIf { guestLocked })
                    currentCoroutineContext().ensureActive()
                    if (generation != requestGeneration) return@launch
                    pendingPlayback.value =
                        pendingPlayback.value?.let { pending ->
                            if (pending.startPositionMs == initialPosition) {
                                pending.copy(startPositionMs = resumePosition)
                            } else {
                                pending
                            }
                        }
                    val failedCachedVideoId = failedResource?.takeIf { it.clientName == "local" }?.videoId
                    if (failedCachedVideoId != null) {
                        try {
                            audioStorage?.invalidateCachedAudio(failedCachedVideoId, requireNotNull(failedResource).url)
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            logIosPlaybackError("Cached audio invalidation failed for ${song.id}", error)
                            Logger.w(
                                "IosPlaybackController",
                                "Cached audio invalidation failed id=${song.id} type=${error::class.simpleName}",
                            )
                        }
                    } else {
                        failedResource
                            ?.takeUnless {
                                it === failedCaptureResource && player.audioCaptureFailure?.shouldExcludeProfile != true
                            }?.let { failed ->
                                resolver.reportPlaybackFailure(
                                    videoId = failed.videoId,
                                    clientName = failed.clientName,
                                    responseCode =
                                        player.audioCaptureFailure
                                            ?.takeIf { it.resource === failed }
                                            ?.cause
                                            ?.findPlaybackHttpStatusCode(),
                                    profileId = failed.profileId,
                                    hints =
                                        (previousSong?.toContentHints(wantVideo = false) ?: ContentHints())
                                            .withIosAudioCapabilities(allowHls = !failed.isDirectFile && !failed.isSabr),
                                    category =
                                        player.audioCaptureFailure?.takeIf { it.resource === failed }?.category
                                            ?: PlaybackFailureCategory.UNKNOWN,
                                )
                            }
                    }
                    var resource = resolveAudioResource(song, skipCachedAudio = failedCachedVideoId == song.id, refresh = refreshExpiredUrl)
                    currentCoroutineContext().ensureActive()
                    if (generation != requestGeneration) return@launch
                    if (song.isPodcast && song.duration == null && pendingPlayback.value?.startPositionMs == resumePosition &&
                        startPositionMs == null && !guestLocked
                    ) {
                        val position =
                            PodcastLibrary.resumePosition(
                                database,
                                song,
                                durationMs =
                                    resource.durationSeconds?.times(1000L) ?: 0L,
                            )
                        currentCoroutineContext().ensureActive()
                        if (generation != requestGeneration) return@launch
                        pendingPlayback.value = pendingPlayback.value?.copy(startPositionMs = position)
                    }
                    var pending = pendingPlayback.value?.takeIf { it.generation == generation } ?: return@launch
                    player.play(song, resource, startPlaying = pending.startPlaying, startPositionMs = pending.startPositionMs)
                    while (player.loadError.value != null) {
                        val failure = player.audioCaptureFailure?.takeIf { it.resource === resource } ?: break
                        if (!audioRecovery.retry(failure)) break
                        if (failure.shouldExcludeProfile) reportAudioCaptureFailure(failure)
                        resource =
                            resolveAudioResource(
                                song,
                                skipCachedAudio = true,
                                refresh = failure.category == PlaybackFailureCategory.URL_EXPIRED,
                            )
                        currentCoroutineContext().ensureActive()
                        if (generation != requestGeneration) return@launch
                        pending = pendingPlayback.value?.takeIf { it.generation == generation } ?: return@launch
                        player.play(song, resource, startPlaying = pending.startPlaying, startPositionMs = pending.startPositionMs)
                    }
                    check(player.loadError.value == null) { "iOS player rejected playback resource" }
                    player.editQueue { restore(pending.queueSnapshot) }
                    pendingPlayback.value = null
                    if (previousSong?.id == song.id) scrobbleManager.onPlaybackInstanceStart(song)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    if (generation != requestGeneration) return@launch
                    logIosPlaybackError("Playback resolution failed for ${song.id}", error)
                    handleTerminalPlaybackFailure(
                        pendingPlayback.value?.startPlaying == true,
                        player.audioCaptureFailure?.category
                            ?: if (player.loadError.value !=
                                null
                            ) {
                                player.failedPlaybackCategory
                            } else {
                                PlaybackFailureClassifier.fromThrowable(error)
                            },
                    )
                    previousSong?.let { previous ->
                        val currentQueue = player.songQueue.value
                        val previousIndex =
                            currentQueue.indexOfFirst { it === previous }.takeIf { it >= 0 }
                                ?: currentQueue.indexOf(previous)
                        if (previousIndex >= 0) player.syncQueue(currentQueue, previousIndex)
                    }
                    Logger.w("IosPlaybackController", "Playback resolution failed id=${song.id} type=${error::class.simpleName}")
                } finally {
                    if (bgTaskId != UIBackgroundTaskInvalid) {
                        UIApplication.sharedApplication.endBackgroundTask(bgTaskId)
                        bgTaskId = UIBackgroundTaskInvalid
                    }
                }
            }
        return song
    }

    private suspend fun cachePlaybackAudio(
        song: SongItem,
        resource: PlaybackResource,
    ) {
        if (!resource.url.startsWith("https://", ignoreCase = true)) return
        try {
            val storage = audioStorage ?: return
            storage.cacheSong(song, resource)
            if (Settings.shared.skipSilence) {
                storage.getCachedPlaybackResource(song)?.let { player.analyzeSilenceFromCache(song, it) }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logIosPlaybackError("Audio cache failed for ${song.id}", error)
            Logger.w("IosPlaybackController", "Audio cache failed id=${song.id} type=${error::class.simpleName}")
        }
    }

    private suspend fun reportAudioCaptureFailure(failure: IosAudioCaptureFailure) {
        if (!failure.shouldExcludeProfile) return
        val resource = failure.resource
        val song = player.songQueue.value.firstOrNull { it.id == resource.videoId }
        resolver.reportPlaybackFailure(
            resource.videoId,
            resource.clientName,
            failure.cause?.findPlaybackHttpStatusCode(),
            resource.profileId,
            hints = (song?.toContentHints(wantVideo = false) ?: ContentHints()).withIosAudioCapabilities(),
            category = failure.category,
        )
    }

    private suspend fun resolveAudioResource(
        song: SongItem,
        skipCachedAudio: Boolean = false,
        refresh: Boolean = false,
    ): PlaybackResource {
        val excludedProfiles = audioRecovery.excludedProfiles(song.id)
        if (!skipCachedAudio && !refresh && excludedProfiles.isEmpty()) {
            try {
                audioStorage?.getCachedPlaybackResource(song)?.let { return it }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                logIosPlaybackError("Cached audio lookup failed for ${song.id}", error)
                Logger.w("IosPlaybackController", "Cached audio lookup failed id=${song.id} type=${error::class.simpleName}")
            }
        }
        val quality = iosPlaybackAudioQuality(Settings.shared.audioQuality)
        return resolveIosAudioResource(
            quality,
            refresh = refresh,
            forceAac = audioRecovery.forceAac(song.id),
        ) { requestedQuality, isRefresh, allowHls ->
            val hints =
                song.toContentHints(wantVideo = false).withIosAudioCapabilities(allowHls)
            if (excludedProfiles.isEmpty() && !isRefresh) {
                resolver.resolve(song.id, hints, requestedQuality, song)
            } else {
                resolver.resolveRemote(
                    song.id,
                    if (excludedProfiles.isEmpty()) hints else hints.copy(playbackClientOverrideId = null),
                    requestedQuality,
                    song,
                    refresh = true,
                    excludedProfiles = excludedProfiles,
                )
            }
        }
    }

    private fun updatePodcastProgress() {
        if (pendingPlayback.value != null || player.playbackInstance.value == null) return
        podcastProgress.update(player.currentSong.value, player.currentPositionMs(), player.duration.value, player.isPlaying.value)
    }

    private fun flushPodcastProgress(): Job? {
        updatePodcastProgress()
        return podcastProgress.flush()
    }

    private fun savePlaybackState(): CompletableDeferred<Unit> {
        flushPodcastProgress()
        if (!Settings.shared.persistentQueue) {
            return submitCheckpoint(null)
        }
        val pending = pendingPlayback.value
        val queue = pending?.queueSnapshot ?: player.queueSnapshot()
        if (queue.displayedItems.isEmpty()) {
            return submitCheckpoint(null)
        }
        val state =
            QueuePlaybackState(
                queue =
                    queue.copy(
                        currentIndex = queue.currentIndex.coerceIn(queue.displayedItems.indices),
                        recommendationsSongsOnly = recommendationsSongsOnly,
                    ),
                positionMs = pending?.startPositionMs ?: player.currentPositionMs(),
                repeatMode = repeatMode.value,
                queueTitle = queueTitle.value,
                radioSeedSongId = currentRadioSeedId,
                radioPlaylistId = currentRadioPlaylistId,
                radioParams = currentRadioParams,
                radioQueueFinite = isFiniteQueue,
            )
        return submitCheckpoint(state)
    }

    private fun restorePersistedPlayback() {
        Settings.shared.pendingContinuePlayback = false
        if (!Settings.shared.persistentQueue) return
        val raw = Settings.shared.persistentPlaybackState
        if (raw.isBlank()) return
        try {
            val jsonElement = persistenceJson.parseToJsonElement(raw).jsonObject
            var queueSnapshot: MusicQueueSnapshot
            var posMs: Long
            var repMode: RepeatMode
            val qTitle: String? = jsonElement["queueTitle"]?.jsonPrimitive?.contentOrNull
            val radioSeed: String? =
                (jsonElement["radioSeedSongId"] ?: jsonElement["radioQueueSeedSongId"])?.jsonPrimitive?.contentOrNull
            val radioPlaylist: String? =
                (jsonElement["radioPlaylistId"] ?: jsonElement["radioQueuePlaylistId"])?.jsonPrimitive?.contentOrNull
            val radioParams: String? =
                (jsonElement["radioParams"] ?: jsonElement["radioQueueParams"])?.jsonPrimitive?.contentOrNull

            when {
                jsonElement.containsKey("queue") && jsonElement["queue"] is JsonArray -> {
                    val songs =
                        persistenceJson.decodeFromJsonElement(
                            ListSerializer(SongItem.serializer()),
                            jsonElement.getValue("queue"),
                        )
                    val currentIndex = jsonElement["currentIndex"]?.jsonPrimitive?.intOrNull ?: 0
                    posMs = jsonElement["positionMs"]?.jsonPrimitive?.longOrNull ?: 0L
                    repMode =
                        jsonElement["repeatMode"]?.jsonPrimitive?.contentOrNull?.let { name ->
                            runCatching { RepeatMode.valueOf(name) }.getOrNull()
                        } ?: RepeatMode.OFF
                    queueSnapshot =
                        MusicQueueSnapshot(
                            songs,
                            songs,
                            currentIndex,
                            isShuffled = jsonElement["isShuffled"]?.jsonPrimitive?.booleanOrNull ?: false,
                        )
                }

                jsonElement.containsKey("queueSongs") -> {
                    val songs =
                        persistenceJson.decodeFromJsonElement(
                            ListSerializer(SongItem.serializer()),
                            jsonElement.getValue("queueSongs"),
                        )
                    val currentIndex = jsonElement["currentMediaItemIndex"]?.jsonPrimitive?.intOrNull ?: 0
                    posMs = jsonElement["currentPosition"]?.jsonPrimitive?.longOrNull ?: 0L
                    val repeatInt = jsonElement["repeatMode"]?.jsonPrimitive?.intOrNull ?: 0
                    repMode =
                        when (repeatInt) {
                            1 -> RepeatMode.ALL
                            2 -> RepeatMode.ONE
                            else -> RepeatMode.OFF
                        }
                    queueSnapshot =
                        MusicQueueSnapshot(
                            songs,
                            songs,
                            currentIndex,
                            isShuffled = jsonElement["shuffleModeEnabled"]?.jsonPrimitive?.booleanOrNull ?: false,
                        )
                }

                jsonElement.containsKey("queue") && jsonElement["queue"] is JsonObject -> {
                    queueSnapshot = persistenceJson.decodeFromJsonElement(StoredQueueSnapshotSerializer, jsonElement.getValue("queue"))
                    posMs = jsonElement["positionMs"]?.jsonPrimitive?.longOrNull ?: 0L
                    repMode =
                        jsonElement["repeatMode"]?.jsonPrimitive?.contentOrNull?.let { name ->
                            runCatching { RepeatMode.valueOf(name) }.getOrNull()
                        } ?: RepeatMode.OFF
                }

                else -> {
                    return
                }
            }

            val revision = jsonElement["revision"]?.jsonPrimitive?.contentOrNull
            val checkpoint =
                runCatching {
                    persistenceJson.decodeFromString(QueuePositionCheckpoint.serializer(), Settings.shared.persistentPlaybackPosition)
                }.getOrNull()?.takeIf { it.revision == revision && it.currentIndex in queueSnapshot.displayedItems.indices }
            if (checkpoint != null) {
                queueSnapshot = queueSnapshot.copy(currentIndex = checkpoint.currentIndex)
                posMs = checkpoint.positionMs
                repMode = checkpoint.repeatMode
            }
            if (queueSnapshot.displayedItems.isEmpty() || queueSnapshot.currentIndex !in queueSnapshot.displayedItems.indices) return
            recommendationsSongsOnly = queueSnapshot.recommendationsSongsOnly
            currentRadioSeedId = radioSeed
            currentRadioPlaylistId = radioPlaylist
            this.currentRadioParams = radioParams
            isFiniteQueue = jsonElement["radioQueueFinite"]?.jsonPrimitive?.booleanOrNull ?: (queueSnapshot.displayedItems.size > 1)
            queueTitle.value = qTitle
            val rememberModes = Settings.shared.rememberShuffleAndRepeat
            repeatMode.value = if (rememberModes) repMode else RepeatMode.OFF
            player.restoreQueue(queueSnapshot.forRestore(rememberModes))
            isShuffled.value = player.isShuffleEnabled
            if (posMs > 0L) {
                player.seekTo(posMs)
            }
            Settings.shared.pendingContinuePlayback = Settings.shared.promptContinuePlayback
            if (radioSeed != null) {
                bootstrapRecommendations(
                    seedSongId = radioSeed,
                    playlistId = radioPlaylist ?: "RDAMVM$radioSeed",
                    params = radioParams,
                )
            }
        } catch (e: Exception) {
            logIosPlaybackError("Failed to restore persisted playback", e)
            Logger.w("IosPlaybackController", "Failed to restore persisted playback type=${e::class.simpleName}")
        }
    }
}

private fun logIosPlaybackError(
    message: String,
    error: Throwable,
) {
    PlaybackEventLog.error(PlaybackEventKind.OTHER, "IosPlaybackController", message, error)
}
