package com.metrolist.desktop.state.playback

import com.metrolist.shared.cast.CastDevice
import com.metrolist.shared.cast.CastError
import com.metrolist.shared.cast.CastMedia
import com.metrolist.shared.cast.CastProtocol
import com.metrolist.shared.cast.CastState
import com.metrolist.shared.cast.JvmCastManager
import com.metrolist.shared.cast.isPlayableOn
import com.metrolist.shared.model.RepeatMode
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.previewThumbnailUrl
import com.metrolist.shared.playback.MusicPlayer
import com.metrolist.shared.playback.MusicQueue
import com.metrolist.shared.youtube.playback.PlaybackResource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class DesktopCastHandoffState(
    val song: SongItem?,
    val artwork: String?,
    val positionMs: Long,
    val durationMs: Long,
    val playing: Boolean,
    val volume: Float,
    val playbackInstanceId: Long,
) {
    fun forHandoff(initial: DesktopCastHandoffState): DesktopCastHandoffState? =
        takeIf { song?.id == initial.song?.id && playbackInstanceId == initial.playbackInstanceId }
}

internal class DesktopCastPlayback(
    private val scope: CoroutineScope,
    private val player: MusicPlayer,
    private val queue: MusicQueue,
    private val stopVideo: () -> Unit,
    private val manager: JvmCastManager,
    /** A null protocol fetches for local playback. */
    private val fetch: suspend (SongItem, CastProtocol?) -> PlaybackResource?,
) {
    private var handledDisconnectGeneration: Long? = null

    fun shouldResumeAfterDisconnect(state: CastState): Boolean = state.mediaGeneration != handledDisconnectGeneration

    fun castTo(
        device: CastDevice,
        current: () -> DesktopCastHandoffState,
    ) {
        val initial = current()
        val song = initial.song
        if (song == null) {
            manager.showError(CastError.NO_MEDIA)
            return
        }
        scope.launch {
            val resource =
                player.currentPlaybackResource.value
                    ?.takeIf { !it.isSabr && it.contentLengthBytes != null && it.mimeType != null && it.isPlayableOn(device.protocol) }
                    ?: fetch(song, device.protocol)
            if (resource == null) {
                manager.showError(CastError.UNSUPPORTED_STREAM)
                return@launch
            }
            val latest = current().forHandoff(initial) ?: return@launch
            if (latest.playing) player.pause()
            handledDisconnectGeneration = manager.state.value.mediaGeneration
            val connected =
                manager.cast(
                    device,
                    CastMedia(
                        song,
                        latest.artwork,
                        resource,
                        latest.durationMs,
                        queue.state.value.let { it.entries.getOrNull(it.currentIndex) },
                    ),
                    latest.positionMs,
                    latest.playing,
                    latest.volume,
                    shouldCommit = { current().forHandoff(initial) != null },
                )
            if (connected) {
                stopVideo()
            } else {
                resumeLocal({ current().forHandoff(initial)?.song }, latest.positionMs, latest.playing)
            }
        }
    }

    fun disconnect(currentSong: () -> SongItem?) {
        val remote = manager.state.value
        handledDisconnectGeneration = remote.mediaGeneration
        scope.launch {
            manager.disconnect()
            resumeLocal(currentSong, remote.positionMs, remote.isPlaying)
        }
    }

    suspend fun resumeLocal(
        currentSong: () -> SongItem?,
        positionMs: Long,
        resumePlayback: Boolean,
    ) {
        val song = currentSong() ?: return
        val resource = withContext(Dispatchers.IO) { fetch(song, null) } ?: return
        withContext(Dispatchers.IO) {
            player.playRecovered(
                song = song,
                resource = resource,
                positionMs = positionMs,
                resumePlayback = resumePlayback,
                shouldCommit = { !manager.state.value.isConnected && currentSong()?.id == song.id },
            )
        }
    }

    suspend fun advance(
        song: SongItem?,
        repeatMode: RepeatMode,
        loadRecommendations: suspend () -> Unit,
    ): PlaybackQueueDecision? {
        val remote = manager.state.value.takeIf { it.isConnected } ?: return null
        var decision = decidePlaybackQueueTransition(repeatMode, true, song, queue.items.value, queue.currentIndex.value)
        if (decision.action == PlaybackQueueAction.AUTOPLAY) {
            val size = queue.items.value.size
            loadRecommendations()
            if (queue.items.value.size == size) return null
            decision = decidePlaybackQueueTransition(repeatMode, true, song, queue.items.value, queue.currentIndex.value)
        }
        val next = decision.song ?: return null
        if (!decision.action.replacesCastMedia()) return null
        val targetIndex =
            when (decision.action) {
                PlaybackQueueAction.PLAY_NEXT -> queue.currentIndex.value + 1
                PlaybackQueueAction.RESTART_QUEUE -> 0
                else -> queue.currentIndex.value
            }
        val targetEntry =
            queue.state.value.entries
                .getOrNull(targetIndex)
        val resource = withContext(Dispatchers.IO) { fetch(next, remote.device?.protocol) }
        val active = manager.state.value
        if (!active.isConnected || !active.hasEnded || active.mediaGeneration != remote.mediaGeneration) return null
        if (resource == null) {
            manager.showError(CastError.UNSUPPORTED_STREAM)
        } else if (manager.replaceMedia(
                CastMedia(next, next.previewThumbnailUrl, resource, next.duration?.times(1_000L) ?: 0L, targetEntry),
                0L,
                active.isPlaying || active.hasEnded,
                active.volume,
            )
        ) {
            return decision
        }
        if (manager.state.value.mediaGeneration != remote.mediaGeneration) return null
        handledDisconnectGeneration = remote.mediaGeneration
        manager.disconnect()
        return decision
    }
}

internal fun desktopCastCheckpointPosition(
    remote: CastState,
    localVideoId: String?,
    originalSongPosition: (Long) -> Long,
): Long =
    if (remote.mediaVideoId != null &&
        remote.mediaVideoId == localVideoId
    ) {
        originalSongPosition(remote.positionMs)
    } else {
        remote.positionMs
    }
