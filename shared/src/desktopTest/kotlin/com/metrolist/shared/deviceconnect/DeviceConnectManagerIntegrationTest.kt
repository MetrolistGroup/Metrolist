/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.deviceconnect

import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playback.MusicQueueSnapshot
import com.metrolist.shared.settings.Settings
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeNotNull
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource
import com.russhwolf.settings.Settings as KmpSettings

class DeviceConnectManagerIntegrationTest {
    @Test
    fun pitchSyncUsesAuthenticatedCapabilityAndSurvivesTransfer() =
        runBlocking {
            for (supportsPitch in listOf(true, false)) {
                val initial = playingState().copy(speed = 1.25f, pitch = 0.8f)
                val targetPort = MemoryPlaybackPort(initial)
                val controllerPort = MemoryPlaybackPort(DcLocalPlaybackState())
                val target = manager(Settings(MapSettings()), ::anonymousIdentity, LEFT_INFO, supportsPlaybackPitch = supportsPitch)
                val controller = manager(Settings(MapSettings()), ::anonymousIdentity, RIGHT_INFO)
                try {
                    target.remote.attach(targetPort)
                    controller.remote.attach(controllerPort)
                    pairAndAwaitPlaybackLink(target, controller)
                    controller.remote.discoverPlayback()
                    withTimeout(TEST_TIMEOUT_MS.milliseconds) { controllerPort.state.first { it.pitch == 0.8f } }
                    assertTrue(controller.remote.intercept(DcPlaybackCommand(DcPlaybackAction.PITCH, pitch = 1.5f)))
                    if (supportsPitch) {
                        withTimeout(TEST_TIMEOUT_MS.milliseconds) { controllerPort.state.first { it.pitch == 1.5f } }
                        withTimeout(TEST_TIMEOUT_MS.milliseconds) { targetPort.state.first { it.pitch == 1.5f } }
                        assertEquals(1.25f, targetPort.state.value.speed)
                        assertTrue(target.remote.intercept(DcPlaybackCommand(DcPlaybackAction.PITCH, pitch = 0.5f)))
                        withTimeout(TEST_TIMEOUT_MS.milliseconds) { controllerPort.state.first { it.pitch == 0.5f } }
                        controller.remote.transferTo(RIGHT_ID)
                        withTimeout(TEST_TIMEOUT_MS.milliseconds) {
                            controllerPort.audioOutputEnabled.first { it }
                            controllerPort.state.first { it.playing && it.pitch == 0.5f }
                        }
                        assertEquals(1.25f, controllerPort.state.value.speed)
                    } else {
                        withTimeout(TEST_TIMEOUT_MS.milliseconds) { controller.remote.state.first { it.error == DcSyncError.NOT_ALLOWED } }
                        assertEquals(0.8f, targetPort.state.value.pitch)
                        assertTrue(controller.remote.intercept(DcPlaybackCommand(DcPlaybackAction.SPEED, speed = 1.75f)))
                        withTimeout(TEST_TIMEOUT_MS.milliseconds) { controllerPort.state.first { it.speed == 1.75f } }
                        assertEquals(0.8f, targetPort.state.value.pitch)
                    }
                    assertTrue(target.isConnectedTo(RIGHT_ID))
                    assertTrue(controller.isConnectedTo(LEFT_ID))
                } finally {
                    release(target, controller)
                }
            }
        }

    @Test
    fun manualConnectionUsesTheListenerWithoutDiscoveryAndReconnectsWithSavedProofs() =
        runBlocking {
            val host = getLocalIpV4Address()
            assumeNotNull(host)
            val left = manager(Settings(MapSettings()), ::anonymousIdentity, LEFT_INFO)
            val right = manager(Settings(MapSettings()), ::anonymousIdentity, RIGHT_INFO)
            try {
                left.enable(null)
                right.enable(null)
                assertTrue(left.state.value.discoveryFailed)
                assertTrue(
                    left.state.value.peers
                        .isEmpty(),
                )
                val endpoint = "$host:${right.state.value.serverPort}"
                left.connectDirect(endpoint)
                val leftRequest = left.awaitPairingRequest(RIGHT_ID, requiresApproval = true)
                val rightRequest = right.awaitPairingRequest(LEFT_ID, requiresApproval = true)
                assertEquals(leftRequest.sas, rightRequest.sas)
                assertTrue(leftRequest.sas.matches(SAS_PATTERN))
                assertFalse(left.isConnectedTo(RIGHT_ID))
                assertEquals(
                    RIGHT_ID,
                    left.state.value.peers
                        .single()
                        .id,
                )
                left.approvePairing(leftRequest.requestId)
                right.approvePairing(rightRequest.requestId)
                left.awaitConnectedAndPaired(RIGHT_ID)
                right.awaitConnectedAndPaired(LEFT_ID)
                assertEquals(
                    listOf(RIGHT_ID),
                    left.state.value.peers
                        .map { it.id },
                )
                assertEquals(
                    "",
                    left.state.value.peers
                        .single()
                        .serviceName,
                )
                assertFalse(left.state.value.directConnectionFailed)

                left.disable()
                left.awaitState { !it.enabled && it.peers.isEmpty() }
                right.awaitState { !it.isConnectedTo(LEFT_ID) }
                left.enable(null)
                left.connectDirect(endpoint)
                left.awaitConnectedAndPaired(RIGHT_ID)
                right.awaitConnectedAndPaired(LEFT_ID)
                assertTrue(
                    left.state.value.pairingRequests
                        .isEmpty(),
                )
                assertTrue(
                    right.state.value.pairingRequests
                        .isEmpty(),
                )
                right.disable()
                right.awaitState { !it.enabled && it.peers.isEmpty() }
                left.awaitState { !it.isConnectedTo(RIGHT_ID) }
                left.connectDirect(endpoint)
                left.awaitState { it.directConnectionFailed }
                assertFalse(left.isConnectedTo(RIGHT_ID))
            } finally {
                release(left, right)
            }
        }

    @Test
    fun undiscoveredPeerRejectionStoresNoGrantAndReportsFailure() =
        runBlocking {
            val left = manager(Settings(MapSettings()), ::anonymousIdentity, LEFT_INFO)
            val right = manager(Settings(MapSettings()), ::anonymousIdentity, RIGHT_INFO)
            val link = MemoryLink(leftRemoteHost = "100.64.0.2", rightRemoteHost = "100.64.0.1")
            try {
                left.attachChannelForTest(link.left, isIncoming = false)
                right.attachChannelForTest(link.right, isIncoming = true)
                left.awaitPairingRequest(RIGHT_ID, requiresApproval = true)
                right.rejectPairing(right.awaitPairingRequest(LEFT_ID, requiresApproval = true).requestId)
                link.awaitClosed()
                left.awaitState { it.directConnectionFailed }
                assertTrue(
                    left.state.value.pairedDevices
                        .isEmpty(),
                )
                assertTrue(
                    right.state.value.pairedDevices
                        .isEmpty(),
                )
                assertFalse(left.isConnectedTo(RIGHT_ID))
            } finally {
                release(left, right)
            }
        }

    @Test
    fun undiscoveredPeersStillRejectDifferentAccounts() =
        runBlocking {
            val left = manager(Settings(MapSettings()), { signedIdentity("left-account") }, LEFT_INFO)
            val right = manager(Settings(MapSettings()), { signedIdentity("right-account") }, RIGHT_INFO)
            val link = MemoryLink()
            try {
                left.attachChannelForTest(link.left, isIncoming = false)
                right.attachChannelForTest(link.right, isIncoming = true)
                link.awaitClosed()
                left.awaitState { it.directConnectionFailed }
                assertTrue(
                    left.state.value.pairedDevices
                        .isEmpty(),
                )
                assertTrue(
                    right.state.value.pairedDevices
                        .isEmpty(),
                )
                assertTrue(
                    left.state.value.pairingRequests
                        .isEmpty(),
                )
            } finally {
                release(left, right)
            }
        }

    @Test
    fun discoveryCannotReplaceALiveAuthenticatedEndpoint() =
        runBlocking {
            val left = manager(Settings(MapSettings()), ::anonymousIdentity, LEFT_INFO)
            val right = manager(Settings(MapSettings()), ::anonymousIdentity, RIGHT_INFO)
            val link = MemoryLink(leftRemoteHost = "192.168.1.2", rightRemoteHost = "192.168.1.3")
            try {
                connect(left, right, link)
                left.approvePairing(left.awaitPairingRequest(RIGHT_ID, requiresApproval = true).requestId)
                right.approvePairing(right.awaitPairingRequest(LEFT_ID, requiresApproval = true).requestId)
                left.awaitConnectedAndPaired(RIGHT_ID)
                right.awaitConnectedAndPaired(LEFT_ID)
                val authenticated =
                    left.state.value.peers
                        .single { it.id == RIGHT_ID }
                left.reportDiscoveredDeviceForTest(DiscoveredDevice("spoof", RIGHT_ID, "Spoof", "192.168.1.99", 4567))
                assertEquals(
                    authenticated.host,
                    left.state.value.peers
                        .single { it.id == RIGHT_ID }
                        .host,
                )
                assertEquals(
                    authenticated.port,
                    left.state.value.peers
                        .single { it.id == RIGHT_ID }
                        .port,
                )
                link.left.close()
                left.awaitState { !it.isConnectedTo(RIGHT_ID) }
                assertEquals(
                    authenticated.host,
                    left.state.value.peers
                        .single { it.id == RIGHT_ID }
                        .host,
                )
            } finally {
                release(left, right)
            }
        }

    @Test
    fun pairingApprovalUsesElapsedTimeRatherThanDisplayedEpochDeadline() =
        runBlocking {
            val clock = TestTimeSource()
            val left = DeviceConnectManager(Settings(MapSettings()), ::anonymousIdentity, { LEFT_INFO }, pairingTimeSource = clock)
            val right = manager(Settings(MapSettings()), ::anonymousIdentity, RIGHT_INFO)
            try {
                val link = connect(left, right)
                val request = left.awaitPairingRequest(RIGHT_ID, requiresApproval = true)
                clock += DEVICE_CONNECT_PAIRING_TIMEOUT_MILLIS.milliseconds
                left.approvePairing(request.requestId)
                link.awaitClosed()
                assertFalse(left.isConnectedTo(RIGHT_ID))
                assertTrue(
                    left.state.value.pairedDevices
                        .isEmpty(),
                )
            } finally {
                release(left, right)
            }
        }

