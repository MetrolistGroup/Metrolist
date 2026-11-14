/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * DeviceConnect manager (shared). Orchestrates:
 *  - a local WebSocket server (accepts peer links),
 *  - LAN service discovery (finds peers),
 *  - the encrypted ECDH + mutually confirmed pairing handshake,
 *  - heartbeat + logging for the debug UI.
 */

package com.metrolist.shared.deviceconnect

import androidx.compose.runtime.snapshotFlow
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.utils.Logger
import com.metrolist.shared.youtube.playback.PlaybackEventKind
import com.metrolist.shared.youtube.playback.PlaybackEventLog
import com.metrolist.shared.youtube.playback.PlaybackNetworkStateStore
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.ClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.url
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.Volatile
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

internal const val DEVICE_CONNECT_MAX_FRAME_BYTES = 64L * 1024

enum class LogLevel { DEBUG, INFO, WARN, ERROR }

data class LogEntry(
    val level: LogLevel,
    val timestamp: String,
    val message: String,
) {
    val formatted: String
        get() = "$timestamp • $message"
}

class DeviceConnectManager
    internal constructor(
        private val settings: Settings,
        private val identityProvider: () -> AccountIdentitySnapshot,
        private val localInfoProvider: () -> DeviceInfoPayload,
        private val advertisedPlaybackVersion: Int = DEVICE_CONNECT_PLAYBACK_VERSION,
        private val supportsCodePairing: Boolean = true,
        private val supportsPlaybackClock: Boolean = true,
        private val supportsPlaybackPitch: Boolean = true,
        private val requestedServerPort: Int = 0,
        private val localAddressProvider: () -> Set<String> = { getLocalIpV4Addresses().toSet() },
        private val pairingTimeSource: TimeSource = TimeSource.Monotonic,
        dispatcher: CoroutineDispatcher = Dispatchers.Default,
    ) {
        constructor(
            serverPort: Int = 0,
            localDeviceType: DeviceConnectDeviceType = DeviceConnectDeviceType.UNKNOWN,
        ) : this(
            Settings.shared,
            AccountIdentity::snapshot,
            { buildLocalDeviceInfo(localDeviceType) },
            requestedServerPort = serverPort,
        )

        enum class PeerState {
            DISCOVERED,
            CONNECTING,
            HANDSHAKING,
            CONNECTED,
            ACCOUNT_MISMATCH,
            ACCOUNT_UNRESOLVED,
            DISCONNECTED,
            ERROR,
        }

        data class PeerDevice(
            val id: String,
            val name: String,
            val host: String,
            val port: Int,
            val state: PeerState,
            val model: String = "",
            val serviceName: String = "",
            val deviceType: DeviceConnectDeviceType = DeviceConnectDeviceType.UNKNOWN,
        )

        data class PairingRequest(
            val requestId: String,
            val deviceId: String,
            val deviceName: String,
            val sas: String = "",
            val requiresApproval: Boolean,
            val expiresAtEpochMs: Long,
        )

        data class PairedDevice(
            val deviceId: String,
            val deviceName: String,
            val model: String = "",
            val deviceType: DeviceConnectDeviceType = DeviceConnectDeviceType.UNKNOWN,
        )

        data class State(
            val enabled: Boolean = false,
            val localDeviceId: String = "",
            val localDeviceName: String = "",
            val localDeviceType: DeviceConnectDeviceType = DeviceConnectDeviceType.UNKNOWN,
            val serverRunning: Boolean = false,
            val serverFailed: Boolean = false,
            val discoveryFailed: Boolean = false,
            val directConnectionFailed: Boolean = false,
            val serverPort: Int = 0,
            val localAddress: String = "",
            val requiresPermission: Boolean = false,
            val anonymous: Boolean = false,
            val peers: List<PeerDevice> = emptyList(),
            val pairingRequests: List<PairingRequest> = emptyList(),
            val pairedDevices: List<PairedDevice> = emptyList(),
            val log: List<LogEntry> = emptyList(),
        )

        private val scope = CoroutineScope(SupervisorJob() + dispatcher)
        private val _state = MutableStateFlow(State())
        val state: StateFlow<State> = _state.asStateFlow()

        @Volatile private var context: Any? = null

        private var server: LocalDeviceServer? = null
        private var discovery: DeviceDiscoveryController? = null
        private var httpClient: HttpClient? = null

        private val peerById = mutableMapOf<String, PeerDevice>()
        private val discoveredEndpoints = DeviceConnectEndpoints()

        // Keyed by connectionId for live links. Guarded by [stateMutex].
        private val connections = mutableMapOf<String, PeerSession>()
        private val pairStore = DevicePairStore(settings)
        private val pairStoreMutex = Mutex()

        @Volatile
        private var connectionCounter = 0
        private val connectionSlots = Semaphore(MAX_CONNECTIONS)
        private val incomingAdmission = DeviceConnectIncomingAdmission()
        private val stateMutex = Mutex()
        private val lifecycleMutex = Mutex()
        private var heartbeatJob: Job? = null
        private var localAddresses = emptySet<String>()

        // Guarded by [stateMutex]; earliest time an automatic connection to a paired peer may be retried.
        private val autoConnectNotBeforeMs = mutableMapOf<String, Long>()

        @Volatile private var discoveryGeneration = 0L

        private val discoveryRetry = DeviceConnectDiscoveryRetry()
        private val discoveryFailed = MutableStateFlow(false)

        private var accountIdentity: AccountIdentitySnapshot? = null

        init {
            val identity = identityProvider()
            accountIdentity = identity
            _state.value =
                _state.value.copy(
                    anonymous = identity.anonymous,
                    pairedDevices =
                        pairStore
                            .pairsFor(identity)
                            .map { PairedDevice(it.peerDeviceId, it.peerDeviceName, it.peerModel, it.peerDeviceType) },
                )
        }

        // UI writes and Default-dispatcher reads require cross-thread visibility.
        @Volatile
        private var localInfo: DeviceInfoPayload? = null

        val remote =
            DeviceConnectRemote(
                parentScope = scope,
                localDeviceId = { localInfo?.deviceId ?: localInfoProvider().deviceId },
                send = ::sendPlayback,
                useLegacyShuffle = { settings.useLegacyShuffle },
                defaultDeviceId = {
                    settings.deviceConnectDefaultDeviceId.takeIf { id ->
                        state.value.pairedDevices.any { it.deviceId == id }
                    }
                },
            )

        init {
            scope.launch {
                var previousMembers = emptySet<String>()
                remote.state.collect { playback ->
                    val members =
                        (playback.controllerIds + listOfNotNull(playback.targetId))
                            .filterTo(mutableSetOf()) { it != _state.value.localDeviceId }
                    val added = members - previousMembers
                    previousMembers = members
                    if (added.isNotEmpty()) {
                        try {
                            val identity = accountIdentity ?: identityProvider()
                            pairStoreMutex.withLock { pairStore.recordUsage(added, identity) }
                            refreshPairedDevices()
                        } catch (error: Exception) {
                            if (error is CancellationException) throw error
                            logError("Could not save device usage: ${error.message}", error)
                        }
                    }
                }
            }
        }

        // Guarded by stateMutex; duplicate sockets represent one playback participant.
        private val playbackPeers = mutableSetOf<String>()

        suspend fun enable(context: Any?) {
            settings.deviceConnectEnabled = true
            enableRuntime(context)
        }

        suspend fun restoreIfEnabled(
            context: Any?,
            canUseLocalNetwork: Boolean = true,
        ) {
            if (!settings.deviceConnectEnabled) return
            if (!canUseLocalNetwork) {
                stateMutex.withLock {
                    _state.value = _state.value.copy(requiresPermission = true)
                }
                logInfo("Device Connect restore is waiting for local-network permission.")
                return
            }
            enableRuntime(context)
        }

        private suspend fun enableRuntime(context: Any?) =
            lifecycleMutex.withLock {
                if (!settings.deviceConnectEnabled) return@withLock
                if (_state.value.enabled) return@withLock
                stateMutex.withLock {
                    autoConnectNotBeforeMs.clear()
                    _state.value = _state.value.copy(requiresPermission = false, serverFailed = false)
                }
                this.context = context
                val identity = refreshAccountIdentity()
                if (identity.anonymous) {
                    logInfo("Anonymous mode: pairing requires confirmation on both devices.")
                } else {
                    logInfo("Account mode: same-account pairing is required.")
                }
                val local = localInfoProvider()
                this.localInfo = local
                localAddresses = localAddressProvider()
                val actualPort = startServer(requestedServerPort)
                if (actualPort <= 0) {
                    stateMutex.withLock { _state.value = _state.value.copy(serverFailed = true) }
                    logError("Failed to start local server.")
                    return@withLock
                }
                val pairedDevices = pairedDevicesFor(identity)

                stateMutex.withLock {
                    _state.value =
                        _state.value.copy(
                            enabled = true,
                            anonymous = identity.anonymous,
                            localDeviceId = local.deviceId,
                            localDeviceName = local.deviceName,
                            localDeviceType = DeviceConnectDeviceType.fromWireName(local.deviceType),
                            serverRunning = true,
                            serverPort = actualPort,
                            localAddress = localAddresses.sorted().firstOrNull().orEmpty(),
                            requiresPermission = false,
                            pairedDevices = pairedDevices,
                        )
                }
                startDiscovery(local, actualPort)
                startHeartbeat()
                logInfo("DeviceConnect enabled on port $actualPort (id=${local.deviceId}).")
            }

        fun disable() {
            settings.deviceConnectEnabled = false
            scope.launch {
                val disposed =
                    lifecycleMutex.withLock {
                        if (settings.deviceConnectEnabled) {
                            false
                        } else {
                            disposeLocked()
                            true
                        }
                    }
                if (disposed) logInfo("DeviceConnect disabled.")
            }
        }

        /** Caller must hold [lifecycleMutex]. */
        private suspend fun disposeLocked() {
            remote.stopAndAwait(pause = false)
            heartbeatJob?.cancel()
            heartbeatJob = null
            val sessions =
                stateMutex.withLock {
                    _state.value =
                        _state.value.copy(
                            enabled = false,
                            serverRunning = false,
                            serverFailed = false,
                            discoveryFailed = false,
                            directConnectionFailed = false,
                            serverPort = 0,
                            localAddress = "",
                            requiresPermission = false,
                            pairingRequests = emptyList(),
                        )
                    playbackPeers.clear()
                    connections.values.toList().also { connections.clear() }
                }
            stopDiscovery()
            sessions.forEach { it.close(intentional = true) }
            withTimeoutOrNull(2_000.milliseconds) {
                sessions.forEach { it.awaitClosed() }
            }
            val stoppingServer = server.also { server = null }
            runCatching { stoppingServer?.stop() }
                .onFailure { logWarn("Server shutdown failed: ${it.message}", it) }
            localAddresses = emptySet()
            val client =
                stateMutex.withLock {
                    httpClient.also { httpClient = null }
                }
            client?.close()
            stateMutex.withLock {
                accountIdentity = null
                peerById.clear()
                discoveredEndpoints.clear()
                autoConnectNotBeforeMs.clear()
                _state.value =
                    _state.value.copy(
                        enabled = false,
                        anonymous = false,
                        serverRunning = false,
                        serverPort = 0,
                        localAddress = "",
                        peers = emptyList(),
                        pairingRequests = emptyList(),
                    )
            }
        }

        fun pair(deviceId: String) {
            scope.launch {
                val peer =
                    stateMutex.withLock {
                        val current = peerById[deviceId]
                        if (current == null || current.serviceName.isBlank() || current.state.isLiveConnectionState()) {
                            val hasEndpoint = current?.serviceName?.isNotBlank()
                            logWarn("Pair with $deviceId skipped (state=${current?.state}, endpoint=$hasEndpoint).")
                            return@launch
                        }
                        autoConnectNotBeforeMs.remove(deviceId)
                        current
                    }
                connectTo(peer.host, peer.port, deviceId, peer.name, peer.serviceName, allowNewPairing = true)
            }
        }

        fun connectDirect(address: String) {
            val endpoint =
                parseDeviceConnectEndpoint(address) ?: run {
                    logWarn("Direct connection rejected: \"$address\" is not a LAN or VPN IPv4:port or [IPv6]:port endpoint.")
                    return
                }
            scope.launch {
                connectTo(endpoint.host, endpoint.port, null, endpoint.host, "", allowNewPairing = true)
            }
        }

        private fun autoConnect(deviceId: String) {
            scope.launch {
                suspend fun candidate(): PeerDevice? =
                    stateMutex.withLock {
                        val current = peerById[deviceId] ?: return@withLock null
                        val retryable =
                            current.state == PeerState.DISCOVERED || current.state == PeerState.DISCONNECTED ||
                                current.state == PeerState.ERROR
                        val paired = _state.value.pairedDevices.any { it.deviceId == deviceId }
                        val due = (autoConnectNotBeforeMs[deviceId] ?: 0L) <= nowMs()
                        current.takeIf { retryable && paired && due && _state.value.enabled }?.let { peer ->
                            val endpoint = discoveredEndpoints.first(deviceId)
                            if (endpoint != null) {
                                peer.copy(host = endpoint.host, port = endpoint.port, serviceName = endpoint.serviceName)
                            } else {
                                peer.takeIf { it.serviceName.isNotBlank() }
                            }
                        }
                    }
                var peer = candidate() ?: return@launch
                // Both sides discover each other; the larger id yields so one link wins instead of two racing.
                if (_state.value.localDeviceId > deviceId) {
                    delay(AUTO_CONNECT_YIELD.milliseconds)
                    peer = candidate() ?: return@launch
                }
                stateMutex.withLock { autoConnectNotBeforeMs[deviceId] = nowMs() + AUTO_CONNECT_RETRY }
                logInfo("Auto-connecting to paired device ${peer.name}.")
                connectTo(peer.host, peer.port, deviceId, peer.name, peer.serviceName, allowNewPairing = false)
            }
        }

        private val playbackEncodingMutex = Mutex()
        private var encodedPlayback: Pair<DcSyncMessage, ByteArray>? = null

        private suspend fun encodePlayback(message: DcSyncMessage): ByteArray =
            playbackEncodingMutex.withLock {
                encodedPlayback?.takeIf { it.first === message }?.second ?: DcJson.encodeToString(message).encodeToByteArray().also {
                    encodedPlayback = message to it
                }
            }

        internal suspend fun sendPlayback(
            deviceId: String,
            message: DcSyncMessage,
        ): Boolean {
            val session =
                stateMutex.withLock {
                    connections.values.firstOrNull { it.device.id == deviceId && it.supportsPlayback() }
                } ?: return false
            return session.queuePlayback(message)
        }

        fun approvePairing(requestId: String) {
            scope.launch {
                pairingSessionFor(requestId)?.approvePairing()
            }
        }

        fun rejectPairing(requestId: String) {
            scope.launch {
                pairingSessionFor(requestId)?.rejectPairing()
            }
        }

        suspend fun forget(deviceId: String) =
            withContext(NonCancellable) {
                if (settings.deviceConnectDefaultDeviceId == deviceId) {
                    remote.cancelPendingDefaultPlayback()
                }
                val identity = stateMutex.withLock { accountIdentity ?: identityProvider() }

                suspend fun closeMatchingSessions() {
                    val sessions = stateMutex.withLock { connections.values.filter { it.device.id == deviceId } }
                    sessions.forEach { it.close(intentional = true) }
                    withTimeoutOrNull(HANDSHAKE_TIMEOUT.milliseconds) {
                        sessions.forEach { it.awaitClosed() }
                    }
                }

                var firstDeleteFailure: Throwable? = null
                try {
                    pairStoreMutex.withLock { pairStore.forget(deviceId, identity) }
                } catch (error: Throwable) {
                    firstDeleteFailure = error
                } finally {
                    closeMatchingSessions()
                }

                val finalDeleteFailure =
                    runCatching {
                        // PAIR_COMMIT can save after revocation; delete again once matching sessions stop.
                        pairStoreMutex.withLock { pairStore.forget(deviceId, identity) }
                    }.exceptionOrNull()
                closeMatchingSessions()
                if (finalDeleteFailure != null) {
                    runCatching { refreshPairedDevices() }
                    logError(
                        "Could not revoke local Device Connect grant for $deviceId: ${finalDeleteFailure.message}",
                        finalDeleteFailure,
                    )
                    throw finalDeleteFailure
                }
                if (firstDeleteFailure != null) {
                    logWarn("Initial local grant deletion failed but the post-close retry succeeded.")
                }
                if (settings.deviceConnectDefaultDeviceId == deviceId) settings.deviceConnectDefaultDeviceId = ""
                refreshPairedDevices()
                logInfo("Forgot local Device Connect grant for $deviceId.")
            }

        suspend fun refreshPairingState() {
            refreshAccountIdentity()
        }

        fun rescan() {
            val running = _state.value.serverRunning
            val port = _state.value.serverPort
            if (!running || port <= 0 || localInfo == null) {
                logWarn("Start DeviceConnect before rescanning.")
                return
            }
            scope.launch {
                lifecycleMutex.withLock {
                    val info = localInfo ?: return@withLock
                    if (!_state.value.serverRunning || _state.value.serverPort != port) return@withLock
                    refreshAccountIdentity()
                    logInfo("Rescanning for devices…")
                    stopDiscovery()
                    startDiscovery(info, port)
                }
            }
        }

        private fun startServer(port: Int): Int {
            val priorServer = server.also { server = null }
            runCatching { priorServer?.stop() }
                .onFailure { logWarn("Prior server shutdown failed: ${it.message}", it) }
            val srv = LocalDeviceServer()
            val actual =
                try {
                    srv.start(
                        port = port,
                        onFailure = { onServerFailure(srv, it) },
                        onChannel = { channel -> handleIncomingChannel(channel) },
                    )
                } catch (e: Exception) {
                    runCatching { srv.stop() }
                    logError("Server error: ${e.message}", e)
                    return -1
                }
            server = srv
            return actual
        }

        private fun onServerFailure(
            failedServer: LocalDeviceServer,
            error: Throwable,
        ) {
            scope.launch {
                lifecycleMutex.withLock {
                    if (server !== failedServer) return@withLock
                    logError("Local server stopped: ${error.message}", error)
                    disposeLocked()
                    stateMutex.withLock { _state.value = _state.value.copy(serverFailed = true) }
                }
            }
        }

        private suspend fun startDiscovery(
            info: DeviceInfoPayload,
            port: Int,
        ) {
            if (context == null) {
                stateMutex.withLock { _state.value = _state.value.copy(discoveryFailed = true) }
                logWarn("No context available for discovery.")
                return
            }
            if (discovery != null) {
                stateMutex.withLock { _state.value = _state.value.copy(discoveryFailed = true) }
                discoveryFailed.value = true
                logWarn("Previous service discovery has not stopped.")
                return
            }
            var controller: DeviceDiscoveryController? = null
            try {
                val generation = ++discoveryGeneration
                stateMutex.withLock { _state.value = _state.value.copy(discoveryFailed = false) }
                discoveryFailed.value = false
                lateinit var started: DeviceDiscoveryController
                started =
                    DeviceDiscoveryController(
                        context = context!!,
                        onError = { message, throwable ->
                            logWarn(message, throwable)
                            scope.launch {
                                stateMutex.withLock {
                                    if (generation == discoveryGeneration) {
                                        _state.value = _state.value.copy(discoveryFailed = true)
                                    }
                                }
                            }
                        },
                        onClosed = { onDiscoveryClosed(started) },
                        onFailure = {
                            if (generation == discoveryGeneration) discoveryFailed.value = true
                        },
                    )
                controller = started
                started.registerService(
                    info.deviceId,
                    info.deviceName,
                    port,
                    DeviceConnectDeviceType.fromWireName(info.deviceType),
                )
                started.startDiscovery(
                    onFound = { device ->
                        scope.launch {
                            onDeviceDiscovered(device, generation)
                        }
                    },
                    onLost = { device ->
                        scope.launch {
                            onDeviceLost(device, generation)
                        }
                    },
                )
                discovery = started
                logInfo("Service discovery started.")
            } catch (e: Exception) {
                runCatching { controller?.close() }
                    .onFailure {
                        discovery = controller
                        logWarn("mDNS close failed: ${it.message}", it)
                    }
                if (e is CancellationException) throw e
                stateMutex.withLock { _state.value = _state.value.copy(discoveryFailed = true) }
                discoveryFailed.value = true
                logError("Discovery error: ${e.message}", e)
            }
        }

        private suspend fun stopDiscovery() {
            discoveryGeneration++
            discoveryFailed.value = false
            val controller = discovery
            val stopped =
                runCatching { controller?.close() }
                    .onFailure { logWarn("mDNS close failed: ${it.message}", it) }
                    .isSuccess
            if (stopped) discovery = null
            stateMutex.withLock {
                discoveredEndpoints.clear()
                val peers = peerById.iterator()
                while (peers.hasNext()) {
                    val peer = peers.next()
                    val retained = peerAfterDeviceConnectDiscoveryStopped(peer.value)
                    if (retained == null) peers.remove() else peer.setValue(retained)
                }
                rebuildPeers()
            }
        }

        private fun onDiscoveryClosed(controller: DeviceDiscoveryController) {
            scope.launch {
                lifecycleMutex.withLock {
                    if (discovery !== controller) return@withLock
                    discovery = null
                    val info = localInfo ?: return@withLock
                    val state = _state.value
                    if (
                        !settings.deviceConnectEnabled ||
                        !state.enabled ||
                        !state.serverRunning ||
                        state.serverPort !in 1..65535
                    ) {
                        logInfo("Discovery closed; not restarting (enabled=${state.enabled}, server=${state.serverRunning}).")
                        return@withLock
                    }
                    logWarn("Discovery closed unexpectedly; restarting.")
                    startDiscovery(info, state.serverPort)
                }
            }
        }

        private suspend fun onDeviceDiscovered(
            device: DiscoveredDevice,
            generation: Long,
        ): Boolean {
            if (!isValidDeviceId(device.deviceId) || !isDeviceConnectLanHost(device.host) || device.port !in 1..65535) {
                logWarn(
                    "Ignored invalid discovery record from ${device.host}:${device.port} " +
                        "(idValid=${isValidDeviceId(device.deviceId)}, lanHost=${isDeviceConnectLanHost(device.host)}).",
                )
                return false
            }
            val advertisedName = sanitizeDeviceName(device.deviceName.ifBlank { device.serviceName })
            var shouldAutoConnect = false
            val safeName =
                stateMutex.withLock {
                    if (!_state.value.enabled || generation != discoveryGeneration) {
                        logDebug("Discovery result for $advertisedName ignored: discovery is stale or Device Connect is off.")
                        return false
                    }
                    discoveredEndpoints.discover(device)
                    val existing = peerById[device.deviceId]
                    val endpoint = discoveredEndpoints.first(device.deviceId) ?: device
                    val retainEndpoint = existing?.state?.isLiveConnectionState() == true
                    val paired = _state.value.pairedDevices.firstOrNull { it.deviceId == device.deviceId }
                    val name =
                        paired
                            ?.deviceName
                            ?.let(::sanitizeDeviceName)
                            ?.takeIf(String::isNotBlank)
                            ?: advertisedName
                    val state =
                        when (existing?.state) {
                            PeerState.CONNECTED,
                            PeerState.CONNECTING,
                            PeerState.HANDSHAKING,
                            PeerState.ACCOUNT_MISMATCH,
                            PeerState.ACCOUNT_UNRESOLVED,
                            -> existing.state

                            else -> PeerState.DISCOVERED
                        }
                    peerById[device.deviceId] =
                        PeerDevice(
                            id = device.deviceId,
                            name = name,
                            host = if (retainEndpoint) checkNotNull(existing).host else endpoint.host,
                            port = if (retainEndpoint) checkNotNull(existing).port else endpoint.port,
                            state = state,
                            model = existing?.model?.takeIf(String::isNotBlank) ?: paired?.model.orEmpty(),
                            serviceName = if (retainEndpoint) checkNotNull(existing).serviceName else endpoint.serviceName,
                            deviceType =
                                selectDeviceConnectDeviceType(
                                    authenticated =
                                        existing
                                            ?.takeIf { it.state == PeerState.CONNECTED }
                                            ?.deviceType ?: DeviceConnectDeviceType.UNKNOWN,
                                    stored = paired?.deviceType ?: DeviceConnectDeviceType.UNKNOWN,
                                    advertised = device.deviceType,
                                ),
                        )
                    shouldAutoConnect = paired != null
                    rebuildPeers()
                    name
                }
            logInfo("Discovered $safeName @ ${device.host}:${device.port}")
            if (shouldAutoConnect) autoConnect(device.deviceId)
            return shouldAutoConnect
        }

        private suspend fun onDeviceLost(
            device: DiscoveredDevice,
            generation: Long,
        ) {
            stateMutex.withLock {
                if (!_state.value.enabled || generation != discoveryGeneration) return
                discoveredEndpoints.lost(device)
                val current = peerById[device.deviceId]
                val updated = current?.let { peerAfterLostDeviceConnectEndpoint(it, device) }
                when {
                    updated == null -> {
                        val alternative = discoveredEndpoints.first(device.deviceId)
                        if (alternative != null && current != null) {
                            peerById[device.deviceId] =
                                current.copy(
                                    host = alternative.host,
                                    port = alternative.port,
                                    serviceName = alternative.serviceName,
                                    state = PeerState.DISCOVERED,
                                )
                        } else {
                            peerById.remove(device.deviceId)
                        }
                    }

                    updated != current -> {
                        peerById[device.deviceId] = updated
                    }
                }
                rebuildPeers()
            }
            logInfo("Lost ${sanitizeDeviceName(device.deviceName.ifBlank { device.serviceName })}")
        }

        internal suspend fun reportDiscoveredDeviceForTest(device: DiscoveredDevice): Boolean =
            onDeviceDiscovered(device, discoveryGeneration)

        internal suspend fun awaitSessionClosedForTest(deviceId: String) {
            val sessions = stateMutex.withLock { connections.values.filter { it.device.id == deviceId } }
            sessions.forEach { it.awaitClosed() }
        }

        internal suspend fun attachChannelForTest(
            channel: DeviceChannel,
            isIncoming: Boolean,
            expectedPeerId: String? = null,
        ) {
            val expectedId = expectedPeerId.orEmpty()
            require(expectedPeerId == null || isValidDeviceId(expectedId)) { "invalid expected peer id" }
            val info = localInfoProvider()
            require(isValidDeviceId(info.deviceId)) { "invalid local device id" }
            localInfo = info
            val identity = refreshAccountIdentity()
            val connId = "${if (isIncoming) "in" else "out"}-${stateMutex.withLock { ++connectionCounter }}"
            check(connectionSlots.tryAcquire()) { "Device Connect test connection limit reached" }
            val initial =
                PeerDevice(
                    id = expectedPeerId ?: connId,
                    name = if (isIncoming) "Incoming" else expectedId,
                    host = channel.remoteHost.orEmpty(),
                    port = if (isIncoming) 0 else 1,
                    state = PeerState.HANDSHAKING,
                    serviceName = if (expectedPeerId == null) "" else "test-$expectedId",
                )
            val session =
                runCatching { PeerSession(connId, initial, channel, isIncoming, identity, expectedPeerId = expectedPeerId) }
                    .getOrElse { error ->
                        connectionSlots.release()
                        channel.close()
                        throw error
                    }
            stateMutex.withLock {
                _state.value =
                    _state.value.copy(
                        enabled = true,
                        serverRunning = true,
                        localDeviceId = info.deviceId,
                        localDeviceName = info.deviceName,
                        localDeviceType = DeviceConnectDeviceType.fromWireName(info.deviceType),
                        anonymous = identity.anonymous,
                    )
                if (!isIncoming) peerById[initial.id] = initial
                connections[connId] = session
                rebuildPeers()
            }
            session.start()
        }

        private suspend fun handleIncomingChannel(channel: DeviceChannel) {
            val identity = refreshAccountIdentity()
            val connId = "in-${stateMutex.withLock { ++connectionCounter }}"
            val remote = channel.remoteHost
            val admitted =
                stateMutex.withLock {
                    if (!incomingAdmission.tryAcquire(remote.orEmpty())) {
                        false
                    } else if (!connectionSlots.tryAcquire()) {
                        incomingAdmission.release(remote.orEmpty())
                        false
                    } else {
                        true
                    }
                }
            if (!admitted) {
                logWarn("[$connId] rejecting incoming WebSocket from ${remote ?: "unknown"}: connection admission limit reached.")
                channel.close()
                return
            }
            logInfo("[$connId] incoming WebSocket from ${remote ?: "unknown address"} (connection accepted).")
            val placeholder =
                PeerDevice(
                    id = connId,
                    name = "Incoming",
                    host = remote ?: "",
                    port = 0,
                    state = PeerState.HANDSHAKING,
                )
            val session =
                runCatching {
                    PeerSession(
                        connId,
                        placeholder,
                        channel,
                        isIncoming = true,
                        identity = identity,
                        incomingAdmissionHeld = true,
                    )
                }.getOrElse {
                    logError("[$connId] could not start session for ${remote ?: "unknown"}: ${it.message}", it)
                    connectionSlots.release()
                    stateMutex.withLock { incomingAdmission.release(remote.orEmpty()) }
                    channel.close()
                    return
                }
            val registered =
                stateMutex.withLock {
                    if (!_state.value.serverRunning) {
                        false
                    } else {
                        connections[connId] = session
                        true
                    }
                }
            if (!registered) {
                logWarn("[$connId] incoming WebSocket from ${remote ?: "unknown"} dropped: server is not running.")
                connectionSlots.release()
                stateMutex.withLock { incomingAdmission.release(remote.orEmpty()) }
                channel.close()
                return
            }
            session.start()
            // Returning would close the Ktor WebSocket; suspend until the link closes.
            session.awaitClosed()
        }

        private suspend fun connectTo(
            host: String,
            port: Int,
            expectedDeviceId: String?,
            deviceName: String,
            serviceName: String,
            allowNewPairing: Boolean,
        ) {
            val deviceId = expectedDeviceId ?: "direct:$host:$port"
            if (host.isBlank() || port !in 1..65535 || (expectedDeviceId != null && !isValidDeviceId(expectedDeviceId))) {
                val idValid = expectedDeviceId == null || isValidDeviceId(expectedDeviceId)
                logWarn(
                    "Ignored invalid connection target $host:$port " +
                        "(hostBlank=${host.isBlank()}, portValid=${port in 1..65535}, idValid=$idValid).",
                )
                remote.onPeerDisconnected(deviceId, intentional = false)
                return
            }
            val safeName = sanitizeDeviceName(deviceName).ifBlank { deviceId }
            val shouldConnect =
                stateMutex.withLock {
                    val existing = peerById[deviceId]
                    val liveEndpoint =
                        expectedDeviceId == null &&
                            peerById.values.any {
                                it.host == host && it.port == port && it.state.isLiveConnectionState()
                            }
                    val skip =
                        when {
                            !_state.value.enabled -> "Device Connect is off"
                            existing?.state?.isLiveConnectionState() == true -> "already ${existing.state}"
                            liveEndpoint -> "endpoint $host:$port already has a live link"
                            else -> null
                        }
                    if (skip != null) {
                        logDebug("Connection to $safeName ($host:$port) skipped: $skip.")
                        false
                    } else {
                        if (expectedDeviceId == null) _state.value = _state.value.copy(directConnectionFailed = false)
                        peerById[deviceId] =
                            (existing ?: PeerDevice(deviceId, safeName, host, port, PeerState.CONNECTING))
                                .copy(
                                    name = safeName,
                                    host = host,
                                    port = port,
                                    state = PeerState.CONNECTING,
                                    serviceName = serviceName,
                                )
                        rebuildPeers()
                        true
                    }
                }
            if (!shouldConnect) return
            val identity = refreshAccountIdentity()
            val client =
                stateMutex.withLock {
                    httpClient ?: createDeviceConnectHttpClient().also { httpClient = it }
                }
            val connId = "out-${stateMutex.withLock { ++connectionCounter }}"
            if (!connectionSlots.tryAcquire()) {
                logWarn("[$connId] rejecting outgoing connection: limit ($MAX_CONNECTIONS) reached.")
                finishFailedConnectAttempt(deviceId, host, port, serviceName, if (expectedDeviceId == null) null else PeerState.ERROR)
                if (expectedDeviceId == null) reportDirectConnectionFailure()
                return
            }
            var ownsSlot = true
            try {
                logInfo("Connecting to $safeName ($host:$port)…")
                val sessionWs: ClientWebSocketSession = client.webSocketSession { url(deviceConnectWebSocketUrl(host, port)) }
                val channel = ClientDeviceChannel(sessionWs, host)
                val peer =
                    stateMutex.withLock {
                        peerById[deviceId] ?: PeerDevice(
                            id = deviceId,
                            name = safeName,
                            host = host,
                            port = port,
                            state = PeerState.CONNECTING,
                            serviceName = serviceName,
                        )
                    }
                val link =
                    PeerSession(
                        connectionId = connId,
                        initial = peer.copy(state = PeerState.HANDSHAKING),
                        channel = channel,
                        isIncoming = false,
                        identity = identity,
                        allowNewPairing = allowNewPairing,
                        expectedPeerId = expectedDeviceId,
                    )
                val registered =
                    stateMutex.withLock {
                        if (!_state.value.enabled) {
                            false
                        } else {
                            connections[connId] = link
                            true
                        }
                    }
                if (!registered) {
                    logWarn("[$connId] connection to $safeName ($host:$port) dropped: Device Connect turned off while connecting.")
                    channel.close()
                    connectionSlots.release()
                    return
                }
                link.start()
                ownsSlot = false
                logInfo("Outgoing connection to $safeName ($host:$port) opened.")
            } catch (e: Exception) {
                if (ownsSlot) connectionSlots.release()
                finishFailedConnectAttempt(deviceId, host, port, serviceName)
                if (expectedDeviceId == null) reportDirectConnectionFailure()
                logWarn(
                    "Connection endpoint for $safeName ($host:$port) is unavailable; discovery remains active: ${e.message}",
                    e,
                )
            }
        }

        private suspend fun reportDirectConnectionFailure() {
            stateMutex.withLock {
                if (_state.value.enabled) _state.value = _state.value.copy(directConnectionFailed = true)
            }
        }

        private fun startHeartbeat() {
            heartbeatJob?.cancel()
            var network = PlaybackNetworkStateStore.current
            var pairs = settings.deviceConnectPairedDevices
            heartbeatJob =
                scope.launch {
                    stateMutex.withLock { discoveryRetry.reset(nowMs()) }
                    while (isActive && _state.value.serverRunning) {
                        val identity = accountIdentity ?: identityProvider()
                        val failed = discoveryFailed.value
                        val hasPeers =
                            stateMutex.withLock {
                                connections.isNotEmpty() || _state.value.pairedDevices.isNotEmpty() ||
                                    _state.value.peers.isNotEmpty()
                            }

                        suspend fun awaitMaintenanceChange() {
                            combine(
                                _state,
                                PlaybackNetworkStateStore.state,
                                snapshotFlow { identityProvider() to settings.deviceConnectPairedDevices },
                                discoveryFailed,
                            ) { state, currentNetwork, account, currentFailure ->
                                !state.serverRunning || currentFailure != failed || currentNetwork != network ||
                                    account != (identity to pairs) ||
                                    (!hasPeers && (state.peers.isNotEmpty() || state.pairedDevices.isNotEmpty()))
                            }.first { it }
                        }
                        if (hasPeers || failed) {
                            withTimeoutOrNull(PING_INTERVAL) { awaitMaintenanceChange() }
                        } else {
                            awaitMaintenanceChange()
                        }
                        network = PlaybackNetworkStateStore.current
                        pairs = settings.deviceConnectPairedDevices
                        refreshDiscoveryAfterNetworkChange()
                        if (!_state.value.serverRunning) break
                        refreshAccountIdentity()
                        stateMutex.withLock { peerById.keys.toList() }.forEach(::autoConnect)
                        restartDiscoveryIfPairedDeviceMissing()
                        remote.discoverPlayback()
                        val now =
                            kotlin.time.Clock.System
                                .now()
                                .toEpochMilliseconds()
                        stateMutex.withLock { connections.values.toList() }.forEach { it.ping(now) }
                    }
                }
        }

        private suspend fun restartDiscoveryIfPairedDeviceMissing() {
            val shouldRestart =
                stateMutex.withLock {
                    val current = _state.value
                    val missing =
                        current.pairedDevices
                            .filter { paired ->
                                current.peers.none { it.id == paired.deviceId && it.state == PeerState.CONNECTED }
                            }.mapTo(mutableSetOf()) { it.deviceId }
                    discoveryRetry.shouldRestart(nowMs(), missing, discoveryFailed.value)
                }
            if (!shouldRestart) return
            lifecycleMutex.withLock {
                val info = localInfo ?: return
                val port = _state.value.serverPort
                if (!_state.value.serverRunning || port <= 0) return
                logInfo("Retrying service discovery after a failure or a missing paired device.")
                stopDiscovery()
                startDiscovery(info, port)
            }
        }

        private suspend fun refreshDiscoveryAfterNetworkChange() {
            val addresses = localAddressProvider()
            lifecycleMutex.withLock {
                val current = _state.value
                if (!current.serverRunning || localAddresses == addresses) return@withLock
                localAddresses = addresses
                stateMutex.withLock {
                    discoveryRetry.reset(nowMs())
                    _state.value = _state.value.copy(localAddress = addresses.sorted().firstOrNull().orEmpty())
                }
                logInfo("Local network changed; restarting service discovery.")
                stopDiscovery()
                localInfo?.let { startDiscovery(it, current.serverPort) }
            }
        }

        private inner class PeerSession(
            val connectionId: String,
            initial: PeerDevice,
            val channel: DeviceChannel,
            val isIncoming: Boolean,
            private val identity: AccountIdentitySnapshot,
            private val allowNewPairing: Boolean = true,
            private var incomingAdmissionHeld: Boolean = false,
            private val expectedPeerId: String? = if (isIncoming) null else initial.id,
        ) {
            private val keypair = DeviceCrypto.generateEcKeyPair()
            private val localRole = if (isIncoming) DeviceConnectRole.RESPONDER else DeviceConnectRole.INITIATOR
            private val peerRole = if (isIncoming) DeviceConnectRole.INITIATOR else DeviceConnectRole.RESPONDER
            private val helloNonce = DeviceCrypto.secureRandomBytes(DEVICE_CONNECT_HELLO_NONCE_BYTES)
            private val localHelloMaterial =
                DeviceConnectHelloMaterial(
                    role = localRole,
                    deviceId = checkNotNull(localInfo) { "local device info unavailable" }.deviceId,
                    nonce = helloNonce,
                    publicKey = keypair.publicKeyUncompressed,
                )
            private val localHello =
                HelloPayload(
                    version = DEVICE_CONNECT_PROTOCOL_VERSION,
                    role = localRole.wireName,
                    deviceId = localHelloMaterial.deviceId,
                    nonce = localHelloMaterial.nonce.toBase64(),
                    publicKey = localHelloMaterial.publicKey.toBase64(),
                )
            private val localHelloCommitment = deviceConnectHelloCommitment(localHelloMaterial)
            private var peerHelloCommit: HelloCommitPayload? = null
            private var peerHello: HelloPayload? = null
            private var transcriptHash: ByteArray? = null
            private var outboundKey: ByteArray? = null
            private var inboundKey: ByteArray? = null
            private var pairSecret: ByteArray? = null
            private var localIdentity: IdentityPayload? = null
            private var peerIdentity: IdentityPayload? = null
            private var savedPair: DevicePair? = null
            private var localHasPair = false
            private var peerHasPair = false
            private var ourPairProofSent = false
            private var peerPairProofVerified = false
            private var pairRequestSent = false
            private var pairRequestReceived = false
            private var localPairApproved = false
            private var peerPairApproved = false
            private var localPairCommitted = false
            private var peerPairCommitted = false
            private var pairingExpiresAt = 0L
            private var pairingDeadline: TimeMark? = null

            @Volatile
            private var helloCommitSent = false

            @Volatile
            private var helloSent = false

            @Volatile var device: PeerDevice = initial
                private set
            private var collectJob: Job? = null
            private var handshakeTimeoutJob: Job? = null
            private var pairingTimeoutJob: Job? = null
            private val done = CompletableDeferred<Unit>()

            private val sendMutex = Mutex()
            private val playbackOutbox = DeviceConnectPlaybackOutbox(scope, ::encodeAndSendPlayback, { close() })

            suspend fun queuePlayback(message: DcSyncMessage): Boolean =
                supportsPlayback() && ensureSessionIdentityCurrent() && playbackOutbox.offer(message)

            private suspend fun encodeAndSendPlayback(message: DcSyncMessage): Boolean {
                val bytes = encodePlayback(message)
                if (bytes.size > DEVICE_CONNECT_MAX_PLAYBACK_BYTES) {
                    remote.reportError(DcSyncError.TOO_LARGE)
                    return false
                }
                return sendPlayback(bytes)
            }

            private val pairingMutex = Mutex()
            private var outSeq = 0L
            private var lastInSeq = -1L

            @Volatile private var lastReceivedAt = nowMs()

            @Volatile var closing = false
                private set

            @Volatile private var intentionalClosure = false

            @Volatile private var playbackVersion = 0
            private val playbackAssembler = DeviceConnectPlaybackAssembler()
            private val playbackReceiveMutex = Mutex()
            private var playbackTimeoutJob: Job? = null

            fun supportsPlayback(): Boolean =
                !closing && device.state == PeerState.CONNECTED &&
                    advertisedPlaybackVersion == DEVICE_CONNECT_PLAYBACK_VERSION && playbackVersion == DEVICE_CONNECT_PLAYBACK_VERSION

            private val logTag: String
                get() = "[$connectionId]"

            suspend fun awaitClosed() = done.await()

            fun start() {
                logInfo("$logTag ${if (isIncoming) "incoming" else "outgoing"} session started; sending HELLO commitment.")
                // An undispatched collector still runs cleanup if disabling closes the session before work starts.
                handshakeTimeoutJob =
                    scope.launch {
                        delay(HANDSHAKE_TIMEOUT.milliseconds)
                        if (device.state != PeerState.CONNECTED) {
                            logWarn("$logTag handshake timed out.")
                            close()
                        }
                    }
                collectJob =
                    scope.launch(start = CoroutineStart.UNDISPATCHED) {
                        try {
                            sendHelloCommit()
                            channel.incoming.collect { handleText(it) }
                        } catch (e: Exception) {
                            logInfo("$logTag channel closed: ${e.message}")
                            try {
                                channel.close()
                            } catch (_: Exception) {
                            }
                        } finally {
                            withContext(NonCancellable) { onClosed() }
                        }
                    }
            }

            private suspend fun sendHelloCommit() {
                val payload =
                    HelloCommitPayload(
                        version = DEVICE_CONNECT_PROTOCOL_VERSION,
                        role = localRole.wireName,
                        deviceId = localHello.deviceId,
                        commitment = localHelloCommitment.toBase64(),
                    )
                val wire =
                    DcWire(
                        type = DcMessageTypes.HELLO_COMMIT,
                        payload = DcJson.encodeToString(payload),
                        version = DEVICE_CONNECT_PROTOCOL_VERSION,
                        encrypted = false,
                    )
                channel.send(DcJson.encodeToString(wire))
                helloCommitSent = true
                logDebug("$logTag → hello_commit (cleartext)")
            }

            private suspend fun sendHello() {
                check(helloCommitSent) { "HELLO before local commitment" }
                val wire =
                    DcWire(
                        type = DcMessageTypes.HELLO,
                        payload = DcJson.encodeToString(localHello),
                        version = DEVICE_CONNECT_PROTOCOL_VERSION,
                        encrypted = false,
                    )
                channel.send(DcJson.encodeToString(wire))
                helloSent = true
                logDebug("$logTag → hello (cleartext)")
            }

            private suspend fun handleText(text: String) {
                if (closing) return
                if (text.length.toLong() > DEVICE_CONNECT_MAX_FRAME_BYTES ||
                    text.encodeToByteArray().size > DEVICE_CONNECT_MAX_FRAME_BYTES
                ) {
                    protocolViolation("oversized frame")
                    return
                }
                val wire =
                    try {
                        DcJson.decodeFromString<DcWire>(text)
                    } catch (_: Exception) {
                        protocolViolation("malformed frame")
                        return
                    }
                val safeType = sanitizeDeviceName(wire.type).ifBlank { "unknown message" }
                if (!isSupportedDeviceConnectVersion(wire.version)) {
                    protocolViolation("unsupported protocol version")
                    return
                }
                if (wire.type == DcMessageTypes.HELLO_COMMIT || wire.type == DcMessageTypes.HELLO) {
                    if (wire.encrypted || wire.seq != 0L) {
                        protocolViolation("invalid $safeType envelope")
                        return
                    }
                    logDebug("$logTag ← $safeType")
                    if (wire.type == DcMessageTypes.HELLO_COMMIT) {
                        onHelloCommit(wire)
                    } else {
                        onHello(wire)
                    }
                    return
                }
                if (!wire.encrypted || wire.seq <= 0L || wire.type !in DcMessageTypes.ENCRYPTED_TYPES) {
                    protocolViolation("cleartext or unsequenced $safeType")
                    return
                }
                if (!isNewDeviceConnectSequence(wire.seq, lastInSeq)) {
                    logWarn("$logTag dropped replayed/old frame (seq=${wire.seq}, last=$lastInSeq).")
                    return
                }
                val key = inboundKey
                if (key == null) {
                    protocolViolation("encrypted $safeType before HELLO")
                    return
                }
                val payloadJson =
                    try {
                        DeviceCrypto
                            .aesGcmDecrypt(key, wire.payload.fromBase64(), deviceConnectAad(wire.type, wire.seq))
                            .decodeToString()
                    } catch (_: Exception) {
                        protocolViolation("unauthenticated $safeType frame (seq=${wire.seq})")
                        return
                    }
                lastInSeq = wire.seq
                lastReceivedAt = nowMs()
                if (wire.type != DcMessageTypes.PLAYBACK && wire.type != DcMessageTypes.PING && wire.type != DcMessageTypes.PONG) {
                    logDebug("$logTag ← $safeType (enc, seq=${wire.seq})")
                }
                val payload =
                    try {
                        decodePayload(wire.type, payloadJson)
                    } catch (_: Exception) {
                        protocolViolation("invalid $safeType payload")
                        return
                    }
                val isPairingMessage =
                    wire.type in
                        setOf(
                            DcMessageTypes.IDENTITY,
                            DcMessageTypes.PAIR_PROOF,
                            DcMessageTypes.PAIR_REQUEST,
                            DcMessageTypes.PAIR_APPROVE,
                            DcMessageTypes.PAIR_COMMIT,
                            DcMessageTypes.AUTH_RESULT,
                        )
                if (device.state == PeerState.CONNECTED && isPairingMessage) {
                    protocolViolation("$safeType in ${device.state} state")
                    return
                }
                if (device.state != PeerState.CONNECTED && !isPairingMessage && payload !is ByePayload) {
                    protocolViolation("$safeType before pairing completed")
                    return
                }
                if (!isDeviceConnectMessageAllowedBeforeIdentity(wire.type, peerIdentity != null)) {
                    protocolViolation("$safeType before verified identity")
                    return
                }
                if (peerIdentity != null && !ensureSessionIdentityCurrent()) return
                when (payload) {
                    is IdentityPayload -> {
                        onIdentity(payload)
                    }

                    is PairProofPayload -> {
                        pairingMutex.withLock { onPairProof(payload) }
                    }

                    is PairRequestPayload -> {
                        pairingMutex.withLock { onPairRequest(payload) }
                    }

                    is PairApprovalPayload -> {
                        pairingMutex.withLock { onPairApproval(payload) }
                    }

                    is PairCommitPayload -> {
                        pairingMutex.withLock { onPairCommit(payload) }
                    }

                    is AuthResultPayload -> {
                        onAuthResult(payload)
                    }

                    is DeviceInfoPayload -> {
                        onDeviceInfo(payload)
                    }

                    is PlaybackChunkPayload -> {
                        onPlaybackChunk(payload)
                    }

                    is PingPayload -> {
                        onPing(payload)
                    }

                    is PongPayload -> {}

                    is ByePayload -> {
                        logInfo("$logTag peer said bye.")
                        close(intentional = true, sendBye = false)
                    }

                    null -> {
                        protocolViolation("unknown encrypted message type $safeType")
                    }

                    else -> {
                        protocolViolation("unsupported $safeType payload")
                    }
                }
            }

            private suspend fun onHelloCommit(wire: DcWire) {
                if (peerHelloCommit != null || peerHello != null) {
                    protocolViolation("duplicate HELLO commitment")
                    return
                }
                val info =
                    localInfo ?: run {
                        protocolViolation("local device info unavailable")
                        return
                    }
                val commit =
                    try {
                        DcJson.decodeFromString<HelloCommitPayload>(wire.payload).also {
                            require(
                                isValidRemoteHelloCommit(
                                    commit = it,
                                    expectedRole = peerRole,
                                    localDeviceId = info.deviceId,
                                    expectedDiscoveredDeviceId = expectedPeerId,
                                ),
                            ) { "invalid hello commitment" }
                        }
                    } catch (_: Exception) {
                        protocolViolation("invalid HELLO commitment")
                        return
                    }
                peerHelloCommit = commit
                sendHello()
            }

            private suspend fun onHello(wire: DcWire) {
                if (peerHello != null) {
                    protocolViolation("duplicate HELLO")
                    return
                }
                val commit =
                    peerHelloCommit ?: run {
                        protocolViolation("HELLO before commitment")
                        return
                    }
                val info =
                    localInfo ?: run {
                        protocolViolation("local device info unavailable")
                        return
                    }
                val hello =
                    try {
                        DcJson.decodeFromString<HelloPayload>(wire.payload).also {
                            require(
                                isValidRemoteHello(
                                    hello = it,
                                    expectedRole = peerRole,
                                    localDeviceId = info.deviceId,
                                    expectedDiscoveredDeviceId = expectedPeerId,
                                ),
                            ) { "invalid hello" }
                        }
                    } catch (_: Exception) {
                        protocolViolation("invalid HELLO")
                        return
                    }
                if (hello.version != commit.version ||
                    hello.role != commit.role ||
                    hello.deviceId != commit.deviceId
                ) {
                    protocolViolation("HELLO does not match commitment metadata")
                    return
                }
                val peerMaterial =
                    DeviceConnectHelloMaterial(
                        role = peerRole,
                        deviceId = hello.deviceId,
                        nonce = hello.nonce.fromBase64(),
                        publicKey = hello.publicKey.fromBase64(),
                    )
                if (!deviceConnectHelloCommitmentMatches(peerMaterial, commit.commitment)) {
                    protocolViolation("HELLO commitment mismatch")
                    return
                }
                val shared =
                    try {
                        DeviceCrypto.deriveSharedSecret(keypair.privateKeyPkcs8, peerMaterial.publicKey)
                    } catch (_: Exception) {
                        protocolViolation("invalid HELLO key")
                        return
                    }
                val transcript =
                    try {
                        deviceConnectTranscriptHash(localHelloMaterial, peerMaterial)
                    } catch (_: Exception) {
                        protocolViolation("invalid HELLO transcript")
                        return
                    }
                peerHello = hello
                transcriptHash = transcript
                outboundKey = deviceConnectTrafficKey(shared, transcript, localRole)
                inboundKey = deviceConnectTrafficKey(shared, transcript, peerRole)
                pairSecret = deviceConnectPairSecret(shared, transcript)
                val discovered =
                    stateMutex.withLock {
                        if (!isIncoming && expectedPeerId == null) peerById.remove(device.id)
                        peerById[hello.deviceId]
                    }
                device =
                    device.copy(
                        id = hello.deviceId,
                        name = discovered?.name ?: hello.deviceId,
                        model = discovered?.model.orEmpty(),
                        deviceType = discovered?.deviceType ?: device.deviceType,
                    )
                if (!retainPreferredSessionAfterHello()) {
                    logInfo("$logTag duplicate peer session closed before pairing.")
                    return
                }
                if (!isIncoming && expectedPeerId == null && !notifyPeerState(hello.deviceId, device.name, PeerState.HANDSHAKING)) return
                logInfo("$logTag HELLO received; v$DEVICE_CONNECT_PROTOCOL_VERSION directional session keys derived.")
                sendIdentity()
            }

            private suspend fun retainPreferredSessionAfterHello(): Boolean {
                val peerId = peerHello?.deviceId ?: return false
                val localId = localInfo?.deviceId ?: return false
                val losers =
                    stateMutex.withLock {
                        if (closing) return false
                        val candidates =
                            connections.values.filter {
                                it.device.id == peerId && it.transcriptHash != null && !it.closing
                            }
                        if (candidates.size < 2) {
                            emptyList()
                        } else {
                            val connected = candidates.firstOrNull { it.device.state == PeerState.CONNECTED }
                            val preferredIncoming = localId > peerId
                            val winner =
                                connected ?: candidates
                                    .filter { it.isIncoming == preferredIncoming }
                                    .ifEmpty { candidates }
                                    .minBy { checkNotNull(it.transcriptHash).toBase64() }
                            (candidates - winner).also { duplicates ->
                                duplicates.forEach { it.close() }
                            }
                        }
                    }
                return this !in losers
            }

            private suspend fun sendIdentity() {
                val info = localInfo ?: return
                val peer = peerHello ?: return
                val transcript = transcriptHash ?: return
                val pair = pairStoreMutex.withLock { pairStore.find(peer.deviceId, identity) }
                savedPair = pair
                localHasPair = pair != null
                val payload =
                    IdentityPayload(
                        fingerprint = identity.fingerprint,
                        accountProof =
                            if (identity.anonymous || !identity.resolved) {
                                ""
                            } else {
                                deviceConnectProof(
                                    secret = identity.secret.encodeToByteArray(),
                                    phase = identityProofPhase(identity.fingerprint, identity.anonymous, identity.resolved, localHasPair),
                                    senderRole = localRole,
                                    senderDeviceId = info.deviceId,
                                    receiverDeviceId = peer.deviceId,
                                    transcriptHash = transcript,
                                )
                            },
                        anonymous = identity.anonymous,
                        resolved = identity.resolved,
                        hasPair = localHasPair,
                        supportsCodePairing = supportsCodePairing,
                    )
                localIdentity = payload
                sendEncrypted(DcMessageTypes.IDENTITY, payload)
            }

            private suspend fun onIdentity(payload: IdentityPayload) {
                if (peerIdentity != null) {
                    protocolViolation("duplicate identity")
                    return
                }
                if (!ensureSessionIdentityCurrent()) return
                val info = localInfo ?: return
                val local = localIdentity ?: return
                val peer =
                    peerHello ?: run {
                        protocolViolation("identity before hello")
                        return
                    }
                val transcript = transcriptHash ?: return
                if (!identity.resolved || !payload.resolved) {
                    logWarn("$logTag account identity is unresolved; pairing was rejected.")
                    notifyPeerState(peer.deviceId, device.name, PeerState.ACCOUNT_UNRESOLVED)
                    rejectAndClose(REJECT_ACCOUNT_UNRESOLVED)
                    return
                }
                val sameMode = payload.anonymous == identity.anonymous
                val sameFingerprint = payload.fingerprint == identity.fingerprint
                val codePairing = local.supportsCodePairing && payload.supportsCodePairing
                val proofValid =
                    if (codePairing) {
                        identity.resolved && payload.resolved
                    } else if (identity.anonymous) {
                        payload.accountProof.isEmpty() && payload.fingerprint.isEmpty() && payload.resolved
                    } else {
                        identity.resolved && payload.resolved &&
                            deviceConnectProofMatches(
                                secret = identity.secret.encodeToByteArray(),
                                proof = payload.accountProof,
                                phase = identityProofPhase(payload.fingerprint, payload.anonymous, payload.resolved, payload.hasPair),
                                senderRole = peerRole,
                                senderDeviceId = peer.deviceId,
                                receiverDeviceId = info.deviceId,
                                transcriptHash = transcript,
                            )
                    }
                if (!sameMode || !sameFingerprint || !proofValid) {
                    logWarn("$logTag account mismatch; pairing was rejected.")
                    notifyPeerState(peer.deviceId, device.name, PeerState.ACCOUNT_MISMATCH)
                    rejectAndClose(REJECT_ACCOUNT_MISMATCH)
                    return
                }
                if (codePairing) {
                    val pairingTranscript = deviceConnectCodePairingTranscript(transcript, localRole, local, payload)
                    pairSecret = deviceConnectPairSecret(checkNotNull(pairSecret), pairingTranscript)
                    transcriptHash = pairingTranscript
                }
                peerIdentity = payload
                peerHasPair = payload.hasPair
                if (deviceConnectPairingPath(localHasPair, peerHasPair) == DeviceConnectPairingPath.RECONNECT) {
                    val pair =
                        savedPair ?: run {
                            protocolViolation("missing advertised pair record")
                            return
                        }
                    sendPairProof(pair)
                } else if (!isIncoming) {
                    if (allowNewPairing) {
                        pairingMutex.withLock { beginNewPairing() }
                    } else {
                        logInfo("$logTag peer is not paired; skipping automatic connection.")
                        close()
                    }
                }
            }

            private fun identityProofPhase(
                fingerprint: String,
                anonymous: Boolean,
                resolved: Boolean,
                hasPair: Boolean,
            ): String = "$PROOF_IDENTITY|$fingerprint|$anonymous|$resolved|$hasPair"

            private suspend fun sendPairProof(pair: DevicePair) {
                if (ourPairProofSent) return
                val info = localInfo ?: return
                val peer = peerHello ?: return
                val transcript = transcriptHash ?: return
                ourPairProofSent = true
                sendEncrypted(
                    DcMessageTypes.PAIR_PROOF,
                    PairProofPayload(
                        deviceId = info.deviceId,
                        proof =
                            deviceConnectProof(
                                secret = pair.secret,
                                phase = PROOF_RECONNECT,
                                senderRole = localRole,
                                senderDeviceId = info.deviceId,
                                receiverDeviceId = peer.deviceId,
                                transcriptHash = transcript,
                            ),
                    ),
                )
            }

            private suspend fun onPairProof(payload: PairProofPayload) {
                val info = localInfo ?: return
                val peer = peerHello ?: return
                val transcript = transcriptHash ?: return
                if (payload.deviceId != peer.deviceId ||
                    pairRequestSent ||
                    pairRequestReceived ||
                    deviceConnectPairingPath(localHasPair, peerHasPair) != DeviceConnectPairingPath.RECONNECT
                ) {
                    protocolViolation("unexpected pair proof")
                    return
                }
                val cachedPair = savedPair
                val pair =
                    cachedPair?.let { cached ->
                        pairStoreMutex.withLock {
                            pairStore
                                .find(peer.deviceId, identity)
                                ?.takeIf { it.secret.contentEquals(cached.secret) }
                        }
                    }
                if (pair == null ||
                    !deviceConnectProofMatches(
                        secret = pair.secret,
                        proof = payload.proof,
                        phase = PROOF_RECONNECT,
                        senderRole = peerRole,
                        senderDeviceId = peer.deviceId,
                        receiverDeviceId = info.deviceId,
                        transcriptHash = transcript,
                    )
                ) {
                    rejectAndClose(REJECT_NOT_PAIRED)
                    return
                }
                peerPairProofVerified = true
                savedPair = pair
                completeReconnectIfReady()
            }

            private suspend fun completeReconnectIfReady() {
                if (!ourPairProofSent || !peerPairProofVerified || device.state == PeerState.CONNECTED) return
                if (!ensureSessionIdentityCurrent()) return
                if (!notifyPeerState(device.id, device.name, PeerState.CONNECTED)) return
                sendDeviceInfo()
                logInfo("$logTag paired reconnect accepted for ${device.name}.")
            }

            private suspend fun beginNewPairing() {
                if (pairRequestSent) return
                pairRequestSent = true
                startPairingTimeout()
                if (!sendPairRequest()) return
                val requiresApproval = requiresCodeApproval()
                publishPairingRequest(requiresApproval = requiresApproval)
                if (!requiresApproval) {
                    localPairApproved = true
                    if (!sendPairApproval()) return
                }
            }

            private suspend fun onPairRequest(payload: PairRequestPayload) {
                if (!ensurePairingDeadline()) return
                val info = localInfo ?: return
                val peer = peerHello ?: return
                val secret = pairSecret ?: return
                val transcript = transcriptHash ?: return
                if (!isIncoming ||
                    pairRequestReceived ||
                    deviceConnectPairingPath(localHasPair, peerHasPair) != DeviceConnectPairingPath.NEW_PAIR ||
                    payload.deviceId != peer.deviceId ||
                    !deviceConnectProofMatches(
                        secret = secret,
                        proof = payload.proof,
                        phase = PROOF_PAIR_REQUEST,
                        senderRole = peerRole,
                        senderDeviceId = peer.deviceId,
                        receiverDeviceId = info.deviceId,
                        transcriptHash = transcript,
                    )
                ) {
                    protocolViolation("invalid pair request")
                    return
                }
                pairRequestReceived = true
                startPairingTimeout()
                publishPairingRequest(requiresApproval = true)
            }

            private suspend fun sendPairRequest(): Boolean {
                val info = localInfo ?: return failPairingSend("request")
                val peer = peerHello ?: return failPairingSend("request")
                val secret = pairSecret ?: return failPairingSend("request")
                val transcript = transcriptHash ?: return failPairingSend("request")
                return try {
                    sendEncrypted(
                        DcMessageTypes.PAIR_REQUEST,
                        PairRequestPayload(
                            deviceId = info.deviceId,
                            proof =
                                deviceConnectProof(
                                    secret = secret,
                                    phase = PROOF_PAIR_REQUEST,
                                    senderRole = localRole,
                                    senderDeviceId = info.deviceId,
                                    receiverDeviceId = peer.deviceId,
                                    transcriptHash = transcript,
                                ),
                        ),
                    )
                    true
                } catch (error: Exception) {
                    failPairingSend("request", error)
                }
            }

            suspend fun approvePairing() {
                pairingMutex.withLock {
                    if (!hasPendingPairing() || localPairApproved) return@withLock
                    if (!ensurePairingDeadline()) return@withLock
                    if (!ensureSessionIdentityCurrent()) return@withLock
                    localPairApproved = true
                    publishPairingRequest(requiresApproval = false)
                    if (!sendPairApproval()) return@withLock
                    commitPairIfReady()
                }
            }

            suspend fun rejectPairing() {
                pairingMutex.withLock {
                    if (!hasPendingPairing()) return@withLock
                    rejectAndClose(REJECT_PAIRING_REJECTED)
                }
            }

            private suspend fun sendPairApproval(): Boolean {
                if (!localPairApproved || !(pairRequestSent || pairRequestReceived)) return false
                val info = localInfo ?: return failPairingSend("approval")
                val peer = peerHello ?: return failPairingSend("approval")
                val secret = pairSecret ?: return failPairingSend("approval")
                val transcript = transcriptHash ?: return failPairingSend("approval")
                return try {
                    sendEncrypted(
                        DcMessageTypes.PAIR_APPROVE,
                        PairApprovalPayload(
                            deviceId = info.deviceId,
                            proof =
                                deviceConnectProof(
                                    secret = secret,
                                    phase = PROOF_PAIR_APPROVE,
                                    senderRole = localRole,
                                    senderDeviceId = info.deviceId,
                                    receiverDeviceId = peer.deviceId,
                                    transcriptHash = transcript,
                                ),
                        ),
                    )
                    true
                } catch (error: Exception) {
                    failPairingSend("approval", error)
                }
            }

            private suspend fun onPairApproval(payload: PairApprovalPayload) {
                if (!ensurePairingDeadline()) return
                val info = localInfo ?: return
                val peer = peerHello ?: return
                val secret = pairSecret ?: return
                val transcript = transcriptHash ?: return
                if (!hasPendingPairing() || peerPairApproved || payload.deviceId != peer.deviceId ||
                    !deviceConnectProofMatches(
                        secret = secret,
                        proof = payload.proof,
                        phase = PROOF_PAIR_APPROVE,
                        senderRole = peerRole,
                        senderDeviceId = peer.deviceId,
                        receiverDeviceId = info.deviceId,
                        transcriptHash = transcript,
                    )
                ) {
                    protocolViolation("invalid pair approval")
                    return
                }
                peerPairApproved = true
                logDebug("$logTag peer pairing approval verified.")
                commitPairIfReady()
            }

            private suspend fun commitPairIfReady() {
                if (!localPairApproved || !peerPairApproved || localPairCommitted) return
                if (closing) {
                    logInfo("$logTag pairing commit skipped because the session is closing.")
                    return
                }
                if (!ensurePairingDeadline()) return
                if (!ensureSessionIdentityCurrent()) return
                val peer = peerHello ?: return
                val secret = pairSecret ?: return
                try {
                    pairStoreMutex.withLock {
                        pairStore.save(
                            peerDeviceId = peer.deviceId,
                            peerDeviceName = device.name,
                            peerModel = device.model,
                            identity = identity,
                            secret = secret,
                        )
                    }
                } catch (error: Exception) {
                    logError("$logTag could not save the local pairing grant: ${error.message}", error)
                    rejectAndClose(REJECT_STORAGE_FAILED)
                    return
                }
                localPairCommitted = true
                refreshPairedDevices()
                if (!sendPairCommit()) return
                completeNewPairingIfReady()
            }

            private suspend fun sendPairCommit(): Boolean {
                val info = localInfo ?: return failPairingSend("commit")
                val peer = peerHello ?: return failPairingSend("commit")
                val secret = pairSecret ?: return failPairingSend("commit")
                val transcript = transcriptHash ?: return failPairingSend("commit")
                return try {
                    sendEncrypted(
                        DcMessageTypes.PAIR_COMMIT,
                        PairCommitPayload(
                            deviceId = info.deviceId,
                            proof =
                                deviceConnectProof(
                                    secret = secret,
                                    phase = PROOF_PAIR_COMMIT,
                                    senderRole = localRole,
                                    senderDeviceId = info.deviceId,
                                    receiverDeviceId = peer.deviceId,
                                    transcriptHash = transcript,
                                ),
                        ),
                    )
                    true
                } catch (error: Exception) {
                    failPairingSend("commit", error)
                }
            }

            private fun failPairingSend(
                stage: String,
                error: Exception? = null,
            ): Boolean {
                val suffix = error?.message?.let { ": $it" }.orEmpty()
                logWarn("$logTag pairing $stage send failed; closing$suffix")
                close()
                return false
            }

            private suspend fun onPairCommit(payload: PairCommitPayload) {
                if (!ensurePairingDeadline()) return
                val info = localInfo ?: return
                val peer = peerHello ?: return
                val secret = pairSecret ?: return
                val transcript = transcriptHash ?: return
                if (!hasPendingPairing() || !peerPairApproved || peerPairCommitted || payload.deviceId != peer.deviceId ||
                    !deviceConnectProofMatches(
                        secret = secret,
                        proof = payload.proof,
                        phase = PROOF_PAIR_COMMIT,
                        senderRole = peerRole,
                        senderDeviceId = peer.deviceId,
                        receiverDeviceId = info.deviceId,
                        transcriptHash = transcript,
                    )
                ) {
                    protocolViolation("invalid pair commit")
                    return
                }
                peerPairCommitted = true
                completeNewPairingIfReady()
            }

            private suspend fun completeNewPairingIfReady() {
                if (!localPairCommitted || !peerPairCommitted || device.state == PeerState.CONNECTED) return
                if (!ensurePairingDeadline()) return
                if (!ensureSessionIdentityCurrent()) return
                if (!notifyPeerState(device.id, device.name, PeerState.CONNECTED)) return
                pairingTimeoutJob?.cancel()
                clearPairingRequest()
                sendDeviceInfo()
                logInfo("$logTag pairing approved and saved for ${device.name}.")
            }

            private suspend fun onAuthResult(payload: AuthResultPayload) {
                if (payload.accepted) {
                    protocolViolation("unexpected auth result")
                    return
                }
                val peer = peerHello ?: return
                val knownReason = payload.reason.takeIf(::isKnownAuthRejectionReason)
                val state =
                    when (knownReason) {
                        REJECT_ACCOUNT_MISMATCH -> PeerState.ACCOUNT_MISMATCH
                        REJECT_ACCOUNT_UNRESOLVED -> PeerState.ACCOUNT_UNRESOLVED
                        else -> PeerState.ERROR
                    }
                notifyPeerState(peer.deviceId, device.name, state)
                if (knownReason == null) {
                    logWarn("$logTag pairing rejected with an unknown reason.")
                } else {
                    logWarn("$logTag pairing rejected: $knownReason.")
                }
                close()
            }

            private fun isKnownAuthRejectionReason(reason: String): Boolean =
                reason in
                    setOf(
                        REJECT_ACCOUNT_MISMATCH,
                        REJECT_ACCOUNT_UNRESOLVED,
                        REJECT_NOT_PAIRED,
                        REJECT_PAIRING_REJECTED,
                        REJECT_STORAGE_FAILED,
                    )

            private fun hasPendingPairing(): Boolean = pairRequestSent || pairRequestReceived

            private suspend fun ensurePairingDeadline(): Boolean {
                if (!hasPendingPairing() || pairingDeadline?.hasPassedNow() != true) return true
                clearPairingRequest()
                logWarn("$logTag pairing approval arrived after its deadline.")
                close()
                return false
            }

            private fun ensureSessionIdentityCurrent(): Boolean {
                if (isCurrentDeviceConnectIdentity(identity, identityProvider())) return true
                logWarn("$logTag account identity changed during pairing; session was suspended.")
                close()
                scope.launch { refreshAccountIdentity() }
                return false
            }

            private fun startPairingTimeout() {
                if (pairingTimeoutJob != null) return
                handshakeTimeoutJob?.cancel()
                pairingExpiresAt = nowMs() + DEVICE_CONNECT_PAIRING_TIMEOUT_MILLIS
                pairingDeadline = pairingTimeSource.markNow() + DEVICE_CONNECT_PAIRING_TIMEOUT_MILLIS.milliseconds
                pairingTimeoutJob =
                    scope.launch {
                        delay(DEVICE_CONNECT_PAIRING_TIMEOUT_MILLIS.milliseconds)
                        pairingMutex.withLock {
                            if (device.state != PeerState.CONNECTED) {
                                clearPairingRequest()
                                logWarn("$logTag pairing approval timed out.")
                                close()
                            }
                        }
                    }
            }

            private fun requiresCodeApproval(): Boolean =
                identity.anonymous || (localIdentity?.supportsCodePairing == true && peerIdentity?.supportsCodePairing == true)

            private suspend fun publishPairingRequest(requiresApproval: Boolean) {
                val peer = peerHello ?: return
                val request =
                    PairingRequest(
                        requestId = connectionId,
                        deviceId = peer.deviceId,
                        deviceName = device.name,
                        sas = if (requiresCodeApproval()) deviceConnectSas(pairSecret ?: return) else "",
                        requiresApproval = requiresApproval,
                        expiresAtEpochMs = pairingExpiresAt,
                    )
                stateMutex.withLock {
                    _state.value =
                        _state.value.copy(
                            pairingRequests = _state.value.pairingRequests.filterNot { it.deviceId == peer.deviceId } + request,
                        )
                }
            }

            private suspend fun clearPairingRequest() {
                val peerId = peerHello?.deviceId ?: return
                stateMutex.withLock {
                    _state.value =
                        _state.value.copy(
                            pairingRequests =
                                _state.value.pairingRequests.filterNot {
                                    it.deviceId == peerId && it.requestId == connectionId
                                },
                        )
                }
            }

            private suspend fun onDeviceInfo(payload: DeviceInfoPayload) {
                if (!ensureSessionIdentityCurrent()) return
                if (!isValidDeviceId(payload.deviceId) || payload.deviceId != device.id) {
                    protocolViolation("invalid device info id")
                    return
                }
                val metadataChanged =
                    pairStoreMutex.withLock {
                        pairStore.updateMetadata(
                            peerDeviceId = payload.deviceId,
                            peerDeviceName = payload.deviceName,
                            peerModel = payload.model,
                            identity = identity,
                            peerDeviceType = DeviceConnectDeviceType.fromWireName(payload.deviceType),
                        )
                    }
                if (metadataChanged) refreshPairedDevices()
                if (!notifyPeerState(
                        payload.deviceId,
                        payload.deviceName,
                        PeerState.CONNECTED,
                        payload.model,
                        authenticatedDeviceType = DeviceConnectDeviceType.fromWireName(payload.deviceType),
                    )
                ) {
                    return
                }
                stateMutex.withLock {
                    playbackVersion = payload.playbackVersion
                    if (supportsPlayback()) {
                        playbackPeers.add(device.id)
                        remote.onPeerConnected(
                            device.id,
                            supportsPlaybackClock = supportsPlaybackClock && payload.supportsPlaybackClock,
                            supportsPlaybackPitch = supportsPlaybackPitch && payload.supportsPlaybackPitch,
                        )
                    } else if (connections.values.none { it.device.id == device.id && it.supportsPlayback() }) {
                        playbackPeers.remove(device.id)
                        remote.onPeerDisconnected(device.id, intentional = true)
                    }
                }
            }

            private suspend fun onPlaybackChunk(payload: PlaybackChunkPayload) {
                if (closing) return
                if (!supportsPlayback()) {
                    protocolViolation("playback before capability negotiation")
                    return
                }
                val message =
                    playbackReceiveMutex.withLock {
                        try {
                            val bytes = playbackAssembler.accept(payload, nowMs())
                            if (bytes != null) {
                                playbackTimeoutJob?.cancel()
                                playbackTimeoutJob = null
                                DcJson.decodeFromString<DcSyncMessage>(bytes.decodeToString(throwOnInvalidSequence = true))
                            } else {
                                if (playbackTimeoutJob == null) {
                                    playbackTimeoutJob =
                                        scope.launch {
                                            delay(DEVICE_CONNECT_PLAYBACK_TIMEOUT_MS.milliseconds)
                                            playbackReceiveMutex.withLock {
                                                playbackAssembler.clear()
                                                protocolViolation("playback assembly timed out")
                                            }
                                        }
                                }
                                null
                            }
                        } catch (_: Exception) {
                            playbackAssembler.clear()
                            protocolViolation("invalid playback assembly")
                            null
                        }
                    }
                if (message != null && supportsPlayback() && ensureSessionIdentityCurrent()) {
                    remote.onMessage(device.id, message)
                }
            }

            suspend fun sendPlayback(bytes: ByteArray): Boolean {
                if (!supportsPlayback() || !ensureSessionIdentityCurrent()) return false
                return try {
                    sendMutex.withLock {
                        for (chunk in chunkDeviceConnectPlayback(bytes)) {
                            if (!supportsPlayback() || !ensureSessionIdentityCurrent()) return false
                            sendEncryptedLocked(DcMessageTypes.PLAYBACK, DcJson.encodeToString(chunk))
                        }
                    }
                    true
                } catch (_: Exception) {
                    close()
                    currentCoroutineContext().ensureActive()
                    false
                }
            }

            private suspend fun onPing(payload: PingPayload) {
                sendPostAuthEncrypted(DcMessageTypes.PONG, PongPayload(payload.t))
            }

            fun ping(now: Long) {
                if (device.state == PeerState.CONNECTED) {
                    if (isHeartbeatExpired(lastReceivedAt, now, HEARTBEAT_TIMEOUT)) {
                        logWarn("$logTag heartbeat timed out; closing stale connection.")
                        close()
                    } else {
                        scope.launch {
                            runCatching { sendPostAuthEncrypted(DcMessageTypes.PING, PingPayload(now)) }
                                .onFailure { close() }
                        }
                    }
                }
            }

            suspend fun sendDeviceInfo() {
                if (device.state != PeerState.CONNECTED) return
                val info = localInfo ?: return
                sendPostAuthEncrypted(
                    DcMessageTypes.DEVICE_INFO,
                    info.copy(
                        playbackVersion = advertisedPlaybackVersion,
                        supportsPlaybackClock = supportsPlaybackClock,
                        supportsPlaybackPitch = supportsPlaybackPitch,
                    ),
                )
            }

            private suspend inline fun <reified T> sendPostAuthEncrypted(
                type: String,
                payload: T,
            ): Boolean {
                if (closing || device.state != PeerState.CONNECTED) return false
                if (!ensureSessionIdentityCurrent()) return false
                sendEncrypted(type, payload)
                return true
            }

            private suspend inline fun <reified T> sendEncrypted(
                type: String,
                payload: T,
            ) {
                val json = DcJson.encodeToString(payload)
                sendMutex.withLock {
                    sendEncryptedLocked(type, json)
                }
            }

            /** Caller holds sendMutex so a chunk batch cannot interleave with any other frame. */
            private suspend fun sendEncryptedLocked(
                type: String,
                json: String,
            ) {
                check(helloSent) { "encrypted message before HELLO" }
                val key = checkNotNull(outboundKey)
                val sentSeq = ++outSeq
                val ct = DeviceCrypto.aesGcmEncrypt(key, json.encodeToByteArray(), deviceConnectAad(type, sentSeq))
                val wire =
                    DcWire(
                        type = type,
                        payload = ct.toBase64(),
                        version = DEVICE_CONNECT_PROTOCOL_VERSION,
                        encrypted = true,
                        seq = sentSeq,
                    )
                val text = DcJson.encodeToString(wire)
                check(text.encodeToByteArray().size <= DEVICE_CONNECT_MAX_FRAME_BYTES) { "oversized outbound frame" }
                channel.send(text)
                if (type != DcMessageTypes.PLAYBACK && type != DcMessageTypes.PING && type != DcMessageTypes.PONG) {
                    logDebug("$logTag → $type (enc, seq=$sentSeq)")
                }
            }

            private fun protocolViolation(reason: String) {
                logWarn("$logTag protocol violation: $reason.")
                close()
            }

            private suspend fun rejectAndClose(reason: String) {
                runCatching { sendEncrypted(DcMessageTypes.AUTH_RESULT, AuthResultPayload(false, reason)) }
                clearPairingRequest()
                close()
            }

            fun close(
                intentional: Boolean = false,
                sendBye: Boolean = intentional,
            ) {
                if (intentional) intentionalClosure = true
                if (closing) return
                val notifyPeer =
                    sendBye && device.state == PeerState.CONNECTED &&
                        isCurrentDeviceConnectIdentity(identity, identityProvider())
                closing = true
                playbackOutbox.close()
                if (!notifyPeer) collectJob?.cancel()
                handshakeTimeoutJob?.cancel()
                pairingTimeoutJob?.cancel()
                playbackTimeoutJob?.cancel()
                scope.launch {
                    try {
                        if (notifyPeer) {
                            withTimeoutOrNull(500.milliseconds) { sendEncrypted(DcMessageTypes.BYE, ByePayload()) }
                            // Give the peer time to consume BYE so it does not treat intentional closure as transport loss.
                            withTimeoutOrNull(500.milliseconds) { done.await() }
                        }
                        collectJob?.cancel()
                        channel.close()
                    } catch (_: Exception) {
                        collectJob?.cancel()
                        runCatching { channel.close() }
                    }
                }
            }

            private suspend fun onClosed() {
                closing = true
                playbackOutbox.close()
                playbackTimeoutJob?.cancel()
                playbackReceiveMutex.withLock { playbackAssembler.clear() }
                pairingMutex.withLock { rollbackUnconfirmedPair() }
                logInfo("$logTag session closed.")
                val id = device.id
                if (!isIncoming && expectedPeerId == null && device.state != PeerState.CONNECTED && !intentionalClosure) {
                    reportDirectConnectionFailure()
                }
                clearPairingRequest()
                connectionSlots.release()
                stateMutex.withLock {
                    releaseIncomingAdmission()
                    if (device.state != PeerState.CONNECTED) discoveredEndpoints.failed(id, device.host, device.port)
                    connections.remove(connectionId)
                    val connectedSession =
                        connections.values.firstOrNull {
                            it.device.id == id && it.device.state == PeerState.CONNECTED && !it.closing
                        }
                    if (connectedSession == null) {
                        val wasPlaybackPeer = playbackPeers.remove(id)
                        if (wasPlaybackPeer || connections.values.none { it.device.id == id && !it.closing }) {
                            remote.onPeerDisconnected(id, intentionalClosure)
                        }
                        val current = peerById[id]
                        val alternative = discoveredEndpoints.first(id)
                        val available =
                            if (alternative != null && current != null) {
                                current.copy(host = alternative.host, port = alternative.port, serviceName = alternative.serviceName)
                            } else {
                                current?.takeIf(::hasAvailableDeviceConnectEndpoint)
                            }
                        if (available != null) {
                            val paired = _state.value.pairedDevices.any { it.deviceId == id }
                            peerById[id] =
                                available.copy(
                                    state =
                                        when {
                                            current?.state == PeerState.ACCOUNT_UNRESOLVED -> PeerState.ACCOUNT_UNRESOLVED
                                            current?.state == PeerState.ACCOUNT_MISMATCH && !paired -> PeerState.ACCOUNT_MISMATCH
                                            intentionalClosure -> PeerState.DISCOVERED
                                            else -> PeerState.DISCONNECTED
                                        },
                                )
                        } else {
                            peerById.remove(id)
                        }
                    } else {
                        peerById[id] = (peerById[id] ?: connectedSession.device).copy(state = PeerState.CONNECTED)
                    }
                    rebuildPeers()
                }
                if (!done.isCompleted) done.complete(Unit)
            }

            private suspend fun rollbackUnconfirmedPair() {
                if (!localPairCommitted || device.state == PeerState.CONNECTED) return
                val peer = peerHello ?: return
                val secret = pairSecret ?: return
                val removed =
                    try {
                        pairStoreMutex.withLock {
                            pairStore.forgetIfSecret(
                                peerDeviceId = peer.deviceId,
                                identity = identity,
                                secret = secret,
                            )
                        }
                    } catch (error: Exception) {
                        logError("$logTag could not roll back an unconfirmed pairing grant: ${error.message}", error)
                        return
                    }
                if (removed) {
                    localPairCommitted = false
                    refreshPairedDevices()
                    logInfo("$logTag unconfirmed local pairing grant was rolled back.")
                }
            }

            // Called only while holding stateMutex, including when authentication and close race.
            private fun releaseIncomingAdmission() {
                if (!incomingAdmissionHeld) return
                incomingAdmissionHeld = false
                incomingAdmission.release(channel.remoteHost.orEmpty())
            }

            private suspend fun notifyPeerState(
                id: String,
                name: String,
                state: PeerState,
                model: String = device.model,
                authenticatedDeviceType: DeviceConnectDeviceType = DeviceConnectDeviceType.UNKNOWN,
            ): Boolean {
                if (state == PeerState.CONNECTED) handshakeTimeoutJob?.cancel()
                val safeName =
                    sanitizeDeviceName(name)
                        .ifBlank { device.name.ifBlank { id } }
                val safeModel = sanitizeDeviceName(model)
                val losers =
                    stateMutex.withLock {
                        if (closing) return false
                        if (state == PeerState.CONNECTED) releaseIncomingAdmission()
                        val prev = peerById[id]
                        val matchingEndpoint = prev?.takeIf { it.host == device.host && (isIncoming || it.port == device.port) }
                        val host = device.host
                        val port = matchingEndpoint?.port?.takeIf { it != 0 } ?: device.port
                        // A blank previous service name marks a withdrawn live endpoint; do not restore it on close.
                        val serviceName = matchingEndpoint?.serviceName ?: device.serviceName
                        val storedType =
                            _state.value.pairedDevices
                                .firstOrNull { it.deviceId == id }
                                ?.deviceType
                        val safeType =
                            selectDeviceConnectDeviceType(
                                authenticated = authenticatedDeviceType,
                                stored = storedType ?: DeviceConnectDeviceType.UNKNOWN,
                                advertised = prev?.deviceType ?: device.deviceType,
                            )
                        device =
                            device.copy(
                                id = id,
                                name = safeName,
                                state = state,
                                model = safeModel,
                                host = host,
                                port = port,
                                serviceName = serviceName,
                                deviceType = safeType,
                            )
                        if (state == PeerState.CONNECTED) discoveredEndpoints.authenticated(device)
                        // Merge endpoint and authenticated IDs so one physical device does not appear twice.
                        val dup =
                            peerById.entries.firstOrNull {
                                it.key != id && it.value.host == host && it.value.port == port && port != 0
                            }
                        if (dup != null) peerById.remove(dup.key)
                        peerById[id] =
                            PeerDevice(
                                id,
                                safeName,
                                host,
                                port,
                                state,
                                safeModel,
                                serviceName,
                                safeType,
                            )
                        rebuildPeers()
                        if (state != PeerState.CONNECTED) {
                            emptyList()
                        } else {
                            duplicateConnectionLosers(id).also { duplicates ->
                                duplicates.forEach { it.close() }
                            }
                        }
                    }
                return this !in losers
            }

            private fun duplicateConnectionLosers(peerId: String): List<PeerSession> {
                if (peerId == localInfo?.deviceId) return emptyList()
                val candidates =
                    connections.values.filter {
                        it.device.id == peerId && it.device.state == PeerState.CONNECTED && !it.closing
                    }
                if (candidates.size < 2) return emptyList()
                val preferIncoming = (localInfo?.deviceId ?: return emptyList()) > peerId
                val preferred = candidates.filter { it.isIncoming == preferIncoming }
                val winner = (preferred.ifEmpty { candidates }).minBy { checkNotNull(it.transcriptHash).toBase64() }
                return candidates - winner
            }
        }

        private suspend fun pairingSessionFor(requestId: String): PeerSession? =
            stateMutex.withLock {
                if (_state.value.pairingRequests.none { it.requestId == requestId }) return@withLock null
                connections[requestId]
            }

        private suspend fun pairedDevicesFor(identity: AccountIdentitySnapshot): List<PairedDevice> =
            pairStoreMutex.withLock {
                pairStore
                    .pairsFor(identity)
                    .map { PairedDevice(it.peerDeviceId, it.peerDeviceName, it.peerModel, it.peerDeviceType) }
            }

        private suspend fun refreshPairedDevices() {
            val identity = accountIdentity ?: identityProvider()
            val pairedDevices = pairedDevicesFor(identity)
            stateMutex.withLock {
                _state.value = _state.value.copy(pairedDevices = pairedDevices)
            }
        }

        private suspend fun finishFailedConnectAttempt(
            deviceId: String,
            host: String,
            port: Int,
            serviceName: String,
            failedEndpointState: PeerState? = null,
        ) {
            stateMutex.withLock {
                if (!_state.value.enabled) return
                discoveredEndpoints.failed(deviceId, host, port)
                val current = peerById[deviceId]
                val updated =
                    peerAfterFailedDeviceConnectAttempt(
                        current,
                        host,
                        port,
                        serviceName,
                        failedEndpointState,
                    )
                when {
                    updated == null -> {
                        val alternative = discoveredEndpoints.first(deviceId)
                        if (alternative != null && current != null) {
                            peerById[deviceId] =
                                current.copy(
                                    host = alternative.host,
                                    port = alternative.port,
                                    serviceName = alternative.serviceName,
                                    state = PeerState.DISCOVERED,
                                )
                        } else {
                            peerById.remove(deviceId)
                        }
                    }

                    updated != current -> {
                        peerById[deviceId] = updated
                    }
                }
                if (connections.values.none { it.device.id == deviceId && !it.closing }) {
                    remote.onPeerDisconnected(deviceId, intentional = false)
                }
                rebuildPeers()
            }
        }

        private suspend fun refreshAccountIdentity(): AccountIdentitySnapshot {
            val current = identityProvider()
            val pairedDevices = pairedDevicesFor(current)
            var changed = false
            val sessions =
                stateMutex.withLock {
                    val previous = accountIdentity
                    accountIdentity = current
                    _state.value = _state.value.copy(anonymous = current.anonymous, pairedDevices = pairedDevices)
                    if (current == previous || previous == null) return@withLock emptyList()
                    changed = true
                    playbackPeers.clear()

                    peerById.entries
                        .filter { it.value.state == PeerState.ACCOUNT_MISMATCH || it.value.state == PeerState.ACCOUNT_UNRESOLVED }
                        .forEach { (id, peer) -> peerById[id] = peer.copy(state = PeerState.DISCOVERED) }
                    rebuildPeers()
                    connections.values.toList()
                }
            if (changed) remote.stopAndAwait()
            sessions.forEach { it.close(intentional = true, sendBye = false) }
            if (sessions.isNotEmpty()) {
                logInfo("Account identity changed; prior Device Connect links were suspended.")
            }
            return current
        }

        private fun rebuildPeers() {
            _state.value = _state.value.copy(peers = peerById.values.toList())
        }

        private fun logDebug(msg: String) = log(LogLevel.DEBUG, msg, null)

        private fun logInfo(msg: String) = log(LogLevel.INFO, msg, null)

        private fun logWarn(
            msg: String,
            throwable: Throwable? = null,
        ) = log(LogLevel.WARN, msg, throwable)

        private fun logError(
            msg: String,
            throwable: Throwable? = null,
        ) = log(LogLevel.ERROR, msg, throwable)

        private fun log(
            level: LogLevel,
            msg: String,
            throwable: Throwable?,
        ) {
            if (throwable != null && (level == LogLevel.WARN || level == LogLevel.ERROR)) {
                PlaybackEventLog.error(
                    PlaybackEventKind.SYNC,
                    "DeviceConnect",
                    msg,
                    throwable,
                )
            }
            val safeMessage = msg.take(MAX_LOG_MESSAGE_CHARS)
            when (level) {
                LogLevel.DEBUG -> Logger.d(loggerTag, safeMessage)
                LogLevel.INFO -> Logger.i(loggerTag, safeMessage)
                LogLevel.WARN -> Logger.w(loggerTag, safeMessage, throwable)
                LogLevel.ERROR -> Logger.e(loggerTag, safeMessage, throwable)
            }
            val entry =
                LogEntry(
                    level,
                    formatTimestampMs(
                        kotlin.time.Clock.System
                            .now()
                            .toEpochMilliseconds(),
                    ),
                    safeMessage,
                )
            scope.launch {
                stateMutex.withLock {
                    var totalChars = 0
                    val next =
                        buildList {
                            for (item in listOf(entry) + _state.value.log) {
                                if (size >= MAX_LOG_ENTRIES || totalChars + item.message.length > MAX_LOG_CHARS) break
                                add(item)
                                totalChars += item.message.length
                            }
                        }
                    _state.value = _state.value.copy(log = next)
                }
            }
        }

        private val loggerTag = "DeviceConnect"

        fun updateDeviceName(name: String) {
            settings.deviceConnectDeviceName = sanitizeDeviceName(name)
            scope.launch {
                val sessions =
                    lifecycleMutex.withLock {
                        val info = localInfoProvider()
                        localInfo = info
                        val connected =
                            stateMutex.withLock {
                                _state.value = _state.value.copy(localDeviceName = info.deviceName)
                                connections.values.filter { it.device.state == PeerState.CONNECTED }
                            }
                        logInfo("Local device name set to \"${info.deviceName}\".")
                        val state = _state.value
                        if (state.serverRunning && state.serverPort > 0) {
                            refreshAccountIdentity()
                            logInfo("Rescanning for devices…")
                            stopDiscovery()
                            startDiscovery(info, state.serverPort)
                        }
                        connected
                    }
                sessions.forEach { session -> session.sendDeviceInfo() }
            }
        }

        suspend fun release() {
            logInfo("Device Connect release started.")
            lifecycleMutex.withLock { disposeLocked() }
            logInfo("Device Connect release completed.")
            remote.close()
            scope.cancel()
        }

        companion object {
            const val SERVICE_TYPE = "_metrolist-connect._tcp."
            private const val PING_INTERVAL = 5_000L
            private const val AUTO_CONNECT_YIELD = 2_000L
            private const val AUTO_CONNECT_RETRY = 15_000L
            private const val HEARTBEAT_TIMEOUT = 20_000L
            private const val HANDSHAKE_TIMEOUT = 10_000L
            private const val PROOF_IDENTITY = "identity"
            private const val PROOF_RECONNECT = "reconnect"
            private const val PROOF_PAIR_REQUEST = "pair-request"
            private const val PROOF_PAIR_APPROVE = "pair-approve"
            private const val PROOF_PAIR_COMMIT = "pair-commit"
            private const val REJECT_ACCOUNT_MISMATCH = "account-mismatch"
            private const val REJECT_ACCOUNT_UNRESOLVED = "account-unresolved"
            private const val REJECT_NOT_PAIRED = "not-paired"
            private const val REJECT_PAIRING_REJECTED = "pairing-rejected"
            private const val REJECT_STORAGE_FAILED = "pairing-storage-failed"
            private const val MAX_CONNECTIONS = 8
            private const val MAX_LOG_ENTRIES = 200
            private const val MAX_LOG_MESSAGE_CHARS = 4_096
            private const val MAX_LOG_CHARS = 64 * 1_024
        }
    }

