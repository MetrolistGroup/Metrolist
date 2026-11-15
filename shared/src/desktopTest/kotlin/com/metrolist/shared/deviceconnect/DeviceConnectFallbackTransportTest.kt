/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.deviceconnect

import com.metrolist.shared.constants.DeviceConnectBluetoothFallbackKey
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playback.MusicQueueSnapshot
import com.metrolist.shared.settings.Settings
import com.russhwolf.settings.MapSettings
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.platform.win32.Guid
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.ptr.IntByReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class DeviceConnectFallbackTransportTest {
    @Test
    fun lengthPrefixedStreamChannelTransfersMultiFramePayloadsAndHandlesEof() =
        runBlocking<Unit> {
            val aToBIn = PipedInputStream(16 * 1024)
            val aToBOut = PipedOutputStream(aToBIn)
            val bToAIn = PipedInputStream(16 * 1024)
            val bToAOut = PipedOutputStream(bToAIn)

            val channelA =
                createJvmStreamDeviceChannel(
                    remoteHost = "bt://AA:BB:CC:DD:EE:02",
                    input = bToAIn,
                    output = aToBOut,
                    onClose = {
                        runCatching { aToBOut.close() }
                        runCatching { bToAIn.close() }
                    },
                )
            var bClosed = false
            val channelB =
                createJvmStreamDeviceChannel(
                    remoteHost = "bt://AA:BB:CC:DD:EE:01",
                    input = aToBIn,
                    output = bToAOut,
                    onClose = {
                        bClosed = true
                        runCatching { bToAOut.close() }
                        runCatching { aToBIn.close() }
                    },
                )

            val sentToB = listOf("""{"type":"HELLO","seq":1}""", """{"type":"PLAYBACK","title":"♫ Café"}""")
            val receivedByB = mutableListOf<String>()
            val collectorB = launch { channelB.incoming.toList(receivedByB) }

            sentToB.forEach { channelA.send(it) }
            channelA.send("""{"type":"PING"}""")
            channelB.send("""{"type":"PONG"}""")

            val pong = withTimeout(TEST_TIMEOUT_MS.milliseconds) { channelA.incoming.first() }
            assertEquals("""{"type":"PONG"}""", pong)

            channelA.close()
            withTimeout(TEST_TIMEOUT_MS.milliseconds) { collectorB.join() }
            assertEquals(sentToB + """{"type":"PING"}""", receivedByB)
            assertTrue(bClosed)
            channelB.close()
        }

    @Test
    fun lengthPrefixedStreamChannelRejectsOversizedFrames() =
        runBlocking<Unit> {
            val oversized = DEVICE_CONNECT_MAX_FRAME_BYTES.toInt() + 1
            val header =
                byteArrayOf(
                    (oversized ushr 24).toByte(),
                    (oversized ushr 16).toByte(),
                    (oversized ushr 8).toByte(),
                    oversized.toByte(),
                )
            val readerChannel =
                createJvmStreamDeviceChannel(
                    remoteHost = "bt://AA:BB:CC:DD:EE:02",
                    input = ByteArrayInputStream(header),
                    output = ByteArrayOutputStream(),
                    onClose = {},
                )
            assertFailsWith<IllegalArgumentException> {
                readerChannel.incoming.first()
            }
            assertFailsWith<IllegalArgumentException> {
                readerChannel.send("a".repeat(oversized))
            }
            readerChannel.close()
        }

    @Test
    fun windowsBtAddrConversionAndSockAddrLayoutAreValid() {
        assertEquals(30, SockAddrBth().size())
        assertEquals(0xAABBCCDDEEFFL, macAddressToWindowsBtAddr("aa:bb:cc:dd:ee:ff"))
        assertEquals(0xAABBCCDDEEFFL, macAddressToWindowsBtAddr("AA-BB-CC-DD-EE-FF"))
        assertEquals("AA:BB:CC:DD:EE:FF", windowsBtAddrToMacAddress(0xAABBCCDDEEFFL))
        assertNull(macAddressToWindowsBtAddr("invalid"))
        assertNull(macAddressToWindowsBtAddr("00:00:00:00:00:00"))
        assertNull(windowsBtAddrToMacAddress(0L))
    }

    @Test
    fun togglingBluetoothFallbackUpdatesRuntimeStateWithoutAffectingDefaultFastPath() =
        runBlocking<Unit> {
            val bus = FakeBluetoothBus()
            val settings = Settings(MapSettings())
            var factoryInvocations = 0
            val manager =
                DeviceConnectManager(
                    settings = settings,
                    identityProvider = ::anonymousIdentity,
                    localInfoProvider = { LEFT_INFO },
                    fallbackTransportFactory = { _, _ ->
                        factoryInvocations++
                        bus.transportFor(LEFT_MAC)
                    },
                )
            try {
                manager.enable(Unit)
                withTimeout(TEST_TIMEOUT_MS.milliseconds) {
                    manager.state.first { it.enabled && it.serverRunning }
                }
                assertEquals(0, factoryInvocations)
                assertFalse(manager.state.value.bluetoothFallbackEnabled)
                assertFalse(manager.state.value.bluetoothFallbackActive)

                manager.setBluetoothFallbackEnabled(true, Unit)
                val activeState =
                    withTimeout(TEST_TIMEOUT_MS.milliseconds) {
                        manager.state.first { it.bluetoothFallbackActive }
                    }
                assertEquals(1, factoryInvocations)
                assertTrue(activeState.bluetoothFallbackEnabled)
                assertEquals(LEFT_MAC, activeState.localBluetoothAddress)

                manager.setBluetoothFallbackEnabled(false, Unit)
                val disabledState =
                    withTimeout(TEST_TIMEOUT_MS.milliseconds) {
                        manager.state.first { !it.bluetoothFallbackEnabled && !it.bluetoothFallbackActive }
                    }
                assertEquals("", disabledState.localBluetoothAddress)
                assertTrue(bus.transportFor(LEFT_MAC).closed)
            } finally {
                manager.release()
                bus.closeAll()
            }
        }

    @Test
    fun bluetoothFallbackConnectsPairsAndSyncsPlaybackOverStreamChannels() =
        runBlocking<Unit> {
            val bus = FakeBluetoothBus()
            val song = SongItem("track-1", "Campus Song", null, emptyList(), null, duration = 240)
            val queue = MusicQueueSnapshot(listOf(song), listOf(song), 0, originalIndices = listOf(0))
            val leftPort = FakePlaybackPort(DcLocalPlaybackState(queue = queue, playing = true, positionMs = 1_500L, ready = true))
            val rightPort = FakePlaybackPort(DcLocalPlaybackState())

            val leftSettings = Settings(MapSettings()).apply { deviceConnectBluetoothFallbackEnabled = true }
            val rightSettings = Settings(MapSettings()).apply { deviceConnectBluetoothFallbackEnabled = true }

            val left =
                DeviceConnectManager(
                    settings = leftSettings,
                    identityProvider = ::anonymousIdentity,
                    localInfoProvider = { LEFT_INFO },
                    fallbackTransportFactory = { _, _ -> bus.transportFor(LEFT_MAC) },
                )
            val right =
                DeviceConnectManager(
                    settings = rightSettings,
                    identityProvider = ::anonymousIdentity,
                    localInfoProvider = { RIGHT_INFO },
                    fallbackTransportFactory = { _, _ -> bus.transportFor(RIGHT_MAC) },
                )

            try {
                left.remote.attach(leftPort)
                right.remote.attach(rightPort)
                left.enable(Unit)
                right.enable(Unit)

                withTimeout(TEST_TIMEOUT_MS.milliseconds) {
                    left.state.first { it.bluetoothFallbackActive && it.localBluetoothAddress == LEFT_MAC }
                    right.state.first { it.bluetoothFallbackActive && it.localBluetoothAddress == RIGHT_MAC }
                }

                // Advertise Right via bt:// direct endpoint and connect from Left using bt:// Direct Connection.
                left.connectDirect("$DEVICE_CONNECT_BT_PREFIX$RIGHT_MAC")

                val leftReq =
                    withTimeout(TEST_TIMEOUT_MS.milliseconds) {
                        left.state
                            .first { state -> state.pairingRequests.any { it.requiresApproval } }
                            .pairingRequests
                            .first()
                    }
                val rightReq =
                    withTimeout(TEST_TIMEOUT_MS.milliseconds) {
                        right.state
                            .first { state -> state.pairingRequests.any { it.requiresApproval } }
                            .pairingRequests
                            .first()
                    }
                assertEquals(6, leftReq.sas.length)
                assertEquals(leftReq.sas, rightReq.sas)

                left.approvePairing(leftReq.requestId)
                right.approvePairing(rightReq.requestId)

                withTimeout(TEST_TIMEOUT_MS.milliseconds) {
                    left.state.first { state ->
                        state.peers.any { it.id == RIGHT_ID && it.state == DeviceConnectManager.PeerState.CONNECTED }
                    }
                    right.state.first { state ->
                        state.peers.any { it.id == LEFT_ID && it.state == DeviceConnectManager.PeerState.CONNECTED }
                    }
                }

                right.remote.discoverPlayback()
                withTimeout(TEST_TIMEOUT_MS.milliseconds) {
                    rightPort.state.first {
                        it.playing && it.queue.displayedItems
                            .firstOrNull()
                            ?.id == "track-1"
                    }
                }
                assertTrue(right.remote.intercept(DcPlaybackCommand(DcPlaybackAction.PAUSE)))
                withTimeout(TEST_TIMEOUT_MS.milliseconds) {
                    leftPort.state.first { !it.playing }
                }
            } finally {
                runCatching { left.release() }
                runCatching { right.release() }
                bus.closeAll()
            }
        }

    @Test
    fun isolatedLanEndpointAutomaticallyFallsBackToDiscoveredBluetoothEndpoint() =
        runBlocking<Unit> {
            val localLanIp = getLocalIpV4Addresses().firstOrNull() ?: return@runBlocking
            val deadPort = ServerSocket(0, 1, InetAddress.getByName(localLanIp)).use { it.localPort }

            val bus = FakeBluetoothBus()
            val leftSettings = Settings(MapSettings()).apply { deviceConnectBluetoothFallbackEnabled = true }
            val rightSettings = Settings(MapSettings()).apply { deviceConnectBluetoothFallbackEnabled = true }

            val left =
                DeviceConnectManager(
                    settings = leftSettings,
                    identityProvider = ::anonymousIdentity,
                    localInfoProvider = { LEFT_INFO },
                    fallbackTransportFactory = { _, _ -> bus.transportFor(LEFT_MAC) },
                )
            val right =
                DeviceConnectManager(
                    settings = rightSettings,
                    identityProvider = ::anonymousIdentity,
                    localInfoProvider = { RIGHT_INFO },
                    fallbackTransportFactory = { _, _ -> bus.transportFor(RIGHT_MAC) },
                )

            try {
                left.enable(Unit)
                right.enable(Unit)
                withTimeout(TEST_TIMEOUT_MS.milliseconds) {
                    left.state.first { it.bluetoothFallbackActive }
                    right.state.first { it.bluetoothFallbackActive }
                }

                // Report both an unreachable/isolated LAN endpoint (higher rank) and a bt:// fallback endpoint.
                val lanEndpoint = DiscoveredDevice("lan-right", RIGHT_ID, "Right", localLanIp, deadPort)
                val btEndpoint =
                    DiscoveredDevice(
                        serviceName = "bt-right",
                        deviceId = RIGHT_ID,
                        deviceName = "Right",
                        host = "$DEVICE_CONNECT_BT_PREFIX$RIGHT_MAC",
                        port = DEVICE_CONNECT_BT_PORT,
                    )
                left.reportDiscoveredDeviceForTest(lanEndpoint)
                left.reportDiscoveredDeviceForTest(btEndpoint)

                val peerBeforePair =
                    assertNotNull(
                        left.state.value.peers
                            .firstOrNull { it.id == RIGHT_ID },
                    )
                assertEquals(localLanIp, peerBeforePair.host)

                left.pair(RIGHT_ID)

                val leftReq =
                    withTimeout(TEST_TIMEOUT_MS.milliseconds) {
                        left.state
                            .first { state -> state.pairingRequests.any { it.requiresApproval } }
                            .pairingRequests
                            .first()
                    }
                val rightReq =
                    withTimeout(TEST_TIMEOUT_MS.milliseconds) {
                        right.state
                            .first { state -> state.pairingRequests.any { it.requiresApproval } }
                            .pairingRequests
                            .first()
                    }
                left.approvePairing(leftReq.requestId)
                right.approvePairing(rightReq.requestId)

                val connectedState =
                    withTimeout(TEST_TIMEOUT_MS.milliseconds) {
                        left.state.first { state ->
                            state.peers.any { it.id == RIGHT_ID && it.state == DeviceConnectManager.PeerState.CONNECTED }
                        }
                    }
                val connectedPeer = connectedState.peers.single { it.id == RIGHT_ID }
                assertEquals("$DEVICE_CONNECT_BT_PREFIX$RIGHT_MAC", connectedPeer.host)
            } finally {
                runCatching { left.release() }
                runCatching { right.release() }
                bus.closeAll()
            }
        }

    @Test
    fun disabledBluetoothFallbackDoesNotActivateOnDirectBtOrLanFailure() =
        runBlocking<Unit> {
            val bus = FakeBluetoothBus()
            val settings = Settings(MapSettings())
            var factoryInvocations = 0
            val manager =
                DeviceConnectManager(
                    settings = settings,
                    identityProvider = ::anonymousIdentity,
                    localInfoProvider = { LEFT_INFO },
                    fallbackTransportFactory = { _, _ ->
                        factoryInvocations++
                        bus.transportFor(LEFT_MAC)
                    },
                )
            try {
                manager.enable(Unit)
                withTimeout(TEST_TIMEOUT_MS.milliseconds) {
                    manager.state.first { it.enabled && it.serverRunning }
                }

                manager.connectDirect("$DEVICE_CONNECT_BT_PREFIX$RIGHT_MAC")
                val directFailedState =
                    withTimeout(TEST_TIMEOUT_MS.milliseconds) {
                        manager.state.first { it.directConnectionFailed }
                    }
                assertEquals(0, factoryInvocations)
                assertFalse(directFailedState.bluetoothFallbackActive)

                val localLanIp = getLocalIpV4Addresses().firstOrNull()
                if (localLanIp != null) {
                    val deadPort = ServerSocket(0, 1, InetAddress.getByName(localLanIp)).use { it.localPort }
                    manager.reportDiscoveredDeviceForTest(
                        DiscoveredDevice("lan-right", RIGHT_ID, "Right", localLanIp, deadPort),
                    )
                    manager.pair(RIGHT_ID)
                    val failedPeerState =
                        withTimeout(TEST_TIMEOUT_MS.milliseconds) {
                            manager.state.first { state ->
                                state.log.any { entry ->
                                    entry.message.contains("Connection endpoint for Right ($localLanIp:$deadPort) is unavailable")
                                }
                            }
                        }
                    assertEquals(0, factoryInvocations)
                    assertFalse(failedPeerState.bluetoothFallbackActive)
                }
            } finally {
                runCatching { manager.release() }
                bus.closeAll()
            }
        }

    @Test
    fun exportBackupSettingsExcludesBluetoothFallbackKey() {
        val settings = Settings(MapSettings()).apply { deviceConnectBluetoothFallbackEnabled = true }
        val backup = settings.exportBackupSettings()
        assertTrue(backup.entries.none { it.key == DeviceConnectBluetoothFallbackKey })
    }

    @Test
    fun unknownBluetoothDiscoveryPairsBindsAndSharesCandidatesWithLanRecords() =
        runBlocking<Unit> {
            val localLanIp = getLocalIpV4Addresses().firstOrNull() ?: return@runBlocking
            val deadPort = ServerSocket(0, 1, InetAddress.getByName(localLanIp)).use { it.localPort }

            val bus = FakeBluetoothBus()
            val leftSettings = Settings(MapSettings()).apply { deviceConnectBluetoothFallbackEnabled = true }
            val rightSettings = Settings(MapSettings()).apply { deviceConnectBluetoothFallbackEnabled = true }

            val left =
                DeviceConnectManager(
                    settings = leftSettings,
                    identityProvider = ::anonymousIdentity,
                    localInfoProvider = { LEFT_INFO },
                    fallbackTransportFactory = { _, _ -> bus.transportFor(LEFT_MAC) },
                )
            val right =
                DeviceConnectManager(
                    settings = rightSettings,
                    identityProvider = ::anonymousIdentity,
                    localInfoProvider = { RIGHT_INFO },
                    fallbackTransportFactory = { _, _ -> bus.transportFor(RIGHT_MAC) },
                )

            try {
                left.enable(Unit)
                right.enable(Unit)
                withTimeout(TEST_TIMEOUT_MS.milliseconds) {
                    left.state.first { it.bluetoothFallbackActive }
                    right.state.first { it.bluetoothFallbackActive }
                }

                val unknownId = unknownBluetoothDeviceId(RIGHT_MAC)
                val btUnknown =
                    DiscoveredDevice(
                        serviceName = "bt-$RIGHT_MAC",
                        deviceId = unknownId,
                        deviceName = "Right BT",
                        host = "$DEVICE_CONNECT_BT_PREFIX$RIGHT_MAC",
                        port = DEVICE_CONNECT_BT_PORT,
                    )
                left.reportDiscoveredDeviceForTest(btUnknown)
                withTimeout(TEST_TIMEOUT_MS.milliseconds) {
                    left.state.first { state -> state.peers.any { it.id == unknownId } }
                }

                left.pair(unknownId)

                val leftReq =
                    withTimeout(TEST_TIMEOUT_MS.milliseconds) {
                        left.state
                            .first { state -> state.pairingRequests.any { it.requiresApproval } }
                            .pairingRequests
                            .first()
                    }
                val rightReq =
                    withTimeout(TEST_TIMEOUT_MS.milliseconds) {
                        right.state
                            .first { state -> state.pairingRequests.any { it.requiresApproval } }
                            .pairingRequests
                            .first()
                    }
                assertEquals(RIGHT_ID, leftReq.deviceId)
                assertEquals(LEFT_ID, rightReq.deviceId)
                left.approvePairing(leftReq.requestId)
                right.approvePairing(rightReq.requestId)

                withTimeout(TEST_TIMEOUT_MS.milliseconds) {
                    left.state.first { state ->
                        state.peers.any { it.id == RIGHT_ID && it.state == DeviceConnectManager.PeerState.CONNECTED } &&
                            state.peers.none { it.id == unknownId }
                    }
                    right.state.first { state ->
                        state.peers.any { it.id == LEFT_ID && it.state == DeviceConnectManager.PeerState.CONNECTED }
                    }
                }
                assertFalse(left.state.value.directConnectionFailed)

                // Re-discovering the unknown Bluetooth ID and a LAN endpoint must merge into RIGHT_ID without a duplicate peer.
                val lanEndpoint = DiscoveredDevice("lan-right", RIGHT_ID, "Right", localLanIp, deadPort)
                left.reportDiscoveredDeviceForTest(btUnknown)
                left.reportDiscoveredDeviceForTest(lanEndpoint)
                assertEquals(listOf(RIGHT_ID), left.state.value.peers.map { it.id })

                // Drop the link and restart Right; re-discovering btUnknown must resolve to RIGHT_ID and auto-reconnect.
                right.disable()
                left.awaitSessionClosedForTest(RIGHT_ID)
                withTimeout(TEST_TIMEOUT_MS.milliseconds) {
                    left.state.first { state -> state.peers.none { it.id == RIGHT_ID && it.state == DeviceConnectManager.PeerState.CONNECTED } }
                    right.state.first { !it.enabled && !it.bluetoothFallbackActive }
                }

                right.enable(Unit)
                withTimeout(TEST_TIMEOUT_MS.milliseconds) {
                    right.state.first { it.bluetoothFallbackActive }
                }
                left.reportDiscoveredDeviceForTest(btUnknown)
                withTimeout(TEST_TIMEOUT_MS.milliseconds) {
                    left.state.first { state ->
                        state.peers.any { it.id == RIGHT_ID && it.state == DeviceConnectManager.PeerState.CONNECTED } &&
                            state.peers.none { it.id == unknownId }
                    }
                    right.state.first { state ->
                        state.peers.any { it.id == LEFT_ID && it.state == DeviceConnectManager.PeerState.CONNECTED }
                    }
                }
            } finally {
                runCatching { left.release() }
                runCatching { right.release() }
                bus.closeAll()
            }
        }

    @Test
    fun bluetoothConnectPreservesAdvertisedL2capPsmAndRotatesToRfcommWithoutRetryingFailedPsm() =
        runBlocking<Unit> {
            val bus = FakeBluetoothBus()
            val leftSettings = Settings(MapSettings()).apply { deviceConnectBluetoothFallbackEnabled = true }
            val rightSettings = Settings(MapSettings()).apply { deviceConnectBluetoothFallbackEnabled = true }
            val attemptedPorts = mutableListOf<Pair<String, Int>>()

            val left =
                DeviceConnectManager(
                    settings = leftSettings,
                    identityProvider = ::anonymousIdentity,
                    localInfoProvider = { LEFT_INFO },
                    fallbackTransportFactory = { _, _ ->
                        val delegate = bus.transportFor(LEFT_MAC)
                        object : LocalDeviceTransport by delegate {
                            override suspend fun connect(
                                host: String,
                                port: Int,
                            ): DeviceChannel {
                                attemptedPorts += host to port
                                if (port != DEVICE_CONNECT_BT_PORT) {
                                    throw IOException("L2CAP PSM $port refused")
                                }
                                return delegate.connect(host, port)
                            }
                        }
                    },
                )
            val right =
                DeviceConnectManager(
                    settings = rightSettings,
                    identityProvider = ::anonymousIdentity,
                    localInfoProvider = { RIGHT_INFO },
                    fallbackTransportFactory = { _, _ -> bus.transportFor(RIGHT_MAC) },
                )

            try {
                left.enable(Unit)
                right.enable(Unit)
                withTimeout(TEST_TIMEOUT_MS.milliseconds) {
                    left.state.first { it.bluetoothFallbackActive }
                    right.state.first { it.bluetoothFallbackActive }
                }

                val btHost = "$DEVICE_CONNECT_BT_PREFIX$RIGHT_MAC"
                left.reportDiscoveredDeviceForTest(DiscoveredDevice("ble-$RIGHT_MAC", RIGHT_ID, "Right", btHost, 135))
                left.reportDiscoveredDeviceForTest(DiscoveredDevice("bt-$RIGHT_MAC", RIGHT_ID, "Right", btHost, DEVICE_CONNECT_BT_PORT))

                left.pair(RIGHT_ID)

                val leftReq =
                    withTimeout(TEST_TIMEOUT_MS.milliseconds) {
                        left.state
                            .first { state -> state.pairingRequests.any { it.requiresApproval } }
                            .pairingRequests
                            .first()
                    }
                val rightReq =
                    withTimeout(TEST_TIMEOUT_MS.milliseconds) {
                        right.state
                            .first { state -> state.pairingRequests.any { it.requiresApproval } }
                            .pairingRequests
                            .first()
                    }
                left.approvePairing(leftReq.requestId)
                right.approvePairing(rightReq.requestId)

                withTimeout(TEST_TIMEOUT_MS.milliseconds) {
                    left.state.first { state ->
                        state.peers.any { it.id == RIGHT_ID && it.state == DeviceConnectManager.PeerState.CONNECTED }
                    }
                }
                assertEquals(listOf(btHost to 135, btHost to DEVICE_CONNECT_BT_PORT), attemptedPorts)
            } finally {
                runCatching { left.release() }
                runCatching { right.release() }
                bus.closeAll()
            }
        }

    @Test
    fun bluetoothConnectDoesNotInfinitelyRetrySingleFailedL2capEndpoint() =
        runBlocking<Unit> {
            val bus = FakeBluetoothBus()
            val leftSettings = Settings(MapSettings()).apply { deviceConnectBluetoothFallbackEnabled = true }
            val attemptedPorts = mutableListOf<Pair<String, Int>>()

            val left =
                DeviceConnectManager(
                    settings = leftSettings,
                    identityProvider = ::anonymousIdentity,
                    localInfoProvider = { LEFT_INFO },
                    fallbackTransportFactory = { _, _ ->
                        val delegate = bus.transportFor(LEFT_MAC)
                        object : LocalDeviceTransport by delegate {
                            override suspend fun connect(
                                host: String,
                                port: Int,
                            ): DeviceChannel {
                                attemptedPorts += host to port
                                throw IOException("L2CAP PSM $port unreachable")
                            }
                        }
                    },
                )

            try {
                left.enable(Unit)
                withTimeout(TEST_TIMEOUT_MS.milliseconds) {
                    left.state.first { it.bluetoothFallbackActive }
                }

                val btHost = "$DEVICE_CONNECT_BT_PREFIX$RIGHT_MAC"
                left.reportDiscoveredDeviceForTest(DiscoveredDevice("ble-$RIGHT_MAC", RIGHT_ID, "Right", btHost, 135))
                left.pair(RIGHT_ID)

                withTimeout(TEST_TIMEOUT_MS.milliseconds) {
                    left.state.first { state ->
                        state.log.any { it.message.contains("Connection endpoint for Right ($btHost:135) is unavailable") }
                    }
                }
                assertEquals(listOf(btHost to 135), attemptedPorts)
            } finally {
                runCatching { left.release() }
                bus.closeAll()
            }
        }

    @Test
    fun windowsBluetoothServiceLookupChecksUuidHandlesResizeAndRejectsNonMetrolistPeers() {
        val expectedUuid = UUID.fromString(DEVICE_CONNECT_BT_SERVICE_UUID)
        val cachedHitWs2 = FakeLookupWs232Lib(cachedResult = LookupOutcome.NEEDS_LARGER_BUFFER_THEN_FOUND)
        assertTrue(hasWindowsBluetoothService(cachedHitWs2, RIGHT_MAC, expectedUuid))
        assertEquals(listOf(false), cachedHitWs2.beginFlushFlags)
        assertEquals(1, cachedHitWs2.openHandles)
        assertEquals(1, cachedHitWs2.closedHandles)
        assertEquals("($RIGHT_MAC)", cachedHitWs2.seenContexts.single())
        assertEquals(expectedUuid, cachedHitWs2.seenUuids.single())

        val flushHitWs2 = FakeLookupWs232Lib(cachedResult = LookupOutcome.NOT_FOUND, flushResult = LookupOutcome.FOUND)
        assertTrue(hasWindowsBluetoothService(flushHitWs2, RIGHT_MAC, expectedUuid))
        assertEquals(listOf(false, true), flushHitWs2.beginFlushFlags)
        assertEquals(2, flushHitWs2.openHandles)
        assertEquals(2, flushHitWs2.closedHandles)

        val missingWs2 = FakeLookupWs232Lib(cachedResult = LookupOutcome.NOT_FOUND, flushResult = LookupOutcome.NOT_FOUND)
        assertFalse(hasWindowsBluetoothService(missingWs2, RIGHT_MAC, expectedUuid))
        assertEquals(listOf(false, true), missingWs2.beginFlushFlags)
        assertEquals(2, missingWs2.openHandles)
        assertEquals(2, missingWs2.closedHandles)

        assertFalse(hasWindowsBluetoothService(missingWs2, "not-a-mac", expectedUuid))
    }

    private enum class LookupOutcome {
        FOUND,
        NEEDS_LARGER_BUFFER_THEN_FOUND,
        NOT_FOUND,
    }

    private class FakeLookupWs232Lib(
        private val cachedResult: LookupOutcome,
        private val flushResult: LookupOutcome = LookupOutcome.NOT_FOUND,
    ) : Ws232Lib {
        val beginFlushFlags = mutableListOf<Boolean>()
        val seenContexts = mutableListOf<String>()
        val seenUuids = mutableListOf<UUID>()
        var openHandles = 0
        var closedHandles = 0
        private var lastError = 0
        private val handleOutcomes = mutableMapOf<Long, LookupOutcome>()
        private val handleRetried = mutableSetOf<Long>()

        override fun WSALookupServiceBeginW(
            lpqsRestrictions: Pointer,
            dwControlFlags: Int,
            lphLookup: WinNT.HANDLEByReference,
        ): Int {
            val querySet = Structure.newInstance(WsaQuerySetW::class.java, lpqsRestrictions).apply { read() }
            assertEquals(16, querySet.dwNameSpace)
            seenContexts += querySet.lpszContext?.toString().orEmpty()
            val guidPtr = checkNotNull(querySet.lpServiceClassId)
            val guid = Structure.newInstance(Guid.GUID::class.java, guidPtr).apply { read() }
            seenUuids += guidToUuid(guid)

            val isFlush = (dwControlFlags and 0x1000) != 0
            beginFlushFlags += isFlush
            val handleId = (++openHandles).toLong()
            handleOutcomes[handleId] = if (isFlush) flushResult else cachedResult
            lphLookup.value = WinNT.HANDLE(Pointer.createConstant(handleId))
            return 0
        }

        override fun WSALookupServiceNextW(
            hLookup: WinNT.HANDLE,
            dwControlFlags: Int,
            lpdwBufferLength: IntByReference,
            lpqsResults: Pointer,
        ): Int {
            val handleId = Pointer.nativeValue(hLookup.pointer)
            return when (handleOutcomes[handleId]) {
                LookupOutcome.FOUND -> {
                    lastError = 0
                    0
                }

                LookupOutcome.NEEDS_LARGER_BUFFER_THEN_FOUND -> {
                    if (handleRetried.add(handleId)) {
                        lpdwBufferLength.value = lpdwBufferLength.value + 1024
                        lastError = 10014
                        -1
                    } else {
                        lastError = 0
                        0
                    }
                }

                LookupOutcome.NOT_FOUND, null -> {
                    lastError = 10110
                    -1
                }
            }
        }

        override fun WSALookupServiceEnd(hLookup: WinNT.HANDLE): Int {
            closedHandles++
            return 0
        }

        override fun WSAGetLastError(): Int = lastError

        override fun WSAStartup(
            wVersionRequested: Int,
            lpWSAData: Pointer,
        ): Int = 0

        override fun socket(
            af: Int,
            type: Int,
            protocol: Int,
        ): WinDef.UINT_PTR = WinDef.UINT_PTR(0)

        override fun bind(
            s: WinDef.UINT_PTR,
            name: Pointer,
            namelen: Int,
        ): Int = 0

        override fun listen(
            s: WinDef.UINT_PTR,
            backlog: Int,
        ): Int = 0

        override fun accept(
            s: WinDef.UINT_PTR,
            addr: Pointer?,
            addrlen: IntByReference?,
        ): WinDef.UINT_PTR = WinDef.UINT_PTR(0)

        override fun connect(
            s: WinDef.UINT_PTR,
            name: Pointer,
            namelen: Int,
        ): Int = 0

        override fun getsockname(
            s: WinDef.UINT_PTR,
            name: Pointer,
            namelen: IntByReference,
        ): Int = 0

        override fun recv(
            s: WinDef.UINT_PTR,
            buf: Pointer,
            len: Int,
            flags: Int,
        ): Int = 0

        override fun send(
            s: WinDef.UINT_PTR,
            buf: Pointer,
            len: Int,
            flags: Int,
        ): Int = 0

        override fun shutdown(
            s: WinDef.UINT_PTR,
            how: Int,
        ): Int = 0

        override fun closesocket(s: WinDef.UINT_PTR): Int = 0

        override fun WSASetServiceW(
            lpqsRegInfo: Pointer,
            essoperation: Int,
            dwControlFlags: Int,
        ): Int = 0

        private fun guidToUuid(guid: Guid.GUID): UUID {
            val msb =
                ((guid.Data1.toLong() and 0xffffffffL) shl 32) or
                    ((guid.Data2.toLong() and 0xffffL) shl 16) or
                    (guid.Data3.toLong() and 0xffffL)
            var lsb = 0L
            for (b in guid.Data4) {
                lsb = (lsb shl 8) or (b.toLong() and 0xffL)
            }
            return UUID(msb, lsb)
        }
    }

    private class FakeBluetoothBus {
        private val transports = mutableMapOf<String, FakeBluetoothTransport>()

        fun transportFor(mac: String): FakeBluetoothTransport = transports.getOrPut(mac) { FakeBluetoothTransport(mac, this) }

        fun openLink(
            sourceMac: String,
            targetHost: String,
        ): DeviceChannel {
            val targetMac = targetHost.removePrefix(DEVICE_CONNECT_BT_PREFIX)
            val target = transports[targetMac] ?: throw IOException("No Bluetooth server at $targetHost")
            if (target.incomingHandler == null) throw IOException("Bluetooth server stopped on $targetHost")

            val clientToServerIn = PipedInputStream(32 * 1024)
            val clientToServerOut = PipedOutputStream(clientToServerIn)
            val serverToClientIn = PipedInputStream(32 * 1024)
            val serverToClientOut = PipedOutputStream(serverToClientIn)

            val clientChannel =
                createJvmStreamDeviceChannel(
                    remoteHost = "$DEVICE_CONNECT_BT_PREFIX$targetMac",
                    input = serverToClientIn,
                    output = clientToServerOut,
                    onClose = {
                        runCatching { clientToServerOut.close() }
                        runCatching { serverToClientIn.close() }
                    },
                )
            val serverChannel =
                createJvmStreamDeviceChannel(
                    remoteHost = "$DEVICE_CONNECT_BT_PREFIX$sourceMac",
                    input = clientToServerIn,
                    output = serverToClientOut,
                    onClose = {
                        runCatching { serverToClientOut.close() }
                        runCatching { clientToServerIn.close() }
                    },
                )
            target.dispatchIncoming(serverChannel)
            return clientChannel
        }

        fun closeAll() {
            transports.values.forEach { runCatching { it.stopServer() } }
            transports.clear()
        }
    }

    private class FakeBluetoothTransport(
        private val mac: String,
        private val bus: FakeBluetoothBus,
    ) : LocalDeviceTransport {
        override val kind: DeviceTransportKind = DeviceTransportKind.BLUETOOTH_RFCOMM
        override val localAddress: String = mac
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        @Volatile var incomingHandler: (suspend (DeviceChannel) -> Unit)? = null

        @Volatile var closed = false

        override fun startServer(
            localInfo: DeviceInfoPayload,
            onChannel: suspend (DeviceChannel) -> Unit,
            onFailure: (Throwable) -> Unit,
        ): Boolean {
            closed = false
            incomingHandler = onChannel
            return true
        }

        fun dispatchIncoming(channel: DeviceChannel) {
            val handler = incomingHandler ?: return
            scope.launch { handler(channel) }
        }

        override fun stopServer() {
            incomingHandler = null
        }

        override fun startDiscovery(
            localInfo: DeviceInfoPayload,
            onFound: (DiscoveredDevice) -> Unit,
            onLost: (DiscoveredDevice) -> Unit,
        ) = Unit

        override suspend fun stopDiscovery() = Unit

        override suspend fun connect(
            host: String,
            port: Int,
        ): DeviceChannel = bus.openLink(mac, host)

        override suspend fun close() {
            closed = true
            stopServer()
            scope.cancel()
        }
    }

    private class FakePlaybackPort(
        initial: DcLocalPlaybackState,
    ) : DeviceConnectPlaybackPort {
        val state = MutableStateFlow(initial)
        private var controlling = false

        override suspend fun snapshot(): DcLocalPlaybackState = state.value

        override suspend fun apply(
            snapshot: DcPlaybackSnapshot,
            positionMs: Long,
            shouldCommit: () -> Boolean,
        ) {
            if (shouldCommit()) state.value = snapshot.playback.copy(positionMs = positionMs)
        }

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

        override suspend fun setAudioOutputEnabled(enabled: Boolean) = Unit
    }

    private fun anonymousIdentity() =
        AccountIdentitySnapshot(
            secret = "",
            fingerprint = "",
            anonymous = true,
            resolved = true,
        )

    private companion object {
        const val LEFT_ID = "left-device"
        const val RIGHT_ID = "right-device"
        const val LEFT_MAC = "AA:BB:CC:DD:EE:01"
        const val RIGHT_MAC = "AA:BB:CC:DD:EE:02"
        const val TEST_TIMEOUT_MS = 6_000L
        val LEFT_INFO =
            DeviceInfoPayload(LEFT_ID, "Left", "Test", deviceType = DeviceConnectDeviceType.DESKTOP.wireName)
        val RIGHT_INFO =
            DeviceInfoPayload(RIGHT_ID, "Right", "Test", deviceType = DeviceConnectDeviceType.ANDROID_PHONE.wireName)
    }
}
