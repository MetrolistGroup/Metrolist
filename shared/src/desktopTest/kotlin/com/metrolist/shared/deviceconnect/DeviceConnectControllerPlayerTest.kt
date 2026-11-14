package com.metrolist.shared.deviceconnect

import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playback.MusicPlayer
import com.metrolist.shared.playback.MusicQueueSnapshot
import com.metrolist.shared.settings.Settings
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeviceConnectControllerPlayerTest {
    @Test
    fun desktopControllerPublishesMetadataWithoutNativePlayback() =
        runBlocking {
            Settings.replaceSharedForTest(Settings(MapSettings()))
            val player = MusicPlayer()
            val song = SongItem("remote", "Remote", null, emptyList(), null, duration = 300)
            val state =
                DcLocalPlaybackState(
                    queue = MusicQueueSnapshot(listOf(song), listOf(song), 0, originalIndices = listOf(0)),
                    playing = true,
                    ready = true,
                    durationMs = 300_000,
                    positionMs = 1_000,
                    speed = 1.25f,
                    pitch = 0.8f,
                )
            try {
                player.setControllerPlayback(state)
                assertEquals(1.25f, player.playbackSpeed.value)
                assertEquals(0.8f, player.playbackPitch.value)
                assertEquals(song, player.currentSong.value)
                assertTrue(player.isPlaying.value)
                assertTrue(player.wantsPlayback)
                assertTrue(player.currentPlaybackPositionMs() >= 1_000)
                assertEquals(0L, player.currentPlaybackInstanceId)
                assertNull(player.currentPlaybackResource.value)
                assertFalse(player.playbackReady)
                player.setControllerPlayback(state.copy(ready = false, positionMs = 2_000, pitch = null))
                assertEquals(2_000L, player.currentPlaybackPositionMs())
                assertFalse(player.isPlaying.value)
                assertTrue(player.wantsPlayback)
                player.setControllerPlayback(null)
                assertFalse(player.isPlaying.value)
                assertEquals(1.25f, player.playbackSpeed.value)
                assertEquals(0.8f, player.playbackPitch.value)
                assertEquals(song, player.currentSong.value)
            } finally {
                player.release()
                Settings.replaceSharedForTest(null)
            }
        }
}
