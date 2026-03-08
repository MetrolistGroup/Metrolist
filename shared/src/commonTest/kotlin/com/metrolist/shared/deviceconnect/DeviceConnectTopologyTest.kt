package com.metrolist.shared.deviceconnect

import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playback.MusicQueueSnapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DeviceConnectTopologyTest {
    @Test
    fun targetVolumeIsSharedByAllControllersWithoutReloadingPlayback() =
        runTest {
            val devices = devices()
            val a = devices.getValue("a")
            val b = devices.getValue("b")
            val c = devices.getValue("c")
            b.port.setVolume(0.4f)
            try {
                a.remote.discoverPlayback()
                c.remote.discoverPlayback()
                runCurrent()
                devices.values.forEach { assertEquals(0.4f, it.port.state.volume) }
                val revision = b.remote.trackRevision
                for ((sender, volume) in listOf(a to 0f, c to 0.6f, b to 1f)) {
                    assertTrue(sender.remote.intercept(DcPlaybackCommand(DcPlaybackAction.VOLUME, volume = volume)))
                    runCurrent()
                    devices.values.forEach {
                        assertEquals(volume, it.port.state.volume)
                        assertEquals(0, it.port.mediaLoads)
                        assertTrue(it.port.state.playing)
                    }
                    assertEquals(revision, b.remote.trackRevision)
                    assertTrue(b.port.audioOutputEnabled)
                    assertFalse(a.port.audioOutputEnabled)
                    assertFalse(c.port.audioOutputEnabled)
                }
                b.port.setVolume(0.2f)
                b.remote.localStateChanged()
                runCurrent()
                devices.values.forEach { assertEquals(0.2f, it.port.state.volume) }
                assertTrue(a.remote.intercept(DcPlaybackCommand(DcPlaybackAction.VOLUME, volume = -1f)))
                runCurrent()
                assertEquals(DcSyncError.INVALID_MESSAGE, a.remote.state.value.error)
                devices.values.forEach { assertEquals(0.2f, it.port.state.volume) }
            } finally {
                devices.values.forEach { it.remote.close() }
            }
        }

    @Test
    fun transferPreservesTheReceivingDevicesVolume() =
        runTest {
            val devices = devices()
            val a = devices.getValue("a")
            val b = devices.getValue("b")
            a.port.setVolume(0.3f)
            b.port.setVolume(0.8f)
            try {
                b.remote.transferTo("a")
                runCurrent()
                assertEquals("a", b.remote.state.value.targetId)
                assertEquals(0.3f, a.port.state.volume)
                assertEquals(0.3f, b.port.state.volume)
            } finally {
                devices.values.forEach { it.remote.close() }
            }
        }

    @Test
    fun controllersSharePlaybackThroughTheirPairedTarget() =
        runTest {
            val devices = devices()
            val a = devices.getValue("a")
            val b = devices.getValue("b")
            val c = devices.getValue("c")
            try {
                a.remote.discoverPlayback()
                c.remote.discoverPlayback()
                runCurrent()

                assertEquals(setOf("a", "c"), b.remote.state.value.controllerIds)
                assertTrue(a.remote.intercept(DcPlaybackCommand(DcPlaybackAction.PAUSE)))
                runCurrent()

                devices.values.forEach { device ->
                    assertEquals("b", device.remote.state.value.targetId)
                    assertFalse(device.port.state.playing)
                }
            } finally {
                devices.values.forEach { it.remote.close() }
            }
        }

    @Test
    fun repeatedControllerSkipsKeepPlaybackOnTheAudibleTarget() =
        runTest {
            val devices = devices()
            val controller = devices.getValue("a")
            val target = devices.getValue("b")
            val song =
                target.port.state.queue.displayedItems
                    .single()
            val songs = listOf(song, song.copy(id = "second"), song.copy(id = "third"))
            target.port.state = target.port.state.copy(queue = MusicQueueSnapshot(songs, songs, 0))
            try {
                controller.remote.discoverPlayback()
                runCurrent()
                val session = target.remote.state.value.sessionId
                for (index in 1..songs.lastIndex) {
                    assertTrue(controller.remote.intercept(DcPlaybackCommand(DcPlaybackAction.NEXT)))
                    runCurrent()

                    assertEquals(index, target.port.state.queue.currentIndex)
                    assertEquals(index, controller.port.state.queue.currentIndex)
                    assertTrue(target.port.state.playing)
                    assertTrue(target.port.audioOutputEnabled)
                    assertFalse(controller.port.audioOutputEnabled)
                    assertEquals(session, target.remote.state.value.sessionId)
                    assertEquals("b", controller.remote.state.value.targetId)
                    assertEquals(index, target.port.mediaLoads)
                    assertEquals(0, controller.port.mediaLoads)
                    assertEquals(null, target.remote.state.value.error)
                    assertEquals(null, controller.remote.state.value.error)
                }
            } finally {
                devices.values.forEach { it.remote.close() }
            }
        }

    @Test
    fun transferToExistingControllerReleasesOtherControllersAndCompletesHandoff() =
        runTest {
            val devices = devices()
            val a = devices.getValue("a")
            val b = devices.getValue("b")
            val c = devices.getValue("c")
            try {
                a.remote.discoverPlayback()
                c.remote.discoverPlayback()
                runCurrent()
                assertEquals(setOf("a", "c"), b.remote.state.value.controllerIds)

                b.remote.transferTo("c")
                runCurrent()

                assertFalse(a.remote.isActive)
                assertFalse(a.port.state.playing)
                assertEquals("c", b.remote.state.value.targetId)
                assertEquals(setOf("b"), c.remote.state.value.controllerIds)
                assertEquals(c.remote.state.value.sessionId, b.remote.state.value.sessionId)
                assertTrue(c.port.state.playing)
                advanceTimeBy(10_001L)
                runCurrent()
                assertEquals(null, b.remote.state.value.error)
                assertTrue(b.remote.isFollowing)

                assertTrue(b.remote.intercept(DcPlaybackCommand(DcPlaybackAction.PAUSE)))
                runCurrent()
                assertFalse(c.port.state.playing)
                assertFalse(b.port.state.playing)
            } finally {
                devices.values.forEach { it.remote.close() }
            }
        }

    @Test
    fun defaultDeviceStartsANewQueueWithoutLoadingLocalMedia() =
        runTest {
            val devices = devices(mapOf("a" to "b", "b" to "a"))
            val a = devices.getValue("a")
            val b = devices.getValue("b")
            try {
                runCurrent()
                val songs =
                    listOf(
                        playingState()
                            .queue.displayedItems
                            .single()
                            .copy(id = "new"),
                    )
                assertTrue(a.remote.willStartRemotePlayback)
                assertTrue(a.remote.intercept(DcPlaybackCommand(DcPlaybackAction.REPLACE_QUEUE, songs = songs, index = 0)))
                runCurrent()
                assertEquals(songs, b.port.state.queue.displayedItems)
                assertEquals("b", a.remote.state.value.targetId)
                assertTrue(b.port.state.playing)
                assertEquals(0, a.port.mediaLoads)
                assertEquals(1, b.port.mediaLoads)
                assertFalse(a.port.audioOutputEnabled)
                assertTrue(b.port.audioOutputEnabled)
            } finally {
                devices.values.forEach { it.remote.close() }
            }
        }

    @Test
    fun unavailableDefaultAndLocalFilesKeepTheNormalPlayerPath() =
        runTest {
            val devices = devices(mapOf("a" to "missing", "c" to "b"))
            try {
                runCurrent()
                val a = devices.getValue("a")
                a.port.state = playingState().copy(playing = false)
                assertFalse(a.remote.willStartRemotePlayback)
                assertFalse(a.remote.intercept(DcPlaybackCommand(DcPlaybackAction.PLAY)))
                val local =
                    playingState()
                        .queue.displayedItems
                        .single()
                        .copy(id = "local_song")
                assertFalse(
                    devices
                        .getValue(
                            "c",
                        ).remote
                        .intercept(DcPlaybackCommand(DcPlaybackAction.REPLACE_QUEUE, songs = listOf(local), index = 0)),
                )
                val localQueue = MusicQueueSnapshot(listOf(local), listOf(local), 0)
                val c = devices.getValue("c")
                assertFalse(c.remote.intercept(DcPlaybackCommand(DcPlaybackAction.PLAY), expectedQueue = localQueue))
                assertFalse(c.remote.intercept(DcPlaybackCommand(DcPlaybackAction.SELECT, index = 0), expectedQueue = localQueue))
                assertFalse(a.remote.isActive)
            } finally {
                devices.values.forEach { it.remote.close() }
            }
        }

    @Test
    fun resumingUsesDefaultButAnExistingSessionKeepsItsTarget() =
        runTest {
            val devices = devices(mapOf("a" to "b", "b" to "c"))
            val a = devices.getValue("a")
            val b = devices.getValue("b")
            try {
                a.port.state = playingState().copy(playing = false, positionMs = 4_000)
                runCurrent()
                assertTrue(a.remote.intercept(DcPlaybackCommand(DcPlaybackAction.PLAY)))
                runCurrent()
                assertEquals(4_000L, b.port.state.positionMs)
                assertTrue(b.port.state.playing)
                assertTrue(b.remote.intercept(DcPlaybackCommand(DcPlaybackAction.PAUSE)))
                runCurrent()
                assertTrue(b.remote.intercept(DcPlaybackCommand(DcPlaybackAction.PLAY)))
                runCurrent()
                assertEquals("b", b.remote.state.value.targetId)
                assertFalse(devices.getValue("c").remote.isActive)
            } finally {
                devices.values.forEach { it.remote.close() }
            }
        }

    @Test
    fun defaultDisconnectAfterInterceptionStillStartsTheRequestedQueueLocally() =
        runTest {
            val devices = devices(mapOf("a" to "b"))
            val a = devices.getValue("a")
            try {
                runCurrent()
                a.remote.onPeerDisconnected("b", intentional = false)
                val songs = playingState().queue.displayedItems
                assertTrue(a.remote.intercept(DcPlaybackCommand(DcPlaybackAction.REPLACE_QUEUE, songs = songs, index = 0)))
                runCurrent()
                assertEquals(songs, a.port.state.queue.displayedItems)
                assertTrue(a.port.state.playing)
                assertEquals(1, a.port.mediaLoads)
                assertFalse(a.remote.isActive)
            } finally {
                devices.values.forEach { it.remote.close() }
            }
        }

    @Test
    fun otherPlaybackOwnersPreventDefaultRouting() =
        runTest {
            val devices = devices(mapOf("a" to "b"))
            val a = devices.getValue("a")
            try {
                a.port.state = playingState().copy(playing = false)
                runCurrent()
                a.remote.transferBlocked = { true }
                assertFalse(a.remote.intercept(DcPlaybackCommand(DcPlaybackAction.PLAY)))
                a.remote.transferBlocked = { false }
                a.port.canStartDefaultPlayback = false
                assertFalse(a.remote.willStartRemotePlayback)
                assertFalse(a.remote.intercept(DcPlaybackCommand(DcPlaybackAction.PLAY)))
            } finally {
                devices.values.forEach { it.remote.close() }
            }
        }

    @Test
    fun pauseQueuedDuringDefaultStartupIsDeliveredToTheTarget() =
        runTest {
            val devices = devices(mapOf("a" to "b"))
            val a = devices.getValue("a")
            try {
                runCurrent()
                val songs = playingState().queue.displayedItems
                assertTrue(a.remote.intercept(DcPlaybackCommand(DcPlaybackAction.REPLACE_QUEUE, songs = songs, index = 0)))
                assertTrue(a.remote.intercept(DcPlaybackCommand(DcPlaybackAction.PAUSE)))
                runCurrent()
                assertFalse(
                    devices
                        .getValue("b")
                        .port.state.playing,
                )
                assertEquals("b", a.remote.state.value.targetId)
                assertEquals(0, a.port.mediaLoads)
            } finally {
                devices.values.forEach { it.remote.close() }
            }
        }

    @Test
    fun failedDefaultTransferRestoresTheRequestedQueueLocally() =
        runTest {
            val devices = devices(mapOf("a" to "b"), rejectTransfers = true)
            val a = devices.getValue("a")
            try {
                runCurrent()
                val songs =
                    listOf(
                        playingState()
                            .queue.displayedItems
                            .single()
                            .copy(id = "new"),
                    )
                assertTrue(a.remote.intercept(DcPlaybackCommand(DcPlaybackAction.REPLACE_QUEUE, songs = songs, index = 0)))
                runCurrent()
                assertFalse(a.remote.isActive)
                assertEquals(songs, a.port.state.queue.displayedItems)
                assertTrue(a.port.state.playing)
                assertTrue(a.port.audioOutputEnabled)
                assertEquals(1, a.port.mediaLoads)
            } finally {
                devices.values.forEach { it.remote.close() }
            }
        }

    private fun TestScope.devices(
        defaults: Map<String, String> = emptyMap(),
        rejectTransfers: Boolean = false,
    ): Map<String, TestDevice> {
        val devices = mutableMapOf<String, TestDevice>()
        val links = mapOf("a" to setOf("b"), "b" to setOf("a", "c"), "c" to setOf("b"))
        links.forEach { (id, peers) ->
            val port = PlaybackPort(if (id == "b") playingState() else DcLocalPlaybackState())
            val remote =
                DeviceConnectRemote(this, { id }, { peer, message ->
                    if (peer in peers && !(rejectTransfers && message.type == DcSyncMessageType.TRANSFER)) {
                        devices.getValue(peer).remote.onMessage(id, DcJson.decodeFromString(DcJson.encodeToString(message)))
                        true
                    } else {
                        false
                    }
                }, monotonicClockMs = { testScheduler.currentTime + 1L }, defaultDeviceId = { defaults[id] })
            devices[id] = TestDevice(remote, port)
            remote.attach(port)
            peers.forEach { remote.onPeerConnected(it, supportsPlaybackClock = true) }
        }
        return devices
    }

    private fun playingState(): DcLocalPlaybackState {
        val song = SongItem("song", "Song", null, emptyList(), null, duration = 300)
        val songs = listOf(song)
        return DcLocalPlaybackState(
            queue = MusicQueueSnapshot(songs, songs, 0, originalIndices = listOf(0)),
            playing = true,
            ready = true,
            durationMs = 300_000L,
        )
    }

    private class TestDevice(
        val remote: DeviceConnectRemote,
        val port: PlaybackPort,
    )

    private class PlaybackPort(
        var state: DcLocalPlaybackState,
    ) : DeviceConnectPlaybackPort {
        override var canStartDefaultPlayback = true
        private var controlling = false
        var audioOutputEnabled = true
            private set
        var mediaLoads = 0
            private set

        override suspend fun snapshot(): DcLocalPlaybackState = state

        override suspend fun apply(
            snapshot: DcPlaybackSnapshot,
            positionMs: Long,
            shouldCommit: () -> Boolean,
        ) {
            if (shouldCommit()) {
                mediaLoads++
                state = snapshot.playback.copy(positionMs = positionMs, ready = true)
            }
        }

        override suspend fun setControllerPlayback(playback: DcLocalPlaybackState?) {
            if (playback != null || controlling) state = playback ?: state.copy(playing = false)
            controlling = playback != null
        }

        override suspend fun setPlaying(playing: Boolean) {
            state = state.copy(playing = playing)
        }

        override suspend fun seekTo(positionMs: Long) {
            state = state.copy(positionMs = positionMs)
        }

        override suspend fun setSpeed(speed: Float) {
            state = state.copy(speed = speed)
        }

        override suspend fun setVolume(volume: Float) {
            state = state.copy(volume = volume)
        }

        override suspend fun setAudioOutputEnabled(enabled: Boolean) {
            audioOutputEnabled = enabled
        }
    }
}
