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
import java.io.IOException
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.InetAddress
import java.net.ServerSocket
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
            val channelB =
                createJvmStreamDeviceChannel(
                    remoteHost = "bt://AA:BB:CC:DD:EE:01",
                    input = aToBIn,
                    output = bToAOut,
                    onClose = {
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
                    output = java.io.ByteArrayOutputStream(),
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
