package com.metrolist.shared.ui.wrapped

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.ui.playback.PlaybackController
import com.metrolist.shared.ui.playback.PlaybackUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

@Composable
fun rememberWrappedSoundtrack(
    controller: PlaybackController,
    songs: List<SongItem>,
): WrappedSoundtrack {
    val scope = rememberCoroutineScope()
    val currentSongs by rememberUpdatedState(songs)
    val soundtrack = remember(controller) { WrappedSoundtrack(controller, { currentSongs }, scope) }
    LaunchedEffect(soundtrack, controller) {
        controller.wrappedSoundtrackAllowed.collect { soundtrack.setAllowed(it) }
    }
    LaunchedEffect(soundtrack, controller) {
        controller.state.collect { soundtrack.onPlaybackChanged(it) }
    }
    LaunchedEffect(soundtrack, controller) {
        controller.playbackCommands.collect { soundtrack.onPlaybackCommand() }
    }
    LaunchedEffect(soundtrack, controller) {
        controller.playbackVolume?.collect { soundtrack.updateVolume() }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { soundtrack.stop() }
    DisposableEffect(soundtrack) { onDispose { soundtrack.stop() } }
    return soundtrack
}

class WrappedSoundtrack internal constructor(
    private val controller: PlaybackController,
    private val songs: () -> List<SongItem>,
    private val scope: CoroutineScope,
) {
    var enabled by mutableStateOf(false)
        private set
    var loading by mutableStateOf(false)
        private set
    var failed by mutableStateOf(false)
        private set
    private var allowed by mutableStateOf(false)
    private var playback by mutableStateOf(controller.state.value)
    val available: Boolean
        get() =
            allowed && controller.playbackVolume != null && (if (enabled) sessionSongs else songs()).isNotEmpty() &&
                !playback.isLoading && !playback.isReconnecting &&
                !playback.isVideoResolving && !playback.sleepTimerState.isActive && !playback.sleepTimerState.stopAfterCurrentSong

    private var normalPlayback: PlaybackUiState? = null
    private var normalGeneration = 0L
    private var normalCommandGeneration: Long? = null
    private var pausedPositionMs: Long? = null
    private var job: Job? = null
    private var player: WrappedAudioPlayer? = null
    private var sessionSongs: List<SongItem> = emptyList()
    private var songIndex = 0
    private var sessionId = 0L

    internal fun setAllowed(value: Boolean) {
        allowed = value
        if (!value && enabled) stop(resume = false)
    }

    internal fun onPlaybackChanged(value: PlaybackUiState) {
        playback = value
        if (enabled && (!available || (pausedPositionMs != null && !ownsPausedPlayback()))) stop(resume = false)
    }

    internal fun onPlaybackCommand() {
        if (enabled && normalCommandGeneration?.let { it != controller.playbackCommandGeneration } == true) stop(resume = false)
    }

    internal fun updateVolume() {
        controller.playbackVolume?.value?.let { player?.setVolume(it) }
    }

    fun toggle() {
        if (enabled) {
            stop()
            return
        }
        if (!available) return
        failed = false
        val session = ++sessionId
        sessionSongs = songs().toList()
        enabled = true
        loading = true
        normalPlayback = controller.state.value
        normalGeneration = controller.playbackReplacementGeneration
        songIndex = 0
        job =
            scope.launch {
                try {
                    if (normalPlayback?.isPlaying == true) {
                        controller.togglePlayPause()
                    }
                    normalCommandGeneration = controller.playbackCommandGeneration
                    if (normalPlayback?.isPlaying == true) {
                        check(withTimeoutOrNull(3_000) { controller.state.first { !it.isPlaying } } != null)
                    }
                    if (!enabled || session != sessionId) return@launch
                    pausedPositionMs = controller.currentPositionMs()
                    if (!ownsPausedPlayback()) {
                        stop(resume = false)
                        return@launch
                    }
                    player =
                        createWrappedAudioPlayer(
                            onEnded = { scope.launch { if (enabled && session == sessionId) playNext() } },
                            onFailure = { scope.launch { if (session == sessionId) fail() } },
                            onInterrupted = { scope.launch { if (session == sessionId) stop(resume = false) } },
                            volume = checkNotNull(controller.playbackVolume).value,
                        )
                    playCurrent(session)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    if (session == sessionId) fail()
                }
            }
    }

    private suspend fun playCurrent(session: Long) {
        if (!enabled || session != sessionId) return
        loading = true
        val current = checkNotNull(player)
        current.play(sessionSongs[songIndex])
        if (enabled && session == sessionId && player === current) loading = false
    }

    private fun playNext() {
        val session = sessionId
        job?.cancel()
        songIndex = (songIndex + 1) % sessionSongs.size
        job =
            scope.launch {
                try {
                    playCurrent(session)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    if (session == sessionId) fail()
                }
            }
    }

    private fun fail() {
        if (!enabled) return
        failed = true
        stop()
    }

    private fun ownsPausedPlayback(): Boolean {
        val saved = normalPlayback ?: return false
        val current = controller.state.value
        return controller.playbackReplacementGeneration == normalGeneration &&
            normalCommandGeneration == controller.playbackCommandGeneration && !current.isPlaying &&
            current.currentSong == saved.currentSong && current.queue == saved.queue &&
            current.currentQueueIndex == saved.currentQueueIndex &&
            pausedPositionMs?.let { abs(controller.currentPositionMs() - it) <= 1_000L } != false
    }

    internal fun stop(resume: Boolean = true) {
        if (!enabled) return
        val resumeNormalPlayback = resume && normalPlayback?.isPlaying == true && ownsPausedPlayback()
        sessionId++
        enabled = false
        loading = false
        job?.cancel()
        job = null
        runCatching { player?.close() }.onFailure { failed = true }
        player = null
        sessionSongs = emptyList()
        normalPlayback = null
        normalCommandGeneration = null
        pausedPositionMs = null
        // The normal player stays loaded, preserving duplicate queue entries, radio state and shuffle order.
        if (resumeNormalPlayback) controller.togglePlayPause()
    }
}

internal interface WrappedAudioPlayer {
    suspend fun play(song: SongItem)

    fun setVolume(volume: Float)

    fun close()
}

internal expect fun createWrappedAudioPlayer(
    onEnded: () -> Unit,
    onFailure: () -> Unit,
    onInterrupted: () -> Unit,
    volume: Float,
): WrappedAudioPlayer
