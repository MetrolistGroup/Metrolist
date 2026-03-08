package com.metrolist.shared.deviceconnect

import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playback.MusicQueueSnapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DeviceConnectClockCompatibilityTest {
    @Test
    fun targetPublishesPitchChangesObservedFromItsPlayer() =
        runTest {
            val port = ClockPort(playback().copy(speed = 1.25f, pitch = 0.75f))
            val sent = mutableListOf<DcSyncMessage>()
            val remote =
                DeviceConnectRemote(this, { "target" }, { _, message ->
                    sent += message
                    true
                }, monotonicClockMs = { testScheduler.currentTime + 1L })
            try {
                remote.attach(port)
                remote.onPeerConnected("controller", supportsPlaybackClock = true, supportsPlaybackPitch = true)
                remote.onMessage("controller", DcSyncMessage(DcSyncMessageType.ATTACH))
                runCurrent()
                sent.clear()
                port.state = port.state.copy(pitch = 1.1f)
                remote.localStateChanged()
                runCurrent()
                val update = sent.single()
                assertEquals(DcSyncMessageType.UPDATE, update.type)
                assertEquals(1.1f, update.pitch)
                assertEquals(1.25f, update.speed)
            } finally {
                remote.close()
            }
        }

    @Test
    fun controllerRejectsInvalidPitchInFullAndScalarState() =
        runTest {
            val port = ClockPort()
            val sent = mutableListOf<DcSyncMessage>()
            val remote =
                DeviceConnectRemote(this, { "controller" }, { _, message ->
                    sent += message
                    true
                }, monotonicClockMs = { testScheduler.currentTime + 1L })
            try {
                remote.attach(port)
                remote.onPeerConnected("target", supportsPlaybackClock = true, supportsPlaybackPitch = true)
                val initial = snapshot().copy(playback = playback().copy(speed = 1.25f, pitch = 0.8f))
                remote.onMessage("target", DcSyncMessage(DcSyncMessageType.STATE, snapshot = initial))
                runCurrent()
                for (pitch in listOf(0.49f, 2.01f, Float.NaN, Float.POSITIVE_INFINITY)) {
                    remote.onMessage("target", update().copy(revision = 2, pitch = pitch))
                    runCurrent()
                    assertEquals(DcSyncError.INVALID_MESSAGE, sent.last().error)
                    assertEquals(0.8f, port.state.pitch)
                    remote.onMessage(
                        "target",
                        DcSyncMessage(
                            DcSyncMessageType.STATE,
                            snapshot = initial.copy(revision = 2, playback = initial.playback.copy(pitch = pitch)),
                        ),
                    )
                    runCurrent()
                    assertEquals(DcSyncError.INVALID_MESSAGE, sent.last().error)
                    assertEquals(0.8f, port.state.pitch)
                }
                remote.onMessage("target", update().copy(revision = 2, speed = 1.5f))
                runCurrent()
                assertEquals(0.8f, port.state.pitch)
                assertEquals(1.5f, port.state.speed)
            } finally {
                remote.close()
            }
        }

    @Test
    fun controllerRejectsInvalidVolumeInFullAndScalarState() =
        runTest {
            val port = ClockPort()
            val sent = mutableListOf<DcSyncMessage>()
            val remote =
                DeviceConnectRemote(this, { "controller" }, { _, message ->
                    sent += message
                    true
                }, monotonicClockMs = { testScheduler.currentTime + 1L })
            try {
                remote.attach(port)
                remote.onPeerConnected("target", supportsPlaybackClock = true)
                val initial = snapshot().copy(playback = playback().copy(volume = 0.3f))
                remote.onMessage("target", DcSyncMessage(DcSyncMessageType.STATE, snapshot = initial))
                runCurrent()
                for (volume in listOf(-1f, 2f, Float.NaN, Float.POSITIVE_INFINITY)) {
                    remote.onMessage("target", update().copy(revision = 2, volume = volume))
                    runCurrent()
                    assertEquals(DcSyncError.INVALID_MESSAGE, sent.last().error)
                    assertEquals(0.3f, port.state.volume)
                    remote.onMessage(
                        "target",
                        DcSyncMessage(
                            DcSyncMessageType.STATE,
                            snapshot = initial.copy(revision = 2, playback = initial.playback.copy(volume = volume)),
                        ),
                    )
                    runCurrent()
                    assertEquals(DcSyncError.INVALID_MESSAGE, sent.last().error)
                    assertEquals(0.3f, port.state.volume)
                }
            } finally {
                remote.close()
            }
        }

    @Test
    fun pausedTransferPublishesLoadCompletionWithoutWaitingForAClockTick() =
        runTest {
            val sent = mutableListOf<DcSyncMessage>()
            val port = ClockPort()
            val remote =
                DeviceConnectRemote(this, { "target" }, { _, message ->
                    sent += message
                    true
                }, monotonicClockMs = { testScheduler.currentTime + 1L })
            try {
                remote.attach(port)
                remote.onPeerConnected("controller", supportsPlaybackClock = true)
                val initial = snapshot().let { it.copy(playback = it.playback.copy(playing = false, ready = false)) }
                remote.onMessage("controller", DcSyncMessage(DcSyncMessageType.TRANSFER, snapshot = initial))
                runCurrent()
                assertFalse(port.state.playing)
                assertEquals(1, port.loads)
                assertTrue(sent.any { it.type == DcSyncMessageType.UPDATE && it.ready && it.playing == false })
            } finally {
                remote.close()
            }
        }

    @Test
    fun targetSendsTrackUpdatesOnlyToCapablePeers() =
        runTest {
            val sent = mutableListOf<Pair<String, DcSyncMessage>>()
            val port = ClockPort(playback())
            val remote =
                DeviceConnectRemote(this, { "target" }, { peer, message ->
                    sent += peer to message
                    true
                }, monotonicClockMs = { testScheduler.currentTime + 1L })
            try {
                remote.attach(port)
                remote.onPeerConnected("legacy")
                remote.onPeerConnected("modern", supportsPlaybackClock = true)
                remote.onMessage("legacy", DcSyncMessage(DcSyncMessageType.ATTACH))
                remote.onMessage("modern", DcSyncMessage(DcSyncMessageType.ATTACH))
                runCurrent()
                val queueId =
                    sent
                        .last()
                        .second.snapshot!!
                        .queueStateId
                sent.clear()
                remote.intercept(DcPlaybackCommand(DcPlaybackAction.NEXT))
                runCurrent()
                assertEquals(DcSyncMessageType.STATE, sent.single { it.first == "legacy" }.second.type)
                val update = sent.single { it.first == "modern" }.second
                assertEquals(DcSyncMessageType.UPDATE, update.type)
                assertEquals(1, update.index)
                assertEquals(queueId, update.queueStateId)
                assertEquals(null, update.snapshot)
            } finally {
                remote.close()
            }
        }

    @Test
    fun naturalCompletionKeepsBothPeersOnTheSameOccurrenceAndAcceptsFurtherCommands() =
        runTest {
            for (clockCapable in listOf(false, true)) {
                val initial = playback()
                val songs = initial.queue.displayedItems + initial.queue.displayedItems.first()
                val targetPort = ClockPort(initial.copy(queue = MusicQueueSnapshot(songs, songs, 0)))
                val controllerPort = ClockPort()
                val sent = mutableListOf<DcSyncMessage>()
                lateinit var controller: DeviceConnectRemote
                val target =
                    DeviceConnectRemote(this, { "target" }, { _, message ->
                        sent += message
                        controller.onMessage("target", DcJson.decodeFromString(DcJson.encodeToString(message)))
                        true
                    }, monotonicClockMs = { testScheduler.currentTime + 1 })
                controller =
                    DeviceConnectRemote(this, { "controller" }, { _, message ->
                        target.onMessage("controller", DcJson.decodeFromString(DcJson.encodeToString(message)))
                        true
                    }, monotonicClockMs = { testScheduler.currentTime + 1 })
                try {
                    target.attach(targetPort)
                    controller.attach(controllerPort)
                    target.onPeerConnected("controller", supportsPlaybackClock = clockCapable)
                    controller.onPeerConnected("target", supportsPlaybackClock = clockCapable)
                    controller.discoverPlayback()
                    runCurrent()
                    val session = target.state.value.sessionId
                    val queueRevision = target.queueRevision

                    for (index in 1..songs.lastIndex) {
                        sent.clear()
                        targetPort.state = targetPort.state.copy(positionMs = targetPort.state.durationMs, ready = false)
                        target.playbackEnded()
                        target.playbackEnded()
                        runCurrent()
                        target.localStateChanged()
                        runCurrent()

                        assertEquals(index, targetPort.state.queue.currentIndex)
                        assertEquals(index, controllerPort.state.queue.currentIndex)
                        assertEquals(0L, targetPort.state.positionMs)
                        assertTrue(targetPort.state.playing)
                        assertTrue(controllerPort.state.ready)
                        assertEquals(session, target.state.value.sessionId)
                        assertEquals(session, controller.state.value.sessionId)
                        assertEquals(queueRevision, target.queueRevision)
                        val transition = sent.first()
                        assertEquals(if (clockCapable) DcSyncMessageType.UPDATE else DcSyncMessageType.STATE, transition.type)
                    }
                    controller.intercept(DcPlaybackCommand(DcPlaybackAction.PAUSE))
                    runCurrent()
                    assertFalse(targetPort.state.playing)
                    controller.intercept(DcPlaybackCommand(DcPlaybackAction.PLAY))
                    runCurrent()
                    assertTrue(targetPort.state.playing)
                    target.playbackEnded()
                    runCurrent()
                    assertFalse(targetPort.state.playing)
                    assertFalse(controllerPort.state.playing)
                    controller.intercept(DcPlaybackCommand(DcPlaybackAction.SELECT, index = 0))
                    runCurrent()
                    assertEquals(0, targetPort.state.queue.currentIndex)
                    assertTrue(targetPort.state.playing)
                    controller.intercept(DcPlaybackCommand(DcPlaybackAction.REPEAT, repeatMode = DcRepeatMode.ONE))
                    runCurrent()
                    val beforeRepeat = target.trackRevision
                    target.playbackEnded()
                    runCurrent()
                    assertEquals(0, targetPort.state.queue.currentIndex)
                    assertEquals(checkNotNull(beforeRepeat) + 1, target.trackRevision)
                    controller.intercept(DcPlaybackCommand(DcPlaybackAction.REPEAT, repeatMode = DcRepeatMode.ALL))
                    controller.intercept(DcPlaybackCommand(DcPlaybackAction.SELECT, index = songs.lastIndex))
                    runCurrent()
                    target.playbackEnded()
                    runCurrent()
                    assertEquals(0, targetPort.state.queue.currentIndex)
                    assertEquals(0, controllerPort.state.queue.currentIndex)
                    assertTrue(targetPort.state.playing)
                    assertEquals(0, controllerPort.loads)
                    assertEquals(null, target.state.value.error)
                    assertEquals(null, controller.state.value.error)
                } finally {
                    target.close()
                    controller.close()
                }
            }
        }

    @Test
    fun uninterruptedPlaybackUsesTenSecondCorrectionsOnlyForCapablePeers() =
        runTest {
            val sent = mutableListOf<Pair<String, DcSyncMessage>>()
            val port = ClockPort(playback()).apply { position = { testScheduler.currentTime } }
            val remote =
                DeviceConnectRemote(this, { "target" }, { peer, message ->
                    sent += peer to message
                    true
                }, monotonicClockMs = {
                    testScheduler.currentTime +
                        1
                })
            try {
                remote.attach(port)
                remote.onPeerConnected("legacy")
                remote.onPeerConnected("modern", supportsPlaybackClock = true)
                remote.onMessage("legacy", DcSyncMessage(DcSyncMessageType.ATTACH))
                remote.onMessage("modern", DcSyncMessage(DcSyncMessageType.ATTACH))
                runCurrent()
                sent.clear()
                advanceTimeBy(10_001)
                runCurrent()
                assertEquals(10, sent.count { it.first == "legacy" && it.second.type == DcSyncMessageType.UPDATE })
                assertEquals(1, sent.count { it.first == "modern" && it.second.type == DcSyncMessageType.UPDATE })
                assertTrue(sent.none { it.second.type == DcSyncMessageType.STATE })
            } finally {
                remote.close()
            }
        }

    @Test
    fun controllerAcceptsLegacySnapshotsAndUpdatesWithoutMediaLoads() =
        runTest {
            val port = ClockPort()
            val remote = DeviceConnectRemote(this, { "controller" }, { _, _ -> true }, monotonicClockMs = { testScheduler.currentTime + 1 })
            try {
                remote.attach(port)
                remote.onPeerConnected("target")
                // Legacy targets may leave ready=false in STATE after a track change; their UPDATE has no ready field.
                val snapshot = snapshot().copy(playback = playback().copy(ready = false))
                remote.onMessage("target", DcSyncMessage(DcSyncMessageType.STATE, snapshot = snapshot))
                runCurrent()
                advanceTimeBy(2_001)
                runCurrent()
                assertEquals(2_000L, port.state.positionMs)
                remote.onMessage("target", update().copy(positionMs = 15_000, speed = 1.5f))
                runCurrent()
                advanceTimeBy(1_000)
                runCurrent()
                assertTrue(port.state.positionMs in 16_000..16_500)
                assertEquals(0, port.loads)
                remote.onPeerDisconnected("target", intentional = true)
                runCurrent()
                assertFalse(port.state.playing)
                assertFalse(remote.isActive)
            } finally {
                remote.close()
            }
        }

    @Test
    fun modernTrackUpdatesUseAcceptedQueueAndFreezeWhenBuffering() =
        runTest {
            val port = ClockPort()
            val sent = mutableListOf<DcSyncMessage>()
            val remote =
                DeviceConnectRemote(this, { "controller" }, { _, message ->
                    sent += message
                    true
                }, monotonicClockMs = {
                    testScheduler.currentTime +
                        1
                })
            try {
                remote.attach(port)
                remote.onPeerConnected("target", supportsPlaybackClock = true)
                remote.onMessage("target", DcSyncMessage(DcSyncMessageType.STATE, snapshot = snapshot()))
                runCurrent()
                val songs = port.state.queue.displayedItems
                val session = DcScrobbleSession("second", 123, 42_000, completed = false, pending = false)
                remote.onMessage(
                    "target",
                    update().copy(
                        revision = 2,
                        trackRevision = 2,
                        index = 1,
                        positionMs = 42_000,
                        ready = false,
                        scrobbleSession = session,
                    ),
                )
                runCurrent()
                advanceTimeBy(3_001)
                runCurrent()
                assertSame(songs, port.state.queue.displayedItems)
                assertEquals(session, port.state.scrobbleSession)
                assertEquals(1, port.state.queue.currentIndex)
                assertEquals(42_000L, port.state.positionMs)
                assertEquals(0, port.loads)
                assertTrue(sent.none { it.type == DcSyncMessageType.ATTACH })
                remote.onMessage("target", update().copy(revision = 3, trackRevision = 2, index = 1, queueStateId = "missing"))
                runCurrent()
                assertEquals(DcSyncMessageType.ATTACH, sent.last().type)
                assertEquals(42_000L, port.state.positionMs)
                remote.onMessage("target", update().copy(revision = 3, trackRevision = 2, index = 99))
                runCurrent()
                assertEquals(DcSyncError.INVALID_MESSAGE, sent.last().error)
            } finally {
                remote.close()
            }
        }

    @Test
    fun transferBackLoadsMediaOnlyAfterLeavingControllerMode() =
        runTest {
            val port = ClockPort()
            val remote =
                DeviceConnectRemote(this, { "controller" }, { _, _ -> true }, monotonicClockMs = { testScheduler.currentTime + 1L })
            try {
                remote.attach(port)
                remote.onPeerConnected("target", supportsPlaybackClock = true)
                remote.onMessage("target", DcSyncMessage(DcSyncMessageType.STATE, snapshot = snapshot()))
                runCurrent()
                assertEquals(0, port.loads)
                val handoff = snapshot().copy(playback = playback().copy(positionMs = 12_000))
                remote.onMessage("target", DcSyncMessage(DcSyncMessageType.TRANSFER, snapshot = handoff))
                runCurrent()
                assertFalse(remote.isActive)
                assertEquals(1, port.loads)
                assertEquals(12_000L, port.state.positionMs)
                assertTrue(port.state.playing)
            } finally {
                remote.close()
            }
        }

    @Test
    fun queueSanitizationReusesUnchangedListsButValidatesNewQueues() {
        val cache = DcWireQueueCache()
        val state = playback()
        val initial = cache.sanitize(state)
        val next = cache.sanitize(state.copy(queue = state.queue.copy(currentIndex = 1), positionMs = 8_000))
        assertSame(initial.queue.displayedItems, next.queue.displayedItems)
        assertSame(initial.queue.originalItems, next.queue.originalItems)
        assertEquals(1, next.queue.currentIndex)
        assertTrue(validDcPlayback(next))
        cache.clear()
        assertTrue(initial.queue.displayedItems !== cache.sanitize(state).queue.displayedItems)
        val invalid = cache.sanitize(state.copy(queue = state.queue.copy(originalIndices = listOf(0, 0))))
        assertFalse(validDcPlayback(invalid))
    }

    private fun playback(): DcLocalPlaybackState {
        val songs = listOf("first", "second").map { SongItem(it, it, null, emptyList(), null, duration = 300) }
        return DcLocalPlaybackState(
            queue = MusicQueueSnapshot(songs, songs, 0, originalIndices = listOf(0, 1)),
            playing = true,
            ready = true,
            durationMs = 300_000,
        )
    }

    private fun snapshot(): DcPlaybackSnapshot = DcPlaybackSnapshot("session", 1, 1, 1, playback(), queueStateId = "queue")

    private fun update(): DcSyncMessage =
        DcSyncMessage(
            DcSyncMessageType.UPDATE,
            sessionId = "session",
            revision = 1,
            queueRevision = 1,
            trackRevision = 1,
            index = 0,
            durationMs = 300_000,
            playing = true,
            queueStateId = "queue",
        )

    private class ClockPort(
        var state: DcLocalPlaybackState = DcLocalPlaybackState(),
    ) : DeviceConnectPlaybackPort {
        var loads = 0

        var position: (() -> Long)? = null

        override suspend fun snapshot(): DcLocalPlaybackState = position?.let { state.copy(positionMs = it()) } ?: state

        override suspend fun apply(
            snapshot: DcPlaybackSnapshot,
            positionMs: Long,
            shouldCommit: () -> Boolean,
        ) {
            loads++
            if (shouldCommit()) state = snapshot.playback.copy(positionMs = positionMs, ready = true)
        }

        private var controlling = false

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

        override suspend fun setAudioOutputEnabled(enabled: Boolean) = Unit
    }
}
