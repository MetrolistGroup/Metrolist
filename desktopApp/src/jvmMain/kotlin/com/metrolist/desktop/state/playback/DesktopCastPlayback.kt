package com.metrolist.desktop.state.playback

import com.metrolist.shared.cast.CastDevice
import com.metrolist.shared.cast.CastError
import com.metrolist.shared.cast.CastMedia
import com.metrolist.shared.cast.CastProtocol
import com.metrolist.shared.cast.CastState
import com.metrolist.shared.cast.JvmCastManager
import com.metrolist.shared.cast.isPlayableOn
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.previewThumbnailUrl
import com.metrolist.shared.playback.MusicPlayer
import com.metrolist.shared.playback.MusicQueue
import com.metrolist.shared.playback.MusicQueueEntry
import com.metrolist.shared.youtube.playback.PlaybackResource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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

internal enum class DesktopCastTrackResult { LOADED, LOCAL, SUPERSEDED }

internal class DesktopCastPlayback(
    private val scope: CoroutineScope,
    private val player: MusicPlayer,
    private val queue: MusicQueue,
    private val stopVideo: () -> Unit,
    private val manager: JvmCastManager,
    private val transitions: DesktopPlaybackTransitions,
    /** A null protocol fetches for local playback. */
    private val fetch: suspend (SongItem, CastProtocol?, (Throwable) -> Unit) -> PlaybackResource?,
) {
    private var handledDisconnectGeneration: Long? = null

    @Volatile private var requestedPlaying: Boolean? = null

    @Volatile private var disconnectJob: Job? = null

    init {
        scope.launch {
            manager.state.collect { current ->
                val requested = requestedPlaying
                if (requested != null) {
                    if (!current.isConnected || (!current.isBuffering && !current.hasEnded && current.isPlaying == requested)) {
                        requestedPlaying = null
                    }
                }
            }
        }
    }

    fun shouldResumeAfterDisconnect(state: CastState): Boolean = state.mediaGeneration != handledDisconnectGeneration

    fun castTo(
        device: CastDevice,
        current: () -> DesktopCastHandoffState,
    ): Job? {
        val initial = current()
        val request = transitions.disconnectGeneration
        val song = initial.song
        if (song == null) {
            manager.showError(CastError.NO_MEDIA)
            return null
        }
        transitions.begin(handoff = true)
        return scope.launch {
            val entry = queue.state.value.let { it.entries.getOrNull(it.currentIndex) }
            val resource =
                player.currentPlaybackResource.value
                    ?.takeIf { !it.isSabr && it.contentLengthBytes != null && it.mimeType != null && it.isPlayableOn(device.protocol) }
                    ?: resolve(song, device.protocol)
            disconnectJob?.join()
            if (request != transitions.disconnectGeneration) return@launch
            if (resource == null) {
                manager.showError(CastError.UNSUPPORTED_STREAM)
                return@launch
            }
            val latest = current().forHandoff(initial) ?: return@launch
            val playing = transitions.playbackIntent(latest.playing)
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
                        entry,
                    ),
                    latest.positionMs,
                    playing,
                    latest.volume,
                    shouldCommit = {
                        request == transitions.disconnectGeneration && current().forHandoff(initial) != null &&
                            isCurrentQueueEntry(entry)
                    },
                )
            if (connected) {
                stopVideo()
            } else {
                resumeLocal({ current().forHandoff(initial)?.song }, latest.positionMs, playing)
            }
        }
    }

    fun setPlaying(playing: Boolean): Job {
        requestedPlaying = playing
        transitions.setPlaying(playing)
        return scope.launch {
            if (playing) manager.play() else manager.pause()
        }
    }

    fun playbackIntent(fallback: Boolean): Boolean = requestedPlaying ?: fallback

    fun disconnect(currentSong: () -> SongItem?) {
        val remote = manager.state.value
        handledDisconnectGeneration = remote.mediaGeneration
        val resolving = transitions.disconnect()
        disconnectJob =
            scope.launch {
                manager.disconnect()
                if (!resolving && remote.isConnected) resumeLocal(currentSong, remote.positionMs, remote.isPlaying)
            }
    }

    suspend fun resumeLocal(
        currentSong: () -> SongItem?,
        positionMs: Long,
        resumePlayback: Boolean,
    ) {
        val song = currentSong() ?: return
        val playbackInstanceId = player.currentPlaybackInstanceId
        val entry = queue.state.value.let { it.entries.getOrNull(it.currentIndex) }
        if (entry != null && entry.song.id != song.id) return
        val resource = withContext(Dispatchers.IO) { fetch(song, null) {} } ?: return
        withContext(Dispatchers.IO) {
            player.playRecovered(
                song = song,
                resource = resource,
                positionMs = positionMs,
                resumePlayback = transitions.playbackIntent(resumePlayback),
                shouldCommit = {
                    !manager.state.value.isConnected && currentSong()?.id == song.id &&
                        player.currentPlaybackInstanceId == playbackInstanceId &&
                        queue.state.value.let { it.entries.getOrNull(it.currentIndex)?.key } == entry?.key
                },
            )
        }
    }

    private fun isCurrentQueueEntry(entry: MusicQueueEntry?): Boolean {
        val current = queue.state.value.let { it.entries.getOrNull(it.currentIndex) }
        return current?.key == entry?.key && current?.song?.id == entry?.song?.id
    }

    private suspend fun resolve(
        song: SongItem,
        protocol: CastProtocol?,
        onFailure: (Throwable) -> Unit = {},
    ): PlaybackResource? = transitions.resolve { fetch(song, protocol, onFailure) }

    suspend fun resolveTrack(
        song: SongItem,
        onFailure: (Throwable) -> Unit = {},
        shouldCommit: () -> Boolean,
    ): PlaybackResource? {
        val remote = manager.state.value
        val resource = resolve(song, remote.device?.protocol, onFailure)
        disconnectJob?.join()
        if (!shouldCommit()) return null
        val active = manager.state.value
        transitions.observePlaybackIntent(remote, active)
        if (!active.isConnected) return withContext(Dispatchers.IO) { fetch(song, null, onFailure) }
        if (resource == null && remote.device?.id == active.device?.id) {
            handledDisconnectGeneration = active.mediaGeneration
            manager.showError(CastError.UNSUPPORTED_STREAM)
            manager.disconnect()
            return withContext(Dispatchers.IO) { fetch(song, null, onFailure) }
        }
        return resource
    }

    suspend fun replaceTrack(
        song: SongItem,
        positionMs: Long,
        autoplay: Boolean,
        resource: PlaybackResource? = null,
        shouldCommit: () -> Boolean,
    ): DesktopCastTrackResult {
        val remote = manager.state.value.takeIf { it.isConnected } ?: return DesktopCastTrackResult.LOCAL
        val request = transitions.disconnectGeneration
        val entry = queue.state.value.let { it.entries.getOrNull(it.currentIndex) }

        fun isCurrent() = shouldCommit() && entry?.song?.id == song.id && isCurrentQueueEntry(entry)
        val selectedResource =
            resource?.takeIf { !it.isSabr && it.isPlayableOn(remote.device?.protocol) }
                ?: resolve(song, remote.device?.protocol)
        disconnectJob?.join()
        if (!isCurrent()) return DesktopCastTrackResult.SUPERSEDED
        val active = manager.state.value
        if (!active.isConnected || request != transitions.disconnectGeneration) return DesktopCastTrackResult.LOCAL
        if (active.device?.id != remote.device?.id) return DesktopCastTrackResult.SUPERSEDED
        transitions.observePlaybackIntent(remote, active)
        val loaded =
            if (selectedResource == null) {
                manager.showError(CastError.UNSUPPORTED_STREAM)
                false
            } else {
                manager.replaceMedia(
                    CastMedia(song, song.previewThumbnailUrl, selectedResource, song.duration?.times(1_000L) ?: 0L, entry),
                    positionMs,
                    transitions.playbackIntent(autoplay),
                    active.volume,
                    shouldCommit = { isCurrent() && request == transitions.disconnectGeneration },
                )
            }
        disconnectJob?.join()
        if (!isCurrent()) return DesktopCastTrackResult.SUPERSEDED
        if (!manager.state.value.isConnected || request != transitions.disconnectGeneration) return DesktopCastTrackResult.LOCAL
        if (loaded) {
            stopVideo()
            return DesktopCastTrackResult.LOADED
        }
        handledDisconnectGeneration = manager.state.value.mediaGeneration
        manager.disconnect()
        return DesktopCastTrackResult.LOCAL
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
