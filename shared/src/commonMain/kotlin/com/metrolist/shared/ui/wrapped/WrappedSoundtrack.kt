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
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.time.TimeSource

@Composable
fun rememberWrappedSoundtrack(
    controller: PlaybackController,
    songs: List<SongItem>,
    chapter: Int,
    cycleExcerpts: Boolean,
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
    LaunchedEffect(soundtrack, chapter, cycleExcerpts) { soundtrack.selectChapter(chapter, cycleExcerpts) }
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
    private var incoming: WrappedAudioPlayer? = null
    private var library: WrappedAudioLibrary? = null
    private var prepared: List<Deferred<String>> = emptyList()
    private var transitionJob: Job? = null
    private var excerptJob: Job? = null
    private var chapter = 0
    private var cycleExcerpts = false
    private var activeIndex = -1
    private var selectionId = 0L
    private var voiceId = 0L
    private var activeVoice = 0L
    private var pendingVoice = 0L
    private var outgoingGain = 1f
    private var incomingGain = 0f
    var playingSong by mutableStateOf<SongItem?>(null)
        private set
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
        controller.playbackVolume?.value?.let {
            player?.setVolume(it * outgoingGain)
            incoming?.setVolume(it * incomingGain)
        }
    }

    internal fun selectChapter(
        value: Int,
        cycleExcerpts: Boolean,
    ) {
        chapter = value
        this.cycleExcerpts = cycleExcerpts
        if (enabled && prepared.isNotEmpty() && !cycleExcerpts) {
            val index = value % sessionSongs.size
            // Keep the current song until the chapter's own song has downloaded, so music never stops for a swipe.
            if (prepared[index].isCompleted && !prepared[index].isCancelled) changeSong(index)
        }
    }

    fun toggle() {
        if (enabled) {
            stop()
            return
        }
        if (!available) return
        failed = false
        val session = ++sessionId
        sessionSongs = songs().distinctBy { it.id }.take(10)
        enabled = true
        loading = true
        songIndex = if (cycleExcerpts) 0 else chapter % sessionSongs.size
        job =
            scope.launch {
                try {
                    val files =
                        createWrappedAudioLibrary {
                            scope.launch { if (session == sessionId) stop(resume = false) }
                        }
                    library = files
                    supervisorScope {
                        // Normal playback keeps going while the opening song downloads alone, so the switch is immediate.
                        val first = async { files.prepare(sessionSongs[songIndex]) }
                        first.join()
                        if (!enabled || session != sessionId) return@supervisorScope
                        if (first.isCancelled) {
                            fail()
                            return@supervisorScope
                        }
                        normalPlayback = controller.state.value
                        normalGeneration = controller.playbackReplacementGeneration
                        if (normalPlayback?.isPlaying == true) {
                            controller.togglePlayPause()
                        }
                        normalCommandGeneration = controller.playbackCommandGeneration
                        if (normalPlayback?.isPlaying == true) {
                            check(withTimeoutOrNull(3_000) { controller.state.first { !it.isPlaying } } != null)
                        }
                        if (!enabled || session != sessionId) return@supervisorScope
                        pausedPositionMs = controller.currentPositionMs()
                        if (!ownsPausedPlayback()) {
                            stop(resume = false)
                            return@supervisorScope
                        }
                        val slots = Semaphore(2)
                        prepared =
                            sessionSongs.indices.map { index ->
                                if (index == songIndex) {
                                    first
                                } else {
                                    async { slots.withPermit { files.prepare(sessionSongs[index]) } }
                                }
                            }
                        changeSong(songIndex)
                        prepared.forEach { pending ->
                            try {
                                pending.await()
                            } catch (error: CancellationException) {
                                throw error
                            } catch (_: Exception) {
                                if (session == sessionId) failed = true
                            }
                        }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    if (session == sessionId) fail()
                }
            }
    }

    private fun changeSong(
        index: Int,
        restart: Boolean = false,
    ) {
        if (!enabled || prepared.isEmpty() || (!restart && index == activeIndex && transitionJob?.isActive != true)) return
        val session = sessionId
        val selection = ++selectionId
        songIndex = index
        pendingVoice = 0L
        incoming?.close()
        incoming = null
        transitionJob?.cancel()
        outgoingGain = 1f
        updateVolume()
        excerptJob?.cancel()
        transitionJob =
            scope.launch {
                var next: WrappedAudioPlayer? = null
                try {
                    loading = true
                    val path = prepared[index].await()
                    if (!enabled || session != sessionId || selection != selectionId) return@launch
                    val voice = ++voiceId
                    pendingVoice = voice
                    val current =
                        createWrappedAudioPlayer(
                            onEnded = {
                                scope.launch {
                                    if (session == sessionId && activeVoice == voice && songIndex == index &&
                                        incoming == null && transitionJob?.isActive != true
                                    ) {
                                        playNext()
                                    }
                                }
                            },
                            onFailure = {
                                scope.launch {
                                    if (session == sessionId &&
                                        (activeVoice == voice || pendingVoice == voice)
                                    ) {
                                        fail()
                                    }
                                }
                            },
                            onInterrupted = {
                                scope.launch {
                                    if (session == sessionId &&
                                        (activeVoice == voice || pendingVoice == voice)
                                    ) {
                                        stop(resume = false)
                                    }
                                }
                            },
                            volume = 0f,
                        )
                    next = current
                    incoming = current
                    incomingGain = 0f
                    outgoingGain = 1f
                    updateVolume()
                    current.prepare(path)
                    current.play()
                    playingSong = sessionSongs[index]
                    loading = false
                    val previous = player
                    if (previous != null) {
                        val start = TimeSource.Monotonic.markNow()
                        do {
                            val progress = (start.elapsedNow().inWholeMilliseconds / 800f).coerceIn(0f, 1f)
                            outgoingGain = cos(progress * PI.toFloat() / 2f)
                            incomingGain = sin(progress * PI.toFloat() / 2f)
                            updateVolume()
                            if (progress == 1f) break
                            delay(20)
                        } while (true)
                        previous.close()
                    }
                    player = current
                    incoming = null
                    next = null
                    activeIndex = index
                    activeVoice = voice
                    pendingVoice = 0L
                    outgoingGain = 1f
                    incomingGain = 0f
                    updateVolume()
                    if (cycleExcerpts) {
                        excerptJob =
                            scope.launch {
                                delay(15_000)
                                if (session == sessionId) playNext()
                            }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    if (session == sessionId) fail()
                } finally {
                    next?.close()
                    if (session == sessionId && selection == selectionId) {
                        if (incoming === next) incoming = null
                        pendingVoice = 0L
                        outgoingGain = 1f
                        updateVolume()
                    }
                }
            }
    }

    private fun playNext() {
        if (sessionSongs.isNotEmpty()) changeSong((songIndex + 1) % sessionSongs.size, restart = true)
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
        transitionJob?.cancel()
        transitionJob = null
        excerptJob?.cancel()
        excerptJob = null
        prepared.forEach { it.cancel() }
        prepared = emptyList()
        incoming?.close()
        incoming = null
        runCatching { player?.close() }.onFailure { failed = true }
        player = null
        library?.close()
        library = null
        activeIndex = -1
        activeVoice = 0L
        pendingVoice = 0L
        playingSong = null
        sessionSongs = emptyList()
        normalPlayback = null
        normalCommandGeneration = null
        pausedPositionMs = null
        // The normal player stays loaded, preserving duplicate queue entries, radio state and shuffle order.
        if (resumeNormalPlayback) controller.togglePlayPause()
    }
}

internal interface WrappedAudioPlayer {
    suspend fun prepare(path: String)

    fun play()

    fun setVolume(volume: Float)

    fun close()
}

internal expect fun createWrappedAudioPlayer(
    onEnded: () -> Unit,
    onFailure: () -> Unit,
    onInterrupted: () -> Unit,
    volume: Float,
): WrappedAudioPlayer
