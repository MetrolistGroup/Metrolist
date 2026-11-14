package com.metrolist.shared.listentogether.network

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.max

class ServerClock(
    private val elapsedRealtime: () -> Long,
) {
    private data class State(
        val offsetMs: Double? = null,
        val bestRoundTripMs: Long = Long.MAX_VALUE,
        val sampleCount: Int = 0,
    )

    private val state = MutableStateFlow(State())

    fun recordSample(
        clientTime: Long,
        serverReceiveTime: Long,
        serverSendTime: Long,
    ): Boolean {
        val receivedAt = elapsedRealtime()
        if (clientTime !in 0L..receivedAt || receivedAt - clientTime > MAX_SAMPLE_AGE_MS) return false
        if (serverReceiveTime !in 1..serverSendTime) return false

        val roundTrip = max(0L, (receivedAt - clientTime) - (serverSendTime - serverReceiveTime))
        if (roundTrip > MAX_ROUND_TRIP_MS) return false
        val currentState = state.value
        if (currentState.bestRoundTripMs != Long.MAX_VALUE && roundTrip > currentState.bestRoundTripMs + MAX_RTT_INCREASE_MS) {
            return false
        }
        val sampleOffset = serverSendTime + roundTrip / 2.0 - receivedAt
        var firstSample = false
        state.update { current ->
            firstSample = current.offsetMs == null
            val weight = if (roundTrip <= current.bestRoundTripMs + GOOD_SAMPLE_MARGIN_MS) 0.25 else 0.05
            State(
                offsetMs = current.offsetMs?.let { it + (sampleOffset - it) * weight } ?: sampleOffset,
                bestRoundTripMs = minOf(current.bestRoundTripMs, roundTrip),
                sampleCount = current.sampleCount + 1,
            )
        }
        return firstSample
    }

    fun serverTimeNow(): Long? = state.value.offsetMs?.let { (elapsedRealtime() + it).toLong() }

    fun sampleCount(): Int = state.value.sampleCount

    fun positionAt(
        position: Long,
        effectiveAtServerTime: Long?,
        isPlaying: Boolean,
        duration: Long? = null,
    ): Long {
        if (!isPlaying || effectiveAtServerTime == null || effectiveAtServerTime <= 0L) return clamp(position, duration)
        val serverNow = serverTimeNow() ?: return clamp(position, duration)
        return clamp(position + max(0L, serverNow - effectiveAtServerTime), duration)
    }

    fun reset() {
        state.value = State()
    }

    private fun clamp(
        position: Long,
        duration: Long?,
    ): Long = position.coerceIn(0L, duration?.takeIf { it > 0L } ?: Long.MAX_VALUE)

    private companion object {
        const val MAX_SAMPLE_AGE_MS = 5_000L
        const val MAX_ROUND_TRIP_MS = 2_000L
        const val MAX_RTT_INCREASE_MS = 500L
        const val GOOD_SAMPLE_MARGIN_MS = 50L
    }
}
