/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.desktop.state.playback

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.metrolist.desktop.deviceconnect.DesktopDeviceConnect
import com.metrolist.desktop.history.HistoryEntry
import com.metrolist.desktop.history.HistoryRepository
import com.metrolist.desktop.history.HistoryStat
import com.metrolist.desktop.playback.PlaybackErrorReporter
import com.metrolist.desktop.state.app.AppState
import com.metrolist.desktop.state.app.UiState
import com.metrolist.desktop.state.auth.AuthState
import com.metrolist.desktop.storage.DownloadManager
import com.metrolist.desktop.storage.SongCacheManager
import com.metrolist.desktop.utils.AppLogger
import com.metrolist.desktop.utils.ComposeTrayManager
import com.metrolist.desktop.window.playbackWindowVisibility
import com.metrolist.innertubex.extraction.AudioQuality
import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.innertubex.extraction.strategy.PlaybackClientCatalog
import com.metrolist.shared.BuildConfig
import com.metrolist.shared.api.musicvideo.CounterpartPositionMap
import com.metrolist.shared.api.musicvideo.MusicTrackResult
import com.metrolist.shared.api.musicvideo.MusicVideoResolver
import com.metrolist.shared.api.musicvideo.MusicVideoResult
import com.metrolist.shared.cast.CastConnectionStatus
import com.metrolist.shared.cast.CastDevice
import com.metrolist.shared.cast.JmDnsCastDiscovery
import com.metrolist.shared.cast.JvmCastManager
import com.metrolist.shared.cast.castAudioQuality
import com.metrolist.shared.cast.forQueueEntry
import com.metrolist.shared.cast.shouldHandleCastPlaybackEnd
import com.metrolist.shared.cast.systemCastNetworkAddresses
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.deviceconnect.DcLocalPlaybackState
import com.metrolist.shared.deviceconnect.DcPlaybackAction
import com.metrolist.shared.deviceconnect.DcPlaybackCommand
import com.metrolist.shared.deviceconnect.DcPlaybackSnapshot
import com.metrolist.shared.deviceconnect.DcRepeatMode
import com.metrolist.shared.deviceconnect.DcScrobbleSession
import com.metrolist.shared.deviceconnect.DeviceConnectPlaybackPort
import com.metrolist.shared.discord.DiscordRpcManager
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.error_audio_output_unavailable
import com.metrolist.shared.generated.resources.music_video_unavailable
import com.metrolist.shared.generated.resources.playback_error_age_restricted
import com.metrolist.shared.generated.resources.playback_error_explicit_sign_in
import com.metrolist.shared.generated.resources.playback_error_explicit_signed_in
import com.metrolist.shared.generated.resources.playback_error_network
import com.metrolist.shared.generated.resources.playback_error_no_music_video
import com.metrolist.shared.generated.resources.playback_error_track_not_found
import com.metrolist.shared.generated.resources.playback_error_track_unavailable
import com.metrolist.shared.generated.resources.playback_error_unable_to_play_link
import com.metrolist.shared.generated.resources.sponsorblock_skipped
import com.metrolist.shared.model.AlbumTiny
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.MUSIC_VIDEO_TYPE_LIVE
import com.metrolist.shared.model.MUSIC_VIDEO_TYPE_VIDEO
import com.metrolist.shared.model.RadioRecommendationHistory
import com.metrolist.shared.model.RepeatMode
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.indexOfPlaylistOccurrence
import com.metrolist.shared.model.isLive
import com.metrolist.shared.model.isPodcast
import com.metrolist.shared.model.isVideoSong
import com.metrolist.shared.model.moveSelection
import com.metrolist.shared.model.previewThumbnailUrl
import com.metrolist.shared.model.varyRadioRecommendations
import com.metrolist.shared.playback.DESKTOP_PRELOAD_MIN_POSITION_MS
import com.metrolist.shared.playback.MusicPlayer
import com.metrolist.shared.playback.MusicQueue
import com.metrolist.shared.playback.MusicQueueSnapshot
import com.metrolist.shared.playback.PLAYBACK_PRE_CACHE_DELAY_MS
import com.metrolist.shared.playback.PlaybackErrorContext
import com.metrolist.shared.playback.PlaybackFailure
import com.metrolist.shared.playback.PlaybackFailureCategory
import com.metrolist.shared.playback.PlaybackFailureClassifier
import com.metrolist.shared.playback.PlaybackFailureSkipBudget
import com.metrolist.shared.playback.PlaybackNetworkRecovery
import com.metrolist.shared.playback.PlaybackRecoveryAction
import com.metrolist.shared.playback.PlaybackRecoveryBudget
import com.metrolist.shared.playback.PlaybackRecoveryPolicy
import com.metrolist.shared.playback.PlaybackSeekSource
import com.metrolist.shared.playback.QueueRemoval
import com.metrolist.shared.playback.TokenlessStreamRenewal
import com.metrolist.shared.playback.VideoFrame
import com.metrolist.shared.playback.VideoPlayerStats
import com.metrolist.shared.playback.enqueueOrStartQueue
import com.metrolist.shared.playback.ffmpeg.isAudioOutputUnavailable
import com.metrolist.shared.playback.findPlaybackHttpStatusCode
import com.metrolist.shared.playback.hasSameStructure
import com.metrolist.shared.playback.isRecommendationGeneratedQueueSource
import com.metrolist.shared.playback.observePlaybackCheckpoints
import com.metrolist.shared.playback.playbackCrossfadeDurationMs
import com.metrolist.shared.playback.shouldPlayAfterSongChange
import com.metrolist.shared.playback.shouldSkipAfterPlaybackFailure
import com.metrolist.shared.playback.songsShareAlbum
import com.metrolist.shared.playback.sponsorblock.observeSponsorBlockSkipping
import com.metrolist.shared.scrobble.ScrobbleManager
import com.metrolist.shared.settings.BlockedContent
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.state.GlobalYouTube
import com.metrolist.shared.state.GlobalYouTubeRepository
import com.metrolist.shared.state.PodcastLibrary
import com.metrolist.shared.state.PodcastPlaybackProgress
import com.metrolist.shared.state.SongLikeState
import com.metrolist.shared.state.SongMetadataState
import com.metrolist.shared.utils.Logger
import com.metrolist.shared.youtube.extraction.models.toContentHints
import com.metrolist.shared.youtube.playback.JvmPlaybackNetworkMonitor
import com.metrolist.shared.youtube.playback.PlaybackErrorMessages
import com.metrolist.shared.youtube.playback.PlaybackEventKind
import com.metrolist.shared.youtube.playback.PlaybackEventLog
import com.metrolist.shared.youtube.playback.PlaybackNetworkStateStore
import com.metrolist.shared.youtube.playback.PlaybackResource
import com.metrolist.shared.youtube.playback.WebPoTokenHealth
import com.metrolist.shared.youtube.playback.formatPlaybackLogDetails
import com.metrolist.shared.youtube.playback.playbackErrorResource
import com.metrolist.shared.youtube.playback.throwableDiagnostics
import com.metrolist.shared.youtube.playback.toPlaybackLogDetails
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.getString
import org.koin.core.context.GlobalContext
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private val WINDOWS_ABSOLUTE_PATH = Regex("^[A-Za-z]:[\\\\/].*")
private val URI_SCHEME_PREFIX = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")

internal fun validatedLocalMediaUrl(
    value: String?,
    relativeTo: Path? = null,
): String? {
    val raw = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
    if (raw.startsWith("//") || raw.startsWith("\\\\")) return null
    return runCatching {
        val path =
            when {
                WINDOWS_ABSOLUTE_PATH.matches(raw) || URI_SCHEME_PREFIX.find(raw) == null -> {
                    relativeTo?.resolve(raw) ?: Path.of(raw)
                }

                else -> {
                    val uri = URI(raw)
                    if (!uri.scheme.equals("file", ignoreCase = true) || !uri.authority.isNullOrEmpty()) return null
                    Path.of(uri)
                }
            }
        val canonical = path.toRealPath()
        canonical.takeIf { Files.isRegularFile(it) }?.toUri()?.toString()
    }.getOrNull()
}

internal fun localPlaybackResource(
    videoId: String,
    hints: ContentHints,
): PlaybackResource? {
    val uri = validatedLocalMediaUrl(hints.endpointParams ?: videoId.takeIf { it.startsWith("file:") }) ?: return null
    return PlaybackResource(
        videoId = videoId,
        url = uri,
        headers = emptyMap(),
        loudnessDb = null,
        expiresAt = null,
        clientName = "local",
        profileId = "device-file",
    )
}

internal fun shouldRequestDesktopVideo(
    showVideoViewer: Boolean,
    showVideoFullscreen: Boolean,
    isVideoSong: Boolean,
    synchronizedPlayback: Boolean = false,
    videoPlaybackEnabled: Boolean = true,
): Boolean = videoPlaybackEnabled && (isVideoSong || (!synchronizedPlayback && (showVideoViewer || showVideoFullscreen)))

internal fun shouldRefreshVideoForManualRequest(
    isVideoAvailable: Boolean,
    currentResourceHasVideo: Boolean,
): Boolean = !isVideoAvailable || !currentResourceHasVideo

internal fun hasFreshVideoResource(
    resource: PlaybackResource?,
    now: Instant,
): Boolean = resource?.hasVideo == true && resource.expiresAt?.let { it > now + 60.seconds } != false

internal fun counterpartPlaybackPosition(
    positionMs: Long,
    positionMap: CounterpartPositionMap?,
    switchToVideo: Boolean,
): Long =
    when {
        positionMap == null -> positionMs
        switchToVideo -> positionMap.mapPosition(positionMs)
        else -> positionMap.reversed().mapPosition(positionMs)
    }

internal fun shouldCommitVideoCounterpartResolution(
    requestedSongId: String,
    currentSongId: String?,
    requestedGeneration: Long,
    currentGeneration: Long,
    requestedPlaybackInstanceId: Long,
    currentPlaybackInstanceId: Long,
): Boolean =
    requestedSongId == currentSongId && requestedGeneration == currentGeneration &&
        requestedPlaybackInstanceId == currentPlaybackInstanceId

internal fun shouldSwitchToCounterpartAudio(
    songId: String,
    resource: PlaybackResource,
): Boolean = resource.videoId == songId && resource.musicVideoId != null

internal fun originalAudioResource(
    songId: String,
    resource: PlaybackResource?,
): PlaybackResource? = resource?.takeIf { it.videoId == songId && it.musicVideoId == null }

internal fun shouldResolveOriginalAudio(
    songId: String,
    resource: PlaybackResource,
    retainedSongId: String?,
): Boolean = shouldSwitchToCounterpartAudio(songId, resource) && retainedSongId != songId

internal data class AttestationRecoveryScope(
    val playbackGeneration: Long,
    val sessionGeneration: Long,
    val videoId: String,
    val profileId: String,
)

internal class DesktopAttestationRecoveryTracker(
    private val retryWindowMs: Long = 120_000L,
) {
    private val refreshTimes = mutableMapOf<AttestationRecoveryScope, Long>()

    internal val trackedRefreshCount: Int
        get() = refreshTimes.size

    fun action(
        category: PlaybackFailureCategory,
        scope: AttestationRecoveryScope?,
        nowMs: Long,
        defaultAction: PlaybackRecoveryAction,
    ): PlaybackRecoveryAction {
        refreshTimes.entries.removeAll { (key, refreshedAtMs) ->
            nowMs - refreshedAtMs !in 0 until retryWindowMs ||
                (
                    scope != null &&
                        (key.playbackGeneration != scope.playbackGeneration || key.sessionGeneration != scope.sessionGeneration)
                )
        }
        return if (
            category == PlaybackFailureCategory.SABR_ATTESTATION &&
            scope != null &&
            refreshTimes.containsKey(scope) &&
            defaultAction == PlaybackRecoveryAction.REFRESH_TOKEN
        ) {
            PlaybackRecoveryAction.RETRY_CLIENT
        } else {
            defaultAction
        }
    }

    fun recordRefresh(
        scope: AttestationRecoveryScope,
        nowMs: Long,
    ) {
        refreshTimes[scope] = nowMs
    }

    fun clear() {
        refreshTimes.clear()
    }
}

internal fun isTokenlessSabrAttestationFailure(
    category: PlaybackFailureCategory,
    resource: PlaybackResource?,
): Boolean =
    category == PlaybackFailureCategory.SABR_ATTESTATION &&
        resource?.sabrBootstrap?.let { it.poToken?.isNotEmpty() != true } == true

internal fun desktopStreamRecoveryHints(
    hints: ContentHints,
    resource: PlaybackResource?,
    category: PlaybackFailureCategory,
    action: PlaybackRecoveryAction,
): ContentHints {
    if (category == PlaybackFailureCategory.URL_EXPIRED) return hints
    val retryHints =
        hints.copy(
            playbackClientOverrideId =
                resource
                    ?.profileId
                    ?.let(PlaybackClientCatalog::manifestIdFromProfileId)
                    ?.takeIf {
                        action == PlaybackRecoveryAction.REFRESH_TOKEN && !isTokenlessSabrAttestationFailure(category, resource) &&
                            !WebPoTokenHealth.tokensRejected()
                    },
        )
    return if (category == PlaybackFailureCategory.SABR_ATTESTATION && action == PlaybackRecoveryAction.RETRY_CLIENT) {
        retryHints.copy(sabrFirst = false)
    } else {
        retryHints
    }
}

internal fun desktopPlaybackSabrFirst(
    hints: ContentHints,
    forceDirect: Boolean,
    devtoolsPlaybackSelection: String,
): Boolean =
    !forceDirect &&
        (hints.sabrFirst || devtoolsPlaybackSelection == PlaybackClientCatalog.SABR_FIRST_ID)

internal fun canUseDesktopVideoResource(
    song: SongItem?,
    resource: PlaybackResource?,
    synchronizedPlayback: Boolean,
): Boolean =
    song != null && resource != null &&
        (!synchronizedPlayback || (song.isVideoSong && (resource.musicVideoId ?: resource.videoId) == song.id))

internal fun directPlaybackQueue(
    target: SongItem,
    radioSongs: List<SongItem>,
    isBlocked: (SongItem) -> Boolean,
): List<SongItem> =
    listOf(target) +
        varyRadioRecommendations(
            radioSongs
                .filterNot { it.id == target.id || isBlocked(it) }
                .distinctBy(SongItem::id),
        )

internal fun retainedAnimatedCoverUrl(
    previousSongId: String?,
    nextSongId: String?,
    previousAlbumId: String?,
    nextAlbumId: String?,
    animatedCoverUrl: String?,
    animatedCoversEnabled: Boolean,
): String? =
    animatedCoverUrl.takeIf {
        animatedCoversEnabled &&
            previousSongId != null &&
            previousSongId == nextSongId &&
            previousAlbumId == nextAlbumId
    }

internal fun resolvedAnimatedCoverUrl(
    currentTrack: SongItem?,
    animatedCoverUrl: String?,
    animatedCoverSongId: String?,
): String? = animatedCoverUrl.takeIf { currentTrack?.id != null && currentTrack.id == animatedCoverSongId }

data class ListenTogetherPlaybackRequest(
    val song: SongItem,
    val queue: List<SongItem> = emptyList(),
    val startIndex: Int = 0,
)

internal fun deviceConnectCanReusePlayback(
    localQueue: MusicQueueSnapshot,
    incomingQueue: MusicQueueSnapshot,
    currentSongId: String?,
    ready: Boolean,
    currentResourceId: String? = currentSongId,
): Boolean {
    if (!ready || currentSongId == null || currentResourceId != currentSongId ||
        currentSongId != incomingQueue.displayedItems.getOrNull(incomingQueue.currentIndex)?.id
    ) {
        return false
    }

    fun MusicQueueSnapshot.withoutAccountTokens() =
        copy(
            displayedItems = displayedItems.map { it.copy(libraryAddToken = null, libraryRemoveToken = null) },
            originalItems = originalItems.map { it.copy(libraryAddToken = null, libraryRemoveToken = null) },
        )
    return localQueue.withoutAccountTokens() == incomingQueue.withoutAccountTokens()
}

internal fun canResolveDeviceConnectTrack(song: SongItem): Boolean = !song.isLocal && !song.id.startsWith("local_")

internal fun MusicPlayer.setPlaying(playing: Boolean) {
    if (playing) resume() else pause()
}

object PlayerState {
    private val _listenTogetherPlaybackRequests = MutableSharedFlow<ListenTogetherPlaybackRequest>(extraBufferCapacity = 1)
    val listenTogetherPlaybackRequests: SharedFlow<ListenTogetherPlaybackRequest> = _listenTogetherPlaybackRequests.asSharedFlow()
    private val database by lazy { GlobalContext.get().get<MusicDatabase>() }
    private val musicVideoResolver by lazy { GlobalContext.get().get<MusicVideoResolver>() }
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private val castNetworkAddresses = ::systemCastNetworkAddresses
    val castManager =
        JvmCastManager(
            scope = scope,
            discoveryFactory = { JmDnsCastDiscovery(castNetworkAddresses) },
            localAddresses = castNetworkAddresses,
            appVersion = BuildConfig.APP_VERSION,
        )
    private val playbackNetworkMonitor =
        JvmPlaybackNetworkMonitor(
            scope,
            playbackUrl = { player.currentPlaybackResource.value?.networkUrl },
        ) { if (!networkRecoveryActive) player.invalidateActiveStreamForNetworkChange() }

    @Volatile private var networkRecoveryActive = false
    private var interruptedNetworkPlaybackInstanceId: Long? = null
    var isReconnecting by mutableStateOf(false)
        private set
    private var lastBlockedAutoSkipSongId: String? = null
    private val failureSkipBudget = PlaybackFailureSkipBudget()
    private val streamRecoveryBudgets = mutableMapOf<String, PlaybackRecoveryBudget>()
    private val attestationRecovery = DesktopAttestationRecoveryTracker()
    private const val REPORTING_TAG = "DeviceConnectReporting"
    private const val TRANSITION_PREPARATION_LEAD_MS = 30_000L

    private data class PlaybackFetchResult(
        val resource: PlaybackResource? = null,
        val failure: Throwable? = null,
    )

    private data class PlaybackPreCacheSelection(
        val currentSongId: String?,
        val nextSongId: String?,
        val reached: Boolean,
        val enabled: Boolean,
    )

    @Volatile
    internal var playbackTransitionGeneration = 0L
    private var playbackTransitionJob: Job? = null
        set(job) {
            field = job
            val generation = playbackTransitionGeneration
            job?.invokeOnCompletion {
                scope.launch {
                    if (field === job && isCurrentPlaybackTransition(generation)) isPlaybackResolving = false
                }
            }
        }
    private var automaticCacheJob: Job? = null
    private var preCacheJob: Job? = null

    private val completedCachePlaybackInstanceId = MutableStateFlow(0L)
    private var automaticCacheAllowedForCurrentTrack = true
    private val restoration = DesktopPlaybackRestoration()
    private var initialRecommendationsGeneration: Long? = null
    private val enrichedAlbums = linkedMapOf<String, AlbumTiny>()
    private val enrichedCounterparts = linkedMapOf<String, SongItem>()
    private var pendingResumePosition: Pair<String, Long>? = null
    private var appliedDeviceQueue: Triple<String, Long, String>? = null
    private val playbackStatePersistence = PlaybackStatePersistence()
    private val playbackStateWrites =
        PlaybackStateWriteQueue(scope, persist = { write ->
            playbackStatePersistence.persist(write.snapshot, write.positionMs, write.repeatMode, write.force)
        })
    private val podcastProgress =
        PodcastPlaybackProgress(
            scope,
            save = { song, position, duration, metadata ->
                PodcastLibrary.updatePlaybackPosition(database, song, position, duration, metadata)
            },
            onError = { Logger.e("PlayerState", "Failed to persist podcast progress", it) },
        )
    private val deviceConnect get() = DesktopDeviceConnect.manager.remote