    @Test
    fun disablingDeviceConnectDisconnectsPairedPeersWithoutForgettingThem() =
        runBlocking {
            val settings = Settings(MapSettings())
            val left = manager(settings, ::anonymousIdentity, LEFT_INFO)
            val right = manager(Settings(MapSettings()), ::anonymousIdentity, RIGHT_INFO)
            try {
                val link = pairAndAwaitPlaybackLink(left, right)
                val pairs = settings.deviceConnectPairedDevices
                assertTrue(pairs.isNotBlank())

                assertFalse(settings.devtoolsUnlocked)
                settings.setDevtoolsEnabled(true)
                settings.setDevtoolsEnabled(false)
                assertTrue(left.isConnectedTo(RIGHT_ID))
                assertTrue(left.sendPlayback(RIGHT_ID, DcSyncMessage(DcSyncMessageType.ERROR, error = DcSyncError.UNAVAILABLE)))

                left.disable()
                link.awaitClosed()
                assertEquals(pairs, settings.deviceConnectPairedDevices)
                assertFalse(left.sendPlayback(RIGHT_ID, DcSyncMessage(DcSyncMessageType.ERROR, error = DcSyncError.UNAVAILABLE)))
                assertFalse(left.remote.isActive)
            } finally {
                release(left, right)
            }
        }

    @Test
    fun playbackSendReturnsWhilePeerWorkerIsBlockedAndShutdownCancelsWorker() =
        runBlocking {
            val left = manager(Settings(MapSettings()), ::anonymousIdentity, LEFT_INFO)
            val right = manager(Settings(MapSettings()), ::anonymousIdentity, RIGHT_INFO)
            val link = MemoryLink()
            val rightChannel = PlaybackSendChannel(link.right)
            val started = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            val returned = CompletableDeferred<Boolean>()
            try {
                left.attachChannelForTest(link.left, isIncoming = false, expectedPeerId = RIGHT_ID)
                right.attachChannelForTest(rightChannel, isIncoming = true)
                val leftRequest = left.awaitPairingRequest(RIGHT_ID, requiresApproval = true)
                val rightRequest = right.awaitPairingRequest(LEFT_ID, requiresApproval = true)
                left.approvePairing(leftRequest.requestId)
                right.approvePairing(rightRequest.requestId)
                left.awaitConnectedAndPaired(RIGHT_ID)
                right.awaitConnectedAndPaired(LEFT_ID)
                val message = DcSyncMessage(DcSyncMessageType.ERROR, error = DcSyncError.UNAVAILABLE)
                link.awaitProcessed { right.sendPlayback(LEFT_ID, message) }
                rightChannel.beforePlaybackSend = {
                    started.complete(Unit)
                    gate.await()
                }
                val sending =
                    launch(CoroutineName("cancelled-playback-caller")) {
                        returned.complete(right.sendPlayback(LEFT_ID, message))
                    }
                withTimeout(TEST_TIMEOUT_MS.milliseconds) { started.await() }
                withTimeout(TEST_TIMEOUT_MS.milliseconds) { sending.join() }
                assertTrue(returned.await())
                assertTrue(currentCoroutineContext().isActive)
                withTimeout(TEST_TIMEOUT_MS.milliseconds) { right.release() }
                link.awaitClosed()
            } finally {
                gate.complete(Unit)
                release(left, right)
            }
        }

    @Test
    fun playbackIsGatedUntilPairingAndUsesBoundedEncryptedBatchesWithoutFrameLogs() =
        runBlocking {
            var leftInfo = LEFT_INFO
            val left =
                DeviceConnectManager(
                    settings = Settings(MapSettings()),
                    identityProvider = ::anonymousIdentity,
                    localInfoProvider = { leftInfo },
                )
            val right = manager(Settings(MapSettings()), ::anonymousIdentity, RIGHT_INFO)
            val link = MemoryLink()
            try {
                connect(left, right, link)
                val leftRequest = left.awaitPairingRequest(RIGHT_ID, requiresApproval = true)
                val rightRequest = right.awaitPairingRequest(LEFT_ID, requiresApproval = true)
                val message = DcSyncMessage(DcSyncMessageType.ERROR, error = DcSyncError.INVALID_MESSAGE)
                assertFalse(left.sendPlayback(RIGHT_ID, message))
                assertFalse(right.sendPlayback(LEFT_ID, message))
                assertTrue(link.sentFrames.none { it.type == DcMessageTypes.PLAYBACK })

                left.approvePairing(leftRequest.requestId)
                right.approvePairing(rightRequest.requestId)
                left.awaitConnectedAndPaired(RIGHT_ID)
                right.awaitConnectedAndPaired(LEFT_ID)
                link.awaitProcessed { left.sendPlayback(RIGHT_ID, message) }
                val largeMessage = message.copy(queueStateId = "x".repeat(100_000))
                val firstSend = async { left.sendPlayback(RIGHT_ID, largeMessage) }
                val secondSend = async { left.sendPlayback(RIGHT_ID, largeMessage) }
                assertTrue(firstSend.await())
                assertTrue(secondSend.await())
                link.awaitProcessed { link.sentFrames.count { it.type == DcMessageTypes.PLAYBACK } >= 11 }
                leftInfo = LEFT_INFO.copy(deviceName = "After playback batches")
                left.updateDeviceName(leftInfo.deviceName)
                right.awaitState { state ->
                    state.peers.any { it.id == LEFT_ID && it.name == leftInfo.deviceName }
                }
                assertTrue(link.sentFrames.count { it.type == DcMessageTypes.PLAYBACK } >= 11)
                for (manager in listOf(left, right)) {
                    assertFalse(
                        manager.state.value.log
                            .any { it.message.contains("playback (enc") },
                    )
                }
                assertTrue(right.isConnectedTo(LEFT_ID))
                assertTrue(link.sentFrames.filter { it.type == DcMessageTypes.PLAYBACK }.all { it.encrypted && it.seq > 0L })
                assertTrue(link.sentFrameSizes.all { it <= DEVICE_CONNECT_MAX_FRAME_BYTES })
                assertFalse(
                    right.state.value.log
                        .any { it.message.contains("invalid playback assembly") },
                )

                link.left.close()
                link.awaitClosed()
                left.awaitState { !it.isConnectedTo(RIGHT_ID) }
                assertFalse(left.sendPlayback(RIGHT_ID, message))
            } finally {
                release(left, right)
            }
        }

    @Test
    fun legacyAndClockCapablePeersFollowTrackChangesInBothDirections() =
        runBlocking {
            for (legacyTarget in listOf(true, false)) {
                val songs = listOf("first", "second").map { SongItem(it, it, null, emptyList(), null, duration = 300) }
                val initial = playingState().copy(queue = MusicQueueSnapshot(songs, songs, 0, originalIndices = listOf(0, 1)))
                val targetPort = MemoryPlaybackPort(initial)
                val controllerPort = MemoryPlaybackPort(DcLocalPlaybackState()).apply { failApplies = 1 }
                val target = manager(Settings(MapSettings()), ::anonymousIdentity, LEFT_INFO, supportsPlaybackClock = !legacyTarget)
                val controller = manager(Settings(MapSettings()), ::anonymousIdentity, RIGHT_INFO, supportsPlaybackClock = legacyTarget)
                try {
                    target.remote.attach(targetPort)
                    controller.remote.attach(controllerPort)
                    pairAndAwaitPlaybackLink(target, controller)
                    controller.remote.discoverPlayback()
                    withTimeout(TEST_TIMEOUT_MS.milliseconds) { controllerPort.state.first { it.queue == initial.queue } }
                    assertTrue(controller.remote.intercept(DcPlaybackCommand(DcPlaybackAction.NEXT)))
                    withTimeout(TEST_TIMEOUT_MS.milliseconds) { controllerPort.state.first { it.queue.currentIndex == 1 } }
                    assertEquals(initial.queue.displayedItems, controllerPort.state.value.queue.displayedItems)
                    assertEquals(1, controllerPort.failApplies)
                    assertTrue(target.isConnectedTo(RIGHT_ID))
                    assertTrue(controller.isConnectedTo(LEFT_ID))
                } finally {
                    release(target, controller)
                }
            }
        }

    @Test
    fun olderPlaybackVersionPeerStaysPairedButNeverReceivesCurrentPlaybackFrames() =
        runBlocking {
            val left = manager(Settings(MapSettings()), ::anonymousIdentity, LEFT_INFO)
            val right =
                DeviceConnectManager(
                    settings = Settings(MapSettings()),
                    identityProvider = ::anonymousIdentity,
                    localInfoProvider = { RIGHT_INFO },
                    advertisedPlaybackVersion = 3,
                )
            try {
                val link = connect(left, right)
                val leftRequest = left.awaitPairingRequest(RIGHT_ID, requiresApproval = true)
                val rightRequest = right.awaitPairingRequest(LEFT_ID, requiresApproval = true)
                left.approvePairing(leftRequest.requestId)
                right.approvePairing(rightRequest.requestId)
                left.awaitConnectedAndPaired(RIGHT_ID)
                right.awaitConnectedAndPaired(LEFT_ID)
                left.awaitState { it.log.any { entry -> entry.message.contains("← device_info") } }
                right.awaitState { it.log.any { entry -> entry.message.contains("← device_info") } }
                assertFalse(left.sendPlayback(RIGHT_ID, DcSyncMessage(DcSyncMessageType.ATTACH)))
                assertFalse(right.sendPlayback(LEFT_ID, DcSyncMessage(DcSyncMessageType.ATTACH)))
                assertTrue(link.sentFrames.none { it.type == DcMessageTypes.PLAYBACK })
                assertTrue(left.isConnectedTo(RIGHT_ID))
                assertTrue(right.isConnectedTo(LEFT_ID))
            } finally {
                release(left, right)
            }
        }

