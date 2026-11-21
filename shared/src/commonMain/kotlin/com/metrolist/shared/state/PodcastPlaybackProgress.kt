package com.metrolist.shared.state

import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.isPodcast
import com.metrolist.shared.playback.PLAYBACK_CHECKPOINT_INTERVAL_MS
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlin.time.TimeSource

/** Called on the player thread; ordered writes may outlive the playback service. */
class PodcastPlaybackProgress(
    private val writerScope: CoroutineScope,
    private val save: suspend (SongItem, Long, Long, Boolean) -> Unit,
    private val onError: (Exception) -> Unit,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    private data class Checkpoint(
        val song: SongItem,
        val position: Long,
        val duration: Long,
    )

    private class WriteAttempt(
        val checkpoint: Checkpoint,
    ) {
        val failed = MutableStateFlow(false)
    }

    private var current: Checkpoint? = null
    private var lastAttempt: WriteAttempt? = null
    private var lastWrite = timeSource.markNow()
    private var pendingWrite: Job? = null
    private var wasPlaying = false
    private var savedMetadata: Pair<SongItem, Long>? = null

    fun update(
        song: SongItem?,
        positionMs: Long,
        durationMs: Long,
        playing: Boolean,
    ) {
        if (current?.song?.id != song?.id) {
            flush()
            current = null
            lastAttempt = null
        }
        val previous = current
        current = song?.takeIf { it.isPodcast }?.let { Checkpoint(it, positionMs.coerceAtLeast(0L), durationMs) }
        val checkpoint = current
        val intervalElapsed = lastWrite.elapsedNow().inWholeMilliseconds >= PLAYBACK_CHECKPOINT_INTERVAL_MS
        val retryFailedCheckpoint = lastAttempt?.let { it.checkpoint == checkpoint && it.failed.value } == true
        if (checkpoint != null &&
            (checkpoint.position > 0L || (previous?.position ?: 0L) > 0L || retryFailedCheckpoint) &&
            (!playing || !wasPlaying || intervalElapsed) &&
            (checkpoint != lastAttempt?.checkpoint || intervalElapsed)
        ) {
            flush()
        }
        wasPlaying = playing
    }

    fun flush(force: Boolean = false): Job? {
        val checkpoint = current ?: return pendingWrite
        val previousAttempt = lastAttempt
        if (
            (checkpoint == previousAttempt?.checkpoint && !previousAttempt.failed.value) ||
            (!force && checkpoint.position == 0L && previousAttempt == null)
        ) {
            return pendingWrite
        }
        val attempt = WriteAttempt(checkpoint)
        lastAttempt = attempt
        lastWrite = timeSource.markNow()
        val previousWrite = pendingWrite
        pendingWrite =
            writerScope.launch {
                previousWrite?.join()
                try {
                    val metadata = checkpoint.song to checkpoint.duration
                    save(checkpoint.song, checkpoint.position, checkpoint.duration, metadata != savedMetadata)
                    savedMetadata = metadata
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    attempt.failed.value = true
                    onError(error)
                }
            }
        return pendingWrite
    }
}
