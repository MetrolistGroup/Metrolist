package com.metrolist.shared.cast

import com.metrolist.shared.youtube.playback.PlaybackEventKind
import com.metrolist.shared.youtube.playback.PlaybackEventLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds

private const val FCAST_EVENT_MEDIA_ITEM_END = 1

internal fun isCurrentFcastPlaybackUpdate(
    awaitingCurrentMediaUpdate: Boolean,
    hasEnded: Boolean,
    expectedDurationMs: Long,
    actualDurationMs: Long,
    expectedStartPositionMs: Long,
    actualPositionMs: Long,
    hasReportedPosition: Boolean,
): Boolean =
    !awaitingCurrentMediaUpdate ||
        (
            !hasEnded &&
                hasReportedPosition &&
                abs(expectedStartPositionMs - actualPositionMs) <= 5_000L &&
                (expectedDurationMs <= 0L || actualDurationMs <= 0L || abs(expectedDurationMs - actualDurationMs) <= 1_500L)
        )

internal fun isCurrentFcastMediaItemEndEvent(
    body: String?,
    currentMediaUrl: String?,
): Boolean {
    if (body == null || currentMediaUrl == null) return false
    val payload = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return false
    val event = payload["event"] as? JsonObject ?: return false
    val eventType = (event["type"] as? JsonPrimitive)?.intOrNull
    val mediaUrl = ((event["item"] as? JsonObject)?.get("url") as? JsonPrimitive)?.contentOrNull
    return eventType == FCAST_EVENT_MEDIA_ITEM_END && mediaUrl == currentMediaUrl
}

