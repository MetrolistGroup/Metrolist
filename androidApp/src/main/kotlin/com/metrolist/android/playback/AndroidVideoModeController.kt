package com.metrolist.android.playback

import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.snapshotFlow
import androidx.media3.common.Player
import com.metrolist.android.deviceconnect.AndroidDeviceConnect
import com.metrolist.android.listentogether.AndroidListenTogetherManager
import com.metrolist.shared.api.musicvideo.CounterpartPositionMap
import com.metrolist.shared.api.musicvideo.MusicTrackResult
import com.metrolist.shared.api.musicvideo.MusicVideoResolver
import com.metrolist.shared.api.musicvideo.MusicVideoResult
import com.metrolist.shared.deviceconnect.DcPlaybackAction
import com.metrolist.shared.deviceconnect.DcPlaybackCommand
import com.metrolist.shared.deviceconnect.DcSyncError
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.music_track_unavailable
import com.metrolist.shared.generated.resources.music_video_not_found
import com.metrolist.shared.generated.resources.music_video_unavailable
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.isPodcast
import com.metrolist.shared.model.isVideoSong
import com.metrolist.shared.playback.PlaybackSeekSource
import com.metrolist.shared.settings.MusicVideoQuality
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.state.GlobalYouTubeRepository
import com.metrolist.shared.youtube.extraction.models.automaticVideoMaxHeight
import com.metrolist.shared.youtube.playback.PlaybackBandwidthMeter
import com.metrolist.shared.youtube.playback.PlaybackEventKind
import com.metrolist.shared.youtube.playback.PlaybackEventLog
import com.metrolist.shared.youtube.playback.PlaybackResource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import timber.log.Timber
import java.io.IOException
import kotlin.math.abs

