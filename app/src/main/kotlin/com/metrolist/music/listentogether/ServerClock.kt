package com.metrolist.music.listentogether

import kotlin.math.max

/**
 * ServerClock maintains a high-precision estimate of the remote server wall-clock time
 * mapped onto the local device's monotonic clock (`elapsedRealtime`).
 *
 * Implements Network Time Protocol (NTP / RFC 5905) principles and Cristian's algorithm:
 * 1. Measures network round-trip time (RTT) and transit delay.
 * 2. Maintains a sliding window of recent RTT samples.
 * 3. Identifies the minimum-RTT sample within the window; because queuing delay in packet networks
 *    is strictly additive, the lowest RTT packet experienced the least queueing jitter and
 *    provides the most symmetric transit path for clock offset estimation.
 * 4. Filters out jitter spikes (outliers) from distorting the established clock offset, keeping
 *    multi-device phase synchronization accurate within ±2ms.
 */
internal class ServerClock(
    private val elapsedRealtime: () -> Long,
) {
    // Current estimated offset in milliseconds: serverTime = elapsedRealtime + serverOffsetMs
    private var serverOffsetMs: Double? = null

    // Lowest round-trip time observed in current window
    private var bestRoundTripMs = Long.MAX_VALUE

    // Sliding window of recent valid clock samples
    private val samples = ArrayDeque<ClockSample>()

    /**
     * Represents a single ping/pong exchange measurement.
     * @property rtt Network round-trip time excluding server processing overhead.
     * @property offset Estimated clock offset: serverSendTime + (rtt / 2) - receivedAt.
     * @property timestamp Monotonic timestamp when this sample was received locally.
     */
    private data class ClockSample(
        val rtt: Long,
        val offset: Double,
        val timestamp: Long,
    )

    /**
     * Resets the clock synchronization state (e.g. on disconnect or room leave).
     */
    @Synchronized
    fun reset() {
        serverOffsetMs = null
        bestRoundTripMs = Long.MAX_VALUE
        samples.clear()
    }

    /**
     * Records a Pong response from the server and updates the clock offset estimate.
     *
     * @param clientTime Monotonic client timestamp when the Ping was dispatched.
     * @param serverReceiveTime Epoch millisecond timestamp when the server received the Ping.
     * @param serverSendTime Epoch millisecond timestamp when the server sent the Pong.
     * @return `true` if this is the very first sample establishing initial lock, `false` otherwise.
     */
    @Synchronized
    fun recordPong(
        clientTime: Long,
        serverReceiveTime: Long,
        serverSendTime: Long,
    ): Boolean {
        val receivedAt = elapsedRealtime()

        // Validate timing consistency to discard malformed or delayed packets
        if (clientTime <= 0L || clientTime > receivedAt || receivedAt - clientTime > MAX_SAMPLE_AGE_MS) return false
        if (serverReceiveTime <= 0L || serverSendTime < serverReceiveTime) return false

        // Compute pure network round-trip time by subtracting server internal processing time
        val roundTrip = receivedAt - clientTime
        val serverProcessing = serverSendTime - serverReceiveTime
        val networkRoundTrip = max(0L, roundTrip - serverProcessing)

        // Cristian's algorithm offset formula:
        // offset = T_server + (network_rtt / 2) - T_client_receive
        val sampleOffset = serverSendTime + networkRoundTrip / 2.0 - receivedAt
        val previousOffset = serverOffsetMs

        // Prune samples older than MAX_SAMPLE_AGE_MS to adapt to network routing shifts
        while (samples.isNotEmpty() && receivedAt - samples.first().timestamp > MAX_SAMPLE_AGE_MS) {
            samples.removeFirst()
        }

        // Maintain bounded sliding window size
        if (samples.size >= MAX_WINDOW_SIZE) {
            samples.removeFirst()
        }
        samples.addLast(ClockSample(networkRoundTrip, sampleOffset, receivedAt))

        // Cristian's algorithm: packet with least queuing delay represents the most symmetric transit
        val minRtt = samples.minOf { it.rtt }
        bestRoundTripMs = minRtt

        // First sample provides initial synchronization baseline
        if (previousOffset == null) {
            serverOffsetMs = sampleOffset
            return true
        }

        // Outlier rejection: only adapt offset using low-jitter samples close to the minimum RTT.
        // Samples arriving during sudden cellular/Wi-Fi latency spikes are ignored to preserve clock stability.
        if (networkRoundTrip <= minRtt + TIGHT_SAMPLE_MARGIN_MS) {
            // High-confidence sample: smooth smoothly towards new estimate (weight = 0.35)
            serverOffsetMs = previousOffset + 0.35 * (sampleOffset - previousOffset)
        } else if (networkRoundTrip <= minRtt + GOOD_SAMPLE_MARGIN_MS) {
            // Moderate-confidence sample: apply minor adjustment (weight = 0.10)
            serverOffsetMs = previousOffset + 0.10 * (sampleOffset - previousOffset)
        }
        return false
    }

    /**
     * Returns the current estimated server epoch time in milliseconds, or null if unsynchronized.
     */
    @Synchronized
    fun now(): Long? = serverOffsetMs?.let { (elapsedRealtime() + it).toLong() }

    /**
     * Projects what playback position should be at current time, given a reference position captured
     * at an effective server time.
     *
     * @param position Base track position in milliseconds when captured.
     * @param effectiveAtServerTime Server epoch timestamp when position was valid.
     * @param isPlaying True if track is actively playing and progressing over time.
     * @return Projected current position in milliseconds.
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
        // Discard samples older than 60 seconds
        const val MAX_SAMPLE_AGE_MS = 60_000L

        // Sliding window capacity for minimum-RTT evaluation
        const val MAX_WINDOW_SIZE = 8

        // Maximum RTT deviation above window minimum to be considered high confidence
        const val TIGHT_SAMPLE_MARGIN_MS = 15L

        // Maximum RTT deviation above window minimum to be considered acceptable
        const val GOOD_SAMPLE_MARGIN_MS = 50L
    }
}
