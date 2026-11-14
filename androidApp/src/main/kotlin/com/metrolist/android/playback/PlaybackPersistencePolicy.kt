package com.metrolist.android.playback

import com.metrolist.android.models.PersistPlayerState
import kotlinx.coroutines.Job
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

internal fun shouldPersistPlaybackState(
    previous: PersistPlayerState?,
    current: PersistPlayerState?,
): Boolean {
    if (previous == null || current == null) return previous != current
    return (!current.playWhenReady && current.currentPosition != previous.currentPosition) ||
        abs(current.currentPosition - previous.currentPosition) >= 5_000 ||
        current.copy(currentPosition = previous.currentPosition) != previous
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
