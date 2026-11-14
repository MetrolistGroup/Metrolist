package com.metrolist.android.playback

import android.os.SystemClock
import androidx.compose.runtime.snapshotFlow
import androidx.media3.common.Player
import com.metrolist.android.deviceconnect.AndroidDeviceConnect
import com.metrolist.android.listentogether.AndroidListenTogetherManager
import com.metrolist.shared.api.musicvideo.MusicVideoResolver
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.canSwitchToAudioTrack
import com.metrolist.shared.model.isPodcast
import com.metrolist.shared.model.isVideoSong
import com.metrolist.shared.playback.PlaybackCrossfadeController
import com.metrolist.shared.playback.SleepTimerController
import com.metrolist.shared.playback.ffmpeg.DirectPlaybackCache
import com.metrolist.shared.playback.playbackCrossfadeDurationMs
import com.metrolist.shared.playback.playbackCrossfadeGains
import com.metrolist.shared.playback.playbackCrossfadeProgress
import com.metrolist.shared.playback.songsShareAlbum
import com.metrolist.shared.settings.BlockedContent
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.state.SongMetadataState
import com.metrolist.shared.youtube.playback.PlaybackNetworkStateStore
import com.metrolist.shared.youtube.playback.PlaybackResource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import kotlin.time.Duration.Companion.milliseconds

