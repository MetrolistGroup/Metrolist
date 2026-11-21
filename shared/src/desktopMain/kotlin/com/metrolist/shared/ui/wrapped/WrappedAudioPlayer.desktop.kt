@file:OptIn(ExperimentalSabrApi::class)

package com.metrolist.shared.ui.wrapped

import com.metrolist.innertubex.extraction.AudioQuality
import com.metrolist.innertubex.sabr.ExperimentalSabrApi
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playback.ffmpeg.AudioPlayer
import com.metrolist.shared.playback.ffmpeg.HttpRangeInputStream
import com.metrolist.shared.playback.ffmpeg.SabrInputStream
import com.metrolist.shared.playback.ffmpeg.isAudioOutputUnavailable
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.state.GlobalYouTube
import com.metrolist.shared.state.GlobalYouTubeRepository
import com.metrolist.shared.youtube.extraction.models.toContentHints
import com.metrolist.shared.youtube.playback.sabrAudioPlaybackChunks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream

internal actual fun createWrappedAudioPlayer(
    onEnded: () -> Unit,
    onFailure: () -> Unit,
    onInterrupted: () -> Unit,
    volume: Float,
): WrappedAudioPlayer = DesktopWrappedAudioPlayer(onEnded, onFailure, onInterrupted, volume)

private class DesktopWrappedAudioPlayer(
    private val onEnded: () -> Unit,
    private val onFailure: () -> Unit,
    private val onInterrupted: () -> Unit,
    initialVolume: Float,
) : WrappedAudioPlayer {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var clockJob: Job? = null

    @Volatile
    private var player: AudioPlayer? = null
    private var input: InputStream? = null
    private var closed = false
    private var currentVolume = initialVolume

    override fun setVolume(volume: Float) {
        currentVolume = volume
        player?.setVolume(volume)
    }

    override suspend fun play(song: SongItem) {
        check(!closed)
        clearPlayer()
        val resolver = checkNotNull(GlobalYouTubeRepository.streamResolver)
        val quality = runCatching { AudioQuality.valueOf(Settings.shared.audioQuality) }.getOrDefault(AudioQuality.AUTO)
        val resource = resolver.resolve(song.id, song.toContentHints(wantVideo = false), quality, song)
        currentCoroutineContext().ensureActive()
        check(!closed)
        val current =
            AudioPlayer(
                onPlaybackCompleted = { if (player === it) onEnded() },
                onPlaybackFailed = { failedPlayer, error ->
                    if (player === failedPlayer) {
                        if (error.isAudioOutputUnavailable()) onInterrupted() else onFailure()
                    }
                },
            )
        player = current
        current.setVolume(currentVolume)
        val source =
            resource.sabrBootstrap?.let { bootstrap ->
                SabrInputStream(
                    mediaSize = bootstrap.contentLengthBytes ?: resource.contentLengthBytes,
                    expectedDurationMs = bootstrap.durationMs,
                    streamFactory = { initial, position ->
                        sabrAudioPlaybackChunks(
                            GlobalYouTube.client,
                            bootstrap,
                            position,
                            initial,
                            tokenSupplier = resource.sabrTokenSupplier,
                            onAttestationPending = resource.onSabrAttestationPending,
                        )
                    },
                )
            }
                ?: resource.contentLengthBytes
                    ?.takeIf {
                        it > 0L && resource.isDirectFile && resource.url.startsWith("https://")
                    }?.let { size ->
                        HttpRangeInputStream(
                            url = resource.url,
                            requestHeaders = resource.headers,
                            mediaSize = size,
                            chunkSize = resource.rangeChunkSizeBytes.coerceIn(65_536L, 1_048_576L).toInt(),
                            prefetchAhead = true,
                        )
                    }
        input = source
        var started = false
        try {
            val prepared =
                withContext(Dispatchers.IO) {
                    if (source != null) {
                        current.prepareStream(
                            source,
                            (resource.sabrBootstrap?.contentLengthBytes ?: resource.contentLengthBytes)
                                ?.coerceIn(1L, Int.MAX_VALUE.toLong() - 8L)
                                ?.toInt() ?: 1,
                            startPaused = true,
                        )
                    } else {
                        current.prepareFile(resource.url, resource.headers, startPaused = true)
                    }
                }
            currentCoroutineContext().ensureActive()
            check(!closed && player === current && prepared)
            current.play()
            clockJob =
                scope.launch {
                    while (isActive && player === current) {
                        current.getCurrentPosition()
                        delay(250)
                    }
                }
            started = true
        } finally {
            if (!started) {
                current.pause()
                current.closeAsync()
                source?.close()
                if (player === current) player = null
                if (input === source) input = null
            }
        }
    }

    private fun clearPlayer() {
        clockJob?.cancel()
        clockJob = null
        val previous = player
        player = null
        previous?.pause()
        previous?.closeAsync()
        input?.close()
        input = null
    }

    override fun close() {
        closed = true
        clearPlayer()
        scope.cancel()
    }
}
