package com.metrolist.shared.playback

fun interpolatePlaybackPosition(
    sampledPositionMs: Long,
    elapsedRealtimeMs: Long,
    playbackSpeed: Float,
    durationMs: Long,
): Long {
    val sampled = sampledPositionMs.coerceAtLeast(0L)
    if (durationMs <= 0L) return sampled
    val boundedSample = sampled.coerceAtMost(durationMs)
    val elapsed = elapsedRealtimeMs.coerceAtLeast(0L)
    val speed = playbackSpeed.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f
    val increment = (elapsed.toDouble() * speed).toLong()
    val remaining = durationMs - boundedSample
    return if (increment >= remaining) durationMs else boundedSample + increment
}
