package com.metrolist.shared.playback

class InstantSilenceDetector(
    private val sampleRate: Int,
) {
    private var silentFrames = 0L
    val isSilent: Boolean get() = silentFrames >= sampleRate * 2L

    fun frame(peak: Int) {
        silentFrames = if (peak < 256) silentFrames + 1 else 0
    }

    fun reset() {
        silentFrames = 0
    }
}

fun instantSilenceSeekTarget(
    positionMs: Long,
    durationMs: Long,
): Long? {
    if (durationMs <= 15_000L) return null
    return minOf(positionMs + 15_000L, durationMs - 500L).takeIf { it > positionMs }
}
