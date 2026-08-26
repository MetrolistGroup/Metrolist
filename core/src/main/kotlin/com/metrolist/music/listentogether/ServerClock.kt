package com.metrolist.music.listentogether

import kotlin.math.max

internal class ServerClock(
    private val elapsedRealtime: () -> Long,
) {
    private var serverOffsetMs: Double? = null
    private var bestRoundTripMs = Long.MAX_VALUE

    @Synchronized
    fun reset() {
        serverOffsetMs = null
        bestRoundTripMs = Long.MAX_VALUE
    }

    @Synchronized
    fun recordPong(
        clientTime: Long,
        serverReceiveTime: Long,
        serverSendTime: Long,
    ): Boolean {
        val receivedAt = elapsedRealtime()
        if (clientTime <= 0L || clientTime > receivedAt || receivedAt - clientTime > MAX_SAMPLE_AGE_MS) return false
        if (serverReceiveTime <= 0L || serverSendTime < serverReceiveTime) return false

        val roundTrip = receivedAt - clientTime
        val serverProcessing = serverSendTime - serverReceiveTime
        val networkRoundTrip = max(0L, roundTrip - serverProcessing)
        val sampleOffset = serverSendTime + networkRoundTrip / 2.0 - receivedAt
        val previousOffset = serverOffsetMs

        if (networkRoundTrip < bestRoundTripMs) bestRoundTripMs = networkRoundTrip
        val weight = if (networkRoundTrip <= bestRoundTripMs + GOOD_SAMPLE_MARGIN_MS) 0.25 else 0.05
        serverOffsetMs = previousOffset?.let { it + weight * (sampleOffset - it) } ?: sampleOffset
        return previousOffset == null
    }

    @Synchronized
    fun now(): Long? = serverOffsetMs?.let { (elapsedRealtime() + it).toLong() }

    fun positionAt(
        position: Long,
        effectiveAtServerTime: Long?,
        isPlaying: Boolean,
    ): Long {
        if (!isPlaying || effectiveAtServerTime == null || effectiveAtServerTime <= 0L) return position
        val serverNow = now() ?: return position
        return position + max(0L, serverNow - effectiveAtServerTime)
    }

    private companion object {
        const val MAX_SAMPLE_AGE_MS = 60_000L
        const val GOOD_SAMPLE_MARGIN_MS = 50L
    }
}
