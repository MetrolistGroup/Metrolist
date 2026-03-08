package com.metrolist.shared.playback

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SleepTimerControllerTest {
    @Test
    fun lateSongFadeStartsAtFullGainAndUsesRemainingDuration() =
        runTest {
            val player = FakeSleepTimerPlayer(durationMs = 120_000, currentPositionMs = 105_000)
            val controller = SleepTimerController(backgroundScope, player)
            controller.stopAfterCurrentSong(fadeOut = true)
            runCurrent()
            assertEquals(1f, player.gain)
            player.playbackProgress.value = 112_500L to 120_000L
            runCurrent()
            assertEquals(0.5f, player.gain)
            player.playbackProgress.value = 120_000L to 120_000L
            runCurrent()
            assertEquals(0f, player.gain)
        }

    @Test
    fun unobservedTimerWaitsUntilFadeAndPausedTrackDoesNotRepeatGainWrites() =
        runTest {
            val player = FakeSleepTimerPlayer(durationMs = 120_000, currentPositionMs = 30_000)
            var clockReads = 0
            val controller =
                SleepTimerController(backgroundScope, player, {
                    clockReads++
                    testScheduler.currentTime
                })
            controller.start(3_600_000, fadeOut = true)
            runCurrent()
            val reads = clockReads
            advanceTimeBy(3_539_999)
            runCurrent()
            assertEquals(reads, clockReads)
            advanceTimeBy(1_001)
            runCurrent()
            assertTrue(player.gain < 1f)
            controller.stopAfterCurrentSong(fadeOut = true)
            runCurrent()
            val writes = player.gainWrites
            advanceTimeBy(3_600_000)
            runCurrent()
            assertEquals(writes, player.gainWrites)
            player.playbackProgress.value = 90_000L to 120_000L
            runCurrent()
            assertEquals(0.5f, player.gain)
            controller.onEndOfMediaItem()
            assertTrue(player.paused)
            assertEquals(1f, player.gain)
        }

    @Test
    fun startTimerPublishesStateAndReplacesPreviousTimer() =
        runTest {
            val player = FakeSleepTimerPlayer()
            val controller = controller(player)

            controller.start(10_000L, fadeOut = true)
            controller.start(20_000L, fadeOut = false)

            assertEquals(SleepTimerState(true, 20_000L, 20_000L, fadeOut = false), controller.state.value)
            assertEquals(1f, player.gain)
        }

    @Test
    fun elapsedTimerPausesAndCompletesAtDeadline() =
        runTest {
            val player = FakeSleepTimerPlayer()
            val controller = controller(player)
            controller.start(10_000L, fadeOut = false)
            runCurrent()
            advanceTimeBy(9_999L)
            runCurrent()
            assertFalse(player.paused)
            advanceTimeBy(1L)
            runCurrent()

            assertTrue(player.paused)
            assertEquals(SleepTimerState(), controller.state.value)
        }

    @Test
    fun stopAfterCurrentSongCancelsCrossfadeAndSetsPauseAtEnd() =
        runTest {
            val player = FakeSleepTimerPlayer(durationMs = 120_000L, currentPositionMs = 90_000L)
            val controller = controller(player)

            controller.stopAfterCurrentSong(fadeOut = true)

            assertTrue(player.crossfadeCancelled)
            assertTrue(player.pauseAtEndOfMediaItems)
            assertEquals(30_000L, controller.state.value.remainingMs)
            assertTrue(controller.state.value.stopAfterCurrentSong)
            assertEquals(1f, player.gain)
        }

    @Test
    fun endOfMediaFinishesStopAfterCurrentSongAndRestoresGain() =
        runTest {
            val player = FakeSleepTimerPlayer(durationMs = 60_000L, currentPositionMs = 1_000L)
            val controller = controller(player)
            controller.stopAfterCurrentSong(fadeOut = true)

            player.gain = 0.25f
            controller.onEndOfMediaItem()

            assertEquals(SleepTimerState(), controller.state.value)
            assertTrue(player.paused)
            assertFalse(player.pauseAtEndOfMediaItems)
            assertEquals(1f, player.gain)
        }

    @Test
    fun cancelClearsTimerAndRestoresPlayerPolicy() =
        runTest {
            val player = FakeSleepTimerPlayer()
            val controller = controller(player)
            controller.stopAfterCurrentSong(fadeOut = true)
            player.gain = 0.5f

            controller.cancel()

            assertEquals(SleepTimerState(), controller.state.value)
            assertFalse(player.pauseAtEndOfMediaItems)
            assertEquals(1f, player.gain)
        }

    @Test
    fun destroyUsesSameCleanupAsCancellation() =
        runTest {
            val player = FakeSleepTimerPlayer()
            val controller = controller(player)
            controller.start(10_000L, fadeOut = true)

            controller.onDestroy()

            assertEquals(SleepTimerState(), controller.state.value)
            assertEquals(1f, player.gain)
        }

    @Test
    fun countdownHandsOffToTheCurrentSongWithoutFadingOrPausingAtExpiry() =
        runTest {
            val player = FakeSleepTimerPlayer(durationMs = 180_000L, currentPositionMs = 60_000L)
            val controller = controller(player)
            controller.start(60_000L, fadeOut = true, finishCurrentSong = true)
            runCurrent()
            advanceTimeBy(59_999L)
            runCurrent()
            assertEquals(1f, player.gain)
            assertFalse(player.pauseAtEndOfMediaItems)
            controller.onEndOfMediaItem()
            assertFalse(player.paused)
            assertTrue(controller.state.value.finishCurrentSong)

            advanceTimeBy(1L)
            runCurrent()
            assertFalse(player.paused)
            assertTrue(player.crossfadeCancelled)
            assertTrue(player.pauseAtEndOfMediaItems)
            assertTrue(controller.state.value.stopAfterCurrentSong)
            assertEquals(120_000L, controller.state.value.remainingMs)
            assertEquals(1f, player.gain)
            player.playbackProgress.value = 150_000L to 180_000L
            runCurrent()
            assertEquals(0.5f, player.gain)
            controller.onEndOfMediaItem()
            assertTrue(player.paused)
            assertEquals(1f, player.gain)
            assertEquals(SleepTimerState(), controller.state.value)
        }

    @Test
    fun expiryUsesTheRemainingPartOfAShortSongForTheFade() =
        runTest {
            val player = FakeSleepTimerPlayer(durationMs = 120_000L, currentPositionMs = 105_000L)
            val controller = controller(player)
            controller.start(10_000L, fadeOut = true, finishCurrentSong = true)
            runCurrent()
            advanceTimeBy(10_000L)
            runCurrent()
            assertEquals(1f, player.gain)
            player.playbackProgress.value = 112_500L to 120_000L
            runCurrent()
            assertEquals(0.5f, player.gain)
            controller.cancel()
            assertEquals(1f, player.gain)
            assertFalse(player.pauseAtEndOfMediaItems)
        }

    @Test
    fun finishSongWithoutFadeHandlesUnknownDurationAndCanBeReplaced() =
        runTest {
            val player = FakeSleepTimerPlayer()
            val controller = controller(player)
            controller.start(1_000L, fadeOut = false, finishCurrentSong = true)
            runCurrent()
            advanceTimeBy(1_000L)
            runCurrent()
            assertTrue(controller.state.value.stopAfterCurrentSong)
            assertEquals(null, controller.state.value.remainingMs)
            assertFalse(player.paused)
            player.playbackProgress.value = 30_000L to 60_000L
            runCurrent()
            assertEquals(1f, player.gain)
            controller.start(10_000L, fadeOut = false)
            controller.onEndOfMediaItem()
            assertFalse(player.paused)
            assertFalse(player.pauseAtEndOfMediaItems)
            advanceTimeBy(10_000L)
            runCurrent()
            assertTrue(player.paused)
        }

    @Test
    fun cancellingCountdownNeverArmsEndOfSong() =
        runTest {
            val player = FakeSleepTimerPlayer()
            val controller = controller(player)
            controller.start(1_000L, fadeOut = true, finishCurrentSong = true)
            runCurrent()
            controller.cancel()
            advanceTimeBy(10_000L)
            runCurrent()
            assertEquals(SleepTimerState(), controller.state.value)
            assertFalse(player.pauseAtEndOfMediaItems)
            assertFalse(player.paused)
            assertFalse(player.crossfadeCancelled)
        }

    private fun TestScope.controller(player: FakeSleepTimerPlayer) =
        SleepTimerController(
            scope = backgroundScope,
            player = player,
            elapsedRealtimeMs = { testScheduler.currentTime },
        )

    private class FakeSleepTimerPlayer(
        override val isAvailable: Boolean = true,
        override val durationMs: Long = 0L,
        override val currentPositionMs: Long = 0L,
    ) : SleepTimerPlayer {
        override val playbackProgress = MutableStateFlow(currentPositionMs to durationMs)
        override var pauseAtEndOfMediaItems = false
        var gain = 1f
        var gainWrites = 0
        var crossfadeCancelled = false
        var paused = false

        override fun pause() {
            paused = true
        }

        override fun cancelCrossfadeTransition() {
            crossfadeCancelled = true
        }

        override fun setSleepTimerGain(gain: Float) {
            gainWrites++
            this.gain = gain
        }
    }
}
