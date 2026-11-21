package com.metrolist.shared.cast

import com.metrolist.shared.utils.Logger
import com.metrolist.shared.youtube.playback.PlaybackEventKind
import com.metrolist.shared.youtube.playback.PlaybackEventLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xml.sax.SAXException
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

internal data class DlnaDescriptor(
    val id: String,
    val name: String,
    val avTransportType: String,
    val avTransportUrl: String,
    val renderingControlType: String?,
    val renderingControlUrl: String?,
    val isSonos: Boolean = false,
)

internal suspend fun fetchDlnaDescriptor(location: String): DlnaDescriptor =
    withContext(Dispatchers.IO) {
        val locationUri = validatedLocalHttpUri(location)
        val response = localHttpRequest(locationUri, "GET")
        check(response.status in 200..299) { "DLNA descriptor fetch failed with HTTP ${response.status}" }
        val document = parseXml(response.body)
        val device = document.elements("device").firstOrNull() ?: error("Missing DLNA device")
        val isSonos = device.text("manufacturer")?.contains("Sonos", ignoreCase = true) == true
        // Sonos friendlyName is "<ip> - <model> - RINCON_…"; roomName is what users recognize.
        val name =
            device.text("roomName")?.trim()?.takeIf { isSonos && it.isNotEmpty() }
                ?: device.text("friendlyName")?.trim()?.takeIf(String::isNotEmpty)
                ?: error("Missing DLNA name")
        val id = device.text("UDN")?.trim()?.takeIf(String::isNotEmpty) ?: locationUri.toString()
        val services = document.elements("service")
        val avTransport =
            services.firstOrNull { it.text("serviceType")?.contains(":AVTransport:") == true }
                ?: error("Missing AVTransport service")
        val renderingControl = services.firstOrNull { it.text("serviceType")?.contains(":RenderingControl:") == true }
        val avTransportType = requireNotNull(avTransport.text("serviceType"))
        val avTransportUrl = resolveLocalControlUrl(locationUri, requireNotNull(avTransport.text("controlURL")))
        val renderingType = renderingControl?.text("serviceType")
        val renderingUrl = renderingControl?.text("controlURL")?.let { resolveLocalControlUrl(locationUri, it) }
        DlnaDescriptor(id, name, avTransportType, avTransportUrl, renderingType, renderingUrl, isSonos)
    }

