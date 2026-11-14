package com.metrolist.desktop.state

import com.metrolist.desktop.state.playback.setPlaying
import com.metrolist.shared.playback.MusicPlayer
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopPlaybackIntentTest {
    @Test
    fun explicitPauseClearsPlaybackIntentBeforeAudioIsReady() {
        val player = MusicPlayer()
        try {
            player.resume()
            assertTrue(player.wantsPlayback)
            assertFalse(player.isPlaying.value)

            repeat(2) {
                player.setPlaying(false)
                assertFalse(player.wantsPlayback)
                assertFalse(player.isPlaying.value)
            }

            player.setPlaying(true)
            assertTrue(player.wantsPlayback)
            assertFalse(player.isPlaying.value)
            player.setPlaying(false)
            assertFalse(player.wantsPlayback)
        } finally {
            player.release()
        }
    }
}
