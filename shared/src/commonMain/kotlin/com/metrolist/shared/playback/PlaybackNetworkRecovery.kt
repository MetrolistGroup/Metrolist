package com.metrolist.shared.playback

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.TimeSource

object PlaybackNetworkRecovery {
    fun handles(category: PlaybackFailureCategory): Boolean =
        category == PlaybackFailureCategory.NETWORK || category == PlaybackFailureCategory.TIMEOUT

    fun handles(
        failure: PlaybackFailure,
        preparationCategory: PlaybackFailureCategory,
    ): Boolean = failure.isMediaSourceFailure && (handles(failure.category) || handles(preparationCategory))

    suspend fun recover(
        isCurrent: () -> Boolean,
        connected: () -> Boolean,
        wantsPlayback: () -> Boolean,
        positionMs: () -> Long,
        onReconnecting: (Boolean) -> Unit,
        stateChanges: Flow<Unit>,
        timeSource: TimeSource = TimeSource.Monotonic,
        retry: suspend () -> Boolean,
    ): Boolean =
        coroutineScope {
            val clock = timeSource.markNow()
            var previousTime = 0L
            var previousPosition = positionMs()
            var stalledMs = 0L
            var nextAttemptMs = 0L
            var backoffMs = 1_000L
            var attempt: Deferred<Boolean>? = null
            var recovered = false
            val revision = MutableStateFlow(0L)
            val observer = launch(start = CoroutineStart.UNDISPATCHED) { stateChanges.collect { revision.value++ } }
            var pausedAttemptRevision = -1L
            try {
                while (true) {
                    val now = clock.elapsedNow().inWholeMilliseconds
                    if (attempt?.isCompleted == true) {
                        if (attempt.await()) {
                            recovered = true
                            break
                        }
                        attempt = null
                        nextAttemptMs = now + backoffMs
                        backoffMs = (backoffMs * 2).coerceAtMost(10_000L)
                    }
                    if (!isCurrent()) break
                    if (!wantsPlayback()) {
                        stalledMs = 0L
                        onReconnecting(false)
                        if (attempt != null) {
                            if (attempt.await()) {
                                recovered = true
                                break
                            }
                            attempt = null
                            pausedAttemptRevision = revision.value
                        }
                        val pausedRevision = revision.value
                        if (!connected() || pausedAttemptRevision == pausedRevision) {
                            revision.first { it != pausedRevision }
                            previousTime = clock.elapsedNow().inWholeMilliseconds
                            previousPosition = positionMs()
                            nextAttemptMs = 0L
                            continue
                        }
                        pausedAttemptRevision = pausedRevision
                    }
                    val position = positionMs()
                    stalledMs = if (!wantsPlayback() || position != previousPosition) 0L else stalledMs + (now - previousTime)
                    previousTime = now
                    previousPosition = position
                    onReconnecting(stalledMs >= 1_000L)
                    if (stalledMs >= 120_000L) break
                    if (attempt == null && connected() && now >= nextAttemptMs) {
                        attempt =
                            async {
                                try {
                                    withTimeoutOrNull(30_000L) { retry() } == true
                                } catch (cancelled: CancellationException) {
                                    currentCoroutineContext().ensureActive()
                                    false
                                }
                            }
                    }
                    delay(250L)
                }
                recovered
            } finally {
                attempt?.cancel()
                observer.cancel()
                onReconnecting(false)
            }
        }
}
