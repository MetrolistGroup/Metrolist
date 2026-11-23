package com.metrolist.android.playback

import com.metrolist.android.models.PersistPlayerState
import com.metrolist.android.models.PlaybackPersistenceWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

internal suspend fun writePendingPlaybackCheckpoints(
    requests: ReceiveChannel<PlaybackPersistenceWriter.Request>,
    write: (PlaybackPersistenceWriter.Request) -> Unit,
    onFailure: (Exception) -> Unit,
) {
    requests.receiveAsFlow().collectLatest { request ->
        repeat(2) { attempt ->
            try {
                write(request)
                return@collectLatest
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                onFailure(error)
                // A paused position may not emit another checkpoint. A newer request cancels this delay.
                if (attempt == 0) delay(1_000L)
            }
        }
    }
}

internal fun shouldPersistPlaybackState(
    previous: PersistPlayerState?,
    current: PersistPlayerState?,
): Boolean {
    // An empty live queue is not a clear: it may be a transient state during restoration or teardown.
    if (current == null) return false
    if (previous == null) return true
    return (!current.playWhenReady && current.currentPosition != previous.currentPosition) ||
        abs(current.currentPosition - previous.currentPosition) >= 5_000 ||
        current.copy(currentPosition = previous.currentPosition) != previous
}

internal data class PlaybackCheckpointDecision(
    val request: PlaybackPersistenceWriter.Request?,
    val state: PersistPlayerState?,
)

internal fun decidePlaybackCheckpoint(
    writer: PlaybackPersistenceWriter,
    lifecycle: PlaybackPersistenceLifecycle,
    persistentQueue: Boolean,
    previous: PersistPlayerState?,
    state: () -> PersistPlayerState?,
): PlaybackCheckpointDecision {
    if (!lifecycle.canSave(persistentQueue)) return PlaybackCheckpointDecision(null, previous)
    if (!persistentQueue) return PlaybackCheckpointDecision(writer.requestClear(), null)
    val next = state() ?: return PlaybackCheckpointDecision(null, previous)
    if (!shouldPersistPlaybackState(previous, next)) return PlaybackCheckpointDecision(null, previous)
    return PlaybackCheckpointDecision(writer.request(next), next)
}

internal class PlaybackPersistenceLifecycle {
    var restoring = true
        private set

    suspend fun restore(restoreState: suspend () -> Unit) {
        restoreState()
        restoring = false
    }

    fun canSave(persistentQueue: Boolean): Boolean = !restoring || !persistentQueue
}

internal suspend fun awaitFinalPlaybackCheckpoint(checkpoint: Job?) {
    withTimeoutOrNull(3_000L) { checkpoint?.join() }
}
