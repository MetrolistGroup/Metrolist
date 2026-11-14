package com.metrolist.shared.youtube.playback

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** Estimates throughput in bits per second. */
object PlaybackBandwidthMeter : SynchronizedObject() {
    private const val DEFAULT_BROADBAND_BPS = 20 * 1_000_000L
    private const val DEFAULT_CELLULAR_BPS = 3 * 1_000_000L
    private const val MIN_SAMPLE_DURATION_MS = 20L
    private const val MIN_SAMPLE_BYTES = 8 * 1024L

    private const val ALPHA = 0.25

    private const val MAX_RECENT_SAMPLES = 64
    private const val MIN_RECENT_TRANSFER_MS = 1_000L

    private var currentEstimateBps: Long = DEFAULT_BROADBAND_BPS
    private var sampleCount: Int = 0
    private val recentSamples = ArrayDeque<RecentSample>()

    private class RecentSample(
        val at: TimeSource.Monotonic.ValueTimeMark,
        val bytes: Long,
        val durationMs: Long,
    )

    val estimatedBandwidthBps: Long
        get() =
            synchronized(this) {
                if (sampleCount == 0) {
                    val networkState = PlaybackNetworkStateStore.current
                    if (networkState.scope.contains("cell", ignoreCase = true) || !networkState.connected) {
                        DEFAULT_CELLULAR_BPS
                    } else {
                        DEFAULT_BROADBAND_BPS
                    }
                } else {
                    currentEstimateBps
                }
            }

    fun recordSample(
        bytes: Long,
        durationMs: Long,
    ) {
        if (bytes < MIN_SAMPLE_BYTES || durationMs < MIN_SAMPLE_DURATION_MS) return

        val instantBps = ((bytes * 8.0 * 1000.0) / durationMs).toLong().coerceAtLeast(100_000L)

        synchronized(this) {
            if (sampleCount == 0) {
                currentEstimateBps = instantBps
            } else {
                currentEstimateBps = ((ALPHA * instantBps) + ((1.0 - ALPHA) * currentEstimateBps)).toLong()
            }
            sampleCount++
            recentSamples.addLast(RecentSample(TimeSource.Monotonic.markNow(), bytes, durationMs))
            if (recentSamples.size > MAX_RECENT_SAMPLES) recentSamples.removeFirst()
        }
    }

    /** Returns null with less than one second of transfer time; unlike EMA, drops stale fast samples. */
    fun recentBandwidthBps(window: Duration = 30.seconds): Long? =
        synchronized(this) {
            val samples = recentSamples.filter { it.at.elapsedNow() <= window }
            val transferMs = samples.sumOf { it.durationMs }
            if (transferMs < MIN_RECENT_TRANSFER_MS) null else samples.sumOf { it.bytes } * 8_000L / transferMs
        }

    fun reset(initialEstimateBps: Long? = null) {
        synchronized(this) {
            sampleCount = if (initialEstimateBps != null) 1 else 0
            recentSamples.clear()
            currentEstimateBps = initialEstimateBps ?: DEFAULT_BROADBAND_BPS
        }
    }
}
