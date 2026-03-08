package com.metrolist.shared.discord

import com.metrolist.shared.network.hasHttpTransportFailureInCauseChain
import com.metrolist.shared.utils.Logger
import com.metrolist.shared.utils.MetrolistClock
import com.metrolist.shared.youtube.playback.PlaybackEventKind
import com.metrolist.shared.youtube.playback.PlaybackEventLog
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlin.concurrent.Volatile
import kotlin.time.Duration.Companion.milliseconds

sealed interface GatewayEvent {
    data object Ready : GatewayEvent

    data object Resumed : GatewayEvent

    data class Disconnected(
        val code: Int,
    ) : GatewayEvent

    data object RefreshToken : GatewayEvent

    data class Fatal(
        val code: Int,
    ) : GatewayEvent
}

class DiscordGateway(
    private val appId: String,
    private val tokenProvider: suspend () -> String,
    private val externalScope: CoroutineScope,
    private val httpClient: HttpClient,
    private val connectionAllowed: () -> Boolean = { true },
) {
    private val _events =
        MutableSharedFlow<GatewayEvent>(
            replay = 0,
            extraBufferCapacity = 64,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    val events: SharedFlow<GatewayEvent> = _events.asSharedFlow()

    @Volatile private var _currentSeq: Int = 0
    val currentSeq: Int get() = _currentSeq

    @Volatile private var _sessionId: String? = null
    val sessionId: String? get() = _sessionId

    private var session: DefaultClientWebSocketSession? = null

    @Volatile private var isOpen: Boolean = false

    @Volatile private var gatewayUrl: String = DEFAULT_GATEWAY_URL

    private val reconnectBackoff = DiscordReconnectBackoff()

    @Volatile private var heartbeatJob: Job? = null

    @Volatile private var connectionJob: Job? = null

    @Volatile private var lastAckAtMs: Long = 0L

    private val connectionGeneration = DiscordConnectionGeneration()
    private val sendMutex = Mutex()

    suspend fun connect(): Boolean {
        if (!connectionAllowed()) throw CancellationException("Discord gateway is disabled")
        val generation = connectionGeneration.next()
        val url = gatewayUrl
        heartbeatJob?.cancel()
        heartbeatJob = null

        val connectedDeferred = CompletableDeferred<Unit>()

        val connectionJob =
            externalScope.launch {
                try {
                    httpClient.webSocket(url, request = {
                        header("User-Agent", DiscordSuperProperties.USER_AGENT)
                    }) {
                        if (!connectionGeneration.isCurrent(generation) || !connectionAllowed()) {
                            connectedDeferred.completeExceptionally(CancellationException("Superseded Discord connection"))
                            close(CloseReason(4000, "superseded"))
                            return@webSocket
                        }
                        session = this
                        isOpen = true
                        lastAckAtMs = MetrolistClock.nowMs()
                        connectedDeferred.complete(Unit)

                        try {
                            for (frame in incoming) {
                                if (!connectionGeneration.isCurrent(generation)) break
                                if (frame is Frame.Text) {
                                    handleFrame(frame.readText())
                                }
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            logDiscordFailure("WebSocket incoming loop failed", e)
                            Logger.e(TAG, "WebSocket incoming loop error", e)
                        } finally {
                            val reason = closeReason.await()
                            handleClose(reason?.code?.toInt() ?: 1000, generation = generation)
                        }
                    }
                } catch (e: CancellationException) {
                    if (connectionGeneration.isCurrent(generation)) {
                        heartbeatJob?.cancel()
                        heartbeatJob = null
                        session = null
                        isOpen = false
                    }
                    throw e
                } catch (e: Throwable) {
                    logDiscordFailure("WebSocket connection failed", e)
                    Logger.e(TAG, "WebSocket connection failed", e)
                    if (!connectedDeferred.isCompleted) {
                        connectedDeferred.completeExceptionally(e)
                    }
                    handleClose(4000, generation = generation, force = true)
                }
            }
        connectionJob.invokeOnCompletion { error ->
            if (error != null) connectedDeferred.completeExceptionally(error)
        }
        this.connectionJob = connectionJob

        return try {
            val connected = withTimeoutOrNull(15000.milliseconds) { connectedDeferred.await() } != null
            if (!connected) {
                logDiscordFailure("WebSocket connection timed out", IllegalStateException("Handshake timed out"))
                connectionJob.cancelAndJoin()
                externalScope.launch { handleClose(4000, generation = generation, force = true) }
            }
            connected
        } catch (e: CancellationException) {
            connectionJob.cancel()
            throw e
        }
    }

    fun close(
        code: Int = 1000,
        reason: String? = null,
    ) {
        heartbeatJob?.cancel()
        heartbeatJob = null
        val generation = connectionGeneration.current()
        if (code == 1000) connectionGeneration.next()
        val activeSession = session
        if (activeSession == null) {
            isOpen = false
            return
        }
        externalScope.launch(start = CoroutineStart.UNDISPATCHED) { closeSession(activeSession, code, reason, generation) }
    }

    internal suspend fun closeAndAwait(
        code: Int = 1000,
        reason: String? = null,
        finalFrameJson: String? = null,
    ) {
        heartbeatJob?.cancel()
        heartbeatJob = null
        val generation = connectionGeneration.current()
        if (code == 1000) connectionGeneration.next()
        val activeSession = session
        if (activeSession == null) {
            isOpen = false
            return
        }
        closeSession(activeSession, code, reason, generation, finalFrameJson)
    }

    internal suspend fun suspendConnection(finalFrameJson: String? = null) {
        val generation = connectionGeneration.current()
        connectionGeneration.next()
        heartbeatJob?.cancel()
        heartbeatJob = null
        val activeSession = session
        val activeConnection = connectionJob
        connectionJob = null
        try {
            if (activeSession != null) closeSession(activeSession, 1000, null, generation, finalFrameJson)
        } finally {
            activeConnection?.cancel()
            session = null
            isOpen = false
        }
    }

    private suspend fun closeSession(
        activeSession: DefaultClientWebSocketSession,
        code: Int,
        reason: String?,
        generation: Long,
        finalFrameJson: String? = null,
    ) {
        sendMutex.withLock {
            if (finalFrameJson != null && isOpen) {
                try {
                    activeSession.send(finalFrameJson)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    logDiscordFailure("Failed to send final frame", error)
                    Logger.e(TAG, "Failed to send final frame", error)
                }
            }
            try {
                activeSession.close(CloseReason(code.toShort(), reason ?: ""))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                logDiscordFailure("Failed to close WebSocket", error)
                Logger.e(TAG, "Failed to close WebSocket", error)
                handleClose(code, generation = generation)
            } finally {
                if (code == 1000 && session === activeSession) {
                    session = null
                    isOpen = false
                }
            }
        }
    }

    fun send(
        frameJson: String,
        onSuccess: (() -> Unit)? = null,
        onFailure: (() -> Unit)? = null,
    ) {
        externalScope.launch(start = CoroutineStart.UNDISPATCHED) {
            if (sendAndAwait(frameJson)) onSuccess?.invoke() else onFailure?.invoke()
        }
    }

    internal suspend fun sendAndAwait(frameJson: String): Boolean =
        sendMutex.withLock {
            val activeSession = session
            if (activeSession == null || !isOpen || !connectionAllowed()) return@withLock false
            try {
                activeSession.send(frameJson)
                true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                logDiscordFailure("Failed to send WebSocket frame", error)
                Logger.e(TAG, "Failed to send frame", error)
                false
            }
        }

    fun identify(token: String) {
        val d =
            buildJsonObject {
                put("token", token)
                put("intents", 0)
                put(
                    "properties",
                    buildJsonObject {
                        put("os", platformOs)
                        put("browser", platformBrowser)
                        put("device", if (platformOs == "Android") appId else platformDevice)
                    },
                )
                put("compress", false)
            }
        send(wrapOp(OP_IDENTIFY, d))
    }

    fun resume(
        sessionId: String,
        seq: Int,
        token: String,
    ) {
        val d =
            buildJsonObject {
                put("token", token)
                put("session_id", sessionId)
                put("seq", seq)
            }
        send(wrapOp(OP_RESUME, d))
    }

    fun heartbeat(seq: Int) {
        val root =
            buildJsonObject {
                put("op", OP_HEARTBEAT)
                if (seq > 0) put("d", seq) else put("d", JsonNull)
            }
        send(root.toString())
    }

    private suspend fun handleFrame(text: String) {
        val json =
            try {
                Json.parseToJsonElement(text).jsonObject
            } catch (e: Throwable) {
                logDiscordFailure("WebSocket frame contained invalid JSON", e)
                Logger.e(TAG, "handleFrame: invalid JSON", e)
                return
            }

        val op = json["op"]?.jsonPrimitive?.int ?: -1
        val d = json["d"]
        val t = json["t"]?.jsonPrimitive?.contentOrNull
        val s = json["s"]?.jsonPrimitive?.intOrNull

        if (s != null && s > 0) _currentSeq = s

        when (op) {
            OP_HELLO -> {
                val interval = ((d as? JsonObject)?.get("heartbeat_interval") as? JsonPrimitive)?.longOrNull ?: DEFAULT_HEARTBEAT_MS
                Logger.d(TAG, "HELLO received, heartbeatInterval=${interval}ms")
                startHeartbeat(interval)
            }

            OP_HEARTBEAT_ACK -> {
                lastAckAtMs = MetrolistClock.nowMs()
            }

            OP_DISPATCH -> {
                when (t) {
                    "READY" -> {
                        val data = d as? JsonObject ?: buildJsonObject {}
                        _sessionId = (data["session_id"] as? JsonPrimitive)?.content
                        val resumeUrl = (data["resume_gateway_url"] as? JsonPrimitive)?.contentOrNull
                        if (resumeUrl != null) gatewayUrl = resumeUrl
                        _events.emit(GatewayEvent.Ready)
                    }

                    "RESUMED" -> {
                        _events.emit(GatewayEvent.Resumed)
                    }
                }
            }

            OP_INVALID_SESSION -> {
                val resumable = d?.jsonPrimitive?.booleanOrNull ?: false
                Logger.w(TAG, "INVALID_SESSION resumable=$resumable")
                if (!resumable) resetForFreshIdentify()
                close(4000, "invalid session")
            }

            OP_HEARTBEAT -> {
                heartbeat(_currentSeq)
            }

            OP_RECONNECT -> {
                Logger.w(TAG, "RECONNECT requested")
                close(4000, "reconnect requested")
            }
        }
    }

    private suspend fun handleClose(
        code: Int,
        generation: Long,
        force: Boolean = false,
    ) {
        if (!connectionGeneration.isCurrent(generation)) return
        if (!force && !isOpen && session == null) return
        isOpen = false
        heartbeatJob?.cancel()
        heartbeatJob = null
        session = null

        _events.emit(GatewayEvent.Disconnected(code))

        val action = DiscordReconnectStrategy.decide(code, _sessionId != null, _currentSeq, _sessionId)

        when (action) {
            is ReconnectAction.SurfaceFatal -> {
                resetForFreshIdentify()
                _events.emit(GatewayEvent.Fatal(code))
            }

            is ReconnectAction.RefreshAndReIdentify -> {
                _events.emit(GatewayEvent.RefreshToken)
            }

            is ReconnectAction.Resume, is ReconnectAction.ReIdentify -> {
                delay(reconnectBackoff.nextDelayMs(rateLimited = code == 4008).milliseconds)
                if (connectionGeneration.isCurrent(generation) && connectionAllowed()) performReconnect(action)
            }
        }
    }

    private suspend fun performReconnect(action: ReconnectAction) {
        try {
            if (!connect()) return
            val token = tokenProvider()
            when (action) {
                is ReconnectAction.Resume -> {
                    resume(action.sessionId, action.seq, token)
                }

                is ReconnectAction.ReIdentify -> {
                    identify(token)
                }

                else -> {}
            }
        } catch (e: Throwable) {
            Logger.e(TAG, "performReconnect failed", e)
        }
    }

    private fun startHeartbeat(intervalMs: Long) {
        heartbeatJob?.cancel()
        val jittered = (intervalMs * 0.9).toLong()
        lastAckAtMs = MetrolistClock.nowMs()
        heartbeatJob =
            externalScope.launch {
                while (isActive && isOpen) {
                    delay(jittered.milliseconds)
                    if (!isActive || !isOpen) break
                    if (MetrolistClock.nowMs() - lastAckAtMs > intervalMs + 15000L) {
                        Logger.w(TAG, "Heartbeat timeout")
                        close(4000, "heartbeat timeout")
                        break
                    }
                    heartbeat(_currentSeq)
                }
            }
    }

    private fun wrapOp(
        op: Int,
        d: JsonObject,
    ): String =
        buildJsonObject {
            put("op", op)
            put("d", d)
        }.toString()

    fun markReady() {
        reconnectBackoff.reset()
    }

    fun resetForFreshIdentify() {
        _sessionId = null
        _currentSeq = 0
        reconnectBackoff.reset()
        gatewayUrl = DEFAULT_GATEWAY_URL
    }

    companion object {
        private const val TAG = "DiscordSvc"
        private const val DEFAULT_GATEWAY_URL = "wss://gateway.discord.gg/?v=10&encoding=json"
        private const val DEFAULT_HEARTBEAT_MS = 41250L

        private const val OP_DISPATCH = 0
        private const val OP_HEARTBEAT = 1
        private const val OP_IDENTIFY = 2
        private const val OP_PRESENCE_UPDATE = 3
        private const val OP_RESUME = 6
        private const val OP_RECONNECT = 7
        private const val OP_INVALID_SESSION = 9
        private const val OP_HELLO = 10
        private const val OP_HEARTBEAT_ACK = 11
    }
}

private fun logDiscordFailure(
    message: String,
    error: Throwable,
) {
    if (error.hasHttpTransportFailureInCauseChain()) return
    PlaybackEventLog.error(PlaybackEventKind.SYNC, "DiscordGateway", message, error)
}

internal class DiscordConnectionGeneration {
    private val value = atomic(0L)

    fun next(): Long = value.incrementAndGet()

    fun current(): Long = value.value

    fun isCurrent(generation: Long): Boolean = generation == value.value
}
