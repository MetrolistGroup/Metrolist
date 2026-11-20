package com.metrolist.android.playback

import androidx.media3.common.Player
import com.metrolist.shared.playback.PauseOnMuteController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceMutePlaybackTest {
    @Test
    fun muteWhileBufferingPausesARequestedLocalTrackUntilUnmuted() {
        val mute = PauseOnMuteController()
        var playbackRequested = true
        var pauses = 0
        var resumes = 0

        fun volumeChanged(muted: Boolean) {
            mute.onVolumeChanged(
                muted = muted,
                enabled = true,
                playing = playbackActiveForDeviceMute(false, playbackRequested, Player.STATE_BUFFERING, hasSong = true) { true },
                pause = {
                    playbackRequested = false
                    pauses++
                },
                resume = {
                    playbackRequested = true
                    resumes++
                },
            )
        }

        volumeChanged(false)
        volumeChanged(true)
        volumeChanged(true)
        assertFalse(playbackRequested)
        assertEquals(1, pauses)
        assertTrue(mute.isPausedByMute.value)
        volumeChanged(false)
        volumeChanged(false)
        assertTrue(playbackRequested)
        assertEquals(1, resumes)
    }

    @Test
    fun bufferingOnlyCountsForAnExistingRequestedLocalTrack() {
        assertFalse(playbackActiveForDeviceMute(false, false, Player.STATE_BUFFERING, true) { true })
        assertFalse(playbackActiveForDeviceMute(false, true, Player.STATE_BUFFERING, false) { true })
        assertFalse(playbackActiveForDeviceMute(false, true, Player.STATE_BUFFERING, true) { false })
        assertFalse(playbackActiveForDeviceMute(false, true, Player.STATE_READY, true) { true })
        assertFalse(playbackActiveForDeviceMute(false, true, Player.STATE_ENDED, true) { true })
        assertTrue(playbackActiveForDeviceMute(true, true, Player.STATE_READY, true) { false })
    }
}