    @Volatile
    internal var externalReportingEnabled = true
    private val syncSession by lazy { DesktopDeviceConnectSession(queue) { deviceConnect } }

    private var controllerPlayback: DcLocalPlaybackState? = null
    private val deviceConnectPlayback =
        object : DeviceConnectPlaybackPort {
            override val canStartDefaultPlayback: Boolean get() = castManager.state.value.status == CastConnectionStatus.DISCONNECTED

            override suspend fun snapshot(): DcLocalPlaybackState =
                withContext(Dispatchers.Main) {
                    controllerPlayback?.let { return@withContext it.copy(positionMs = player.currentPlaybackPositionMs()) }
                    DcLocalPlaybackState(
                        queue = queue.snapshots.value,
                        title = syncSession.title,
                        repeatMode = DcRepeatMode.valueOf(repeatMode.name),
                        playing = if (deviceConnect.isActive) syncSession.desiredPlaying else player.wantsPlayback,
                        positionMs = pendingResumePosition?.second ?: player.currentPlaybackPositionMs(),
                        durationMs = if (pendingResumePosition != null) playbackDuration.value else player.duration.value,
                        speed = if (deviceConnect.isActive) syncSession.baseSpeed else player.playbackSpeed(),
                        pitch = player.playbackPitch.value,
                        volume = localVolume,
                        ready =
                            player.playbackReady && player.loadFailure.value == null &&
                                (!deviceConnect.isActive || syncSession.ready),
                        finite = syncSession.finite,
                        scrobbleSession = scrobbleManager.session(),
                    )
                }

            override suspend fun apply(
                snapshot: DcPlaybackSnapshot,
                positionMs: Long,
                shouldCommit: () -> Boolean,
            ) {
                applyDeviceConnectSnapshot(snapshot, positionMs, shouldCommit)
            }

            override suspend fun setControllerPlayback(playback: DcLocalPlaybackState?) =
                withContext(Dispatchers.Main) {
                    if (playback != null) {
                        if (externalReportingEnabled) return@withContext
                        if (controllerPlayback == null) {
                            beginPlaybackTransition(allowAutomaticCache = false)
                            stopVideo()
                        }
                        isPlaybackResolving = !playback.ready
                        if (controllerPlayback?.queue != playback.queue) {
                            queue.restore(playback.queue)
                            shuffleEnabled = playback.queue.isShuffled
                            queueChanged()
                        }
                        localRepeatMode = RepeatMode.valueOf(playback.repeatMode.name)
                        syncSession.applyPlayback(playback)
                        syncSession.setReady(false)
                        desiredPlayback.value = playback.playing
                        val track = playback.queue.displayedItems.getOrNull(playback.queue.currentIndex)
                        if (controllerPlayback?.queue?.displayedItems?.getOrNull(controllerPlayback?.queue?.currentIndex ?: -1) !=
                            track
                        ) {
                            updateCurrentTrack(track)
                        }
                    }
                    if (playback == null && controllerPlayback != null) {
                        isPlaybackResolving = false
                        desiredPlayback.value = false
                    }
                    controllerPlayback = playback
                    remoteVolume = playback?.volume
                    volumeState.value = volume
                    player.setControllerPlayback(playback)
                    if (playback !=
                        null
                    ) {
                        scrobbleManager.setReportingEnabled(false, player.currentSong.value, playback.playing, playback.scrobbleSession)
                    }
                }

            override suspend fun setPlaying(playing: Boolean) =
                withContext(Dispatchers.Main) {
                    setPlayingFromSync(playing)
                }

            override suspend fun seekTo(positionMs: Long) =
                withContext(Dispatchers.Main) {
                    seekToPositionInternal(positionMs)
                }

            override suspend fun setVolume(volume: Float) =
                withContext(Dispatchers.Main) {
                    applyVolumeLevel(volume)
                }

            override suspend fun setSpeed(speed: Float) =
                withContext(Dispatchers.Main) {
                    applyPlaybackSpeed(speed)
                }

            override suspend fun setAudioOutputEnabled(enabled: Boolean) =
                withContext(Dispatchers.Main) {
                    syncSession.setAudioOutputEnabled(enabled)
                    updateVideoAudioRouting()
                }

            override suspend fun extendQueue(shouldCommit: () -> Boolean): DcLocalPlaybackState? {
                if (!shouldCommit()) return null
                withContext(Dispatchers.Main) {
                    loadRecommendations(candidateLimit = 6, playImmediately = false, shouldCommit = shouldCommit)
                }
                return snapshot().takeIf { shouldCommit() }
            }

            override suspend fun acknowledge(snapshot: DcPlaybackSnapshot) =
                withContext(Dispatchers.Main) {
                    syncSession.acknowledge(snapshot)
                }

            override fun setExternalReportingEnabled(
                enabled: Boolean,
                session: DcScrobbleSession?,
            ) {
                val reportingChanged = externalReportingEnabled != enabled
                externalReportingEnabled = enabled
                if (reportingChanged) Logger.i(REPORTING_TAG, "platform reporting enabled=$enabled")
                player.setExternalReportingEnabled(enabled)
                val song = player.currentSong.value
                scrobbleManager.setReportingEnabled(enabled, song, player.isPlaying.value, session)
                if (!enabled) {
                    DiscordRpcManager.clear()
                } else {
                    if (reportingChanged) resetPlaybackTimeTracking(song)
                    DiscordRpcState.updateDiscordRpc()
                }
            }
        }

    suspend fun release() {
        withContext(Dispatchers.Main) {
            runCatching { finalizeHistory(close = true) }.exceptionOrNull()?.let {
                Logger.e("PlayerState", "Failed to record final listening session", it)
            }
            val preserveStoredSession = restoration.stop()
            runCatching { if (!preserveStoredSession) flushPlaybackState() }.exceptionOrNull()?.let {
                Logger.e("PlayerState", "Failed to persist final playback state", it)
            }
        }
        deviceConnect.detach(deviceConnectPlayback)
        ListenTogetherState.close()
        playbackTransitionJob?.cancel()
        videoModeRequestJob?.cancel()
        runCatching { video.close() }
        castTimerVolume.setGain(castManager.state.value, 1f)?.let { castManager.setVolume(it) }
        castManager.release()
        playbackNetworkMonitor.stop()
        scope.coroutineContext[Job]?.cancelAndJoin()
    }

    private fun beginPlaybackTransition(
        allowAutomaticCache: Boolean = true,
        resumePosition: Pair<String, Long>? = null,
        preservePreparedNext: Boolean = false,
    ): Long {
        flushPodcastProgress()
        pendingResumePosition = resumePosition
        val generation = ++playbackTransitionGeneration
        radio.cancelPrefetch()
        isPlaybackResolving = true
        restoration.invalidate()
        automaticCacheAllowedForCurrentTrack = allowAutomaticCache
        playbackTransitionJob?.cancel()
        automaticCacheJob?.cancel()
        if (!preservePreparedNext) transitions.cancel()
        streamRecoveryBudgets.clear()
        attestationRecovery.clear()
        video.resetCrashRecovery()
        video.discardSource()
        showPlayerError = false
        playerErrorMessage = ""
        playerErrorDetails = ""
        if (castManager.state.value.status != CastConnectionStatus.DISCONNECTED) {
            scope.launch { castManager.disconnect() }
        }
        return generation
    }

    internal fun isCurrentPlaybackTransition(generation: Long): Boolean = generation == playbackTransitionGeneration

    private suspend fun <T> withTrackedPlaybackTransition(
        generation: Long,
        block: suspend () -> T,
    ): T =
        try {
            withContext(Dispatchers.IO) { block() }
        } finally {
            scope.launch {
                if (isCurrentPlaybackTransition(generation)) isPlaybackResolving = false
            }
        }

    val player = MusicPlayer(playbackCacheCapture = SongCacheManager::capturePlayback)
    val queue = MusicQueue()
    internal val playbackConnected = MutableStateFlow(true)
    internal val desiredPlayback = MutableStateFlow(false)
    var currentTrack: SongItem? by mutableStateOf(null)
        private set
    private val artwork = DesktopAnimatedArtwork(scope)
    val animatedCoverSongId: String? get() = artwork.animatedCoverSongId
    val animatedCoverUrl: String? get() = artwork.animatedCoverUrl
    val currentArtworkUrl: String? get() = currentTrack?.previewThumbnailUrl
    internal var artworkPreview by mutableStateOf(PlayerArtworkPreview())
        private set
    var isPlaying by mutableStateOf(false)
        private set
    private var isPlaybackResolving by mutableStateOf(false)
    private var isPlaybackStalled by mutableStateOf(false)
    val isPlaybackLoading: Boolean get() = isPlaybackResolving || isPlaybackStalled
    var progress by mutableStateOf(0f)
        private set
    var bufferedProgress by mutableStateOf(0f)
        private set
    internal val playbackUiVisible = playbackWindowVisibility().stateIn(scope, SharingStarted.Eagerly, false)
    internal val mediaPositionDiscontinuities = MutableSharedFlow<Long>(extraBufferCapacity = 1)
    val playbackPosition = MutableStateFlow(0L)
    val playbackBufferedPosition = MutableStateFlow(0L)
    val playbackDuration = MutableStateFlow(0L)
    private var localVolume by Settings.shared::volume
    private var remoteVolume by mutableStateOf<Float?>(null)
    val volume: Float get() = remoteVolume ?: localVolume
    internal val volumeState = MutableStateFlow(volume)
    var previousVolume by Settings.shared::previousVolume
    var crossfadeEnabled by Settings.shared::crossfadeEnabled
    var crossfadeDuration by Settings.shared::crossfadeDuration
    val isCrossfading: Boolean get() = transitions.isCrossfading
    private val transitions by lazy {
        DesktopQueueTransitions(
            scope,
            player,
            queue,
            video,
            fetch = { fetchPlaybackResource(it) },
            onSongChanged = { song, resource, previousSongId ->
                if (previousSongId == song.id && externalReportingEnabled) {
                    scrobbleManager.onPlayerStateChanged(player.isPlaying.value, song, newPlayback = true)
                }
                updateCurrentTrack(song, startArtworkFetch = true)
                if (!resource.hasVideo) attachBackgroundVideoResource(song, playbackTransitionGeneration)
            },
            onUnconsumedEof = { skipNext(fromEOF = true) },
            episodeResumePosition = { song, duration -> podcastResumePosition(song, duration) ?: 0L },
            allowed = { !sleepTimerStopAfterCurrentSong && repeatMode != RepeatMode.ONE && !deviceConnect.isActive },
        )
    }

    val isExpanded: Boolean get() = UiState.playerPresentation.isExpanded
    var isFullscreen: Boolean
        get() = UiState.playerPresentation.isFullscreen
        set(value) {
            UiState.playerPresentation.isFullscreen = value
        }
    var shuffleEnabled by Settings.shared::shuffleEnabled
    private var localRepeatMode by mutableStateOf(RepeatMode.OFF)
    var repeatMode: RepeatMode
        get() = localRepeatMode
        set(value) {
            if (deviceConnect.intercept(DcPlaybackCommand(DcPlaybackAction.REPEAT, repeatMode = DcRepeatMode.valueOf(value.name)))) return
            if (isListenTogetherGuest()) return
            localRepeatMode = value
            transitions.queueChanged()
            if (value == RepeatMode.ALL) {
                radio.reset()
                transitions.cancel()
                queue.removeAt(
                    queue.items.value.indices
                        .filter { index ->
                            index != queue.currentIndex.value &&
                                queue.items.value[index]
                                    .queueSourceTitle
                                    .isRecommendationGeneratedQueueSource()
                        }.toSet(),
                )
                queueChanged()
            }
        }
    var isSubtitleActive by mutableStateOf(false)

    private val video =
        DesktopVideoLifecycle(
            scope,
            player,
            queue,
            status = {
                DesktopVideoPlaybackStatus(
                    currentTrack,
                    playbackTransitionGeneration,
                    ::keepsCanonicalMedia,
                    isPlaying,
                    volume,
                    syncSession.audioOutputEnabled,
                    isVideoPresented,
                )
            },
            onUnavailable = {
                val manualRequestFailed = manualVideoSongId == currentTrack?.id && manualVideoSongId != null
                markVideoUnavailable()
                if (manualRequestFailed) {
                    UiState.showVideoViewer = false
                    UiState.showVideoFullscreen = false
                    scope.launch {
                        reportPlayerError(getString(Res.string.music_video_unavailable))
                    }
                }
            },
            fetchSource = { song, wantVideo ->
                fetchPlaybackResource(song.id, song.toContentHints().copy(wantVideo = wantVideo), song = song)
            },
        )
    val videoFrame: VideoFrame? get() = video.videoFrame
    val isVideoAvailable: Boolean get() = video.isVideoAvailable
    val isVideoResolving: Boolean get() = video.isVideoResolving
    val isVideoActive: Boolean get() = video.isVideoActive
    private var videoWindowVisible by mutableStateOf(false)
    private val isVideoPresented: Boolean
        get() =
            Settings.shared.musicVideoPlaybackEnabled && !deviceConnect.isFollowing && currentTrack != null &&
                shouldPresentDesktopVideo(videoWindowVisible, UiState.showVideoViewer, UiState.showVideoFullscreen, isExpanded)

    internal fun setVideoWindowVisible(visible: Boolean) {
        videoWindowVisible = visible
    }

    val mvPositionMap: CounterpartPositionMap? get() = video.mvPositionMap
    val mvVideoId: String? get() = video.mvVideoId
    val mvResolutionFailedForSong: String? get() = video.mvResolutionFailedForSong
    private val videoPlaybackResource: PlaybackResource? get() = video.videoPlaybackResource
    private var videoModeRequestJob: Job? = null
    private var videoModeRequestId = 0L
    private var manualVideoSongId: String? = null

    private fun startVideo(
        force: Boolean = false,
        startMs: Long? = null,
        resetRecovery: Boolean = true,
    ) {
        if (!deviceConnect.isFollowing) video.startVideo(force, startMs, resetRecovery)
    }

    private fun stopVideo() = video.stopVideo()

    private fun updateVideoAudioRouting(level: Float = volume) = video.updateVideoAudioRouting(level)

    fun getVideoStats(): VideoPlayerStats = video.getVideoStats()

    fun copyVideoDebugInfo() = video.copyVideoDebugInfo()

    fun toggleVideoViewer() {
        if (deviceConnect.isFollowing || !Settings.shared.musicVideoPlaybackEnabled) return
        if (UiState.showVideoViewer) {
            closeVideoViewer()
            return
        }
        val song = currentTrack ?: return
        val resource = videoPlaybackResource ?: player.currentPlaybackResource.value ?: return
        if (!isVideoAvailable || !hasFreshVideoResource(resource, Clock.System.now())) {
            refreshVideoForViewer(song)
            return
        }
        UiState.showVideoViewer = true
        scope.launch {
            if (!video.switchToVideoAudio(song, resource)) {
                if (
                    currentTrack?.id == song.id &&
                    (UiState.showVideoViewer || UiState.showVideoFullscreen)
                ) {
                    refreshVideoForViewer(song)
                }
                return@launch
            }
            if (!UiState.showVideoViewer && !UiState.showVideoFullscreen) return@launch
            LyricsState.retimeCurrentLyricsIfPossible()
            startVideo()
        }
    }

    fun requestVideoMode() {
        if (deviceConnect.isFollowing || !Settings.shared.musicVideoPlaybackEnabled) return
        val song = currentTrack ?: return
        if (song.id.startsWith("local_")) return
        if (
            !shouldRefreshVideoForManualRequest(
                isVideoAvailable = isVideoAvailable,
                currentResourceHasVideo =
                    hasFreshVideoResource(
                        videoPlaybackResource ?: player.currentPlaybackResource.value,
                        Clock.System.now(),
                    ),
            )
        ) {
            toggleVideoViewer()
            return
        }
        if (keepsCanonicalMedia()) {
            selectVideoMode(song, playbackTransitionGeneration)
        } else {
            manualVideoSongId = song.id
            refreshVideoForViewer(song)
        }
    }

    private fun refreshVideoForViewer(song: SongItem) {
        video.setResolving(true)
        UiState.showVideoViewer = true
        video.setVideoResource(null)
        attachBackgroundVideoResource(song, playbackTransitionGeneration)
    }

    internal fun keepsCanonicalMedia(): Boolean = deviceConnect.isActive || ListenTogetherState.listenTogetherManager.isInRoom.value

    internal suspend fun resolveVideoCounterpartForLyrics(song: SongItem): Pair<String?, CounterpartPositionMap?> =
        video.resolveCounterpartForLyrics(song)

    private fun selectVideoMode(
        song: SongItem,
        generation: Long,
        resolvedVideo: MusicVideoResult.Found? = null,
    ) {
        val sessionId = deviceConnect.state.value.sessionId
        val queueRevision = deviceConnect.queueRevision
        val trackRevision = deviceConnect.trackRevision
        val playbackInstanceId = player.currentPlaybackInstanceId
        val queueIndex = queue.currentIndex.value
        videoModeRequestJob?.cancel()
        val requestId = ++videoModeRequestId

        fun isCurrentRequest(): Boolean =
            Settings.shared.musicVideoPlaybackEnabled && requestId == videoModeRequestId && isCurrentPlaybackTransition(generation) &&
                sessionId == deviceConnect.state.value.sessionId

        fun isCurrentSource(): Boolean =
            isCurrentRequest() && player.currentPlaybackInstanceId == playbackInstanceId &&
                currentTrack?.id == song.id && queue.currentIndex.value == queueIndex

        videoModeRequestJob =
            scope.launch {
                video.setResolving(true)
                try {
                    val resolved =
                        resolvedVideo ?: if (song.isVideoSong || song.isPodcast) {
                            MusicVideoResult.Found(song.id)
                        } else {
                            withContext(Dispatchers.IO) { musicVideoResolver.resolveMusicVideo(song) } as? MusicVideoResult.Found
                        }
                    if (!isCurrentSource()) return@launch
                    val target =
                        if (resolved?.videoId == song.id) {
                            song
                        } else {
                            resolved?.let {
                                withContext(Dispatchers.IO) { GlobalYouTubeRepository.instance.getSongMetadata(it.videoId) }
                            }
                        }
                    if (!isCurrentSource()) return@launch
                    if (target == null || resolved == null) {
                        reportPlayerError(getString(Res.string.playback_error_no_music_video))
                        return@launch
                    }
                    val positionMs = player.currentPlaybackPositionMs()
                    val mappedPosition = resolved.positionMap?.mapPosition(positionMs) ?: positionMs
                    if (target.id != song.id &&
                        deviceConnect.intercept(
                            DcPlaybackCommand(
                                DcPlaybackAction.REPLACE_CURRENT,
                                songs = listOf(target),
                                positionMs = mappedPosition,
                                expectedTrackRevision = trackRevision,
                            ),
                            expectedQueueRevision = queueRevision,
                            expectedSessionId = sessionId,
                        )
                    ) {
                        UiState.showVideoViewer = true
                        return@launch
                    }
                    if (target.id != song.id && isListenTogetherGuest()) return@launch
                    val resource =
                        withContext(Dispatchers.IO) {
                            fetchPlaybackResource(target.id, target.toContentHints().copy(wantVideo = true), song = target)
                        }
                    if (!isCurrentSource()) return@launch
                    if (resource == null || !resource.hasVideo || (resource.musicVideoId ?: resource.videoId) != target.id) {
                        reportPlayerError(getString(Res.string.playback_error_no_music_video))
                        return@launch
                    }
                    if (target.id != song.id) {
                        if (deviceConnect.isActive || isListenTogetherGuest()) return@launch
                        val playing = player.wantsPlayback
                        val currentPosition = player.currentPlaybackPositionMs()
                        val targetPosition = resolved.positionMap?.mapPosition(currentPosition) ?: currentPosition
                        val prepared =
                            withContext(Dispatchers.IO) {
                                player.playRecovered(
                                    song = target,
                                    resource = resource,
                                    positionMs = targetPosition,
                                    resumePlayback = playing,
                                    shouldCommit = {
                                        isCurrentRequest() && currentTrack?.id == song.id && queue.currentIndex.value == queueIndex &&
                                            !deviceConnect.isActive && !isListenTogetherGuest()
                                    },
                                    replaceCurrentQueueItem = true,
                                )
                            }
                        if (!prepared || !isCurrentRequest()) return@launch
                        queue.replaceCurrent(target)
                        queueChanged()
                        updateCurrentTrack(target, startArtworkFetch = true)
                        desiredPlayback.value = playing
                        ListenTogetherState.listenTogetherManager.publishHostPlaybackChange(trackChanged = true)
                    } else {
                        player.attachPlaybackResource(resource)
                    }
                    UiState.showVideoViewer = true
                    video.setAvailability(true)
                    video.setCounterpart(target.id, resolved.positionMap)
                    LyricsState.retimeCurrentLyricsIfPossible()
                    startVideo(force = true)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    if (isCurrentRequest()) showPlaybackFetchError(error)
                } finally {
                    if (requestId == videoModeRequestId) video.setResolving(false)
                }
            }
    }