internal class DlnaSession(
    private val scope: CoroutineScope,
    private val descriptorLocation: String,
) : CastSession {
    private val _state = MutableStateFlow(CastSessionState())
    override val state: StateFlow<CastSessionState> = _state.asStateFlow()
    private lateinit var descriptor: DlnaDescriptor
    private var pollingJob: Job? = null
    private var lastPoll: TimeMark? = null

    @Volatile private var lastPlayingPositionMs: Long? = null

    @Volatile private var lastPlayingTrackUri: String? = null

    private val seekLock = Any()

    @Volatile private var seekGeneration = 0L

    private var restartPending = false

    @Volatile private var stopRequested = false

    @Volatile
    private var mediaGeneration = 0L

    @Volatile
    private var closed = false

    override suspend fun load(
        mediaUrl: String,
        media: CastMedia,
        positionMs: Long,
        autoplay: Boolean,
        volume: Float,
        mediaGeneration: Long,
    ) = withContext(Dispatchers.IO) {
        check(!closed)
        pollingJob?.cancelAndJoin()
        pollingJob = null
        this@DlnaSession.mediaGeneration = mediaGeneration
        seekGeneration++
        restartPending = false
        if (::descriptor.isInitialized) {
            runCatching { stop() }
        } else {
            descriptor = fetchDlnaDescriptor(descriptorLocation)
        }
        lastPoll = null
        lastPlayingPositionMs = null
        lastPlayingTrackUri = null
        stopRequested = false
        val mimeType = requireNotNull(media.resource.mimeType).substringBefore(';').trim()
        val duration = formatDlnaTime(media.durationMs)
        val didl =
            """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/"><item id="0" parentID="0" restricted="1"><dc:title>${xmlEscape(
                media.title,
            )}</dc:title><upnp:class>object.item.audioItem.musicTrack</upnp:class><res protocolInfo="http-get:*:${xmlEscape(
                mimeType,
            )}:*" duration="$duration">${xmlEscape(mediaUrl)}</res></item></DIDL-Lite>"""
        val setUriResult =
            runCatching {
                soap(
                    descriptor.avTransportUrl,
                    descriptor.avTransportType,
                    "SetAVTransportURI",
                    "<InstanceID>0</InstanceID><CurrentURI>${xmlEscape(
                        mediaUrl,
                    )}</CurrentURI><CurrentURIMetaData>${xmlEscape(didl)}</CurrentURIMetaData>",
                    logFailure = false,
                )
            }
        if (setUriResult.isFailure) {
            soap(
                descriptor.avTransportUrl,
                descriptor.avTransportType,
                "SetAVTransportURI",
                "<InstanceID>0</InstanceID><CurrentURI>${xmlEscape(
                    mediaUrl,
                )}</CurrentURI><CurrentURIMetaData></CurrentURIMetaData>",
                logFailure = false,
            )
        }
        normalizeSonosPlayMode()
        if (positionMs > 0L) {
            seekTo(positionMs)
        }
        if (autoplay) play()
        _state.value =
            CastSessionState(
                connected = true,
                isPlaying = autoplay,
                isBuffering = autoplay,
                positionMs = positionMs,
                durationMs = media.durationMs,
                volume = readVolume(logFailure = false) ?: _state.value.volume,
                mediaGeneration = mediaGeneration,
            )
        if (pollingJob == null) {
            pollingJob =
                scope.launch(Dispatchers.IO) {
                    var consecutiveErrors = 0
                    while (isActive && !closed) {
                        delay(POLL_INTERVAL_MS.milliseconds)
                        val ok = runCatching { pollState() }.isSuccess
                        if (ok) {
                            consecutiveErrors = 0
                        } else {
                            consecutiveErrors++
                            if (consecutiveErrors >= MAX_CONSECUTIVE_POLL_ERRORS) {
                                close()
                            }
                        }
                    }
                }
        }
    }

    override suspend fun play() =
        withContext(Dispatchers.IO) {
            stopRequested = false
            soap(
                descriptor.avTransportUrl,
                descriptor.avTransportType,
                "Play",
                "<InstanceID>0</InstanceID><Speed>1</Speed>",
                logFailure = false,
            )
            Unit
        }

    override suspend fun pause() =
        withContext(Dispatchers.IO) {
            soap(descriptor.avTransportUrl, descriptor.avTransportType, "Pause", "<InstanceID>0</InstanceID>", logFailure = false)
            Unit
        }

    override suspend fun seekTo(positionMs: Long) =
        withContext(Dispatchers.IO) {
            synchronized(seekLock) {
                seekGeneration++
                restartPending = false
                lastPlayingPositionMs = null
                lastPlayingTrackUri = null
                soap(
                    descriptor.avTransportUrl,
                    descriptor.avTransportType,
                    "Seek",
                    "<InstanceID>0</InstanceID><Unit>REL_TIME</Unit><Target>${formatDlnaTime(positionMs)}</Target>",
                    logFailure = false,
                )
            }
            Unit
        }

    override suspend fun setVolume(volume: Float) =
        withContext(Dispatchers.IO) {
            val controlUrl = descriptor.renderingControlUrl ?: return@withContext
            val controlType = descriptor.renderingControlType ?: return@withContext
            val requestedVolume = dlnaVolumePercent(volume)
            runCatching {
                soap(
                    controlUrl,
                    controlType,
                    "SetVolume",
                    "<InstanceID>0</InstanceID><Channel>Master</Channel><DesiredVolume>$requestedVolume</DesiredVolume>",
                )
            }.onSuccess {
                _state.value = _state.value.copy(volume = requestedVolume / 100f)
            }.onFailure {
                Logger.w("DlnaSession", "Volume control failed: ${it::class.simpleName}")
            }
            Unit
        }

    override suspend fun stop() =
        withContext(Dispatchers.IO) {
            stopRequested = true
            soap(descriptor.avTransportUrl, descriptor.avTransportType, "Stop", "<InstanceID>0</InstanceID>", logFailure = false)
            Unit
        }

    private fun pollState() =
        synchronized(seekLock) {
            val pollIntervalMs = lastPoll?.elapsedNow()?.inWholeMilliseconds?.coerceAtLeast(POLL_INTERVAL_MS) ?: POLL_INTERVAL_MS
            lastPoll = TimeSource.Monotonic.markNow()
            val pollGeneration = mediaGeneration
            val pollSeekGeneration = seekGeneration
            val position =
                soap(
                    descriptor.avTransportUrl,
                    descriptor.avTransportType,
                    "GetPositionInfo",
                    "<InstanceID>0</InstanceID>",
                )
            val transport =
                soap(
                    descriptor.avTransportUrl,
                    descriptor.avTransportType,
                    "GetTransportInfo",
                    "<InstanceID>0</InstanceID>",
                )
            if (pollGeneration != mediaGeneration || pollSeekGeneration != seekGeneration) return@synchronized
            val current = _state.value
            val positionMs = position.text("RelTime")?.let(::parseDlnaTime) ?: current.positionMs
            val durationMs = position.text("TrackDuration")?.let(::parseDlnaTime)?.takeIf { it > 0L } ?: current.durationMs
            val trackUri = position.text("TrackURI")
            val transportState = transport.text("CurrentTransportState")
            val volume = readVolume(logFailure = false) ?: current.volume
            val isPlaying = transportState == "PLAYING" || transportState == "TRANSITIONING"
            val isBuffering = transportState == "TRANSITIONING"
            normalizeSonosPlayMode()
            if (pollGeneration != mediaGeneration || pollSeekGeneration != seekGeneration) return@synchronized
            if (!trackUri.isNullOrEmpty() && lastPlayingTrackUri != null && trackUri != lastPlayingTrackUri) {
                restartPending = false
                lastPlayingPositionMs = null
                lastPlayingTrackUri = trackUri
            }
            val wasPlaying = current.isPlaying && lastPlayingPositionMs != null
            val restarted =
                descriptor.isSonos &&
                    (
                        restartPending ||
                            castPlaybackRestartedNearDuration(
                                wasPlaying = wasPlaying,
                                isPlaying = transportState == "PLAYING",
                                sameMedia = trackUri != null && trackUri == lastPlayingTrackUri,
                                positionMs = positionMs,
                                durationMs = durationMs,
                                lastPlayingPositionMs = lastPlayingPositionMs ?: positionMs,
                                pollIntervalMs = pollIntervalMs,
                            )
                    )
            if (restarted) {
                restartPending = true
                runCatching {
                    soap(
                        descriptor.avTransportUrl,
                        descriptor.avTransportType,
                        "Stop",
                        "<InstanceID>0</InstanceID>",
                        logFailure = false,
                    )
                }.getOrThrow()
                restartPending = false
                lastPlayingPositionMs = null
                lastPlayingTrackUri = null
            } else if (transportState == "PLAYING") {
                lastPlayingPositionMs = positionMs
                lastPlayingTrackUri = trackUri
            }
            _state.value =
                CastSessionState(
                    connected = true,
                    isPlaying = isPlaying,
                    isBuffering = isBuffering,
                    positionMs = positionMs,
                    durationMs = durationMs,
                    volume = volume,
                    hasEnded =
                        !stopRequested && (
                            (current.hasEnded && transportState == "STOPPED") ||
                                castPlaybackEndedNearDuration(
                                    current.isPlaying,
                                    transportState == "STOPPED",
                                    positionMs,
                                    durationMs,
                                    lastPlayingPositionMs = lastPlayingPositionMs ?: positionMs,
                                    pollIntervalMs = pollIntervalMs,
                                ) || restarted
                        ),
                    mediaGeneration = pollGeneration,
                )
        }

    private fun normalizeSonosPlayMode() {
        if (!descriptor.isSonos) return
        val mode =
            runCatching {
                soap(
                    descriptor.avTransportUrl,
                    descriptor.avTransportType,
                    "GetTransportSettings",
                    "<InstanceID>0</InstanceID>",
                    logFailure = false,
                ).text("PlayMode")
            }.getOrNull() ?: return
        if (mode == "NORMAL") return
        runCatching {
            soap(
                descriptor.avTransportUrl,
                descriptor.avTransportType,
                "SetPlayMode",
                "<InstanceID>0</InstanceID><NewPlayMode>NORMAL</NewPlayMode>",
                logFailure = false,
            )
        }
    }

    private fun readVolume(logFailure: Boolean = true): Float? {
        val url = descriptor.renderingControlUrl ?: return null
        val type = descriptor.renderingControlType ?: return null
        return runCatching {
            soap(url, type, "GetVolume", "<InstanceID>0</InstanceID><Channel>Master</Channel>", logFailure = logFailure)
                .text("CurrentVolume")
                ?.toFloatOrNull()
                ?.div(100f)
                ?.coerceIn(0f, 1f)
        }.getOrNull()
    }

    private fun soap(
        url: String,
        serviceType: String,
        action: String,
        arguments: String,
        logFailure: Boolean = true,
    ): Document {
        val endpoint = validatedLocalHttpUri(url)
        val envelope =
            """<?xml version="1.0" encoding="utf-8"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><u:$action xmlns:u="${xmlEscape(
                serviceType,
            )}">$arguments</u:$action></s:Body></s:Envelope>"""
        val response =
            try {
                localHttpRequest(
                    uri = endpoint,
                    method = "POST",
                    headers =
                        mapOf(
                            "Content-Type" to "text/xml; charset=utf-8",
                            "SOAPACTION" to "\"$serviceType#$action\"",
                        ),
                    body = envelope.toByteArray(Charsets.UTF_8),
                )
            } catch (error: Exception) {
                if (logFailure) {
                    PlaybackEventLog.error(PlaybackEventKind.NETWORK, "DlnaSession", "SOAP $action request failed", error)
                }
                throw error
            }
        if (response.status !in 200..299) {
            val error = IllegalStateException("SOAP $action failed with HTTP ${response.status}")
            if (logFailure) {
                PlaybackEventLog.error(PlaybackEventKind.NETWORK, "DlnaSession", "SOAP $action request failed", error)
            }
            throw error
        }
        return try {
            parseXml(response.body)
        } catch (error: Exception) {
            if (logFailure) {
                PlaybackEventLog.error(PlaybackEventKind.NETWORK, "DlnaSession", "SOAP $action response could not be parsed", error)
            }
            throw error
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        pollingJob?.cancel()
        _state.value = CastSessionState(mediaGeneration = mediaGeneration)
    }
}

