package com.metrolist.music.playback

import android.os.Handler
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.analytics.PlaybackStats
import androidx.media3.exoplayer.analytics.PlaybackStatsListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal class YouTubeHistoryReporter(
    private val player: Player,
    private val scope: CoroutineScope,
    private val thresholdMs: () -> Long,
    private val isEnabled: () -> Boolean,
    private val createReport: (String) -> suspend () -> Boolean,
    onStatsReady: PlaybackStatsListener.Callback,
) {
    private class Listen(
        val videoId: String,
        val startedAtMs: Long,
        val report: suspend () -> Boolean,
    ) {
        var playTimeMs = 0L
        var attempts = 0
        var inFlight = false
        var sent = false
        var finished = false
    }

    private val handler = Handler(player.applicationLooper)
    private val wakeUp = Runnable { refresh() }
    private var current: Listen? = null
    private var closed = false
    val statsListener = PlaybackStatsListener(false) { eventTime, playbackStats ->
        val videoId = eventTime.timeline.getWindow(eventTime.windowIndex, Timeline.Window()).mediaItem.mediaId
        if (playbackStats.totalPlayTimeMs > 0) {
            val listen = listenFor(videoId, playbackStats)
            listen.finished = true
            submit(listen)
            if (current === listen) current = null
        }
        onStatsReady.onPlaybackStatsReady(eventTime, playbackStats)
    }

    val listener: AnalyticsListener = object : AnalyticsListener {
        override fun onEvents(player: Player, events: AnalyticsListener.Events) {
            if (events.contains(AnalyticsListener.EVENT_PLAYER_RELEASED)) close() else refresh()
        }
    }

    fun refresh() {
        handler.removeCallbacks(wakeUp)
        if (closed) return
        val playbackStats = statsListener.playbackStats ?: return
        val videoId = player.currentMediaItem?.mediaId ?: return
        val listen = listenFor(videoId, playbackStats)
        current = listen
        submit(listen)
        if (player.isPlaying && isEnabled() && listen.attempts == 0) {
            // Wake only at the threshold; Media3 excludes pauses, buffering, and seeks.
            handler.postDelayed(wakeUp, (thresholdMs() - playbackStats.totalPlayTimeMs).coerceAtLeast(1L))
        }
    }

    fun close() {
        closed = true
        handler.removeCallbacks(wakeUp)
    }

    private fun listenFor(videoId: String, playbackStats: PlaybackStats): Listen {
        val listen = current?.takeIf { it.videoId == videoId && it.startedAtMs == playbackStats.firstReportedTimeMs }
            ?: Listen(videoId, playbackStats.firstReportedTimeMs, createReport(videoId))
        listen.playTimeMs = playbackStats.totalPlayTimeMs
        return listen
    }

    private fun submit(listen: Listen) {
        if (!isEnabled() || listen.playTimeMs <= 0 || listen.playTimeMs < thresholdMs()) return
        if (listen.sent || listen.inFlight || listen.attempts >= (if (listen.finished) 2 else 1)) return
        listen.attempts = if (listen.finished) 2 else 1
        listen.inFlight = true
        scope.launch {
            try {
                listen.sent = listen.report()
            } finally {
                listen.inFlight = false
            }
            // A failed live attempt gets one final retry using the same playback nonce.
            if (!listen.sent && listen.finished) submit(listen)
        }
    }
}