    fun closeVideoViewer() {
        videoModeRequestId++
        videoModeRequestJob?.cancel()
        video.setResolving(false)
        video.resetCrashRecovery()
        UiState.showVideoViewer = false
        UiState.showVideoFullscreen = false
        stopVideo()
        LyricsState.restoreOriginalLyricsIfRetimed()
        video.restoreOriginalAudioSource()
    }

    private suspend fun onTrackPlaybackStarted(
        song: SongItem,
        resource: PlaybackResource,
        generation: Long,
    ) {
        if (deviceConnect.isActive && !deviceConnect.isFollowing) {
            syncSession.setPlaying(true)
            syncSession.setReady(true)
            desiredPlayback.value = true
            deviceConnect.localStateChanged(trackChanged = true)
        }
        if (resource.hasVideo) {
            withContext(Dispatchers.Main) {
                video.setVideoResource(resource)
                video.setAvailability(true)
                if (song.isVideoSong || UiState.showVideoViewer || UiState.showVideoFullscreen || isVideoActive) {
                    LyricsState.retimeCurrentLyricsIfPossible()
                }
                if (UiState.showVideoViewer || UiState.showVideoFullscreen || isVideoActive) {
                    startVideo()
                }
            }
        } else {
            if (resource.isSabr) {
                withContext(Dispatchers.Main) { markVideoUnavailable() }
            } else {
                attachBackgroundVideoResource(song, generation)
            }
        }
    }

    internal fun markVideoUnavailable() {
        manualVideoSongId = null
        video.markUnavailable()
    }

    fun toggleExpandedMode() {
        if (isExpanded) exitExpanded() else enterExpanded()
    }

    fun enterExpanded() {
        UiState.playerPresentation.enterExpanded()
        UiState.isPlayerExpanded = true
    }

    fun exitExpanded() {
        UiState.isPlayerExpanded = false
        UiState.playerPresentation.exitExpanded()
    }

    fun toggleFullscreenVideo() {
        UiState.playerPresentation.toggleFullscreenVideo()
        UiState.isPlayerExpanded = isExpanded
        if (UiState.showVideoFullscreen) {
            if (!isVideoActive && !UiState.showVideoViewer) requestVideoMode()
        } else {
            closeVideoViewer()
        }
    }

    fun closeFullscreenPlayer() {
        UiState.playerPresentation.closePlayer()
        UiState.isPlayerExpanded = isExpanded
    }

    private val recommendationHistory = RadioRecommendationHistory()
    private val radio = DesktopQueueRadio(scope, queue, ::queueChanged, recommendationHistory = recommendationHistory)

    var swapPlayerControls by Settings.shared::swapPlayerControls
    var animatedGradient by Settings.shared::animatedGradient

    var showPlayerError by mutableStateOf(false)
    var playerErrorMessage by mutableStateOf("")
        private set
    var playerErrorDetails by mutableStateOf("")
        private set

    private fun reportPlayerError(
        message: String,
        error: Throwable? = null,
        failure: PlaybackFailure? = null,
    ) {
        playerErrorMessage = message
        playerErrorDetails = if (error == null && failure == null) "" else playbackErrorDetails(error, failure)
        showPlayerError = true
    }

    private fun handleTerminalPlaybackFailure(
        category: PlaybackFailureCategory,
        originalCategory: PlaybackFailureCategory = category,
    ) {
        val hasSuccessor = queue.peekNext() != null || (repeatMode == RepeatMode.ALL && queue.items.value.isNotEmpty())
        val advance =
            hasSuccessor && !isListenTogetherGuest() && !deviceConnect.isFollowing &&
                shouldSkipAfterPlaybackFailure(
                    Settings.shared.autoSkipNextOnError,
                    desiredPlayback.value,
                    PlaybackNetworkStateStore.current.connected,
                    repeatMode == RepeatMode.ONE,
                    category,
                    originalCategory,
                ) && failureSkipBudget.tryAdvance(playbackTransitionGeneration)
        val generation = playbackTransitionGeneration
        transitions.cancel()
        player.pause()
        isPlaybackResolving = false
        if (advance) {
            scope.launch {
                if (isCurrentPlaybackTransition(generation)) skipNext(fromFailure = true)
            }
        }
    }

    private suspend fun fetchSelectedPlaybackResource(
        song: SongItem,
        generation: Long,
    ): PlaybackResource? {
        var failure: Throwable? = null
        val resource = fetchPlaybackResource(song, showError = true, onFailure = { failure = it })
        if (resource == null) {
            withContext(Dispatchers.Main) {
                if (isCurrentPlaybackTransition(generation)) {
                    player.beginPendingPlayback(song)
                    updateCurrentTrack(song, startArtworkFetch = true)
                    reportPlayerError(
                        failure?.let { playbackFetchErrorMessage(it) } ?: getString(Res.string.playback_error_track_unavailable),
                        failure,
                    )
                    handleTerminalPlaybackFailure(PlaybackFailureClassifier.fromThrowable(failure))
                }
            }
        }
        return resource
    }

    private fun playbackErrorDetails(
        error: Throwable?,
        failure: PlaybackFailure?,
    ): String {
        val details =
            buildMap {
                currentTrack?.let {
                    put("videoId", it.id)
                    putAll(it.toPlaybackLogDetails())
                }
                player.currentPlaybackResource.value?.let { putAll(it.toPlaybackLogDetails()) }
                failure?.let { putAll(it.toPlaybackLogDetails()) }
                putAll(throwableDiagnostics(error))
            }
        return PlaybackErrorMessages.describe(error) + "\n\n" + formatPlaybackLogDetails(details).trimIndent()
    }

    val likedSongs: List<SongItem>
        get() = SongLikeState.likedSongs
    private val library = DesktopPlaybackLibrary(scope, database = { database })
    val userPlaylists get() = library.userPlaylists
    val userPlaylistMembership get() = library.userPlaylistMembership
    val isUserPlaylistsLoading get() = library.isUserPlaylistsLoading
    var showAddToPlaylistSongs: List<SongItem>
        get() = library.showAddToPlaylistSongs
        set(value) {
            library.showAddToPlaylistSongs = value
        }

    var showAddToPlaylistSong: SongItem?
        get() = library.showAddToPlaylistSong
        set(value) {
            library.showAddToPlaylistSong = value
        }

    internal val scrobbleManager = ScrobbleManager(scope)

    internal fun updateCurrentTrack(
        newTrack: SongItem?,
        startArtworkFetch: Boolean = true,
    ) {
        currentTrack = newTrack?.let(SongMetadataState::apply)
        artworkPreview = artworkPreview.forTrack(currentTrack?.id, currentArtworkUrl)
        artwork.updateTrack(currentTrack, startArtworkFetch)
    }

