package com.metrolist.shared.ui.wrapped

import com.metrolist.shared.playback.ffmpeg.AudioPlayer
import com.metrolist.shared.playback.ffmpeg.isAudioOutputUnavailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import kotlin.concurrent.thread

internal actual fun createWrappedAudioLibrary(onInterrupted: () -> Unit): WrappedAudioLibrary =
    DesktopWrappedAudioLibrary(Files.createTempDirectory("metrolist-wrapped-").toFile())

private class DesktopWrappedAudioLibrary(
    directory: File,
) : JvmWrappedAudioLibrary(directory) {
    override fun close() {
        super.close()
        if (!directory.exists()) return
        // Decoders release their files asynchronously, and Windows cannot delete a file that is still open.
        thread(isDaemon = true, name = "wrapped-audio-cleanup") {
            repeat(20) {
                Thread.sleep(500)
                if (directory.deleteRecursively()) return@thread
            }
            directory.walkTopDown().forEach { it.deleteOnExit() }
        }
    }
}

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
    private val preparationLock = Any()
    private var preparing = false
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
        val prepared =
            withContext(Dispatchers.IO) {
                synchronized(preparationLock) {
                    check(!closed)
                    preparing = true
                }
                try {
                    player.prepareFile(path, startPaused = true)
                } finally {
                    synchronized(preparationLock) {
                        preparing = false
                        if (closed) player.closeAsync()
                    }
                }
            }
        currentCoroutineContext().ensureActive()
        check(!closed && prepared)
    }

    override fun play() {
        check(!closed)
        player.play()
    }

    override fun close() {
        synchronized(preparationLock) {
            if (closed) return
            closed = true
            if (!preparing) {
                player.pause()
                player.closeAsync()
            }
        }
    }
}
