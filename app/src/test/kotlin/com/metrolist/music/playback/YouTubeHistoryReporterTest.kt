package com.metrolist.music.playback

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.SparseArray
import androidx.media3.common.C
import androidx.media3.common.FlagSet
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.analytics.PlaybackStatsListener
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import androidx.media3.exoplayer.source.SinglePeriodTimeline
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import org.robolectric.annotation.LooperMode
import java.lang.reflect.Proxy
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class YouTubeHistoryReporterTest {
    private val fixtures = mutableListOf<Playback>()

    @After
    fun cleanUp() {
        fixtures.forEach { it.reporter.close(); it.scope.cancel() }
    }

    @Test
    fun `sends at the threshold without waiting for song end and does not resend`() = playback().run {
        advance(29_999)
        assertTrue(reports.isEmpty())
        advance(1)
        assertEquals(listOf("song"), reports)
        advance(90_000)
        release()
        assertEquals(listOf("song"), reports)
        assertEquals(1, finished)
    }

    @Test
    fun `close cancels timers but deferred final stats still apply the threshold`() {
        for ((listenedMs, expected) in listOf(10_000L to emptyList(), 30_000L to listOf("song"))) {
            val playback = playback()
            ShadowSystemClock.advanceBy(Duration.ofMillis(listenedMs))
            playback.reporter.close()
            Handler(Looper.getMainLooper()).post { playback.release() }
            advance(60_000)
            assertEquals(expected, playback.reports)
        }
    }

    @Test
    fun `pauses and buffering do not count as listening`() = playback().run {
        advance(10_000)
        playWhenReady = false
        event(AnalyticsListener.EVENT_PLAY_WHEN_READY_CHANGED)
        advance(60_000)
        playWhenReady = true
        state = Player.STATE_BUFFERING
        event(AnalyticsListener.EVENT_PLAYBACK_STATE_CHANGED)
        advance(60_000)
        assertTrue(reports.isEmpty())
        state = Player.STATE_READY
        event(AnalyticsListener.EVENT_PLAYBACK_STATE_CHANGED)
        advance(20_000)
        assertEquals(listOf("song"), reports)
    }

    @Test
    fun `seeking ahead and changing speed do not manufacture listened time`() = playback().run {
        advance(5_000)
        seek(180_000)
        speed = 2f
        event(AnalyticsListener.EVENT_PLAYBACK_PARAMETERS_CHANGED)
        advance(24_999)
        assertTrue(reports.isEmpty())
        advance(1)
        seek(0)
        advance(30_000)
        assertEquals(listOf("song"), reports)
    }

    @Test
    fun `skips stay below threshold and repeats get their own entry`() = playback().run {
        advance(10_000)
        next("second", Player.DISCONTINUITY_REASON_SEEK)
        advance(30_000)
        next("second")
        advance(30_000)
        release()
        assertEquals(listOf("second", "second"), reports)
        assertEquals(2, reportIds.toSet().size)
        assertEquals(1, naturalEnds)
    }

    @Test
    fun `live failure retries once at the end with the same report`() = playback().run {
        result = false
        advance(30_000)
        advance(30_000)
        assertEquals(1, reports.size)
        result = true
        release()
        assertEquals(listOf("song", "song"), reports)
        assertEquals(listOf(1, 1), reportIds)
    }

    @Test
    fun `ending during a request does not send a duplicate`() = playback().run {
        val response = CompletableDeferred<Boolean>().also { gate = it }
        advance(30_000)
        next("second")
        response.complete(true)
        advance(30_000)
        release()
        assertEquals(listOf("song", "second"), reports)
    }

    @Test
    fun `failed in flight request retries once after the session ends`() = playback().run {
        val response = CompletableDeferred<Boolean>().also { gate = it }
        advance(30_000)
        release()
        response.complete(false)
        assertEquals(listOf("song", "song"), reports)
        assertEquals(listOf(1, 1), reportIds)
    }

    @Test
    fun `final retry rechecks the threshold against the full listen time`() {
        for ((threshold, expected) in listOf(90_000L to listOf("song"), 40_000L to listOf("song", "song"))) {
            val playback = playback()
            val gate = CompletableDeferred<Boolean>().also { playback.gate = it }
            advance(30_000)
            playback.threshold = threshold
            playback.reporter.refresh()
            advance(30_000)
            playback.release()
            gate.complete(false)
            assertEquals(expected, playback.reports)
        }
    }

    @Test
    fun `privacy changes and cancellation block an in flight final retry`() {
        for (cancel in listOf(false, true)) {
            val playback = playback()
            val gate = CompletableDeferred<Boolean>().also { playback.gate = it }
            advance(30_000)
            if (cancel) playback.scope.cancel() else playback.enabled = false
            playback.release()
            gate.complete(false)
            assertEquals(listOf("song"), playback.reports)
        }
    }

    @Test
    fun `paused history blocks live reports`() = playback().run {
        enabled = false
        reporter.refresh()
        advance(60_000)
        release()
        assertTrue(reports.isEmpty())
    }

    @Test
    fun `re-enabling history and changing threshold reschedules the live report`() = playback().run {
        enabled = false
        reporter.refresh()
        advance(10_000)
        enabled = true
        threshold = 15_000
        reporter.refresh()
        advance(5_000)
        assertEquals(listOf("song"), reports)
    }

    @Test
    fun `crossfade players track their own listens and release cancels their timers`() {
        val first = playback()
        advance(10_000)
        val second = playback()
        advance(20_000)
        first.release()
        advance(10_000)
        second.release()
        advance(60_000)
        assertEquals(listOf("song"), first.reports)
        assertEquals(listOf("song"), second.reports)
    }

    private fun playback(id: String = "song") = Playback(id).also {
        fixtures += it
        it.event(AnalyticsListener.EVENT_TIMELINE_CHANGED)
    }

    private fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private class Playback(id: String) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var item = MediaItem.Builder().setMediaId(id).setUri("https://example.test/audio").build()
        var timeline = timeline()
        var sequence = 0L
        var position = 0L
        var playWhenReady = true
        var state = Player.STATE_READY
        var speed = 1f
        var enabled = true
        var threshold = 30_000L
        var result = true
        var gate: CompletableDeferred<Boolean>? = null
        var finished = 0
        var naturalEnds = 0
        var created = 0
        val reports = mutableListOf<String>()
        val reportIds = mutableListOf<Int>()
        val player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, _ ->
            when (method.name) {
                "getApplicationLooper" -> Looper.getMainLooper()
                "getCurrentMediaItem" -> item
                "getPlaybackState" -> state
                "getPlayWhenReady" -> playWhenReady
                "isPlaying" -> playWhenReady && state == Player.STATE_READY
                "getPlaybackSuppressionReason" -> Player.PLAYBACK_SUPPRESSION_REASON_NONE
                "getPlayerError" -> null
                "getCurrentTracks" -> Tracks.EMPTY
                "getPlaybackParameters" -> PlaybackParameters(speed)
                else -> error("Unexpected player call: ${method.name}")
            }
        } as Player
        val reporter = YouTubeHistoryReporter(
            player, scope, { threshold }, { enabled },
            createReport = { videoId ->
                val reportId = ++created
                val report: suspend () -> Boolean = {
                    reports += videoId
                    reportIds += reportId
                    gate?.await() ?: result
                }
                report
            },
            onStatsReady = PlaybackStatsListener.Callback { _, stats ->
                finished++
                naturalEnds += stats.endedCount
            },
        )

        private fun timeline() = SinglePeriodTimeline(300_000_000, true, false, false, null, item)

        private fun time(): AnalyticsListener.EventTime {
            val period = MediaPeriodId(timeline.getUidOfPeriod(0), sequence)
            return AnalyticsListener.EventTime(
                SystemClock.elapsedRealtime(), timeline, 0, period, position,
                timeline, 0, period, position, 0,
            )
        }

        private val listeners = listOf(reporter.statsListener, reporter.listener)

        fun event(flag: Int) {
            val events = AnalyticsListener.Events(
                FlagSet.Builder().add(flag).build(), SparseArray<AnalyticsListener.EventTime>().apply { put(flag, time()) },
            )
            listeners.forEach { it.onEvents(player, events) }
        }

        private fun positionInfo() = Player.PositionInfo(
            null, 0, item, timeline.getUidOfPeriod(0), 0, position, position, C.INDEX_UNSET, C.INDEX_UNSET,
        )

        fun seek(target: Long) {
            val old = positionInfo()
            position = target
            listeners.forEach { it.onPositionDiscontinuity(time(), old, positionInfo(), Player.DISCONTINUITY_REASON_SEEK) }
            event(AnalyticsListener.EVENT_POSITION_DISCONTINUITY)
        }

        fun next(id: String, reason: Int = Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
            val old = positionInfo()
            sequence++
            position = 0
            item = item.buildUpon().setMediaId(id).build()
            timeline = timeline()
            listeners.forEach { it.onPositionDiscontinuity(time(), old, positionInfo(), reason) }
            event(AnalyticsListener.EVENT_POSITION_DISCONTINUITY)
        }

        fun release() = event(AnalyticsListener.EVENT_PLAYER_RELEASED)
    }
}