    @Test
    fun playbackSendRechecksCurrentAccountIdentity() =
        runBlocking {
            var leftIdentity = anonymousIdentity()
            val left = manager(Settings(MapSettings()), { leftIdentity }, LEFT_INFO)
            val right = manager(Settings(MapSettings()), ::anonymousIdentity, RIGHT_INFO)
            try {
                val link = connect(left, right)
                val leftRequest = left.awaitPairingRequest(RIGHT_ID, requiresApproval = true)
                val rightRequest = right.awaitPairingRequest(LEFT_ID, requiresApproval = true)
                left.approvePairing(leftRequest.requestId)
                right.approvePairing(rightRequest.requestId)
                left.awaitConnectedAndPaired(RIGHT_ID)
                right.awaitConnectedAndPaired(LEFT_ID)
                val message = DcSyncMessage(DcSyncMessageType.ERROR, error = DcSyncError.INVALID_MESSAGE)
                link.awaitProcessed { left.sendPlayback(RIGHT_ID, message) }
                leftIdentity = signedIdentity("changed-account")
                assertFalse(left.sendPlayback(RIGHT_ID, message))
                link.awaitClosed()
            } finally {
                release(left, right)
            }
        }

    @Test
    fun helloBeforeCommitmentIsRejected() =
        runBlocking {
            val settings = Settings(MapSettings())
            val manager = manager(settings, ::anonymousIdentity, RIGHT_INFO)
            val channel = ProbeChannel()
            try {
                manager.attachChannelForTest(channel, isIncoming = true)
                assertEquals(DcMessageTypes.HELLO_COMMIT, channel.nextOutgoing().type)

                val key = DeviceCrypto.generateEcKeyPair()
                channel.inject(
                    DcWire(
                        type = DcMessageTypes.HELLO,
                        payload =
                            DcJson.encodeToString(
                                HelloPayload(
                                    version = DEVICE_CONNECT_PROTOCOL_VERSION,
                                    role = DeviceConnectRole.INITIATOR.wireName,
                                    deviceId = LEFT_ID,
                                    nonce = ByteArray(DEVICE_CONNECT_HELLO_NONCE_BYTES).toBase64(),
                                    publicKey = key.publicKeyUncompressed.toBase64(),
                                ),
                            ),
                        version = DEVICE_CONNECT_PROTOCOL_VERSION,
                    ),
                )

                channel.awaitClosed()
                assertTrue(
                    manager.state.value.pairingRequests
                        .isEmpty(),
                )
                assertTrue(DevicePairStore(settings).pairsFor(anonymousIdentity()).isEmpty())
            } finally {
                release(manager)
            }
        }

    @Test
    fun tamperedHelloRevealIsRejected() =
        runBlocking {
            val settings = Settings(MapSettings())
            val manager = manager(settings, ::anonymousIdentity, RIGHT_INFO)
            val channel = ProbeChannel()
            try {
                manager.attachChannelForTest(channel, isIncoming = true)
                assertEquals(DcMessageTypes.HELLO_COMMIT, channel.nextOutgoing().type)

                val key = DeviceCrypto.generateEcKeyPair()
                val material =
                    DeviceConnectHelloMaterial(
                        role = DeviceConnectRole.INITIATOR,
                        deviceId = LEFT_ID,
                        nonce = ByteArray(DEVICE_CONNECT_HELLO_NONCE_BYTES) { 1 },
                        publicKey = key.publicKeyUncompressed,
                    )
                channel.inject(
                    DcWire(
                        type = DcMessageTypes.HELLO_COMMIT,
                        payload =
                            DcJson.encodeToString(
                                HelloCommitPayload(
                                    version = DEVICE_CONNECT_PROTOCOL_VERSION,
                                    role = material.role.wireName,
                                    deviceId = material.deviceId,
                                    commitment = deviceConnectHelloCommitment(material).toBase64(),
                                ),
                            ),
                        version = DEVICE_CONNECT_PROTOCOL_VERSION,
                    ),
                )
                assertEquals(DcMessageTypes.HELLO, channel.nextOutgoing().type)

                channel.inject(
                    DcWire(
                        type = DcMessageTypes.HELLO,
                        payload =
                            DcJson.encodeToString(
                                HelloPayload(
                                    version = DEVICE_CONNECT_PROTOCOL_VERSION,
                                    role = material.role.wireName,
                                    deviceId = material.deviceId,
                                    nonce = ByteArray(DEVICE_CONNECT_HELLO_NONCE_BYTES) { 2 }.toBase64(),
                                    publicKey = material.publicKey.toBase64(),
                                ),
                            ),
                        version = DEVICE_CONNECT_PROTOCOL_VERSION,
                    ),
                )

                channel.awaitClosed()
                assertTrue(
                    manager.state.value.pairingRequests
                        .isEmpty(),
                )
                assertTrue(DevicePairStore(settings).pairsFor(anonymousIdentity()).isEmpty())
            } finally {
                release(manager)
            }
        }

    @Test
    fun reflectedHelloCommitmentIsRejected() =
        runBlocking {
            val manager = manager(Settings(MapSettings()), ::anonymousIdentity, RIGHT_INFO)
            val channel = ProbeChannel()
            try {
                manager.attachChannelForTest(channel, isIncoming = true)
                val localCommit = channel.nextOutgoing()
                assertEquals(DcMessageTypes.HELLO_COMMIT, localCommit.type)

                channel.inject(localCommit)

                channel.awaitClosed()
                assertTrue(
                    manager.state.value.pairingRequests
                        .isEmpty(),
                )
            } finally {
                release(manager)
            }
        }

    @Test
    fun failedConnectionDropsOnlyTheAttemptedEndpoint() {
        val attempted =
            DeviceConnectManager.PeerDevice(
                id = RIGHT_ID,
                name = "Right",
                host = "192.0.2.1",
                port = 40_000,
                state = DeviceConnectManager.PeerState.CONNECTING,
                serviceName = "old-service",
            )
        assertEquals(
            null,
            peerAfterFailedDeviceConnectAttempt(
                attempted,
                attempted.host,
                attempted.port,
                attempted.serviceName,
            ),
        )

        val rediscovered = attempted.copy(serviceName = "new-service")
        assertEquals(
            rediscovered.copy(state = DeviceConnectManager.PeerState.DISCOVERED),
            peerAfterFailedDeviceConnectAttempt(
                rediscovered,
                attempted.host,
                attempted.port,
                attempted.serviceName,
            ),
        )

        val connected = attempted.copy(state = DeviceConnectManager.PeerState.CONNECTED)
        assertEquals(
            connected,
            peerAfterFailedDeviceConnectAttempt(
                connected,
                attempted.host,
                attempted.port,
                attempted.serviceName,
            ),
        )

        assertEquals(
            attempted.copy(state = DeviceConnectManager.PeerState.ERROR),
            peerAfterFailedDeviceConnectAttempt(
                attempted,
                attempted.host,
                attempted.port,
                attempted.serviceName,
                DeviceConnectManager.PeerState.ERROR,
            ),
        )
    }

    @Test
    fun delayedLossDoesNotRemoveARediscoveredOrConnectedEndpoint() {
        val old = DiscoveredDevice("old", RIGHT_ID, "", "192.0.2.1", 40_000)
        val discovered =
            DeviceConnectManager.PeerDevice(
                id = RIGHT_ID,
                name = "Right",
                host = old.host,
                port = old.port,
                state = DeviceConnectManager.PeerState.DISCOVERED,
                serviceName = old.serviceName,
            )

        assertEquals(null, peerAfterLostDeviceConnectEndpoint(discovered, old))
        assertEquals(
            discovered.copy(serviceName = "new"),
            peerAfterLostDeviceConnectEndpoint(discovered.copy(serviceName = "new"), old),
        )
        assertEquals(
            discovered.copy(port = 40_001),
            peerAfterLostDeviceConnectEndpoint(discovered.copy(port = 40_001), old),
        )
        val lostWhileConnected =
            peerAfterLostDeviceConnectEndpoint(
                discovered.copy(state = DeviceConnectManager.PeerState.CONNECTED),
                old,
            )
        assertEquals(
            discovered.copy(state = DeviceConnectManager.PeerState.CONNECTED, serviceName = ""),
            lostWhileConnected,
        )
        assertFalse(hasAvailableDeviceConnectEndpoint(requireNotNull(lostWhileConnected)))
        assertTrue(hasAvailableDeviceConnectEndpoint(discovered.copy(serviceName = "new")))

        assertEquals(
            null,
            peerAfterFailedDeviceConnectAttempt(
                discovered.copy(state = DeviceConnectManager.PeerState.CONNECTING, serviceName = ""),
                old.host,
                old.port,
                old.serviceName,
            ),
        )
    }

    @Test
    fun discoveryRestartDropsMissingPeerWithoutDisconnectingLivePeer() =
        runBlocking {
            val settings = Settings(MapSettings())
            val left =
                DeviceConnectManager(
                    settings = settings,
                    identityProvider = ::anonymousIdentity,
                    localInfoProvider = {
                        LEFT_INFO.copy(
                            deviceName = settings.deviceConnectDeviceName.ifBlank { LEFT_INFO.deviceName },
                        )
                    },
                )
            val right = manager(Settings(MapSettings()), ::anonymousIdentity, RIGHT_INFO)
            val link = MemoryLink()
            try {
                left.enable(Unit)
                connect(left, right, link)
                left.approvePairing(left.awaitPairingRequest(RIGHT_ID, requiresApproval = true).requestId)
                right.approvePairing(right.awaitPairingRequest(LEFT_ID, requiresApproval = true).requestId)
                left.awaitConnectedAndPaired(RIGHT_ID)
                right.awaitConnectedAndPaired(LEFT_ID)

                val missing =
                    DiscoveredDevice(
                        serviceName = "missing-service",
                        deviceId = "missing-device",
                        deviceName = "Missing",
                        host = "192.168.2.1",
                        port = 40_000,
                    )
                left.reportDiscoveredDeviceForTest(missing)
                left.awaitState { state -> state.peers.any { it.id == missing.deviceId } }

                left.updateDeviceName("Renamed")

                val state =
                    left.awaitState { state ->
                        state.localDeviceName == "Renamed" &&
                            state.peers.none { it.id == missing.deviceId } &&
                            state.isConnectedTo(RIGHT_ID)
                    }
                val connected = state.peers.single { it.id == RIGHT_ID }
                assertEquals(DeviceConnectManager.PeerState.CONNECTED, connected.state)
                assertTrue(connected.serviceName.isBlank())
                assertTrue(right.isConnectedTo(LEFT_ID))
                assertFalse(link.isClosed)
            } finally {
                release(left, right)
            }
        }

