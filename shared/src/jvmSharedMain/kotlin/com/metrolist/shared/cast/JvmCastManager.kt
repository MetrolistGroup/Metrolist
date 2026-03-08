package com.metrolist.shared.cast

import androidx.compose.runtime.snapshotFlow
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.utils.Logger
import com.metrolist.shared.youtube.playback.PlaybackEventKind
import com.metrolist.shared.youtube.playback.PlaybackEventLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.milliseconds

internal class CastOperationLock {
    private val mutex = Mutex()

    @Volatile
    var remoteStopRequested = false
        private set

    suspend fun <T> withLock(block: suspend CastOperationLock.() -> T): T = mutex.withLock { block() }

    suspend fun <T> withStopMarker(block: suspend () -> T): T =
        mutex.withLock {
            remoteStopRequested = true
            block()
        }

    fun markMediaActive() {
        remoteStopRequested = false
    }
}

class JvmCastManager(
    private val scope: CoroutineScope,
    private val discoveryFactory: () -> MdnsCastDiscovery,
    private val localAddresses: () -> List<InetAddress>,
    private val appVersion: String,
    private val settings: Settings = Settings.shared,
) {
    private var mdnsDiscovery: MdnsCastDiscovery? = null
    private val devicesById = linkedMapOf<String, CastDevice>()
    private val devicesLock = Any()
    private val operationLock = CastOperationLock()
    private val _devices = MutableStateFlow<List<CastDevice>>(emptyList())
    val devices: StateFlow<List<CastDevice>> = _devices.asStateFlow()
    private val _state = MutableStateFlow(CastState())
    val state: StateFlow<CastState> = _state.asStateFlow()
    private val discovering = AtomicBoolean(false)
    private val discoveryGeneration = AtomicLong()
    private var discoveryJob: Job? = null
    private var sessionStateJob: Job? = null
    private var session: CastSession? = null
    private var proxy: CastProxyServer? = null
    private var mediaGeneration = 0L

    private val enabledJob =
        scope.launch {
            snapshotFlow { settings.castEnabled }.collect { enabled ->
                if (!enabled) {
                    stopDiscovery()
                    discoveryJob?.join()
                    operationLock.withLock {
                        disconnectLocked(stopRemote = true, finalState = _state.value.afterDisconnect())
                    }
                }
            }
        }

    fun startDiscovery() {
        if (!settings.castEnabled || !discovering.compareAndSet(false, true)) return
        val generation = discoveryGeneration.incrementAndGet()
        val previousDiscovery = discoveryJob
        discoveryJob =
            scope.launch(Dispatchers.IO) {
                previousDiscovery?.join()
                try {
                    if (!settings.castEnabled || !discovering.get()) return@launch
                    val discovery = mdnsDiscovery ?: discoveryFactory().also { mdnsDiscovery = it }
                    discovery.start(
                        { addDevice(it, generation) },
                        { removeDevice(it, generation) },
                    ) { error ->
                        PlaybackEventLog.error(
                            PlaybackEventKind.NETWORK,
                            "CastDiscovery",
                            "mDNS discovery failed: ${error.name}",
                            IllegalStateException("mDNS discovery failed: ${error.name}"),
                        )
                        if (discovering.get()) showError(error)
                    }
                    var dlnaFailureLogged = false
                    while (discovering.get() && settings.castEnabled) {
                        val result = runCatching { discoverDlna(localAddresses(), generation) }
                        if (result.isSuccess) {
                            dlnaFailureLogged = false
                        } else {
                            val error = result.exceptionOrNull()
                            if (error != null && error !is CancellationException && !dlnaFailureLogged) {
                                dlnaFailureLogged = true
                                PlaybackEventLog.error(
                                    PlaybackEventKind.NETWORK,
                                    "CastDiscovery",
                                    "DLNA discovery failed",
                                    error,
                                )
                            }
                        }
                        delay(DISCOVERY_INTERVAL_MS.milliseconds)
                    }
                } finally {
                    withContext(NonCancellable + Dispatchers.IO) { mdnsDiscovery?.stop() }
                }
            }
    }

    fun stopDiscovery() {
        discovering.set(false)
        discoveryGeneration.incrementAndGet()
        discoveryJob?.cancel()
        synchronized(devicesLock) {
            devicesById.clear()
            publishDevices()
        }
    }

    fun showError(error: CastError) {
        if (settings.castEnabled) _state.value = _state.value.copy(error = error)
    }

    suspend fun cast(
        device: CastDevice,
        media: CastMedia,
        positionMs: Long,
        autoplay: Boolean,
        volume: Float,
        shouldCommit: () -> Boolean = { true },
    ): Boolean =
        operationLock.withLock {
            if (!settings.castEnabled || !shouldCommit()) return@withLock false
            disconnectLocked(stopRemote = true)
            markMediaActive()
            val nextMediaGeneration = ++mediaGeneration
            _state.value =
                CastState(
                    status = CastConnectionStatus.CONNECTING,
                    device = device,
                    volume = volume.coerceIn(0f, 1f),
                    mediaGeneration = nextMediaGeneration,
                    queueEntry = media.queueEntry,
                    mediaVideoId = media.resource.musicVideoId ?: media.resource.videoId,
                )
            if (!isCastable(media, device.protocol)) {
                _state.value = CastState(error = CastError.UNSUPPORTED_STREAM, mediaGeneration = nextMediaGeneration)
                return@withLock false
            }
            var stage = "resolve_receiver"
            try {
                val (nextProxy, nextSession) =
                    withContext(Dispatchers.IO) {
                        val receiverAddress = resolveReceiverAddress(device.host)
                        stage = "select_route"
                        val localAddress = localAddressFor(receiverAddress)
                        stage = "start_proxy"
                        val nextProxy = CastProxyServer(receiverAddress, localAddress, media).also { proxy = it }
                        stage = "create_session"
                        val nextSession: CastSession =
                            when (device.protocol) {
                                CastProtocol.FCAST -> FcastSession(scope, receiverAddress, device.port, appVersion)
                                CastProtocol.GOOGLE_CAST -> GoogleCastSession(scope, receiverAddress, device.port)
                                CastProtocol.DLNA, CastProtocol.SONOS -> DlnaSession(scope, requireNotNull(device.endpoint))
                            }
                        session = nextSession
                        nextProxy to nextSession
                    }
                if (!settings.castEnabled || !shouldCommit()) {
                    disconnectLocked(stopRemote = false)
                    return@withLock false
                }
                stage = "load_session"
                nextSession.load(
                    mediaUrl = nextProxy.mediaUrl(),
                    media = media,
                    positionMs = positionMs.coerceAtLeast(0L),
                    autoplay = autoplay,
                    volume = volume.coerceIn(0f, 1f),
                    mediaGeneration = nextMediaGeneration,
                )
                if (!settings.castEnabled || !shouldCommit()) {
                    disconnectLocked(stopRemote = true)
                    return@withLock false
                }
                _state.value =
                    _state.value.copy(
                        status = CastConnectionStatus.CONNECTED,
                        device = device,
                        isPlaying = autoplay,
                        positionMs = positionMs.coerceAtLeast(0L),
                        durationMs = media.durationMs,
                        volume = nextSession.state.value.volume,
                        hasEnded = false,
                        error = null,
                    )
                observeSessionState(nextSession, device, nextMediaGeneration)
                true
            } catch (e: CancellationException) {
                disconnectLocked(stopRemote = false)
                throw e
            } catch (error: Exception) {
                PlaybackEventLog.error(
                    PlaybackEventKind.NETWORK,
                    "Cast",
                    "Connection failed during $stage (${device.protocol})",
                    error,
                )
                Logger.w(
                    TAG,
                    "connection failed protocol=${device.protocol} stage=$stage type=${error::class.simpleName ?: "unknown"}",
                )
                disconnectLocked(
                    stopRemote = false,
                    finalState =
                        CastState(
                            error = CastError.CONNECTION_FAILED.takeIf { shouldCommit() },
                            mediaGeneration = nextMediaGeneration,
                        ),
                )
                false
            }
        }

    suspend fun replaceMedia(
        media: CastMedia,
        positionMs: Long,
        autoplay: Boolean,
        volume: Float,
    ): Boolean =
        operationLock.withLock {
            if (!settings.castEnabled) return@withLock false
            val activeSession = session ?: return@withLock false
            val device = _state.value.device ?: return@withLock false
            if (!isCastable(media, device.protocol)) {
                showError(CastError.UNSUPPORTED_STREAM)
                return@withLock false
            }
            var nextProxy: CastProxyServer? = null
            val nextMediaGeneration = mediaGeneration + 1L
            try {
                val replacementProxy =
                    withContext(Dispatchers.IO) {
                        val receiverAddress = resolveReceiverAddress(device.host)
                        CastProxyServer(receiverAddress, localAddressFor(receiverAddress), media).also { nextProxy = it }
                    }
                sessionStateJob?.cancelAndJoin()
                sessionStateJob = null
                activeSession.load(
                    mediaUrl = replacementProxy.mediaUrl(),
                    media = media,
                    positionMs = positionMs.coerceAtLeast(0L),
                    autoplay = autoplay,
                    volume = volume.coerceIn(0f, 1f),
                    mediaGeneration = nextMediaGeneration,
                )
                withContext(NonCancellable) {
                    markMediaActive()
                    mediaGeneration = nextMediaGeneration
                    val oldProxy = proxy
                    proxy = replacementProxy
                    nextProxy = null
                    withContext(Dispatchers.IO) { runCatching { oldProxy?.close() } }
                    _state.value =
                        _state.value.copy(
                            status = CastConnectionStatus.CONNECTED,
                            isPlaying = autoplay,
                            positionMs = positionMs.coerceAtLeast(0L),
                            durationMs = media.durationMs,
                            volume = activeSession.state.value.volume,
                            hasEnded = false,
                            error = null,
                            mediaGeneration = nextMediaGeneration,
                            queueEntry = media.queueEntry,
                            mediaVideoId = media.resource.musicVideoId ?: media.resource.videoId,
                        )
                    observeSessionState(activeSession, device, nextMediaGeneration)
                }
                true
            } catch (e: CancellationException) {
                withContext(NonCancellable + Dispatchers.IO) { runCatching { nextProxy?.close() } }
                throw e
            } catch (error: Exception) {
                PlaybackEventLog.error(
                    PlaybackEventKind.NETWORK,
                    "Cast",
                    "Media replacement failed (${device.protocol})",
                    error,
                )
                Logger.w(
                    TAG,
                    "media replacement failed protocol=${device.protocol} type=${error::class.simpleName ?: "unknown"}",
                )
                withContext(NonCancellable + Dispatchers.IO) { runCatching { nextProxy?.close() } }
                val lastState = _state.value
                disconnectLocked(
                    stopRemote = false,
                    finalState = lastState.afterDisconnect(CastError.PLAYBACK_FAILED, mediaGeneration),
                )
                false
            }
        }

    suspend fun play() =
        control {
            it.play()
            operationLock.markMediaActive()
        }

    suspend fun pause() = control { it.pause() }

    suspend fun stop() {
        operationLock.withStopMarker {
            controlLocked { it.stop() }
            _state.value = _state.value.copy(isPlaying = false, hasEnded = false)
        }
    }

    suspend fun seekTo(positionMs: Long) =
        control {
            val targetMs = positionMs.coerceAtLeast(0L)
            it.seekTo(targetMs)
            _state.value = _state.value.copy(positionMs = targetMs, seekGeneration = _state.value.seekGeneration + 1L)
        }

    suspend fun setVolume(volume: Float) = control { it.setVolume(volume.coerceIn(0f, 1f)) }

    suspend fun disconnect(stopRemote: Boolean = true) {
        operationLock.withLock { disconnectLocked(stopRemote) }
    }

    suspend fun release() {
        enabledJob.cancelAndJoin()
        stopDiscovery()
        discoveryJob?.join()
        disconnect()
        mdnsDiscovery?.close()
    }

    fun closeImmediately() {
        enabledJob.cancel()
        stopDiscovery()
        sessionStateJob?.cancel()
        sessionStateJob = null
        val oldSession = session
        val oldProxy = proxy
        session = null
        proxy = null
        thread(name = "cast-close", isDaemon = true) {
            runCatching { oldSession?.close() }
            runCatching { oldProxy?.close() }
        }
        mdnsDiscovery?.close()
        _state.value = CastState()
    }

    private fun observeSessionState(
        activeSession: CastSession,
        device: CastDevice,
        expectedMediaGeneration: Long,
    ) {
        sessionStateJob =
            scope.launch {
                activeSession.state.collect { playback ->
                    if (
                        session !== activeSession ||
                        _state.value.mediaGeneration != expectedMediaGeneration ||
                        playback.mediaGeneration != expectedMediaGeneration
                    ) {
                        return@collect
                    }
                    if (!playback.connected) {
                        val lastState = _state.value
                        scope.launch {
                            operationLock.withLock {
                                if (
                                    session !== activeSession ||
                                    _state.value.mediaGeneration != expectedMediaGeneration
                                ) {
                                    return@withLock
                                }
                                disconnectLocked(
                                    stopRemote = false,
                                    finalState = lastState.afterDisconnect(CastError.PLAYBACK_FAILED),
                                )
                            }
                        }
                        return@collect
                    }
                    _state.value =
                        _state.value.copy(
                            status = CastConnectionStatus.CONNECTED,
                            device = device,
                            isPlaying = playback.isPlaying,
                            positionMs = playback.positionMs,
                            durationMs = playback.durationMs,
                            volume = playback.volume,
                            hasEnded = acceptedCastPlaybackEnd(playback.hasEnded, operationLock.remoteStopRequested),
                            mediaGeneration = expectedMediaGeneration,
                        )
                }
            }
    }

    private suspend fun control(block: suspend (CastSession) -> Unit) {
        operationLock.withLock { controlLocked(block) }
    }

    private suspend fun controlLocked(block: suspend (CastSession) -> Unit) {
        if (!settings.castEnabled) return
        val activeSession = session ?: return
        try {
            block(activeSession)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            PlaybackEventLog.error(PlaybackEventKind.NETWORK, "Cast", "Receiver control request failed", error)
            val lastState = _state.value
            disconnectLocked(
                stopRemote = false,
                finalState = lastState.afterDisconnect(CastError.PLAYBACK_FAILED),
            )
        }
    }

    private suspend fun disconnectLocked(
        stopRemote: Boolean,
        finalState: CastState = CastState(error = _state.value.error, mediaGeneration = _state.value.mediaGeneration),
    ) {
        operationLock.markMediaActive()
        val oldSession = session
        val oldProxy = proxy
        session = null
        proxy = null
        sessionStateJob?.cancel()
        sessionStateJob = null
        completeCastDisconnect(
            finalState = finalState,
            closeResources = {
                withContext(NonCancellable + Dispatchers.IO) {
                    if (oldSession != null && stopRemote) {
                        runCatching { oldSession.stop() }
                    }
                    runCatching { oldSession?.close() }
                    runCatching { oldProxy?.close() }
                }
            },
            publish = { _state.value = it },
        )
    }

    private fun isCastable(
        media: CastMedia,
        protocol: CastProtocol,
    ): Boolean {
        val resource = media.resource
        if (!resource.isPlayableOn(protocol)) return false
        if (resource.isSabr || resource.contentLengthBytes?.let { it <= 0L } != false || resource.mimeType.isNullOrBlank()) return false
        return runCatching { validatedGoogleVideoUrl(resource.url) }.isSuccess
    }

    private fun resolveReceiverAddress(host: String): InetAddress {
        val cleanHost = host.substringBefore('%')
        val addresses = InetAddress.getAllByName(cleanHost)
        return addresses
            .filter { !it.isAnyLocalAddress && !it.isMulticastAddress }
            .minByOrNull { if (it is Inet4Address) 0 else 1 }
            ?: error("Cast receiver address unavailable for host=$host")
    }

    private fun localAddressFor(receiverAddress: InetAddress): InetAddress {
        var routeProbeFailure: Throwable? = null
        val connectedAddress =
            runCatching {
                DatagramSocket().use { socket ->
                    socket.connect(receiverAddress, 9)
                    socket.localAddress.takeUnless { it.isAnyLocalAddress || it.isLoopbackAddress }
                }
            }.onFailure { routeProbeFailure = it }.getOrNull()
        if (connectedAddress != null) return connectedAddress

        val candidates = localAddresses()
        val sameFamily = candidates.filter { it.javaClass == receiverAddress.javaClass }
        val receiverBytes = receiverAddress.address
        val subnetMatch =
            sameFamily.firstOrNull { candidate ->
                val candBytes = candidate.address
                candBytes.size == receiverBytes.size &&
                    candBytes.size >= 2 &&
                    candBytes[0] == receiverBytes[0] &&
                    candBytes[1] == receiverBytes[1]
            }
        if (subnetMatch != null) return subnetMatch

        sameFamily.firstOrNull()?.let { return it }
        candidates.firstOrNull { isLocalNetworkAddress(it) && !it.isLoopbackAddress }?.let { return it }

        val failure = IllegalStateException("No route to Cast receiver ($receiverAddress)", routeProbeFailure)
        PlaybackEventLog.error(
            PlaybackEventKind.NETWORK,
            "CastDiscovery",
            "Could not determine the local route to the receiver",
            failure,
        )
        throw failure
    }

    private fun addDevice(
        device: CastDevice,
        generation: Long,
    ) {
        synchronized(devicesLock) {
            if (!settings.castEnabled || !discovering.get() || generation != discoveryGeneration.get()) return
            devicesById[device.id] = device
            publishDevices()
        }
    }

    private fun removeDevice(
        id: String,
        generation: Long,
    ) {
        synchronized(devicesLock) {
            if (generation != discoveryGeneration.get()) return
            devicesById.remove(id)
            publishDevices()
        }
    }

    private fun publishDevices() {
        _devices.value =
            devicesById.values.sortedWith(compareBy<CastDevice> { it.name.lowercase() }.thenBy { it.protocol.ordinal })
    }

    private suspend fun discoverDlna(
        addresses: List<InetAddress>,
        generation: Long,
    ) {
        val locations =
            withContext(Dispatchers.IO) {
                addresses
                    .filterIsInstance<Inet4Address>()
                    .flatMap(::searchSsdp)
                    .distinct()
                    .take(MAX_DLNA_DEVICES)
            }
        val discovered =
            coroutineScope {
                locations
                    .map { location ->
                        async(Dispatchers.IO) {
                            runCatching {
                                val descriptor = fetchDlnaDescriptor(location)
                                val uri = URI(location)
                                CastDevice(
                                    id = "dlna:${descriptor.id}",
                                    name = descriptor.name,
                                    protocol = if (descriptor.isSonos) CastProtocol.SONOS else CastProtocol.DLNA,
                                    host = uri.host,
                                    port = uri.port.takeIf { it >= 0 } ?: 80,
                                    endpoint = location,
                                )
                            }.getOrNull()
                        }
                    }.awaitAll()
                    .filterNotNull()
            }
        synchronized(devicesLock) {
            if (!settings.castEnabled || !discovering.get() || generation != discoveryGeneration.get()) return
            devicesById.replaceDlnaDevices(discovered)
            publishDevices()
        }
    }

    private fun searchSsdp(localAddress: Inet4Address): List<String> {
        val requests =
            SSDP_SEARCH_TARGETS.map { searchTarget ->
                (
                    "M-SEARCH * HTTP/1.1\r\n" +
                        "HOST: 239.255.255.250:1900\r\n" +
                        "MAN: \"ssdp:discover\"\r\n" +
                        "MX: 2\r\n" +
                        "ST: $searchTarget\r\n\r\n"
                ).toByteArray(Charsets.US_ASCII)
            }
        val target = InetSocketAddress("239.255.255.250", 1900)
        val locations = linkedSetOf<String>()
        DatagramSocket(null).use { socket ->
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(localAddress, 0))
            socket.soTimeout = SSDP_TIMEOUT_MS
            repeat(2) { requests.forEach { bytes -> socket.send(DatagramPacket(bytes, bytes.size, target)) } }
            while (true) {
                val buffer = ByteArray(65_507)
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (_: java.net.SocketTimeoutException) {
                    break
                }
                if (!isLocalNetworkAddress(packet.address)) continue
                parseSsdpLocation(packet.data.copyOf(packet.length).toString(Charsets.UTF_8))?.let(locations::add)
            }
        }
        return locations.toList()
    }

    private companion object {
        const val TAG = "JvmCastManager"
        const val DISCOVERY_INTERVAL_MS = 15_000L
        const val SSDP_TIMEOUT_MS = 3_000
        const val MAX_DLNA_DEVICES = 32

        // Sonos players answer ZonePlayer searches reliably; generic renderers answer MediaRenderer.
        val SSDP_SEARCH_TARGETS =
            listOf(
                "urn:schemas-upnp-org:device:MediaRenderer:1",
                "urn:schemas-upnp-org:device:ZonePlayer:1",
            )
    }
}

