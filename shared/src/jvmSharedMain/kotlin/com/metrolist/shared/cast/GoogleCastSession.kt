package com.metrolist.shared.cast

import com.metrolist.shared.youtube.playback.PlaybackEventKind
import com.metrolist.shared.youtube.playback.PlaybackEventLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import kotlin.time.Duration.Companion.milliseconds

internal data class CastWireMessage(
    val sourceId: String,
    val destinationId: String,
    val namespace: String,
    val payload: String,
)

internal fun encodeCastWireMessage(message: CastWireMessage): ByteArray {
    val output = ByteArrayOutputStream()
    writeVarintField(output, 1, 0)
    writeStringField(output, 2, message.sourceId)
    writeStringField(output, 3, message.destinationId)
    writeStringField(output, 4, message.namespace)
    writeVarintField(output, 5, 0)
    writeStringField(output, 6, message.payload)
    return output.toByteArray()
}

internal fun decodeCastWireMessage(bytes: ByteArray): CastWireMessage {
    val input = ByteArrayInputStream(bytes)
    var sourceId = ""
    var destinationId = ""
    var namespace = ""
    var payload = ""
    while (input.available() > 0) {
        val tag = readVarint(input).toInt()
        val field = tag ushr 3
        val wireType = tag and 7
        when {
            wireType == 0 -> {
                readVarint(input)
            }

            wireType == 2 -> {
                val length = readVarint(input).toInt()
                require(length in 0..MAX_CAST_MESSAGE_SIZE && length <= input.available())
                val value = input.readUpTo(length).toString(Charsets.UTF_8)
                when (field) {
                    2 -> sourceId = value
                    3 -> destinationId = value
                    4 -> namespace = value
                    6 -> payload = value
                }
            }

            else -> {
                error("Unsupported Cast wire type")
            }
        }
    }
    return CastWireMessage(sourceId, destinationId, namespace, payload)
}

internal fun readGoogleCastFrameSize(input: InputStream): Int? {
    val sizeBytes = input.readUpTo(Int.SIZE_BYTES)
    if (sizeBytes.isEmpty()) return null
    if (sizeBytes.size != Int.SIZE_BYTES) throw EOFException("Receiver closed during message header")
    return ByteBuffer.wrap(sizeBytes).int
}

private fun writeVarintField(
    output: ByteArrayOutputStream,
    field: Int,
    value: Long,
) {
    writeVarint(output, (field shl 3).toLong())
    writeVarint(output, value)
}

private fun writeStringField(
    output: ByteArrayOutputStream,
    field: Int,
    value: String,
) {
    val bytes = value.toByteArray(Charsets.UTF_8)
    writeVarint(output, ((field shl 3) or 2).toLong())
    writeVarint(output, bytes.size.toLong())
    output.write(bytes)
}

private fun writeVarint(
    output: ByteArrayOutputStream,
    value: Long,
) {
    var remaining = value
    while (true) {
        if (remaining and -128L == 0L) {
            output.write(remaining.toInt())
            return
        }
        output.write((remaining.toInt() and 0x7f) or 0x80)
        remaining = remaining ushr 7
    }
}

private fun readVarint(input: InputStream): Long {
    var result = 0L
    var shift = 0
    while (shift < 64) {
        val value = input.read()
        require(value >= 0)
        result = result or ((value and 0x7f).toLong() shl shift)
        if (value and 0x80 == 0) return result
        shift += 7
    }
    error("Invalid varint")
}

internal fun CastSessionState.withGoogleCastMedia(
    durationMs: Long,
    positionMs: Long,
    autoplay: Boolean,
): CastSessionState =
    copy(
        connected = true,
        isPlaying = autoplay,
        positionMs = positionMs,
        durationMs = durationMs,
        hasEnded = false,
    )

internal fun googleCastMediaInformation(
    mediaUrl: String,
    media: CastMedia,
): JsonObject =
    buildJsonObject {
        put("contentId", mediaUrl)
        put("streamType", "BUFFERED")
        put("contentType", requireNotNull(media.resource.mimeType).substringBefore(';').trim())
        put("duration", media.durationMs / 1000.0)
        put(
            "metadata",
            buildJsonObject {
                put("metadataType", 3)
                put("title", media.title)
                media.artist?.takeIf(String::isNotBlank)?.let { put("artist", it) }
                media.album?.takeIf(String::isNotBlank)?.let { put("albumName", it) }
                media.artworkUrl?.let { artwork ->
                    put("images", buildJsonArray { add(buildJsonObject { put("url", artwork) }) })
                }
            },
        )
    }