private fun formatTimestampMs(epochMs: Long): String {
    val totalSeconds = epochMs / 1000
    val ms = epochMs % 1000
    val seconds = (totalSeconds % 60).toInt()
    val minutes = ((totalSeconds / 60) % 60).toInt()
    val hours = ((totalSeconds / 3600) % 24).toInt()
    return listOf(hours, minutes, seconds).joinToString(":") { it.toString().padStart(2, '0') } +
        ".${ms.toString().padStart(3, '0')}"
}

internal fun sanitizeDeviceName(value: String): String =
    value
        .filterNot(Char::isISOControl)
        .trim()
        .replace(Regex("\\s+"), " ")
        .take(64)

internal fun deviceConnectAad(
    type: String,
    seq: Long,
): ByteArray = "metrolist-device-connect-v2:$type:$seq".encodeToByteArray()

internal fun isHeartbeatExpired(
    lastReceivedAt: Long,
    now: Long,
    timeout: Long,
): Boolean = now - lastReceivedAt > timeout

private fun nowMs(): Long =
    kotlin.time.Clock.System
        .now()
        .toEpochMilliseconds()

private class ClientDeviceChannel(
    private val session: ClientWebSocketSession,
    override val remoteHost: String?,
) : DeviceChannel {
    override val incoming: Flow<String> =
        flow {
            session.incoming.consumeEach { frame ->
                if (frame is Frame.Text) emit(frame.readText())
            }
        }

    override suspend fun send(text: String) {
        session.send(Frame.Text(text))
    }

    override suspend fun close() {
        try {
            session.close()
        } catch (_: Exception) {
        }
    }
}
