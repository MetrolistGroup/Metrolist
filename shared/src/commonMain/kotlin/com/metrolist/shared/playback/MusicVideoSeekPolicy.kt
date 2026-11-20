package com.metrolist.shared.playback

internal class MusicVideoSeekLifecycle {
    var isForeground = false
        private set
    var seekInFlight = false
        private set
    var positionSynchronized = false
        private set
    private var generation = 0L

    fun setForeground(foreground: Boolean) {
        if (isForeground == foreground) return
        isForeground = foreground
        invalidateSeek()
    }

    fun markUnsynchronized() {
        positionSynchronized = false
    }

    fun markSynchronized() {
        positionSynchronized = true
    }

    fun beginSeek(): Long? {
        if (!isForeground || seekInFlight) return null
        seekInFlight = true
        positionSynchronized = false
        return ++generation
    }

    fun cancelSeek() {
        invalidateSeek()
    }

    fun completeSeek(
        completedGeneration: Long,
        finished: Boolean,
        synchronized: Boolean = finished,
    ): Boolean {
        if (completedGeneration != generation) return false
        seekInFlight = false
        if (!isForeground || !finished) return false
        positionSynchronized = synchronized
        return true
    }

    fun isCurrentSeek(completedGeneration: Long): Boolean = completedGeneration == generation

    private fun invalidateSeek() {
        generation++
        seekInFlight = false
        positionSynchronized = false
    }
}

internal fun isMaterialMusicVideoTargetChange(
    targetDeltaMs: Long,
    expectedPlaybackAdvanceMs: Long,
    toleranceMs: Long,
): Boolean = kotlin.math.abs(targetDeltaMs - expectedPlaybackAdvanceMs) > toleranceMs

internal class MusicVideoDriftRetryPolicy(
    private val slowSeekThresholdMs: Long,
    private val maximumSlowSeeks: Int,
) {
    private var consecutiveSlowSeeks = 0

    val exhausted: Boolean
        get() = consecutiveSlowSeeks >= maximumSlowSeeks

    fun recordSeek(
        durationMs: Long,
        succeeded: Boolean = true,
    ) {
        consecutiveSlowSeeks =
            when {
                !succeeded -> consecutiveSlowSeeks + 1
                durationMs > slowSeekThresholdMs -> consecutiveSlowSeeks + 1
                else -> 0
            }
    }

    fun shouldRetry(isUserSeek: Boolean): Boolean = isUserSeek || !exhausted

    fun reset() {
        consecutiveSlowSeeks = 0
    }
}
