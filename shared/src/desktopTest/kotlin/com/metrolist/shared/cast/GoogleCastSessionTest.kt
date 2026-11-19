package com.metrolist.shared.cast

import com.metrolist.shared.youtube.playback.PlaybackResource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import javax.net.ssl.SSLSocketFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GoogleCastSessionTest {
    @Test
    fun oldLoadErrorsDoNotDisconnectReplacementMedia() =
        runBlocking {
            for (error in listOf("LOAD_FAILED", "LOAD_CANCELLED")) {
                ReceiverFixture().use { fixture ->
                    val releaseOldError = CompletableDeferred<Unit>()
                    val receiver =
                        launch(Dispatchers.IO) {
                            val first = fixture.readLoad()
                            fixture.respond(first, "MEDIA_STATUS", sessionId = 1)
                            val second = fixture.readLoad()
                            fixture.respond(second, "MEDIA_STATUS", sessionId = 2)
                            releaseOldError.await()
                            fixture.respond(first, error)
                            fixture.respond(second, "MEDIA_STATUS", sessionId = 2, position = 4)
                        }
                    withTimeout(5_000) {
                        fixture.session.load("http://example.test/one", media, 0, true, 1f, 1)
                        fixture.session.load("http://example.test/two", media, 0, true, 1f, 2)
                        releaseOldError.complete(Unit)
                        val state = fixture.session.state.first { !it.connected || it.positionMs == 4_000L }
                        assertTrue(state.connected, "A delayed $error for the first request must not close the second")
                        assertEquals(2L, state.mediaGeneration)
                        receiver.join()
                    }
                }
            }
        }

    @Test
    fun lateFailureOfCurrentLoadStillDisconnects() =
        runBlocking {
            for (error in listOf("LOAD_FAILED", "LOAD_CANCELLED")) {
                ReceiverFixture().use { fixture ->
                    val releaseError = CompletableDeferred<Unit>()
                    val receiver =
                        launch(Dispatchers.IO) {
                            val request = fixture.readLoad()
                            fixture.respond(request, "MEDIA_STATUS")
                            releaseError.await()
                            fixture.respond(request, error)
                        }
                    withTimeout(5_000) {
                        fixture.session.load("http://example.test/audio", media, 0, true, 1f, 1)
                        assertTrue(fixture.session.state.value.connected)
                        releaseError.complete(Unit)
                        fixture.session.state.first { !it.connected }
                        receiver.join()
                    }
                }
            }
        }

    @Test
    fun currentLoadFailuresAndCancellationsAreRejected() =
        runBlocking {
            for (error in listOf("LOAD_FAILED", "LOAD_CANCELLED", "INVALID_REQUEST")) {
                ReceiverFixture().use { fixture ->
                    val receiver = launch(Dispatchers.IO) { fixture.respond(fixture.readLoad(), error) }
                    withTimeout(5_000) {
                        assertFailsWith<IllegalStateException>(error) {
                            fixture.session.load("http://example.test/audio", media, 0, true, 1f, 1)
                        }
                        receiver.join()
                    }
                }
            }
        }

    private val media =
        CastMedia(
            "Test track",
            null,
            PlaybackResource("track", "http://example.test/audio", emptyMap(), null, null, mimeType = "audio/mp4"),
            60_000,
        )
}

/** Uses the real framed-message reader and writer; only TLS setup and receiver discovery are bypassed. */
private class ReceiverFixture : Closeable {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val listener = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
    private val client = Socket(InetAddress.getLoopbackAddress(), listener.localPort)
    private val receiver = listener.accept().apply { soTimeout = 5_000 }
    private val input = DataInputStream(receiver.getInputStream())
    private val output = DataOutputStream(receiver.getOutputStream())
    val session = GoogleCastSession(scope, InetAddress.getLoopbackAddress(), listener.localPort)

    init {
        set("socket", SSLSocketFactory.getDefault().createSocket())
        set("input", DataInputStream(client.getInputStream()))
        set("output", DataOutputStream(client.getOutputStream()))
        set("transportId", "receiver-test")
        set("receiverSessionId", "app-test")
        scope.launch {
            GoogleCastSession::class.java
                .getDeclaredMethod("readLoop")
                .apply { isAccessible = true }
                .invoke(session)
        }
    }

    private fun set(
        name: String,
        value: Any,
    ) {
        GoogleCastSession::class.java
            .getDeclaredField(name)
            .apply { isAccessible = true }
            .set(session, value)
    }

    fun readLoad(): CastWireMessage {
        while (true) {
            val message = decodeCastWireMessage(input.readUpTo(input.readInt()))
            if (Json
                    .parseToJsonElement(message.payload)
                    .jsonObject["type"]
                    ?.jsonPrimitive
                    ?.content == "LOAD"
            ) {
                return message
            }
        }
    }

    fun respond(
        request: CastWireMessage,
        type: String,
        sessionId: Int = 1,
        position: Int = 0,
    ) {
        val id =
            Json
                .parseToJsonElement(request.payload)
                .jsonObject
                .getValue("requestId")
                .jsonPrimitive.content
        val status =
            if (type ==
                "MEDIA_STATUS"
            ) {
                ",\"status\":[{\"mediaSessionId\":$sessionId,\"playerState\":\"PLAYING\",\"currentTime\":$position}]"
            } else {
                ""
            }
        val message =
            CastWireMessage(request.destinationId, request.sourceId, request.namespace, "{\"type\":\"$type\",\"requestId\":$id$status}")
        val bytes = encodeCastWireMessage(message)
        synchronized(output) {
            output.writeInt(bytes.size)
            output.write(bytes)
            output.flush()
        }
    }

    override fun close() {
        session.close()
        client.close()
        receiver.close()
        listener.close()
        scope.cancel()
    }
}
