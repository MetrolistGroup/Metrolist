package com.metrolist.shared.cast

import com.metrolist.innertubex.extraction.AudioQuality
import com.metrolist.shared.model.AlbumTiny
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.network.sharedOkHttpClient
import com.metrolist.shared.utils.LogBridge
import com.metrolist.shared.youtube.playback.PlaybackEventLog
import com.metrolist.shared.youtube.playback.PlaybackResource
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URI
import java.net.UnknownHostException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class CastProtocolTest {
    @Test
    fun cleanGoogleCastHeaderEofIsNormalButPartialHeaderFails() {
        assertNull(readGoogleCastFrameSize(ByteArrayInputStream(byteArrayOf())))
        assertFailsWith<EOFException> {
            readGoogleCastFrameSize(ByteArrayInputStream(byteArrayOf(0, 0)))
        }
    }

    @Test
    fun rejectedHandoffSeekFailsBeforeStartingPlayback() =
        runBlocking {
            val actions = Collections.synchronizedList(mutableListOf<String>())
            val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
            server.createContext("/") { exchange ->
                val action = exchange.requestHeaders.getFirst("SOAPAction").orEmpty()
                actions += action
                val body =
                    if (exchange.requestMethod == "GET") {
                        """<root><device><friendlyName>Receiver</friendlyName><UDN>receiver</UDN><serviceList><service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType><controlURL>/control</controlURL></service></serviceList></device></root>"""
                    } else {
                        "<response/>"
                    }
                exchange.requestBody.use { it.readBytes() }
                exchange.sendResponseHeaders(if ("#Seek" in action) 500 else 200, body.toByteArray().size.toLong())
                exchange.responseBody.use { it.write(body.toByteArray()) }
            }
            server.start()
            val session = DlnaSession(this, "http://127.0.0.1:${server.address.port}/device.xml")
            try {
                val media =
                    CastMedia(
                        SongItem("id", "Title", null, emptyList(), null),
                        null,
                        PlaybackResource("id", "https://media.example.test/audio", emptyMap(), null, null, mimeType = "audio/mp4"),
                        60_000,
                    )
                assertFails { session.load("http://127.0.0.1/audio", media, 30_000, true, 0.5f, 1) }
                assertTrue(actions.any { "#Seek" in it })
                assertTrue(actions.none { "#Play" in it })
                assertFalse(session.state.value.isPlaying)
                assertEquals(0L, session.state.value.positionMs)
            } finally {
                session.close()
                server.stop(0)
            }
        }

    @Test
    fun proxyDiagnosticsOmitSensitiveExceptionMessages() {
        PlaybackEventLog.clear()
        val previous = LogBridge.sink
        val logs = mutableListOf<Pair<String, String>>()
        val workerThread = Thread.currentThread()
        LogBridge.sink = { level, tag, message, error ->
            if (tag == "CastProxy" && Thread.currentThread() === workerThread) {
                assertNull(error)
                logs += level to message
            }
        }
        try {
            runCastProxyWorker { throw IllegalStateException("secret signed URL") }
            runCastProxyWorker { throw SocketException("secret token") }
            runCastProxyWorker { throw UnknownHostException("upstream unavailable") }
            runCastProxyWorker(isClosed = { true }) { throw IOException("shutdown") }
            assertEquals(listOf("WARN", "DEBUG", "DEBUG", "DEBUG"), logs.map { it.first })
            assertEquals(1, PlaybackEventLog.events.value.size)
            assertTrue(logs.none { "secret" in it.second })
        } finally {
            LogBridge.sink = previous
        }
    }

    @Test
    fun proxyRecoversAllWorkersFromReceiversThatStopReading() {
        val address = InetAddress.getLoopbackAddress()
        val bytes = ByteArray(16 * 1024 * 1024)
        val media =
            CastMedia(
                SongItem("id", "Title", null, emptyList(), null),
                null,
                PlaybackResource(
                    "id",
                    "https://r1.googlevideo.com/videoplayback",
                    emptyMap(),
                    null,
                    null,
                    contentLengthBytes = bytes.size.toLong(),
                    mimeType = "audio/mp4",
                ),
                0L,
            )
        val started = CountDownLatch(4)
        val upstream =
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    started.countDown()
                    Response
                        .Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(206)
                        .message("Partial Content")
                        .body(bytes.toResponseBody())
                        .build()
                }.build()
        val proxy = CastProxyServer(address, address, media, writeTimeoutMillis = 300)
        CastProxyServer::class.java
            .getDeclaredField("client")
            .apply { isAccessible = true }
            .set(proxy, upstream)
        val clients = mutableListOf<Socket>()
        try {
            val path = URI(proxy.mediaUrl()).rawPath
            repeat(4) {
                val socket =
                    Socket().apply {
                        receiveBufferSize = 1024
                        connect(InetSocketAddress(address, proxy.port))
                    }
                clients += socket
                socket.getOutputStream().write("GET $path HTTP/1.1\r\nHost: localhost\r\n\r\n".toByteArray())
            }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            Socket(address, proxy.port).use { client ->
                client.soTimeout = 5_000
                client.getOutputStream().write("HEAD $path HTTP/1.1\r\nHost: localhost\r\n\r\n".toByteArray())
                assertTrue(
                    client
                        .getInputStream()
                        .bufferedReader()
                        .readLine()
                        .startsWith("HTTP/1.1 200"),
                )
            }
            val workers =
                CastProxyServer::class.java
                    .getDeclaredField("workers")
                    .apply { isAccessible = true }
                    .get(proxy) as ThreadPoolExecutor
            proxy.close()
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS))
        } finally {
            clients.forEach(Socket::close)
            proxy.close()
        }
    }

    @Test
    fun proxyBoundsAcceptedSocketsAndClosesActiveAndQueuedClients() {
        val address = InetAddress.getLoopbackAddress()
        val media =
            CastMedia(
                SongItem("id", "Title", null, emptyList(), null),
                null,
                PlaybackResource(
                    "id",
                    "https://r1.googlevideo.com/videoplayback",
                    emptyMap(),
                    null,
                    null,
                    contentLengthBytes = 10L,
                    mimeType = "audio/mp4",
                ),
                0L,
            )
        val proxy = CastProxyServer(address, address, media)
        val clients = mutableListOf<Socket>()
        val workers =
            CastProxyServer::class.java
                .getDeclaredField("workers")
                .apply { isAccessible = true }
                .get(proxy) as ThreadPoolExecutor

        try {
            repeat(12) { clients += Socket(address, proxy.port).apply { soTimeout = 5_000 } }
            Socket(address, proxy.port).use { rejected ->
                rejected.soTimeout = 5_000
                assertEquals(-1, rejected.getInputStream().read())
                assertEquals(4, workers.activeCount)
                assertEquals(8, workers.queue.size)
            }
            val upstream = sharedOkHttpClient.newCall(Request.Builder().url("https://r1.googlevideo.com/videoplayback").build())
            val unrelated = sharedOkHttpClient.newCall(Request.Builder().url("https://r1.googlevideo.com/videoplayback").build())

            @Suppress("UNCHECKED_CAST")
            val calls =
                CastProxyServer::class.java
                    .getDeclaredField("calls")
                    .apply { isAccessible = true }
                    .get(proxy) as MutableSet<Call>
            calls.add(upstream)
            proxy.close()
            assertTrue(upstream.isCanceled())
            assertFalse(unrelated.isCanceled())
            clients.forEach { assertEquals(-1, it.getInputStream().read()) }
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS))
        } finally {
            proxy.close()
            clients.forEach { it.close() }
        }
    }

    @Test
    fun googleCastMusicLoadPreservesSongMetadata() {
        val song =
            SongItem(
                "id",
                "Title",
                "https://example.com/art",
                listOf(ArtistTiny(null, "Artist"), ArtistTiny(null, "Guest")),
                AlbumTiny(null, "Album"),
            )
        val resource =
            PlaybackResource(
                "id",
                "https://example.com/audio",
                emptyMap(),
                null,
                Instant.DISTANT_FUTURE,
                mimeType = "audio/mp4; codecs=mp4a",
            )
        val media = CastMedia(song, song.thumbnail, resource, 12_000L)
        val payload = googleCastMediaInformation("http://proxy/audio", media)
        val metadata = payload.getValue("metadata").jsonObject
        assertEquals("audio/mp4", payload.getValue("contentType").jsonPrimitive.content)
        assertEquals("3", metadata.getValue("metadataType").jsonPrimitive.content)
        assertEquals(song.title, metadata.getValue("title").jsonPrimitive.content)
        assertEquals("Artist, Guest", metadata.getValue("artist").jsonPrimitive.content)
        assertEquals("Album", metadata.getValue("albumName").jsonPrimitive.content)
        assertEquals(
            song.thumbnail,
            metadata
                .getValue("images")
                .jsonArray
                .single()
                .jsonObject
                .getValue("url")
                .jsonPrimitive.content,
        )
        val missing =
            googleCastMediaInformation(
                "http://proxy/audio",
                media.copy(artist = null, album = null),
            ).getValue("metadata").jsonObject
        assertFalse("artist" in missing)
        assertFalse("albumName" in missing)
    }

    @Test
    fun castWireMessageRoundTrips() {
        val expected =
            CastWireMessage(
                sourceId = "sender-1",
                destinationId = "receiver-0",
                namespace = "urn:x-cast:test",
                payload = """{"type":"PING"}""",
            )

        assertEquals(expected, decodeCastWireMessage(encodeCastWireMessage(expected)))
    }

    @Test
    fun googleCastLoadKeepsObservedReceiverVolume() {
        val loaded =
            CastSessionState(volume = 0.35f, mediaGeneration = 4L)
                .withGoogleCastMedia(durationMs = 12_000L, positionMs = 500L, autoplay = true)

        assertEquals(0.35f, loaded.volume)
        assertEquals(12_000L, loaded.durationMs)
        assertEquals(4L, loaded.mediaGeneration)
        assertTrue(loaded.isPlaying)
    }

    @Test
    fun fcastRejectsOldPlaybackUpdatesWhileAReplacementLoads() {
        fun accepts(
            awaiting: Boolean = true,
            ended: Boolean = false,
            expectedDurationMs: Long = 10_000L,
            actualDurationMs: Long = 10_000L,
            expectedPositionMs: Long = 0L,
            actualPositionMs: Long = 0L,
            hasPosition: Boolean = true,
        ) = isCurrentFcastPlaybackUpdate(
            awaiting,
            ended,
            expectedDurationMs,
            actualDurationMs,
            expectedPositionMs,
            actualPositionMs,
            hasPosition,
        )

        assertFalse(accepts(ended = true))
        assertFalse(accepts(actualDurationMs = 20_000L))
        assertFalse(accepts(actualPositionMs = 9_000L))
        assertFalse(accepts(actualDurationMs = 0L, actualPositionMs = 9_000L))
        assertFalse(accepts(hasPosition = false))
        assertTrue(accepts(actualPositionMs = 1_000L))
        assertTrue(accepts(actualDurationMs = 0L, actualPositionMs = 1_000L))
        assertTrue(accepts(awaiting = false, ended = true, actualPositionMs = 10_000L))
    }

    @Test
    fun byteRangesStayInsideKnownMediaLength() {
        assertEquals(ByteRange(10, 19), parseByteRange("bytes=10-19", 100))
        assertEquals(ByteRange(90, 99), parseByteRange("bytes=-10", 100))
        assertEquals(ByteRange(95, 99), parseByteRange("bytes=95-200", 100))
        assertNull(parseByteRange("bytes=100-", 100))
        assertNull(parseByteRange("bytes=20-10", 100))
        assertNull(parseByteRange("items=0-1", 100))
    }

    @Test
    fun ssdpLocationHeaderIsCaseInsensitive() {
        val response =
            "HTTP/1.1 200 OK\r\n" +
                "CACHE-CONTROL: max-age=1800\r\n" +
                "Location: http://192.168.1.20:1400/xml/device_description.xml\r\n\r\n"

        assertEquals(
            "http://192.168.1.20:1400/xml/device_description.xml",
            parseSsdpLocation(response),
        )
        assertNull(parseSsdpLocation("HTTP/1.1 404 Not Found\r\nLocation: http://192.168.1.20/\r\n"))
    }

    @Test
    fun proxyAcceptsOnlyGoogleVideoMediaUrls() {
        assertEquals(
            "https://r1---sn.example.googlevideo.com/videoplayback?id=media",
            validatedGoogleVideoUrl("https://r1---sn.example.googlevideo.com/videoplayback?id=media"),
        )
        assertFails { validatedGoogleVideoUrl("http://r1---sn.example.googlevideo.com/videoplayback?id=media") }
        assertFails { validatedGoogleVideoUrl("https://googlevideo.com.evil.example/videoplayback") }
        assertFails { validatedGoogleVideoUrl("https://r1---sn.example.googlevideo.com/other") }
    }

    @Test
    fun proxyClientDisconnectDoesNotEscapeWorker() {
        runCastProxyWorker { throw SocketException("broken pipe") }
    }

    @Test
    fun dlnaTimeRoundTrips() {
        assertEquals("01:02:03", formatDlnaTime(3_723_999L))
        assertEquals(3_723_000L, parseDlnaTime("01:02:03.500"))
        assertNull(parseDlnaTime("01:99:00"))
    }

    @Test
    fun sonosRequiresAacWhileOtherReceiversKeepTheirStream() {
        val opus =
            PlaybackResource("id", "https://rr1---sn.googlevideo.com/x", emptyMap(), null, null, mimeType = "audio/webm; codecs=\"opus\"")
        val aac = opus.copy(mimeType = "audio/mp4; codecs=\"mp4a.40.2\"")
        assertFalse(opus.isPlayableOn(CastProtocol.SONOS))
        assertTrue(aac.isPlayableOn(CastProtocol.SONOS))
        assertTrue(opus.isPlayableOn(CastProtocol.DLNA))
        assertEquals(AudioQuality.MP4, castAudioQuality(CastProtocol.SONOS))
        assertNull(castAudioQuality(CastProtocol.GOOGLE_CAST))
    }

    @Test
    fun sonosDescriptorUsesRoomNameAndEmbeddedRendererServices() {
        val xml =
            """<?xml version="1.0"?><root xmlns="urn:schemas-upnp-org:device-1-0"><device>""" +
                "<deviceType>urn:schemas-upnp-org:device:ZonePlayer:1</deviceType>" +
                "<friendlyName>192.168.1.20 - Sonos One - RINCON_ABC</friendlyName>" +
                "<manufacturer>Sonos, Inc.</manufacturer><UDN>uuid:RINCON_ABC</UDN><roomName>Kitchen</roomName>" +
                "<deviceList><device><deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType><serviceList>" +
                "<service><serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>" +
                "<controlURL>/MediaRenderer/RenderingControl/Control</controlURL></service>" +
                "<service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>" +
                "<controlURL>/MediaRenderer/AVTransport/Control</controlURL></service>" +
                "</serviceList></device></deviceList></device></root>"
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            thread(isDaemon = true) {
                server.accept().use { socket ->
                    val body = xml.toByteArray()
                    socket.getInputStream().bufferedReader().let { reader ->
                        while (!reader.readLine().isNullOrEmpty()) { }
                    }
                    socket.getOutputStream().write(
                        "HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\n\r\n".toByteArray() + body,
                    )
                }
            }
            val descriptor = runBlocking { fetchDlnaDescriptor("http://127.0.0.1:${server.localPort}/xml/device_description.xml") }
            assertTrue(descriptor.isSonos)
            assertEquals("Kitchen", descriptor.name)
            assertEquals("http://127.0.0.1:${server.localPort}/MediaRenderer/AVTransport/Control", descriptor.avTransportUrl)
        }
    }

    @Test
    fun unexpectedDisconnectRetainsTheLastReceiverPositionAndPlayIntent() {
        val state =
            CastState(
                status = CastConnectionStatus.CONNECTED,
                device = CastDevice("id", "receiver", CastProtocol.GOOGLE_CAST, "127.0.0.1", 8009),
                isPlaying = true,
                positionMs = 42_000L,
                durationMs = 180_000L,
                mediaGeneration = 7L,
            )

        val disconnected = state.afterDisconnect(CastError.PLAYBACK_FAILED)

        assertEquals(42_000L, disconnected.positionMs)
        assertTrue(disconnected.isPlaying)
        assertEquals(7L, disconnected.mediaGeneration)
        assertEquals(CastConnectionStatus.DISCONNECTED, disconnected.status)
    }

    @Test
    fun disconnectPublishesOnlyTheRetainedFinalStateAfterCleanup() =
        runBlocking {
            val finalState =
                CastState(
                    isPlaying = true,
                    positionMs = 42_000L,
                    error = CastError.PLAYBACK_FAILED,
                    mediaGeneration = 7L,
                )
            val cleanupStarted = CompletableDeferred<Unit>()
            val finishCleanup = CompletableDeferred<Unit>()
            val published = mutableListOf<CastState>()
            val disconnect =
                async {
                    completeCastDisconnect(
                        finalState = finalState,
                        closeResources = {
                            cleanupStarted.complete(Unit)
                            finishCleanup.await()
                        },
                        publish = published::add,
                    )
                }

            cleanupStarted.await()
            assertTrue(published.isEmpty())
            finishCleanup.complete(Unit)
            disconnect.await()

            assertEquals(listOf(finalState), published)
        }

    @Test
    fun stopMarkerIsSetAfterQueuedMediaReplacement() =
        runBlocking {
            val operations = CastOperationLock()
            val replacementStarted = CompletableDeferred<Unit>()
            val releaseReplacement = CompletableDeferred<Unit>()
            val replacement =
                async {
                    operations.withLock {
                        replacementStarted.complete(Unit)
                        releaseReplacement.await()
                        markMediaActive()
                    }
                }
            replacementStarted.await()

            val stopSent = CompletableDeferred<Unit>()
            val stop =
                async {
                    operations.withStopMarker {
                        stopSent.complete(Unit)
                    }
                }
            yield()
            assertFalse(stopSent.isCompleted)

            releaseReplacement.complete(Unit)
            replacement.await()
            stop.await()
            assertTrue(operations.remoteStopRequested)
        }

    @Test
    fun localNetworkAddressClassification() {
        assertTrue(isLocalNetworkAddress(InetAddress.getByName("192.168.1.100")))
        assertTrue(isLocalNetworkAddress(InetAddress.getByName("10.0.0.5")))
        assertTrue(isLocalNetworkAddress(InetAddress.getByName("172.16.0.1")))
        assertTrue(isLocalNetworkAddress(InetAddress.getByName("127.0.0.1")))
        assertFalse(isLocalNetworkAddress(InetAddress.getByName("8.8.8.8")))
    }

    @Test
    fun proxyClosePreservesSharedOkHttpClientDispatcher() {
        val song =
            SongItem(
                "id",
                "Title",
                "https://example.com/art",
                listOf(ArtistTiny(null, "Artist")),
                AlbumTiny(null, "Album"),
            )
        val resource =
            PlaybackResource(
                "id",
                "https://r1---sn.example.googlevideo.com/videoplayback?id=media",
                emptyMap(),
                null,
                Instant.DISTANT_FUTURE,
                contentLengthBytes = 1024L,
                mimeType = "audio/mp4",
            )
        val media = CastMedia(song, song.thumbnail, resource, 12_000L)
        val proxy = CastProxyServer(InetAddress.getLoopbackAddress(), InetAddress.getLoopbackAddress(), media)
        proxy.close()
        assertFalse(sharedOkHttpClient.dispatcher.executorService.isShutdown)
        assertFalse(sharedOkHttpClient.dispatcher.executorService.isTerminated)
    }
}