    @Test
    fun simultaneousDuplicateConnectionsKeepOneLiveLink() {
        runBlocking {
            val left = manager(Settings(MapSettings()), ::anonymousIdentity, LEFT_INFO)
            val right = manager(Settings(MapSettings()), ::anonymousIdentity, RIGHT_INFO)
            try {
                val original = connect(left, right)
                left.approvePairing(left.awaitPairingRequest(RIGHT_ID, requiresApproval = true).requestId)
                right.approvePairing(right.awaitPairingRequest(LEFT_ID, requiresApproval = true).requestId)
                left.awaitConnectedAndPaired(RIGHT_ID)
                right.awaitConnectedAndPaired(LEFT_ID)
                val links =
                    listOf(
                        original,
                        connect(right, left, expectedPeerId = LEFT_ID),
                        connect(left, right),
                        connect(right, left, expectedPeerId = LEFT_ID),
                    )
                withTimeout(TEST_TIMEOUT_MS.milliseconds) {
                    combine(links.map { it.closedState }) { closed -> closed.count { !it } }.first { it == 1 }
                }
                left.awaitConnectedAndPaired(RIGHT_ID)
                right.awaitConnectedAndPaired(LEFT_ID)
                left.forget(RIGHT_ID)
                links.forEach { it.awaitClosed() }
                right.awaitState { !it.isConnectedTo(LEFT_ID) }
            } finally {
                release(left, right)
            }
        }
    }

    @Test
    fun anonymousPairingRequiresBothApprovalsReconnectsAndForgetRequiresPairingAgain() =
        runBlocking {
            val leftSettings = Settings(MapSettings())
            val rightSettings = Settings(MapSettings())
            val identity = anonymousIdentity()

            val firstLeft = manager(leftSettings, { identity }, LEFT_INFO)
            val firstRight = manager(rightSettings, { identity }, RIGHT_INFO)
            try {
                connect(firstLeft, firstRight)
                val leftRequest = firstLeft.awaitPairingRequest(RIGHT_ID, requiresApproval = true)
                val rightRequest = firstRight.awaitPairingRequest(LEFT_ID, requiresApproval = true)

                assertTrue(leftRequest.sas.matches(SAS_PATTERN))
                assertEquals(leftRequest.sas, rightRequest.sas)

                firstLeft.approvePairing(leftRequest.requestId)
                firstLeft.awaitPairingRequest(RIGHT_ID, requiresApproval = false)
                assertFalse(firstLeft.isConnectedTo(RIGHT_ID))
                assertFalse(firstRight.isConnectedTo(LEFT_ID))

                firstRight.approvePairing(rightRequest.requestId)
                firstLeft.awaitConnectedAndPaired(RIGHT_ID)
                firstRight.awaitConnectedAndPaired(LEFT_ID)
                firstLeft.awaitState { state ->
                    state.localDeviceType == DeviceConnectDeviceType.DESKTOP &&
                        state.peers.any {
                            it.id == RIGHT_ID && it.deviceType == DeviceConnectDeviceType.ANDROID_PHONE
                        } &&
                        state.pairedDevices.any {
                            it.deviceId == RIGHT_ID && it.deviceType == DeviceConnectDeviceType.ANDROID_PHONE
                        }
                }
                firstRight.awaitState { state ->
                    state.peers.any {
                        it.id == LEFT_ID && it.deviceType == DeviceConnectDeviceType.DESKTOP
                    }
                }
            } finally {
                release(firstLeft, firstRight)
            }

            val restoredLeft = manager(leftSettings, { identity }, LEFT_INFO)
            val restoredRight = manager(rightSettings, { identity }, RIGHT_INFO)
            try {
                assertEquals(
                    DeviceConnectDeviceType.ANDROID_PHONE,
                    restoredLeft.state.value.pairedDevices
                        .single { it.deviceId == RIGHT_ID }
                        .deviceType,
                )
                assertEquals(
                    DeviceConnectDeviceType.DESKTOP,
                    restoredRight.state.value.pairedDevices
                        .single { it.deviceId == LEFT_ID }
                        .deviceType,
                )
                val reconnect = connect(restoredLeft, restoredRight)
                restoredLeft.awaitConnectedAndPaired(RIGHT_ID)
                restoredRight.awaitConnectedAndPaired(LEFT_ID)
                assertTrue(
                    restoredLeft.state.value.pairingRequests
                        .isEmpty(),
                )
                assertTrue(
                    restoredRight.state.value.pairingRequests
                        .isEmpty(),
                )

                restoredLeft.forget(RIGHT_ID)
                reconnect.awaitClosed()
                restoredLeft.awaitState { state ->
                    state.pairedDevices.none { it.deviceId == RIGHT_ID } && !state.isConnectedTo(RIGHT_ID)
                }
                restoredRight.awaitState { state -> !state.isConnectedTo(LEFT_ID) }

                connect(restoredLeft, restoredRight)
                val leftRequest = restoredLeft.awaitPairingRequest(RIGHT_ID, requiresApproval = true)
                val rightRequest = restoredRight.awaitPairingRequest(LEFT_ID, requiresApproval = true)
                assertEquals(leftRequest.sas, rightRequest.sas)
            } finally {
                release(restoredLeft, restoredRight)
            }
        }

    @Test
    fun forgetWinsAgainstAnInFlightPairingApproval() =
        runBlocking {
            val identity = anonymousIdentity()
            val leftSettings = Settings(MapSettings())
            val left = manager(leftSettings, { identity }, LEFT_INFO)
            val right = manager(Settings(MapSettings()), { identity }, RIGHT_INFO)
            val link = MemoryLink(gateLeftPairApprovalAfterSend = true)
            try {
                connect(left, right, link)
                val leftRequest = left.awaitPairingRequest(RIGHT_ID, requiresApproval = true)
                val rightRequest = right.awaitPairingRequest(LEFT_ID, requiresApproval = true)

                right.approvePairing(rightRequest.requestId)
                left.awaitState { state ->
                    state.log.any { it.message.contains("peer pairing approval verified") }
                }
                left.approvePairing(leftRequest.requestId)
                link.awaitLeftPairApprovalBlocked()

                val forgetJob = async { left.forget(RIGHT_ID) }
                link.awaitClosed()
                link.releaseLeftPairApproval()
                left.awaitState { state ->
                    state.log.any { it.message.contains("pairing commit skipped because the session is closing") }
                }
                forgetJob.await()

                assertTrue(DevicePairStore(leftSettings).pairsFor(identity).isEmpty())
                assertTrue(
                    left.state.value.pairedDevices
                        .none { it.deviceId == RIGHT_ID },
                )
            } finally {
                link.releaseLeftPairApproval()
                release(left, right)
            }
        }

    @Test
    fun approvalTargetsOnlyTheCurrentPendingConnection() =
        runBlocking {
            val left = manager(Settings(MapSettings()), { anonymousIdentity() }, LEFT_INFO)
            val right = manager(Settings(MapSettings()), { anonymousIdentity() }, RIGHT_INFO)
            try {
                val firstLink = connect(left, right)
                val staleRequest = left.awaitPairingRequest(RIGHT_ID, requiresApproval = true)
                firstLink.left.close()
                firstLink.awaitClosed()
                left.awaitState { it.pairingRequests.isEmpty() }
                right.awaitState { it.pairingRequests.isEmpty() }
                left.awaitSessionClosed(RIGHT_ID)
                right.awaitSessionClosed(LEFT_ID)

                connect(left, right)
                val currentRequest = left.awaitPairingRequest(RIGHT_ID, requiresApproval = true)
                val peerRequest = right.awaitPairingRequest(LEFT_ID, requiresApproval = true)
                assertNotEquals(staleRequest.requestId, currentRequest.requestId)
                left.approvePairing(staleRequest.requestId)
                right.approvePairing(peerRequest.requestId)
                right.awaitPairingRequest(LEFT_ID, requiresApproval = false)
                assertTrue(
                    left.state.value.pairingRequests
                        .single()
                        .requiresApproval,
                )
                assertFalse(left.isConnectedTo(RIGHT_ID))
                left.approvePairing(currentRequest.requestId)
                left.awaitConnectedAndPaired(RIGHT_ID)
                right.awaitConnectedAndPaired(LEFT_ID)
            } finally {
                release(left, right)
            }
        }

    @Test
    fun anonymousRejectionStoresNoGrant() =
        runBlocking {
            val identity = anonymousIdentity()
            val leftSettings = Settings(MapSettings())
            val rightSettings = Settings(MapSettings())
            val left = manager(leftSettings, { identity }, LEFT_INFO)
            val right = manager(rightSettings, { identity }, RIGHT_INFO)
            try {
                val link = connect(left, right)
                left.awaitPairingRequest(RIGHT_ID, requiresApproval = true)
                val request = right.awaitPairingRequest(LEFT_ID, requiresApproval = true)

                right.rejectPairing(request.requestId)
                link.awaitClosed()
                left.awaitState { it.pairingRequests.isEmpty() }
                right.awaitState { it.pairingRequests.isEmpty() }

                assertTrue(DevicePairStore(leftSettings).pairsFor(identity).isEmpty())
                assertTrue(DevicePairStore(rightSettings).pairsFor(identity).isEmpty())
                assertFalse(left.isConnectedTo(RIGHT_ID))
                assertFalse(right.isConnectedTo(LEFT_ID))
            } finally {
                release(left, right)
            }
        }

    @Test
    fun reconnectRechecksGrantAfterIdentity() =
        runBlocking {
            val identity = anonymousIdentity()
            val leftSettings = Settings(MapSettings())
            val rightSettings = Settings(MapSettings())
            val firstLeft = manager(leftSettings, { identity }, LEFT_INFO)
            val firstRight = manager(rightSettings, { identity }, RIGHT_INFO)
            try {
                connect(firstLeft, firstRight)
                val leftRequest = firstLeft.awaitPairingRequest(RIGHT_ID, requiresApproval = true)
                val rightRequest = firstRight.awaitPairingRequest(LEFT_ID, requiresApproval = true)
                firstLeft.approvePairing(leftRequest.requestId)
                firstRight.approvePairing(rightRequest.requestId)
                firstLeft.awaitConnectedAndPaired(RIGHT_ID)
                firstRight.awaitConnectedAndPaired(LEFT_ID)
            } finally {
                release(firstLeft, firstRight)
            }

            val restoredLeft = manager(leftSettings, { identity }, LEFT_INFO)
            val restoredRight = manager(rightSettings, { identity }, RIGHT_INFO)
            val gatedLink = MemoryLink(gateRightPairProof = true)
            try {
                connect(restoredLeft, restoredRight, gatedLink)
                gatedLink.awaitRightPairProofBlocked()

                DevicePairStore(leftSettings).forget(RIGHT_ID, identity)
                gatedLink.releaseRightPairProof()

                gatedLink.awaitClosed()
                restoredLeft.awaitState { !it.isConnectedTo(RIGHT_ID) }
                assertTrue(DevicePairStore(leftSettings).pairsFor(identity).none { it.peerDeviceId == RIGHT_ID })
            } finally {
                gatedLink.releaseRightPairProof()
                release(restoredLeft, restoredRight)
            }
        }

