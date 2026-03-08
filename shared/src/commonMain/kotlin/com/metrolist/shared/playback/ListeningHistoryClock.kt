package com.metrolist.shared.playback

class ListeningHistoryClock(
    private val nowMs: () -> Long,
) {
    private var startedAtMs: Long? = null
    private var accumulatedMs = 0L
    private var playing = false

    fun update(
        playing: Boolean,
        historyPaused: Boolean,
    ) {
        this.playing = playing
        val now = nowMs()
        startedAtMs?.let { accumulatedMs += (now - it).coerceAtLeast(0L) }
        startedAtMs = now.takeIf { playing && !historyPaused }
    }

    fun setHistoryPaused(paused: Boolean) = update(playing, paused)

    fun elapsedMs(): Long = accumulatedMs + (startedAtMs?.let { (nowMs() - it).coerceAtLeast(0L) } ?: 0L)

    fun reset() {
        startedAtMs = null
        accumulatedMs = 0L
        playing = false
    }
}
