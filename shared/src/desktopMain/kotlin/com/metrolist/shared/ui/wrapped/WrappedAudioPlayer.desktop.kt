package com.metrolist.shared.ui.wrapped

import com.metrolist.shared.playback.ffmpeg.AudioPlayer
import com.metrolist.shared.playback.ffmpeg.isAudioOutputUnavailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.file.Files

internal actual fun createWrappedAudioLibrary(onInterrupted: () -> Unit): WrappedAudioLibrary =
    JvmWrappedAudioLibrary(Files.createTempDirectory("metrolist-wrapped-").toFile())

internal actual fun createWrappedAudioPlayer(
    onEnded: () -> Unit,
    onFailure: () -> Unit,
    onInterrupted: () -> Unit,
    volume: Float,
): WrappedAudioPlayer = DesktopWrappedAudioPlayer(onEnded, onFailure, onInterrupted, volume)

private class DesktopWrappedAudioPlayer(
    onEnded: () -> Unit,
    onFailure: () -> Unit,
    onInterrupted: () -> Unit,
    initialVolume: Float,
) : WrappedAudioPlayer {
    @Volatile private var closed = false
    private val player =
        AudioPlayer(
            onPlaybackCompleted = { if (!closed) onEnded() },
            onPlaybackFailed = { _, error ->
                if (!closed) {
                    if (error.isAudioOutputUnavailable()) onInterrupted() else onFailure()
                }
            },
        ).apply { setVolume(initialVolume) }

    override fun setVolume(volume: Float) {
        player.setVolume(volume)
    }

    override suspend fun prepare(path: String) {
        check(!closed)
        val prepared = withContext(Dispatchers.IO) { player.prepareFile(path, startPaused = true) }
        currentCoroutineContext().ensureActive()
        check(!closed && prepared)
    }

    override fun play() {
        check(!closed)
        player.play()
    }

    override fun close() {
        if (closed) return
        closed = true
        player.pause()
        player.closeAsync()
    }
}
