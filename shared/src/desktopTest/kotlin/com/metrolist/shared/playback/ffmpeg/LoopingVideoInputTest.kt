package com.metrolist.shared.playback.ffmpeg

import com.metrolist.shared.playback.VideoPlayer
import com.metrolist.shared.storage.DesktopAnimatedArtworkCache
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LoopingVideoInputTest {
    private val directory = Files.createTempDirectory("loop-cache-test").toFile()
    private val cache = DesktopAnimatedArtworkCache(directory, 1024 * 1024)

    @AfterTest
    fun cleanup() {
        cache.shutdown()
        directory.deleteRecursively()
    }

    @Test
    fun playerLoopsHttpClipWithoutReopeningDecoderOrDownloadingAgain() {
        val bytes =
            "YUV4MPEG2 W64 H36 F25:1 Ip A1:1 C420jpeg\n".toByteArray() +
                (0 until 3)
                    .flatMap {
                        (
                            "FRAME\n".toByteArray() + ByteArray(64 * 36) { 100 } +
                                ByteArray(64 * 36 / 2) { 128.toByte() }
                        ).toList()
                    }.toByteArray()
        val requests = AtomicInteger()
        val frames = CountDownLatch(8)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/cover.mp4") { exchange ->
            requests.incrementAndGet()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        val player = VideoPlayer(onFrame = { frames.countDown() }, onEnded = {}, looping = true)
        try {
            assertTrue(player.load("http://127.0.0.1:${server.address.port}/cover.mp4", emptyMap()) { 0L })
            val grabber =
                VideoPlayer::class.java
                    .getDeclaredField("grabber")
                    .apply { isAccessible = true }
                    .get(player)
            val codecField = FFmpegFrameGrabber::class.java.getDeclaredField("video_c").apply { isAccessible = true }
            val codec = codecField.get(grabber)
            player.play()
            assertTrue(frames.await(10, TimeUnit.SECONDS))
            assertTrue(codec === codecField.get(grabber))
            assertEquals(1, requests.get())
        } finally {
            runBlocking { player.closeAsync().join() }
            server.stop(0)
        }
    }

    @Test
    fun closingInputCancelsAnInProgressDownload() {
        val responseStarted = CountDownLatch(1)
        val releaseResponse = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/cover.mp4") { exchange ->
            try {
                exchange.sendResponseHeaders(200, 1024)
                exchange.responseBody.write(0)
                exchange.responseBody.flush()
                responseStarted.countDown()
                releaseResponse.await(5, TimeUnit.SECONDS)
            } finally {
                exchange.close()
            }
        }
        server.start()
        val input = LoopingVideoInput("http://127.0.0.1:${server.address.port}/cover.mp4", emptyMap(), cache = cache)
        try {
            val prepared = executor.submit<Boolean> { input.prepare() }
            assertTrue(responseStarted.await(5, TimeUnit.SECONDS))
            input.close()
            assertFalse(prepared.get(5, TimeUnit.SECONDS))
            assertEquals(-1, input.read())
        } finally {
            releaseResponse.countDown()
            input.close()
            executor.shutdownNow()
            server.stop(0)
        }
    }

    @Test
    fun repeatedRewindsReadCachedBytesWithoutAnotherRequest() {
        val bytes = ByteArray(8192) { it.toByte() }
        val requests = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/cover.mp4") { exchange ->
            requests.incrementAndGet()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            LoopingVideoInput("http://127.0.0.1:${server.address.port}/cover.mp4", emptyMap(), cache = cache).use { input ->
                assertTrue(input.prepare())
                repeat(3) {
                    input.seekToMediaPosition(0)
                    assertContentEquals(bytes, input.readBytes())
                }
                assertEquals(1, requests.get())
                assertEquals(bytes.size.toLong(), input.mediaSize)
            }
            LoopingVideoInput("http://127.0.0.1:${server.address.port}/cover.mp4", emptyMap(), cache = cache).use { replay ->
                assertTrue(replay.prepare())
                assertContentEquals(bytes, replay.readBytes())
                assertEquals(1, requests.get())
                cache.clear()
                replay.seekToMediaPosition(0)
                assertContentEquals(bytes, replay.readBytes())
            }
            assertEquals(0, cache.sizeBytes())
            LoopingVideoInput("http://127.0.0.1:${server.address.port}/cover.mp4", emptyMap(), cache = null).use { uncached ->
                assertTrue(uncached.prepare())
                assertContentEquals(bytes, uncached.readBytes())
                assertEquals(2, requests.get())
            }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun finiteHlsRangesAreReusedAsOneFile() {
        val requests = AtomicInteger()
        val bytes = ByteArray(30) { it.toByte() }
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/cover.m3u8") { exchange ->
            requests.incrementAndGet()
            val playlist =
                """
                #EXTM3U
                #EXT-X-MAP:URI="video.mp4",BYTERANGE="10@0"
                #EXT-X-BYTERANGE:10@10
                video.mp4
                #EXT-X-BYTERANGE:10
                video.mp4
                #EXT-X-ENDLIST
                """.trimIndent().toByteArray()
            exchange.sendResponseHeaders(200, playlist.size.toLong())
            exchange.responseBody.use { it.write(playlist) }
        }
        server.createContext("/video.mp4") { exchange ->
            requests.incrementAndGet()
            val range =
                exchange.requestHeaders
                    .getFirst("Range")
                    .removePrefix("bytes=")
                    .split('-')
                    .map(String::toInt)
            val data = bytes.copyOfRange(range[0], range[1] + 1)
            exchange.responseHeaders.add("Content-Range", "bytes ${range[0]}-${range[1]}/${bytes.size}")
            exchange.sendResponseHeaders(206, data.size.toLong())
            exchange.responseBody.use { it.write(data) }
        }
        server.start()
        try {
            repeat(2) {
                LoopingVideoInput("http://127.0.0.1:${server.address.port}/cover.m3u8", emptyMap(), cache = cache).use { input ->
                    assertTrue(input.prepare())
                    assertContentEquals(bytes, input.readBytes())
                }
            }
            assertEquals(4, requests.get())
            assertEquals(bytes.size.toLong(), cache.sizeBytes())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun clearDuringDownloadKeepsPlaybackButDoesNotPublish() {
        val started = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val bytes = ByteArray(1024) { it.toByte() }
        val executor = Executors.newSingleThreadExecutor()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/cover.mp4") { exchange ->
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use {
                it.write(bytes, 0, 1)
                it.flush()
                started.countDown()
                finish.await(5, TimeUnit.SECONDS)
                it.write(bytes, 1, bytes.size - 1)
            }
        }
        server.start()
        val input = LoopingVideoInput("http://127.0.0.1:${server.address.port}/cover.mp4", emptyMap(), cache = cache)
        try {
            val prepared = executor.submit<Boolean> { input.prepare() }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            cache.clear()
            finish.countDown()
            assertTrue(prepared.get(5, TimeUnit.SECONDS))
            assertContentEquals(bytes, input.readBytes())
            assertEquals(0, cache.sizeBytes())
        } finally {
            finish.countDown()
            input.close()
            executor.shutdownNow()
            server.stop(0)
        }
    }

    @Test
    fun oversizedAndPlaylistResponsesAreNotCached() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/oversized") { exchange ->
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { it.write(ByteArray(128)) }
        }
        server.createContext("/playlist") { exchange ->
            exchange.responseHeaders.add("Content-Type", "application/vnd.apple.mpegurl")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { it.write("#EXTM3U".toByteArray()) }
        }
        server.start()
        try {
            for (path in listOf("oversized", "playlist")) {
                LoopingVideoInput("http://127.0.0.1:${server.address.port}/$path", emptyMap(), maxBytes = 64, cache = cache).use { input ->
                    assertFalse(input.prepare())
                    assertEquals(-1, input.read())
                }
            }
        } finally {
            server.stop(0)
        }
    }
}