    @Test
    fun independentSameAccountLoginsRequireMutualCodeApprovalThenReconnectWithPairSecrets() =
        runBlocking {
            val leftSettings = Settings(MapSettings())
            val rightSettings = Settings(MapSettings())
            val leftIdentity = AccountIdentitySnapshot("login-a", "same-account", anonymous = false, resolved = true)
            val rightIdentity = leftIdentity.copy(secret = "login-b")
            val left = manager(leftSettings, { leftIdentity }, LEFT_INFO)
            val right = manager(rightSettings, { rightIdentity }, RIGHT_INFO)
            try {
                val link = connect(left, right)
                val leftRequest = left.awaitPairingRequest(RIGHT_ID, requiresApproval = true)
                val rightRequest = right.awaitPairingRequest(LEFT_ID, requiresApproval = true)
                assertTrue(leftRequest.sas.matches(SAS_PATTERN))
                assertEquals(leftRequest.sas, rightRequest.sas)
                assertFalse(left.isConnectedTo(RIGHT_ID))
                assertFalse(right.isConnectedTo(LEFT_ID))
                assertTrue(link.sentFrames.none { it.type == DcMessageTypes.DEVICE_INFO || it.type == DcMessageTypes.PLAYBACK })
                assertTrue(DevicePairStore(leftSettings).pairsFor(leftIdentity).isEmpty())
                assertTrue(DevicePairStore(rightSettings).pairsFor(rightIdentity).isEmpty())

                right.approvePairing(rightRequest.requestId)
                assertFalse(left.isConnectedTo(RIGHT_ID))
                left.approvePairing(leftRequest.requestId)
                left.awaitConnectedAndPaired(RIGHT_ID)
                right.awaitConnectedAndPaired(LEFT_ID)
            } finally {
                release(left, right)
            }

            val restoredLeft = manager(leftSettings, { leftIdentity.copy(secret = "renewed-a") }, LEFT_INFO)
            val restoredRight = manager(rightSettings, { rightIdentity.copy(secret = "renewed-b") }, RIGHT_INFO)
            try {
                val link = connect(restoredLeft, restoredRight)
                restoredLeft.awaitConnectedAndPaired(RIGHT_ID)
                restoredRight.awaitConnectedAndPaired(LEFT_ID)
                assertTrue(
                    restoredLeft.state.value.pairingRequests
                        .isEmpty(),
                )
                assertTrue(
                    restoredRight.state.value.pairingRequests
                        .isEmpty(),
                )
                assertEquals(2, link.sentFrames.count { it.type == DcMessageTypes.PAIR_PROOF })
                assertTrue(link.sentFrames.none { it.type == DcMessageTypes.PAIR_REQUEST })
            } finally {
                release(restoredLeft, restoredRight)
            }
        }

    @Test
    fun legacyCapabilityCannotReplaceCredentialProofWithPublicScope() =
        runBlocking {
            for (legacyInitiator in listOf(false, true)) {
                val leftIdentity = AccountIdentitySnapshot("login-a", "same-account", anonymous = false, resolved = true)
                val rightIdentity = leftIdentity.copy(secret = "login-b")
                val leftSettings = Settings(MapSettings())
                val rightSettings = Settings(MapSettings())
                val left = manager(leftSettings, { leftIdentity }, LEFT_INFO, supportsCodePairing = !legacyInitiator)
                val right = manager(rightSettings, { rightIdentity }, RIGHT_INFO, supportsCodePairing = legacyInitiator)
                try {
                    val link = connect(left, right)
                    link.awaitClosed()
                    assertFalse(left.isConnectedTo(RIGHT_ID))
                    assertFalse(right.isConnectedTo(LEFT_ID))
                    assertTrue(
                        left.state.value.pairingRequests
                            .isEmpty(),
                    )
                    assertTrue(
                        right.state.value.pairingRequests
                            .isEmpty(),
                    )
                    assertTrue(DevicePairStore(leftSettings).pairsFor(leftIdentity).isEmpty())
                    assertTrue(DevicePairStore(rightSettings).pairsFor(rightIdentity).isEmpty())
                    assertTrue(link.sentFrames.none { it.type == DcMessageTypes.PAIR_APPROVE })
                } finally {
                    release(left, right)
                }
            }
        }

    @Test
    fun savedLegacyPairReconnectsWithModernIndependentLoginsButWrongPairSecretFails() =
        runBlocking {
            val leftIdentity = AccountIdentitySnapshot("login-a", "same-account", anonymous = false, resolved = true)
            val rightIdentity = leftIdentity.copy(secret = "login-b")
            for (matchingGrant in listOf(true, false)) {
                val leftSettings = Settings(MapSettings())
                val rightSettings = Settings(MapSettings())
                val secret = ByteArray(DEVICE_CONNECT_PAIR_SECRET_BYTES) { 7 }
                DevicePairStore(leftSettings).save(RIGHT_ID, RIGHT_INFO.deviceName, RIGHT_INFO.model, leftIdentity, secret)
                DevicePairStore(rightSettings).save(
                    LEFT_ID,
                    LEFT_INFO.deviceName,
                    LEFT_INFO.model,
                    rightIdentity,
                    if (matchingGrant) secret else ByteArray(DEVICE_CONNECT_PAIR_SECRET_BYTES) { 8 },
                )
                val left = manager(leftSettings, { leftIdentity }, LEFT_INFO)
                val right = manager(rightSettings, { rightIdentity }, RIGHT_INFO)
                try {
                    val link = connect(left, right)
                    if (matchingGrant) {
                        left.awaitConnectedAndPaired(RIGHT_ID)
                        right.awaitConnectedAndPaired(LEFT_ID)
                    } else {
                        link.awaitClosed()
                        assertFalse(left.isConnectedTo(RIGHT_ID))
                        assertFalse(right.isConnectedTo(LEFT_ID))
                    }
                    assertTrue(
                        left.state.value.pairingRequests
                            .isEmpty(),
                    )
                    assertTrue(
                        right.state.value.pairingRequests
                            .isEmpty(),
                    )
                    assertTrue(link.sentFrames.none { it.type == DcMessageTypes.PAIR_REQUEST })
                } finally {
                    release(left, right)
                }
            }
        }

    @Test
    fun legacySignedPairingRejectsDifferentAccountAndReconnectsAfterSwitchingBack() =
        runBlocking {
            val originalAccount = signedIdentity("original-account")
            val otherAccount = signedIdentity("other-account")
            var leftIdentity = originalAccount
            val leftSettings = Settings(MapSettings())
            val rightSettings = Settings(MapSettings())
            val left = manager(leftSettings, { leftIdentity }, LEFT_INFO)
            val right = manager(rightSettings, { originalAccount }, RIGHT_INFO, supportsCodePairing = false)
            val song = SongItem("song", "Song", null, emptyList(), null)
            left.remote.attach(
                MemoryPlaybackPort(DcLocalPlaybackState(queue = MusicQueueSnapshot(listOf(song), listOf(song), 0))),
            )
            right.remote.attach(MemoryPlaybackPort(DcLocalPlaybackState()))
            try {
                val link = connect(left, right)
                val initiatorRequest = left.awaitPairingRequest(RIGHT_ID, requiresApproval = false)
                val receiverRequest = right.awaitPairingRequest(LEFT_ID, requiresApproval = true)
                assertTrue(initiatorRequest.sas.isEmpty())
                assertTrue(receiverRequest.sas.isEmpty())

                right.approvePairing(receiverRequest.requestId)
                left.awaitConnectedAndPaired(RIGHT_ID)
                right.awaitConnectedAndPaired(LEFT_ID)
                val probe = DcSyncMessage(DcSyncMessageType.UPDATE)
                link.awaitProcessed { left.sendPlayback(RIGHT_ID, probe) }
                link.awaitProcessed { right.sendPlayback(LEFT_ID, probe) }
                left.remote.transferTo(RIGHT_ID)
                withTimeout(TEST_TIMEOUT_MS.milliseconds) { right.remote.state.first { it.sessionId != null } }

                leftIdentity = otherAccount
                left.remote.intercept(DcPlaybackCommand(DcPlaybackAction.PAUSE))
                withTimeout(TEST_TIMEOUT_MS.milliseconds) { left.remote.state.first { it.sessionId == null } }
                left.awaitState { state ->
                    state.pairedDevices.none { it.deviceId == RIGHT_ID } && !state.isConnectedTo(RIGHT_ID)
                }
                right.awaitState { state -> !state.isConnectedTo(LEFT_ID) }

                val mismatch = connect(left, right)
                mismatch.awaitClosed()
                left.awaitState { state ->
                    state.peers.any { it.id == RIGHT_ID && it.state == DeviceConnectManager.PeerState.ACCOUNT_MISMATCH }
                }
                assertTrue(
                    left.state.value.pairingRequests
                        .isEmpty(),
                )
                assertTrue(
                    right.state.value.pairingRequests
                        .isEmpty(),
                )

                leftIdentity = originalAccount
                left.refreshPairingState()
                left.awaitState { state -> state.pairedDevices.any { it.deviceId == RIGHT_ID } }

                connect(left, right)
                left.awaitConnectedAndPaired(RIGHT_ID)
                right.awaitConnectedAndPaired(LEFT_ID)
                assertTrue(
                    left.state.value.pairingRequests
                        .isEmpty(),
                )
                assertTrue(
                    right.state.value.pairingRequests
                        .isEmpty(),
                )
            } finally {
                release(left, right)
            }
        }