internal class AndroidQueueTransitions(
    private val player: AndroidPlaybackPlayer,
    private val scope: CoroutineScope,
    private val audioCacheManager: AudioCacheManager,
    private val musicVideoResolver: MusicVideoResolver,
    private val queue: AndroidQueueCoordinator,
    private val fetchPlaybackResource: suspend (SongItem, Boolean) -> PlaybackResource?,
    private val onPreloadFailure: suspend (PlaybackResource, SongItem, Boolean, Throwable) -> Unit,
    private val onAdvance: suspend () -> Unit,
    private val episodeResumePosition: suspend (SongItem, Long) -> Long,
) {
    companion object {
        private const val TAG = "MusicService"
        private const val NEXT_RENDER_WAIT_TIMEOUT_MS = 5_000L
    }

    private var nextTrackPreloadJob: Job? = null
    private var nextTrackPreloadToken: AndroidPlaybackPlayer.PlaybackPreloadToken? = null
    private val nextTrackPreloadResourceState = MutableStateFlow<PlaybackResource?>(null)
    private var nextTrackPreloadResource: PlaybackResource?
        get() = nextTrackPreloadResourceState.value
        set(value) {
            nextTrackPreloadResourceState.value = value
        }
    private var nextTrackPreloadSong: SongItem? = null
    private var nextTrackPreloadSourceSong: SongItem? = null
    private var nextTrackPreloadExpectedInstanceId = 0L
    private var nextTrackPreloadAttempt: NextTrackPreloadAttempt? = null
    private val nextTrackPreloadInvalidation = MutableStateFlow(0L)
    private var crossfadeJob: Job? = null
    var isCrossfading = false
        private set
    private val crossfadeCompletionGuard = CrossfadeCompletionGuard()
    private val crossfadeController = PlaybackCrossfadeController<SongItem>()
    val crossfadeState: StateFlow<AndroidCrossfadeState?> = crossfadeController.state
    private val queuePreCacheScheduler = QueuePreCacheScheduler(scope)
    private val deferredSabrCacheSongs = linkedMapOf<String, SongItem>()
    private val pendingAutomaticCacheSongs = linkedMapOf<String, SongItem>()
    private val cacheRetryPolicy = CacheRetryPolicy(SystemClock::elapsedRealtime)
    private var cacheQueueSessionId: Long? = null
    private var preCacheJob: Job? = null

    fun observe(sleepTimer: SleepTimerController) {
        player.consumePreloadedNext = { consumePreloadedNext(player.currentPlaybackInstanceId) }

        scope.launch {
            nextTrackPreloadTriggers(
                playbackPosition = player.currentPosition,
                currentMediaItemIndex = player.currentMediaItemIndex,
                songQueue = player.songQueue,
                repeatMode = queue.repeatMode,
                invalidation = nextTrackPreloadInvalidation,
                currentPlaybackInstanceId = { player.currentPlaybackInstanceId },
                hasMore = { queue.playlistPage?.hasMore == true },
                stopAfterCurrentSong = { sleepTimer.isStopAfterCurrentSong },
                isPreloadedTargetActive = { instanceId, nextVideoId ->
                    val token = nextTrackPreloadToken
                    token != null && token.queueSongId == nextVideoId &&
                        nextTrackPreloadExpectedInstanceId == instanceId &&
                        player.isPreloadedPlaybackActive(token)
                },
            ).collectLatest { trigger ->
                if (trigger.shouldPreload && trigger.nextVideoId != null) {
                    if (!trigger.hasValidPreload) {
                        while (isCrossfading) delay(CROSSFADE_UPDATE_INTERVAL_MS.milliseconds)
                        if (nextTrackPreloadToken != null) cancelNextTrackPreload(resetAttempt = false)
                        delay(NEXT_TRACK_PRELOAD_SETTLE_DELAY_MS.milliseconds)
                        scheduleNextTrackPreload(trigger)
                    }
                } else if (nextTrackPreloadToken != null || nextTrackPreloadJob?.isActive == true) {
                    cancelNextTrackPreload()
                }
            }
        }

        scope.launch {
            combine(
                player.currentPosition,
                player.duration,
                player.isPlaying,
            ) { position, duration, isPlaying ->
                Triple(position, duration, isPlaying)
            }.collect { (position, duration, isPlaying) ->
                val songs = player.songQueue.value
                val next =
                    nextQueuedIndex(
                        songs.size,
                        player.currentMediaItemIndex.value,
                        queue.repeatMode.value,
                        queue.playlistPage?.hasMore == true,
                    )?.let(songs::getOrNull)
                        ?: return@collect
                val current = player.currentSong.value
                val isGapless =
                    Settings.shared.crossfadeGapless && songsShareAlbum(current, next)
                val crossfadeDurationMs =
                    playbackCrossfadeDurationMs(
                        enabled = Settings.shared.crossfadeEnabled,
                        preserveSameAlbumGapless = Settings.shared.crossfadeGapless,
                        sameAlbum = isGapless,
                        configuredDurationMs = (Settings.shared.crossfadeDuration * 1_000f).toLong().coerceAtLeast(1_000L),
                        outgoingDurationMs = duration,
                        incomingDurationMs = next.duration?.times(1_000L) ?: duration,
                    )
                if (
                    shouldStartAndroidCrossfade(
                        playbackPositionMs = position,
                        durationMs = duration,
                        crossfadeDurationMs = crossfadeDurationMs,
                        isPlaying = isPlaying,
                        crossfadeEnabled = Settings.shared.crossfadeEnabled,
                        isCrossfading = isCrossfading,
                        repeatMode = queue.repeatMode.value,
                        isGapless = isGapless,
                        stopAfterCurrentSong = sleepTimer.isStopAfterCurrentSong,
                    )
                ) {
                    startAndroidCrossfade()
                }
            }
        }

        scope.launch {
            combine(
                player.currentMediaItemIndex,
                player.songQueue,
                player.currentPlaybackResource,
                player.playbackStateFlow,
                nextTrackPreloadResourceState,
            ) { currentIndex, queue, resource, playbackState, preloaded ->
                val currentSongId = queue.getOrNull(currentIndex)?.id
                listOf(resource?.videoId, currentSongId, playbackState, preloaded?.videoId)
            }.combine(queue.repeatMode) { targets, repeat -> targets to repeat }
                .distinctUntilChanged()
                .collect { scheduleQueuePreCache(queue.queueSessionId) }
        }

        scope.launch {
            combine(player.isPlaying, player.currentSong) { playing, song -> playing to song }
                .distinctUntilChanged()
                .collect { (playing, _) -> if (playing) scheduleQueuePreCache(queue.queueSessionId) }
        }

        scope.launch {
            snapshotFlow { Settings.shared.videoViewerVisible }
                .distinctUntilChanged()
                .collect { cancelNextTrackPreload() }
        }

        scope.launch {
            snapshotFlow { Settings.shared.maxSongCacheSize }
                .distinctUntilChanged()
                .collect { cacheSize ->
                    withContext(Dispatchers.IO) { DirectPlaybackCache.clear() }
                    audioCacheManager.sabrPlaybackCache.clear()
                    if (cacheSize == 0) {
                        preCacheJob?.cancel()
                        preCacheJob = null
                        deferredSabrCacheSongs.clear()
                        pendingAutomaticCacheSongs.clear()
                        cancelSongCache()
                    } else {
                        scheduleQueuePreCache(queue.queueSessionId)
                        nextTrackPreloadResource?.let { resource ->
                            preCacheJob?.cancel()
                            preCacheJob =
                                scope.launch(Dispatchers.IO) {
                                    audioCacheManager.prefetchPlayback(resource)
                                }
                        }
                    }
                }
        }
    }

    fun scheduleNextTrackPreload(trigger: NextTrackPreloadTrigger) {
        if (trigger.invalidationGeneration != nextTrackPreloadInvalidation.value) return
        if (AndroidDeviceConnect.manager.remote.isActive || AndroidListenTogetherManager.isGuestPlaybackLocked) return
        val nextVideoId = trigger.nextVideoId ?: return
        val preloadToken = nextTrackPreloadToken
        if (
            preloadToken?.queueSongId == trigger.nextVideoId &&
            nextTrackPreloadExpectedInstanceId == trigger.currentPlaybackInstanceId &&
            player.isPreloadedPlaybackActive(preloadToken)
        ) {
            return
        }
        val sessionId = queue.queueSessionId
        val currentVideoId = player.currentPlaybackResource.value?.videoId ?: return
        val nextSong =
            player.songQueue.value
                .getOrNull(player.currentMediaItemIndex.value + 1)
                ?.takeIf { it.id == trigger.nextVideoId } ?: return
        val sourceSong = SongMetadataState.apply(nextSong)
        val wantNextVideo =
            shouldRequestQueueVideo(
                isVideoSong = nextSong.isVideoSong,
                isVideoModeActive = Settings.shared.videoViewerVisible,
                isLocal = nextSong.isLocal || nextSong.id.startsWith("local_"),
                isPodcast = nextSong.isPodcast,
                videoPlaybackEnabled = Settings.shared.musicVideoPlaybackEnabled,
            )
        val attempt = NextTrackPreloadAttempt(trigger.currentPlaybackInstanceId, nextSong.id, wantNextVideo)
        if (nextTrackPreloadAttempt == attempt) return
        cancelNextTrackPreload(resetAttempt = false)
        nextTrackPreloadAttempt = attempt
        nextTrackPreloadJob =
            scope.launch {
                if (!isCurrentPreloadTarget(sessionId, trigger, currentVideoId, nextSong, wantNextVideo)) return@launch
                val cachedAudio =
                    if (!wantNextVideo && nextSong.canSwitchToAudioTrack) {
                        audioCacheManager.getCachedPlaybackResource(sourceSong)
                    } else {
                        null
                    }
                val selection =
                    resolveAndroidAudioTrackSelection(
                        sourceSong = sourceSong,
                        positionMs = 0L,
                        wantVideo = wantNextVideo,
                        cachedAudio = cachedAudio,
                        allowCounterpartLookup =
                            PlaybackNetworkStateStore.current.connected && !AndroidDeviceConnect.manager.remote.isActive &&
                                !AndroidListenTogetherManager.isGuestPlaybackLocked,
                        isCounterpartAllowed = { !BlockedContent.isBlocked(it) },
                        resolveMusicTrack = musicVideoResolver::resolveMusicTrack,
                    )
                var playbackSong = selection.song
                var playbackPositionMs = selection.positionMs
                var resource = selection.cachedAudio ?: fetchPlaybackResource(playbackSong, wantNextVideo)
                if (resource == null && playbackSong.id != nextSong.id) {
                    playbackSong = sourceSong
                    playbackPositionMs = 0L
                    resource = cachedAudio ?: fetchPlaybackResource(playbackSong, wantNextVideo)
                }
                if (resource == null || !isCurrentPreloadTarget(sessionId, trigger, currentVideoId, nextSong, wantNextVideo)) {
                    return@launch
                }
                if (playbackSong.isPodcast && episodeResumePosition(playbackSong, resource.durationSeconds?.times(1000L) ?: 0L) > 0L) {
                    return@launch
                }
                var token =
                    withContext(Dispatchers.IO) {
                        player.prepareNextTrack(
                            resource = resource,
                            expectedCurrentPlaybackInstanceId = trigger.currentPlaybackInstanceId,
                            expectedCurrentVideoId = currentVideoId,
                            startPaused = true,
                            queueSongId = nextSong.id,
                            positionMs = playbackPositionMs,
                            onFailure = preloadFailureHandler(resource, playbackSong, wantNextVideo),
                        )
                    }
                if (token == null && playbackSong.id != nextSong.id) {
                    playbackSong = sourceSong
                    playbackPositionMs = 0L
                    resource = cachedAudio ?: fetchPlaybackResource(playbackSong, wantNextVideo) ?: return@launch
                    token =
                        withContext(Dispatchers.IO) {
                            player.prepareNextTrack(
                                resource = resource,
                                expectedCurrentPlaybackInstanceId = trigger.currentPlaybackInstanceId,
                                expectedCurrentVideoId = currentVideoId,
                                startPaused = true,
                                queueSongId = nextSong.id,
                                positionMs = playbackPositionMs,
                                onFailure = preloadFailureHandler(resource, playbackSong, wantNextVideo),
                            )
                        }
                }
                val preparedToken = token ?: return@launch
                if (!isCurrentPreloadTarget(sessionId, trigger, currentVideoId, nextSong, wantNextVideo)) {
                    if (player.isPreloadedPlaybackActive(preparedToken)) player.cancelNext()
                    return@launch
                }
                nextTrackPreloadToken = preparedToken
                nextTrackPreloadResource = resource
                nextTrackPreloadSong = playbackSong
                nextTrackPreloadSourceSong = sourceSong
                nextTrackPreloadExpectedInstanceId = trigger.currentPlaybackInstanceId
                preCacheJob?.cancel()
                preCacheJob =
                    scope.launch(Dispatchers.IO) {
                        audioCacheManager.prefetchPlayback(resource)
                    }
            }
    }

    private fun preloadFailureHandler(
        resource: PlaybackResource,
        song: SongItem,
        wantVideo: Boolean,
    ): (Throwable) -> Unit =
        { error ->
            scope.launch(Dispatchers.IO) { onPreloadFailure(resource, song, wantVideo, error) }
        }

    fun cancelNextTrackPreload(resetAttempt: Boolean = true) {
        preCacheJob?.cancel()
        preCacheJob = null
        nextTrackPreloadJob?.cancel()
        nextTrackPreloadJob = null
        nextTrackPreloadToken = null
        nextTrackPreloadResource = null
        nextTrackPreloadSong = null
        nextTrackPreloadSourceSong = null
        nextTrackPreloadExpectedInstanceId = 0L
        if (resetAttempt) {
            nextTrackPreloadAttempt = null
            nextTrackPreloadInvalidation.value++
        }
        player.cancelNext()
    }

    fun startAndroidCrossfade() {
        if (AndroidDeviceConnect.manager.remote.isActive || AndroidListenTogetherManager.isGuestPlaybackLocked) return
        val token = nextTrackPreloadToken ?: return
        val resource = nextTrackPreloadResource ?: return
        val expectedInstanceId = nextTrackPreloadExpectedInstanceId
        if (player.currentSong.value == null) return
        val nextSong = nextTrackPreloadSong ?: player.songQueue.value.firstOrNull { it.id == token.queueSongId } ?: return
        if (expectedInstanceId != player.currentPlaybackInstanceId) return
        isCrossfading = true
        crossfadeCompletionGuard.arm(expectedInstanceId)
        crossfadeJob?.cancel()
        crossfadeJob =
            scope.launch {
                if (!player.startNextPlayback()) {
                    val advanceAfterAbort = crossfadeCompletionGuard.takeConsumedCompletion()
                    crossfadeCompletionGuard.clear()
                    cancelNextTrackPreload(resetAttempt = false)
                    isCrossfading = false
                    if (advanceAfterAbort) onAdvance()
                    return@launch
                }
                val endPositionMs = player.duration.value
                val renderWaitStartedAt = SystemClock.elapsedRealtime()
                while (
                    isActive && player.isPreloadedPlaybackActive(token) && !player.isNextPlaybackRendering &&
                    SystemClock.elapsedRealtime() - renderWaitStartedAt < NEXT_RENDER_WAIT_TIMEOUT_MS
                ) {
                    delay(CROSSFADE_UPDATE_INTERVAL_MS.milliseconds)
                }
                if (!player.isPreloadedPlaybackActive(token) || !player.isNextPlaybackRendering) {
                    val advanceAfterAbort = crossfadeCompletionGuard.takeConsumedCompletion()
                    crossfadeCompletionGuard.clear()
                    cancelNextTrackPreload()
                    isCrossfading = false
                    crossfadeJob = null
                    if (advanceAfterAbort) onAdvance()
                    return@launch
                }
                val startPositionMs = player.getRealtimePositionMs()
                var progress = if (crossfadeCompletionGuard.hasConsumedCompletion) 1f else 0f
                crossfadeController.start(
                    nextSong = nextSong,
                    outgoingSong = player.currentSong.value,
                    positionMs = player.nextPlaybackPositionMs,
                    durationMs = player.nextPlaybackDurationMs,
                )
                val playbackActive =
                    combine(player.wantsPlaybackState, player.isPlaying, player.playbackStateFlow) { requested, playing, state ->
                        requested && (playing || state == Player.STATE_ENDED)
                    }
                runCrossfadeFrames(playbackActive) {
                    if (AndroidDeviceConnect.manager.remote.isActive || AndroidListenTogetherManager.isGuestPlaybackLocked) {
                        cancelAndroidCrossfade()
                        cancelNextTrackPreload()
                        return@launch
                    }
                    if (!player.isPreloadedPlaybackActive(token)) {
                        val advanceAfterAbort = crossfadeCompletionGuard.takeConsumedCompletion()
                        player.setTransitionVolume(100f)
                        crossfadeController.cancel()
                        crossfadeCompletionGuard.clear()
                        cancelNextTrackPreload()
                        isCrossfading = false
                        crossfadeJob = null
                        if (advanceAfterAbort) onAdvance()
                        return@launch
                    }
                    progress =
                        maxOf(
                            progress,
                            playbackCrossfadeProgress(
                                startPositionMs,
                                endPositionMs,
                                player.getRealtimePositionMs(),
                                outgoingHasEnded = crossfadeCompletionGuard.hasConsumedCompletion,
                            ),
                        )
                    val (outgoingGain, incomingGain) = playbackCrossfadeGains(progress)
                    player.setCrossfadeVolumes(outgoingGain * 100f, incomingGain * 100f)
                    crossfadeController.update(
                        progress = progress,
                        positionMs = player.nextPlaybackPositionMs,
                        durationMs = player.nextPlaybackDurationMs,
                    )
                    progress < 1f
                }
                crossfadeController.complete()
                var advanceAfterAbort = false
                if (
                    expectedInstanceId == player.currentPlaybackInstanceId &&
                    player.swapToPreloadedQueueNext(token, resource, preloadedQueueSongForPromotion())
                ) {
                    crossfadeCompletionGuard.discardConsumedCompletion()
                    nextTrackPreloadJob = null
                    nextTrackPreloadToken = null
                    nextTrackPreloadResource = null
                    nextTrackPreloadSong = null
                    nextTrackPreloadSourceSong = null
                    nextTrackPreloadExpectedInstanceId = 0L
                    nextTrackPreloadAttempt = null
                } else {
                    crossfadeController.cancel()
                    advanceAfterAbort = crossfadeCompletionGuard.takeConsumedCompletion()
                    player.setCrossfadeVolumes(100f, 0f)
                    cancelNextTrackPreload(resetAttempt = false)
                    if (!advanceAfterAbort && expectedInstanceId == player.currentPlaybackInstanceId) player.skipToNext()
                }
                isCrossfading = false
                crossfadeJob = null
                if (advanceAfterAbort) onAdvance()
            }
    }

    fun cancelAndroidCrossfade() {
        crossfadeJob?.cancel()
        crossfadeJob = null
        crossfadeController.cancel()
        crossfadeCompletionGuard.clear()
        if (isCrossfading) {
            player.setCrossfadeVolumes(100f, 0f)
        }
        player.cancelNext()
        isCrossfading = false
    }

    fun cancelSongCache() {
        queuePreCacheScheduler.cancel()
    }

    private fun activeSabrCacheIds(): Set<String> =
        activeSabrCacheTargets(
            current = player.currentPlaybackResource.value,
            currentSongId = player.currentSong.value?.id,
            playbackState = player.playbackStateFlow.value,
            preloaded = nextTrackPreloadResource,
            preloadedSongId = nextTrackPreloadToken?.queueSongId,
        )

    fun scheduleQueuePreCache(sessionId: Long) {
        if (cacheQueueSessionId != sessionId) {
            cacheQueueSessionId = sessionId
            deferredSabrCacheSongs.clear()
            cacheRetryPolicy.reset()
        }
        if (Settings.shared.maxSongCacheSize == 0) {
            deferredSabrCacheSongs.clear()
            cancelSongCache()
            return
        }
        val currentResource = player.currentPlaybackResource.value
        val currentSong = player.currentSong.value
        pendingAutomaticCacheSongs.clear()
        automaticCacheTracks(currentSong, currentResource, player.isPlaying.value)
            .forEach { pendingAutomaticCacheSongs[it.id] = it }
        val targets = (deferredSabrCacheSongs.values + pendingAutomaticCacheSongs.values).distinctBy { it.id }
        if (targets.isEmpty()) return
        queuePreCacheScheduler.schedule {
            cacheQueueSequentially(
                targets = targets.filter { cacheRetryPolicy.canAttempt(it.id) },
                isCurrent = { sessionId == queue.queueSessionId },
                isCached = { id ->
                    audioCacheManager.isCached(id).also { cached ->
                        if (cached) {
                            audioCacheManager.sabrPlaybackCache.discard(id)
                            deferredSabrCacheSongs.remove(id)
                            pendingAutomaticCacheSongs.remove(id)
                            cacheRetryPolicy.succeeded(id)
                        }
                    }
                },
                cache = { song ->
                    if (!song.isLocal) {
                        cacheWhenSabrReleased(
                            song = song,
                            deferred = deferredSabrCacheSongs,
                            activeIds = ::activeSabrCacheIds,
                            resolve = {
                                val direct =
                                    player.currentPlaybackResource.value?.takeIf { it.videoId == song.id && !it.isSabr }
                                        ?: nextTrackPreloadResource?.takeIf { it.videoId == song.id && !it.isSabr }
                                val sabr = audioCacheManager.sabrPlaybackCache.candidate(song.id)
                                if (direct != null) {
                                    direct
                                } else if (sabr?.capture != null) {
                                    sabr.resource
                                } else if ((sabr != null || deferredSabrCacheSongs.containsKey(song.id)) &&
                                    sabr?.canDownload(Settings.shared.historyDuration) != true
                                ) {
                                    deferredSabrCacheSongs.remove(song.id)
                                    null
                                } else {
                                    fetchPlaybackResource(song, false).also { if (it == null) cacheRetryPolicy.failed(song.id) }
                                }
                            },
                            store = { resource ->
                                audioCacheManager.cacheSong(
                                    song = song,
                                    resource = resource,
                                    playbackInstanceId = player.currentPlaybackInstanceId,
                                    isCurrentSong = song.id == player.currentSong.value?.id,
                                )
                                audioCacheManager.isCached(resource.musicVideoId ?: song.id).also { cached ->
                                    if (cached) {
                                        pendingAutomaticCacheSongs.remove(song.id)
                                        cacheRetryPolicy.succeeded(song.id)
                                    } else if (resource.isSabr) {
                                        cacheRetryPolicy.failed(song.id)
                                    }
                                }
                            },
                        )
                    }
                },
                onFailure = { song, error ->
                    cacheRetryPolicy.failed(song.id)
                    Timber.tag(TAG).w(error, "Failed to cache song in queue: %s", song.id)
                },
            )
        }
    }

    fun consumeCrossfadeCompletion(completedPlaybackInstanceId: Long?): Boolean =
        crossfadeCompletionGuard.consume(completedPlaybackInstanceId)

    fun startPreloadedNextAtEof(completedPlaybackInstanceId: Long): Boolean {
        val token = nextTrackPreloadToken ?: return false
        if (nextTrackPreloadResource == null || nextTrackPreloadExpectedInstanceId != completedPlaybackInstanceId ||
            player.songQueue.value
                .getOrNull(player.currentMediaItemIndex.value + 1)
                ?.id != token.queueSongId ||
            !player.isPreloadedPlaybackActive(token)
        ) {
            return false
        }
        player.setNextVolume(100f)
        return player.startNextPlayback().also { if (!it) player.setNextVolume(0f) }
    }

    fun consumePreloadedNext(completedPlaybackInstanceId: Long): Boolean {
        val token = nextTrackPreloadToken ?: return false
        val resource = nextTrackPreloadResource ?: return false
        if (nextTrackPreloadExpectedInstanceId != completedPlaybackInstanceId ||
            AndroidDeviceConnect.manager.remote.isActive || AndroidListenTogetherManager.isGuestPlaybackLocked
        ) {
            cancelNextTrackPreload()
            return false
        }
        val swapped = player.swapToPreloadedQueueNext(token, resource, preloadedQueueSongForPromotion())
        nextTrackPreloadJob = null
        nextTrackPreloadToken = null
        nextTrackPreloadResource = null
        nextTrackPreloadSong = null
        nextTrackPreloadSourceSong = null
        nextTrackPreloadExpectedInstanceId = 0L
        nextTrackPreloadAttempt = null
        if (swapped) crossfadeController.cancel() else player.cancelNext()
        return swapped
    }

    private fun preloadedQueueSongForPromotion(): SongItem? {
        val sourceSong = nextTrackPreloadSourceSong ?: return nextTrackPreloadSong
        val selectedSong = nextTrackPreloadSong ?: return null
        val queueSong =
            player.songQueue.value
                .getOrNull(player.currentMediaItemIndex.value + 1)
                ?.takeIf { it.id == nextTrackPreloadToken?.queueSongId }
                ?.let(SongMetadataState::apply) ?: return selectedSong
        return mergeSelectedQueueSong(selectedSong, sourceSong, queueSong)
    }

    private fun isCurrentPreloadTarget(
        sessionId: Long,
        trigger: NextTrackPreloadTrigger,
        currentVideoId: String,
        nextSong: SongItem,
        wantVideo: Boolean,
    ): Boolean =
        !AndroidDeviceConnect.manager.remote.isActive &&
            !AndroidListenTogetherManager.isGuestPlaybackLocked &&
            sessionId == queue.queueSessionId &&
            trigger.invalidationGeneration == nextTrackPreloadInvalidation.value &&
            player.currentPlaybackInstanceId == trigger.currentPlaybackInstanceId &&
            player.currentPlaybackResource.value?.videoId == currentVideoId &&
            player.songQueue.value
                .getOrNull(player.currentMediaItemIndex.value + 1)
                ?.id == nextSong.id &&
            shouldRequestQueueVideo(
                isVideoSong = nextSong.isVideoSong,
                isVideoModeActive = Settings.shared.videoViewerVisible,
                isLocal = nextSong.isLocal || nextSong.id.startsWith("local_"),
                isPodcast = nextSong.isPodcast,
                videoPlaybackEnabled = Settings.shared.musicVideoPlaybackEnabled,
            ) == wantVideo

    internal data class NextTrackPreloadAttempt(
        val currentPlaybackInstanceId: Long,
        val nextVideoId: String,
        val wantVideo: Boolean,
    )
}

internal suspend fun cacheQueueSequentially(
    targets: List<SongItem>,
    isCurrent: () -> Boolean,
    isCached: suspend (String) -> Boolean,
    cache: suspend (SongItem) -> Unit,
    onFailure: (SongItem, Exception) -> Unit,
) {
    for (song in targets) {
        if (!isCurrent()) return
        try {
            if (!isCached(song.id)) cache(song)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            onFailure(song, error)
        }
    }
}