internal fun formatDlnaTime(positionMs: Long): String {
    val totalSeconds = positionMs.coerceAtLeast(0L) / 1000L
    val hours = totalSeconds / 3600L
    val minutes = totalSeconds % 3600L / 60L
    val seconds = totalSeconds % 60L
    return "%02d:%02d:%02d".format(Locale.ROOT, hours, minutes, seconds)
}

internal fun parseDlnaTime(value: String): Long? {
    val parts = value.trim().split(':')
    if (parts.size != 3) return null
    val hours = parts[0].toLongOrNull() ?: return null
    val minutes = parts[1].toLongOrNull()?.takeIf { it in 0..59 } ?: return null
    val seconds = parts[2].substringBefore('.').toLongOrNull()?.takeIf { it in 0..59 } ?: return null
    return (hours * 3600L + minutes * 60L + seconds) * 1000L
}

private fun validatedLocalHttpUri(value: String): URI {
    val uri = URI(value)
    require(uri.scheme.equals("http", true))
    require(uri.userInfo == null && uri.host != null)
    require(InetAddress.getAllByName(uri.host).all(::isLocalNetworkAddress))
    return uri
}

private fun resolveLocalControlUrl(
    base: URI,
    controlUrl: String,
): String = validatedLocalHttpUri(base.resolve(controlUrl.trim()).toString()).toString()