    @Test
    fun unresolvedAccountsRemainVisibleAndCanPairAfterIdentityRecovery() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val resolved = signedIdentity("same-account")
            val unresolved = AccountIdentitySnapshot("unresolved", "", anonymous = false, resolved = false)
            for ((initialLeft, initialRight) in listOf(unresolved to resolved, resolved to unresolved, unresolved to unresolved)) {
                var leftIdentity = initialLeft
                var rightIdentity = initialRight
                val leftSettings = Settings(MapSettings())
                val rightSettings = Settings(MapSettings())
                val left = manager(leftSettings, { leftIdentity }, LEFT_INFO, dispatcher = dispatcher)
                val right = manager(rightSettings, { rightIdentity }, RIGHT_INFO, dispatcher = dispatcher)
                try {
                    val link = MemoryLink(leftRemoteHost = "192.168.1.10", rightRemoteHost = "192.168.1.11")
                    val discoveryReady = CompletableDeferred<Unit>()
                    val rightChannel =
                        object : DeviceChannel by link.right {
                            override suspend fun send(text: String) {
                                discoveryReady.await()
                                link.right.send(text)
                            }
                        }
                    left.attachChannelForTest(link.left, isIncoming = false, expectedPeerId = RIGHT_ID)
                    right.attachChannelForTest(rightChannel, isIncoming = true)
                    val rightDevice = DiscoveredDevice("right-service", RIGHT_ID, "Right", "192.168.1.10", 40_000)
                    left.reportDiscoveredDeviceForTest(rightDevice)
                    right.reportDiscoveredDeviceForTest(DiscoveredDevice("left-service", LEFT_ID, "Left", "192.168.1.11", 40_001))
                    discoveryReady.complete(Unit)
                    link.awaitClosed()
                    left.awaitSessionClosed(RIGHT_ID)
                    right.awaitSessionClosed(LEFT_ID)
                    assertEquals(
                        DeviceConnectManager.PeerState.ACCOUNT_UNRESOLVED,
                        right.state.value.peers
                            .single { it.id == LEFT_ID }
                            .state,
                        right.state.value.log
                            .toString(),
                    )
                    left.reportDiscoveredDeviceForTest(rightDevice)
                    assertEquals(
                        DeviceConnectManager.PeerState.ACCOUNT_UNRESOLVED,
                        left.state.value.peers
                            .single { it.id == RIGHT_ID }
                            .state,
                    )
                    assertTrue(
                        left.state.value.pairingRequests
                            .isEmpty(),
                    )
                    assertTrue(
                        right.state.value.pairingRequests
                            .isEmpty(),
                    )
                    assertTrue(DevicePairStore(leftSettings).pairsFor(resolved).isEmpty())
                    assertTrue(DevicePairStore(rightSettings).pairsFor(resolved).isEmpty())

                    leftIdentity = resolved
                    rightIdentity = resolved
                    left.refreshPairingState()
                    right.refreshPairingState()
                    pairAndAwaitPlaybackLink(left, right)
                } finally {
                    try {
                        left.release()
                    } finally {
                        right.release()
                    }
                }
            }
        }

    @Test
    fun newPairingRejectsDifferentAndMixedAccountStates() =
        runBlocking {
            val cases =
                listOf(
                    signedIdentity("left-account") to signedIdentity("right-account"),
                    AccountIdentitySnapshot(
                        secret = "same-credential",
                        fingerprint = "account-a",
                        anonymous = false,
                        resolved = true,
                    ) to
                        AccountIdentitySnapshot(
                            secret = "same-credential",
                            fingerprint = "account-b",
                            anonymous = false,
                            resolved = true,
                        ),
                    signedIdentity("left-account") to anonymousIdentity(),
                    AccountIdentitySnapshot("unresolved", "", anonymous = false, resolved = false) to
                        signedIdentity("right-account"),
                    AccountIdentitySnapshot("unresolved-a", "", anonymous = false, resolved = false) to
                        AccountIdentitySnapshot("unresolved-b", "", anonymous = false, resolved = false),
                )
            for ((leftIdentity, rightIdentity) in cases) {
                val leftSettings = Settings(MapSettings())
                val rightSettings = Settings(MapSettings())
                val left = manager(leftSettings, { leftIdentity }, LEFT_INFO)
                val right = manager(rightSettings, { rightIdentity }, RIGHT_INFO)
                try {
                    connect(left, right).awaitClosed()
                    val expected =
                        if (!leftIdentity.resolved || !rightIdentity.resolved) {
                            DeviceConnectManager.PeerState.ACCOUNT_UNRESOLVED
                        } else {
                            DeviceConnectManager.PeerState.ACCOUNT_MISMATCH
                        }
                    left.awaitState { state -> state.peers.any { it.id == RIGHT_ID && it.state == expected } }
                    assertTrue(DevicePairStore(leftSettings).pairsFor(leftIdentity).isEmpty())
                    assertTrue(DevicePairStore(rightSettings).pairsFor(rightIdentity).isEmpty())
                    assertTrue(
                        left.state.value.pairingRequests
                            .isEmpty(),
                    )
                    assertTrue(
                        right.state.value.pairingRequests
                            .isEmpty(),
                    )
                } finally {
                    release(left, right)
                }
            }
        }

    @Test
    fun oneSidedStorageFailureRollsBackTheOtherGrant() =
        runBlocking {
            val identity = anonymousIdentity()
            val leftSettings = Settings(MapSettings())
            val rightSettings = Settings(FailingPairSettings())
            val left = manager(leftSettings, { identity }, LEFT_INFO)
            val right = manager(rightSettings, { identity }, RIGHT_INFO)
            try {
                val link = connect(left, right)
                val leftRequest = left.awaitPairingRequest(RIGHT_ID, requiresApproval = true)
                val rightRequest = right.awaitPairingRequest(LEFT_ID, requiresApproval = true)

                left.approvePairing(leftRequest.requestId)
                left.awaitPairingRequest(RIGHT_ID, requiresApproval = false)
                right.approvePairing(rightRequest.requestId)

                link.awaitClosed()
                left.awaitState { state ->
                    state.log.any { it.message.contains("unconfirmed local pairing grant was rolled back") }
                }

                assertTrue(DevicePairStore(leftSettings).pairsFor(identity).isEmpty())
                assertTrue(DevicePairStore(rightSettings).pairsFor(identity).isEmpty())
                assertFalse(left.isConnectedTo(RIGHT_ID))
                assertFalse(right.isConnectedTo(LEFT_ID))
            } finally {
                release(left, right)
            }
        }

    @Test
    fun transferMovesPlaybackToTargetAndControllerCommandsRouteThere() =
        runBlocking<Unit> {
            val initial = playingState()
            val leftPort = MemoryPlaybackPort(initial)
            val rightPort = MemoryPlaybackPort(DcLocalPlaybackState())
            val left = manager(Settings(MapSettings()), ::anonymousIdentity, LEFT_INFO)
            val right = manager(Settings(MapSettings()), ::anonymousIdentity, RIGHT_INFO)
            try {
                left.remote.attach(leftPort)
                right.remote.attach(rightPort)
                pairAndAwaitPlaybackLink(left, right)

                left.remote.transferTo(RIGHT_ID)
                withTimeout(TEST_TIMEOUT_MS.milliseconds) {
                    rightPort.state.first { it.queue == initial.queue && it.playing }
                    right.remote.state.first { it.targetId == RIGHT_ID && it.controllerIds == setOf(LEFT_ID) }
                    left.remote.state.first { it.targetId == RIGHT_ID }
                }
                assertEquals(right.remote.state.value.sessionId, left.remote.state.value.sessionId)
                assertTrue(left.remote.isFollowing)
                assertFalse(right.remote.isFollowing)
                assertFalse(leftPort.audioOutputEnabled.value)
                assertTrue(rightPort.audioOutputEnabled.value)
                assertEquals(initial.queue, leftPort.state.value.queue)

                assertTrue(left.remote.intercept(DcPlaybackCommand(DcPlaybackAction.SEEK, positionMs = 9_876)))
                withTimeout(TEST_TIMEOUT_MS.milliseconds) { rightPort.state.first { it.positionMs in 9_876L..20_000L } }
                assertTrue(left.remote.intercept(DcPlaybackCommand(DcPlaybackAction.PAUSE)))
                withTimeout(TEST_TIMEOUT_MS.milliseconds) {
                    rightPort.state.first { !it.playing }
                    leftPort.state.first { !it.playing }
                }
            } finally {
                release(left, right)
            }
        }

    @Test
    fun pullingBackReturnsPlaybackToThisDeviceAndEndsTheSession() =
        runBlocking<Unit> {
            val initial = playingState()
            val leftPort = MemoryPlaybackPort(initial)
            val rightPort = MemoryPlaybackPort(DcLocalPlaybackState())
            val left = manager(Settings(MapSettings()), ::anonymousIdentity, LEFT_INFO)
            val right = manager(Settings(MapSettings()), ::anonymousIdentity, RIGHT_INFO)
            try {
                left.remote.attach(leftPort)
                right.remote.attach(rightPort)
                pairAndAwaitPlaybackLink(left, right)
                left.remote.transferTo(RIGHT_ID)
                withTimeout(TEST_TIMEOUT_MS.milliseconds) { left.remote.state.first { it.targetId == RIGHT_ID && it.sessionId != null } }
                withTimeout(TEST_TIMEOUT_MS.milliseconds) { rightPort.state.first { it.playing } }

                left.remote.transferTo(LEFT_ID)
                withTimeout(TEST_TIMEOUT_MS.milliseconds) {
                    left.remote.state.first { it.sessionId == null }
                    right.remote.state.first { it.sessionId == null }
                    rightPort.state.first { !it.playing }
                    leftPort.audioOutputEnabled.first { it }
                    leftPort.state.first { it.playing && it.queue == initial.queue }
                }
                assertEquals(initial.queue, leftPort.state.value.queue)
                assertFalse(left.remote.isFollowing)
            } finally {
                release(left, right)
            }
        }

    @Test
    fun losingTheTargetRestoresLocalOutputWhileTheTargetKeepsPlaying() =
        runBlocking<Unit> {
            val leftPort = MemoryPlaybackPort(playingState())
            val rightPort = MemoryPlaybackPort(DcLocalPlaybackState())
            val left = manager(Settings(MapSettings()), ::anonymousIdentity, LEFT_INFO)
            val right = manager(Settings(MapSettings()), ::anonymousIdentity, RIGHT_INFO)
            try {
                left.remote.attach(leftPort)
                right.remote.attach(rightPort)
                val link = pairAndAwaitPlaybackLink(left, right)
                left.remote.transferTo(RIGHT_ID)
                withTimeout(TEST_TIMEOUT_MS.milliseconds) {
                    left.remote.state.first { it.targetId == RIGHT_ID && it.sessionId != null }
                    rightPort.state.first { it.playing }
                }

                link.left.close()
                link.awaitClosed()
                withTimeout(TEST_TIMEOUT_MS.milliseconds) {
                    left.remote.state.first { it.sessionId == null }
                    right.remote.state.first { it.sessionId == null }
                }
                assertTrue(leftPort.audioOutputEnabled.value)
                assertFalse(leftPort.state.value.playing)
                assertTrue(rightPort.state.value.playing)
                assertTrue(
                    left.state.value.pairedDevices
                        .any { it.deviceId == RIGHT_ID },
                )
            } finally {
                release(left, right)
            }
        }

    @Test
    fun idleDeviceMirrorsAPlayingPeerWithoutAManualPick() =
        runBlocking<Unit> {
            val initial = playingState()
            val leftPort = MemoryPlaybackPort(initial)
            val rightPort = MemoryPlaybackPort(DcLocalPlaybackState())
            val left = manager(Settings(MapSettings()), ::anonymousIdentity, LEFT_INFO)
            val right = manager(Settings(MapSettings()), ::anonymousIdentity, RIGHT_INFO)
            try {
                left.remote.attach(leftPort)
                right.remote.attach(rightPort)
                pairAndAwaitPlaybackLink(left, right)

                right.remote.discoverPlayback()
                withTimeout(TEST_TIMEOUT_MS.milliseconds) {
                    left.remote.state.first { it.targetId == LEFT_ID && it.controllerIds == setOf(RIGHT_ID) }
                    right.remote.state.first { it.targetId == LEFT_ID && it.sessionId != null }
                    rightPort.state.first { it.queue == initial.queue }
                }
                assertFalse(rightPort.audioOutputEnabled.value)
                assertTrue(leftPort.audioOutputEnabled.value)
            } finally {
                release(left, right)
            }
        }

    @Test
    fun mirrorStaysMutedAndFollowsTheTargetsRealPosition() =
        runBlocking<Unit> {
            val initial = playingState()
            val leftPort = MemoryPlaybackPort(initial)
            val rightPort = MemoryPlaybackPort(DcLocalPlaybackState())
            val left = manager(Settings(MapSettings()), ::anonymousIdentity, LEFT_INFO)
            val right = manager(Settings(MapSettings()), ::anonymousIdentity, RIGHT_INFO)
            try {
                left.remote.attach(leftPort)
                right.remote.attach(rightPort)
                pairAndAwaitPlaybackLink(left, right)
                right.remote.discoverPlayback()
                withTimeout(TEST_TIMEOUT_MS.milliseconds) { rightPort.state.first { it.queue == initial.queue } }

                rightPort.audioOutputEnabled.value = true
                leftPort.state.value = leftPort.state.value.copy(positionMs = 200_000L)
                withTimeout(TEST_TIMEOUT_MS.milliseconds) {
                    rightPort.audioOutputEnabled.first { !it }
                    rightPort.state.first { it.positionMs >= 190_000L }
                }
            } finally {
                release(left, right)
            }
        }

    @Test
    fun controllerDoesNotAttemptMediaLoads() =
        runBlocking<Unit> {
            val initial = playingState()
            val leftPort = MemoryPlaybackPort(initial)
            val rightPort = MemoryPlaybackPort(DcLocalPlaybackState()).apply { failApplies = 1 }
            val left = manager(Settings(MapSettings()), ::anonymousIdentity, LEFT_INFO)
            val right = manager(Settings(MapSettings()), ::anonymousIdentity, RIGHT_INFO)
            try {
                left.remote.attach(leftPort)
                right.remote.attach(rightPort)
                pairAndAwaitPlaybackLink(left, right)

                right.remote.discoverPlayback()
                withTimeout(TEST_TIMEOUT_MS.milliseconds) {
                    rightPort.state.first { it.queue == initial.queue }
                }
                assertEquals(1, rightPort.failApplies)
            } finally {
                release(left, right)
            }
        }

    @Test
    fun localFileQueuesCannotBeTransferred() =
        runBlocking<Unit> {
            val localSong = SongItem("local_1", "Local", null, emptyList(), null, duration = 300)
            val leftPort =
                MemoryPlaybackPort(
                    DcLocalPlaybackState(
                        queue = MusicQueueSnapshot(listOf(localSong), listOf(localSong), 0, originalIndices = listOf(0)),
                        playing = true,
                    ),
                )
            val rightPort = MemoryPlaybackPort(DcLocalPlaybackState())
            val left = manager(Settings(MapSettings()), ::anonymousIdentity, LEFT_INFO)
            val right = manager(Settings(MapSettings()), ::anonymousIdentity, RIGHT_INFO)
            try {
                left.remote.attach(leftPort)
                right.remote.attach(rightPort)
                pairAndAwaitPlaybackLink(left, right)

                left.remote.transferTo(RIGHT_ID)
                withTimeout(TEST_TIMEOUT_MS.milliseconds) { left.remote.state.first { it.error == DcSyncError.UNSHAREABLE_QUEUE } }
                assertFalse(left.remote.isActive)
                assertTrue(leftPort.audioOutputEnabled.value)
                assertTrue(
                    rightPort.state.value.queue.displayedItems
                        .isEmpty(),
                )
            } finally {
                release(left, right)
            }
        }

    @Test
    fun forgettingTheDefaultTargetClearsPreferenceAndEndsRemoteControl() =
        runBlocking<Unit> {
            val leftPort = MemoryPlaybackPort(playingState())
            val rightPort = MemoryPlaybackPort(DcLocalPlaybackState())
            val settings = Settings(MapSettings()).apply { deviceConnectDefaultDeviceId = RIGHT_ID }
            val left = manager(settings, ::anonymousIdentity, LEFT_INFO)
            val right = manager(Settings(MapSettings()), ::anonymousIdentity, RIGHT_INFO)
            try {
                left.remote.attach(leftPort)
                right.remote.attach(rightPort)
                val link = pairAndAwaitPlaybackLink(left, right)
                link.awaitProcessed { left.remote.willStartRemotePlayback }
                assertTrue(left.remote.intercept(DcPlaybackCommand(DcPlaybackAction.PLAY)))
                withTimeout(TEST_TIMEOUT_MS.milliseconds) { left.remote.state.first { it.targetId == RIGHT_ID && it.sessionId != null } }

                left.forget(RIGHT_ID)
                link.awaitClosed()
                withTimeout(TEST_TIMEOUT_MS.milliseconds) {
                    left.remote.state.first { it.sessionId == null }
                    right.remote.state.first { it.sessionId == null }
                }
                assertEquals("", settings.deviceConnectDefaultDeviceId)
                assertFalse(left.remote.willStartRemotePlayback)
                assertFalse(left.remote.intercept(DcPlaybackCommand(DcPlaybackAction.PLAY)))
                assertTrue(leftPort.audioOutputEnabled.value)
                assertTrue(
                    left.state.value.pairedDevices
                        .none { it.deviceId == RIGHT_ID },
                )
            } finally {
                release(left, right)
            }
        }

    @Test
    fun discoveredPairedDeviceAutoConnectsButUnpairedNeverDoes() =
        runBlocking<Unit> {
            val settings = Settings(MapSettings())
            val identity = anonymousIdentity()
            val firstLeft = manager(settings, { identity }, LEFT_INFO)
            val firstRight = manager(Settings(MapSettings()), { identity }, RIGHT_INFO)
            try {
                connect(firstLeft, firstRight)
                firstLeft.approvePairing(firstLeft.awaitPairingRequest(RIGHT_ID, requiresApproval = true).requestId)
                firstRight.approvePairing(firstRight.awaitPairingRequest(LEFT_ID, requiresApproval = true).requestId)
                firstLeft.awaitConnectedAndPaired(RIGHT_ID)
                firstRight.awaitConnectedAndPaired(LEFT_ID)
            } finally {
                release(firstLeft, firstRight)
            }

            val restored = manager(settings, { identity }, LEFT_INFO)
            try {
                restored.enable(Unit)
                restored.awaitState { state -> state.pairedDevices.any { it.deviceId == RIGHT_ID } }

                val paired = DiscoveredDevice("right-service", RIGHT_ID, "Right", "192.168.1.10", 40_000)
                assertTrue(restored.reportDiscoveredDeviceForTest(paired))

                val unpaired = DiscoveredDevice("other-service", "other-device", "Other", "192.168.1.11", 40_001)
                assertFalse(restored.reportDiscoveredDeviceForTest(unpaired))
            } finally {
                release(restored)
            }
        }
}

