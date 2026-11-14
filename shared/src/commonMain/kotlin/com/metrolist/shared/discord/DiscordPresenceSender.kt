package com.metrolist.shared.discord

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.TimeSource

internal class DiscordPresenceSender(
    scope: CoroutineScope,
    private val send: suspend (String) -> Boolean,
    private val nowMs: () -> Long = monotonicClock(),
) {
    private class Request(
        val frame: String,
        val onSuccess: () -> Unit,
        val onFailure: () -> Unit,
    )

    private data class State(
        val ready: Boolean = false,
        val request: Request? = null,
        val connectionGeneration: Long = 0,
    )

    private val state = MutableStateFlow(State())
    private val worker =
        scope.launch {
            var nextSendAt = 0L
            while (isActive) {
                state.first { it.ready && it.request != null }
                delay((nextSendAt - nowMs()).coerceAtLeast(0L))
                val attempt = state.value
                val request = attempt.request
                if (!attempt.ready || request == null) continue
                val success = send(request.frame)
                // Leave margin under Discord's five presence updates per twenty seconds, including retries.
                nextSendAt = nowMs() + 5_000L
                if (success) {
                    if (state.compareAndSet(attempt, attempt.copy(request = null))) request.onSuccess()
                } else if (state.value == attempt) {
                    request.onFailure()
                }
            }
        }

    fun submit(
        frame: String,
        onSuccess: () -> Unit,
        onFailure: () -> Unit,
    ) {
        state.update { it.copy(request = Request(frame, onSuccess, onFailure)) }
    }

    fun setReady(ready: Boolean) {
        state.update { it.copy(ready = ready, connectionGeneration = it.connectionGeneration + 1) }
    }

    fun reset() {
        state.update { State(connectionGeneration = it.connectionGeneration + 1) }
    }

    fun stop() {
        reset()
        worker.cancel()
    }
}

private fun monotonicClock(): () -> Long {
    val start = TimeSource.Monotonic.markNow()
    return { start.elapsedNow().inWholeMilliseconds }
}
