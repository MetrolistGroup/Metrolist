package com.metrolist.music.discord

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.random.Random

/** Thrown when an in-flight connect is superseded by a newer connect/close before its handshake finished. */
class GatewaySupersededException : IOException("connection superseded")

class GatewayConnectionException(
    val statusCode: Int,
    val retryAfter: String?,
    cause: Throwable,
) : IOException("gateway connection failed (status=$statusCode): ${cause.message}", cause) {
    val retryReason: String
        get() = if (retryAfter != null) {
            "${cause?.message ?: "failure"};retry_after=$retryAfter"
        } else {
            cause?.message ?: "failure"
        }
}

sealed interface GatewayEvent {
    data class Hello(val connectionId: Long, val heartbeatIntervalMs: Long) : GatewayEvent
    data class Ready(
        val connectionId: Long,
        val sessionId: String,
        val resumeGatewayUrl: String?,
    ) : GatewayEvent
    data class Resumed(val connectionId: Long, val sessionId: String) : GatewayEvent
    data class HeartbeatAck(val connectionId: Long, val lastSeq: Int?) : GatewayEvent
    data class InvalidSession(val connectionId: Long, val resumable: Boolean) : GatewayEvent
    data class Disconnected(
        val connectionId: Long,
        val code: Int,
        val reason: String,
        val remote: Boolean,
    ) : GatewayEvent
    data class TextDispatch(
        val connectionId: Long,
        val op: Int,
        val t: String?,
        val d: JSONObject,
    ) : GatewayEvent
}

