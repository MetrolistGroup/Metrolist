package com.metrolist.shared.playback

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnimatedCoverPlayerTest {
    @Test
    fun cancellationAbortsPreparationBeforeWaitingForTheNextSession() =
        runBlocking {
            val downloading = CountDownLatch(1)
            val releaseDownload = CountDownLatch(1)
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/cover.mp4") { exchange ->
                exchange.sendResponseHeaders(200, 1024L)
                exchange.responseBody.use {
                    it.write(0)
                    it.flush()
                    downloading.countDown()
                    releaseDownload.await(10, TimeUnit.SECONDS)
                }
            }
            server.start()
            val cover = AnimatedCoverPlayer()
            val frames = LinkedBlockingQueue<VideoFrame>()
            val playback =
                launch(Dispatchers.Default) {
                    cover.play("http://127.0.0.1:${server.address.port}/cover.mp4", MutableStateFlow(true)) {
                        it?.let(frames::offer)
                    }
                }
            try {
                assertTrue(withContext(Dispatchers.IO) { downloading.await(5, TimeUnit.SECONDS) })
                withTimeout(5_000L) { playback.cancelAndJoin() }
                assertEquals(1L, releaseDownload.count)
                assertTrue(frames.isEmpty())
            } finally {
                releaseDownload.countDown()
                playback.cancelAndJoin()
                server.stop(0)
            }
        }

    @Test
    fun replacementWaitsForDrawAndCanceledRequestsCannotPublishFrames() =
        runBlocking {
            val fixture = File(assertNotNull(javaClass.getResource("/ffmpeg/canvas-source.mp4")).toURI())
            val cover = AnimatedCoverPlayer()
            val active = MutableStateFlow(true)
            val frames = LinkedBlockingQueue<VideoFrame>()
            val nextFrames = LinkedBlockingQueue<VideoFrame>()
            val drawing = CountDownLatch(1)
            val releaseDraw = CountDownLatch(1)
            val executor = Executors.newSingleThreadExecutor()
            cover.setTargetSize(32, 48, fillViewport = true)
            val firstCleared = AtomicBoolean()
            val first =
                launch(Dispatchers.Default) {
                    cover.play(fixture.path, active) {
                        if (it != null) frames.offer(it) else firstCleared.set(true)
                    }
                }
            var second: Job? = null
            try {
                val frame = withContext(Dispatchers.IO) { assertNotNull(frames.poll(10, TimeUnit.SECONDS)) }
                val draw =
                    executor.submit {
                        assertTrue(
                            frame.drawSafely {
                                drawing.countDown()
                                assertTrue(releaseDraw.await(10, TimeUnit.SECONDS))
                            },
                        )
                    }
                assertTrue(withContext(Dispatchers.IO) { drawing.await(5, TimeUnit.SECONDS) })
                val canceled =
                    launch(start = CoroutineStart.UNDISPATCHED) {
                        cover.play(fixture.path, active) { error("Canceled request ran") }
                    }
                canceled.cancelAndJoin()
                second =
                    launch(Dispatchers.Default) {
                        cover.play(fixture.path, active) {
                            if (it != null) {
                                assertTrue(firstCleared.get())
                                nextFrames.offer(it)
                            }
                        }
                    }
                first.cancel()
                assertNull(withTimeoutOrNull(100L) { first.join() })
                assertTrue(nextFrames.isEmpty())
                releaseDraw.countDown()
                withContext(Dispatchers.IO) { draw.get(5, TimeUnit.SECONDS) }
                first.join()
                val next = withContext(Dispatchers.IO) { assertNotNull(nextFrames.poll(10, TimeUnit.SECONDS)) }
                assertEquals(frame.width to frame.height, next.width to next.height)
                assertFalse(frame.drawSafely { error("Retired frame remained drawable") })
            } finally {
                releaseDraw.countDown()
                first.cancelAndJoin()
                second?.cancelAndJoin()
                executor.shutdownNow()
            }
        }

    @Test
    fun failedLoadClearsArtworkAndAllowsTheNextCover() =
        runBlocking {
            val fixture = File(assertNotNull(javaClass.getResource("/ffmpeg/canvas-source.mp4")).toURI())
            val cover = AnimatedCoverPlayer()
            val active = MutableStateFlow(true)
            val availability = mutableListOf<Boolean>()
            cover.play(File(fixture.parentFile, "missing-cover.mp4").path, active) { availability += it != null }
            assertEquals(listOf(false), availability)

            val frames = LinkedBlockingQueue<VideoFrame>()
            val playback = launch(Dispatchers.Default) { cover.play(fixture.path, active) { it?.let(frames::offer) } }
            try {
                assertTrue(withContext(Dispatchers.IO) { frames.poll(10, TimeUnit.SECONDS) } != null)
            } finally {
                playback.cancelAndJoin()
            }
        }
}