internal fun MutableMap<String, CastDevice>.replaceDlnaDevices(discovered: List<CastDevice>) {
    entries.removeAll { it.value.protocol == CastProtocol.DLNA || it.value.protocol == CastProtocol.SONOS }
    discovered.forEach { put(it.id, it) }
}

internal suspend fun completeCastDisconnect(
    finalState: CastState,
    closeResources: suspend () -> Unit,
    publish: (CastState) -> Unit,
) {
    closeResources()
    publish(finalState)
}

internal fun CastState.afterDisconnect(
    error: CastError? = this.error,
    mediaGeneration: Long = this.mediaGeneration,
): CastState =
    copy(
        status = CastConnectionStatus.DISCONNECTED,
        device = null,
        error = error,
        mediaGeneration = mediaGeneration,
    )

fun systemCastNetworkAddresses(): List<InetAddress> =
    runCatching {
        NetworkInterface
            .getNetworkInterfaces()
            ?.toList()
            .orEmpty()
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .flatMap { it.inetAddresses.toList() }
            .filter { isLocalNetworkAddress(it) && !it.isLoopbackAddress }
    }.getOrDefault(emptyList())

internal fun parseSsdpLocation(response: String): String? {
    if (response
            .lineSequence()
            .firstOrNull()
            ?.split(' ')
            ?.getOrNull(1) != "200"
    ) {
        return null
    }
    return response
        .lineSequence()
        .mapNotNull { line ->
            val separator = line.indexOf(':')
            if (separator <= 0 || !line.substring(0, separator).trim().equals("location", true)) return@mapNotNull null
            line.substring(separator + 1).trim().takeIf(String::isNotEmpty)
        }.firstOrNull()
}