internal fun isLocalNetworkAddress(address: InetAddress): Boolean {
    if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress) return true
    val bytes = address.address
    return address is Inet6Address && bytes.isNotEmpty() && (bytes[0].toInt() and 0xfe) == 0xfc
}

private data class LocalHttpResponse(
    val status: Int,
    val body: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as LocalHttpResponse

        if (status != other.status) return false
        if (!body.contentEquals(other.body)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = status
        result = 31 * result + body.contentHashCode()
        return result
    }
}

private fun localHttpRequest(
    uri: URI,
    method: String,
    headers: Map<String, String> = emptyMap(),
    body: ByteArray = ByteArray(0),
): LocalHttpResponse {
    val address =
        InetAddress
            .getAllByName(uri.host)
            .sortedBy { if (it is Inet4Address) 0 else 1 }
            .firstOrNull(::isLocalNetworkAddress)
            ?: error("Invalid local address for host=${uri.host}")
    val port = uri.port.takeIf { it >= 0 } ?: 80
    val target =
        buildString {
            append(uri.rawPath?.takeIf(String::isNotEmpty) ?: "/")
            uri.rawQuery?.let { append('?').append(it) }
        }
    val host = if (uri.host.contains(':')) "[${uri.host}]" else uri.host
    return Socket().use { socket ->
        socket.connect(InetSocketAddress(address, port), HTTP_TIMEOUT_MS)
        socket.soTimeout = HTTP_TIMEOUT_MS
        val output = BufferedOutputStream(socket.getOutputStream())
        output.write(
            buildString {
                append("$method $target HTTP/1.1\r\n")
                append("Host: $host:$port\r\n")
                append("Connection: close\r\n")
                headers.forEach { (name, value) -> append("$name: $value\r\n") }
                if (body.isNotEmpty()) append("Content-Length: ${body.size}\r\n")
                append("\r\n")
            }.toByteArray(Charsets.US_ASCII),
        )
        output.write(body)
        output.flush()

        val input = BufferedInputStream(socket.getInputStream())
        val statusLine = readHttpLine(input) ?: error("Missing HTTP status")
        val status = statusLine.split(' ').getOrNull(1)?.toIntOrNull() ?: error("Invalid HTTP status")
        val responseHeaders = linkedMapOf<String, String>()
        var headerBytes = statusLine.length
        while (true) {
            val line = readHttpLine(input) ?: error("Incomplete HTTP headers")
            headerBytes += line.length
            require(headerBytes <= MAX_HTTP_HEADER_BYTES)
            if (line.isEmpty()) break
            val separator = line.indexOf(':')
            if (separator > 0) responseHeaders[line.substring(0, separator).trim().lowercase()] = line.substring(separator + 1).trim()
        }
        val responseBody =
            if (responseHeaders["transfer-encoding"]?.contains("chunked", true) == true) {
                readChunkedBody(input)
            } else {
                val length = responseHeaders["content-length"]?.toIntOrNull()
                if (length != null) {
                    require(length in 0..MAX_XML_BYTES)
                    input.readUpTo(length).also { require(it.size == length) }
                } else {
                    readUntilEof(input)
                }
            }
        LocalHttpResponse(status, responseBody)
    }
}