internal class FcastSession(
    private val scope: CoroutineScope,
    private val address: InetAddress,
    private val port: Int,
    private val appVersion: String,
) : CastSession {
    private val json = Json { ignoreUnknownKeys = true }
    private val socket = Socket()
    private val writeLock = Any()
    private val mediaStateLock = Any()
    private lateinit var input: BufferedInputStream
    private lateinit var output: BufferedOutputStream
    private val receiverVersion = CompletableDeferred<Int>()
    private val _state = MutableStateFlow(CastSessionState())
    override val state: StateFlow<CastSessionState> = _state.asStateFlow()
    private var readerJob: Job? = null
    private var heartbeatJob: Job? = null

    @Volatile
    private var mediaGeneration = 0L

    @Volatile
    private var awaitingCurrentMediaUpdate = false

    @Volatile
    private var awaitingMediaStartPositionMs = 0L

    @Volatile
    private var currentMediaUrl: String? = null

    private var loadedMedia: LoadedMedia? = null
    private var replayPositionMs = 0L

    @Volatile
    private var stopRequested = false

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
        synchronized(mediaStateLock) {
            check(!closed)
            this@FcastSession.mediaGeneration = mediaGeneration
            currentMediaUrl = mediaUrl
            loadedMedia = LoadedMedia(mediaUrl, media)
            replayPositionMs = 0L
            stopRequested = false
            awaitingCurrentMediaUpdate = true
            awaitingMediaStartPositionMs = positionMs
            _state.value =
                CastSessionState(
                    connected = true,
                    isPlaying = autoplay,
                    positionMs = positionMs,
                    durationMs = media.durationMs,
                    volume = _state.value.volume,
                    mediaGeneration = mediaGeneration,
                )
        }
        if (!socket.isConnected) {
            socket.connect(InetSocketAddress(address, port), CONNECT_TIMEOUT_MS)
            socket.soTimeout = READ_TIMEOUT_MS
            input = BufferedInputStream(socket.getInputStream())
            output = BufferedOutputStream(socket.getOutputStream())
            readerJob = scope.launch(Dispatchers.IO) { readLoop() }
            send(OPCODE_VERSION, buildJsonObject { put("version", PROTOCOL_VERSION) }.toString())
            val receiverProtocol = withTimeoutOrNull(CONNECT_TIMEOUT_MS.toLong().milliseconds) { receiverVersion.await() } ?: 1
            check(!closed)
            val negotiatedVersion = minOf(PROTOCOL_VERSION, receiverProtocol)
            if (negotiatedVersion >= 3) {
                send(
                    OPCODE_INITIAL,
                    buildJsonObject {
                        put("displayName", "Metrolist")
                        put("appName", "Metrolist")
                        put("appVersion", appVersion)
                    }.toString(),
                )
                send(
                    OPCODE_SUBSCRIBE_EVENT,
                    buildJsonObject {
                        put("event", buildJsonObject { put("type", FCAST_EVENT_MEDIA_ITEM_END) })
                    }.toString(),
                )
            }
            heartbeatJob =
                scope.launch(Dispatchers.IO) {
                    while (isActive && !closed) {
                        delay(HEARTBEAT_INTERVAL_MS.milliseconds)
                        runCatching { send(OPCODE_PING) }.onFailure { error ->
                            PlaybackEventLog.error(PlaybackEventKind.NETWORK, "Fcast", "Receiver heartbeat failed", error)
                            close()
                        }
                    }
                }
        }
        sendPlay(mediaUrl, media, positionMs)
        if (!autoplay) send(OPCODE_PAUSE)
    }

    override suspend fun play() =
        withContext(Dispatchers.IO) {
            synchronized(writeLock) {
                val stoppedMedia =
                    synchronized(mediaStateLock) {
                        loadedMedia?.takeIf { stopRequested || _state.value.hasEnded }?.also {
                            awaitingCurrentMediaUpdate = true
                            awaitingMediaStartPositionMs = replayPositionMs
                            _state.value =
                                _state.value.copy(
                                    isPlaying = true,
                                    isBuffering = true,
                                    positionMs = replayPositionMs,
                                    hasEnded = false,
                                    mediaGeneration = mediaGeneration,
                                )
                        } ?: run {
                            _state.value = _state.value.copy(isPlaying = true, hasEnded = false)
                            null
                        }
                    }
                if (stoppedMedia != null) {
                    sendPlay(stoppedMedia.url, stoppedMedia.media, replayPositionMs)
                    synchronized(mediaStateLock) { stopRequested = false }
                } else {
                    send(OPCODE_RESUME)
                }
            }
        }

    override suspend fun pause() = withContext(Dispatchers.IO) { send(OPCODE_PAUSE) }

    override suspend fun seekTo(positionMs: Long) =
        withContext(Dispatchers.IO) {
            synchronized(writeLock) {
                val stopped =
                    synchronized(mediaStateLock) {
                        (stopRequested || _state.value.hasEnded).also { stopped ->
                            if (stopped) {
                                replayPositionMs = positionMs
                                _state.value = _state.value.copy(positionMs = positionMs)
                            } else if (awaitingCurrentMediaUpdate) {
                                awaitingMediaStartPositionMs = positionMs
                            }
                        }
                    }
                if (!stopped) send(OPCODE_SEEK, buildJsonObject { put("time", positionMs / 1000.0) }.toString())
            }
        }

    override suspend fun setVolume(volume: Float) =
        withContext(Dispatchers.IO) {
            send(OPCODE_SET_VOLUME, buildJsonObject { put("volume", volume.toDouble()) }.toString())
        }

    override suspend fun stop() =
        withContext(Dispatchers.IO) {
            synchronized(writeLock) {
                synchronized(mediaStateLock) {
                    stopRequested = true
                    replayPositionMs = 0L
                    _state.value = _state.value.copy(isPlaying = false, isBuffering = false, positionMs = 0L, hasEnded = false)
                }
                send(OPCODE_STOP)
            }
        }

    private fun sendPlay(
        mediaUrl: String,
        media: CastMedia,
        positionMs: Long,
    ) {
        val mimeType = requireNotNull(media.resource.mimeType).substringBefore(';').trim()
        send(
            OPCODE_PLAY,
            buildJsonObject {
                put("container", mimeType)
                put("url", mediaUrl)
                put("time", positionMs / 1000.0)
                put("speed", 1.0)
                put(
                    "metadata",
                    buildJsonObject {
                        put("type", 0)
                        put("title", media.title)
                        media.artworkUrl?.let { put("thumbnailUrl", it) }
                    },
                )
            }.toString(),
        )
    }

    private data class LoadedMedia(
        val url: String,
        val media: CastMedia,
    )

    private fun readLoop() {
        try {
            while (!closed) {
                val sizeBytes = input.readUpTo(4)
                if (sizeBytes.isEmpty()) break
                if (sizeBytes.size != 4) throw EOFException("Receiver closed during packet header")
                val size = ByteBuffer.wrap(sizeBytes).order(ByteOrder.LITTLE_ENDIAN).int
                require(size in 1..MAX_PACKET_SIZE)
                val opcode = input.read()
                if (opcode < 0) throw EOFException("Receiver closed before packet opcode")
                val bodyBytes = input.readUpTo(size - 1)
                if (bodyBytes.size != size - 1) throw EOFException("Receiver closed during packet body")
                val body = if (bodyBytes.isNotEmpty()) bodyBytes.toString(Charsets.UTF_8) else null
                handle(opcode, body)
            }
        } catch (error: Exception) {
            if (!closed) {
                PlaybackEventLog.error(PlaybackEventKind.NETWORK, "Fcast", "Receiver connection failed", error)
            }
        } finally {
            if (!closed) close()
        }
    }

    private fun handle(
        opcode: Int,
        body: String?,
    ) {
        when (opcode) {
            OPCODE_VERSION -> {
                val version =
                    body?.let {
                        json
                            .parseToJsonElement(it)
                            .jsonObject["version"]
                            ?.jsonPrimitive
                            ?.intOrNull
                    }
                if (version != null && !receiverVersion.isCompleted) receiverVersion.complete(version)
            }

            OPCODE_PLAYBACK_UPDATE -> {
                val payload = body?.let { json.parseToJsonElement(it).jsonObject } ?: return
                val playbackState = payload["state"]?.jsonPrimitive?.intOrNull
                val isPlaying = playbackState == PLAYBACK_STATE_PLAYING
                val reportedPositionMs =
                    payload["time"]
                        ?.jsonPrimitive
                        ?.doubleOrNull
                        ?.times(1000.0)
                        ?.toLong()
                synchronized(mediaStateLock) {
                    if (stopRequested) return
                    val current = _state.value
                    val positionMs = reportedPositionMs ?: current.positionMs
                    val durationMs =
                        payload["duration"]
                            ?.jsonPrimitive
                            ?.doubleOrNull
                            ?.times(1000.0)
                            ?.toLong() ?: current.durationMs
                    val hasEnded =
                        current.hasEnded ||
                            castPlaybackEndedNearDuration(
                                current.isPlaying,
                                playbackState == PLAYBACK_STATE_IDLE,
                                positionMs,
                                durationMs,
                            )
                    if (hasEnded && !current.hasEnded) replayPositionMs = 0L
                    // shortcut: FCast sends no media ID; replace this heuristic if the protocol adds one.
                    if (
                        !isCurrentFcastPlaybackUpdate(
                            awaitingCurrentMediaUpdate,
                            hasEnded,
                            current.durationMs,
                            durationMs,
                            awaitingMediaStartPositionMs,
                            positionMs,
                            reportedPositionMs != null,
                        )
                    ) {
                        return
                    }
                    awaitingCurrentMediaUpdate = false
                    _state.value =
                        current.copy(
                            isPlaying = isPlaying,
                            isBuffering = false,
                            positionMs = positionMs,
                            durationMs = durationMs,
                            hasEnded = hasEnded,
                            mediaGeneration = mediaGeneration,
                        )
                }
            }

            OPCODE_VOLUME_UPDATE -> {
                val volume =
                    body?.let {
                        json
                            .parseToJsonElement(it)
                            .jsonObject["volume"]
                            ?.jsonPrimitive
                            ?.doubleOrNull
                    }
                if (volume != null) {
                    synchronized(mediaStateLock) {
                        _state.value = _state.value.copy(volume = volume.toFloat().coerceIn(0f, 1f))
                    }
                }
            }

            OPCODE_EVENT -> {
                synchronized(mediaStateLock) {
                    if (closed || stopRequested || !isCurrentFcastMediaItemEndEvent(body, currentMediaUrl)) return
                    val current = _state.value
                    if (current.hasEnded) return
                    replayPositionMs = 0L
                    _state.value =
                        current.copy(
                            isPlaying = false,
                            positionMs = current.durationMs.takeIf { it > 0L } ?: current.positionMs,
                            hasEnded = true,
                            mediaGeneration = mediaGeneration,
                        )
                }
            }

            OPCODE_PLAYBACK_ERROR -> {
                close()
            }

            OPCODE_PING -> {
                send(OPCODE_PONG)
            }
        }
    }

    private fun send(
        opcode: Int,
        body: String? = null,
    ) {
        if (closed) return
        val bytes = body?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
        require(bytes.size + 1 <= MAX_PACKET_SIZE)
        synchronized(writeLock) {
            output.write(
                ByteBuffer
                    .allocate(4)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(bytes.size + 1)
                    .array(),
            )
            output.write(opcode)
            output.write(bytes)
            output.flush()
        }
    }

    override fun close() {
        synchronized(mediaStateLock) {
            if (closed) return
            closed = true
            _state.value = CastSessionState(mediaGeneration = mediaGeneration)
        }
        heartbeatJob?.cancel()
        readerJob?.cancel()
        runCatching { socket.close() }
    }

    private companion object {
        const val PROTOCOL_VERSION = 3
        const val MAX_PACKET_SIZE = 32_000
        const val CONNECT_TIMEOUT_MS = 8_000
        const val READ_TIMEOUT_MS = 30_000
        const val HEARTBEAT_INTERVAL_MS = 10_000L
        const val PLAYBACK_STATE_IDLE = 0
        const val PLAYBACK_STATE_PLAYING = 1
        const val OPCODE_PLAY = 1
        const val OPCODE_PAUSE = 2
        const val OPCODE_RESUME = 3
        const val OPCODE_STOP = 4
        const val OPCODE_SEEK = 5
        const val OPCODE_PLAYBACK_UPDATE = 6
        const val OPCODE_VOLUME_UPDATE = 7
        const val OPCODE_SET_VOLUME = 8
        const val OPCODE_PLAYBACK_ERROR = 9
        const val OPCODE_VERSION = 11
        const val OPCODE_PING = 12
        const val OPCODE_PONG = 13
        const val OPCODE_INITIAL = 14
        const val OPCODE_SUBSCRIBE_EVENT = 17
        const val OPCODE_EVENT = 19
    }
}
