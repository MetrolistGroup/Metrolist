package com.metrolist.shared.deviceconnect

import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playback.MusicQueueSnapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DeviceConnectRemoteTimingTest {
    @Test
    fun targetPauseStopsTheControllerClockAndSurvivesScalarCommands() =
        runTest {
            val sent = mutableListOf<DcSyncMessage>()
            val initial = snapshot().playback
            val port = DelayedPlaybackPort {}.apply { state = initial }
            val remote =
                DeviceConnectRemote(this, { "target" }, { _, message ->
                    sent += message
                    true
                }, monotonicClockMs = { testScheduler.currentTime + 1L })
            try {
                remote.attach(port)
                remote.onPeerConnected("controller", supportsPlaybackClock = true)
                remote.onMessage("controller", DcSyncMessage(DcSyncMessageType.ATTACH))
                runCurrent()
                sent.clear()
                port.state = port.state.copy(playing = false, positionMs = 1_500L)
                advanceTimeBy(1_001L)
                runCurrent()
                val paused = sent.last { it.type == DcSyncMessageType.UPDATE }
                assertEquals(false, paused.playing)
                assertEquals(1_500L, paused.positionMs)
                advanceTimeBy(3_000L)
                remote.intercept(DcPlaybackCommand(DcPlaybackAction.REPEAT, repeatMode = DcRepeatMode.ONE))
                runCurrent()
                assertFalse(port.state.playing)
                assertEquals(1_500L, port.state.positionMs)
                assertEquals(DcRepeatMode.ONE, port.state.repeatMode)
            } finally {
                remote.close()
            }
        }

    @Test
    fun volumeCommandDoesNotCancelAnInFlightTrackLoad() =
        runTest {
            val loaded = CompletableDeferred<Unit>()
            var loads = 0
            val initial = snapshot().playback
            val port =
                DelayedPlaybackPort {
                    loads++
                    loaded.await()
                }.apply { state = initial }
            val remote = DeviceConnectRemote(this, { "target" }, { _, _ -> true }, monotonicClockMs = { testScheduler.currentTime + 1L })
            try {
                remote.attach(port)
                remote.onPeerConnected("controller")
                remote.onMessage("controller", DcSyncMessage(DcSyncMessageType.ATTACH))
                runCurrent()
                remote.intercept(DcPlaybackCommand(DcPlaybackAction.NEXT))
                runCurrent()
                assertEquals(1, loads)
                remote.intercept(DcPlaybackCommand(DcPlaybackAction.VOLUME, volume = 0.2f))
                runCurrent()
                assertEquals(0.2f, port.state.volume)
                loaded.complete(Unit)
                runCurrent()
                assertEquals(1, loads)
                assertEquals(1, port.state.queue.currentIndex)
                assertEquals(0.2f, port.state.volume)
            } finally {
                remote.close()
            }
        }

    @Test
    fun staleReleaseCannotEndANewerControllerSession() =
        runTest {
            val port = DelayedPlaybackPort { error("A controller must not load media") }
            val remote =
                DeviceConnectRemote(this, { "controller" }, { _, _ -> true }, monotonicClockMs = { testScheduler.currentTime + 1L })
            try {
                remote.attach(port)
                remote.onPeerConnected("target", supportsPlaybackClock = true)
                remote.onMessage("target", DcSyncMessage(DcSyncMessageType.STATE, snapshot = snapshot()))
                runCurrent()
                remote.onMessage("target", DcSyncMessage(DcSyncMessageType.RELEASE, sessionId = "previous", pause = true))
                runCurrent()
                assertTrue(remote.isFollowing)
                assertTrue(port.state.playing)
                remote.onMessage("target", DcSyncMessage(DcSyncMessageType.RELEASE, sessionId = "session"))
                runCurrent()
                assertFalse(remote.isActive)
                assertFalse(port.state.playing)
            } finally {
                remote.close()
            }
        }

    @Test
    fun staleReleaseCannotPauseANewerTargetSession() =
        runTest {
            val initial = snapshot().playback
            val port = DelayedPlaybackPort {}.apply { state = initial }
            val remote = DeviceConnectRemote(this, { "target" }, { _, _ -> true }, monotonicClockMs = { testScheduler.currentTime + 1L })
            try {
                remote.attach(port)
                remote.onPeerConnected("controller")
                remote.onMessage("controller", DcSyncMessage(DcSyncMessageType.ATTACH))
                runCurrent()
                val session = remote.state.value.sessionId
                remote.onMessage("controller", DcSyncMessage(DcSyncMessageType.RELEASE, sessionId = "previous", pause = true))
                runCurrent()
                assertEquals(setOf("controller"), remote.state.value.controllerIds)
                assertTrue(port.state.playing)
                remote.onMessage("controller", DcSyncMessage(DcSyncMessageType.RELEASE, sessionId = session, pause = true))
                runCurrent()
                assertFalse(remote.isActive)
                assertFalse(port.state.playing)
            } finally {
                remote.close()
            }
        }

    @Test
    fun failedReturnHandoffKeepsTheTargetAudible() =
        runTest {
            val initial = snapshot().playback
            val port = DelayedPlaybackPort {}.apply { state = initial }
            val remote =
                DeviceConnectRemote(this, { "target" }, { _, message -> message.type != DcSyncMessageType.TRANSFER }, monotonicClockMs = {
                    testScheduler.currentTime +
                        1L
                })
            try {
                remote.attach(port)
                remote.onPeerConnected("controller")
                remote.onMessage("controller", DcSyncMessage(DcSyncMessageType.ATTACH))
                runCurrent()
                remote.onMessage("controller", DcSyncMessage(DcSyncMessageType.PULL, sessionId = remote.state.value.sessionId))
                runCurrent()
                assertTrue(port.state.playing)
                assertTrue(remote.isActive)
                assertEquals(DcSyncError.UNAVAILABLE, remote.state.value.error)
                remote.onPeerDisconnected("controller", intentional = false)
                runCurrent()
                assertFalse(remote.isActive)
                assertTrue(port.state.playing)
            } finally {
                remote.close()
            }
        }

    @Test
    fun rejectedPullKeepsControllingWithoutStartingLocalAudio() =
        runTest {
            var loads = 0
            val port = DelayedPlaybackPort { loads++ }
            val remote =
                DeviceConnectRemote(this, { "controller" }, { _, _ -> true }, monotonicClockMs = { testScheduler.currentTime + 1L })
            try {
                remote.attach(port)
                remote.onPeerConnected("target", supportsPlaybackClock = true)
                remote.onMessage("target", DcSyncMessage(DcSyncMessageType.STATE, snapshot = snapshot()))
                runCurrent()
                remote.transferTo("controller")
                runCurrent()
                remote.onMessage("target", DcSyncMessage(DcSyncMessageType.ERROR, sessionId = "session", error = DcSyncError.NOT_ALLOWED))
                runCurrent()
                assertTrue(remote.isFollowing)
                assertEquals(DcSyncError.NOT_ALLOWED, remote.state.value.error)
                assertEquals(0, loads)
                remote.transferTo("controller")
                runCurrent()
                remote.onMessage("target", DcSyncMessage(DcSyncMessageType.TRANSFER, snapshot = snapshot()))
                runCurrent()
                assertFalse(remote.isActive)
                assertEquals(1, loads)
                assertTrue(port.state.playing)
            } finally {
                remote.close()
            }
        }

    @Test
    fun unansweredPullKeepsControllingWithoutStartingLocalAudio() =
        runTest {
            var loads = 0
            val port = DelayedPlaybackPort { loads++ }
            val remote =
                DeviceConnectRemote(this, { "controller" }, { _, _ -> true }, transferTimeoutMs = 3_000, monotonicClockMs = {
                    testScheduler.currentTime +
                        1L
                })
            try {
                remote.attach(port)
                remote.onPeerConnected("target", supportsPlaybackClock = true)
                remote.onMessage("target", DcSyncMessage(DcSyncMessageType.STATE, snapshot = snapshot()))
                runCurrent()
                remote.transferTo("controller")
                runCurrent()
                advanceTimeBy(3_001)
                runCurrent()
                assertTrue(remote.isFollowing)
                assertEquals(DcSyncError.UNAVAILABLE, remote.state.value.error)
                assertEquals(0, loads)
            } finally {
                remote.close()
            }
        }

    @Test
    fun unansweredTransferRestoresTheLastAudiblePosition() =
        runTest {
            var loads = 0
            val initial = snapshot().playback
            val port = DelayedPlaybackPort { loads++ }.apply { state = initial }
            val remote =
                DeviceConnectRemote(this, { "controller" }, { _, _ -> true }, transferTimeoutMs = 3_000, monotonicClockMs = {
                    testScheduler.currentTime + 1
                })
            try {
                remote.attach(port)
                remote.onPeerConnected("target", supportsPlaybackClock = true)
                runCurrent()
                remote.transferTo("target")
                runCurrent()
                advanceTimeBy(3_001)
                runCurrent()
                assertFalse(remote.isActive)
                assertTrue(port.state.playing)
                assertEquals(initial.positionMs, port.state.positionMs)
                assertEquals(1, loads)
            } finally {
                remote.close()
            }
        }

    @Test
    fun pausedTargetOnlyUpdatesWhenObservedStateChanges() =
        runTest {
            val sent = mutableListOf<DcSyncMessage>()
            val port = DelayedPlaybackPort {}
            val remote =
                DeviceConnectRemote(this, { "target" }, { _, message ->
                    sent += message
                    true
                }, monotonicClockMs = { testScheduler.currentTime + 1L })
            try {
                remote.attach(port)
                remote.onPeerConnected("controller")
                val initial = snapshot()
                remote.onMessage(
                    "controller",
                    DcSyncMessage(DcSyncMessageType.TRANSFER, snapshot = initial.copy(playback = initial.playback.copy(playing = false))),
                )
                runCurrent()
                sent.clear()
                advanceTimeBy(5_001L)
                runCurrent()
                assertTrue(sent.none { it.type == DcSyncMessageType.UPDATE })
                val pausedReads = port.snapshotReads
                advanceTimeBy(60_000L)
                runCurrent()
                assertEquals(pausedReads, port.snapshotReads)
                port.state = port.state.copy(durationMs = 400_000L)
                remote.localStateChanged()
                runCurrent()
                assertEquals(400_000L, sent.last { it.type == DcSyncMessageType.UPDATE }.durationMs)
                sent.clear()
                assertTrue(remote.intercept(DcPlaybackCommand(DcPlaybackAction.PLAY)))
                runCurrent()
                sent.clear()
                advanceTimeBy(2_000L)
                runCurrent()
                assertTrue(sent.any { it.type == DcSyncMessageType.UPDATE && it.playing == true })
            } finally {
                remote.close()
            }
        }

    @Test
    fun targetDoesNotSeekPastTheIntroAfterSlowTransferOrNextTrackPreparation() =
        runTest {
            var now = 1_000L
            val port = DelayedPlaybackPort { now += 3_000L }
            val remote = DeviceConnectRemote(this, { "target" }, { _, _ -> true }, monotonicClockMs = { now })
            try {
                remote.attach(port)
                remote.onPeerConnected("controller")
                remote.onMessage("controller", DcSyncMessage(DcSyncMessageType.TRANSFER, snapshot = snapshot()))
                assertEquals(1_000L, withTimeout(5_000) { port.seeks.receive() })
                assertTrue(remote.intercept(DcPlaybackCommand(DcPlaybackAction.NEXT)))
                assertEquals(0L, withTimeout(5_000) { port.seeks.receive() })
                assertEquals(1, port.state.queue.currentIndex)
            } finally {
                remote.close()
            }
        }

    @Test
    fun controllerUsesMetadataAndClockWithoutLoadingMedia() =
        runTest {
            val port = DelayedPlaybackPort { error("A controller must not load media") }
            val remote = DeviceConnectRemote(this, { "controller" }, { _, _ -> true }, monotonicClockMs = { testScheduler.currentTime + 1 })
            try {
                remote.attach(port)
                remote.onPeerConnected("target")
                remote.onMessage("target", DcSyncMessage(DcSyncMessageType.STATE, snapshot = snapshot()))
                runCurrent()
                assertEquals(1_000L, port.state.positionMs)
                advanceTimeBy(8_001L)
                runCurrent()
                assertEquals(9_000L, port.state.positionMs)
                assertTrue(remote.isFollowing)
            } finally {
                remote.close()
            }
        }

    private fun snapshot(): DcPlaybackSnapshot {
        val songs = listOf("first", "second").map { SongItem(it, it, null, emptyList(), null, duration = 300) }
        return DcPlaybackSnapshot(
            sessionId = "session",
            revision = 1,
            queueRevision = 1,
            trackRevision = 1,
            playback =
                DcLocalPlaybackState(
                    queue = MusicQueueSnapshot(songs, songs, 0, originalIndices = listOf(0, 1)),
                    positionMs = 1_000L,
                    durationMs = 300_000L,
                    playing = true,
                    ready = true,
                ),
        )
    }

    private class DelayedPlaybackPort(
        private val load: suspend () -> Unit,
    ) : DeviceConnectPlaybackPort {
        var state = DcLocalPlaybackState()
        var snapshotReads = 0
        val seeks = Channel<Long>(Channel.UNLIMITED)

        override suspend fun snapshot(): DcLocalPlaybackState {
            snapshotReads++
            return state
        }

        override suspend fun apply(
            snapshot: DcPlaybackSnapshot,
            positionMs: Long,
            shouldCommit: () -> Boolean,
        ) {
            load()
            if (shouldCommit()) state = snapshot.playback.copy(positionMs = positionMs, volume = state.volume)
        }

        override suspend fun seekTo(positionMs: Long) {
            state = state.copy(positionMs = positionMs)
            seeks.send(positionMs)
        }

        private var controlling = false

        override suspend fun setControllerPlayback(playback: DcLocalPlaybackState?) {
            if (playback != null || controlling) state = playback ?: state.copy(playing = false)
            controlling = playback != null
        }

        override suspend fun setPlaying(playing: Boolean) {
            state = state.copy(playing = playing)
        }

        override suspend fun setSpeed(speed: Float) {
            state = state.copy(speed = speed)
        }

        override suspend fun setVolume(volume: Float) {
            state = state.copy(volume = volume)
        }

        override suspend fun setAudioOutputEnabled(enabled: Boolean) = Unit
    }
}
