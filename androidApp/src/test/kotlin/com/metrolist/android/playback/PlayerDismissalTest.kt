package com.metrolist.android.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerDismissalTest {
    @Test
    fun dismissalSurvivesPreparationButCannotStopReplacementOrDisconnectedPlayback() {
        var requestId: Long? = 1L
        var playbackState: String
        val dismissLoading = preparePlayerDismissal({ requestId }) { playbackState = "stopped" }

        playbackState = "playing"
        dismissLoading()
        assertEquals("stopped", playbackState)

        val dismissOriginal = preparePlayerDismissal({ requestId }) { playbackState = "stopped" }
        requestId = 2L
        playbackState = "loading replacement"
        dismissOriginal()
        assertEquals("loading replacement", playbackState)

        val dismissReplacement = preparePlayerDismissal({ requestId }) { playbackState = "stopped" }
        requestId = null
        playbackState = "disconnected"
        dismissReplacement()
        assertEquals("disconnected", playbackState)

        val dismissDisconnected = preparePlayerDismissal({ requestId }) { playbackState = "stopped" }
        dismissDisconnected()
        assertEquals("disconnected", playbackState)
    }
}
