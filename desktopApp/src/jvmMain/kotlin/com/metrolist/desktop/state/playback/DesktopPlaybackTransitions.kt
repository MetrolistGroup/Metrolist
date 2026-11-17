package com.metrolist.desktop.state.playback

import com.metrolist.shared.cast.CastState
import com.metrolist.shared.cast.forQueueEntry
import com.metrolist.shared.playback.MusicQueueState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Owns song transitions and cast resolution. EOF cannot supersede a pending selection. */
internal class DesktopPlaybackTransitions(
    private val scope: CoroutineScope,
) {
    @Volatile
    var generation = 0L
        private set

    @Volatile
    var disconnectGeneration = 0L
        private set

    @Volatile
    private var resolution: Job? = null

    private var handoff = false

    @Volatile private var requestedPlaying: Boolean? = null

    fun setPlaying(playing: Boolean) {
        requestedPlaying = playing
    }

    fun playbackIntent(initial: Boolean): Boolean = if (pending) requestedPlaying ?: initial else initial

    fun observePlaybackIntent(
        before: CastState,
        after: CastState,
    ) {
        if (requestedPlaying == null && before.isConnected && after.isConnected && before.mediaGeneration == after.mediaGeneration &&
            before.isPlaying != after.isPlaying && !after.hasEnded && !after.isBuffering
        ) {
            requestedPlaying = after.isPlaying
        }
    }

    private val _pending = MutableStateFlow(false)
    val pendingState = _pending.asStateFlow()
    val pending: Boolean get() = _pending.value

    var job: Job? = null
        set(value) {
            field = value
            if (value == null) _pending.value = false
            val selectedGeneration = generation
            value?.invokeOnCompletion {
                scope.launch {
                    if (field === value) finish(selectedGeneration)
                }
            }
        }

    fun begin(handoff: Boolean = false): Long {
        this.handoff = handoff
        requestedPlaying = null
        generation++
        job?.cancel()
        job = null
        _pending.value = true
        return generation
    }

    fun finish(selectedGeneration: Long) {
        if (generation == selectedGeneration) _pending.value = false
    }

    fun disconnect(): Boolean {
        disconnectGeneration++
        resolution?.cancel()
        return pending && !handoff
    }

    suspend fun <T> resolve(block: suspend () -> T): T? =
        coroutineScope {
            val request = disconnectGeneration
            val next = async(Dispatchers.IO) { block() }
            resolution = next
            try {
                next.await()
            } catch (error: CancellationException) {
                currentCoroutineContext().ensureActive()
                if (request == disconnectGeneration) throw error
                null
            } finally {
                if (resolution === next) resolution = null
            }
        }

    fun advance(
        remote: CastState,
        queue: MusicQueueState,
        start: () -> Unit,
    ): Boolean {
        if (pending || !remote.hasEnded || remote.forQueueEntry(queue) == null) return false
        start()
        return true
    }
}
