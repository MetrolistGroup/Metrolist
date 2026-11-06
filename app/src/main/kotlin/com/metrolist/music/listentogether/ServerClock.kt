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
    private val elevatedSamples = ArrayDeque<ClockSample>()

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
        elevatedSamples.clear()
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
        while (elevatedSamples.isNotEmpty() && receivedAt - elevatedSamples.first().timestamp > MAX_SAMPLE_AGE_MS) {
            elevatedSamples.removeFirst()
        }

        val currentSample = ClockSample(networkRoundTrip, sampleOffset, receivedAt)

        if (samples.isNotEmpty()) {
            val currentMinRtt = samples.minOf { it.rtt }
            if (networkRoundTrip > currentMinRtt + GOOD_SAMPLE_MARGIN_MS) {
                elevatedSamples.addLast(currentSample)
                val duration = receivedAt - elevatedSamples.first().timestamp
                if (elevatedSamples.size >= SUSTAINED_RTT_COUNT && duration >= SUSTAINED_RTT_MIN_DURATION_MS) {
                    // Sustained RTT shift over time-separated measurements: reseed window
                    samples.clear()
                    while (elevatedSamples.size > MAX_WINDOW_SIZE) {
                        elevatedSamples.removeFirst()
                    }
                    samples.addAll(elevatedSamples)
                    elevatedSamples.clear()
                } else {
                    // Isolated spike or short-burst queueing stall: discard
                    return false
                }
            } else {
                elevatedSamples.clear()
                if (samples.size >= MAX_WINDOW_SIZE) {
                    samples.removeFirst()
                }
                samples.addLast(currentSample)
            }
        } else {
            elevatedSamples.clear()
            samples.addLast(currentSample)
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
        const val SUSTAINED_RTT_MIN_DURATION_MS = 2_000L
    }
}