private fun manager(
    settings: Settings,
    identityProvider: () -> AccountIdentitySnapshot,
    info: DeviceInfoPayload,
    supportsCodePairing: Boolean = true,
    supportsPlaybackClock: Boolean = true,
    supportsPlaybackPitch: Boolean = true,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
): DeviceConnectManager =
    DeviceConnectManager(
        settings = settings,
        identityProvider = identityProvider,
        localInfoProvider = { info },
        supportsCodePairing = supportsCodePairing,
        supportsPlaybackClock = supportsPlaybackClock,
        supportsPlaybackPitch = supportsPlaybackPitch,
        dispatcher = dispatcher,
    )

private suspend fun connect(
    left: DeviceConnectManager,
    right: DeviceConnectManager,
    link: MemoryLink = MemoryLink(),
    expectedPeerId: String = RIGHT_ID,
): MemoryLink =
    link.also {
        left.attachChannelForTest(link.left, isIncoming = false, expectedPeerId = expectedPeerId)
        right.attachChannelForTest(link.right, isIncoming = true)
    }

private fun playingState(): DcLocalPlaybackState {
    val song = SongItem("song", "Song", null, emptyList(), null, duration = 300)
    return DcLocalPlaybackState(
        queue = MusicQueueSnapshot(listOf(song), listOf(song), 0, originalIndices = listOf(0)),
        playing = true,
        positionMs = 1_000L,
        durationMs = 300_000L,
        ready = true,
    )
}

