/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.desktop.state.playback

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.setValue
import com.metrolist.desktop.state.app.AppState
import com.metrolist.desktop.state.app.UiState
import com.metrolist.desktop.utils.NativeMemoryTrimmer
import com.metrolist.shared.api.musicvideo.CounterpartPositionMap
import com.metrolist.shared.api.musicvideo.MusicVideoResolver
import com.metrolist.shared.api.musicvideo.MusicVideoResult
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.isPodcast
import com.metrolist.shared.model.isVideoSong
import com.metrolist.shared.playback.MusicPlayer
import com.metrolist.shared.playback.MusicQueue
import com.metrolist.shared.playback.PlaybackFailureCategory
import com.metrolist.shared.playback.VideoFrame
import com.metrolist.shared.playback.VideoPlayer
import com.metrolist.shared.playback.VideoPlayerStats
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.state.GlobalYouTubeRepository
import com.metrolist.shared.utils.Logger
import com.metrolist.shared.youtube.extraction.models.toContentHints
import com.metrolist.shared.youtube.playback.PlaybackResource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.koin.core.context.GlobalContext
import java.net.URI
import kotlin.time.Duration.Companion.milliseconds

internal fun isCurrentDesktopVideoLoad(
    generation: Long,
    currentGeneration: Long,
    playbackInstanceId: Long,
    currentPlaybackInstanceId: Long,
    songId: String?,
    currentSongId: String?,
    jobActive: Boolean,
    viewerOpen: Boolean,
): Boolean =
    jobActive && viewerOpen && generation == currentGeneration &&
        playbackInstanceId == currentPlaybackInstanceId && songId != null && songId == currentSongId

internal fun shouldPresentDesktopVideo(
    windowVisible: Boolean,
    showVideoViewer: Boolean,
    showVideoFullscreen: Boolean,
    isExpanded: Boolean,
): Boolean = windowVisible && (showVideoFullscreen || (showVideoViewer && !isExpanded))

internal data class DesktopVideoPlaybackStatus(
    val song: SongItem?,
    val generation: Long,
    val canonicalMedia: () -> Boolean,
    val playing: Boolean,
    val volume: Float,
    val audioOutputEnabled: Boolean,
    val videoVisible: Boolean,
)

