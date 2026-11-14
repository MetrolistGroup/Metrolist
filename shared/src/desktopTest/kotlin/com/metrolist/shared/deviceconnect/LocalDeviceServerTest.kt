package com.metrolist.shared.deviceconnect

import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.FrameTooBigException
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.ServerSocket
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class LocalDeviceServerTest {
    @BeforeTest
    fun verifyReflectionFreeRuntime() {
        if (System.getProperty("metrolist.test.withoutReflection") == "true") {
            assertFailsWith<ClassNotFoundException> {
                Class.forName("kotlin.reflect.jvm.internal.ReflectionFactoryImpl")
            }
            assertEquals("true", System.getProperty("io.ktor.development"))
        }
    }

    @Test
    fun acceptsConnectionsOverIpv4AndIpv6() =
        runBlocking {
            val server = LocalDeviceServer()
            val client = createDeviceConnectHttpClient()
            val port =
                server.start(0, onFailure = {}) { channel ->
                    channel.incoming.firstOrNull()?.let { channel.send(it) }
                }
            try {
                withTimeout(5_000.milliseconds) {
                    for (host in listOf("127.0.0.1", "::1")) {
                        client.webSocket(host = host, port = port, path = "/connect") {
                            send("echo")
                            assertEquals("echo", assertIs<Frame.Text>(incoming.receive()).readText())
                        }
                    }
                }
            } finally {
                client.close()
                server.stop()
            }
        }

    @Test
    fun startsOnAnEphemeralPortAndReleasesIt() {
        val server = LocalDeviceServer()
        val port = server.start(0, onFailure = {}) {}
        assertTrue(port > 0)

        server.stop()

        ServerSocket(port).use { assertTrue(it.isBound) }
    }

    @Test
    fun startsOnTheRequestedPortAndReleasesIt() {
        val requestedPort = ServerSocket(0).use { it.localPort }
        val server = LocalDeviceServer()
        val port = server.start(requestedPort, onFailure = {}) {}
        assertEquals(requestedPort, port)

        server.stop()

        ServerSocket(port).use { assertTrue(it.isBound) }
    }

    @Test
    fun failedBindCanBeStoppedAndStartedSafely() {
        val occupied = ServerSocket(0)
        val port = occupied.localPort
        val server = LocalDeviceServer()
        try {
            assertFails { server.start(port, onFailure = {}) {} }
            occupied.close()

            assertEquals(port, server.start(port, onFailure = {}) {})
        } finally {
            occupied.close()
            server.stop()
        }
        ServerSocket(port).use { assertTrue(it.isBound) }
    }

    @Test
    fun restartingReleasesThePreviousConnector() {
        val (firstPort, secondPort) =
            ServerSocket(0).use { first ->
                ServerSocket(0).use { second -> first.localPort to second.localPort }
            }
        val server = LocalDeviceServer()
        try {
            server.start(firstPort, onFailure = {}) {}
            server.start(secondPort, onFailure = {}) {}

            ServerSocket(firstPort).use { assertTrue(it.isBound) }
        } finally {
            server.stop()
        }
    }

    @Test
    fun acceptsMaximumFrameAndRejectsOversizedFrame() {
        assertEquals(DEVICE_CONNECT_MAX_FRAME_BYTES.toInt(), sendFrame(DEVICE_CONNECT_MAX_FRAME_BYTES.toInt())?.length)
        assertNull(sendFrame(DEVICE_CONNECT_MAX_FRAME_BYTES.toInt() + 1))
    }

    @Test
    fun clientAcceptsMaximumFrameAndRejectsOversizedFrame() =
        runBlocking {
            val server = LocalDeviceServer()
            val client = createDeviceConnectHttpClient()
            val maximum = "x".repeat(DEVICE_CONNECT_MAX_FRAME_BYTES.toInt())
            val port =
                server.start(0, onFailure = {}) { channel ->
                    channel.send(maximum)
                    channel.send(maximum + "x")
                    channel.incoming.firstOrNull()
                }
            try {
                withTimeout(2_000.milliseconds) {
                    client.webSocket(host = "127.0.0.1", port = port, path = "/connect") {
                        assertEquals(maximum, assertIs<Frame.Text>(incoming.receive()).readText())
                        assertIs<FrameTooBigException>(incoming.receiveCatching().exceptionOrNull())
                    }
                }
            } finally {
                client.close()
                server.stop()
            }
        }

    private fun sendFrame(size: Int): String? =
        runBlocking {
            val received = CompletableDeferred<Result<String?>>()
            val server = LocalDeviceServer()
            val client = createDeviceConnectHttpClient()
            val port =
                server.start(0, onFailure = {}) { channel ->
                    received.complete(runCatching { channel.incoming.firstOrNull() })
                }
            try {
                withTimeout(2_000.milliseconds) {
                    client.webSocket(host = "127.0.0.1", port = port, path = "/connect") {
                        send("x".repeat(size))
                        if (size > DEVICE_CONNECT_MAX_FRAME_BYTES) incoming.receiveCatching() else received.await()
                    }
                    val result = received.await()
                    if (size > DEVICE_CONNECT_MAX_FRAME_BYTES) {
                        assertIs<FrameTooBigException>(result.exceptionOrNull())
                        null
                    } else {
                        result.getOrThrow()
                    }
                }
            } finally {
                client.close()
                server.stop()
            }
        }
}
