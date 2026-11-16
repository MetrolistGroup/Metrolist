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
    ): Boolean {
        if (completedGeneration != generation) return false
        seekInFlight = false
        if (!isForeground || !finished) return false
        positionSynchronized = true
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