    init {
        scope.launch { playbackUiVisible.collect(player::setProgressUiVisible) }
        scope.launch {
            observeSponsorBlockSkipping(
                currentSong = player.currentSong,
                currentPosition = player.currentPosition,
                duration = player.duration,
                isPlaying = player.isPlaying,
                enabled = snapshotFlow { Settings.shared.sponsorBlockEnabled },
                canSeek = {
                    Settings.shared.sponsorBlockEnabled && !castManager.state.value.isConnected &&
                        !isListenTogetherGuest() && !deviceConnect.isFollowing
                },
                seekTo = ::seekToPosition,
                onSkipped = {
                    scope.launch { AppState.snackbarHostState.showSnackbar(getString(Res.string.sponsorblock_skipped)) }
                },
            )
        }
        scope.launch {
            snapshotFlow { Settings.shared.songCacheEnabled }.distinctUntilChanged().collect { enabled ->
                if (!enabled) {
                    automaticCacheJob?.cancel()
                    preCacheJob?.cancel()
                    withContext(Dispatchers.IO) { SongCacheManager.cancelPendingCache() }
                }
            }
        }
        scope.launch {
            DiscordRpcManager.accessTokenFlow.map { it != null }.distinctUntilChanged().collect {
                DiscordRpcState.onTrackChanged(currentTrack, scope)
            }
        }
        scope.launch {
            snapshotFlow { ComposeTrayManager.windowVisible }.collect(playbackNetworkMonitor::setForegroundActive)
        }
        playbackNetworkMonitor.setPlaybackActive(false)
        playbackNetworkMonitor.start()
        scope.launch {
            combine(player.playbackStateChanges, player.currentPlaybackResource) { _, resource ->
                player.wantsPlayback && resource != null && resource.clientName != "local"
            }.distinctUntilChanged().collect(playbackNetworkMonitor::setPlaybackActive)
        }
        scope.launch {
            SongMetadataState.revision.collectLatest {
                currentTrack?.let { updateCurrentTrack(SongMetadataState.apply(it), startArtworkFetch = false) }
            }
        }
        scope.launch(Dispatchers.IO) {
            runCatching { HistoryRepository.clearQueuedScrobbles() }
                .onFailure { AppLogger.warning("PlayerState", "Failed to clear legacy queued scrobbles", it) }
        }
        scope.launch {
            snapshotFlow {
                Settings.shared.audioNormalization to Settings.shared.loudnessLevel
            }.collectLatest { updateVideoAudioRouting() }
        }
        scope.launch {
            player.currentPlaybackResource.collectLatest { updateVideoAudioRouting() }
        }
        updateVideoAudioRouting()
        val playbackSpeed = Settings.shared.playbackSpeed.coerceIn(0.5f, 2f)
        player.setPlaybackSpeed(playbackSpeed)
        video.setPlaybackSpeed(playbackSpeed)
        player.setPlaybackPitch(Settings.shared.playbackPitch.coerceIn(0.5f, 2f))

        // Initialize UiState here: creating its states inside the snapshotFlow read snapshot throws.
        UiState.showVideoViewer
        scope.launch {
            snapshotFlow { isVideoPresented }.collect { video.updateVisibility() }
        }
        scope.launch {
            snapshotFlow {
                val song = currentTrack
                val isVideoContext =
                    song != null &&
                        (song.isVideoSong || isVideoActive || UiState.showVideoViewer || UiState.showVideoFullscreen)
                Triple(song?.id, isVideoContext, mvVideoId)
            }.distinctUntilChanged()
                .collectLatest {
                    LyricsState.syncLyricsOffsetForVideoMode()
                }
        }

        scope.launch {
            player.loadFailure.collectLatest { failure ->
                if (failure != null) {
                    val resource = player.currentPlaybackResource.value
                    PlaybackErrorReporter.report(
                        failure = failure,
                        context =
                            PlaybackErrorContext(
                                stage = failure.stage,
                                videoId = currentTrack?.id,
                                clientName = resource?.clientName,
                                profileId = resource?.profileId,
                                recovered = false,
                            ),
                    )
                    reportPlayerError(
                        if (failure.cause?.isAudioOutputUnavailable() ==
                            true
                        ) {
                            getString(Res.string.error_audio_output_unavailable)
                        } else {
                            failure.message
                        },
                        failure.cause,
                        failure,
                    )
                    PlaybackEventLog.error(
                        PlaybackEventKind.PLAYER,
                        "Terminal load failure (${failure.stage}): ${failure.message}. ${PlaybackErrorMessages.describe(failure.cause)}",
                        videoId = currentTrack?.id ?: failure.videoId,
                        clientName = resource?.clientName,
                        profileId = resource?.profileId,
                        details =
                            buildMap {
                                put("stage", failure.stage)
                                failure.sourceName?.let { put("sourceName", it) }
                                failure.code?.let { put("code", it) }
                                failure.sabrFailureKind?.let { put("sabrKind", it.name) }
                                put("playbackPositionMs", failure.playbackPositionMs.toString())
                                resource?.let { putAll(it.toPlaybackLogDetails()) }
                                currentTrack?.let { putAll(it.toPlaybackLogDetails()) }
                            },
                        error = failure.cause,
                    )
                } else if (isPlaybackResolving || player.playbackReady) {
                    showPlayerError = false
                }
            }
        }

        scope.launch {
            TokenlessStreamRenewal.observe(
                player.currentPlaybackResource,
                player.currentPosition,
                isActive = {
                    player.wantsPlayback && !isPlaybackResolving && !transitions.isCrossfading &&
                        !castManager.state.value.isConnected &&
                        !deviceConnect.isFollowing && !networkRecoveryActive
                },
            ) { resource ->
                val song = player.currentSong.value ?: return@observe false
                val resolver = GlobalYouTubeRepository.streamResolver ?: return@observe false
                val instance = player.currentPlaybackInstanceId
                val generation = playbackTransitionGeneration
                val isCurrent = {
                    isCurrentPlaybackTransition(generation) && player.currentPlaybackInstanceId == instance &&
                        player.currentPlaybackResource.value === resource && !transitions.isCrossfading && !isPlaybackResolving &&
                        !castManager.state.value.isConnected && !deviceConnect.isFollowing
                }
                TokenlessStreamRenewal.renew(
                    resource,
                    player::currentPlaybackPositionMs,
                    isCurrent,
                    resolve = { excluded ->
                        resolver.resolveRemote(
                            song.id,
                            song.toContentHints().copy(playbackClientOverrideId = null),
                            playbackAudioQuality(),
                            song,
                            refresh = true,
                            excludedProfiles = excluded,
                        )
                    },
                ) { fresh ->
                    withContext(Dispatchers.IO) {
                        val context = currentCoroutineContext()
                        player.playRecovered(
                            song,
                            fresh,
                            positionMs = player.currentPlaybackPositionMs(),
                            resumePlayback = player.wantsPlayback,
                            handoffPosition = { it },
                            shouldCommit = { context.isActive && isCurrent() },
                            recoveryPlaybackInstanceId = instance,
                            warmReplacement = true,
                        )
                    }
                }
            }
        }

        scope.launch {
            player.streamFailure.collect { failure ->
                val failedVideoId = failure.videoId
                val failedPlaybackInstanceId = failure.playbackInstanceId ?: return@collect
                if (failedPlaybackInstanceId != player.currentPlaybackInstanceId ||
                    interruptedNetworkPlaybackInstanceId == failedPlaybackInstanceId
                ) {
                    return@collect
                }
                val track =
                    transitions.failedOutgoingTrack(failedVideoId, failedPlaybackInstanceId)
                        ?: if (failedVideoId != null) {
                            player.currentSong.value?.takeIf { it.id == failedVideoId } ?: return@collect
                        } else {
                            currentTrack ?: return@collect
                        }
                val videoId = track.id
                if (!PlaybackRecoveryPolicy.canRecover(failure)) {
                    reportPlayerError(failure.message, failure.cause, failure)
                    handleTerminalPlaybackFailure(failure.category)
                    player.finishInterruptedPlayback(failedPlaybackInstanceId, failure.playbackPositionMs)
                    return@collect
                }

                val generation = playbackTransitionGeneration
                val resolver = GlobalYouTubeRepository.streamResolver ?: return@collect
                val hints = track.toContentHints()
                var resource = player.currentPlaybackResource.value
                var recoveryError = failure.cause ?: IllegalStateException(failure.message)
                var category =
                    failure.category.takeUnless { it == PlaybackFailureCategory.UNKNOWN }
                        ?: PlaybackFailureClassifier.fromThrowable(recoveryError)
                val excludedProfiles =
                    PlaybackRecoveryPolicy
                        .recoveryExclusions(
                            category,
                            resource,
                            failure.playbackPositionMs,
                        ).toMutableSet()
                var budget =
                    (streamRecoveryBudgets[videoId] ?: PlaybackRecoveryBudget.starting(System.nanoTime() / 1_000_000L))
                        .forPlaybackFailure(failure.playbackPositionMs, System.nanoTime() / 1_000_000L, category)
                var attempts = 0

                while (
                    !(failure.isMediaSourceFailure && category == PlaybackFailureCategory.NETWORK) &&
                    budget.canStart(System.nanoTime() / 1_000_000L) &&
                    isCurrentPlaybackTransition(generation) &&
                    player.currentPlaybackInstanceId == failedPlaybackInstanceId &&
                    currentTrack?.id == videoId
                ) {
                    val recoveryNowMs = System.nanoTime() / 1_000_000L
                    val decision =
                        PlaybackRecoveryPolicy.decide(
                            category = category,
                            budget = budget,
                            nowMs = recoveryNowMs,
                            resource = resource,
                            positionMs = failure.playbackPositionMs,
                        )
                    budget = decision.budget
                    val attestationScope =
                        if (category == PlaybackFailureCategory.SABR_ATTESTATION) {
                            resource?.profileId?.let {
                                AttestationRecoveryScope(
                                    playbackGeneration = generation,
                                    sessionGeneration = GlobalYouTube.playbackSessionGeneration,
                                    videoId = videoId,
                                    profileId = it,
                                )
                            }
                        } else {
                            null
                        }
                    val action =
                        attestationRecovery.action(
                            category = category,
                            scope = attestationScope,
                            nowMs = recoveryNowMs,
                            defaultAction = decision.action,
                        )
                    if (action == PlaybackRecoveryAction.NONE) break
                    excludedProfiles += PlaybackRecoveryPolicy.recoveryExclusions(category, resource, failure.playbackPositionMs)
                    attempts++

                    if (
                        resource != null &&
                        (
                            category == PlaybackFailureCategory.CDN_FORBIDDEN ||
                                category == PlaybackFailureCategory.SABR_PROTOCOL ||
                                category == PlaybackFailureCategory.SABR_ATTESTATION ||
                                action == PlaybackRecoveryAction.RETRY_CLIENT
                        )
                    ) {
                        resolver.reportPlaybackFailure(
                            videoId = videoId,
                            clientName = resource.clientName,
                            responseCode = recoveryError.findPlaybackHttpStatusCode(),
                            profileId = resource.profileId,
                            hints = hints,
                            category = category,
                        )
                    }

                    val nextResource =
                        if (
                            action == PlaybackRecoveryAction.RETRY_SAME_URL ||
                            action == PlaybackRecoveryAction.REBUILD_RENDERER
                        ) {
                            resource
                        } else if (budget.requestsRemaining <= 0) {
                            null
                        } else {
                            when (action) {
                                PlaybackRecoveryAction.REFRESH_TOKEN -> {
                                    resolver.invalidateAttestation()
                                    attestationScope?.let { attestationRecovery.recordRefresh(it, recoveryNowMs) }
                                }

                                PlaybackRecoveryAction.RELOAD_PLAYER_CONFIG -> {
                                    resolver.refreshPlayerConfigAfterStreamRejection()
                                }

                                PlaybackRecoveryAction.INVALIDATE_NETWORK -> {
                                    resolver.invalidateNetworkScope(PlaybackNetworkStateStore.current)
                                }

                                else -> {}
                            }
                            budget = budget.consumeRequest()
                            val retryHints = desktopStreamRecoveryHints(hints, resource, category, action)
                            try {
                                withContext(Dispatchers.IO) {
                                    val excluded =
                                        excludedProfiles +
                                            PlaybackRecoveryPolicy.recoveryExclusions(category, resource, failure.playbackPositionMs)
                                    if (excluded.isEmpty()) {
                                        resolver.refresh(videoId, retryHints, playbackAudioQuality(), sourceSong = track)
                                    } else {
                                        resolver.resolveRemote(
                                            videoId,
                                            retryHints.copy(playbackClientOverrideId = null),
                                            playbackAudioQuality(),
                                            sourceSong = track,
                                            refresh = true,
                                            excludedProfiles = excluded,
                                        )
                                    }
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                recoveryError = error
                                category = PlaybackFailureClassifier.fromThrowable(error)
                                PlaybackRecoveryPolicy.retainCurrentResourceAfterFailedRefresh(action, resource)
                            }
                        }
                    streamRecoveryBudgets[videoId] = budget
                    if (nextResource == null) continue

                    var prepareFailure: Throwable? = null
                    val recovered =
                        withContext(Dispatchers.IO) {
                            player.playRecovered(
                                song = track,
                                resource = nextResource,
                                positionMs = player.currentPlaybackPositionMs(),
                                resumePlayback = player.wantsPlayback,
                                handoffPosition = { it },
                                shouldCommit = {
                                    isCurrentPlaybackTransition(generation) &&
                                        player.currentPlaybackInstanceId == failedPlaybackInstanceId &&
                                        currentTrack?.id == videoId
                                },
                                onPrepareFailure = { prepareFailure = it },
                                recoveryPlaybackInstanceId = failedPlaybackInstanceId,
                                warmReplacement =
                                    !nextResource.isLive &&
                                        (PlaybackNetworkRecovery.handles(failure, category) || resource?.isSabr == true),
                            )
                        }
                    if (recovered) {
                        showPlayerError = false
                        return@collect
                    }
                    resource = nextResource
                    recoveryError = prepareFailure ?: IllegalStateException("Refreshed stream could not be prepared")
                    category = PlaybackFailureClassifier.fromThrowable(recoveryError)
                    if (PlaybackRecoveryPolicy.requiresFreshUrl(category)) {
                        budget = budget.forPlaybackFailure(failure.playbackPositionMs, System.nanoTime() / 1_000_000L, category)
                    }
                }

                val networkFailure = PlaybackNetworkRecovery.handles(failure, category)
                if (networkFailure) {
                    networkRecoveryActive = true
                    try {
                        val recovered =
                            PlaybackNetworkRecovery.recover(
                                isCurrent = {
                                    isCurrentPlaybackTransition(generation) &&
                                        player.currentPlaybackInstanceId == failedPlaybackInstanceId && currentTrack?.id == videoId
                                },
                                connected = { PlaybackNetworkStateStore.current.connected },
                                wantsPlayback = { player.wantsPlayback },
                                positionMs = { pendingResumePosition?.second ?: player.currentPlaybackPositionMs() },
                                onReconnecting = { isReconnecting = it },
                                stateChanges =
                                    merge(
                                        player.playbackStateChanges.map { Unit },
                                        PlaybackNetworkStateStore.state.map { Unit },
                                    ),
                            ) {
                                val recoveryContext = currentCoroutineContext()
                                attempts++
                                try {
                                    resolver.invalidateNetworkScope(PlaybackNetworkStateStore.current)
                                    val fresh =
                                        withContext(Dispatchers.IO) {
                                            val excluded =
                                                excludedProfiles +
                                                    PlaybackRecoveryPolicy.recoveryExclusions(
                                                        category,
                                                        resource,
                                                        failure.playbackPositionMs,
                                                    )
                                            if (excluded.isEmpty()) {
                                                resolver.refresh(videoId, hints, playbackAudioQuality(), sourceSong = track)
                                            } else {
                                                resolver.resolveRemote(
                                                    videoId,
                                                    hints.copy(playbackClientOverrideId = null),
                                                    playbackAudioQuality(),
                                                    sourceSong = track,
                                                    refresh = true,
                                                    excludedProfiles = excluded,
                                                )
                                            }
                                        }
                                    withContext(Dispatchers.IO) {
                                        player.playRecovered(
                                            song = track,
                                            resource = fresh,
                                            positionMs = pendingResumePosition?.second ?: player.currentPlaybackPositionMs(),
                                            resumePlayback = player.wantsPlayback,
                                            handoffPosition = { pendingResumePosition?.second ?: it },
                                            shouldCommit = {
                                                recoveryContext.isActive && isCurrentPlaybackTransition(generation) &&
                                                    player.currentPlaybackInstanceId == failedPlaybackInstanceId &&
                                                    currentTrack?.id == videoId
                                            },
                                            onPrepareFailure = { it?.let { error -> recoveryError = error } },
                                            recoveryPlaybackInstanceId = failedPlaybackInstanceId,
                                            warmReplacement = !fresh.isLive && pendingResumePosition == null,
                                        )
                                    }
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (error: Exception) {
                                    recoveryError = error
                                    false
                                }
                            }
                        if (recovered) {
                            if (!isCurrentPlaybackTransition(generation) || currentTrack?.id != videoId) return@collect
                            streamRecoveryBudgets[videoId] =
                                PlaybackRecoveryBudget.starting(System.nanoTime() / 1_000_000L).copy(
                                    urlRefreshesRemaining = budget.urlRefreshesRemaining,
                                )
                            interruptedNetworkPlaybackInstanceId = null
                            pendingResumePosition = null
                            showPlayerError = false
                            return@collect
                        }
                    } finally {
                        networkRecoveryActive = false
                    }
                }

                if (
                    isCurrentPlaybackTransition(generation) &&
                    player.currentPlaybackInstanceId == failedPlaybackInstanceId &&
                    currentTrack?.id == videoId
                ) {
                    PlaybackErrorReporter.report(
                        failure = failure.copy(cause = recoveryError),
                        context =
                            PlaybackErrorContext(
                                stage = "stream_retry_exhausted",
                                videoId = videoId,
                                clientName = resource?.clientName,
                                profileId = resource?.profileId,
                                recovered = false,
                                retryCount = attempts,
                                resource = resource,
                            ),
                    )
                    streamRecoveryBudgets.remove(videoId)
                    reportPlayerError(
                        if (recoveryError.isAudioOutputUnavailable()) {
                            getString(
                                Res.string.error_audio_output_unavailable,
                            )
                        } else {
                            failure.message
                        },
                        recoveryError,
                        failure,
                    )
                    if (networkFailure) interruptedNetworkPlaybackInstanceId = failedPlaybackInstanceId
                    val position = player.currentPlaybackPositionMs()
                    pendingResumePosition = videoId to position
                    handleTerminalPlaybackFailure(category, failure.category)
                    player.finishInterruptedPlayback(failedPlaybackInstanceId, position)
                }
            }
        }

        scope.launch {
            player.currentPosition.collect { position ->
                if (player.isPlaying.value) failureSkipBudget.observeProgress(playbackTransitionGeneration, position)
            }
        }
        scope.launch {
            player.isPlaying.collect {
                if (!deviceConnect.isActive && !isPlaybackResolving) desiredPlayback.value = player.wantsPlayback
                deviceConnect.localStateChanged()
            }
        }

        scope.launch {
            combine(player.isStalled, desiredPlayback) { stalled, wanted -> stalled && wanted }.collect { isPlaybackStalled = it }
        }

        scope.launch {
            var wasConnected = false
            var handledEndGeneration: Long? = null
            var checkpointSeekGeneration: Long? = null
            castManager.state.collect { castState ->
                val connectionLost =
                    wasConnected && !castState.isConnected &&
                        (castState.error != null || !Settings.shared.castEnabled) &&
                        castPlayback.shouldResumeAfterDisconnect(castState)
                if (castState.isConnected && !wasConnected && sleepTimerGain < 1f) applySleepTimerGain(sleepTimerGain)
                wasConnected = castState.isConnected
                if (!castState.isConnected) {
                    handledEndGeneration = null
                    castTimerVolume.setGain(castState, 1f)
                }
                if (castState.isConnected) {
                    isPlaying = castState.isPlaying
                    playbackPosition.value = castState.positionMs
                    playbackDuration.value = castState.durationMs
                    progress =
                        if (castState.durationMs > 0L) {
                            castState.positionMs.toFloat() / castState.durationMs.toFloat()
                        } else {
                            0f
                        }
                    val requestedVolume = castTimerVolume.requestedVolume(castState)
                    localVolume = requestedVolume
                    volumeState.value = requestedVolume
                } else if (connectionLost) {
                    resumeLocalAfterCast(castState.positionMs, castState.isPlaying)
                }
                val checkpoint = castState.forQueueEntry(queue.state.value)
                if (checkpoint != null && (!checkpoint.isPlaying || checkpoint.seekGeneration != checkpointSeekGeneration)) {
                    persistPlaybackState(flushPodcast = false)
                }
                checkpointSeekGeneration = checkpoint?.seekGeneration
                if (shouldHandleCastPlaybackEnd(castState, handledEndGeneration)) {
                    handledEndGeneration = castState.mediaGeneration
                    advanceCastQueue()
                }
            }
        }
        scope.launch {
            combine(player.currentPosition, player.bufferedPosition, player.duration) { position, buffered, duration ->
                Triple(position, buffered, duration)
            }.collectLatest { (playerPosition, buffered, playerDuration) ->
                if (!castManager.state.value.isConnected) {
                    val position = pendingResumePosition?.second ?: playerPosition
                    val duration = if (pendingResumePosition != null) (queue.currentItem()?.duration ?: 0L) * 1000L else playerDuration
                    playbackPosition.value = position
                    progress = if (duration > 0L) position.toFloat() / duration else 0f
                    val inVideoMode = isVideoPresented
                    val activeBuf =
                        if (inVideoMode) {
                            val videoBuf = video.bufferedPositionMs(duration)
                            if (buffered > 0L && videoBuf > 0L) {
                                minOf(buffered, videoBuf)
                            } else {
                                maxOf(buffered, videoBuf)
                            }
                        } else {
                            0L
                        }
                    playbackBufferedPosition.value = activeBuf
                    // Whole buffered seconds: the bar cannot show finer steps, and each change recomposes its readers.
                    bufferedProgress = if (duration > 0L) ((activeBuf / 1000L * 1000L).toFloat() / duration).coerceIn(0f, 1f) else 0f
                    playbackDuration.value = duration
                }
            }
        }
        scope.launch {
            combine(player.playbackSpeed, player.duration) { _, _ -> Unit }.collect {
                DiscordRpcState.updateDiscordRpc()
            }
        }
        scope.launch {
            snapshotFlow { Settings.shared.pauseListenHistory }.collect(history::onHistoryPausedChanged)
        }
        scope.launch {
            player.isPlaying.collectLatest {
                if (castManager.state.value.isConnected) return@collectLatest
                isPlaying = it
                history.onPlayingChanged(it)
                if (it) video.resume() else video.pause()
                DiscordRpcState.updateDiscordRpc()
                persistPlaybackState()
            }
        }

        scope.launch {
            player.currentSong.collectLatest { song ->
                coroutineScope {
                    val previousSongId = history.currentSongId
                    val previousTrack = currentTrack

                    updateCurrentTrack(song, startArtworkFetch = true)
                    DiscordRpcState.updateDiscordRpc()
                    if (previousSongId != song?.id) markVideoUnavailable()
                    song?.let {
                        player.currentPlaybackResource.value?.takeIf(PlaybackResource::hasVideo)?.let { resource ->
                            video.setVideoResource(resource)
                            video.retainOriginalAudioForCounterpart(it, resource)
                        }
                        video.setAvailability(!deviceConnect.isFollowing && (it.isVideoSong || videoPlaybackResource != null))
                        if (isVideoAvailable && (UiState.showVideoViewer || UiState.showVideoFullscreen)) startVideo()

                        if (it.isVideoSong) {
                            LyricsState.retimeCurrentLyricsIfPossible()
                            launch {
                                val s =
                                    enrichedCounterparts[it.id]
                                        ?: (musicVideoResolver.resolveMusicTrack(it) as? MusicTrackResult.Found)?.song
                                if (s != null && currentTrack?.id == it.id) {
                                    enrichedCounterparts[it.id] = s
                                    if (enrichedCounterparts.size > 64) enrichedCounterparts.remove(enrichedCounterparts.keys.first())
                                    updateCurrentTrack(
                                        currentTrack?.copy(
                                            thumbnail = s.thumbnail ?: it.thumbnail,
                                            album = s.album ?: it.album,
                                        ),
                                        startArtworkFetch = true,
                                    )
                                    DiscordRpcState.updateDiscordRpc()
                                }
                            }
                        }

                        PlaybackErrorReporter.setActivePlayback(
                            song = it,
                            resource = player.currentPlaybackResource.value?.takeIf { resource -> resource.videoId == it.id },
                        )
                    } ?: run {
                        video.setAvailability(false)
                        if (UiState.showVideoViewer || UiState.showVideoFullscreen) closeVideoViewer()
                        PlaybackErrorReporter.clearActivePlayback()
                    }
                    if (previousTrack?.id != song?.id) streamRecoveryBudgets.clear()
                    if (song?.id != lastBlockedAutoSkipSongId) {
                        lastBlockedAutoSkipSongId = null
                    }
                    if (song != null && isSongBlocked(song)) {
                        autoSkipBlockedSong(song.id)
                        return@coroutineScope
                    }
                    if (song != null) {
                        maybePrefetchRecommendationsForCurrentQueue(song)
                        if (previousSongId != song.id) finalizeHistory(nextSong = song)
                        DiscordRpcState.onTrackChanged(song, scope)
                        if (previousSongId != song.id || previousTrack?.id != song.id || LyricsState.currentLyricsSongId != song.id) {
                            if (LyricsState.currentLyricsSongId != song.id) {
                                LyricsState.clearLyrics()
                            }
                            val shouldFetchLyrics =
                                UiState.showLyricsDrawer ||
                                    (UiState.showVideoFullscreen && UiState.showVideoFullscreenLyrics) ||
                                    isSubtitleActive
                            if (shouldFetchLyrics && LyricsState.currentLyricsSongId != song.id) {
                                LyricsState.fetchLyrics(song)
                            }
                        }
                        if (song.album?.id.isNullOrBlank()) {
                            launch {
                                val album = enrichedAlbums[song.id] ?: GlobalYouTubeRepository.instance.resolveSongAlbum(song)
                                if (album != null && currentTrack?.id == song.id) {
                                    enrichedAlbums[song.id] = album
                                    if (enrichedAlbums.size > 64) enrichedAlbums.remove(enrichedAlbums.keys.first())
                                    updateCurrentTrack(currentTrack?.copy(album = album), startArtworkFetch = true)
                                    DiscordRpcState.updateDiscordRpc()
                                }
                            }
                        }
                        refreshHistory()
                        if (previousSongId == song.id) history.updateMetadata(song)
                    } else {
                        finalizeHistory()
                        LyricsState.clearLyrics()
                        DiscordRpcState.onTrackChanged(null, scope)
                    }
                    DiscordRpcState.updateDiscordRpc()
                    persistPlaybackState()
                }
            }
        }
        scope.launch {
            player.currentPosition.map { it / 1000L }.distinctUntilChanged().collect {
                DiscordRpcState.updateDiscordRpc()
                updatePodcastProgress()
            }
        }
        scope.launch {
            combine(player.currentSong, player.isPlaying) { _, _ -> Unit }.collect { updatePodcastProgress() }
        }
        scope.launch {
            snapshotFlow { UiState.isWindowFocused }.distinctUntilChanged().collect { focused ->
                if (!focused) persistPlaybackState()
            }
        }
        scope.launch {
            combine(player.isPlaying, player.currentSong) { playing, song ->
                playing to song
            }.collectLatest { (playing, song) ->
                if (externalReportingEnabled) scrobbleManager.onPlayerStateChanged(playing, song)
                if (playing && song != null) {
                    player.currentPlaybackResource.value
                        ?.takeIf { it.videoId == song.id }
                        ?.let { scheduleAutomaticCache(song, it) }
                } else if (song == null) {
                    automaticCacheJob?.cancel()
                }
            }
        }
        scope.launch {
            combine(
                player.currentPosition,
                player.currentSong,
                queue.state,
                snapshotFlow { automaticCachingEnabled() },
            ) { position, song, state, enabled ->
                PlaybackPreCacheSelection(
                    currentSongId = song?.id,
                    nextSongId =
                        state.entries
                            .getOrNull(state.currentIndex + 1)
                            ?.song
                            ?.id,
                    reached = position >= PLAYBACK_PRE_CACHE_DELAY_MS,
                    enabled = enabled,
                )
            }.distinctUntilChanged().collectLatest { selection ->
                preCacheJob?.cancel()
                preCacheJob = null
                val next = queue.peekNext()?.takeIf { it.id == selection.nextSongId }
                if (!selection.reached || !selection.enabled || next == null ||
                    next.id == selection.currentSongId || repeatMode == RepeatMode.ONE
                ) {
                    return@collectLatest
                }
                preCacheJob =
                    scope.launch {
                        val resource = fetchPlaybackResource(next) ?: return@launch
                        if (currentTrack?.id != selection.currentSongId || queue.peekNext()?.id != next.id) return@launch
                        withContext(Dispatchers.IO) { SongCacheManager.prefetchPlayback(resource) }
                    }
            }
        }
        scope.launch {
            snapshotFlow {
                isVideoPresented
            }.collectLatest { active ->
                if (!active) {
                    playbackBufferedPosition.value = 0L
                    bufferedProgress = 0f
                    return@collectLatest
                }
                while (isActive) {
                    val dur = playbackDuration.value
                    if (dur > 0L) {
                        val videoBuf = video.bufferedPositionMs(dur)
                        val audioBuf = player.bufferedPosition.value
                        val combinedBuf =
                            if (audioBuf > 0L && videoBuf > 0L) {
                                minOf(audioBuf, videoBuf)
                            } else {
                                maxOf(audioBuf, videoBuf)
                            }
                        if (playbackBufferedPosition.value != combinedBuf) {
                            playbackBufferedPosition.value = combinedBuf
                        }
                        bufferedProgress = ((combinedBuf / 1000L * 1000L).toFloat() / dur.toFloat()).coerceIn(0f, 1f)
                    }
                    delay(100.milliseconds)
                }
            }
        }

        scope.launch {
            player.onEOFPlaybackInstance.collect { completedPlaybackInstanceId ->
                if (!transitions.acceptsEof(completedPlaybackInstanceId)) return@collect
                flushPodcastProgress()
                completedCachePlaybackInstanceId.value = completedPlaybackInstanceId
                if (deviceConnect.isActive) {
                    if (sleepTimerStopAfterCurrentSong) {
                        setPlaybackPlaying(false)
                        clearSleepTimer()
                    } else {
                        deviceConnect.playbackEnded()
                    }
                    return@collect
                }
                if (sleepTimerStopAfterCurrentSong) {
                    transitions.cancel()
                    setPlaybackPlaying(false)
                    clearSleepTimer()
                    return@collect
                }
                if (transitions.onEof(completedPlaybackInstanceId)) return@collect

                skipNext(fromEOF = true)
            }
        }

        scope.launch {
            combine(
                player.currentPosition,
                player.duration,
                queue.state,
                player.playbackSpeed,
                snapshotFlow {
                    Triple(
                        crossfadeEnabled to crossfadeDuration,
                        Settings.shared.crossfadeGapless to repeatMode,
                        Triple(sleepTimerStopAfterCurrentSong, sleepTimerFadeOut, deviceConnect.isActive),
                    )
                },
            ) { pos, dur, _, _, _ -> pos to dur }
                .collectLatest { (pos, dur) ->
                    transitions.queueChanged()
                    if (dur <= 0L || transitions.isCrossfading || deviceConnect.isActive ||
                        sleepTimerStopAfterCurrentSong
                    ) {
                        player.setTransitionSampling(
                            player.currentPlaybackInstanceId,
                            60_000L.takeIf { sleepTimerStopAfterCurrentSong && sleepTimerFadeOut && !deviceConnect.isActive },
                        )
                        return@collectLatest
                    }
                    val speed = player.playbackSpeed.value
                    val remaining = desktopRemainingOutputMs(pos, dur, speed)
                    val next = queue.peekNext()
                    if (next == null || repeatMode == RepeatMode.ONE) {
                        player.setTransitionSampling(player.currentPlaybackInstanceId, null)
                        return@collectLatest
                    }
                    val gapless =
                        Settings.shared.crossfadeGapless && songsShareAlbum(currentTrack, next)
                    val threshold =
                        if (crossfadeEnabled) {
                            playbackCrossfadeDurationMs(
                                enabled = true,
                                preserveSameAlbumGapless = Settings.shared.crossfadeGapless,
                                sameAlbum = gapless,
                                configuredDurationMs = (crossfadeDuration * 1000f).toLong(),
                                outgoingDurationMs = (dur / speed).toLong(),
                                incomingDurationMs = ((next.duration?.times(1000L) ?: dur) / speed).toLong(),
                            )
                        } else {
                            0L
                        }
                    player.setTransitionSampling(player.currentPlaybackInstanceId, threshold, threshold + TRANSITION_PREPARATION_LEAD_MS)
                    if (remaining in 1L..(threshold + TRANSITION_PREPARATION_LEAD_MS) &&
                        pos > DESKTOP_PRELOAD_MIN_POSITION_MS && repeatMode != RepeatMode.ONE
                    ) {
                        transitions.nearEnd(next, gapless, threshold)
                    }
                }
        }

        scope.launch {
            while (true) {
                delay(30000.milliseconds)
                if (!isPlaying || currentTrack == null) DiscordRpcState.updateDiscordRpc()
            }
        }
        scope.launch {
            val playing =
                combine(player.isPlaying, castManager.state, queue.state) { localPlaying, remote, queueState ->
                    remote.forQueueEntry(queueState)?.isPlaying ?: localPlaying
                }
            observePlaybackCheckpoints(playing, snapshotFlow { Settings.shared.persistentQueue }) {
                persistPlaybackState(flushPodcast = false)
            }
        }
        restoration.start(scope) { restorePersistedPlayback() }
        scope.launch {
            restoration.await()
            syncSession.setPlaying(player.wantsPlayback)
            desiredPlayback.value = syncSession.desiredPlaying
            syncSession.setReady(player.currentPlaybackInstanceId != 0L)
            deviceConnect.attach(deviceConnectPlayback)
            deviceConnect.state.collectLatest {
                if (deviceConnect.isActive) {
                    cancelCrossfadeForManualAction()
                    if (deviceConnect.isFollowing) radio.reset()
                } else {
                    syncSession.disconnected()
                }
            }
        }
    }

    private fun updatePodcastProgress() {
        if (pendingResumePosition != null || !player.playbackReady) return
        podcastProgress.update(
            player.currentSong.value,
            video.originalSongPosition(player.currentPlaybackPositionMs()),
            player.duration.value,
            player.isPlaying.value,
        )
    }

    private fun flushPodcastProgress(): Job? {
        updatePodcastProgress()
        return podcastProgress.flush()
    }

    private fun persistPlaybackState(flushPodcast: Boolean = true) {
        if (restoration.pending) return
        if (flushPodcast) flushPodcastProgress()
        playbackStateWrites.submit(
            PlaybackStateWrite(
                queue.snapshots.value,
                persistedPlaybackPositionMs(),
                repeatMode,
            ),
        )
    }

    private fun persistedPlaybackPositionMs(): Long =
        castManager.state.value
            .forQueueEntry(queue.state.value)
            ?.let { remote ->
                desktopCastCheckpointPosition(
                    remote,
                    player.currentPlaybackResource.value?.let { it.musicVideoId ?: it.videoId },
                    video::originalSongPosition,
                )
            }
            ?: pendingResumePosition?.second
            ?: video.originalSongPosition(player.currentPlaybackPositionMs())

    private suspend fun flushPlaybackState() {
        flushPodcastProgress()?.join()
        playbackStateWrites.flush(
            PlaybackStateWrite(
                queue.snapshots.value,
                persistedPlaybackPositionMs(),
                repeatMode,
                force = true,
            ),
        )
    }

    private suspend fun restorePersistedPlayback() {
        val generation = playbackTransitionGeneration
        val state = withContext(Dispatchers.IO) { playbackStatePersistence.restore() } ?: return
        if (!isCurrentPlaybackTransition(generation) || queue.items.value.isNotEmpty()) return
        val restoreQueue = state.queue
        queue.restore(restoreQueue)
        shuffleEnabled = restoreQueue.isShuffled
        localRepeatMode = state.repeatMode
        radio.syncQueue()
        updateCurrentTrack(queue.currentItem(), startArtworkFetch = true)

        pendingResumePosition = queue.currentItem()?.id?.let { it to state.positionMs.coerceAtLeast(0L) }
        playbackPosition.value = state.positionMs.coerceAtLeast(0L)
        playbackDuration.value = (queue.currentItem()?.duration ?: 0L) * 1000L
        progress = if (playbackDuration.value > 0) playbackPosition.value.toFloat() / playbackDuration.value else 0f
    }

    private fun queueChanged() {
        transitions.queueChanged()
        invalidatePersistedRestore()
        radio.syncQueue()
        persistPlaybackState()
    }

    private fun invalidatePersistedRestore() {
        restoration.invalidate()
    }

    private fun remainingQueuedSongs(): Int {
        val currentIndex = queue.currentIndex.value
        if (currentIndex !in queue.items.value.indices) return 0
        return (queue.items.value.size - currentIndex - 1).coerceAtLeast(0)
    }

    private fun canLoadRecommendations(song: SongItem): Boolean =
        Settings.shared.autoLoadMore && repeatMode == RepeatMode.OFF && !isListenTogetherGuest() && !deviceConnect.isFollowing &&
            !syncSession.finite && queue.currentItem()?.id == song.id

    private fun maybePrefetchRecommendationsForCurrentQueue(song: SongItem) {
        if (repeatMode != RepeatMode.OFF || !canLoadRecommendations(song) ||
            initialRecommendationsGeneration == playbackTransitionGeneration
        ) {
            return
        }
        if (remainingQueuedSongs() > 1) return
        val generation = playbackTransitionGeneration
        radio.prefetch(song) { isCurrentPlaybackTransition(generation) && canLoadRecommendations(song) }
    }

    internal fun replaceQueue(
        songs: List<SongItem>,
        startIndex: Int = 0,
        resetRecommendations: Boolean = true,
        songsOnly: Boolean = false,
    ) {
        val startSong = songs.getOrNull(startIndex)
        val blocked = BlockedContent.snapshot()
        val filteredSongs = BlockedContent.filterPlayableSongs(songs, blocked)
        if (filteredSongs.isEmpty()) return
        pendingResumePosition = null
        val adjustedStartIndex =
            if (startSong != null && !startSong.isUnavailable && !blocked.isBlocked(startSong)) {
                songs.take(startIndex).count { !it.isUnavailable && !blocked.isBlocked(it) }
            } else {
                startIndex.coerceIn(0, filteredSongs.lastIndex)
            }

        if (resetRecommendations) {
            radio.reset()
        }
        val retainShuffle = Settings.shared.persistentShuffleAcrossQueues && shuffleEnabled
        syncSession.resetQueueMetadata()
        queue.setQueue(filteredSongs, adjustedStartIndex, recommendationsSongsOnly = songsOnly)
        shuffleEnabled = retainShuffle
        if (retainShuffle) queue.toggleShuffle(legacyShuffle = Settings.shared.useLegacyShuffle)
        queueChanged()
    }

    private suspend fun applyDeviceConnectSnapshot(
        snapshot: DcPlaybackSnapshot,
        positionMs: Long,
        shouldCommit: () -> Boolean,
    ) = withContext(Dispatchers.Main) {
        if (!shouldCommit()) return@withContext
        val state = snapshot.playback
        val reuseInitialTrack =
            syncSession.sessionId == null && !deviceConnect.isFollowing &&
                deviceConnectCanReusePlayback(
                    queue.snapshots.value,
                    state.queue,
                    player.currentSong.value?.id,
                    player.playbackReady,
                    player.currentPlaybackResource.value?.let { it.musicVideoId ?: it.videoId } ?: player.currentSong.value?.id,
                )
        val trackChanged =
            !reuseInitialTrack &&
                (syncSession.sessionId != snapshot.sessionId || syncSession.trackRevision != snapshot.trackRevision)
        if (reuseInitialTrack) syncSession.setReady(true)
        syncSession.apply(snapshot)
        localRepeatMode = RepeatMode.valueOf(state.repeatMode.name)
        val queueIdentity = Triple(snapshot.sessionId, snapshot.queueRevision, snapshot.queueStateId)
        if (appliedDeviceQueue != queueIdentity) {
            if (!queue.snapshots.value.hasSameStructure(state.queue)) queue.restore(state.queue)
            appliedDeviceQueue = queueIdentity
            radio.syncQueue()
        }
        if (queue.currentIndex.value != state.queue.currentIndex) {
            if (state.queue.currentIndex < 0) queue.restore(state.queue) else queue.skipTo(state.queue.currentIndex)
        }
        shuffleEnabled = state.queue.isShuffled
        invalidatePersistedRestore()
        pendingResumePosition = null
        persistPlaybackState()
        val track = queue.currentItem()
        if (track == null) {
            beginPlaybackTransition(allowAutomaticCache = false)
            player.stop()
            updateCurrentTrack(null)
            syncSession.setReady(false)
            return@withContext
        }
        val loadedResourceId = player.currentPlaybackResource.value?.let { it.musicVideoId ?: it.videoId }
        val needsTrackLoad =
            trackChanged || player.currentSong.value?.id != track.id ||
                (loadedResourceId != null && loadedResourceId != track.id) || !syncSession.ready
        if (isSongBlocked(track) || (needsTrackLoad && !canResolveDeviceConnectTrack(track))) {
            beginPlaybackTransition(allowAutomaticCache = false)
            player.stop()
            updateCurrentTrack(track)
            syncSession.setReady(false)
            reportPlayerError(getString(Res.string.playback_error_track_unavailable))
            return@withContext
        }
        if (needsTrackLoad) {
            syncSession.setReady(false)
            val loaded = playTrackInternalSuspend(track, emptyList(), startPaused = true, shouldCommit = shouldCommit)
            if (!shouldCommit()) return@withContext
            syncSession.setReady(loaded)
            if (!loaded) return@withContext
            seekToPositionInternal(positionMs)
        }
        if (!shouldCommit()) return@withContext
        applyPlaybackSpeed(state.speed)
        state.pitch?.let(player::setPlaybackPitch)
        setPlayingFromSync(state.playing)
        scrobbleManager.setReportingEnabled(
            externalReportingEnabled,
            track,
            player.isPlaying.value,
            state.scrobbleSession,
        )
        if (!deviceConnect.isFollowing) maybePrefetchRecommendationsForCurrentQueue(track)
    }

    internal fun setPlayingFromSync(playing: Boolean) {
        syncSession.setPlaying(playing)
        desiredPlayback.value = playing
        video.resetSeek()
        player.setPlaying(playing)
        if (playing) {
            video.resume()
        } else {
            video.pause()
        }
    }

    internal fun currentQueueTitle(): String? = syncSession.title

    internal fun setQueueTitleFromSync(title: String?) {
        syncSession.setTitle(title)
    }

    fun isLiked(songId: String): Boolean = SongLikeState.isLiked(songId)

    fun isDisliked(songId: String): Boolean = SongLikeState.isDisliked(songId)

    fun toggleDislike(song: SongItem) {
        val disliked = SongLikeState.toggleDislike(song)
        if (disliked) {
            relatedSongs = relatedSongs.filterNot { it.id == song.id }
            if (deviceConnect.isActive) {
                removeQueueItems(
                    queue.items.value.indices
                        .filter { queue.items.value[it].id == song.id && it != queue.currentIndex.value }
                        .toSet(),
                )
            } else {
                queue.removeSongs(setOf(song.id), keepCurrent = true)
                queueChanged()
            }
            if (currentTrack?.id == song.id) {
                autoSkipBlockedSong(song.id)
            }
        }
    }

    var sleepTimerEnabled by mutableStateOf(false)
        private set
    val sleepTimerTimeLeftMs = MutableStateFlow(0L)
    var sleepTimerStopAfterCurrentSong by mutableStateOf(false)
        private set
    var sleepTimerFinishCurrentSong by mutableStateOf(false)
        private set
    internal var sleepTimerJob: Job? = null
    private var sleepTimerGain = 1f
    var sleepTimerFadeOut by mutableStateOf(false)
        private set
    private val castTimerVolume = DesktopSleepTimerCastVolume()

    private val related =
        DesktopRelatedSongs(scope, { currentTrack?.id }) {
            BlockedContent.filterPlayableSongs(GlobalYouTubeRepository.instance.startRadio(it)).drop(1).take(25)
        }
    var relatedSongs: List<SongItem>
        get() = related.songs
        private set(value) {
            related.songs = value
        }
    val isRelatedLoading: Boolean get() = related.loading

    val historyEntries get() = library.historyEntries
    val topSongs get() = library.topSongs

    private val history = DesktopPlaybackHistory()

    internal val mediaControls =
        combine(
            snapshotFlow { Triple(currentTrack != null, isPlaybackResolving, isListenTogetherGuest()) },
            queue.state,
            playbackPosition,
            snapshotFlow { repeatMode to Settings.shared.autoLoadMore },
        ) { (hasTrack, loading, guest), queueState, position, (repeat, autoplay) ->
            desktopMediaControls(hasTrack, loading, guest, queueState.entries.size, queueState.currentIndex, position, repeat, autoplay)
        }.stateIn(scope, SharingStarted.Eagerly, DesktopMediaControls())

    var showMiniplayer by Settings.shared::showMiniplayer
    var miniplayerFocusMode by Settings.shared::miniplayerFocusMode

    fun toggleShuffle() {
        if (deviceConnect.intercept(DcPlaybackCommand(DcPlaybackAction.SHUFFLE, enabled = !shuffleEnabled))) return
        if (isListenTogetherGuest()) return
        invalidatePersistedRestore()
        shuffleEnabled = !shuffleEnabled
        queue.toggleShuffle(legacyShuffle = Settings.shared.useLegacyShuffle)
        persistPlaybackState()
    }

    fun cycleRepeatMode() {
        if (isListenTogetherGuest()) return
        invalidatePersistedRestore()
        repeatMode =
            when (repeatMode) {
                RepeatMode.OFF -> RepeatMode.ALL
                RepeatMode.ALL -> RepeatMode.ONE
                RepeatMode.ONE -> RepeatMode.OFF
            }
        persistPlaybackState()
    }

    fun setVolumeLevel(level: Float) {
        if (!level.isFinite()) return
        val volumeLevel = level.coerceIn(0f, 1f)
        if (deviceConnect.intercept(DcPlaybackCommand(DcPlaybackAction.VOLUME, volume = volumeLevel))) return
        applyVolumeLevel(volumeLevel)
    }

    private fun applyVolumeLevel(volumeLevel: Float) {
        localVolume = volumeLevel
        volumeState.value = volumeLevel
        if (castManager.state.value.isConnected) {
            val outputVolume = castTimerVolume.setRequestedVolume(volumeLevel)
            scope.launch { castManager.setVolume(outputVolume) }
        } else {
            updateVideoAudioRouting(volumeLevel)
        }
    }

    fun startCastDiscovery() = castManager.startDiscovery()

    fun stopCastDiscovery() = castManager.stopDiscovery()

    private val castPlayback by lazy {
        DesktopCastPlayback(scope, player, queue, video::stopVideo, castManager) { song, castProtocol ->
            if (castProtocol != null) {
                fetchPlaybackResource(
                    song.id,
                    song.toContentHints().copy(wantVideo = false),
                    song = song,
                    forceDirect = true,
                    audioQuality = castAudioQuality(castProtocol),
                )
            } else {
                fetchPlaybackResource(song, showError = true)
            }
        }
    }

    fun castTo(device: CastDevice) {
        if (!Settings.shared.castEnabled) return
        if (isListenTogetherGuest() || deviceConnect.isActive) return
        if (castManager.state.value.device
                ?.id != device.id
        ) {
            restoreCastTimerVolume()
        }
        castPlayback.castTo(device) {
            DesktopCastHandoffState(
                currentTrack,
                currentArtworkUrl,
                currentMediaPositionMs(),
                playbackDuration.value,
                isPlaying,
                volume,
                player.currentPlaybackInstanceId,
            )
        }
    }

    fun disconnectCast() {
        restoreCastTimerVolume()
        castPlayback.disconnect { currentTrack }
    }

    private fun restoreCastTimerVolume() {
        val remote = castManager.state.value
        val restored = castTimerVolume.setGain(remote, 1f) ?: return
        scope.launch {
            if (castManager.state.value.device
                    ?.id == remote.device?.id
            ) {
                castManager.setVolume(restored)
            }
        }
    }

    private suspend fun resumeLocalAfterCast(
        positionMs: Long,
        resumePlayback: Boolean,
    ) = castPlayback.resumeLocal({ currentTrack }, positionMs, resumePlayback)

    private suspend fun advanceCastQueue() {
        if (sleepTimerStopAfterCurrentSong) {
            setPlaybackPlaying(false)
            clearSleepTimer()
            return
        }
        val decision =
            castPlayback.advance(currentTrack, repeatMode) {
                loadRecommendations(candidateLimit = 4, playImmediately = false)
            } ?: return
        val song = decision.song ?: return
        when (decision.action) {
            PlaybackQueueAction.PLAY_NEXT -> queue.getNext()
            PlaybackQueueAction.RESTART_QUEUE -> queue.skipTo(0)
            else -> Unit
        }
        if (castManager.state.value.isConnected) {
            player.beginPendingPlayback(song)
            updateCurrentTrack(song, startArtworkFetch = true)
        } else {
            playTrackInternal(song, emptyList())
        }
        queueChanged()
    }

    internal fun replaceUpcomingQueueFromListenTogether(songs: List<SongItem>) {
        if (player.currentSong.value == null) {
            queue.setQueue(songs, startIndex = -1)
        } else {
            queue.replaceUpcomingQueue(songs)
        }
        queueChanged()
    }

    internal fun addToQueueFromListenTogether(song: SongItem) {
        queue.addToQueue(song, Settings.shared.preventDuplicateTracksInQueue)
        queueChanged()
    }

    fun setPlaybackSpeed(speed: Float) {
        if (!speed.isFinite()) return
        val normalizedSpeed = speed.coerceIn(0.5f, 2f)
        if (deviceConnect.intercept(DcPlaybackCommand(DcPlaybackAction.SPEED, speed = normalizedSpeed))) return
        if (ListenTogetherState.listenTogetherManager.isInRoom.value) return
        Settings.shared.playbackSpeed = normalizedSpeed
        applyPlaybackSpeed(normalizedSpeed)
    }

    internal fun applyPlaybackSpeed(speed: Float) {
        val normalizedSpeed = speed.coerceIn(0.5f, 2f)
        player.setPlaybackSpeed(normalizedSpeed)
        video.setPlaybackSpeed(normalizedSpeed)
    }

    fun setPlaybackPitch(pitch: Float) {
        if (!pitch.isFinite()) return
        val normalizedPitch = pitch.coerceIn(0.5f, 2f)
        if (deviceConnect.intercept(DcPlaybackCommand(DcPlaybackAction.PITCH, pitch = normalizedPitch))) return
        Settings.shared.playbackPitch = normalizedPitch
        player.setPlaybackPitch(normalizedPitch)
    }

    fun setVideoTargetSize(
        width: Int,
        height: Int,
    ) {
        video.setTargetSize(width, height)
    }

    fun setVideoCropTargetSize(
        width: Int,
        height: Int,
    ) {
        video.setTargetSize(width, height, fillViewport = true)
    }

    fun isListenTogetherGuest(): Boolean = ListenTogetherState.listenTogetherManager.isGuestPlaybackLocked

    fun playQueueOccurrence(key: Long) {
        val entry =
            queue.state.value.entries
                .firstOrNull { it.key == key } ?: return
        playTrack(entry.song, queue.items.value)
    }

    private fun localSongChangeStartsPlayback(): Boolean =
        castManager.state.value.isConnected ||
            shouldPlayAfterSongChange(
                keepPaused = Settings.shared.keepPausedOnSongChange,
                hasCurrentSong = currentTrack != null,
                playbackRequested = if (isPlaybackResolving) desiredPlayback.value else player.wantsPlayback,
            )

    fun playTrack(
        track: SongItem,
        list: List<SongItem>? = null,
        songsOnly: Boolean = false,
    ) {
        if (track.isUnavailable) return
        if (list?.any { it.isUnavailable } == true) {
            playTrack(track, list.filterNot { it.isUnavailable }, songsOnly)
            return
        }
        val songs = list?.takeIf { it.isNotEmpty() } ?: listOf(track)
        val index = songs.indexOfPlaylistOccurrence(track).coerceAtLeast(0)
        val action = if (list === queue.items.value) DcPlaybackAction.SELECT else DcPlaybackAction.REPLACE_QUEUE
        if (deviceConnect.intercept(
                DcPlaybackCommand(action, songs = songs, index = index, finite = list != null),
                expectedQueue = queue.snapshots.value,
            )
        ) {
            return
        }
        if (isListenTogetherGuest()) {
            val startIndex = list?.indexOfFirst { it.id == track.id }?.coerceAtLeast(0) ?: 0
            _listenTogetherPlaybackRequests.tryEmit(
                ListenTogetherPlaybackRequest(
                    song = track,
                    queue = list ?: emptyList(),
                    startIndex = startIndex,
                ),
            )
            return
        }

        playTrackInternal(
            track,
            list,
            startPaused = !localSongChangeStartsPlayback(),
            songsOnly = songsOnly || (list === queue.items.value && queue.recommendationsSongsOnly),
        )
    }

    internal suspend fun fetchPlaybackResource(
        song: SongItem,
        showError: Boolean = false,
        onFailure: (Throwable) -> Unit = {},
    ): PlaybackResource? {
        val wantVideo =
            shouldRequestDesktopVideo(
                showVideoViewer = UiState.showVideoViewer,
                showVideoFullscreen = UiState.showVideoFullscreen,
                isVideoSong = song.isVideoSong,
                synchronizedPlayback = keepsCanonicalMedia(),
                videoPlaybackEnabled = Settings.shared.musicVideoPlaybackEnabled,
            )
        val hints = song.toContentHints().copy(wantVideo = wantVideo)
        val resource = fetchPlaybackResource(song.id, hints, showError && !wantVideo, song, onFailure = onFailure)
        if (resource == null && wantVideo) {
            return fetchPlaybackResource(song.id, hints.copy(wantVideo = false), showError, song, onFailure = onFailure)
        }
        return resource
    }

    internal suspend fun fetchPlaybackResource(
        videoId: String,
        hints: ContentHints,
        showError: Boolean = false,
        song: SongItem? = null,
        forceDirect: Boolean = false,
        audioQuality: AudioQuality? = null,
        onFailure: (Throwable) -> Unit = {},
    ): PlaybackResource? {
        val isLiveStream = hints.isLive == true || song?.isLive == true
        val playbackHints =
            hints.copy(
                isLive = if (isLiveStream) true else hints.isLive,
                wantVideo = hints.wantVideo && Settings.shared.musicVideoPlaybackEnabled,
                playbackClientOverrideId =
                    when {
                        forceDirect -> null
                        else -> forcedPlaybackClientId() ?: if (isLiveStream) "ANDROID" else null
                    },
                sabrFirst = desktopPlaybackSabrFirst(hints, forceDirect, Settings.shared.devtoolsPlaybackClient),
            )
        val result = resolvePlaybackResource(videoId, playbackHints, song, audioQuality, forceDirect)
        val failure = result.failure
        if (failure != null) onFailure(failure)
        if (showError && failure != null) {
            withContext(Dispatchers.Main) {
                showPlaybackFetchError(failure)
            }
            PlaybackErrorReporter.report(
                error = failure,
                context =
                    PlaybackErrorContext(
                        stage = "stream_resolve_direct",
                        videoId = videoId,
                        recovered = false,
                        song = song,
                    ),
            )
        }
        return result.resource
            ?.takeUnless { keepsCanonicalMedia() && (it.musicVideoId ?: it.videoId) != videoId }
            ?.copy(videoId = videoId)
    }

    private suspend fun resolvePlaybackResource(
        videoId: String,
        hints: ContentHints,
        sourceSong: SongItem?,
        audioQuality: AudioQuality? = null,
        forceRemote: Boolean = false,
    ): PlaybackFetchResult =
        try {
            val resource =
                if (hints.isLocal == true || videoId.startsWith("local_")) {
                    localPlaybackResource(videoId, hints)
                } else {
                    val resolver =
                        GlobalYouTubeRepository.streamResolver
                            ?: throw IllegalStateException("Stream resolver is not available")
                    if (forceRemote) {
                        resolver.resolveRemote(videoId, hints, audioQuality ?: playbackAudioQuality(), sourceSong = sourceSong)
                    } else {
                        resolver.resolve(videoId, hints, audioQuality ?: playbackAudioQuality(), sourceSong = sourceSong)
                    }
                }
            PlaybackFetchResult(resource = resource)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            PlaybackFetchResult(failure = e)
        }

    private fun playbackAudioQuality(): AudioQuality =
        try {
            AudioQuality
                .valueOf(Settings.shared.audioQuality)
        } catch (_: Exception) {
            AudioQuality.AUTO
        }

    private suspend fun showPlaybackFetchError(error: Throwable) {
        reportPlayerError(playbackFetchErrorMessage(error), error)
        PlaybackEventLog.error(
            PlaybackEventKind.RESOLVE,
            PlaybackErrorMessages.describe(error),
            videoId = currentTrack?.id,
            clientName = player.currentPlaybackResource.value?.clientName,
            profileId = player.currentPlaybackResource.value?.profileId,
            details =
                buildMap {
                    currentTrack?.let { putAll(it.toPlaybackLogDetails()) }
                    player.currentPlaybackResource.value?.let { putAll(it.toPlaybackLogDetails()) }
                },
            error = error,
        )
    }

    private suspend fun playbackFetchErrorMessage(error: Throwable): String = getString(error.playbackErrorResource(AuthState.isSignedIn))

    internal fun playTrackInternal(
        track: SongItem,
        list: List<SongItem>? = null,
        startPaused: Boolean = false,
        songsOnly: Boolean = false,
    ) {
        cancelCrossfadeForManualAction()
        val restorePosition = pendingResumePosition?.takeIf { list?.isEmpty() == true && it.first == track.id }
        val generation = beginPlaybackTransition(resumePosition = restorePosition)
        desiredPlayback.value = !startPaused
        playbackTransitionJob =
            scope.launch {
                val playableTrack: SongItem
                if (!list.isNullOrEmpty()) {
                    val startIndex = list.indexOfPlaylistOccurrence(track).takeIf { it >= 0 } ?: 0
                    if (list === queue.items.value) {
                        queue.skipTo(startIndex)
                    } else {
                        replaceQueue(list, startIndex, songsOnly = songsOnly)
                    }
                    playableTrack = queue
                        .currentItem()
                        ?.takeIf { current -> !isSongBlocked(current) && list.any { it.id == current.id } }
                        ?: return@launch
                    Logger.i("PlayerState", "Set up queue with ${list.size} songs, starting at index $startIndex")
                } else if (list == null) {
                    if (isSongBlocked(track)) return@launch
                    replaceQueue(listOf(track), 0)
                    playableTrack = queue.currentItem()?.takeIf { !isSongBlocked(it) } ?: return@launch
                    Logger.i("PlayerState", "Set up single-song queue")
                } else {
                    if (isSongBlocked(track)) return@launch
                    playableTrack = track
                }

                val resumePosition =
                    pendingResumePosition?.takeIf { it.first == playableTrack.id }?.second
                        ?: podcastResumePosition(playableTrack)
                if (!isCurrentPlaybackTransition(generation)) return@launch
                pendingResumePosition = resumePosition?.let { playableTrack.id to it }
                val preparePaused = startPaused || resumePosition != null
                val forcedClientId = forcedPlaybackClientId()
                val localResource =
                    withContext(Dispatchers.IO) {
                        if (forcedClientId == null) DownloadManager.getLocalPlaybackResource(playableTrack.id, playableTrack) else null
                    }
                val cachedResource =
                    if (forcedClientId == null && localResource == null) {
                        withContext(
                            Dispatchers.IO,
                        ) { runCatching { SongCacheManager.getCachedPlaybackResource(playableTrack.id, playableTrack) }.getOrNull() }
                    } else {
                        null
                    }
                if (!isCurrentPlaybackTransition(generation)) return@launch
                val pendingTransitionId = player.beginPendingPlayback(playableTrack)
                if (currentTrack?.id != playableTrack.id || history.currentSongId != playableTrack.id) {
                    LyricsState.clearLyrics()
                    val shouldFetchLyrics =
                        UiState.showLyricsDrawer ||
                            (UiState.showVideoFullscreen && UiState.showVideoFullscreenLyrics) ||
                            isSubtitleActive
                    if (shouldFetchLyrics) {
                        LyricsState.fetchLyrics(playableTrack)
                    }
                }
                updateCurrentTrack(playableTrack, startArtworkFetch = true)
                markVideoUnavailable()
                video.setResolving(true)
                if (localResource != null) {
                    val played =
                        withContext(Dispatchers.IO) { player.playPending(playableTrack, localResource, pendingTransitionId, preparePaused) }
                    if (played) {
                        attachBackgroundVideoResource(playableTrack, generation)
                        Logger.i("PlayerState", "Started local playback")
                    } else if (isCurrentPlaybackTransition(generation)) {
                        video.setResolving(false)
                    }
                } else if (cachedResource != null) {
                    val played =
                        withContext(
                            Dispatchers.IO,
                        ) { player.playPending(playableTrack, cachedResource, pendingTransitionId, preparePaused) }
                    if (played) {
                        attachBackgroundVideoResource(playableTrack, generation)
                        Logger.i("PlayerState", "Started cached playback")
                    } else if (isCurrentPlaybackTransition(generation)) {
                        video.setResolving(false)
                    }
                } else {
                    val resource = withContext(Dispatchers.IO) { fetchSelectedPlaybackResource(playableTrack, generation) }
                    if (resource != null && isCurrentPlaybackTransition(generation)) {
                        val liveTrack =
                            if (resource.isLive && !playableTrack.isLive) {
                                val updated = playableTrack.copy(musicVideoType = MUSIC_VIDEO_TYPE_LIVE)
                                queue.replaceCurrent(updated)
                                withContext(Dispatchers.Main) {
                                    updateCurrentTrack(updated, startArtworkFetch = false)
                                }
                                updated
                            } else {
                                playableTrack
                            }
                        val played =
                            withContext(Dispatchers.IO) {
                                player.playPending(liveTrack, resource, pendingTransitionId, preparePaused)
                            }
                        if (played) {
                            onTrackPlaybackStarted(liveTrack, resource, generation)
                            Logger.i("PlayerState", "Started playback")
                        }
                        if (!played && isCurrentPlaybackTransition(generation)) video.setResolving(false)
                    } else if (isCurrentPlaybackTransition(generation)) {
                        video.setResolving(false)
                    }
                }
                if (resumePosition != null && playableTrack.duration == null && restorePosition == null &&
                    pendingResumePosition?.second == resumePosition
                ) {
                    val position = podcastResumePosition(playableTrack, player.duration.value)
                    if (!isCurrentPlaybackTransition(generation)) return@launch
                    pendingResumePosition = position?.let { playableTrack.id to it }
                }
                if (completeDesktopPlaybackResume(
                        positionMs = pendingResumePosition?.second ?: resumePosition,
                        prepared = player.playbackReady,
                        currentTransition = isCurrentPlaybackTransition(generation),
                        desiredPlaying = { desiredPlayback.value },
                        seek = player::seekTo,
                        setPlaying = player::setPlaying,
                    )
                ) {
                    pendingResumePosition = null
                    persistPlaybackState()
                }
            }
    }

    /** Waits until playback starts, for Listen Together synchronization. */
    internal suspend fun playTrackInternalSuspend(
        track: SongItem,
        list: List<SongItem>? = null,
        startIndex: Int? = null,
        startPaused: Boolean = false,
        shouldCommit: () -> Boolean = { true },
    ): Boolean {
        if (!shouldCommit()) return false
        val generation = withContext(Dispatchers.Main) { beginPlaybackTransition(allowAutomaticCache = false) }

        val loadContext = currentCoroutineContext()

        fun isCurrent(): Boolean = loadContext.isActive && shouldCommit() && isCurrentPlaybackTransition(generation)
        return withTrackedPlaybackTransition(generation) {
            val stagedQueue =
                withContext(Dispatchers.Main) {
                    if (!isCurrent()) return@withContext null
                    val songs = list ?: listOf(track)
                    if (songs.isEmpty()) return@withContext null
                    val index = startIndex ?: songs.indexOfPlaylistOccurrence(track).takeIf { it >= 0 } ?: 0
                    val selected = songs.getOrNull(index)
                    val blocked = BlockedContent.snapshot()
                    val playable = BlockedContent.filterPlayableSongs(songs, blocked)
                    val adjustedIndex =
                        if (selected != null && !selected.isUnavailable && !blocked.isBlocked(selected)) {
                            songs.take(index).count { !it.isUnavailable && !blocked.isBlocked(it) }
                        } else {
                            index.coerceIn(0, playable.lastIndex.coerceAtLeast(0))
                        }
                    MusicQueue().apply { setQueue(playable, adjustedIndex) }.also {
                        if (it.currentItem() != null) player.pause()
                    }
                }
            val playableTrack =
                if (list?.isEmpty() == true) track.takeIf { !isSongBlocked(it) } else stagedQueue?.currentItem()
            if (playableTrack == null) return@withTrackedPlaybackTransition false

            val forcedClientId = forcedPlaybackClientId()
            val localResource =
                withContext(Dispatchers.IO) {
                    if (forcedClientId == null) DownloadManager.getLocalPlaybackResource(playableTrack.id, playableTrack) else null
                }
            val cachedResource =
                if (forcedClientId == null && localResource == null) {
                    withContext(
                        Dispatchers.IO,
                    ) { runCatching { SongCacheManager.getCachedPlaybackResource(playableTrack.id, playableTrack) }.getOrNull() }
                } else {
                    null
                }
            if (!isCurrent()) return@withTrackedPlaybackTransition false
            val pendingTransitionId =
                withContext(Dispatchers.Main) {
                    if (!isCurrent()) return@withContext null
                    val pendingTransitionId = if (stagedQueue == null) player.beginPendingPlayback(playableTrack) else null
                    if (pendingTransitionId != null) {
                        if (currentTrack?.id != playableTrack.id || history.currentSongId != playableTrack.id) {
                            LyricsState.clearLyrics()
                            val shouldFetchLyrics =
                                UiState.showLyricsDrawer ||
                                    (UiState.showVideoFullscreen && UiState.showVideoFullscreenLyrics) ||
                                    isSubtitleActive
                            if (shouldFetchLyrics) LyricsState.fetchLyrics(playableTrack)
                        }
                        updateCurrentTrack(playableTrack, startArtworkFetch = true)
                        markVideoUnavailable()
                    }
                    video.setResolving(true)
                    pendingTransitionId
                }
            if (!isCurrent()) return@withTrackedPlaybackTransition false

            suspend fun prepareTrack(
                song: SongItem,
                resource: PlaybackResource,
            ): Boolean =
                if (pendingTransitionId != null) {
                    player.playPending(song, resource, pendingTransitionId, startPaused, ::isCurrent)
                } else {
                    player.prepareAndPlay(song, resource, resumePlayback = !startPaused, shouldCommit = ::isCurrent)
                }
            if (localResource != null) {
                if (!isCurrent()) return@withTrackedPlaybackTransition false
                if (!prepareTrack(playableTrack, localResource)) {
                    withContext(Dispatchers.Main) {
                        if (isCurrent()) video.setResolving(false)
                    }
                    return@withTrackedPlaybackTransition false
                }
                attachBackgroundVideoResource(playableTrack, generation)
                Logger.i("PlayerState", "Started local playback (suspend)")
            } else if (cachedResource != null) {
                if (!isCurrent()) return@withTrackedPlaybackTransition false
                if (!prepareTrack(playableTrack, cachedResource)) {
                    withContext(Dispatchers.Main) {
                        if (isCurrent()) video.setResolving(false)
                    }
                    return@withTrackedPlaybackTransition false
                }
                attachBackgroundVideoResource(playableTrack, generation)
                Logger.i("PlayerState", "Started cached playback (suspend)")
            } else {
                val resource = fetchPlaybackResource(playableTrack, showError = true)
                if (resource != null && isCurrent()) {
                    val liveTrack =
                        if (resource.isLive && !playableTrack.isLive) {
                            val updated = playableTrack.copy(musicVideoType = MUSIC_VIDEO_TYPE_LIVE)
                            (stagedQueue ?: queue).replaceCurrent(updated)
                            updated
                        } else {
                            playableTrack
                        }
                    if (!prepareTrack(liveTrack, resource)) {
                        withContext(Dispatchers.Main) {
                            if (isCurrent()) video.setResolving(false)
                        }
                        return@withTrackedPlaybackTransition false
                    }
                    if (resource.hasVideo) {
                        withContext(Dispatchers.Main) {
                            video.setAvailability(true)
                            if (playableTrack.isVideoSong || UiState.showVideoViewer || UiState.showVideoFullscreen || isVideoActive) {
                                LyricsState.retimeCurrentLyricsIfPossible()
                            }
                            if (UiState.showVideoViewer || UiState.showVideoFullscreen || isVideoActive) {
                                startVideo()
                            }
                        }
                    } else {
                        if (resource.isSabr) {
                            withContext(Dispatchers.Main) { markVideoUnavailable() }
                        } else {
                            attachBackgroundVideoResource(playableTrack, generation)
                        }
                    }
                    Logger.i("PlayerState", "Started playback (suspend)")
                } else {
                    withContext(Dispatchers.Main) {
                        if (isCurrent()) video.setResolving(false)
                    }
                    return@withTrackedPlaybackTransition false
                }
            }

            var waited = 0L
            while (isCurrent() && currentTrack?.id != playableTrack.id && waited < 5000L) {
                delay(50.milliseconds)
                waited += 50
            }
            withContext(Dispatchers.Main) {
                if (!isCurrent() || currentTrack?.id != playableTrack.id) return@withContext false
                if (stagedQueue != null) replaceQueue(stagedQueue.items.value, stagedQueue.currentIndex.value)
                true
            }
        }
    }

    private suspend fun podcastResumePosition(
        song: SongItem,
        durationMs: Long = song.duration?.times(1000L) ?: 0L,
    ): Long? {
        if (!song.isPodcast || isListenTogetherGuest() || deviceConnect.isActive) return null
        podcastProgress.flush()?.join()
        return withContext(Dispatchers.IO) { PodcastLibrary.resumePosition(database, song, durationMs = durationMs) }
    }

    private suspend fun playQueueTrack(
        song: SongItem,
        resource: PlaybackResource,
        generation: Long,
        startPlaying: Boolean,
    ) {
        val previousSongId = player.currentSong.value?.id
        val position = podcastResumePosition(song, (song.duration ?: resource.durationSeconds)?.times(1000L) ?: 0L)
        if (!isCurrentPlaybackTransition(generation)) return
        pendingResumePosition = position?.let { song.id to it }
        withContext(Dispatchers.IO) { player.play(song, resource, startPaused = !startPlaying || position != null) }
        if (!isCurrentPlaybackTransition(generation)) return
        if (completeDesktopPlaybackResume(
                positionMs = pendingResumePosition?.second,
                prepared = player.playbackReady,
                currentTransition = true,
                desiredPlaying = { desiredPlayback.value },
                seek = player::seekTo,
                setPlaying = player::setPlaying,
            )
        ) {
            pendingResumePosition = null
        }
        if (previousSongId == song.id && externalReportingEnabled) {
            scrobbleManager.onPlayerStateChanged(startPlaying, song, newPlayback = true)
        }
        onTrackPlaybackStarted(song, resource, generation)
    }

    private fun attachBackgroundVideoResource(
        song: SongItem,
        generation: Long,
    ) = video.attachBackgroundResource(song, generation)

    fun skipNext(
        fromEOF: Boolean = false,
        fromListenTogether: Boolean = false,
        fromRemoteControl: Boolean = false,
        fromFailure: Boolean = false,
    ) {
        if ((fromEOF || fromFailure) && deviceConnect.isActive) {
            deviceConnect.playbackEnded()
            return
        }
        if (!fromEOF && !fromFailure && !fromListenTogether && deviceConnect.intercept(DcPlaybackCommand(DcPlaybackAction.NEXT))) return
        if ((!fromEOF || fromFailure) && !fromListenTogether && isListenTogetherGuest()) return

        if (fromEOF && sleepTimerStopAfterCurrentSong) {
            setPlaybackPlaying(false)
            clearSleepTimer()
            return
        }

        val startPlaying =
            if (fromEOF || fromFailure || fromListenTogether || fromRemoteControl || castManager.state.value.isConnected) {
                fromEOF || fromFailure || player.wantsPlayback
            } else {
                localSongChangeStartsPlayback()
            }
        if (!fromEOF && queue.peekNext()?.let(::isSongBlocked) != true &&
            transitions.promoteNext(startPlaying) {
                beginPlaybackTransition(preservePreparedNext = true)
                desiredPlayback.value = startPlaying
                stopVideo()
            }
        ) {
            isPlaybackResolving = false
            val resource = player.currentPlaybackResource.value
            val song = player.currentSong.value
            if (resource?.hasVideo == true && song != null) {
                val generation = playbackTransitionGeneration
                playbackTransitionJob =
                    scope.launch {
                        if (isCurrentPlaybackTransition(generation)) onTrackPlaybackStarted(song, resource, generation)
                    }
            } else if (resource?.isSabr == true) {
                markVideoUnavailable()
            }
            return
        }
        val generation = beginPlaybackTransition()
        desiredPlayback.value = startPlaying
        playbackTransitionJob =
            scope.launch {
                stopVideo()
                val track = currentTrack
                var decision =
                    decidePlaybackQueueTransition(
                        repeatMode = repeatMode,
                        fromEof = fromEOF,
                        currentTrack = track?.takeUnless(::isSongBlocked),
                        queueItems = queue.items.value,
                        currentIndex = queue.currentIndex.value,
                        autoLoadMore = Settings.shared.autoLoadMore,
                    )
                if (decision.action == PlaybackQueueAction.REPLAY_CURRENT) {
                    val replaySong = decision.song ?: return@launch
                    val resource = withContext(Dispatchers.IO) { fetchSelectedPlaybackResource(replaySong, generation) }
                    if (resource != null && isCurrentPlaybackTransition(generation)) {
                        withContext(Dispatchers.IO) { player.play(replaySong, resource, startPaused = !startPlaying) }
                        onTrackPlaybackStarted(replaySong, resource, generation)
                        finalizeHistory(nextSong = replaySong)
                        scrobbleManager.onPlaybackInstanceStart(replaySong)
                        return@launch
                    }
                    if (resource == null) return@launch
                    decision =
                        decidePlaybackQueueTransition(
                            repeatMode = repeatMode,
                            fromEof = fromEOF,
                            currentTrack = track,
                            queueItems = queue.items.value,
                            currentIndex = queue.currentIndex.value,
                            repeatCurrentAttempted = true,
                            autoLoadMore = Settings.shared.autoLoadMore,
                        )
                }

                if (decision.action == PlaybackQueueAction.PLAY_NEXT && decision.song != null) {
                    var resolveFailure: Throwable? = null
                    val next =
                        resolveNextQueueOccurrence(queue, { isCurrentPlaybackTransition(generation) }) { target ->
                            withContext(
                                Dispatchers.IO,
                            ) { fetchPlaybackResource(target, showError = true, onFailure = { resolveFailure = it }) }
                        }
                    if (next != null) {
                        val resource = next.second
                        if (resource != null) {
                            playQueueTrack(next.first, resource, generation, startPlaying)
                        } else {
                            player.beginPendingPlayback(next.first)
                            updateCurrentTrack(next.first, startArtworkFetch = true)
                            reportPlayerError(
                                resolveFailure?.let { playbackFetchErrorMessage(it) }
                                    ?: getString(Res.string.playback_error_track_unavailable),
                                resolveFailure,
                            )
                            handleTerminalPlaybackFailure(PlaybackFailureClassifier.fromThrowable(resolveFailure))
                        }
                        return@launch
                    }
                    if (!isCurrentPlaybackTransition(generation) || queue.peekNext() != null) return@launch
                    decision =
                        decidePlaybackQueueTransition(
                            repeatMode,
                            fromEOF,
                            track,
                            queue.items.value,
                            queue.currentIndex.value,
                            repeatCurrentAttempted = true,
                            autoLoadMore = Settings.shared.autoLoadMore,
                        )
                }
                if (decision.action == PlaybackQueueAction.RESTART_QUEUE) {
                    val first = queue.skipTo(0)
                    queueChanged()
                    if (first != null) {
                        val resource = withContext(Dispatchers.IO) { fetchSelectedPlaybackResource(first, generation) }
                        if (resource != null && isCurrentPlaybackTransition(generation)) {
                            playQueueTrack(first, resource, generation, startPlaying)
                        }
                    }
                } else if (decision.action == PlaybackQueueAction.AUTOPLAY) {
                    tryLoadAndQueueRecommendations(startPlaying)
                }
            }
    }

    internal fun skipNextFromListenTogether() {
        skipNext(fromListenTogether = true)
    }

    private suspend fun tryLoadAndQueueRecommendations(startPlaying: Boolean) {
        loadRecommendations(candidateLimit = 4, playImmediately = true, startPlaying = startPlaying)
    }

    private suspend fun loadRecommendations(
        candidateLimit: Int,
        playImmediately: Boolean,
        startPlaying: Boolean = player.wantsPlayback,
        shouldCommit: () -> Boolean = { true },
    ) {
        val seed = currentTrack ?: return
        val generation = playbackTransitionGeneration
        val canCommit = { shouldCommit() && isCurrentPlaybackTransition(generation) && canLoadRecommendations(seed) }
        val next = radio.load(candidateLimit, seed, playImmediately, canCommit) ?: return

        fun canPlay() =
            shouldCommit() && isCurrentPlaybackTransition(generation) &&
                Settings.shared.autoLoadMore && !deviceConnect.isFollowing && !syncSession.finite && !isListenTogetherGuest() &&
                currentTrack?.id == seed.id && queue.currentItem()?.id == next.id
        if (!canPlay()) return
        val resource = withContext(Dispatchers.IO) { fetchSelectedPlaybackResource(next, generation) } ?: return
        if (!canPlay()) return
        playQueueTrack(next, resource, generation, startPlaying)
    }

    fun skipPrevious(
        fromListenTogether: Boolean = false,
        fromRemoteControl: Boolean = false,
    ) {
        if (!fromListenTogether && deviceConnect.intercept(DcPlaybackCommand(DcPlaybackAction.PREVIOUS))) return
        if (!fromListenTogether && isListenTogetherGuest()) return

        val startPlaying =
            if (fromListenTogether || fromRemoteControl ||
                castManager.state.value.isConnected
            ) {
                player.wantsPlayback
            } else {
                localSongChangeStartsPlayback()
            }
        cancelCrossfadeForManualAction()
        val currentPos = currentMediaPositionMs()
        val restartPosition = desktopPreviousRestartPosition(pendingResumePosition, currentPos, isLiveStream, queue.hasPrevious())
        val generation = beginPlaybackTransition(resumePosition = restartPosition)
        desiredPlayback.value = startPlaying
        playbackTransitionJob =
            scope.launch {
                val isLive = isLiveStream
                if (isLive) {
                    val prev = queue.getPrevious()
                    if (prev != null) {
                        val resource = withContext(Dispatchers.IO) { fetchSelectedPlaybackResource(prev, generation) }
                        if (resource != null && isCurrentPlaybackTransition(generation)) {
                            playQueueTrack(prev, resource, generation, startPlaying)
                        }
                    }
                    return@launch
                }
                if (currentPos > 3000) {
                    seekToPositionInternal(0)
                } else {
                    val prev = queue.getPrevious()
                    if (prev != null) {
                        val resource = withContext(Dispatchers.IO) { fetchSelectedPlaybackResource(prev, generation) }
                        if (resource != null && isCurrentPlaybackTransition(generation)) {
                            playQueueTrack(prev, resource, generation, startPlaying)
                        }
                    } else {
                        seekToPositionInternal(0)
                    }
                }
            }
    }

    val isLiveStream: Boolean
        get() =
            currentTrack?.isLive == true ||
                player.currentSong.value?.isLive == true ||
                player.currentPlaybackResource.value?.isLive == true

    val canSkipPrevious: Boolean
        get() = if (isLiveStream) queue.hasPrevious() else true

    val canSeek: Boolean
        get() = !isLiveStream

    internal fun skipPreviousFromListenTogether() {
        skipPrevious(fromListenTogether = true)
    }

    fun addToQueue(songs: List<SongItem>) {
        if (deviceConnect.intercept(DcPlaybackCommand(DcPlaybackAction.ADD, songs = BlockedContent.filterPlayableSongs(songs)))) return
        if (isListenTogetherGuest()) return

        val filteredSongs = BlockedContent.filterPlayableSongs(songs)
        if (filteredSongs.isEmpty()) return

        enqueueSongs(filteredSongs, playNext = false)
    }

    fun addToQueue(song: SongItem) = addToQueue(listOf(song))

    fun blockArtist(artist: ArtistTiny) {
        val artistId = artist.id ?: return
        BlockedContent.blockArtist(artist)
        relatedSongs = relatedSongs.filterNot { song -> song.artists.any { it.id == artistId } }
        removeQueueItems(
            queue.items.value.indices
                .filter { index ->
                    index != queue.currentIndex.value &&
                        queue.items.value[index]
                            .artists
                            .any { it.id == artistId }
                }.toSet(),
        )
        currentTrack?.takeIf { song -> song.artists.any { it.id == artistId } }?.let { song ->
            autoSkipBlockedSong(song.id)
        }
    }

    fun startSleepTimer(
        durationMs: Long,
        stopAfterCurrentSong: Boolean,
        fadeOut: Boolean,
        finishCurrentSong: Boolean = false,
    ) {
        if (isListenTogetherGuest() || (!stopAfterCurrentSong && durationMs <= 0L)) return
        sleepTimerJob?.cancel()
        applySleepTimerGain(1f)
        sleepTimerStopAfterCurrentSong = stopAfterCurrentSong
        sleepTimerFinishCurrentSong = finishCurrentSong
        sleepTimerFadeOut = fadeOut
        sleepTimerEnabled = true
        if (stopAfterCurrentSong) {
            transitions.cancel()
            sleepTimerTimeLeftMs.value = 0L
            sleepTimerJob =
                scope.launch {
                    endOfSongSleepTimerProgress(playbackPosition, playbackDuration, player.playbackSpeed).collect { (remaining, gain) ->
                        sleepTimerTimeLeftMs.value = remaining ?: 0L
                        if (fadeOut) applySleepTimerGain(gain)
                    }
                }
            return
        }
        val deadline = SleepTimerDeadline(durationMs)
        sleepTimerTimeLeftMs.value = durationMs
        sleepTimerJob =
            scope.launch {
                deadline.awaitExpiry(
                    fadeOut,
                    sleepTimerTimeLeftMs,
                    playbackUiVisible,
                    playbackChanges = combine(playbackPosition, snapshotFlow { isPlaying }) { _, _ -> Unit },
                    applyGain = ::applySleepTimerGain,
                    finishCurrentSong = finishCurrentSong,
                )
                if (finishCurrentSong) {
                    startSleepTimer(0L, stopAfterCurrentSong = true, fadeOut = fadeOut)
                } else {
                    if (castManager.state.value.isConnected) castManager.pause() else setPlaybackPlaying(false)
                    clearSleepTimer()
                }
            }
    }

    private fun applySleepTimerGain(gain: Float) {
        sleepTimerGain = gain
        player.setSleepTimerGain(gain)
        updateVideoAudioRouting()
        val remote = castManager.state.value
        val outputVolume = castTimerVolume.setGain(remote, gain) ?: return
        scope.launch {
            if (castManager.state.value.device
                    ?.id == remote.device?.id
            ) {
                castManager.setVolume(outputVolume)
            }
        }
    }

    fun clearSleepTimer() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        sleepTimerEnabled = false
        sleepTimerTimeLeftMs.value = 0L
        sleepTimerStopAfterCurrentSong = false
        sleepTimerFinishCurrentSong = false
        sleepTimerFadeOut = false
        applySleepTimerGain(1f)
    }

    fun refreshHistory() = library.refreshHistory()

    fun fetchRelated(videoId: String) = related.fetch(videoId)

    fun playNext(songs: List<SongItem>) {
        if (deviceConnect.intercept(
                DcPlaybackCommand(DcPlaybackAction.PLAY_NEXT, songs = BlockedContent.filterPlayableSongs(songs)),
            )
        ) {
            return
        }
        if (isListenTogetherGuest()) return

        val filteredSongs = BlockedContent.filterPlayableSongs(songs)
        if (filteredSongs.isEmpty()) return

        enqueueSongs(filteredSongs, playNext = true)
    }

    private fun enqueueSongs(
        songs: List<SongItem>,
        playNext: Boolean,
    ) {
        enqueueOrStartQueue(
            songs = songs,
            currentSong = currentTrack,
            preventDuplicates = Settings.shared.preventDuplicateTracksInQueue,
            startQueue = { additions, startPlaying ->
                replaceQueue(additions)
                val track = queue.currentItem() ?: return@enqueueOrStartQueue
                updateCurrentTrack(track, startArtworkFetch = true)
                desiredPlayback.value = startPlaying
                playTrackInternal(track, emptyList(), startPaused = !startPlaying)
            },
            enqueue = { additions ->
                if (playNext) {
                    queue.playNext(additions, Settings.shared.preventDuplicateTracksInQueue)
                } else {
                    queue.addToQueue(additions, Settings.shared.preventDuplicateTracksInQueue)
                }
                queueChanged()
            },
        )
    }

    fun playNext(song: SongItem) = playNext(listOf(song))

    fun removeQueueItems(
        indices: Set<Int>,
        expectedQueue: MusicQueueSnapshot = queue.snapshots.value,
    ): QueueRemoval? {
        if (isListenTogetherGuest()) return null
        if (deviceConnect.isActive) {
            return syncSession.remove(indices, expectedQueue)
        }
        return queue.removeAt(indices).also { if (it != null) queueChanged() }
    }

    fun removeQueueOccurrence(key: Long): QueueRemoval? {
        if (isListenTogetherGuest() || Settings.shared.isQueueLocked) return null
        val state = queue.state.value
        val index = state.entries.indexOfFirst { it.key == key }
        if (index <= state.currentIndex) return null
        return removeQueueItems(setOf(index), queue.snapshots.value)
    }

    fun moveQueueItem(
        fromKey: Long,
        toKey: Long,
        selectedEntryKeys: Set<Long> = emptySet(),
    ) {
        if (isListenTogetherGuest() || Settings.shared.isQueueLocked) return
        val state = queue.state.value
        val from = state.entries.indexOfFirst { it.key == fromKey }
        val to = state.entries.indexOfFirst { it.key == toKey }
        if (from <= state.currentIndex || to <= state.currentIndex || from == to) return
        val selected =
            state.entries.indices.filterTo(mutableSetOf()) {
                it > state.currentIndex &&
                    state.entries[it].key in selectedEntryKeys
            }
        reorderQueue(
            state.entries.indices
                .toList()
                .moveSelection(from, to, selected),
        )
    }

    fun reorderQueue(order: List<Int>): Boolean {
        if (isListenTogetherGuest() || Settings.shared.isQueueLocked) return false
        val expectedQueue = queue.snapshots.value
        if (order.size != expectedQueue.displayedItems.size || order.toSet() != expectedQueue.displayedItems.indices.toSet()) return false
        if (deviceConnect.intercept(
                DcPlaybackCommand(DcPlaybackAction.REORDER, order = order),
                expectedQueue = expectedQueue,
            )
        ) {
            return true
        }
        queue.reorder(order)
        queueChanged()
        return true
    }

    fun restoreQueueItems(removal: QueueRemoval) {
        if (isListenTogetherGuest()) return
        if (syncSession.restore(removal)) return
        queue.restore(removal)
        queueChanged()
    }

    fun moveQueueItem(
        from: Int,
        to: Int,
        expectedQueue: MusicQueueSnapshot = queue.snapshots.value,
    ) {
        if (deviceConnect.intercept(
                DcPlaybackCommand(DcPlaybackAction.MOVE, index = from, toIndex = to),
                expectedQueue = expectedQueue,
            )
        ) {
            return
        }
        if (isListenTogetherGuest()) return
        queue.move(from, to)
        queueChanged()
    }

    fun toggleLike(song: SongItem) {
        SongLikeState.toggleLike(song)
    }

    fun startRadio(song: SongItem) {
        if (deviceConnect.intercept(
                DcPlaybackCommand(
                    DcPlaybackAction.REPLACE_QUEUE,
                    songs = listOf(song),
                    index = 0,
                    finite = false,
                    positionMs = if (currentTrack?.id == song.id) player.currentPlaybackPositionMs() else 0L,
                    preserveCurrentTrack = true,
                ),
            )
        ) {
            return
        }
        if (isListenTogetherGuest() || isSongBlocked(song)) return

        cancelCrossfadeForManualAction()
        val preserveCurrent = player.currentSong.value?.id == song.id && player.playbackReady && !isPlaybackResolving
        val generation =
            if (preserveCurrent) {
                playbackTransitionJob?.cancel()
                ++playbackTransitionGeneration
            } else {
                beginPlaybackTransition()
            }
        initialRecommendationsGeneration = generation
        replaceQueue(listOf(song))
        playbackTransitionJob =
            scope.launch {
                val recommendations =
                    async(Dispatchers.IO) {
                        try {
                            freshRadioRecommendations(song.id, generation)
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Exception) {
                            emptyList()
                        }
                    }
                if (!preserveCurrent) {
                    desiredPlayback.value = true
                    val resource = withContext(Dispatchers.IO) { fetchSelectedPlaybackResource(song, generation) }
                    if (resource != null && isCurrentPlaybackTransition(generation)) {
                        withContext(Dispatchers.IO) { player.play(song, resource) }
                        onTrackPlaybackStarted(song, resource, generation)
                    }
                }
                appendRadioRecommendations(recommendations.await(), generation)
            }
    }

    private suspend fun freshRadioRecommendations(
        seedId: String,
        generation: Long,
    ): List<SongItem> {
        val repository = GlobalYouTubeRepository.instance
        val firstPage = repository.startRadioQueue(seedId)
        val page =
            recommendationHistory.select(
                firstPage = firstPage,
                filter = { songs -> songs.filter { it.id != seedId && !isSongBlocked(it) } },
                isCurrent = { isCurrentPlaybackTransition(generation) },
                loadMore = { repository.continueRadioQueue(it) },
            ) ?: return emptyList()
        return firstPage.songs.filter { it.id == seedId } + page.songs
    }

    private fun appendRadioRecommendations(
        songs: List<SongItem>,
        generation: Long,
    ) {
        if (!isCurrentPlaybackTransition(generation)) return
        initialRecommendationsGeneration = null
        val blocked = BlockedContent.snapshot()
        val existing = queue.items.value.mapTo(mutableSetOf()) { it.id }
        val additions = songs.filter { !it.isUnavailable && !blocked.isBlocked(it) && existing.add(it.id) }
        if (additions.isNotEmpty()) {
            queue.addToQueue(additions)
            recommendationHistory.remember(additions)
            queueChanged()
        }
    }

    fun playFromId(videoId: String) {
        if (isListenTogetherGuest()) return
        if (deviceConnect.willStartRemotePlayback) {
            val generation = playbackTransitionGeneration
            scope.launch {
                val song = withContext(Dispatchers.IO) { GlobalYouTubeRepository.instance.getSongMetadata(videoId) }
                if (song != null && isCurrentPlaybackTransition(generation)) playTrack(song)
            }
            return
        }
        cancelCrossfadeForManualAction()
        val generation = beginPlaybackTransition()
        initialRecommendationsGeneration = generation
        playbackTransitionJob =
            scope.launch {
                try {
                    val recommendations =
                        async(Dispatchers.IO) {
                            if (!Settings.shared.autoLoadMore) return@async emptyList()
                            try {
                                freshRadioRecommendations(videoId, generation)
                            } catch (error: CancellationException) {
                                throw error
                            } catch (_: Exception) {
                                emptyList()
                            }
                        }
                    val playerInfo =
                        withContext(Dispatchers.IO) {
                            GlobalYouTubeRepository.instance.getSongPlayerInformation(videoId)
                        }
                    val target =
                        playerInfo?.let { info ->
                            val title = info.title?.takeIf(String::isNotBlank) ?: return@let null
                            SongItem(
                                id = videoId,
                                title = title,
                                artists = info.artist?.let { listOf(ArtistTiny(id = null, name = it)) }.orEmpty(),
                                thumbnail = info.thumbnail,
                                duration = info.durationSeconds,
                                album = null,
                                musicVideoType =
                                    if (info.isLive) {
                                        MUSIC_VIDEO_TYPE_LIVE
                                    } else {
                                        info.musicVideoType ?: MUSIC_VIDEO_TYPE_VIDEO
                                    },
                            )
                        }
                            ?: withContext(Dispatchers.IO) { GlobalYouTubeRepository.instance.getSongMetadata(videoId) }
                            ?: recommendations.await().firstOrNull { it.id == videoId }
                            ?: run {
                                if (isCurrentPlaybackTransition(generation)) {
                                    reportPlayerError(getString(Res.string.playback_error_track_not_found))
                                }
                                return@launch
                            }
                    if (!isCurrentPlaybackTransition(generation) || isSongBlocked(target)) return@launch
                    if (isCurrentPlaybackTransition(generation)) {
                        replaceQueue(listOf(target), 0)
                        val resource = withContext(Dispatchers.IO) { fetchPlaybackResource(target, showError = false) }
                        if (resource != null && isCurrentPlaybackTransition(generation)) {
                            withContext(Dispatchers.IO) { player.play(target, resource) }
                            onTrackPlaybackStarted(target, resource, generation)
                        } else if (resource == null && isCurrentPlaybackTransition(generation)) {
                            reportPlayerError(getString(Res.string.playback_error_unable_to_play_link))
                        }
                    }
                    val songs = recommendations.await()
                    if (Settings.shared.autoLoadMore) appendRadioRecommendations(songs, generation)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    if (isCurrentPlaybackTransition(generation)) {
                        reportPlayerError(getString(Res.string.playback_error_unable_to_play_link))
                    }
                }
            }
    }

    fun fetchUserPlaylists(
        songs: List<SongItem>,
        signedIn: Boolean,
    ) = library.fetchUserPlaylists(songs, signedIn)

    fun addToPlaylist(
        playlistId: String,
        songs: List<SongItem>,
        onResult: (Result<Unit>) -> Unit,
    ) = library.addToPlaylist(playlistId, songs, onResult)

    fun addToPlaylists(
        playlistIds: List<String>,
        songs: List<SongItem>,
        onPlaylistAdded: (String) -> Unit,
        onResult: (String?, Result<Unit>) -> Unit,
    ) = library.addToPlaylists(playlistIds, songs, onPlaylistAdded, onResult)

    val playlistAddCompleted get() = library.playlistAddCompleted
    val playlistAddTotal get() = library.playlistAddTotal

    fun cancelPlaylistAddition() = library.cancelPlaylistAddition()

    fun dismissAddToPlaylist() = library.dismissAddToPlaylist()

    fun fetchUserPlaylists(
        song: SongItem,
        signedIn: Boolean,
    ) = library.fetchUserPlaylists(song, signedIn)

    fun addToPlaylist(
        playlistId: String,
        song: SongItem,
        onResult: (Result<Unit>) -> Unit,
    ) = library.addToPlaylist(playlistId, song, onResult)

    fun togglePlay() {
        if (isListenTogetherGuest()) return
        if (deviceConnect.isActive) {
            setPlaybackPlaying(!syncSession.desiredPlaying)
        } else {
            togglePlayInternal()
        }
    }

    fun play() = setPlaybackPlaying(true)

    fun pause() = setPlaybackPlaying(false)

    fun setPlaybackPlaying(playing: Boolean) {
        if (deviceConnect.intercept(
                DcPlaybackCommand(if (playing) DcPlaybackAction.PLAY else DcPlaybackAction.PAUSE),
                expectedQueue = queue.snapshots.value,
            )
        ) {
            return
        }
        if (isListenTogetherGuest()) return
        if (playing) playInternal() else pauseInternal()
    }

    fun stop() {
        if (deviceConnect.intercept(DcPlaybackCommand(DcPlaybackAction.PAUSE))) return
        if (isListenTogetherGuest()) return
        ++playbackTransitionGeneration
        playbackTransitionJob?.cancel()
        playbackTransitionJob = null
        isPlaybackResolving = false
        cancelCrossfadeForManualAction()
        if (castManager.state.value.isConnected) scope.launch { castManager.stop() }
        flushPodcastProgress()
        pendingResumePosition = null
        player.stop()
        stopVideo()
        updateCurrentTrack(null)
        persistPlaybackState()
    }

    internal fun togglePlayInternal() {
        val playing =
            if (castManager.state.value.isConnected) {
                castManager.state.value.isPlaying
            } else {
                player.wantsPlayback
            }
        setPlaybackPlaying(!playing)
    }

    private fun playInternal() {
        if (castManager.state.value.isConnected) {
            if (!castManager.state.value.isPlaying) scope.launch { castManager.play() }
            return
        }
        invalidatePersistedRestore()
        desiredPlayback.value = true
        video.resetSeek()
        if (interruptedNetworkPlaybackInstanceId == player.currentPlaybackInstanceId) {
            interruptedNetworkPlaybackInstanceId = null
            showPlayerError = false
            player.resume()
            player.invalidateActiveStreamForNetworkChange(forceRecovery = true)
            return
        }
        if (retryPendingTrack()) return
        player.setPlaying(true)
        if (isVideoActive) {
            video.resume()
        } else if (isVideoAvailable) {
            startVideo()
        }
        persistPlaybackState()
    }

    private fun pauseInternal() {
        if (castManager.state.value.isConnected) {
            if (castManager.state.value.isPlaying) scope.launch { castManager.pause() }
            return
        }
        invalidatePersistedRestore()
        desiredPlayback.value = false
        video.resetSeek()
        player.setPlaying(false)
        video.pause()
        persistPlaybackState()
    }

    private fun retryPendingTrack(): Boolean {
        val track = currentTrack ?: return false
        if (networkRecoveryActive) return false
        if (player.playbackReady && player.loadFailure.value == null && !showPlayerError) return false
        playTrackInternal(track, emptyList())
        return true
    }

    private fun cancelCrossfadeForManualAction() = transitions.cancel()

    fun seekRelative(deltaMs: Long) {
        if (isLiveStream) return
        val newPos =
            (currentMediaPositionMs() + deltaMs)
                .coerceIn(0, playbackDuration.value)
        seekToPosition(newPos)
    }

    internal fun currentMediaPositionMs(): Long =
        when {
            castManager.state.value.isConnected -> castManager.state.value.positionMs
            else -> pendingResumePosition?.second ?: player.currentPlaybackPositionMs()
        }

    private suspend fun seekCastTo(positionMs: Long) {
        val remote = castManager.state.value
        val targetMs = positionMs.coerceAtLeast(0L)
        castManager.seekTo(targetMs)
        val current = castManager.state.value
        if (current.isConnected && current.device?.id == remote.device?.id && current.mediaGeneration == remote.mediaGeneration) {
            mediaPositionDiscontinuities.emit(targetMs)
        }
    }

    fun seekToPosition(
        positionMs: Long,
        source: PlaybackSeekSource,
    ) {
        source.record(currentMediaPositionMs(), positionMs, currentTrack?.id)
        seekToPosition(positionMs)
    }

    fun seekToPosition(positionMs: Long) {
        if (isLiveStream) return
        if (deviceConnect.intercept(DcPlaybackCommand(DcPlaybackAction.SEEK, positionMs = positionMs))) return
        if (isListenTogetherGuest()) return
        val dur = playbackDuration.value
        val targetMs = if (dur > 0) positionMs.coerceIn(0, dur) else positionMs.coerceAtLeast(0)
        ListenTogetherState.listenTogetherManager.sendSeek(targetMs)
        seekToPositionInternal(targetMs)
    }

    fun seekToPositionInternal(positionMs: Long) {
        if (isLiveStream) return
        if (castManager.state.value.isConnected) {
            scope.launch { seekCastTo(positionMs) }
            return
        }
        cancelCrossfadeForManualAction()
        invalidatePersistedRestore()
        val dur = player.duration.value
        val targetMs = if (dur > 0) positionMs.coerceIn(0, dur) else positionMs.coerceAtLeast(0)
        pendingResumePosition?.let {
            pendingResumePosition = it.first to targetMs
            playbackPosition.value = targetMs
            mediaPositionDiscontinuities.tryEmit(targetMs)
            progress = if (playbackDuration.value > 0) targetMs.toFloat() / playbackDuration.value else 0f
            persistPlaybackState()
            return
        }
        player.seekTo(targetMs)
        video.seekTo(targetMs)
        persistPlaybackState()
        podcastProgress.flush(force = true)
    }

    fun seekTo(fraction: Float) {
        if (isLiveStream) return
        if (!fraction.isFinite()) return
        if (deviceConnect.intercept(
                DcPlaybackCommand(
                    DcPlaybackAction.SEEK,
                    positionMs =
                        (
                            fraction.coerceIn(0f, 1f) *
                                playbackDuration.value
                        ).toLong(),
                ),
            )
        ) {
            return
        }
        if (isListenTogetherGuest()) return

        if (castManager.state.value.isConnected) {
            val targetMs = (fraction.coerceIn(0f, 1f) * playbackDuration.value).toLong()
            scope.launch { seekCastTo(targetMs) }
            return
        }
        cancelCrossfadeForManualAction()
        invalidatePersistedRestore()
        val dur = player.duration.value
        if (dur > 0) {
            val positionMs = (fraction * dur).toLong()
            ListenTogetherState.listenTogetherManager.sendSeek(positionMs)
        }
        seekToInternal(fraction)
        persistPlaybackState()
    }

    internal fun seekToInternal(fraction: Float) {
        if (isLiveStream) return
        val dur = playbackDuration.value
        if (dur > 0) {
            val targetMs = (fraction * dur).toLong()
            seekToPositionInternal(targetMs)
        }
    }

    fun resetPlaybackTimeTracking(songId: String) {
        history.reset(songId, currentTrack?.takeIf { it.id == songId })
    }

    internal fun resetPlaybackTimeTracking(song: SongItem?) {
        history.reset(song?.id, song)
        history.onPlayingChanged(player.isPlaying.value)
    }

    private fun scheduleAutomaticCache(
        song: SongItem,
        resource: PlaybackResource,
    ) {
        automaticCacheJob?.cancel()
        if (
            (!resource.isDirectFile && !resource.isSabr) || resource.clientName == "local" ||
            !automaticCacheAllowedForCurrentTrack ||
            !automaticCachingEnabled()
        ) {
            return
        }
        val mvId = resource.musicVideoId
        val targetSong =
            if (mvId != null && mvId != song.id) {
                song.copy(id = mvId)
            } else {
                song
            }
        automaticCacheJob =
            scope.launch {
                SongCacheManager.cacheSong(targetSong, resource) {
                    awaitSabrCacheReady(
                        changes =
                            combine(
                                player.playbackStateChanges,
                                player.currentPlaybackResource,
                                completedCachePlaybackInstanceId,
                                snapshotFlow { Settings.shared.songCacheEnabled },
                            ) { _, _, _, _ -> Unit },
                        enabled = { Settings.shared.songCacheEnabled },
                        deferred = {
                            player.shouldDeferSabrCache(targetSong.id, completedCachePlaybackInstanceId.value)
                        },
                    )
                }
            }
    }

    private fun automaticCachingEnabled(): Boolean =
        Settings.shared.songCacheEnabled && Settings.shared.devtoolsPlaybackClient == PlaybackClientCatalog.AUTOMATIC_ID

    private suspend fun finalizeHistory(
        nextSong: SongItem? = null,
        close: Boolean = false,
    ) {
        history.finalizeSession(
            nextSong = nextSong,
            isPlaying = nextSong != null && player.isPlaying.value,
            reportingEnabled = externalReportingEnabled,
            minDurationMs = (Settings.shared.historyDuration * 1000).toLong(),
            pauseHistory = Settings.shared.pauseListenHistory,
            close = close,
        )
    }

    private fun isSongBlocked(songId: String): Boolean = BlockedContent.isSongBlocked(songId)

    private fun forcedPlaybackClientId(): String? =
        Settings.shared.devtoolsPlaybackClient
            .takeUnless { it == PlaybackClientCatalog.AUTOMATIC_ID }
            ?.takeIf(PlaybackClientCatalog::isDevtoolsOverrideAllowed)

    private fun isSongBlocked(song: SongItem): Boolean = song.isUnavailable || BlockedContent.isBlocked(song)

    private fun autoSkipBlockedSong(songId: String) {
        val currentIsBlocked = currentTrack?.id == songId && currentTrack?.let(::isSongBlocked) == true
        if (!currentIsBlocked && !isSongBlocked(songId)) return
        if (lastBlockedAutoSkipSongId == songId) return
        lastBlockedAutoSkipSongId = songId

        if (deviceConnect.isActive) {
            removeQueueItems(
                queue.items.value.indices
                    .filter { queue.items.value[it].id == songId }
                    .toSet(),
            )
            return
        }

        scope.launch {
            player.pause()
            queue.removeSongs(setOf(songId), keepCurrent = false)
            queueChanged()
            skipNext(fromEOF = true)
        }
    }
}

internal fun shouldRecoverStalledVideo(
    isVideoActive: Boolean,
    isAudioPlaying: Boolean,
    isVideoResolving: Boolean,
    recoveryActive: Boolean,
    lastVideoFrameMs: Long,
    nowMs: Long,
    timeoutMs: Long,
): Boolean =
    lastVideoFrameMs > 0L &&
        nowMs - lastVideoFrameMs > timeoutMs &&
        isVideoActive &&
        isAudioPlaying &&
        !isVideoResolving &&
        !recoveryActive
