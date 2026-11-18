package com.metrolist.shared.playback

import com.metrolist.shared.youtube.playback.PlaybackEventKind
import com.metrolist.shared.youtube.playback.PlaybackEventLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class AnimatedCoverPlayer {
    private val session = Mutex()
    private val sizeLock = Any()
    private var targetWidth = 0
    private var targetHeight = 0
    private var fillViewport = false
    private var player: VideoPlayer? = null

    suspend fun play(
        url: String,
        active: Flow<Boolean>,
        onFrameChanged: (VideoFrame?) -> Unit,
    ) {
        session.withLock {
            currentCoroutineContext().ensureActive()
            val ended = CompletableDeferred<Unit>()
            try {
                val player =
                    synchronized(sizeLock) {
                        VideoPlayer(
                            onFrame = { onFrameChanged(it) },
                            onEnded = {
                                onFrameChanged(null)
                                ended.complete(Unit)
                            },
                            looping = true,
                        ).also {
                            this.player = it
                            it.setTargetSize(targetWidth, targetHeight, fillViewport)
                        }
                    }
                val loaded =
                    coroutineScope {
                        val loading = async(Dispatchers.IO) { player.load(url, positionProvider = { 0L }) }
                        try {
                            loading.await()
                        } catch (error: CancellationException) {
                            player.dispose()
                            throw error
                        }
                    }
                if (!loaded) return
                player.setVolume(0f)
                coroutineScope {
                    val activity =
                        launch {
                            active.distinctUntilChanged().collect { playing ->
                                if (playing) player.resume() else player.pause()
                            }
                        }
                    try {
                        ended.await()
                    } finally {
                        activity.cancel()
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                PlaybackEventLog.error(PlaybackEventKind.PLAYER, "AnimatedCover", "Animated cover failed", error)
            } catch (error: LinkageError) {
                PlaybackEventLog.error(PlaybackEventKind.PLAYER, "AnimatedCover", "Animated cover native library failed", error)
            } finally {
                // A replacement must wait for native preparation, decoding and drawing to finish.
                val retiring = synchronized(sizeLock) { player.also { player = null } }
                withContext(NonCancellable) {
                    withContext(Dispatchers.IO) { retiring?.disposeAsync()?.join() }
                    onFrameChanged(null)
                }
            }
        }
    }

    fun setTargetSize(
        width: Int,
        height: Int,
        fillViewport: Boolean,
    ) {
        synchronized(sizeLock) {
            targetWidth = width
            targetHeight = height
            this.fillViewport = fillViewport
            player?.setTargetSize(width, height, fillViewport)
        }
    }
}
