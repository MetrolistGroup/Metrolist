package com.metrolist.android.playback

import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playback.SleepTimerController
import com.metrolist.shared.playback.SleepTimerState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

@OptIn(ExperimentalCoroutinesApi::class)
class MusicSleepTimerPlayerTest {
    @Test
    fun countdownDoesNotWaitForAnotherSongWhenCurrentSongWasDismissedBeforeExpiry() =
        runTest {
            val backend = Backend()
            val controller = controller(backend)
            controller.start(60_000L, fadeOut = true, finishCurrentSong = true)
            runCurrent()
            preparePlayerDismissal(currentRequestId = { 1L }, stop = backend.player::stop).invoke()
            advanceTimeBy(60_000L)
            runCurrent()

            val deadlineState = controller.state.value
            backend.loadTrack("later")
            controller.onEndOfMediaItem()

            assertFalse("A timer with no song at its deadline must not pause a later track", backend.paused)
            assertEquals(SleepTimerState(), deadlineState)
            assertEquals(1f, backend.gain)
        }

    @Test
    fun countdownStillWaitsForARealCurrentTrackEvenWithUnknownDuration() =
        runTest {
            val backend = Backend()
            backend.duration.value = 0L
            val controller = controller(backend)
            controller.start(60_000L, fadeOut = true, finishCurrentSong = true)
            runCurrent()
            advanceTimeBy(60_000L)
            runCurrent()

            assertTrue(controller.state.value.stopAfterCurrentSong)
            assertFalse(backend.paused)
            controller.onEndOfMediaItem()
            assertTrue(backend.paused)
            assertEquals(SleepTimerState(), controller.state.value)
        }

    private fun TestScope.controller(backend: Backend) =
        SleepTimerController(
            scope = backgroundScope,
            player = MusicSleepTimerPlayer(backend.player, pausePlayback = { backend.paused = true }, cancelCrossfade = {}),
            elapsedRealtimeMs = { testScheduler.currentTime },
        )

    private class Backend {
        val current = MutableStateFlow<SongItem?>(SongItem("original", "Original", null, emptyList(), null))
        val position = MutableStateFlow(0L)
        val duration = MutableStateFlow(100_000L)
        var paused = false
        var gain = 1f

        val player =
            Proxy.newProxyInstance(
                AndroidPlaybackPlayer::class.java.classLoader,
                arrayOf(AndroidPlaybackPlayer::class.java),
            ) { _, method, args ->
                when (method.name) {
                    "getCurrentSong" -> {
                        current
                    }

                    "getCurrentPosition" -> {
                        position
                    }

                    "getDuration" -> {
                        duration
                    }

                    "setSleepTimerGain" -> {
                        gain = args!![0] as Float
                    }

                    "stop" -> {
                        current.value = null
                        position.value = 0L
                        duration.value = 0L
                    }

                    else -> {
                        error("Unexpected test call: ${method.name}")
                    }
                }
            } as AndroidPlaybackPlayer

        fun loadTrack(id: String) {
            current.value = SongItem(id, id, null, emptyList(), null)
            duration.value = 100_000L
        }
    }
}