internal class DesktopVideoLifecycle(
    private val scope: CoroutineScope,
    private val player: MusicPlayer,
    private val queue: MusicQueue,
    private val status: () -> DesktopVideoPlaybackStatus,
    private val onUnavailable: () -> Unit,
    private val fetchSource: suspend (SongItem, Boolean) -> PlaybackResource?,
    private val retimeLyrics: () -> Unit = { LyricsState.retimeCurrentLyricsIfPossible() },
) {
    private companion object {
        const val VIDEO_FIRST_FRAME_WAIT_MS = 6_000L
        const val MAX_VIDEO_RECOVERY_ATTEMPTS = 3
        const val VIDEO_STALL_TIMEOUT_MS = 8_000L
    }

    private val musicVideoResolver by lazy { GlobalContext.get().get<MusicVideoResolver>() }
    private val canPlayVideo: Boolean
        get() = status().videoVisible && Settings.shared.musicVideoPlaybackEnabled

    private val videoPlayer: VideoPlayer =
        VideoPlayer(
            onFrame = { frame ->
                videoFrame = frame
                lastVideoFrameMs = System.currentTimeMillis()
            },
            onEnded = { handleVideoEnded() },
            onSeekLanded = { if (isVideoActive) isVideoResolving = false },
            onRenderedFirstFrame = {
                if (!canPlayVideo) return@VideoPlayer
                videoCrashRecoveryAttempts = 0
                videoRecoveryAttempts = 0
                isVideoResolving = false
                hasRenderedFirstFrame = true
                lastVideoFrameMs = System.currentTimeMillis()
                videoSeekTimeoutJob?.cancel()
                if (audioPausedForVideo) {
                    audioPausedForVideo = false
                    player.resume()
                }
            },
        )

    init {
        videoPlayer.onStreamExpired = { statusCode ->
            val playbackJob = videoPlaybackJob
            scope.launch {
                if (playbackJob != null && videoPlaybackJob === playbackJob) {
                    recoverVideo(videoPlayer.getStats().videoPtsMs, reportProfile = true, statusCode = statusCode)
                }
            }
        }
        videoPlayer.audioPtsProvider = { player.currentAudioPtsMs() }
    }

    private var decodedFrame by mutableStateOf<VideoFrame?>(null, policy = neverEqualPolicy())
    private var videoFrameSongId by mutableStateOf<String?>(null)
    var videoFrame: VideoFrame?
        get() = decodedFrame.takeIf { videoFrameSongId != null && videoFrameSongId == status().song?.id }
        private set(value) {
            decodedFrame = value
        }
    var isVideoAvailable by mutableStateOf(false)
        private set
    var isVideoResolving by mutableStateOf(false)
        private set
    var isVideoActive by mutableStateOf(false)
        private set
    var mvPositionMap by mutableStateOf<CounterpartPositionMap?>(null)
        private set
    var mvVideoId by mutableStateOf<String?>(null)
        private set
    var mvResolutionFailedForSong by mutableStateOf<String?>(null)
        private set
    var videoPlaybackResource: PlaybackResource? = null
        private set

    private data class OriginalAudioPlayback(
        val songId: String,
        val resource: PlaybackResource,
        val positionMap: CounterpartPositionMap?,
    )

    private var originalAudioPlayback: OriginalAudioPlayback? = null

    fun setAvailability(available: Boolean) {
        isVideoAvailable = available
    }

    fun setResolving(resolving: Boolean) {
        isVideoResolving = resolving
    }

    fun setCounterpart(
        id: String,
        map: CounterpartPositionMap?,
    ) {
        mvVideoId = id
        mvPositionMap = map
    }

    fun setVideoResource(resource: PlaybackResource?) {
        videoPlaybackResource = resource
    }

    suspend fun resolveCounterpartForLyrics(song: SongItem): Pair<String?, CounterpartPositionMap?> {
        if (status().song?.id != song.id) return null to null
        if (mvVideoId != null || mvPositionMap != null) return (mvVideoId ?: song.id.takeIf { song.isVideoSong }) to mvPositionMap
        if (song.isVideoSong) return song.id to null
        if (mvResolutionFailedForSong == song.id) return null to null
        val generation = status().generation
        val instanceId = player.currentPlaybackInstanceId
        val result =
            try {
                withContext(Dispatchers.IO) { musicVideoResolver.resolveMusicVideo(song) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Logger.w("LyricsRetimer", "Music video resolution failed for songId=${song.id}: ${error::class.simpleName}")
                null
            }
        if (!shouldCommitVideoCounterpartResolution(
                song.id,
                status().song?.id,
                generation,
                status().generation,
                instanceId,
                player.currentPlaybackInstanceId,
            )
        ) {
            return null to null
        }
        if (result is MusicVideoResult.Found && result.videoId != song.id) {
            mvVideoId = result.videoId
            mvPositionMap = result.positionMap
            return result.videoId to result.positionMap
        }
        mvResolutionFailedForSong = song.id
        return null to null
    }

    fun attachBackgroundResource(
        song: SongItem,
        generation: Long,
    ) {
        backgroundResourceJob?.cancel()
        backgroundResourceJob = null
        if (!canPlayVideo) return
        if (!UiState.showVideoViewer && !UiState.showVideoFullscreen && !isVideoActive) return
        if (song.id.startsWith("local_") || (status().canonicalMedia() && !song.isVideoSong)) return
        var instanceId = player.currentPlaybackInstanceId

        backgroundResourceJob =
            scope.launch(Dispatchers.IO) {
                fun current() =
                    isActive && canPlayVideo &&
                        shouldCommitVideoCounterpartResolution(
                            song.id,
                            status().song?.id,
                            generation,
                            status().generation,
                            instanceId,
                            player.currentPlaybackInstanceId,
                        )
                try {
                    if (!current()) return@launch
                    var resource: PlaybackResource? = null
                    if (song.isVideoSong) {
                        resource = fetchSource(song, true)
                    } else if (song.isPodcast) {
                        resource = fetchSource(song, true)
                    } else {
                        val resolved = musicVideoResolver.resolveMusicVideo(song)
                        if (resolved is MusicVideoResult.Found) {
                            if (!current() || status().canonicalMedia()) return@launch
                            withContext(Dispatchers.Main) {
                                if (!current()) return@withContext
                                mvVideoId = resolved.videoId
                                mvPositionMap = resolved.positionMap
                                if (UiState.showVideoViewer || UiState.showVideoFullscreen ||
                                    isVideoActive
                                ) {
                                    retimeLyrics()
                                }
                            }
                            if (!current()) return@launch
                            resource = fetchSource(song, true)
                        }
                        if (!current()) return@launch
                        if (resource == null || !resource.hasVideo) resource = fetchSource(song, true)
                    }
                    if (!current()) return@launch
                    if (resource != null && resource.hasVideo) {
                        val mediaId = resource.musicVideoId ?: resource.videoId
                        if (status().canonicalMedia() && mediaId != song.id) return@launch
                        val wasAvailable = isVideoAvailable
                        val oldUrl = videoPlaybackResource?.videoUrl
                        val wantsVideo = UiState.showVideoViewer || UiState.showVideoFullscreen
                        videoPlaybackResource = resource
                        val ready =
                            if (wantsVideo) {
                                switchToVideoAudio(song, resource).also { switched ->
                                    if (switched) instanceId = player.currentPlaybackInstanceId
                                }
                            } else {
                                if (!shouldSwitchToCounterpartAudio(song.id, resource)) player.attachPlaybackResource(resource)
                                true
                            }
                        if (ready && current()) {
                            withContext(Dispatchers.Main) {
                                if (!current()) return@withContext
                                isVideoAvailable = true
                                if (song.isVideoSong || UiState.showVideoViewer || UiState.showVideoFullscreen || isVideoActive) {
                                    retimeLyrics()
                                    if (UiState.showVideoViewer || UiState.showVideoFullscreen || isVideoActive) {
                                        startVideo(force = (!wasAvailable || resource.videoUrl != oldUrl) && !isPlayingResource(resource))
                                    }
                                }
                            }
                        }
                    } else {
                        withContext(Dispatchers.Main) { if (current()) onUnavailable() }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Logger.w("PlayerState", "Video resource resolution failed", error)
                    withContext(Dispatchers.Main) { if (current()) onUnavailable() }
                }
            }
    }

    fun discardSource() {
        cancelBackgroundResolution()
        videoPlaybackResource = null
        originalAudioPlayback = null
    }

    fun markUnavailable() {
        resetCrashRecovery()
        isVideoAvailable = false
        videoPlaybackResource = null
        if (originalAudioPlayback?.songId == status().song?.id) {
            restoreOriginalAudioSource()
        } else {
            originalAudioPlayback = null
        }
        mvPositionMap = null
        mvVideoId = null
        mvResolutionFailedForSong = null
        stopVideo()
    }

    suspend fun switchToVideoAudio(
        song: SongItem,
        resource: PlaybackResource,
        originalResource: PlaybackResource? = player.currentPlaybackResource.value,
    ): Boolean {
        if (!shouldSwitchToCounterpartAudio(song.id, resource)) return true
        if (originalAudioPlayback?.songId == song.id && player.currentPlaybackResource.value?.musicVideoId == resource.musicVideoId) {
            return true
        }
        val original =
            originalAudioPlayback?.takeIf { it.songId == song.id }
                ?: originalAudioResource(song.id, originalResource)?.let { OriginalAudioPlayback(song.id, it, mvPositionMap) }
                ?: return false
        val playbackSong = player.currentSong.value?.takeIf { it.id == song.id } ?: return false
        val playbackInstanceId = player.currentPlaybackInstanceId
        val positionMap = mvPositionMap
        val switched =
            withContext(Dispatchers.IO) {
                player.playRecovered(
                    song = playbackSong,
                    resource = resource,
                    positionMs = 0L,
                    resumePlayback = player.wantsPlayback,
                    handoffPosition = { position -> counterpartPlaybackPosition(position, positionMap, switchToVideo = true) },
                    shouldCommit = { status().song?.id == song.id && (UiState.showVideoViewer || UiState.showVideoFullscreen) },
                    recoveryPlaybackInstanceId = playbackInstanceId,
                )
            }
        if (switched) {
            originalAudioPlayback = original
            if (!UiState.showVideoViewer && !UiState.showVideoFullscreen) restoreOriginalAudioSource()
        }
        return switched
    }

    fun originalSongPosition(positionMs: Long): Long {
        val song = player.currentSong.value ?: return positionMs
        val resource = player.currentPlaybackResource.value ?: return positionMs
        return if (shouldSwitchToCounterpartAudio(song.id, resource)) {
            counterpartPlaybackPosition(positionMs, mvPositionMap ?: originalAudioPlayback?.positionMap, switchToVideo = false)
        } else {
            positionMs
        }
    }

    fun restoreOriginalAudioSource() {
        val original = originalAudioPlayback ?: return
        val song = player.currentSong.value?.takeIf { it.id == original.songId } ?: return
        val playbackInstanceId = player.currentPlaybackInstanceId
        val positionMap = mvPositionMap
        val resumePlayback = player.wantsPlayback
        scope.launch(Dispatchers.IO) {
            val restored =
                player.playRecovered(
                    song = song,
                    resource = original.resource,
                    positionMs = 0L,
                    resumePlayback = resumePlayback,
                    handoffPosition = { position -> counterpartPlaybackPosition(position, positionMap, switchToVideo = false) },
                    shouldCommit = {
                        status().song?.id == original.songId &&
                            (!isVideoAvailable || (!UiState.showVideoViewer && !UiState.showVideoFullscreen))
                    },
                    recoveryPlaybackInstanceId = playbackInstanceId,
                )
            if (restored && originalAudioPlayback === original) originalAudioPlayback = null
        }
    }

    fun retainOriginalAudioForCounterpart(
        song: SongItem,
        resource: PlaybackResource,
    ) {
        originalAudioJob?.cancel()
        originalAudioJob = null
        if (!shouldResolveOriginalAudio(song.id, resource, originalAudioPlayback?.songId)) return
        val generation = status().generation
        val playbackInstanceId = player.currentPlaybackInstanceId
        originalAudioJob =
            scope.launch(Dispatchers.IO) {
                fun current() =
                    isActive &&
                        shouldCommitVideoCounterpartResolution(
                            song.id,
                            status().song?.id,
                            generation,
                            status().generation,
                            playbackInstanceId,
                            player.currentPlaybackInstanceId,
                        )
                if (!current()) return@launch
                val resolvedVideo = musicVideoResolver.resolveMusicVideo(song) as? MusicVideoResult.Found
                if (!current()) return@launch
                val original = originalAudioResource(song.id, fetchSource(song, false)) ?: return@launch
                withContext(Dispatchers.Main) {
                    if (!current() || player.currentPlaybackInstanceId != playbackInstanceId ||
                        player.currentSong.value?.id != song.id ||
                        player.currentPlaybackResource.value?.musicVideoId != resource.musicVideoId ||
                        !shouldResolveOriginalAudio(song.id, resource, originalAudioPlayback?.songId)
                    ) {
                        return@withContext
                    }
                    originalAudioPlayback = OriginalAudioPlayback(song.id, original, resolvedVideo?.positionMap)
                    resolvedVideo?.takeIf { it.videoId == resource.musicVideoId }?.let {
                        mvVideoId = it.videoId
                        mvPositionMap = it.positionMap
                    }
                    if (!UiState.showVideoViewer && !UiState.showVideoFullscreen) {
                        restoreOriginalAudioSource()
                    } else {
                        retimeLyrics()
                    }
                }
            }
    }

    var videoCrashRecoveryAttempts = 0
    var audioPausedForVideo = false
    var videoSeekTimeoutJob: Job? = null
    private var videoPlaybackJob: Job? = null
    private var backgroundResourceJob: Job? = null
    private var originalAudioJob: Job? = null

    private fun cancelBackgroundResolution() {
        backgroundResourceJob?.cancel()
        backgroundResourceJob = null
        originalAudioJob?.cancel()
        originalAudioJob = null
    }

    private var pendingVideoUrl: String? = null
    private var videoRecoveryStartMs: Long? = null
    private var videoStallWatchdogJob: Job? = null
    private var lastVideoFrameMs = 0L
    private var hasRenderedFirstFrame = false
    private var videoRecoveryActive = false
    private var videoRecoveryJob: Job? = null
    private var videoRecoveryAttempts = 0
    private val videoLoadMutex = Mutex()

    fun updateVisibility() {
        if (!canPlayVideo) {
            stopVideo()
            return
        }
        val song = status().song ?: return
        if (player.currentPlaybackResource.value?.hasVideo == true) {
            startVideo()
        } else {
            attachBackgroundResource(song, status().generation)
        }
    }

    fun resume() {
        if (canPlayVideo && isVideoActive) videoPlayer.resume()
    }

    fun pause() {
        if (isVideoActive && !audioPausedForVideo) videoPlayer.pause()
    }

    fun setPlaybackSpeed(speed: Float) = videoPlayer.setPlaybackSpeed(speed)

    fun bufferedPositionMs(duration: Long): Long = videoPlayer.getBufferedPositionMs(duration)

    fun setTargetSize(
        width: Int,
        height: Int,
        fillViewport: Boolean = false,
    ) = videoPlayer.setTargetSize(width, height, fillViewport)

    fun isPlayingResource(resource: PlaybackResource): Boolean = isVideoActive && videoPlayer.currentUrl == resource.videoUrl

    fun resetCrashRecovery() {
        videoCrashRecoveryAttempts = 0
    }

    fun seekTo(positionMs: Long) {
        if (!canPlayVideo || !isVideoActive) return
        isVideoResolving = true
        videoSeekTimeoutJob?.cancel()
        if (status().playing && !videoPlayer.hasOwnAudio) {
            audioPausedForVideo = true
            player.pause()
            videoSeekTimeoutJob =
                scope.launch {
                    delay(2500L.milliseconds)
                    if (audioPausedForVideo) {
                        audioPausedForVideo = false
                        isVideoResolving = false
                        player.resume()
                    }
                }
        }
        videoPlayer.seekTo(positionMs)
    }

    fun resetSeek() {
        videoSeekTimeoutJob?.cancel()
        audioPausedForVideo = false
    }

    fun stopVideo(cancelRecovery: Boolean = true) {
        if (cancelRecovery) {
            videoRecoveryJob?.cancel()
            videoRecoveryJob = null
            videoRecoveryActive = false
        }
        backgroundResourceJob?.cancel()
        backgroundResourceJob = null
        videoSeekTimeoutJob?.cancel()
        videoStallWatchdogJob?.cancel()
        videoStallWatchdogJob = null
        videoPlaybackJob?.cancel()
        videoPlaybackJob = null
        pendingVideoUrl = null
        videoRecoveryStartMs = null
        videoFrameSongId = null
        videoFrame = null
        videoPlayer.close()
        NativeMemoryTrimmer.schedule()
        isVideoActive = false
        isVideoResolving = false
        if (audioPausedForVideo) {
            audioPausedForVideo = false
            player.resume()
        }
        updateVideoAudioRouting()
    }

    private fun handleVideoEnded() {
        if (!canPlayVideo || !isVideoActive) return
        val currentMs = player.currentPlaybackPositionMs()
        val totalMs = player.duration.value
        val isAudioStillPlaying = player.isPlaying.value

        val isNearEnd = totalMs > 0 && currentMs >= totalMs - 8000L

        Logger.i(
            "PlayerState",
            "handleVideoEnded: pos=${currentMs}ms/${totalMs}ms" +
                " audioPlaying=$isAudioStillPlaying nearEnd=$isNearEnd" +
                " recoveryAttempt=$videoRecoveryAttempts/$MAX_VIDEO_RECOVERY_ATTEMPTS",
        )

        if (!isNearEnd &&
            isVideoAvailable &&
            (UiState.showVideoViewer || UiState.showVideoFullscreen)
        ) {
            recoverVideo(
                player.currentPlaybackPositionMs(),
                reportProfile = !isNearEnd,
            )
        } else {
            videoRecoveryAttempts = 0
            isVideoActive = false
            updateVideoAudioRouting()
        }
    }

    private fun recoverVideo(
        resumeMs: Long,
        reportProfile: Boolean = false,
        statusCode: Int? = null,
    ) {
        if (!canPlayVideo || !isVideoActive || videoRecoveryActive) return
        if (videoRecoveryAttempts >= MAX_VIDEO_RECOVERY_ATTEMPTS) {
            Logger.w("PlayerState", "Video recovery aborted: max attempts reached")
            onUnavailable()
            return
        }
        videoRecoveryActive = true
        videoRecoveryAttempts++
        val generation = status().generation
        val resume = resumeMs.takeIf { it > 0 } ?: player.currentPlaybackPositionMs()
        videoRecoveryJob =
            scope.launch(start = CoroutineStart.LAZY) {
                try {
                    if (reportProfile) reportVideoProfileFailure(statusCode)
                    if (!canPlayVideo) return@launch
                    stopVideo(cancelRecovery = false)
                    delay(1000.milliseconds)
                    if (!canPlayVideo || status().generation != generation) return@launch
                    Logger.i("PlayerState", "Refreshing playback resource for video recovery attempt #$videoRecoveryAttempts")
                    val res = refreshVideoResource()
                    val refreshed = res != null
                    Logger.i(
                        "PlayerState",
                        "Refresh result: success=$refreshed hasVideo=${res?.hasVideo}" +
                            " hasSabrVideo=${res?.hasSabrVideo} hasDirectVideo=${res?.hasDirectVideo}",
                    )
                    if (!canPlayVideo || status().generation != generation) return@launch
                    if (refreshed && res.hasVideo) {
                        startVideo(force = true, startMs = resume, resetRecovery = false)
                    } else {
                        onUnavailable()
                    }
                } finally {
                    if (videoRecoveryJob === coroutineContext[Job]) {
                        videoRecoveryJob = null
                        videoRecoveryActive = false
                    }
                }
            }
        videoRecoveryJob?.start()
    }

    private suspend fun refreshVideoResource(): PlaybackResource? {
        if (!canPlayVideo) return null
        val song = queue.currentItem() ?: status().song ?: return null
        val generation = status().generation
        val playbackInstanceId = player.currentPlaybackInstanceId
        if (!canUseDesktopVideoResource(song, player.currentPlaybackResource.value, status().canonicalMedia())) return null
        if (!player.refreshPlaybackResource(wantVideo = true)) return null
        return player.currentPlaybackResource.value?.takeIf {
            canPlayVideo && status().generation == generation && player.currentPlaybackInstanceId == playbackInstanceId &&
                status().song?.id == song.id && queue.currentItem()?.id == song.id &&
                canUseDesktopVideoResource(song, it, status().canonicalMedia())
        }
    }

    private suspend fun reportVideoProfileFailure(statusCode: Int?) {
        val resource = player.currentPlaybackResource.value ?: return
        val track = status().song ?: return
        val resolver = GlobalYouTubeRepository.streamResolver ?: return
        val category =
            when (statusCode) {
                401 -> PlaybackFailureCategory.TOKEN
                403 -> PlaybackFailureCategory.CDN_FORBIDDEN
                404, 410, 416 -> PlaybackFailureCategory.URL_EXPIRED
                else -> PlaybackFailureCategory.UNKNOWN
            }
        runCatching {
            resolver.reportPlaybackFailure(
                videoId = resource.videoId,
                clientName = resource.clientName,
                responseCode = statusCode,
                profileId = resource.profileId,
                hints = track.toContentHints(),
                category = category,
            )
        }
    }

    private fun startVideoStallWatchdog() {
        videoStallWatchdogJob?.cancel()
        videoStallWatchdogJob =
            scope.launch {
                while (videoStallWatchdogJob?.isActive == true) {
                    delay(1000.milliseconds)
                    if (!canPlayVideo) return@launch
                    val timeout = if (hasRenderedFirstFrame) VIDEO_STALL_TIMEOUT_MS else VIDEO_STALL_TIMEOUT_MS * 2
                    if (
                        shouldRecoverStalledVideo(
                            isVideoActive = isVideoActive,
                            isAudioPlaying = player.isPlaying.value,
                            isVideoResolving = isVideoResolving,
                            recoveryActive = videoRecoveryActive,
                            lastVideoFrameMs = lastVideoFrameMs,
                            nowMs = System.currentTimeMillis(),
                            timeoutMs = timeout,
                        )
                    ) {
                        Logger.w("PlayerState", "Video stall watchdog triggered (no frame for ${timeout}ms)")
                        recoverVideo(player.currentPlaybackPositionMs())
                    }
                }
            }
    }

    fun getVideoStats(): VideoPlayerStats {
        val stats = videoPlayer.getStats()
        val song = status().song
        val vid = stats.videoId.ifBlank { song?.id ?: "" }
        val pos = player.currentPlaybackPositionMs()
        val res = player.currentPlaybackResource.value
        val isSabr = res?.hasSabrVideo == true
        val resolvedSource =
            if (stats.streamSource.isNotBlank()) {
                stats.streamSource
            } else if (isSabr) {
                "SABR Video Stream"
            } else if (res?.videoUrl?.startsWith("http", ignoreCase = true) == true) {
                "HTTP Range (Direct Chunked)"
            } else {
                "Local Video Stream"
            }
        val activeAudioClock = if (videoPlayer.hasOwnAudio) stats.audioClockMs else pos
        val syncOffset = activeAudioClock - stats.videoPtsMs
        val counterpart = res?.counterpartSource ?: ""
        val mvVid = res?.musicVideoId
        val displayVid = if (!mvVid.isNullOrBlank() && mvVid != vid) "$vid (MV: $mvVid)" else vid
        return stats.copy(
            videoId = displayVid,
            volume = status().volume,
            audioClockMs = activeAudioClock,
            avSyncOffsetMs = syncOffset,
            streamSource = resolvedSource,
            counterpartSource = counterpart,
            isResolving = isVideoResolving,
        )
    }

    fun copyVideoDebugInfo() {
        val stats = getVideoStats()
        val track = status().song
        val artistNames = track?.artists?.joinToString { it.name } ?: "Unknown"
        val videoBitrateStr = if (stats.videoBitrateKbps > 0) " (${stats.videoBitrateKbps} kbps)" else ""
        val audioBitrateStr = if (stats.audioBitrateKbps > 0) " (${stats.audioBitrateKbps} kbps)" else ""
        val optimalResStr =
            if (stats.optimalResolution.isNotBlank() && stats.optimalResolution != stats.videoResolution) {
                " (Optimal: ${stats.optimalResolution})"
            } else {
                ""
            }
        val pixelFormatStr =
            if (stats.colorSpace.isNotBlank()) {
                "${stats.pixelFormat.ifBlank { "N/A" }} (${stats.colorSpace})"
            } else {
                stats.pixelFormat.ifBlank { "N/A" }
            }
        val framesStr = "${stats.decodedFrames} decoded, ${stats.renderedFrames} rendered, ${stats.droppedFrames} dropped"
        val syncStr = "${stats.avSyncOffsetMs} ms (PTS: ${stats.videoPtsMs} ms, Audio: ${stats.audioClockMs} ms)"

        val sb = StringBuilder()
        sb.appendLine("=== Metrolist Video Stats for Nerds ===")
        sb.appendLine("Video ID: ${stats.videoId.ifBlank { "N/A" }}")
        sb.appendLine("Track: ${track?.title ?: "Unknown"} - $artistNames")
        if (stats.counterpartSource.isNotBlank()) {
            sb.appendLine("MV Source: ${stats.counterpartSource}")
        }
        sb.appendLine("CPN: ${stats.cpn.ifBlank { "N/A" }}")
        sb.appendLine("Video Codec: ${stats.videoCodec.ifBlank { "N/A" }}$videoBitrateStr")
        sb.appendLine("Audio Codec: ${stats.audioCodec.ifBlank { "N/A" }}$audioBitrateStr")
        sb.appendLine("Container: ${stats.containerFormat.ifBlank { "N/A" }}")
        sb.appendLine("Resolution: ${stats.videoResolution.ifBlank { "N/A" }}$optimalResStr")
        sb.appendLine("FPS: ${"%.2f".format(stats.fps)} / ${"%.2f".format(stats.targetFps)} (Nominal)")
        sb.appendLine("Pixel Format: $pixelFormatStr")
        sb.appendLine("Frames: $framesStr")
        sb.appendLine("A/V Sync Offset: $syncStr")
        sb.appendLine("Render Latency: ${"%.2f".format(stats.renderDurationMs)} ms")
        sb.appendLine("Stream Source: ${stats.streamSource.ifBlank { "N/A" }}")
        sb.appendLine("Stream Position: ${stats.streamPositionBytes} / ${stats.mediaSizeBytes} bytes")
        sb.appendLine("Volume: ${(stats.volume * 100).toInt()}%")
        AppState.copyToClipboard(sb.toString())
    }

    fun updateVideoAudioRouting(level: Float = status().volume) {
        val videoOwnsAudio = isVideoActive && videoPlayer.hasOwnAudio
        player.setAudioOutputEnabled(status().audioOutputEnabled && !videoOwnsAudio)
        player.setVolume((level * 100).toInt())
        videoPlayer.setVolume(if (status().audioOutputEnabled) player.outputVolume else 0f)
    }

    fun startVideo(
        force: Boolean = false,
        startMs: Long? = null,
        resetRecovery: Boolean = true,
    ) {
        if (!canPlayVideo) return
        if (!UiState.showVideoViewer && !UiState.showVideoFullscreen) return
        val targetUrl = player.currentPlaybackResource.value?.videoUrl
        if (!force && pendingVideoUrl == targetUrl && targetUrl != null) {
            return
        }
        pendingVideoUrl = targetUrl
        videoRecoveryStartMs = startMs
        val generation = status().generation
        val playbackInstanceId = player.currentPlaybackInstanceId
        videoPlaybackJob?.cancel()
        videoPlaybackJob =
            scope.launch(start = CoroutineStart.LAZY) {
                fun current() =
                    isActive && videoPlaybackJob === coroutineContext[Job] &&
                        isCurrentPlaybackTransitionSafeForVideo(generation, playbackInstanceId)

                isVideoResolving = true
                try {
                    var resource = player.currentPlaybackResource.value
                    if (resource == null) {
                        pendingVideoUrl = null
                        return@launch
                    }
                    if (status().canonicalMedia() && (resource.musicVideoId ?: resource.videoId) != queue.currentItem()?.id) {
                        pendingVideoUrl = null
                        return@launch
                    }
                    val videoUrl = resource.videoUrl
                    val videoHeaders = resource.headers

                    val hasPot = videoUrl?.let { it.contains("&pot=") || it.contains("?pot=") } ?: false
                    val urlHost = videoUrl?.let { runCatching { URI(it).host }.getOrNull() } ?: "null"
                    Logger.i(
                        "PlayerState",
                        "startVideo: hasSabrVideo=${resource.hasSabrVideo}" +
                            " hasDirectVideo=${resource.hasDirectVideo} host=$urlHost" +
                            " hasPot=$hasPot client=${resource.clientName}" +
                            " clen=${resource.videoContentLengthBytes}",
                    )

                    if (!resource.hasVideo) {
                        if (current()) onUnavailable()
                        return@launch
                    }

                    val positionProvider: () -> Long = { videoRecoveryStartMs ?: player.currentPlaybackPositionMs() }

                    val loaded =
                        videoLoadMutex.withLock {
                            try {
                                var loadedInner =
                                    withContext(Dispatchers.IO) {
                                        if (!current()) return@withContext false
                                        if (resource.hasSabrVideo) {
                                            player.createSabrVideoSource(resource)?.let { source ->
                                                videoPlayer.load(source) { positionProvider() }
                                            } ?: false
                                        } else if (!videoUrl.isNullOrBlank() && !videoUrl.startsWith("sabr-video://")) {
                                            videoPlayer.load(videoUrl, videoHeaders) {
                                                positionProvider()
                                            }
                                        } else {
                                            false
                                        }
                                    }

                                if (!loadedInner &&
                                    !videoRecoveryActive &&
                                    current() &&
                                    (UiState.showVideoViewer || UiState.showVideoFullscreen)
                                ) {
                                    val freshResource = refreshVideoResource()
                                    if (freshResource != null) {
                                        val freshVideoUrl = freshResource.videoUrl
                                        val freshHeaders = freshResource.headers
                                        if (freshResource.hasVideo) {
                                            loadedInner =
                                                withContext(Dispatchers.IO) {
                                                    if (!current()) return@withContext false
                                                    if (!canUseDesktopVideoResource(
                                                            queue.currentItem(),
                                                            freshResource,
                                                            status().canonicalMedia(),
                                                        )
                                                    ) {
                                                        return@withContext false
                                                    }
                                                    if (freshResource.hasSabrVideo) {
                                                        player.createSabrVideoSource(freshResource)?.let { source ->
                                                            videoPlayer.load(source) { positionProvider() }
                                                        } ?: false
                                                    } else if (!freshVideoUrl.isNullOrBlank() &&
                                                        !freshVideoUrl.startsWith("sabr-video://")
                                                    ) {
                                                        videoPlayer.load(freshVideoUrl, freshHeaders) {
                                                            positionProvider()
                                                        }
                                                    } else {
                                                        false
                                                    }
                                                }
                                            if (loadedInner) pendingVideoUrl = freshVideoUrl
                                        }
                                    }
                                }
                                loadedInner
                            } finally {
                                // A cancelled native load can finish after hiding; retire it before the next load.
                                if (!current()) videoPlayer.close()
                            }
                        }

                    if (!loaded) {
                        if (!current()) return@launch
                        if (videoRecoveryActive) {
                            Logger.w("PlayerState", "startVideo: load failed but recovery already in flight, deferring")
                            return@launch
                        }
                        Logger.w("PlayerState", "startVideo: load failed")
                        onUnavailable()
                        return@launch
                    }
                    if (!current()) return@launch
                    val shouldStartPaused = !status().playing
                    Logger.i("PlayerState", "startVideo: load succeeded, calling videoPlayer.play(startPaused=$shouldStartPaused)")
                    hasRenderedFirstFrame = false
                    lastVideoFrameMs = System.currentTimeMillis()
                    videoLoadMutex.withLock {
                        if (current()) {
                            videoFrameSongId = status().song?.id
                            videoPlayer.play(startPaused = shouldStartPaused)
                        }
                    }
                    if (!current()) return@launch
                    isVideoActive = true
                    if (resetRecovery) videoRecoveryAttempts = 0
                    videoRecoveryStartMs = null
                    updateVideoAudioRouting()
                    startVideoStallWatchdog()
                    waitForFirstVideoFrame()
                    if (current() && status().playing) {
                        videoPlayer.resume()
                    }
                    Logger.i("PlayerState", "startVideo: finished waitForFirstVideoFrame, hasVideoFrame=${videoFrame != null}")
                } finally {
                    if (videoPlaybackJob === coroutineContext[Job]) isVideoResolving = false
                }
            }
        videoPlaybackJob?.start()
    }

    private suspend fun waitForFirstVideoFrame() {
        val deadline = System.currentTimeMillis() + VIDEO_FIRST_FRAME_WAIT_MS
        while (videoFrame == null && System.currentTimeMillis() < deadline && videoPlaybackJob?.isActive == true) {
            delay(50.milliseconds)
        }
    }

    private fun isCurrentPlaybackTransitionSafeForVideo(
        generation: Long,
        playbackInstanceId: Long,
    ): Boolean =
        isCurrentDesktopVideoLoad(
            generation,
            status().generation,
            playbackInstanceId,
            player.currentPlaybackInstanceId,
            status().song?.id,
            player.currentSong.value?.id,
            videoPlaybackJob?.isActive == true,
            canPlayVideo && (UiState.showVideoViewer || UiState.showVideoFullscreen),
        )

    fun close() {
        videoRecoveryJob?.cancel()
        cancelBackgroundResolution()
        videoSeekTimeoutJob?.cancel()
        videoStallWatchdogJob?.cancel()
        videoPlaybackJob?.cancel()
        videoPlayer.dispose()
    }
}