private fun readChunkedBody(input: BufferedInputStream): ByteArray {
    val output = ByteArrayOutputStream()
    while (true) {
        val size = readHttpLine(input)?.substringBefore(';')?.trim()?.toIntOrNull(16) ?: error("Invalid chunk")
        if (size == 0) {
            while (true) {
                if (readHttpLine(input).isNullOrEmpty()) break
            }
            return output.toByteArray()
        }
        require(size > 0 && output.size() + size <= MAX_XML_BYTES)
        val chunk = input.readUpTo(size)
        require(chunk.size == size)
        output.write(chunk)
        require(readHttpLine(input)?.isEmpty() == true)
    }
}

private fun readUntilEof(input: BufferedInputStream): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) return output.toByteArray()
        require(output.size() + count <= MAX_XML_BYTES)
        output.write(buffer, 0, count)
    }
}

private fun readHttpLine(input: BufferedInputStream): String? {
    val output = ByteArrayOutputStream()
    while (output.size() <= MAX_HTTP_LINE_BYTES) {
        val value = input.read()
        if (value < 0) return null
        if (value == '\n'.code) {
            val bytes = output.toByteArray()
            val length = if (bytes.lastOrNull() == '\r'.code.toByte()) bytes.size - 1 else bytes.size
            return bytes.copyOf(length).toString(Charsets.US_ASCII)
        }
        output.write(value)
    }
    error("HTTP line too long")
}

internal expect fun disallowDlnaDoctype(
    factory: DocumentBuilderFactory,
    bytes: ByteArray,
)

private fun parseXml(bytes: ByteArray): Document {
    require(!bytes.toString(Charsets.UTF_8).contains("<!DOCTYPE", ignoreCase = true))
    val factory = DocumentBuilderFactory.newInstance()
    // XInclude is off by default; Android's factory throws on setXIncludeAware, even when disabling it.
    factory.isNamespaceAware = true
    factory.isExpandEntityReferences = false
    disallowDlnaDoctype(factory, bytes)
    return factory
        .newDocumentBuilder()
        .apply {
            setEntityResolver { _, _ -> throw SAXException("External entities are not allowed") }
        }.parse(ByteArrayInputStream(bytes))
}

private fun Document.elements(localName: String): List<Element> {
    val namespaced = getElementsByTagNameNS("*", localName)
    val nodes = if (namespaced.length > 0) namespaced else getElementsByTagName(localName)
    return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
}

private fun Element.text(localName: String): String? {
    val namespaced = getElementsByTagNameNS("*", localName)
    val nodes = if (namespaced.length > 0) namespaced else getElementsByTagName(localName)
    return nodes.item(0)?.textContent
}

private fun Document.text(localName: String): String? = elements(localName).firstOrNull()?.textContent

