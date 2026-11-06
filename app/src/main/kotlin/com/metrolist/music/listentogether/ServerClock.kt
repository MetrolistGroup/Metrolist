package com.metrolist.music.listentogether

import kotlin.math.max

/**
 * Tracks remote server wall-clock time relative to local monotonic time.
 * Uses a sliding window of ping/pong samples to select the minimum-RTT measurement,
 * minimizing transit asymmetry errors.
 */
internal class ServerClock(
    private val elapsedRealtime: () -> Long,
) {
    private var serverOffsetMs: Double? = null
    private var bestRoundTripMs = Long.MAX_VALUE
    private val samples = ArrayDeque<ClockSample>()
    private var consecutiveElevatedRttCount = 0

    private data class ClockSample(
        val rtt: Long,
        val offset: Double,
        val timestamp: Long,
    )

    /**
     * Resets clock synchronization state and clears all cached samples.
     */
    @Synchronized
    fun reset() {
        serverOffsetMs = null
        bestRoundTripMs = Long.MAX_VALUE
        samples.clear()
        consecutiveElevatedRttCount = 0
    }

    /**
     * Records a pong response and updates the server offset estimate.
     *
     * @param clientTime Monotonic time when ping was sent.
     * @param serverReceiveTime Server epoch time when ping was received.
     * @param serverSendTime Server epoch time when pong was sent.
     * @return True if this sample established initial clock lock.
     */
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

        // Cristian's algorithm: T_server + (RTT / 2) - T_client_receive
        val sampleOffset = serverSendTime + networkRoundTrip / 2.0 - receivedAt
        val previousOffset = serverOffsetMs

        // Prune expired samples
        while (samples.isNotEmpty() && receivedAt - samples.first().timestamp > MAX_SAMPLE_AGE_MS) {
            samples.removeFirst()
        }

        if (samples.isNotEmpty()) {
            val currentMinRtt = samples.minOf { it.rtt }
            if (networkRoundTrip > currentMinRtt + GOOD_SAMPLE_MARGIN_MS) {
                consecutiveElevatedRttCount++
                if (consecutiveElevatedRttCount >= SUSTAINED_RTT_COUNT) {
                    // Sustained RTT shift: reset window to adapt to the new baseline
                    samples.clear()
                    consecutiveElevatedRttCount = 0
                    samples.addLast(ClockSample(networkRoundTrip, sampleOffset, receivedAt))
                } else {
                    // Isolated spike: discard
                    return false
                }
            } else {
                consecutiveElevatedRttCount = 0
                if (samples.size >= MAX_WINDOW_SIZE) {
                    samples.removeFirst()
                }
                samples.addLast(ClockSample(networkRoundTrip, sampleOffset, receivedAt))
            }
        } else {
            consecutiveElevatedRttCount = 0
            samples.addLast(ClockSample(networkRoundTrip, sampleOffset, receivedAt))
        }

        // Always anchor clock offset to the sample with minimum RTT in the active window
        val bestSample = samples.minWithOrNull(compareBy({ it.rtt }, { -it.timestamp })) ?: return false
        bestRoundTripMs = bestSample.rtt
        serverOffsetMs = bestSample.offset

        return previousOffset == null
    }

    /**
     * Returns the current estimated server epoch timestamp in milliseconds, or null if unsynchronized.
     */
    @Synchronized
    fun now(): Long? = serverOffsetMs?.let { (elapsedRealtime() + it).toLong() }

    /**
     * Projects track position to current time based on effective server time.
     *
     * @param position Base track position in milliseconds.
     * @param effectiveAtServerTime Server timestamp when position was recorded.
     * @param isPlaying Whether playback is actively advancing.
     */
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
        const val MAX_WINDOW_SIZE = 8
        const val GOOD_SAMPLE_MARGIN_MS = 50L
        const val SUSTAINED_RTT_COUNT = 3
    }
}
