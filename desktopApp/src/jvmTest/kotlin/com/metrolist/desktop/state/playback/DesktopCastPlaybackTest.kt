package com.metrolist.desktop.state.playback

import com.metrolist.shared.cast.CastConnectionStatus
import com.metrolist.shared.cast.CastDevice
import com.metrolist.shared.cast.CastError
import com.metrolist.shared.cast.CastProtocol
import com.metrolist.shared.cast.CastState
import com.metrolist.shared.cast.JvmCastManager
import com.metrolist.shared.model.RepeatMode
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playback.MusicPlayer
import com.metrolist.shared.playback.MusicQueue
import com.metrolist.shared.settings.Settings
import com.russhwolf.settings.PropertiesSettings
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import java.util.Properties
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopCastPlaybackTest {
    @BeforeTest
    fun isolateSettings() {
        Settings.replaceSharedForTest(Settings(PropertiesSettings(Properties())))
    }

    @AfterTest
    fun restoreSettings() {
        Settings.replaceSharedForTest(null)
    }

    @Test
    fun checkpointMapsOnlyTheSameLocalVideoTimeline() {
        val remote = CastState(positionMs = 60_000L, mediaVideoId = "counterpart")
        assertEquals(55_000L, desktopCastCheckpointPosition(remote, "counterpart") { it - 5_000L })
        assertEquals(60_000L, desktopCastCheckpointPosition(remote, "original") { error("different timeline") })
        assertEquals(60_000L, desktopCastCheckpointPosition(remote.copy(mediaVideoId = null), null) { error("unknown timeline") })
    }

    @Test
    fun uncastableNextTrackDisconnectsAndReturnsTheQueueDecisionForLocalPlayback() =
        runTest {
            withPlayback { playback, manager, state, current, next ->
                val decision = playback.advance(current, RepeatMode.OFF) { error("Queue already has a next track") }
                assertEquals(PlaybackQueueDecision(PlaybackQueueAction.PLAY_NEXT, next), decision)
                assertFalse(manager.state.value.isConnected)
                assertEquals(CastError.UNSUPPORTED_STREAM, manager.state.value.error)
                assertFalse(playback.shouldResumeAfterDisconnect(manager.state.value))
                assertTrue(playback.shouldResumeAfterDisconnect(state.value.copy(mediaGeneration = 2L)))
            }
        }

    @Test
    fun supersededCastDoesNotDisconnectOrAdvanceAfterResolutionFails() =
        runTest {
            withPlayback(supersedeDuringFetch = true) { playback, manager, _, current, _ ->
                assertNull(playback.advance(current, RepeatMode.OFF) {})
                assertTrue(manager.state.value.isConnected)
                assertNull(manager.state.value.error)
                assertEquals(2L, manager.state.value.mediaGeneration)
            }
        }

    private suspend fun TestScope.withPlayback(
        supersedeDuringFetch: Boolean = false,
        block: suspend (DesktopCastPlayback, JvmCastManager, MutableStateFlow<CastState>, SongItem, SongItem) -> Unit,
    ) {
        val scope = backgroundScope
        val manager = JvmCastManager(scope, { error("No discovery") }, { emptyList() }, "test", Settings(PropertiesSettings(Properties())))

        @Suppress("UNCHECKED_CAST")
        val state =
            JvmCastManager::class.java
                .getDeclaredField(
                    "_state",
                ).apply { isAccessible = true }
                .get(manager) as MutableStateFlow<CastState>
        state.value =
            CastState(
                status = CastConnectionStatus.CONNECTED,
                device = CastDevice("id", "Receiver", CastProtocol.SONOS, "127.0.0.1", 1400),
                hasEnded = true,
                positionMs = 180_000L,
                mediaGeneration = 1L,
            )
        val current = SongItem("current", "Current", null, emptyList(), null)
        val next = current.copy(id = "next", title = "Next")
        val queue = MusicQueue().apply { setQueue(listOf(current, next)) }
        val player = MusicPlayer()
        val playback =
            DesktopCastPlayback(scope, player, queue, {}, manager) { _, _ ->
                if (supersedeDuringFetch) state.value = state.value.copy(mediaGeneration = 2L)
                null
            }
        try {
            block(playback, manager, state, current, next)
        } finally {
            player.release()
            manager.release()
            scope.cancel()
        }
    }
}
