package com.metrolist.android.playback

import android.content.Context
import android.os.SystemClock
import com.metrolist.android.deviceconnect.AndroidDeviceConnect
import com.metrolist.android.deviceconnect.canUseDeviceConnectTrack
import com.metrolist.android.deviceconnect.requiresDeviceConnectTrackReload
import com.metrolist.android.deviceconnect.toDeviceConnectRepeatMode
import com.metrolist.android.deviceconnect.toPlayerRepeatMode
import com.metrolist.shared.deviceconnect.DcLocalPlaybackState
import com.metrolist.shared.deviceconnect.DcPlaybackSnapshot
import com.metrolist.shared.deviceconnect.DcScrobbleSession
import com.metrolist.shared.deviceconnect.DeviceConnectPlaybackPort
import com.metrolist.shared.discord.DiscordRpcManager
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.playback_unable_to_play_queued_track
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.isVideoSong
import com.metrolist.shared.playback.PlaybackSeekSource
import com.metrolist.shared.scrobble.ScrobbleManager
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.utils.Logger
import com.metrolist.shared.youtube.playback.PlaybackResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.getString
import java.io.IOException
import kotlin.math.abs

internal class AndroidDeviceConnectPlayback(
    private val context: Context,
    private val player: AndroidPlaybackPlayer,
    private val queue: AndroidQueueCoordinator,
    private val video: AndroidVideoModeController,
    private val transitions: AndroidQueueTransitions,
    private val scrobbleManager: ScrobbleManager,
    private val history: AndroidListeningHistory,
    private val repeatMode: MutableStateFlow<Int>,
    private val shuffleModeEnabled: MutableStateFlow<Boolean>,
    private val fetchPlaybackResource: suspend (SongItem, Boolean) -> PlaybackResource?,
    private val isCasting: () -> Boolean,
    private val onSave: () -> Unit,
    private val onDiscordUpdate: () -> Unit,
) {
    var playbackReleased = false
        private set
    var syncTargetLoaded = true
        private set

    fun onLocalTrackLoaded() {
        syncTargetLoaded = true
    }

    private var controllerPlayback: DcLocalPlaybackState? = null
    private var appliedSyncSessionId: String? = null
    private var appliedSyncTrackRevision: Long? = null
    val port: DeviceConnectPlaybackPort =
        object : DeviceConnectPlaybackPort {
            override val canStartDefaultPlayback: Boolean get() = !isCasting()

            override suspend fun snapshot(): DcLocalPlaybackState =
                withContext(Dispatchers.Main.immediate) {
                    check(!playbackReleased)
                    controllerPlayback?.let { return@withContext it.copy(positionMs = player.getRealtimePositionMs()) }
                    DcLocalPlaybackState(
                        queue = player.queueSnapshots.value,
                        title = queue.queueTitle.value,
                        repeatMode = repeatMode.value.toDeviceConnectRepeatMode(),
                        playing = player.wantsPlayback,
                        positionMs = player.getRealtimePositionMs(),
                        durationMs = player.duration.value,
                        speed = player.playbackSpeed(),
                        pitch = player.playbackPitch.value,
                        volume = player.volume.value,
                        ready =
                            player.isReady && syncTargetLoaded &&
                                player.currentSong.value?.id ==
                                player.songQueue.value
                                    .getOrNull(player.currentMediaItemIndex.value)
                                    ?.id,
                        finite = queue.isFiniteQueue,
                        scrobbleSession = scrobbleManager.session(),
                    )
                }

            override suspend fun apply(
                snapshot: DcPlaybackSnapshot,
                positionMs: Long,
                shouldCommit: () -> Boolean,
            ) = withContext(Dispatchers.Main.immediate) {
                if (playbackReleased || !shouldCommit()) return@withContext
                val desired = snapshot.playback
                val desiredQueue = desired.queue
                val song = desiredQueue.displayedItems.getOrNull(desiredQueue.currentIndex)
                val previousQueue = player.queueSnapshot()
                val trackChanged =
                    requiresDeviceConnectTrackReload(
                        currentSongId = player.currentSong.value?.id,
                        currentResourceId = player.currentPlaybackResource.value?.let { it.musicVideoId ?: it.videoId },
                        previousQueue = previousQueue,
                        snapshot = snapshot,
                        appliedSessionId = appliedSyncSessionId,
                        appliedTrackRevision = appliedSyncTrackRevision,
                        targetLoaded = syncTargetLoaded,
                    )
                if (trackChanged) syncTargetLoaded = false
                val startedAt = SystemClock.elapsedRealtime()

                fun targetPosition(): Long {
                    val elapsed = if (desired.playing) ((SystemClock.elapsedRealtime() - startedAt) * desired.speed).toLong() else 0L
                    val target = (positionMs + elapsed).coerceAtLeast(0L)
                    return desired.durationMs.takeIf { it > 0L }?.let { target.coerceAtMost(it) } ?: target
                }
                transitions.cancelNextTrackPreload()
                val seedId = desiredQueue.originalItems.firstOrNull()?.id
                if (AndroidDeviceConnect.manager.remote.isFollowing || desired.finite != queue.isFiniteQueue ||
                    seedId != queue.recommendationSeedSongId
                ) {
                    queue.resetQueueSession(seedId, desired.title, desired.finite)
                }
                queue.setTitle(desired.title)
                repeatMode.value = desired.repeatMode.toPlayerRepeatMode()
                if (trackChanged || previousQueue != desiredQueue) player.restoreQueue(desiredQueue, currentTrackChanged = trackChanged)
                shuffleModeEnabled.value = player.isShuffleEnabled
                player.setPlaybackSpeed(desired.speed)
                desired.pitch?.let(player::setPlaybackPitch)
                if (song != null && !canUseDeviceConnectTrack(song, AndroidDeviceConnect.manager.remote.isFollowing, trackChanged)) {
                    syncTargetLoaded = false
                    player.pause()
                    throw IOException(getString(Res.string.playback_unable_to_play_queued_track))
                }
                if (song == null) {
                    player.stop()
                } else if (trackChanged) {
                    video.clearVideoMode()
                    player.pause()
                    val wantVideo =
                        shouldRequestQueueVideo(
                            isVideoSong = song.isVideoSong,
                            videoPlaybackEnabled = Settings.shared.musicVideoPlaybackEnabled,
                        )
                    val started =
                        queue.withQueueLoading(isPlaybackLoading = true) {
                            val resource =
                                fetchPlaybackResource(song, wantVideo)
                                    ?: throw IOException(getString(Res.string.playback_unable_to_play_queued_track))
                            if (playbackReleased || !shouldCommit()) return@withQueueLoading false
                            player.prepareAndPlay(
                                song = song,
                                resource = resource,
                                positionMs = targetPosition(),
                                resumePlayback = desired.playing,
                                shouldCommit = { !playbackReleased && shouldCommit() },
                                // A paused source cannot catch a moving target; remote drift correction seeks after the swap.
                                playWhenReadyProvider = { desired.playing },
                            )
                        }
                    if (playbackReleased || !shouldCommit()) return@withContext
                    if (!started) throw IOException(getString(Res.string.playback_unable_to_play_queued_track))
                    if (desired.playing) player.resume() else player.pause()
                    val hasVideo =
                        shouldActivateQueueVideoMode(
                            videoRequested = wantVideo,
                            resolvedResourceHasVideo = player.currentPlaybackResource.value?.hasVideo == true,
                        )
                    video.onQueuePlaybackStarted(song, hasVideo, player.currentPlaybackResource.value)
                } else {
                    if (abs(player.getRealtimePositionMs() - targetPosition()) >
                        150L
                    ) {
                        player.seekTo(targetPosition(), PlaybackSeekSource("device_connect"))
                    }
                    if (desired.playing) player.resume() else player.pause()
                }
                if (playbackReleased || !shouldCommit()) return@withContext
                appliedSyncSessionId = snapshot.sessionId
                appliedSyncTrackRevision = snapshot.trackRevision
                syncTargetLoaded = true
                scrobbleManager.setReportingEnabled(
                    history.enabled,
                    song,
                    player.isPlaying.value,
                    desired.scrobbleSession,
                )
                if (!queue.isFiniteQueue && queue.recommendationBootstrapJob == null && song != null) {
                    queue.bootstrapRecommendations(queue.recommendationSeedSongId ?: song.id)
                }
                onSave()
            }

            override suspend fun acknowledge(snapshot: DcPlaybackSnapshot) =
                withContext(Dispatchers.Main.immediate) {
                    if (!playbackReleased && syncTargetLoaded) {
                        appliedSyncSessionId = snapshot.sessionId
                        appliedSyncTrackRevision = snapshot.trackRevision
                    }
                }

            override suspend fun extendQueue(shouldCommit: () -> Boolean): DcLocalPlaybackState? =
                withContext(Dispatchers.Main.immediate) {
                    if (playbackReleased || !shouldCommit() || queue.isFiniteQueue ||
                        AndroidDeviceConnect.manager.remote.isFollowing
                    ) {
                        return@withContext null
                    }
                    queue.extendRadioQueue { !playbackReleased && shouldCommit() }
                    if (!playbackReleased && shouldCommit()) snapshot() else null
                }

            override suspend fun setControllerPlayback(playback: DcLocalPlaybackState?) =
                withContext(Dispatchers.Main.immediate) {
                    if (playbackReleased) return@withContext
                    if (playback != null && controllerPlayback?.queue != playback.queue) {
                        transitions.cancelNextTrackPreload()
                        video.clearVideoMode()
                        queue.resetQueueSession(
                            playback.queue.originalItems
                                .firstOrNull()
                                ?.id,
                            playback.title,
                            playback.finite,
                        )
                        queue.setTitle(playback.title)
                        repeatMode.value = playback.repeatMode.toPlayerRepeatMode()
                        if (player.queueSnapshots.value != playback.queue) player.restoreQueue(playback.queue)
                        shuffleModeEnabled.value = playback.queue.isShuffled
                        syncTargetLoaded = false
                    }
                    controllerPlayback = playback
                    player.setControllerPlayback(playback)
                    if (playback !=
                        null
                    ) {
                        scrobbleManager.setReportingEnabled(false, player.currentSong.value, playback.playing, playback.scrobbleSession)
                    }
                }

            override suspend fun setPlaying(playing: Boolean) =
                withContext(Dispatchers.Main.immediate) {
                    if (!playbackReleased) {
                        if (playing && syncTargetLoaded) player.resume() else player.pause()
                    }
                }

            override suspend fun setAudioOutputEnabled(enabled: Boolean) =
                withContext(Dispatchers.Main.immediate) {
                    if (!playbackReleased) player.setAudioOutputEnabled(enabled)
                }

            override suspend fun seekTo(positionMs: Long) =
                withContext(Dispatchers.Main.immediate) {
                    if (!playbackReleased) player.seekTo(positionMs, PlaybackSeekSource("device_connect"))
                }

            override suspend fun setVolume(volume: Float) =
                withContext(Dispatchers.Main.immediate) {
                    if (!playbackReleased) player.setVolume(volume)
                }

            override suspend fun setSpeed(speed: Float) =
                withContext(Dispatchers.Main.immediate) {
                    if (!playbackReleased) player.setPlaybackSpeed(speed)
                }

            override fun setExternalReportingEnabled(
                enabled: Boolean,
                session: DcScrobbleSession?,
            ) {
                val reportingChanged = history.enabled != enabled
                history.setEnabled(enabled)
                if (reportingChanged) Logger.i(REPORTING_TAG, "platform reporting enabled=$enabled")
                player.setExternalReportingEnabled(enabled)
                val song = player.currentSong.value
                scrobbleManager.setReportingEnabled(enabled, song, player.isPlaying.value, session)
                if (!enabled) DiscordRpcManager.clear() else onDiscordUpdate()
            }
        }

    fun release() {
        playbackReleased = true
    }
}