internal class AndroidVideoModeController(
    private val context: Context,
    private val player: AndroidPlaybackPlayer,
    private val scope: CoroutineScope,
    private val musicVideoResolver: MusicVideoResolver,
    private val withPlaybackLoading: suspend (suspend () -> PlaybackResource?) -> PlaybackResource?,
    private val fetchPlaybackResource: suspend (SongItem, Boolean) -> PlaybackResource?,
    private val onError: (String) -> Unit,
) {
    companion object {
        private const val MUSIC_VIDEO_TAG = "MusicVideo"
        private const val AUTO_QUALITY_POLL_MS = 2_000L
        private const val AUTO_QUALITY_COOLDOWN_MS = 15_000L
        private const val STALL_MS = 4_000L
        private const val UPSWITCH_BACKOFF_MS = 60_000L
        private const val MAX_UPSWITCH_BACKOFF_MS = 600_000L
        private const val SEEK_JUMP_MS = 5_000L
    }

    private val _isVideoModeActive = MutableStateFlow(false)
    val isVideoModeActive: StateFlow<Boolean> = _isVideoModeActive.asStateFlow()
    private val _isVideoModeResolving = MutableStateFlow(false)
    val isVideoModeResolving: StateFlow<Boolean> = _isVideoModeResolving.asStateFlow()
    private val _isVideoViewerVisible = MutableStateFlow(Settings.shared.videoViewerVisible)
    val isVideoViewerVisible: StateFlow<Boolean> = _isVideoViewerVisible.asStateFlow()
    private var videoModeJob: Job? = null
    private var videoModeGeneration = 0L
    private var videoModeSongId: String? = null
    private var videoModePositionMap: CounterpartPositionMap? = null
    private var videoModeOriginalAudioSong: SongItem? = null

    init {
        scope.launch { runSabrAutoQuality() }
    }

    private suspend fun runSabrAutoQuality() {
        var lastPositionMs = -1L
        var bufferingSinceMs: Long? = null
        var nextDecisionAtMs = 0L
        var upswitchBlockedUntilMs = 0L
        var upswitchBackoffMs = UPSWITCH_BACKOFF_MS
        // A downswitch that never commits keeps the old rung playing; descend from the attempted rung next time.
        var failedDownswitch: Pair<PlaybackResource, Int>? = null
        while (true) {
            combine(
                player.currentPlaybackResource,
                player.wantsPlaybackState,
                _isVideoModeActive,
                snapshotFlow { Settings.shared.musicVideoQuality },
            ) { resource, wantsPlayback, videoActive, quality ->
                wantsPlayback && videoActive && quality == MusicVideoQuality.AUTO &&
                    resource?.hasSabrVideo == true && resource.videoHeight != null &&
                    resource.sabrVideoBootstrap?.videoVariants?.isNotEmpty() == true
            }.first { eligible ->
                if (!eligible) {
                    bufferingSinceMs = null
                    lastPositionMs = -1L
                }
                eligible
            }
            delay(AUTO_QUALITY_POLL_MS)
            val resource = player.currentPlaybackResource.value
            val song = player.currentSong.value
            val variants = resource?.sabrVideoBootstrap?.videoVariants.orEmpty()
            val currentHeight = failedDownswitch?.takeIf { it.first === resource }?.second ?: resource?.videoHeight
            if (
                resource == null || song == null || !player.wantsPlayback || currentHeight == null || !resource.hasSabrVideo ||
                variants.isEmpty() ||
                !_isVideoModeActive.value || videoModeJob?.isActive == true ||
                Settings.shared.musicVideoQuality != MusicVideoQuality.AUTO
            ) {
                bufferingSinceMs = null
                continue
            }
            val now = SystemClock.elapsedRealtime()
            val positionMs = player.getRealtimePositionMs()
            val buffering = player.playbackStateFlow.value == Player.STATE_BUFFERING && player.wantsPlayback
            // A seek also buffers; restart the stall clock whenever the position jumps.
            bufferingSinceMs = if (buffering && abs(positionMs - lastPositionMs) < SEEK_JUMP_MS) bufferingSinceMs ?: now else null
            lastPositionMs = positionMs
            if (now < nextDecisionAtMs) continue
            val stalled = bufferingSinceMs?.let { now - it >= STALL_MS } == true
            val maxHeight =
                androidVideoMaxHeight(MusicVideoQuality.AUTO, automaticVideoMaxHeight(bandwidthBps = Long.MAX_VALUE)) ?: continue
            val bandwidthBps = PlaybackBandwidthMeter.recentBandwidthBps()
            val next =
                nextAutoSabrVideoVariant(
                    variants = variants,
                    currentHeight = currentHeight,
                    maxHeight = maxHeight,
                    bandwidthBps = bandwidthBps,
                    audioBitrate = resource.bitrate ?: 0,
                    stalled = stalled,
                    allowUpswitch = now >= upswitchBlockedUntilMs && player.isPlaying.value,
                ) ?: continue
            val upswitch = next.height > currentHeight
            val committed =
                player.prepareAndPlay(
                    seekSource = PlaybackSeekSource("replacement", mapOf("reason" to "video_mode")),
                    song = song,
                    resource = resource.withSabrVideoVariant(next),
                    positionMs = player.getRealtimePositionMs(),
                    resumePlayback = player.wantsPlayback,
                    shouldCommit = {
                        player.currentPlaybackResource.value === resource && _isVideoModeActive.value &&
                            Settings.shared.musicVideoQuality == MusicVideoQuality.AUTO
                    },
                    positionMsProvider = player::getRealtimePositionMs,
                    // A stalled player has nothing to keep playing; warming a second one would only split the bandwidth.
                    replaceInForeground = !upswitch,
                )
            nextDecisionAtMs = SystemClock.elapsedRealtime() + AUTO_QUALITY_COOLDOWN_MS
            bufferingSinceMs = null
            if (upswitch && committed) {
                upswitchBackoffMs = UPSWITCH_BACKOFF_MS
            } else {
                upswitchBlockedUntilMs = nextDecisionAtMs + upswitchBackoffMs
                if (upswitch) upswitchBackoffMs = (upswitchBackoffMs * 2).coerceAtMost(MAX_UPSWITCH_BACKOFF_MS)
            }
            failedDownswitch = if (committed || upswitch) null else resource to next.height
            PlaybackEventLog.info(
                PlaybackEventKind.PLAYER,
                "Auto quality ${if (committed) "switched" else "could not switch"} SABR video from ${currentHeight}p to " +
                    "${next.height}p (itag ${next.format.itag}).",
                videoId = resource.videoId,
                clientName = resource.clientName,
                profileId = resource.profileId,
                details = mapOf("bandwidthBps" to bandwidthBps.toString(), "stalled" to stalled.toString()),
            )
        }
    }

    fun markQueueTarget(songId: String) {
        if (songId != videoModeSongId) {
            videoModeOriginalAudioSong = null
            videoModePositionMap = null
        }
        videoModeSongId = songId
    }

    fun onSongChanged(song: SongItem?) {
        if ((_isVideoModeActive.value || _isVideoModeResolving.value) && song?.id != videoModeSongId) clearVideoMode()
    }

    fun selectVideoForQueue(song: SongItem) {
        if (!song.isVideoSong || song.isPodcast || !Settings.shared.musicVideoPlaybackEnabled || song.isLocal ||
            song.id.startsWith("local_")
        ) {
            return
        }
        clearVideoMode()
        setVideoViewerVisible(true)
    }

    fun onPreloadedPlaybackCommitted(
        song: SongItem,
        resource: PlaybackResource,
    ) {
        val hasVideo = shouldActivateQueueVideoMode(_isVideoViewerVisible.value, resource.hasVideo)
        onQueuePlaybackStarted(song, hasVideo, resource)
    }

    fun onQueuePlaybackStarted(
        song: SongItem,
        hasVideo: Boolean,
        resource: PlaybackResource? = null,
    ) {
        markQueueTarget(song.id)
        if (hasVideo && resource?.videoId == song.id && resource.musicVideoId != null) {
            videoModeOriginalAudioSong = null
            videoModePositionMap = resource.musicVideoPositionMap
        } else if (!hasVideo || !song.isVideoSong) {
            videoModeOriginalAudioSong = null
            videoModePositionMap = null
        }
        _isVideoModeActive.value = hasVideo
        videoModeSongId = song.id.takeIf { hasVideo }
    }

    fun toggleVideoMode() {
        if (!Settings.shared.musicVideoPlaybackEnabled || _isVideoModeResolving.value) return
        val sync = AndroidDeviceConnect.manager.remote
        if (AndroidListenTogetherManager.isGuestPlaybackLocked) {
            if (sync.isActive) sync.reportError(DcSyncError.NOT_ALLOWED)
            return
        }
        player.currentSong.value ?: return
        setVideoMode(!_isVideoViewerVisible.value)
    }

    fun setVideoMode(wantVideo: Boolean) {
        if (AndroidDeviceConnect.manager.remote.isFollowing) return
        if (wantVideo && !Settings.shared.musicVideoPlaybackEnabled) return
        if (!wantVideo && _isVideoModeResolving.value && !_isVideoModeActive.value) {
            clearVideoMode(resetViewerPreference = true)
        } else if (wantVideo && !_isVideoModeActive.value) {
            requestVideoMode(wantVideo = true)
        } else if (!wantVideo && _isVideoModeActive.value) {
            requestVideoMode(wantVideo = false)
        } else {
            setVideoViewerVisible(wantVideo)
        }
    }

    fun changeMusicVideoResolution(quality: MusicVideoQuality) {
        if (Settings.shared.musicVideoQuality == quality) return
        Settings.shared.musicVideoQuality = quality
        if (_isVideoModeActive.value) requestVideoMode(wantVideo = true)
    }

    private fun requestVideoMode(wantVideo: Boolean) {
        if (wantVideo && !Settings.shared.musicVideoPlaybackEnabled) return
        val sourceSong = player.currentSong.value ?: return
        val sync = AndroidDeviceConnect.manager.remote
        val syncSessionId = sync.state.value.sessionId
        val syncQueueRevision = sync.queueRevision
        val syncTrackRevision = sync.trackRevision
        val wasVideoModeActive = _isVideoModeActive.value
        if (sourceSong.id.startsWith("local_")) {
            Timber.tag(MUSIC_VIDEO_TAG).d(
                "mode request ignored videoId=%s wantVideo=%s local=%s",
                sourceSong.id,
                wantVideo,
                sourceSong.id.startsWith("local_"),
            )
            return
        }
        val isPodcastEpisode = sourceSong.isPodcast
        val target =
            PlaybackTarget(
                songId = sourceSong.id,
                playbackInstanceId = player.currentPlaybackInstanceId,
                queueRequestId = player.latestQueuePlaybackRequestId,
            )
        val generation = ++videoModeGeneration
        videoModeSongId = sourceSong.id

        videoModeJob?.cancel()
        _isVideoModeResolving.value = true
        Timber.tag(MUSIC_VIDEO_TAG).d(
            "mode request generation=%d sourceVideoId=%s wantVideo=%s sourceIsVideo=%s instance=%d queueRequest=%d positionMs=%d playing=%s playWhenReady=%s",
            generation,
            sourceSong.id,
            wantVideo,
            sourceSong.isVideoSong,
            target.playbackInstanceId,
            target.queueRequestId,
            player.getRealtimePositionMs(),
            player.isPlaying.value,
            player.wantsPlayback,
        )
        PlaybackEventLog.info(
            PlaybackEventKind.PLAYER,
            "Android video mode requested.",
            videoId = sourceSong.id,
            details =
                mapOf(
                    "wantVideo" to wantVideo.toString(),
                    "videoModeActive" to wasVideoModeActive.toString(),
                    "videoViewerVisible" to _isVideoViewerVisible.value.toString(),
                    "playbackInstanceId" to target.playbackInstanceId.toString(),
                    "queueRequestId" to target.queueRequestId.toString(),
                    "playbackPositionMs" to player.getRealtimePositionMs().toString(),
                ),
        )
        videoModeJob =
            scope.launch {
                try {
                    var playbackSong = sourceSong
                    var positionMap: CounterpartPositionMap? = null
                    val originalAudio = originalAudioForVideo(sourceSong.id, videoModeSongId, videoModeOriginalAudioSong)
                    if (wantVideo && !sourceSong.isVideoSong && !isPodcastEpisode) {
                        when (val result = musicVideoResolver.resolveMusicVideo(sourceSong)) {
                            is MusicVideoResult.Found -> {
                                Timber.tag(MUSIC_VIDEO_TAG).d(
                                    "counterpart video found generation=%d sourceVideoId=%s targetVideoId=%s mappedPosition=%s",
                                    generation,
                                    sourceSong.id,
                                    result.videoId,
                                    result.positionMap != null,
                                )
                                playbackSong = GlobalYouTubeRepository.instance.getSongMetadata(result.videoId)
                                    ?: throw IOException(getString(Res.string.music_video_unavailable))
                                positionMap = result.positionMap
                            }

                            MusicVideoResult.NotFound -> {
                                Timber.tag(MUSIC_VIDEO_TAG).w(
                                    "counterpart video not found generation=%d sourceVideoId=%s",
                                    generation,
                                    sourceSong.id,
                                )
                                if (isCurrentVideoModeTarget(target, generation)) {
                                    onError(getString(Res.string.music_video_not_found))
                                }
                                return@launch
                            }

                            MusicVideoResult.Error -> {
                                Timber.tag(MUSIC_VIDEO_TAG).w(
                                    "counterpart video lookup failed generation=%d sourceVideoId=%s",
                                    generation,
                                    sourceSong.id,
                                )
                                if (isCurrentVideoModeTarget(target, generation)) {
                                    onError(getString(Res.string.music_video_unavailable))
                                }
                                return@launch
                            }
                        }
                    } else if (!wantVideo && originalAudio != null) {
                        playbackSong = originalAudio
                        positionMap = videoModePositionMap?.reversed()
                    } else if (shouldResolveMusicTrackCounterpart(sourceSong.isVideoSong, wantVideo)) {
                        when (val result = musicVideoResolver.resolveMusicTrack(sourceSong)) {
                            is MusicTrackResult.Found -> {
                                Timber.tag(MUSIC_VIDEO_TAG).d(
                                    "counterpart track found generation=%d sourceVideoId=%s targetVideoId=%s mappedPosition=%s",
                                    generation,
                                    sourceSong.id,
                                    result.song.id,
                                    result.positionMap != null,
                                )
                                playbackSong = result.song
                                positionMap = result.positionMap
                            }

                            MusicTrackResult.NotFound, MusicTrackResult.Error -> {
                                Timber.tag(MUSIC_VIDEO_TAG).w(
                                    "counterpart track unavailable generation=%d sourceVideoId=%s result=%s, falling back to audio stream",
                                    generation,
                                    sourceSong.id,
                                    result::class.simpleName,
                                )
                            }
                        }
                    } else {
                        positionMap = videoModePositionMap?.reversed()
                    }

                    if (!isCurrentVideoModeTarget(target, generation)) return@launch
                    if (playbackSong.id != sourceSong.id &&
                        sync.intercept(
                            DcPlaybackCommand(
                                DcPlaybackAction.REPLACE_CURRENT,
                                songs = listOf(playbackSong),
                                positionMs = positionMap?.mapPosition(player.getRealtimePositionMs()) ?: player.getRealtimePositionMs(),
                                expectedTrackRevision = syncTrackRevision,
                            ),
                            expectedQueueRevision = syncQueueRevision,
                            expectedSessionId = syncSessionId,
                        )
                    ) {
                        return@launch
                    }

                    fun canCommit(): Boolean =
                        (!wantVideo || Settings.shared.musicVideoPlaybackEnabled) &&
                            isCurrentVideoModeTarget(target, generation) &&
                            (playbackSong.id == sourceSong.id || (!sync.isActive && !AndroidListenTogetherManager.isGuestPlaybackLocked))

                    if (!canCommit()) return@launch
                    val result =
                        withPlaybackLoading { fetchPlaybackResource(playbackSong, wantVideo) }
                    if (!canCommit()) {
                        Timber.tag(MUSIC_VIDEO_TAG).d(
                            "mode result discarded generation=%d targetVideoId=%s reason=stale_target",
                            generation,
                            playbackSong.id,
                        )
                        return@launch
                    }
                    val resource = result
                    if (resource == null || (wantVideo && !resource.hasVideo)) {
                        Timber.tag(MUSIC_VIDEO_TAG).w(
                            "mode resource unusable generation=%d targetVideoId=%s wantVideo=%s resourcePresent=%s hasVideo=%s",
                            generation,
                            playbackSong.id,
                            wantVideo,
                            resource != null,
                            resource?.hasVideo,
                        )
                        onError(
                            getString(
                                if (wantVideo) {
                                    Res.string.music_video_unavailable
                                } else {
                                    Res.string.music_track_unavailable
                                },
                            ),
                        )
                        return@launch
                    }
                    val transitionPositionMap = positionMap.takeUnless { wantVideo && wasVideoModeActive }

                    fun targetPositionFor(sourcePositionMs: Long): Long {
                        val mappedPositionMs = transitionPositionMap?.mapPosition(sourcePositionMs) ?: sourcePositionMs
                        return if (wantVideo) {
                            mappedPositionMs.coerceAtLeast(0L)
                        } else {
                            clampToDuration(playbackSong, mappedPositionMs)
                        }
                    }
                    val currentPositionMs = player.getRealtimePositionMs()
                    val positionMs = transitionPositionMap?.mapPosition(currentPositionMs) ?: currentPositionMs
                    val resumePlayback = player.wantsPlayback
                    videoModeSongId = playbackSong.id
                    val targetPositionMs = targetPositionFor(currentPositionMs)
                    Timber.tag(MUSIC_VIDEO_TAG).d(
                        "mode prepare generation=%d targetVideoId=%s fromPositionMs=%d mappedPositionMs=%d targetPositionMs=%d resume=%s replaceQueueItem=%s",
                        generation,
                        playbackSong.id,
                        currentPositionMs,
                        positionMs,
                        targetPositionMs,
                        resumePlayback,
                        playbackSong.id != sourceSong.id,
                    )

                    val committed =
                        player.prepareAndPlay(
                            seekSource = PlaybackSeekSource("replacement", mapOf("reason" to "video_mode")),
                            song = playbackSong,
                            resource = resource,
                            positionMs = targetPositionMs,
                            resumePlayback = resumePlayback,
                            shouldCommit = ::canCommit,
                            replaceCurrentQueueItem = playbackSong.id != sourceSong.id,
                            positionMsProvider = { targetPositionFor(player.getRealtimePositionMs()) },
                        )
                    if (!committed) {
                        Timber.tag(MUSIC_VIDEO_TAG).d(
                            "mode prepare not committed generation=%d targetVideoId=%s",
                            generation,
                            playbackSong.id,
                        )
                        return@launch
                    }

                    videoModeSongId = playbackSong.id
                    videoModePositionMap =
                        when {
                            !wantVideo -> null
                            wasVideoModeActive -> videoModePositionMap
                            else -> positionMap
                        }
                    videoModeOriginalAudioSong =
                        when {
                            !wantVideo -> null
                            wasVideoModeActive -> videoModeOriginalAudioSong
                            sourceSong.id != playbackSong.id -> sourceSong
                            else -> null
                        }
                    _isVideoModeActive.value = wantVideo
                    setVideoViewerVisible(wantVideo)
                    PlaybackEventLog.info(
                        PlaybackEventKind.PLAYER,
                        "Android video mode committed.",
                        videoId = playbackSong.id,
                        details =
                            mapOf(
                                "sourceSongId" to sourceSong.id,
                                "wantVideo" to wantVideo.toString(),
                                "playbackInstanceId" to player.currentPlaybackInstanceId.toString(),
                                "queueRequestId" to target.queueRequestId.toString(),
                                "playbackPositionMs" to player.getRealtimePositionMs().toString(),
                            ),
                    )
                    Timber.tag(MUSIC_VIDEO_TAG).d(
                        "mode committed generation=%d videoId=%s enabled=%s viewerVisible=%s instance=%d",
                        generation,
                        playbackSong.id,
                        wantVideo,
                        _isVideoViewerVisible.value,
                        player.currentPlaybackInstanceId,
                    )
                } catch (error: CancellationException) {
                    Timber.tag(MUSIC_VIDEO_TAG).d(
                        "mode cancelled generation=%d sourceVideoId=%s wantVideo=%s",
                        generation,
                        sourceSong.id,
                        wantVideo,
                    )
                    throw error
                } catch (error: Exception) {
                    if (isCurrentVideoModeTarget(target, generation)) {
                        Timber.tag(MUSIC_VIDEO_TAG).w(
                            "mode failed generation=%d sourceVideoId=%s wantVideo=%s type=%s",
                            generation,
                            sourceSong.id,
                            wantVideo,
                            error::class.simpleName,
                        )
                        onError(
                            getString(
                                if (wantVideo) {
                                    Res.string.music_video_unavailable
                                } else {
                                    Res.string.music_track_unavailable
                                },
                            ),
                        )
                    }
                } finally {
                    if (generation == videoModeGeneration) _isVideoModeResolving.value = false
                    Timber.tag(MUSIC_VIDEO_TAG).d(
                        "mode finished generation=%d currentGeneration=%d resolving=%s",
                        generation,
                        videoModeGeneration,
                        _isVideoModeResolving.value,
                    )
                }
            }
    }

    private fun isCurrentVideoModeTarget(
        target: PlaybackTarget,
        generation: Long,
    ): Boolean =
        target.isCurrent(
            generation,
            videoModeGeneration,
            player.currentPlaybackInstanceId,
            player.latestQueuePlaybackRequestId,
            player.currentSong.value?.id,
        )

    private fun clampToDuration(
        song: SongItem,
        positionMs: Long,
    ): Long {
        val durationMs = song.duration?.times(1000L)?.takeIf { it > 0L } ?: return positionMs.coerceAtLeast(0L)
        return positionMs.coerceIn(0L, durationMs - 1L)
    }

    fun clearVideoMode(resetViewerPreference: Boolean = false) {
        videoModeGeneration++
        videoModeJob?.cancel()
        videoModeJob = null
        videoModeSongId = null
        videoModePositionMap = null
        videoModeOriginalAudioSong = null
        _isVideoModeActive.value = false
        _isVideoModeResolving.value = false
        if (resetViewerPreference) {
            setVideoViewerVisible(false)
        }
    }

    private fun setVideoViewerVisible(visible: Boolean) {
        Settings.shared.videoViewerVisible = visible
        _isVideoViewerVisible.value = visible
    }

    internal data class PlaybackTarget(
        val songId: String,
        val playbackInstanceId: Long,
        val queueRequestId: Long,
    ) {
        fun isCurrent(
            generation: Long,
            currentGeneration: Long,
            playbackInstanceId: Long,
            queueRequestId: Long,
            songId: String?,
        ): Boolean =
            generation == currentGeneration && this.playbackInstanceId == playbackInstanceId && this.queueRequestId == queueRequestId &&
                this.songId == songId
    }
}

internal fun originalAudioForVideo(
    videoId: String,
    currentVideoId: String?,
    originalAudio: SongItem?,
): SongItem? = originalAudio?.takeIf { videoId == currentVideoId }
