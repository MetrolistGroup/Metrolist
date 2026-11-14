package com.metrolist.shared.api.artwork

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged

class CanvasPlaybackWatchdog {
    private var lastFrame = 0L
    private var waitingSinceMs: Long? = null

    fun reset() {
        waitingSinceMs = null
    }

    suspend fun observe(
        active: Flow<Boolean>,
        timeMs: () -> Long,
        renderedFrames: () -> Long,
        onStalled: () -> Unit,
    ) {
        active.distinctUntilChanged().collectLatest { expectingFrames ->
            reset()
            if (!expectingFrames) return@collectLatest
            while (true) {
                if (isStalled(timeMs(), renderedFrames(), true)) {
                    onStalled()
                    break
                }
                delay(1_000)
            }
        }
    }

    fun isStalled(
        nowMs: Long,
        renderedFrames: Long,
        expectingFrames: Boolean,
    ): Boolean {
        if (!expectingFrames || renderedFrames != lastFrame) {
            lastFrame = renderedFrames
            waitingSinceMs = if (expectingFrames) nowMs else null
            return false
        }
        val since = waitingSinceMs ?: nowMs.also { waitingSinceMs = it }
        return nowMs - since >= 15_000L
    }
}
