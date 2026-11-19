package com.metrolist.android.utils

internal object LyricsLocalVideoLimits {
    const val MAX_SOURCE_BYTES = 32L * 1024 * 1024
    const val MAX_FRAME_BYTES = 24L * 1024 * 1024
    const val MAX_SOURCE_PIXELS = 1920L * 1080
    const val MAX_SIDE = 320
    const val SAMPLE_FPS = 8
    const val MAX_LOOP_MS = 6_000L
    const val MAX_SOURCE_DURATION_MS = 24L * 60 * 60 * 1000

    fun loopDuration(sourceDurationMs: Long): Long {
        require(sourceDurationMs in 1..MAX_SOURCE_DURATION_MS) { "Invalid video duration" }
        return minOf(sourceDurationMs, MAX_LOOP_MS)
    }

    fun frameCount(loopDurationMs: Long): Int {
        require(loopDurationMs in 1..MAX_LOOP_MS)
        return ((loopDurationMs * SAMPLE_FPS + 999) / 1000).toInt()
    }

    fun sampleTimeUs(index: Int, loopDurationMs: Long): Long {
        require(index in 0 until frameCount(loopDurationMs))
        return index * 1_000_000L / SAMPLE_FPS
    }

    fun frameIndex(positionMs: Long, loopDurationMs: Long): Int {
        require(positionMs >= 0)
        val count = frameCount(loopDurationMs)
        return ((positionMs % loopDurationMs) * SAMPLE_FPS / 1000).toInt().coerceAtMost(count - 1)
    }

    fun addWithinBudget(current: Long, addition: Long, limit: Long): Long {
        require(current >= 0 && addition > 0 && limit >= 0 && current <= limit && addition <= limit - current) {
            "Video exceeds resource limit"
        }
        return current + addition
    }

    fun sourceDimensionsAllowed(width: Int, height: Int): Boolean =
        width > 0 && height > 0 && width.toLong() * height <= MAX_SOURCE_PIXELS
}