private suspend fun pairAndAwaitPlaybackLink(
    left: DeviceConnectManager,
    right: DeviceConnectManager,
): MemoryLink {
    val link = connect(left, right)
    left.approvePairing(left.awaitPairingRequest(RIGHT_ID, requiresApproval = true).requestId)
    right.approvePairing(right.awaitPairingRequest(LEFT_ID, requiresApproval = true).requestId)
    left.awaitConnectedAndPaired(RIGHT_ID)
    right.awaitConnectedAndPaired(LEFT_ID)
    val probe = DcSyncMessage(DcSyncMessageType.UPDATE)
    link.awaitProcessed { left.sendPlayback(RIGHT_ID, probe) }
    link.awaitProcessed { right.sendPlayback(LEFT_ID, probe) }
    return link
}

private suspend fun DeviceConnectManager.awaitPairingRequest(
    peerId: String,
    requiresApproval: Boolean,
): DeviceConnectManager.PairingRequest =
    awaitState { state ->
        state.pairingRequests.any { it.deviceId == peerId && it.requiresApproval == requiresApproval }
    }.pairingRequests.single { it.deviceId == peerId }

private suspend fun DeviceConnectManager.awaitConnectedAndPaired(peerId: String) {
    awaitState { state ->
        state.isConnectedTo(peerId) &&
            state.pairedDevices.any { it.deviceId == peerId } &&
            state.pairingRequests.none { it.deviceId == peerId }
    }
}

private suspend fun DeviceConnectManager.awaitState(
    predicate: suspend (DeviceConnectManager.State) -> Boolean,
): DeviceConnectManager.State = withTimeout(TEST_TIMEOUT_MS.milliseconds) { state.first(predicate) }

private suspend fun DeviceConnectManager.awaitSessionClosed(peerId: String) {
    withTimeout(TEST_TIMEOUT_MS.milliseconds) { awaitSessionClosedForTest(peerId) }
}

private fun DeviceConnectManager.isConnectedTo(peerId: String): Boolean = state.value.isConnectedTo(peerId)

private fun DeviceConnectManager.State.isConnectedTo(peerId: String): Boolean =
    peers.any { it.id == peerId && it.state == DeviceConnectManager.PeerState.CONNECTED }

private suspend fun release(vararg managers: DeviceConnectManager) {
    managers.forEach { manager -> runCatching { manager.release() } }
}

private fun anonymousIdentity() =
    AccountIdentitySnapshot(
        secret = "",
        fingerprint = "",
        anonymous = true,
        resolved = true,
    )

private fun signedIdentity(secret: String) =
    AccountIdentitySnapshot(
        secret = secret,
        fingerprint = AccountIdentity.accountFingerprint(secret),
        anonymous = false,
        resolved = true,
    )

private class MemoryLink(
    gateRightPairProof: Boolean = false,
    gateLeftPairApprovalAfterSend: Boolean = false,
    leftRemoteHost: String = "memory-right",
    rightRemoteHost: String = "memory-left",
) {
    val sentFrames = ConcurrentLinkedQueue<DcWire>()
    private val processedFrames = MutableStateFlow(0L)
    val closedState = MutableStateFlow(false)
    val sentFrameSizes = ConcurrentLinkedQueue<Int>()
    private val leftInbox = Channel<String>(Channel.UNLIMITED)
    private val rightInbox = Channel<String>(Channel.UNLIMITED)
    private val closed = CompletableDeferred<Unit>()
    private val rightPairProofBlocked = CompletableDeferred<Unit>()
    private val rightPairProofRelease = CompletableDeferred<Unit>()
    private val leftPairApprovalBlocked = CompletableDeferred<Unit>()
    private val leftPairApprovalRelease = CompletableDeferred<Unit>()
    val isClosed: Boolean get() = closed.isCompleted

    val left: DeviceChannel =
        Endpoint(
            leftInbox,
            rightInbox,
            leftRemoteHost,
            gatePairProof = false,
            gatePairApprovalAfterSend = gateLeftPairApprovalAfterSend,
        )
    val right: DeviceChannel =
        Endpoint(
            rightInbox,
            leftInbox,
            rightRemoteHost,
            gatePairProof = gateRightPairProof,
            gatePairApprovalAfterSend = false,
        )

    suspend fun awaitClosed() {
        withTimeout(TEST_TIMEOUT_MS.milliseconds) { closed.await() }
    }

    suspend fun awaitProcessed(predicate: suspend () -> Boolean) {
        withTimeout(TEST_TIMEOUT_MS.milliseconds) { processedFrames.first { predicate() } }
    }

    suspend fun awaitRightPairProofBlocked() {
        withTimeout(TEST_TIMEOUT_MS.milliseconds) { rightPairProofBlocked.await() }
    }

    fun releaseRightPairProof() {
        rightPairProofRelease.complete(Unit)
    }

    suspend fun awaitLeftPairApprovalBlocked() {
        withTimeout(TEST_TIMEOUT_MS.milliseconds) { leftPairApprovalBlocked.await() }
    }

    fun releaseLeftPairApproval() {
        leftPairApprovalRelease.complete(Unit)
    }

    private fun closeLink() {
        if (!closed.complete(Unit)) return
        closedState.value = true
        leftInbox.close()
        rightInbox.close()
    }

    private inner class Endpoint(
        inbox: Channel<String>,
        private val outbox: Channel<String>,
        override val remoteHost: String,
        private val gatePairProof: Boolean,
        private val gatePairApprovalAfterSend: Boolean,
    ) : DeviceChannel {
        override val incoming: Flow<String> =
            flow {
                for (message in inbox) {
                    emit(message)
                    processedFrames.update { it + 1 }
                }
            }

        override suspend fun send(text: String) {
            sentFrames.add(DcJson.decodeFromString<DcWire>(text))
            sentFrameSizes.add(text.encodeToByteArray().size)
            if (gatePairProof && DcJson.decodeFromString<DcWire>(text).type == DcMessageTypes.PAIR_PROOF) {
                rightPairProofBlocked.complete(Unit)
                rightPairProofRelease.await()
            }
            if (
                gatePairApprovalAfterSend &&
                DcJson.decodeFromString<DcWire>(text).type == DcMessageTypes.PAIR_APPROVE
            ) {
                outbox.send(text)
                leftPairApprovalBlocked.complete(Unit)
                leftPairApprovalRelease.await()
                return
            }
            outbox.send(text)
        }

        override suspend fun close() = closeLink()
    }
}

private class PlaybackSendChannel(
    private val delegate: DeviceChannel,
) : DeviceChannel by delegate {
    @Volatile var beforePlaybackSend: suspend () -> Unit = {}

    override suspend fun send(text: String) {
        if (DcJson.decodeFromString<DcWire>(text).type == DcMessageTypes.PLAYBACK) beforePlaybackSend()
        delegate.send(text)
    }
}

private class MemoryPlaybackPort(
    initial: DcLocalPlaybackState,
) : DeviceConnectPlaybackPort {
    val state = MutableStateFlow(initial)
    val audioOutputEnabled = MutableStateFlow(true)
    var failApplies = 0

    override suspend fun snapshot(): DcLocalPlaybackState = state.value

    override suspend fun apply(
        snapshot: DcPlaybackSnapshot,
        positionMs: Long,
        shouldCommit: () -> Boolean,
    ) {
        if (failApplies > 0) {
            failApplies--
            throw IOException("load failed")
        }
        if (shouldCommit()) state.value = snapshot.playback.copy(positionMs = positionMs)
    }

    private var controlling = false

    override suspend fun setControllerPlayback(playback: DcLocalPlaybackState?) {
        if (playback != null || controlling) state.value = playback ?: state.value.copy(playing = false)
        controlling = playback != null
    }

    override suspend fun setPlaying(playing: Boolean) {
        state.value = state.value.copy(playing = playing)
    }

    override suspend fun seekTo(positionMs: Long) {
        state.value = state.value.copy(positionMs = positionMs)
    }

    override suspend fun setSpeed(speed: Float) {
        state.value = state.value.copy(speed = speed)
    }

    override suspend fun setVolume(volume: Float) {
        state.value = state.value.copy(volume = volume)
    }

    override suspend fun setAudioOutputEnabled(enabled: Boolean) {
        audioOutputEnabled.value = enabled
    }
}

private class ProbeChannel : DeviceChannel {
    private val inbox = Channel<String>(Channel.UNLIMITED)
    private val outbox = Channel<String>(Channel.UNLIMITED)
    private val closed = CompletableDeferred<Unit>()

    override val remoteHost: String = "probe"
    override val incoming: Flow<String> = inbox.receiveAsFlow()

    override suspend fun send(text: String) {
        outbox.send(text)
    }

    override suspend fun close() {
        if (!closed.complete(Unit)) return
        inbox.close()
        outbox.close()
    }

    suspend fun nextOutgoing(): DcWire = withTimeout(TEST_TIMEOUT_MS.milliseconds) { DcJson.decodeFromString(outbox.receive()) }

    suspend fun inject(wire: DcWire) {
        inbox.send(DcJson.encodeToString(wire))
    }

    suspend fun awaitClosed() {
        withTimeout(TEST_TIMEOUT_MS.milliseconds) { closed.await() }
    }
}

private class FailingPairSettings(
    private val delegate: KmpSettings = MapSettings(),
) : KmpSettings by delegate {
    override fun putString(
        key: String,
        value: String,
    ) {
        check(key != PAIR_STORE_KEY) { "pair write failed" }
        delegate.putString(key, value)
    }
}

private class ObservingPairSettings(
    private val delegate: KmpSettings = MapSettings(),
) : KmpSettings by delegate {
    @Volatile var onPairWrite: ((String) -> Unit)? = null

    override fun putString(
        key: String,
        value: String,
    ) {
        if (key == PAIR_STORE_KEY) onPairWrite?.invoke(value)
        delegate.putString(key, value)
    }
}

private const val LEFT_ID = "left-device"
private const val RIGHT_ID = "right-device"
private const val PAIR_STORE_KEY = "deviceConnectPairedDevices"
private const val TEST_TIMEOUT_MS = 5_000L
private val SAS_PATTERN = Regex("\\d{6}")
private val LEFT_INFO =
    DeviceInfoPayload(LEFT_ID, "Left", "Test", deviceType = DeviceConnectDeviceType.DESKTOP.wireName)
private val RIGHT_INFO =
    DeviceInfoPayload(RIGHT_ID, "Right", "Test", deviceType = DeviceConnectDeviceType.ANDROID_PHONE.wireName)
