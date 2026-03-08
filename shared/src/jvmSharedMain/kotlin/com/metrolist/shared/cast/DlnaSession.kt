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
        if (::descriptor.isInitialized) {
            runCatching { stop() }
        } else {
            descriptor = fetchDlnaDescriptor(descriptorLocation)
        }
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
        if (positionMs > 0L) {
            seekTo(positionMs)
        }
        if (autoplay) play()
        _state.value =
            CastSessionState(
                connected = true,
                isPlaying = autoplay,
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
            soap(
                descriptor.avTransportUrl,
                descriptor.avTransportType,
                "Seek",
                "<InstanceID>0</InstanceID><Unit>REL_TIME</Unit><Target>${formatDlnaTime(positionMs)}</Target>",
                logFailure = false,
            )
            Unit
        }

    override suspend fun setVolume(volume: Float) =
        withContext(Dispatchers.IO) {
            val controlUrl = descriptor.renderingControlUrl ?: return@withContext
            val controlType = descriptor.renderingControlType ?: return@withContext
            val requestedVolume = (volume.coerceIn(0f, 1f) * 100).toInt()
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
            soap(descriptor.avTransportUrl, descriptor.avTransportType, "Stop", "<InstanceID>0</InstanceID>", logFailure = false)
            Unit
        }

    private fun pollState() {
        val pollGeneration = mediaGeneration
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
        if (pollGeneration != mediaGeneration) return
        val current = _state.value
        val positionMs = position.text("RelTime")?.let(::parseDlnaTime) ?: current.positionMs
        val durationMs = position.text("TrackDuration")?.let(::parseDlnaTime)?.takeIf { it > 0L } ?: current.durationMs
        val transportState = transport.text("CurrentTransportState")
        val volume = readVolume(logFailure = false) ?: current.volume
        val isPlaying = transportState == "PLAYING" || transportState == "TRANSITIONING"
        _state.value =
            CastSessionState(
                connected = true,
                isPlaying = isPlaying,
                positionMs = positionMs,
                durationMs = durationMs,
                volume = volume,
                hasEnded =
                    castPlaybackEndedNearDuration(
                        current.isPlaying,
                        transportState == "STOPPED",
                        positionMs,
                        durationMs,
                    ),
                mediaGeneration = pollGeneration,
            )
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

private fun parseXml(bytes: ByteArray): Document {
    require(!bytes.toString(Charsets.UTF_8).contains("<!DOCTYPE", ignoreCase = true))
    val factory = DocumentBuilderFactory.newInstance()
    factory.isNamespaceAware = true
    factory.isXIncludeAware = false
    factory.isExpandEntityReferences = false
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
    factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
    return factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
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
