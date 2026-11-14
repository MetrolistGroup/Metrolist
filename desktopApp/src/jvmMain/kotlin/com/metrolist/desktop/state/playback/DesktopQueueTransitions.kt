package com.metrolist.desktop.state.playback

import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.isPodcast
import com.metrolist.shared.playback.MusicPlayer
import com.metrolist.shared.playback.MusicQueue
import com.metrolist.shared.playback.playbackFadeThroughGains
import com.metrolist.shared.playback.shouldPromotePlaybackCrossfade
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.youtube.playback.PlaybackResource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class DesktopQueueTransitions(
    private val scope: CoroutineScope,
    private val player: MusicPlayer,
    private val queue: MusicQueue,
    private val video: DesktopVideoLifecycle,
    private val fetch: suspend (SongItem) -> PlaybackResource?,
    private val onSongChanged: (SongItem, PlaybackResource, String?) -> Unit,
    private val onUnconsumedEof: () -> Unit,
    private val episodeResumePosition: suspend (SongItem, Long) -> Long,
    private val allowed: () -> Boolean = { true },
) {
    private var crossfadeJob: Job? = null
    var isCrossfading = false
        private set
    private val pendingEofState = MutableStateFlow(false)
    private var pendingEof: Boolean
        get() = pendingEofState.value
        set(value) {
            pendingEofState.value = value
        }
    private var attemptedPreload: Pair<Long, Long>? = null
    private var targetKey: Long? = null
    private var targetSongId: String? = null
    private var originalSong: SongItem? = null
    private var originalResource: PlaybackResource? = null
    private var originalInstanceId = 0L
    private var preloadJob: Job? = null
    private var preloadToken: MusicPlayer.PlaybackPreloadToken? = null
    private var preloadSong: SongItem? = null
    private var preloadResource: PlaybackResource? = null
    private var preloadInstanceId = 0L

    fun cancel() {
        crossfadeJob?.cancel()
        attemptedPreload = null
        targetKey = null
        targetSongId = null
        preloadJob?.cancel()
        preloadJob = null
        preloadToken = null
        preloadSong = null
        preloadResource = null
        preloadInstanceId = 0L
        player.cancelNext()
        resetCrossfade()
        pendingEof = false
    }

    fun failedOutgoingTrack(
        videoId: String?,
        instanceId: Long,
    ): SongItem? {
        val outgoing = originalSong ?: return null
        if (!isCrossfading || outgoing.id != videoId || originalInstanceId != instanceId) return null
        cancel()
        return outgoing
    }

    fun acceptsEof(instanceId: Long): Boolean =
        (isCrossfading && instanceId == originalInstanceId) || instanceId == player.currentPlaybackInstanceId

    fun onEof(instanceId: Long): Boolean {
        if (!acceptsEof(instanceId)) return true
        if (!allowed() || !hasCurrentTarget()) {
            cancel()
            return false
        }
        if (isCrossfading) {
            pendingEof = true
            return true
        }
        return promotePreparedNext(instanceId)
    }

    fun promoteNext(
        startPlaying: Boolean,
        beforePromotion: () -> Unit,
    ): Boolean {
        if (isCrossfading || !allowed() || !hasCurrentTarget() || queue.peekNext()?.isPodcast == true) return false
        return promotePreparedNext(player.currentPlaybackInstanceId, startPlaying, beforePromotion)
    }

    private fun promotePreparedNext(
        instanceId: Long,
        startPlaying: Boolean? = null,
        beforePromotion: () -> Unit = {},
    ): Boolean {
        val token = preloadToken
        val song = preloadSong
        val resource = preloadResource
        if (token != null && song != null && resource != null && preloadInstanceId == instanceId) {
            if (!player.isPreloadedNextActive(token)) return false
            beforePromotion()
            crossfadeJob?.cancel()
            preloadToken = null
            preloadSong = null
            preloadResource = null
            preloadJob = null
            val previousSongId = player.currentSong.value?.id
            player.setNextVolume(100f)
            if (
                player.swapToNext(
                    token,
                    resource,
                    startPlaying = startPlaying,
                    beforeResume = {
                        queue.getNext()
                        player.setCurrentSong(song, resource)
                    },
                )
            ) {
                targetKey = null
                targetSongId = null
                onSongChanged(song, resource, previousSongId)
                return true
            }
            player.cancelNext()
        }
        return false
    }

    fun queueChanged() {
        if (targetKey != null && (!allowed() || !hasCurrentTarget())) {
            val ended = pendingEof
            cancel()
            if (ended) scope.launch { onUnconsumedEof() }
        }
    }

    private fun hasCurrentTarget(): Boolean =
        targetKey != null &&
            queue.state.value.let {
                val target = it.entries.getOrNull(it.currentIndex + 1)
                target != null && target.key == targetKey && target.song.id == targetSongId
            }

    fun nearEnd(
        next: SongItem,
        gapless: Boolean,
        fadeDurationMs: Long = (Settings.shared.crossfadeDuration * 1_000f).toLong(),
    ) {
        queueChanged()
        if (!allowed()) return
        val state = queue.state.value
        val target = state.entries.getOrNull(state.currentIndex + 1)?.takeIf { it.song == next } ?: return
        val instanceId = player.currentPlaybackInstanceId
        val attempt = instanceId to target.key
        if (attemptedPreload == attempt) return
        attemptedPreload = attempt
        targetKey = target.key
        targetSongId = target.song.id
        val videoId = player.currentPlaybackResource.value?.videoId ?: player.currentSong.value?.id
        preloadInstanceId = instanceId
        preloadJob =
            scope.launch {
                val resource = withContext(Dispatchers.IO) { fetch(next) } ?: return@launch
                if (!allowed() || !hasCurrentTarget() || player.currentPlaybackInstanceId != instanceId) return@launch
                if (next.isPodcast && episodeResumePosition(next, resource.durationSeconds?.times(1000L) ?: 0L) > 0L) return@launch
                val context = currentCoroutineContext()
                val token =
                    withContext(Dispatchers.IO) {
                        player.prepareNextTrack(resource, instanceId, videoId, startPaused = true, shouldCommit = { context.isActive })
                    } ?: return@launch
                if (!allowed() || !hasCurrentTarget() || player.currentPlaybackInstanceId != instanceId) {
                    player.cancelNext()
                    return@launch
                }
                preloadSong = next
                preloadResource = resource
                preloadToken = token
                if (!gapless && Settings.shared.crossfadeEnabled && fadeDurationMs > 0L) {
                    crossfadeJob = launch { crossfade(next, resource, token, fadeDurationMs) }
                }
            }
    }

    private suspend fun crossfade(
        next: SongItem,
        resource: PlaybackResource,
        token: MusicPlayer.PlaybackPreloadToken,
        fadeDurationMs: Long,
    ) {
        player.setCrossfadeSampling(fadeDurationMs)
        combine(player.currentPosition, player.duration, player.playbackSpeed, player.isPlaying) { position, duration, speed, playing ->
            playing && desktopRemainingOutputMs(position, duration, speed) <= fadeDurationMs
        }.first { it }
        if (!allowed() || !hasCurrentTarget() || preloadToken != token || !player.isPreloadedNextActive(token)) {
            player.setCrossfadeSampling(null)
            return
        }
        originalSong = player.currentSong.value
        originalResource = player.currentPlaybackResource.value
        originalInstanceId = player.currentPlaybackInstanceId
        isCrossfading = true
        player.isCrossfading = true
        video.stopVideo()
        val startPosition = player.currentPosition.value
        val clock = DesktopCrossfadeClock(startPosition, fadeDurationMs)
        var progress = 0f
        var promoted = false
        while (progress < 1f) {
            if (!allowed() || (!promoted && (!hasCurrentTarget() || !player.isPreloadedNextActive(token)))) {
                abortCrossfade()
                return
            }
            val sampledPosition = player.currentPosition.value
            val sampledPlaying = player.isPlaying.value
            progress = maxOf(progress, clock.sample(sampledPosition, player.playbackSpeed.value, sampledPlaying, pendingEof))
            val (outgoingGain, incomingGain) = playbackFadeThroughGains(progress)
            if (promoted) {
                player.setTransitionVolume(incomingGain * 100f)
            } else {
                player.setTransitionVolume(outgoingGain * 100f)
                player.setNextVolume(incomingGain * 100f)
                if (shouldPromotePlaybackCrossfade(incomingGain)) {
                    val incomingStartPosition = player.nextPlayerPosition
                    promoted =
                        player.swapToNext(
                            token,
                            resource,
                            beforeResume = {
                                queue.getNext()
                                player.setCurrentSong(next, resource)
                            },
                            initialTransitionGain = incomingGain,
                        )
                    if (!promoted) {
                        player.setTransitionVolume(100f)
                        abortCrossfade()
                        return
                    }
                    clock.rebase(incomingStartPosition)
                    preloadToken = null
                    preloadSong = null
                    preloadResource = null
                    targetKey = null
                    targetSongId = null
                    onSongChanged(next, resource, originalSong?.id)
                    originalSong = null
                    originalResource = null
                    originalInstanceId = 0L
                    pendingEof = false
                }
            }
            if (progress < 1f) {
                combine(
                    player.currentPosition,
                    player.isPlaying,
                    player.playbackStateChanges,
                    pendingEofState,
                ) { position, playing, _, eof ->
                    position != sampledPosition || playing != sampledPlaying || eof || (!promoted && player.nextPlayerFailed)
                }.first { it }
            }
        }
        if (!promoted) {
            abortCrossfade()
            return
        }
        player.setTransitionVolume(100f)
        player.setNextVolume(0f)
        player.setCrossfadeSampling(null)
        isCrossfading = false
        player.isCrossfading = false
        originalSong = null
        originalResource = null
        originalInstanceId = 0L
        pendingEof = false
    }

    private fun resetCrossfade() {
        isCrossfading = false
        player.isCrossfading = false
        player.setCrossfadeSampling(null)
        crossfadeJob = null
        originalSong?.let { player.setCurrentSong(it, originalResource) }
        originalSong = null
        originalResource = null
        originalInstanceId = 0L
        video.updateVideoAudioRouting()
    }

    private fun abortCrossfade() {
        preloadToken = null
        preloadSong = null
        preloadResource = null
        player.cancelNext()
        resetCrossfade()
        if (pendingEof) {
            pendingEof = false
            scope.launch { onUnconsumedEof() }
        }
    }
}

internal fun desktopRemainingOutputMs(
    positionMs: Long,
    durationMs: Long,
    speed: Float,
): Long = ((durationMs - positionMs).coerceAtLeast(0L) / speed).toLong()

internal class DesktopCrossfadeClock(
    private var lastPositionMs: Long,
    private val durationMs: Long,
) {
    private var elapsedMs = 0.0

    fun rebase(positionMs: Long) {
        lastPositionMs = positionMs
    }

    fun sample(
        positionMs: Long,
        speed: Float,
        playing: Boolean,
        ended: Boolean,
    ): Float {
        if (playing) elapsedMs += (positionMs - lastPositionMs).coerceAtLeast(0L) / speed.toDouble()
        lastPositionMs = positionMs
        return if (ended) 1f else (elapsedMs / durationMs).toFloat().coerceIn(0f, 1f)
    }
}