private fun xmlEscape(value: String): String =
    buildString(value.length) {
        value.forEach {
            append(
                when (it) {
                    '&' -> "&amp;"
                    '<' -> "&lt;"
                    '>' -> "&gt;"
                    '"' -> "&quot;"
                    '\'' -> "&apos;"
                    else -> it
                },
            )
        }
    }

private const val MAX_XML_BYTES = 1_048_576
private const val MAX_HTTP_HEADER_BYTES = 32_768
private const val MAX_HTTP_LINE_BYTES = 8_192
private const val HTTP_TIMEOUT_MS = 8_000
private const val POLL_INTERVAL_MS = 1_000L
private const val MAX_CONSECUTIVE_POLL_ERRORS = 5

internal data class SonosZone(
    val coordinatorId: String,
    val name: String,
    val location: String,
)

/**
 * Lists each visible Sonos group once, at its coordinator. Every grouped, bonded or satellite speaker answers SSDP,
 * but only the coordinator accepts transport commands; the others fail with UPnP error 800.
 */
internal fun parseSonosZoneGroups(zoneGroupState: String): List<SonosZone> =
    parseXml(zoneGroupState.toByteArray(Charsets.UTF_8)).elements("ZoneGroup").mapNotNull { group ->
        val coordinator = group.getAttribute("Coordinator").takeIf(String::isNotEmpty) ?: return@mapNotNull null
        val members = group.childElements("ZoneGroupMember")
        val leader = members.firstOrNull { it.getAttribute("UUID") == coordinator } ?: return@mapNotNull null
        if (leader.getAttribute("Invisible") == "1") return@mapNotNull null
        val names =
            members
                .filter { it.getAttribute("Invisible") != "1" }
                .map { it.getAttribute("ZoneName").trim() }
                .filter(String::isNotEmpty)
                .distinct()
        SonosZone("uuid:$coordinator", names.joinToString(" + ").ifEmpty { coordinator }, leader.getAttribute("Location"))
    }

/** Replaces discovered Sonos speakers with their group coordinators, or returns null when the topology is unavailable. */
internal suspend fun sonosGroupDevices(speakers: List<CastDevice>): List<CastDevice>? =
    withContext(Dispatchers.IO) {
        speakers.firstNotNullOfOrNull { speaker ->
            runCatching {
                val location = validatedLocalHttpUri(checkNotNull(speaker.endpoint))
                val control = validatedLocalHttpUri(location.resolve(SONOS_TOPOLOGY_CONTROL_PATH).toString())
                val response =
                    localHttpRequest(
                        uri = control,
                        method = "POST",
                        headers =
                            mapOf(
                                "Content-Type" to "text/xml; charset=utf-8",
                                "SOAPACTION" to "\"$SONOS_TOPOLOGY_SERVICE#GetZoneGroupState\"",
                            ),
                        body = SONOS_TOPOLOGY_REQUEST.toByteArray(Charsets.UTF_8),
                    )
                check(response.status in 200..299) { "Sonos topology request failed with HTTP ${response.status}" }
                val state = parseXml(response.body).text("ZoneGroupState") ?: error("Missing ZoneGroupState")
                parseSonosZoneGroups(state).mapNotNull { zone ->
                    runCatching {
                        val uri = validatedLocalHttpUri(zone.location)
                        CastDevice(
                            id = "dlna:${zone.coordinatorId}",
                            name = zone.name,
                            protocol = CastProtocol.SONOS,
                            host = uri.host,
                            port = uri.port.takeIf { it >= 0 } ?: 80,
                            endpoint = zone.location,
                        )
                    }.getOrNull()
                }
            }.getOrNull()
        }
    }

private fun Element.childElements(localName: String): List<Element> {
    val namespaced = getElementsByTagNameNS("*", localName)
    val nodes = if (namespaced.length > 0) namespaced else getElementsByTagName(localName)
    return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
}

private const val SONOS_TOPOLOGY_CONTROL_PATH = "/ZoneGroupTopology/Control"
private const val SONOS_TOPOLOGY_SERVICE = "urn:schemas-upnp-org:service:ZoneGroupTopology:1"
private const val SONOS_TOPOLOGY_REQUEST =
    """<?xml version="1.0" encoding="utf-8"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" """ +
        """s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><u:GetZoneGroupState """ +
        """xmlns:u="$SONOS_TOPOLOGY_SERVICE"></u:GetZoneGroupState></s:Body></s:Envelope>"""
