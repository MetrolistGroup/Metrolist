package com.metrolist.shared.cast

import com.metrolist.shared.youtube.playback.PlaybackEventLog
import com.metrolist.shared.youtube.playback.PlaybackResource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FcastSessionTest {
    @Test
    fun orderlyReceiverCloseDoesNotLogFailure() =
        runBlocking {
            PlaybackEventLog.clear()
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
                val peer =
                    async(Dispatchers.IO) {
                        server.accept().use { socket ->
                            socket.soTimeout = 5_000
                            val input = socket.getInputStream()
                            val output = socket.getOutputStream()
                            val greetingSize = ByteBuffer.wrap(input.readUpTo(4)).order(ByteOrder.LITTLE_ENDIAN).int
                            input.read()
                            input.readUpTo(greetingSize - 1)
                            val body = """{"version":1}""".toByteArray()
                            val header =
                                ByteBuffer
                                    .allocate(4)
                                    .order(ByteOrder.LITTLE_ENDIAN)
                                    .putInt(body.size + 1)
                                    .array()
                            output.write(header)
                            output.write(11)
                            output.write(body)
                            output.flush()
                            val playSize = ByteBuffer.wrap(input.readUpTo(4)).order(ByteOrder.LITTLE_ENDIAN).int
                            input.read()
                            input.readUpTo(playSize - 1)
                        }
                    }
                val session = FcastSession(scope, InetAddress.getLoopbackAddress(), server.localPort, "test")
                val media =
                    CastMedia(
                        "Song",
                        null,
                        PlaybackResource(
                            "song",
                            "https://r1.googlevideo.com/videoplayback",
                            emptyMap(),
                            null,
                            null,
                            contentLengthBytes = 1024L,
                            mimeType = "audio/mp4",
                        ),
                        180_000L,
                    )
                try {
                    session.load("http://127.0.0.1/audio", media, 0L, true, 1f, 1L)
                    withTimeout(5_000L) { peer.await() }
                    withTimeout(5_000L) { session.state.first { !it.connected } }
                    assertTrue(PlaybackEventLog.events.value.isEmpty())
                } finally {
                    session.close()
                    scope.cancel()
                }
            }
        }

    @Test
    fun loadingPreservesReceiverVolumeUntilExplicitAdjustment() =
        runBlocking {
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
                val received =
                    async(Dispatchers.IO) {
                        server.accept().use { socket ->
                            socket.soTimeout = 5_000
                            val input = socket.getInputStream()
                            val output = socket.getOutputStream()
                            val frames = mutableListOf<Pair<Int, String>>()
                            while (true) {
                                val length = ByteBuffer.wrap(input.readUpTo(4)).order(ByteOrder.LITTLE_ENDIAN).int
                                val opcode = input.read()
                                frames += opcode to input.readUpTo(length - 1).toString(Charsets.UTF_8)
                                if (opcode == 11) {
                                    for ((responseOpcode, body) in listOf(7 to """{"volume":0.2}""", 11 to """{"version":3}""")) {
                                        val bytes = body.toByteArray()
                                        output.write(
                                            ByteBuffer
                                                .allocate(4)
                                                .order(ByteOrder.LITTLE_ENDIAN)
                                                .putInt(bytes.size + 1)
                                                .array(),
                                        )
                                        output.write(responseOpcode)
                                        output.write(bytes)
                                        output.flush()
                                    }
                                }
                                if (opcode == 5) break
                            }
                            frames
                        }
                    }
                val session = FcastSession(scope, InetAddress.getLoopbackAddress(), server.localPort, "test")
                try {
                    val media =
                        CastMedia(
                            "Song",
                            null,
                            PlaybackResource(
                                "song",
                                "https://r1.googlevideo.com/videoplayback",
                                emptyMap(),
                                null,
                                null,
                                contentLengthBytes = 1024L,
                                mimeType = "audio/mp4",
                            ),
                            180_000L,
                        )
                    session.load("http://127.0.0.1/audio", media, 0L, true, 1f, 1L)
                    withTimeout(5_000L) { session.state.first { it.volume == 0.2f } }
                    session.load("http://127.0.0.1/next", media, 0L, true, 0.8f, 2L)
                    assertEquals(0.2f, session.state.value.volume)
                    session.setVolume(0.5f)
                    session.seekTo(1_000L)
                    val frames = withTimeout(5_000L) { received.await() }
                    assertEquals(2, frames.count { it.first == 1 })
                    assertEquals("""{"volume":0.5}""", frames.single { it.first == 8 }.second)
                    assertTrue(frames.indexOfFirst { it.first == 8 } > frames.indexOfLast { it.first == 1 })
                } finally {
                    session.close()
                    scope.cancel()
                }
            }
        }
}
