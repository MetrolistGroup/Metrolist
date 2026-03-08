/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.desktop.state

import com.metrolist.desktop.state.playback.DesktopCrossfadeClock
import com.metrolist.desktop.state.playback.DesktopQueueTransitions
import com.metrolist.desktop.state.playback.DesktopVideoLifecycle
import com.metrolist.desktop.state.playback.DesktopVideoPlaybackStatus
import com.metrolist.desktop.state.playback.desktopRemainingOutputMs
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playback.MusicPlayer
import com.metrolist.shared.playback.MusicQueue
import com.metrolist.shared.settings.Settings
import com.russhwolf.settings.PropertiesSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import java.util.Properties
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class DesktopCrossfadeProgressTest {
    @Before
    fun isolateSettings() {
        Settings.replaceSharedForTest(Settings(PropertiesSettings(Properties())).apply { crossfadeEnabled = true })
    }

    @After
    fun restoreSettings() {
        Settings.replaceSharedForTest(null)
    }

    @Test
    fun queueEditsCancelPreparationAndStopAfterCurrentSuppressesNewWork() =
        runBlocking {
            val player = MusicPlayer()
            val outgoing = SongItem("out", "Out", null, emptyList(), null)
            val incoming = outgoing.copy(id = "in")
            val inserted = outgoing.copy(id = "inserted")
            val queue = MusicQueue().apply { setQueue(listOf(outgoing, incoming)) }
            val video =
                DesktopVideoLifecycle(
                    this,
                    player,
                    queue,
                    status = { DesktopVideoPlaybackStatus(outgoing, 0, { false }, false, 1f, true, videoVisible = true) },
                    onUnavailable = {},
                    fetchSource = { _, _ -> null },
                )
            val started = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            val fetched = CopyOnWriteArrayList<String>()
            val completedFetches = Channel<Unit>(Channel.UNLIMITED)
            var allowed = true
            val transitions =
                DesktopQueueTransitions(
                    this,
                    player,
                    queue,
                    video,
                    fetch = { song ->
                        fetched += song.id
                        if (song.id == incoming.id) {
                            started.complete(Unit)
                            try {
                                awaitCancellation()
                            } finally {
                                cancelled.complete(Unit)
                            }
                        }
                        completedFetches.send(Unit)
                        null
                    },
                    onSongChanged = { _, _, _ -> },
                    onUnconsumedEof = {},
                    episodeResumePosition = { _, _ -> 0L },
                    allowed = { allowed },
                )
            try {
                transitions.nearEnd(incoming, gapless = true)
                withTimeout(5_000L) { started.await() }
                queue.playNext(inserted)
                transitions.queueChanged()
                withTimeout(5_000L) { cancelled.await() }
                transitions.nearEnd(inserted, gapless = true)
                withTimeout(5_000L) { completedFetches.receive() }
                assertEquals(listOf("in", "inserted"), fetched)
                allowed = false
                transitions.queueChanged()
                transitions.nearEnd(inserted, gapless = false)
                assertFalse(transitions.onEof(player.currentPlaybackInstanceId))
                assertEquals(2, fetched.size)
            } finally {
                transitions.cancel()
                video.close()
                player.release()
            }
        }

    @Test
    fun fadeDurationUsesOutputTimeAcrossSpeedChangesAndPauses() {
        assertEquals(5_000L, desktopRemainingOutputMs(10_000L, 20_000L, 2f))
        assertEquals(5_000L, desktopRemainingOutputMs(10_000L, 12_500L, 0.5f))
        val clock = DesktopCrossfadeClock(10_000L, 5_000L)
        assertEquals(0.2f, clock.sample(12_000L, 2f, playing = true, ended = false))
        assertEquals(0.2f, clock.sample(12_000L, 2f, playing = false, ended = false))
        assertEquals(0.4f, clock.sample(12_500L, 0.5f, playing = true, ended = false))
        assertEquals(1f, clock.sample(14_000L, 0.5f, playing = true, ended = false))
        assertEquals(1f, clock.sample(14_000L, 0.5f, playing = false, ended = true))
    }

    @Test
    fun promotionKeepsElapsedOutputTimeAcrossSpeedChanges() {
        val clock = DesktopCrossfadeClock(10_000L, 5_000L)
        assertEquals(0.6f, clock.sample(16_000L, 2f, playing = true, ended = false))
        clock.rebase(0L)
        assertEquals(0.6f, clock.sample(0L, 2f, playing = false, ended = false))
        assertEquals(0.8f, clock.sample(500L, 0.5f, playing = true, ended = false))
        assertEquals(1f, clock.sample(2_500L, 2f, playing = true, ended = false))
    }

    @Test
    fun failedCrossfadeIsAttemptedOnceUntilTheTargetOccurrenceChanges() =
        runBlocking {
            val player = MusicPlayer()
            val outgoing = SongItem("out", "Outgoing", null, emptyList(), null)
            val incoming = SongItem("in", "Incoming", null, emptyList(), null)
            val queue = MusicQueue().apply { setQueue(listOf(outgoing, incoming), 0) }
            val video =
                DesktopVideoLifecycle(
                    this,
                    player,
                    queue,
                    status = { DesktopVideoPlaybackStatus(outgoing, 0, { true }, false, 1f, true, videoVisible = true) },
                    onUnavailable = {},
                    fetchSource = { _, _ -> null },
                )
            var fetches = 0
            val completedFetches = Channel<Unit>(Channel.UNLIMITED)
            val transitions =
                DesktopQueueTransitions(
                    this,
                    player,
                    queue,
                    video,
                    fetch = {
                        fetches++
                        completedFetches.send(Unit)
                        null
                    },
                    onSongChanged = { _, _, _ -> },
                    onUnconsumedEof = {},
                    episodeResumePosition = { _, _ -> 0L },
                )
            try {
                transitions.nearEnd(incoming, gapless = false)
                withTimeout(5_000) { completedFetches.receive() }
                repeat(100) { transitions.nearEnd(incoming, gapless = false) }
                assertEquals(1, fetches)
                queue.setQueue(listOf(outgoing, incoming), 0)
                transitions.nearEnd(incoming, gapless = false)
                withTimeout(5_000) { completedFetches.receive() }
                assertEquals(2, fetches)
            } finally {
                transitions.cancel()
                video.close()
                player.release()
            }
        }
}