class DiscordGateway(
    private val appId: String,
    private val externalScope: CoroutineScope,
    private val webSocketFactory: WebSocket.Factory? = null,
) {
    private val _events = MutableSharedFlow<GatewayEvent>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<GatewayEvent> = _events.asSharedFlow()

    @Volatile
    private var _currentSeq: Int = 0
    val currentSeq: Int get() = _currentSeq

    @Volatile
    private var _sessionId: String? = null
    val sessionId: String? get() = _sessionId

    @Volatile
    private var webSocket: WebSocket? = null

    @Volatile
    private var isOpen: Boolean = false

    private val connectionLock = Any()
    private val webSocketIdCounter = AtomicLong(0L)

    @Volatile
    private var activeWebSocketId: Long = 0L

    @Volatile
    private var gatewayUrl: String = DEFAULT_GATEWAY_URL

    @Volatile
    private var heartbeatJob: Job? = null

    private val lastAckAtMs = AtomicLong(0L)

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(0, TimeUnit.MILLISECONDS)
        .build()

    suspend fun connect(onConnectionCreated: (Long) -> Unit = {}): Long {
        val myId = synchronized(connectionLock) {
            webSocketIdCounter.incrementAndGet().also { activeWebSocketId = it }
        }
        try {
            onConnectionCreated(myId)
        } catch (e: Throwable) {
            invalidateAttempt(myId, null)
            throw e
        }
        val openDeferred = CompletableDeferred<Unit>()
        val request = Request.Builder().url(gatewayUrl).build()
        val listener = createListener(openDeferred, myId)
        val ws = try {
            (webSocketFactory ?: httpClient).newWebSocket(request, listener)
        } catch (e: Throwable) {
            invalidateAttempt(myId, null)
            throw GatewayConnectionException(4000, null, e)
        }
        val accepted = synchronized(connectionLock) {
            if (myId == activeWebSocketId) {
                webSocket = ws
                true
            } else {
                false
            }
        }
        if (!accepted) {
            runCatching { ws.cancel() }
            throw GatewaySupersededException()
        }
        try {
            withTimeout(HANDSHAKE_TIMEOUT_MS) { openDeferred.await() }
            Timber.tag(TAG).i("connect: WS opened (id=%d), gatewayUrl=%s", myId, gatewayUrl)
            return myId
        } catch (e: TimeoutCancellationException) {
            Timber.tag(TAG).e("connect: handshake timed out after %dms (id=%d)", HANDSHAKE_TIMEOUT_MS, myId)
            runCatching { ws.cancel() }
            invalidateAttempt(myId, ws)
            throw GatewayConnectionException(4000, null, e)
        } catch (e: CancellationException) {
            runCatching { ws.cancel() }
            invalidateAttempt(myId, ws)
            throw e
        } catch (e: GatewaySupersededException) {
            runCatching { ws.cancel() }
            throw e
        } catch (e: GatewayConnectionException) {
            Timber.tag(TAG).e(e, "connect: failed to open WS (id=%d)", myId)
            runCatching { ws.cancel() }
            invalidateAttempt(myId, ws)
            throw e
        } catch (e: Throwable) {
            Timber.tag(TAG).e(e, "connect: failed to open WS (id=%d)", myId)
            runCatching { ws.cancel() }
            invalidateAttempt(myId, ws)
            throw GatewayConnectionException(4000, null, e)
        }
    }

    fun close(code: Int = 1000, reason: String? = null) {
        Timber.tag(TAG).i("close: code=%d, reason=%s", code, reason ?: "")
        val ws = synchronized(connectionLock) {
            heartbeatJob?.cancel()
            heartbeatJob = null
            val current = webSocket
            webSocket = null
            isOpen = false
            activeWebSocketId = webSocketIdCounter.incrementAndGet()
            current
        }
        if (ws != null) {
            runCatching {
                if (reason != null) ws.close(code, reason) else ws.close(code, null)
            }
        }
    }

    private fun send(frameJson: String) {
        val ws = synchronized(connectionLock) {
            if (isOpen) webSocket else null
        }
        if (ws == null) {
            Timber.tag(TAG).w("send: WebSocket not open")
            throw IllegalStateException("DiscordGateway: WebSocket is not open")
        }
        send(frameJson, ws)
    }

    private fun send(frameJson: String, forConnectionId: Long) {
        val ws = synchronized(connectionLock) {
            if (forConnectionId != activeWebSocketId || !isOpen) {
                null
            } else {
                webSocket
            }
        }
        if (ws == null) throw GatewaySupersededException()
        send(frameJson, ws)
    }

    private fun send(frameJson: String, ws: WebSocket) {
        Timber.tag(TAG).v("send: frame (length=%d)", frameJson.length)
        val ok = ws.send(frameJson)
        if (!ok) {
            Timber.tag(TAG).w("send: WebSocket send returned false (queue full or closing)")
            throw IllegalStateException("DiscordGateway: WebSocket send returned false (queue full or closing)")
        }
    }

    suspend fun identify(token: String, connectionId: Long) {
        val frame = buildIdentifyFrame(token)
        send(frame, connectionId)
        Timber.tag(TAG).i("identify: IDENTIFY sent (token length=%d)", token.length)
    }

    fun presenceUpdate(presenceJson: String) {
        send(presenceJson)
    }

    suspend fun resume(sessionId: String, seq: Int, token: String, connectionId: Long) {
        val frame = buildResumeFrame(sessionId, seq, token)
        send(frame, connectionId)
        Timber.tag(TAG).i("resume: RESUME sent (sessionId prefix=%s, seq=%d)", sessionId.take(8), seq)
    }

    private fun heartbeat(seq: Int, connectionId: Long) {
        Timber.tag(TAG).d("heartbeat: sending seq=%d", seq)
        val frame = buildHeartbeatFrame(seq)
        send(frame, connectionId)
    }

    fun invalidateSession() {
        synchronized(connectionLock) {
            _sessionId = null
            _currentSeq = 0
            gatewayUrl = DEFAULT_GATEWAY_URL
        }
    }

    fun closeHttp() {
        runCatching {
            httpClient.dispatcher.executorService.shutdown()
            httpClient.connectionPool.evictAll()
        }
    }

    private fun createListener(openDeferred: CompletableDeferred<Unit>, wsId: Long): WebSocketListener =
        object : WebSocketListener() {
            private val opened = AtomicBoolean(false)

            override fun onOpen(webSocket: WebSocket, response: Response) {
                val accepted = synchronized(connectionLock) {
                    if (wsId != activeWebSocketId) {
                        false
                    } else {
                        this@DiscordGateway.webSocket = webSocket
                        isOpen = true
                        lastAckAtMs.set(System.currentTimeMillis())
                        opened.set(true)
                        true
                    }
                }
                if (!accepted) {
                    Timber.tag(TAG).i(
                        "onOpen: stale WS (wsId=%d, activeId=%d), cancelling superseded socket",
                        wsId, activeWebSocketId,
                    )
                    runCatching { webSocket.cancel() }
                    openDeferred.completeExceptionally(GatewaySupersededException())
                    return
                }
                Timber.tag(TAG).i("onOpen: response.code=%d, wsId=%d", response.code, wsId)
                openDeferred.complete(Unit)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (wsId != activeWebSocketId) return
                externalScope.launch {
                    try {
                        handleFrame(text, wsId)
                    } catch (e: Throwable) {
                        Timber.tag(TAG).e(e, "onMessage: failed to handle text frame")
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                Timber.tag(TAG).w("onMessage: binary frame received (%d bytes), ignoring", bytes.size)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Timber.tag(TAG).i("onClosing: code=%d, reason=%s, wsId=%d", code, reason, wsId)
                runCatching { webSocket.close(1000, null) }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Timber.tag(TAG).i("onClosed: code=%d, reason=%s, wsId=%d", code, reason, wsId)
                if (!opened.get()) {
                    val exception = if (!isActiveConnection(wsId)) {
                        GatewaySupersededException()
                    } else {
                        GatewayConnectionException(code, null, IOException(reason.ifEmpty { "closed before open" }))
                    }
                    openDeferred.completeExceptionally(exception)
                    return
                }
                externalScope.launch {
                    handleClose(code, reason, remote = true, closedWebSocketId = wsId)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!isActiveConnection(wsId)) {
                    openDeferred.completeExceptionally(GatewaySupersededException())
                    return
                }
                if (!opened.get()) {
                    openDeferred.completeExceptionally(
                        GatewayConnectionException(
                            statusCode = response?.code ?: 4000,
                            retryAfter = response?.header("Retry-After"),
                            cause = t,
                        ),
                    )
                    return
                }
                Timber.tag(TAG).e(t, "onFailure after open: response=%s, wsId=%d", response?.code, wsId)
                externalScope.launch {
                    val code = response?.code ?: 4000
                    val retryAfter = response?.header("Retry-After")
                    val reason = if (retryAfter != null) {
                        "${t.message ?: "failure"};retry_after=$retryAfter"
                    } else {
                        t.message ?: "failure"
                    }
                    handleClose(code, reason, remote = false, closedWebSocketId = wsId)
                }
            }
        }

    private suspend fun handleFrame(text: String, connectionId: Long) {
        val json = try {
            JSONObject(text)
        } catch (e: Throwable) {
            Timber.tag(TAG).e(e, "handleFrame: invalid JSON")
            return
        }
        if (!isOpenConnection(connectionId)) return

        val op = json.optInt("op", -1)
        val d: JSONObject? = json.optJSONObject("d")
        val t: String? = if (json.has("t")) json.optString("t") else null

        Timber.tag(TAG).v("handleFrame: op=%d t=%s seq=%d",
            op, t ?: "", json.optInt("s", 0))

        if (json.has("s") && !json.isNull("s")) {
            val seq = json.optInt("s", 0)
            if (seq > 0) {
                synchronized(connectionLock) {
                    if (connectionId != activeWebSocketId || !isOpen) return
                    _currentSeq = seq
                }
            }
        }

        when (op) {
            HELLO -> {
                val interval = d?.optLong("heartbeat_interval", DEFAULT_HEARTBEAT_MS)
                    ?: DEFAULT_HEARTBEAT_MS
                if (!isOpenConnection(connectionId)) return
                Timber.tag(TAG).d("handleFrame: HELLO received, heartbeatInterval=%dms", interval)
                startHeartbeat(interval, connectionId)
                if (!isOpenConnection(connectionId)) return
                _events.emit(GatewayEvent.Hello(connectionId, interval))
            }
            HEARTBEAT_ACK -> {
                synchronized(connectionLock) {
                    if (connectionId != activeWebSocketId || !isOpen) return
                    lastAckAtMs.set(System.currentTimeMillis())
                }
                _events.emit(GatewayEvent.HeartbeatAck(connectionId, _currentSeq))
            }
            DISPATCH -> {
                when (t) {
                    "READY" -> {
                        val data = d ?: JSONObject()
                        val sessionId = data.optString("session_id", "")
                        val resumeUrl: String? = data.optString("resume_gateway_url", "")
                            .takeIf { it.isNotEmpty() }
                        synchronized(connectionLock) {
                            if (connectionId != activeWebSocketId || !isOpen) return
                            _sessionId = sessionId
                            if (resumeUrl != null) gatewayUrl = resumeUrl
                        }
                        Timber.tag(TAG).d(
                            "handleFrame: READY parsed (sessionId prefix=%s, resumeUrl=%s)",
                            sessionId.take(8), resumeUrl?.take(60),
                        )
                        if (resumeUrl != null) {
                            Timber.tag(TAG).i("setGatewayUrl: %s", resumeUrl)
                        }
                        if (!isOpenConnection(connectionId)) return
                        _events.emit(GatewayEvent.Ready(connectionId, sessionId, resumeUrl))
                    }
                    "RESUMED" -> {
                        if (!isOpenConnection(connectionId)) return
                        Timber.tag(TAG).d("handleFrame: RESUMED parsed (sessionId prefix=%s)", _sessionId?.take(8))
                        _events.emit(GatewayEvent.Resumed(connectionId, _sessionId.orEmpty()))
                    }
                    else -> {
                        if (!isOpenConnection(connectionId)) return
                        _events.emit(GatewayEvent.TextDispatch(connectionId, op, t, d ?: JSONObject()))
                    }
                }
            }
            INVALID_SESSION -> {
                val resumable = (json.opt("d") as? Boolean) ?: false
                Timber.tag(TAG).w("INVALID_SESSION: resumable=%s", resumable)
                synchronized(connectionLock) {
                    if (connectionId != activeWebSocketId || !isOpen) return
                    if (!resumable) {
                        _sessionId = null
                    }
                }
                _events.emit(GatewayEvent.InvalidSession(connectionId, resumable))
                close(connectionId, 4000, "invalid session")
            }
            HEARTBEAT -> {
                if (!isOpenConnection(connectionId)) return
                heartbeat(_currentSeq, connectionId)
            }
            RECONNECT -> {
                Timber.tag(TAG).w("RECONNECT requested by server, closing 4000")
                close(connectionId, 4000, "reconnect requested")
            }
            else -> {
                if (!isOpenConnection(connectionId)) return
                _events.emit(GatewayEvent.TextDispatch(connectionId, op, t, d ?: JSONObject()))
            }
        }
    }

    private suspend fun handleClose(code: Int, reason: String, remote: Boolean, closedWebSocketId: Long) {
        val accepted = synchronized(connectionLock) {
            if (closedWebSocketId != activeWebSocketId || (!isOpen && webSocket == null)) {
                false
            } else {
                isOpen = false
                heartbeatJob?.cancel()
                heartbeatJob = null
                webSocket = null
                if (code == 1000 && remote) {
                    _sessionId = null
                    _currentSeq = 0
                }
                true
            }
        }
        if (!accepted) {
            Timber.tag(TAG).i(
                "handleClose: ignoring stale WS close (closedId=%d, activeId=%d, code=%d)",
                closedWebSocketId, activeWebSocketId, code,
            )
            return
        }

        if (code == 1000 && remote) {
            Timber.tag(TAG).d("handleClose: clean remote close (code=1000), resetting session")
        }
        _events.emit(GatewayEvent.Disconnected(closedWebSocketId, code, reason, remote))
    }

    private fun startHeartbeat(intervalMs: Long, connectionId: Long) {
        val jittered = applyJitter(intervalMs, JITTER_RATIO)
        Timber.tag(TAG).i("startHeartbeat: interval=%dms, jittered=%dms", intervalMs, jittered)
        val newJob = externalScope.launch(start = CoroutineStart.LAZY) {
            // Stays 0 until the first heartbeat is actually sent: the liveness check below
            // compares against the last heartbeat we sent, and on the first tick we haven't
            // sent one yet, so there is no ACK to wait for.
            var lastSentAt = 0L
            while (isActive && isOpen && connectionId == activeWebSocketId) {
                delay(jittered)
                if (!isActive || !isOpen || connectionId != activeWebSocketId) break
                val lastAck = lastAckAtMs.get()
                if (lastSentAt > 0L && lastAck < lastSentAt) {
                    Timber.tag(TAG).w("heartbeat: no ACK in %d ms, closing with 4000", jittered)
                    close(connectionId, 4000, "heartbeat timeout")
                    break
                }
                lastSentAt = System.currentTimeMillis()
                runCatching { heartbeat(_currentSeq, connectionId) }
                    .onFailure { Timber.tag(TAG).w(it, "heartbeat: send failed") }
            }
        }
        synchronized(connectionLock) {
            if (connectionId != activeWebSocketId || !isOpen) {
                newJob.cancel()
                return
            }
            heartbeatJob?.cancel()
            lastAckAtMs.set(System.currentTimeMillis())
            heartbeatJob = newJob
            newJob.start()
        }
    }

    private fun close(connectionId: Long, code: Int, reason: String) {
        val ws = synchronized(connectionLock) {
            if (connectionId == activeWebSocketId) webSocket else null
        } ?: return
        runCatching { ws.close(code, reason) }
    }

    private fun invalidateAttempt(connectionId: Long, ws: WebSocket?) {
        synchronized(connectionLock) {
            if (connectionId != activeWebSocketId) return
            if (ws == null || webSocket === ws) {
                webSocket = null
            }
            heartbeatJob?.cancel()
            heartbeatJob = null
            isOpen = false
            activeWebSocketId = webSocketIdCounter.incrementAndGet()
        }
    }

    private fun isActiveConnection(connectionId: Long): Boolean = synchronized(connectionLock) {
        connectionId == activeWebSocketId
    }

    private fun isOpenConnection(connectionId: Long): Boolean = synchronized(connectionLock) {
        connectionId == activeWebSocketId && isOpen
    }

    private fun buildIdentifyFrame(token: String): String {
        val d = JSONObject()
        d.put("token", token)
        d.put("intents", 0)
        val props = JSONObject()
        props.put("os", "android")
        props.put("browser", "Discord Android")
        props.put("device", appId)
        d.put("properties", props)
        d.put("compress", false)
        return wrapOp(IDENTIFY, d)
    }

    private fun buildResumeFrame(sessionId: String, seq: Int, token: String): String {
        val d = JSONObject()
        d.put("token", token)
        d.put("session_id", sessionId)
        d.put("seq", seq)
        return wrapOp(RESUME, d)
    }

    private fun buildHeartbeatFrame(seq: Int): String {
        val root = JSONObject()
        root.put("op", HEARTBEAT)
        if (seq > 0) {
            root.put("d", seq)
        } else {
            root.put("d", JSONObject.NULL)
        }
        return root.toString()
    }

    private fun wrapOp(op: Int, d: JSONObject): String {
        val root = JSONObject()
        root.put("op", op)
        root.put("d", d)
        return root.toString()
    }

    private fun applyJitter(intervalMs: Long, ratio: Double): Long {
        if (intervalMs <= 0L) return intervalMs
        val delta = (intervalMs * ratio).toLong()
        if (delta <= 0L) return intervalMs
        val offset = abs(Random.nextLong(delta + 1))
        val sign = if (Random.nextBoolean()) -1L else 1L
        return intervalMs + sign * offset
    }

    companion object {
        private const val TAG = "DiscordSvc"

        private const val DEFAULT_GATEWAY_URL = "wss://gateway.discord.gg/?v=10&encoding=json"
        private const val DEFAULT_HEARTBEAT_MS = 41250L
        private const val JITTER_RATIO = 0.05
        private const val HANDSHAKE_TIMEOUT_MS = 20_000L

        private const val DISPATCH = 0
        private const val HEARTBEAT = 1
        private const val IDENTIFY = 2
        private const val PRESENCE_UPDATE = 3
        private const val VOICE_STATE = 4
        private const val RESUME = 6
        private const val RECONNECT = 7
        private const val INVALID_SESSION = 9
        private const val HELLO = 10
        private const val HEARTBEAT_ACK = 11
    }
}
