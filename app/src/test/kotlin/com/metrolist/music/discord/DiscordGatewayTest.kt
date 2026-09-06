package com.metrolist.music.discord

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.yield
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class DiscordGatewayTest {

    private lateinit var scope: CoroutineScope
    private lateinit var webSocketFactory: FakeWebSocketFactory
    private lateinit var gateway: DiscordGateway

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        webSocketFactory = FakeWebSocketFactory()
        gateway = DiscordGateway(
            appId = "test-app",
            externalScope = scope,
            webSocketFactory = webSocketFactory,
        )
    }

    @After
    fun tearDown() {
        gateway.closeHttp()
        scope.cancel()
    }

    @Test
    fun identifyAndResume_rejectSupersededConnection() = runBlocking {
        val firstConnectionId = connectGateway()
        val secondConnectionId = connectGateway()

        try {
            gateway.identify("Bearer stale", firstConnectionId)
            fail("Expected the superseded connection to reject Identify")
        } catch (_: GatewaySupersededException) {
            // Expected.
        }

        gateway.resume(
            sessionId = "session-abc",
            seq = 42,
            token = "Bearer current",
            connectionId = secondConnectionId,
        )

        assertTrue(webSocketFactory.connection(0).webSocket.sentTextFrames.isEmpty())
        val resumeFrame = JSONObject(webSocketFactory.connection(1).webSocket.sentTextFrames.single())
        assertEquals(6, resumeFrame.getInt("op"))
        assertEquals("session-abc", resumeFrame.getJSONObject("d").getString("session_id"))
        assertEquals(42, resumeFrame.getJSONObject("d").getInt("seq"))
    }

    @Test
    fun readyFromSupersededConnection_doesNotMutateSession() = runBlocking {
        connectGateway()
        val activeConnectionId = connectGateway()
        val readyEvents = mutableListOf<GatewayEvent.Ready>()
        val collection = launch(start = CoroutineStart.UNDISPATCHED) {
            gateway.events.filterIsInstance<GatewayEvent.Ready>().collect {
                readyEvents += it
            }
        }

        webSocketFactory.message(
            index = 0,
            text = readyFrame("stale-session", "wss://stale.example"),
        )
        yield()
        assertNull(gateway.sessionId)
        assertTrue(readyEvents.isEmpty())

        webSocketFactory.message(
            index = 1,
            text = readyFrame("active-session", "wss://active.example"),
        )
        yield()

        assertEquals("active-session", gateway.sessionId)
        assertEquals(
            listOf(
                GatewayEvent.Ready(
                    connectionId = activeConnectionId,
                    sessionId = "active-session",
                    resumeGatewayUrl = "wss://active.example",
                ),
            ),
            readyEvents,
        )
        collection.cancel()
    }

    @Test
    fun stalePreOpenFailure_isReportedAsSuperseded() = runBlocking {
        supervisorScope {
            val firstConnect = async(start = CoroutineStart.UNDISPATCHED) { gateway.connect() }
            val secondConnect = async(start = CoroutineStart.UNDISPATCHED) { gateway.connect() }

            webSocketFactory.fail(
                index = 0,
                throwable = IOException("old socket failed"),
            )
            try {
                firstConnect.await()
                fail("Expected the stale attempt to be superseded")
            } catch (_: GatewaySupersededException) {
                // Expected.
            }

            webSocketFactory.open(1)
            assertEquals(2L, secondConnect.await())
        }
    }

    @Test
    fun activePreOpenFailure_preservesRateLimitMetadata() = runBlocking {
        supervisorScope {
            val connect = async(start = CoroutineStart.UNDISPATCHED) { gateway.connect() }

            webSocketFactory.fail(
                index = 0,
                throwable = IOException("rate limited"),
                statusCode = 429,
                retryAfter = "75.5",
            )

            try {
                connect.await()
                fail("Expected the connection attempt to fail")
            } catch (e: GatewayConnectionException) {
                assertEquals(429, e.statusCode)
                assertEquals("75.5", e.retryAfter)
                assertTrue(e.retryReason.contains("retry_after=75.5"))
            }
        }
    }

    @Test
    fun activeUnexpectedClose_emitsSingleDisconnectedEvent() = runBlocking {
        val connectionId = connectGateway()
        val disconnected = mutableListOf<GatewayEvent.Disconnected>()
        val collection = launch(start = CoroutineStart.UNDISPATCHED) {
            gateway.events.filterIsInstance<GatewayEvent.Disconnected>().collect {
                disconnected += it
            }
        }

        webSocketFactory.closeFromServer(0, 4000, "gateway restart")
        webSocketFactory.closeFromServer(0, 4000, "duplicate callback")
        yield()

        assertEquals(
            listOf(
                GatewayEvent.Disconnected(
                    connectionId = connectionId,
                    code = 4000,
                    reason = "gateway restart",
                    remote = true,
                ),
            ),
            disconnected,
        )
        assertEquals(1, webSocketFactory.connectionCount)
        collection.cancel()
    }

    @Test
    fun postOpenFailureFromSupersededConnection_doesNotEmitDisconnected() = runBlocking {
        connectGateway()
        val activeConnectionId = connectGateway()
        val disconnected = mutableListOf<GatewayEvent.Disconnected>()
        val collection = launch(start = CoroutineStart.UNDISPATCHED) {
            gateway.events.filterIsInstance<GatewayEvent.Disconnected>().collect {
                disconnected += it
            }
        }

        webSocketFactory.fail(
            index = 0,
            throwable = IOException("old socket failed"),
        )
        yield()
        assertTrue(disconnected.isEmpty())

        webSocketFactory.closeFromServer(1, 4000, "active socket failed")
        yield()
        assertEquals(
            listOf(
                GatewayEvent.Disconnected(
                    connectionId = activeConnectionId,
                    code = 4000,
                    reason = "active socket failed",
                    remote = true,
                ),
            ),
            disconnected,
        )
        collection.cancel()
    }

    @Test
    fun cleanRemoteClose_resetsSessionAndEmitsDisconnected() = runBlocking {
        val connectionId = connectGateway()
        webSocketFactory.message(
            index = 0,
            text = readyFrame("session-abc", "wss://resume.example"),
        )
        val disconnected = mutableListOf<GatewayEvent.Disconnected>()
        val collection = launch(start = CoroutineStart.UNDISPATCHED) {
            gateway.events.filterIsInstance<GatewayEvent.Disconnected>().collect {
                disconnected += it
            }
        }

        webSocketFactory.closeFromServer(0, 1000, "normal closure")
        yield()

        assertNull(gateway.sessionId)
        assertEquals(0, gateway.currentSeq)
        assertEquals(
            listOf(
                GatewayEvent.Disconnected(
                    connectionId = connectionId,
                    code = 1000,
                    reason = "normal closure",
                    remote = true,
                ),
            ),
            disconnected,
        )
        assertEquals(1, webSocketFactory.connectionCount)
        collection.cancel()
    }

    @Test
    fun intentionalClose_invalidatesCallbackWithoutEmittingDisconnected() = runBlocking {
        connectGateway()
        val disconnected = mutableListOf<GatewayEvent.Disconnected>()
        val collection = launch(start = CoroutineStart.UNDISPATCHED) {
            gateway.events.filterIsInstance<GatewayEvent.Disconnected>().collect {
                disconnected += it
            }
        }

        gateway.close(1000, "manager disconnect")
        webSocketFactory.closeFromServer(0, 1000, "manager disconnect")
        yield()

        assertTrue(webSocketFactory.connection(0).webSocket.closeRequested)
        assertTrue(disconnected.isEmpty())
        collection.cancel()
    }

    private suspend fun connectGateway(): Long = coroutineScope {
        val connectionIndex = webSocketFactory.connectionCount
        val connect = async(start = CoroutineStart.UNDISPATCHED) { gateway.connect() }
        webSocketFactory.open(connectionIndex)
        connect.await()
    }

    private fun readyFrame(sessionId: String, resumeUrl: String): String = JSONObject()
        .put("op", 0)
        .put("s", 1)
        .put("t", "READY")
        .put(
            "d",
            JSONObject()
                .put("session_id", sessionId)
                .put("resume_gateway_url", resumeUrl),
        )
        .toString()

    private class FakeWebSocketFactory : WebSocket.Factory {
        private val connections = mutableListOf<Connection>()

        val connectionCount: Int
            get() = connections.size

        override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
            val webSocket = FakeWebSocket(request)
            connections += Connection(
                webSocket = webSocket,
                listener = listener,
            )
            return webSocket
        }

        fun connection(index: Int): Connection = connections[index]

        fun open(index: Int) {
            val connection = connection(index)
            connection.listener.onOpen(
                connection.webSocket,
                response(connection.webSocket.request()),
            )
        }

        fun message(index: Int, text: String) {
            val connection = connection(index)
            connection.listener.onMessage(connection.webSocket, text)
        }

        fun fail(
            index: Int,
            throwable: Throwable,
            statusCode: Int? = null,
            retryAfter: String? = null,
        ) {
            val connection = connection(index)
            val response = statusCode?.let {
                response(connection.webSocket.request(), it, retryAfter)
            }
            connection.listener.onFailure(connection.webSocket, throwable, response)
        }

        fun closeFromServer(index: Int, code: Int, reason: String) {
            val connection = connection(index)
            connection.listener.onClosed(connection.webSocket, code, reason)
        }

        private fun response(
            request: Request,
            code: Int = 101,
            retryAfter: String? = null,
        ): Response = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code == 101) "Switching Protocols" else "Failure")
            .apply {
                if (retryAfter != null) header("Retry-After", retryAfter)
            }
            .build()

        data class Connection(
            val webSocket: FakeWebSocket,
            val listener: WebSocketListener,
        )
    }

    private class FakeWebSocket(
        private val request: Request,
    ) : WebSocket {
        val sentTextFrames = mutableListOf<String>()
        var closeRequested: Boolean = false
            private set

        override fun request(): Request = request

        override fun queueSize(): Long = 0L

        override fun send(text: String): Boolean {
            sentTextFrames += text
            return true
        }

        override fun send(bytes: ByteString): Boolean = true

        override fun close(code: Int, reason: String?): Boolean {
            closeRequested = true
            return true
        }

        override fun cancel() = Unit
    }
}