internal class GoogleCastSession(
    private val scope: CoroutineScope,
    private val address: InetAddress,
    private val port: Int,
) : CastSession {
    private val json = Json { ignoreUnknownKeys = true }
    private val sourceId = "sender-${SecureRandom().nextInt().toUInt()}"
    private val requestIds = AtomicInteger(0)
    private val messages = MutableSharedFlow<CastWireMessage>(replay = 16, extraBufferCapacity = 32)
    private val _state = MutableStateFlow(CastSessionState())
    override val state: StateFlow<CastSessionState> = _state.asStateFlow()
    private val writeLock = Any()
    private lateinit var socket: SSLSocket
    private lateinit var input: DataInputStream
    private lateinit var output: DataOutputStream
    private var readerJob: Job? = null
    private var pollingJob: Job? = null
    private var transportId: String? = null
    private var receiverSessionId: String? = null
    private var mediaSessionId: Int? = null
    private val mediaSessionGenerations = mutableMapOf<Int, Long>()

    @Volatile
    private var pendingMediaGeneration: Long? = null

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
        this@GoogleCastSession.mediaGeneration = mediaGeneration
        if (!::socket.isInitialized) {
            connectSocket()
            readerJob = scope.launch(Dispatchers.IO) { readLoop() }
            sendConnection(RECEIVER_ID)

            val statusResponse = request(RECEIVER_NAMESPACE, RECEIVER_ID, "GET_STATUS")
            var application = findDefaultReceiver(statusResponse)
            if (application == null) {
                val launchResponse = request(RECEIVER_NAMESPACE, RECEIVER_ID, "LAUNCH") { put("appId", DEFAULT_MEDIA_RECEIVER_APP_ID) }
                application = findDefaultReceiver(launchResponse)
                if (application == null) {
                    application =
                        withTimeout(LAUNCH_TIMEOUT_MS.milliseconds) {
                            messages
                                .mapNotNull { message ->
                                    message.payloadObject()?.takeIf { it.type() == "RECEIVER_STATUS" }?.let(::findDefaultReceiver)
                                }.first()
                        }
                }
            }
            transportId = application["transportId"]?.jsonPrimitive?.contentOrNull ?: error("Missing Cast transport")
            receiverSessionId = application["sessionId"]?.jsonPrimitive?.contentOrNull
            sendConnection(requireNotNull(transportId))
        }
        _state.value =
            _state.value
                .copy(mediaGeneration = mediaGeneration)
                .withGoogleCastMedia(media.durationMs, positionMs, autoplay)
        mediaSessionId = null
        pendingMediaGeneration = mediaGeneration
        val response =
            request(MEDIA_NAMESPACE, requireNotNull(transportId), "LOAD") {
                receiverSessionId?.let { put("sessionId", it) }
                put(
                    "media",
                    googleCastMediaInformation(mediaUrl, media),
                )
                put("autoplay", autoplay)
                put("currentTime", positionMs / 1000.0)
            }
        check(response.type() != "LOAD_FAILED")
        updateMediaStatus(response, loadedMediaGeneration = mediaGeneration)
        if (pollingJob == null) {
            pollingJob =
                scope.launch(Dispatchers.IO) {
                    var consecutiveErrors = 0
                    while (isActive && !closed) {
                        delay(POLL_INTERVAL_MS.milliseconds)
                        val ok = runCatching { sendMediaCommand("GET_STATUS") }.isSuccess
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

    override suspend fun play() = withContext(Dispatchers.IO) { sendMediaCommand("PLAY") }

    override suspend fun pause() = withContext(Dispatchers.IO) { sendMediaCommand("PAUSE") }

    override suspend fun seekTo(positionMs: Long) =
        withContext(Dispatchers.IO) {
            sendMediaCommand("SEEK") {
                put("currentTime", positionMs / 1000.0)
                put("resumeState", if (state.value.isPlaying) "PLAYBACK_START" else "PLAYBACK_PAUSE")
            }
        }

    override suspend fun setVolume(volume: Float) =
        withContext(Dispatchers.IO) {
            if (!::output.isInitialized) return@withContext
            send(
                RECEIVER_NAMESPACE,
                RECEIVER_ID,
                buildJsonObject {
                    put("type", "SET_VOLUME")
                    put("requestId", requestIds.incrementAndGet())
                    put("volume", buildJsonObject { put("level", volume.toDouble().coerceIn(0.0, 1.0)) })
                }.toString(),
            )
        }

    override suspend fun stop() = withContext(Dispatchers.IO) { sendMediaCommand("STOP") }

    private fun connectSocket() {
        // Cast receivers use self-signed TLS certificates; the socket is scoped to the LAN address selected through discovery.
        val trustManager =
            object : X509TrustManager {
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

                override fun checkClientTrusted(
                    chain: Array<out X509Certificate>?,
                    authType: String?,
                ) = Unit

                override fun checkServerTrusted(
                    chain: Array<out X509Certificate>?,
                    authType: String?,
                ) = Unit
            }
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf(trustManager), SecureRandom())
        socket = context.socketFactory.createSocket() as SSLSocket
        socket.connect(InetSocketAddress(address, port), CONNECT_TIMEOUT_MS)
        socket.soTimeout = READ_TIMEOUT_MS
        socket.startHandshake()
        input = DataInputStream(socket.inputStream)
        output = DataOutputStream(socket.outputStream)
    }

    private suspend fun request(
        namespace: String,
        destinationId: String,
        type: String,
        content: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {},
    ): JsonObject {
        val requestId = requestIds.incrementAndGet()
        val payload =
            buildJsonObject {
                put("type", type)
                put("requestId", requestId)
                content()
            }
        send(namespace, destinationId, payload.toString())
        return withTimeout(COMMAND_TIMEOUT_MS.milliseconds) {
            messages
                .mapNotNull { message ->
                    message.payloadObject()?.takeIf { it["requestId"]?.jsonPrimitive?.intOrNull == requestId }
                }.first()
        }
    }

    private fun sendMediaCommand(
        type: String,
        content: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {},
    ) {
        val destination = transportId ?: return
        send(
            MEDIA_NAMESPACE,
            destination,
            buildJsonObject {
                put("type", type)
                put("requestId", requestIds.incrementAndGet())
                receiverSessionId?.let { put("sessionId", it) }
                mediaSessionId?.let { put("mediaSessionId", it) }
                content()
            }.toString(),
        )
    }

    private fun sendConnection(destinationId: String) {
        send(
            CONNECTION_NAMESPACE,
            destinationId,
            buildJsonObject {
                put("type", "CONNECT")
                put("origin", buildJsonObject {})
            }.toString(),
        )
    }

    private fun send(
        namespace: String,
        destinationId: String,
        payload: String,
    ) {
        if (closed) return
        val bytes = encodeCastWireMessage(CastWireMessage(sourceId, destinationId, namespace, payload))
        require(bytes.size <= MAX_CAST_MESSAGE_SIZE)
        synchronized(writeLock) {
            output.writeInt(bytes.size)
            output.write(bytes)
            output.flush()
        }
    }

    private fun readLoop() {
        try {
            while (!closed) {
                val size = readGoogleCastFrameSize(input) ?: break
                require(size in 1..MAX_CAST_MESSAGE_SIZE)
                val message = decodeCastWireMessage(input.readUpTo(size).also { require(it.size == size) })
                if (message.namespace == HEARTBEAT_NAMESPACE && message.payloadObject()?.type() == "PING") {
                    send(HEARTBEAT_NAMESPACE, message.sourceId, buildJsonObject { put("type", "PONG") }.toString())
                }
                val payload = message.payloadObject()
                when (payload?.type()) {
                    "MEDIA_STATUS" -> updateMediaStatus(payload)
                    "RECEIVER_STATUS" -> updateReceiverStatus(payload)
                    "LOAD_FAILED", "LOAD_CANCELLED" -> close()
                }
                messages.tryEmit(message)
            }
        } catch (error: Exception) {
            if (!closed) {
                PlaybackEventLog.error(PlaybackEventKind.NETWORK, "GoogleCast", "Receiver connection failed", error)
            }
        } finally {
            if (!closed) close()
        }
    }

    @Synchronized
    private fun updateMediaStatus(
        payload: JsonObject,
        loadedMediaGeneration: Long? = null,
    ) {
        val status = payload["status"]?.jsonArray?.firstOrNull()?.jsonObject ?: return
        val statusMediaSessionId = status["mediaSessionId"]?.jsonPrimitive?.intOrNull
        val statusMediaGeneration =
            if (loadedMediaGeneration != null) {
                statusMediaSessionId?.let { mediaSessionGenerations[it] = loadedMediaGeneration }
                if (statusMediaSessionId != null) pendingMediaGeneration = null
                loadedMediaGeneration
            } else {
                val sessionId = statusMediaSessionId ?: return
                mediaSessionGenerations[sessionId]
                    ?: pendingMediaGeneration?.also {
                        mediaSessionGenerations[sessionId] = it
                        pendingMediaGeneration = null
                    } ?: return
            }
        if (pendingMediaGeneration == null) {
            mediaSessionGenerations.entries.removeIf { it.value < statusMediaGeneration - 1L }
        }
        if (statusMediaGeneration != mediaGeneration) return
        mediaSessionId = statusMediaSessionId ?: mediaSessionId
        val current = _state.value
        val playerState = status["playerState"]?.jsonPrimitive?.contentOrNull
        val idleReason = status["idleReason"]?.jsonPrimitive?.contentOrNull
        if (playerState == "IDLE" && idleReason == "ERROR") {
            close()
            return
        }
        val duration =
            status["media"]
                ?.jsonObject
                ?.get("duration")
                ?.jsonPrimitive
                ?.doubleOrNull
        _state.value =
            current.copy(
                isPlaying = playerState == "PLAYING" || playerState == "BUFFERING",
                positionMs =
                    status["currentTime"]
                        ?.jsonPrimitive
                        ?.doubleOrNull
                        ?.times(1000.0)
                        ?.toLong() ?: current.positionMs,
                durationMs = duration?.times(1000.0)?.toLong() ?: current.durationMs,
                hasEnded = playerState == "IDLE" && idleReason == "FINISHED",
                mediaGeneration = statusMediaGeneration,
            )
    }

    private fun updateReceiverStatus(payload: JsonObject) {
        val level =
            payload["status"]
                ?.jsonObject
                ?.get("volume")
                ?.jsonObject
                ?.get("level")
                ?.jsonPrimitive
                ?.doubleOrNull
        if (level != null) _state.value = _state.value.copy(volume = level.toFloat().coerceIn(0f, 1f))
    }

    private fun findDefaultReceiver(payload: JsonObject): JsonObject? =
        payload["status"]
            ?.jsonObject
            ?.get("applications")
            ?.jsonArray
            ?.firstOrNull { application ->
                application.jsonObject["appId"]?.jsonPrimitive?.contentOrNull == DEFAULT_MEDIA_RECEIVER_APP_ID
            }?.jsonObject

    private fun CastWireMessage.payloadObject(): JsonObject? = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull()

    private fun JsonObject.type(): String? = this["type"]?.jsonPrimitive?.contentOrNull

    override fun close() {
        if (closed) return
        closed = true
        pollingJob?.cancel()
        readerJob?.cancel()
        runCatching { socket.close() }
        _state.value = CastSessionState(mediaGeneration = mediaGeneration)
    }

    private companion object {
        const val RECEIVER_ID = "receiver-0"
        const val DEFAULT_MEDIA_RECEIVER_APP_ID = "CC1AD845"
        const val CONNECTION_NAMESPACE = "urn:x-cast:com.google.cast.tp.connection"
        const val HEARTBEAT_NAMESPACE = "urn:x-cast:com.google.cast.tp.heartbeat"
        const val RECEIVER_NAMESPACE = "urn:x-cast:com.google.cast.receiver"
        const val MEDIA_NAMESPACE = "urn:x-cast:com.google.cast.media"
        const val CONNECT_TIMEOUT_MS = 8_000
        const val READ_TIMEOUT_MS = 30_000
        const val COMMAND_TIMEOUT_MS = 8_000L
        const val LAUNCH_TIMEOUT_MS = 12_000L
        const val POLL_INTERVAL_MS = 1_000L
        const val MAX_CONSECUTIVE_POLL_ERRORS = 5
    }
}

private const val MAX_CAST_MESSAGE_SIZE = 512 * 1024
