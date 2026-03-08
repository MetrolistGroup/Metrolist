package com.metrolist.shared.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PauseOnMuteControllerTest {
    @Test
    fun startingMutedAndRepeatedVolumeNotificationsDoNotPausePlayback() {
        val playback = Playback(muted = true)
        playback.play()
        repeat(3) { playback.volumeChanged(muted = true) }
        assertTrue(playback.playing)
        assertEquals(0, playback.pauses)

        playback.volumeChanged(muted = false)
        assertTrue(playback.playing)
        assertEquals(0, playback.resumes)
        playback.volumeChanged(muted = true)
        assertFalse(playback.playing)
        assertEquals(1, playback.pauses)
    }

    @Test
    fun manualPlayAfterAutoPauseKeepsPlayingUntilAnotherMuteTransition() {
        val playback = Playback(muted = false)
        playback.play()
        playback.volumeChanged(muted = true)
        assertFalse(playback.playing)
        assertTrue(playback.controller.isPausedByMute.value)
        playback.play()
        repeat(3) { playback.volumeChanged(muted = true) }
        assertTrue(playback.playing)
        assertFalse(playback.controller.isPausedByMute.value)
        assertEquals(1, playback.pauses)
        playback.volumeChanged(muted = false)
        assertTrue(playback.playing)
        assertEquals(0, playback.resumes)
        playback.volumeChanged(muted = true)
        assertFalse(playback.playing)
        assertEquals(2, playback.pauses)
    }

    @Test
    fun unmutingResumesOnlyPlaybackPausedByMute() {
        val playback = Playback(muted = false)
        playback.play()
        playback.volumeChanged(muted = true)
        repeat(3) { playback.volumeChanged(muted = true) }
        assertEquals(1, playback.pauses)
        assertTrue(playback.controller.isPausedByMute.value)
        playback.volumeChanged(muted = false)
        playback.volumeChanged(muted = false)
        assertTrue(playback.playing)
        assertEquals(1, playback.resumes)

        playback.pause()
        playback.volumeChanged(muted = true)
        playback.volumeChanged(muted = false)
        assertFalse(playback.playing)
        assertEquals(1, playback.resumes)
    }

    @Test
    fun manualPauseCancelsAutomaticResume() {
        val playback = Playback(muted = false)
        playback.play()
        playback.volumeChanged(muted = true)
        playback.pause()
        playback.volumeChanged(muted = false)
        assertFalse(playback.playing)
        assertEquals(0, playback.resumes)
    }

    @Test
    fun disabledSettingNeverPausesAndEnablingWhileMutedDoesNotPause() {
        val playback = Playback(muted = false)
        playback.enabled = false
        playback.play()
        playback.volumeChanged(muted = true)
        playback.volumeChanged(muted = false)
        playback.volumeChanged(muted = true)
        assertTrue(playback.playing)
        assertEquals(0, playback.pauses)
        playback.enabled = true
        playback.volumeChanged(muted = true)
        assertTrue(playback.playing)
        playback.volumeChanged(muted = false)
        playback.volumeChanged(muted = true)
        assertFalse(playback.playing)
        assertEquals(1, playback.pauses)
    }

    @Test
    fun disablingSettingAfterAutoPauseCancelsResume() {
        val playback = Playback(muted = false)
        playback.play()
        playback.volumeChanged(muted = true)
        playback.enabled = false
        playback.volumeChanged(muted = true)
        playback.volumeChanged(muted = false)
        assertFalse(playback.playing)
        assertEquals(0, playback.resumes)
    }

    @Test
    fun restartingObservationEstablishesABaselineWithoutPausing() {
        val playback = Playback(muted = false)
        playback.play()
        playback.controller.reset(muted = true)
        playback.volumeChanged(muted = true)
        assertTrue(playback.playing)
        playback.controller.reset()
        playback.volumeChanged(muted = true)
        assertTrue(playback.playing)
        assertEquals(0, playback.pauses)
        playback.volumeChanged(muted = false)
        playback.volumeChanged(muted = true)
        assertFalse(playback.playing)
    }

    private class Playback(
        muted: Boolean,
    ) {
        val controller = PauseOnMuteController()
        var playing = false
        var enabled = true
        var pauses = 0
        var resumes = 0

        init {
            volumeChanged(muted)
        }

        fun play() {
            controller.cancelResume()
            playing = true
        }

        fun pause() {
            controller.cancelResume()
            playing = false
        }

        fun volumeChanged(muted: Boolean) {
            controller.onVolumeChanged(
                muted = muted,
                enabled = enabled,
                playing = playing,
                pause = {
                    pauses++
                    pause()
                },
                resume = {
                    resumes++
                    play()
                },
            )
        }
    }
}
