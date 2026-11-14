package com.metrolist.shared.playback

import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class VideoDecodeLifecycleTest {
    @Test
    fun closeReturnsWhileDecoderIsBlockedAndNextPrepareWaitsForItsRelease() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val loadStarted = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val player = VideoPlayer(onFrame = {}, onEnded = {})
        val decoder =
            Thread {
                entered.countDown()
                while (release.count > 0) {
                    try {
                        release.await()
                    } catch (_: InterruptedException) {
                        // Models a native read that does not honor Java interruption.
                    }
                }
            }.apply {
                isDaemon = true
                start()
            }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            VideoPlayer::class.java
                .getDeclaredField("decodeThread")
                .apply { isAccessible = true }
                .set(player, decoder)
            val close = executor.submit { player.close() }
            close.get(5, TimeUnit.SECONDS)
            assertTrue(decoder.isAlive)
            val nextLoad =
                executor.submit<Boolean> {
                    loadStarted.countDown()
                    player.load("missing-video-file", emptyMap()) { 0L }
                }
            assertTrue(loadStarted.await(5, TimeUnit.SECONDS))
            assertFailsWith<TimeoutException> { nextLoad.get(100, TimeUnit.MILLISECONDS) }
            release.countDown()
            assertFalse(nextLoad.get(10, TimeUnit.SECONDS))
            decoder.join(5_000L)
            assertFalse(decoder.isAlive)
        } finally {
            release.countDown()
            runBlocking { player.closeAsync().join() }
            executor.shutdownNow()
        }
    }

    @Test
    fun nativeBitmapReleaseWaitsForDrawWithoutBlockingClose() {
        val directory = createTempDirectory("metrolist-video-draw").toFile()
        val frames = LinkedBlockingQueue<VideoFrame>()
        val drawing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val player = VideoPlayer(onFrame = { frames.offer(it) }, onEnded = {})
        try {
            val file = File(directory, "video.y4m")
            file.writeBytes(
                "YUV4MPEG2 W64 H36 F25:1 Ip A1:1 C420jpeg\nFRAME\n".toByteArray() +
                    ByteArray(64 * 36) { 100 } + ByteArray(64 * 36 / 2) { 128.toByte() },
            )
            assertTrue(player.load(file.path, emptyMap()) { 0L })
            player.play(startPaused = true)
            val frame = assertNotNull(frames.poll(10, TimeUnit.SECONDS))
            val draw =
                executor.submit {
                    frame.drawSafely {
                        drawing.countDown()
                        release.await()
                        assertEquals(64, planeY!!.width)
                    }
                }
            assertTrue(drawing.await(5, TimeUnit.SECONDS))
            val closing = player.closeAsync()
            assertFalse(closing.isCompleted)
            release.countDown()
            draw.get(5, TimeUnit.SECONDS)
            runBlocking { closing.join() }
            assertFalse(frame.drawSafely { error("Retired frame must not be drawn") })
            assertTrue(frames.isEmpty())
        } finally {
            release.countDown()
            runBlocking { player.closeAsync().join() }
            executor.shutdownNow()
            directory.deleteRecursively()
        }
    }

    @Test
    fun pausedDecoderSupportsSeekResumeAndClose() {
        val directory = createTempDirectory("metrolist-video-pause").toFile()
        val frames = LinkedBlockingQueue<VideoFrame>()
        val position = AtomicLong()
        val player = VideoPlayer(onFrame = { frames.offer(it) }, onEnded = {})
        try {
            val file = File(directory, "video.y4m")
            file.outputStream().use { output ->
                output.write("YUV4MPEG2 W64 H36 F25:1 Ip A1:1 C420jpeg\n".toByteArray())
                repeat(100) {
                    output.write("FRAME\n".toByteArray())
                    output.write(ByteArray(64 * 36) { 100 })
                    output.write(ByteArray(64 * 36 / 2) { 128.toByte() })
                }
            }
            player.setTargetSize(32, 18)
            assertTrue(player.load(file.path, emptyMap()) { position.get() })
            player.play(startPaused = true)
            val first = assertNotNull(frames.poll(10, TimeUnit.SECONDS))
            assertEquals(32 to 18, first.width to first.height)
            assertEquals(VideoPixelFormat.YUV420P, first.format)
            first.drawSafely {
                assertEquals(32, planeY!!.width)
                assertEquals(16, planeU!!.width)
                assertEquals(16, planeV!!.width)
            }
            val decodeThread =
                VideoPlayer::class.java
                    .getDeclaredField("decodeThread")
                    .apply { isAccessible = true }
                    .get(player) as Thread

            player.setTargetSize(64, 36)
            player.seekTo(400)
            val sought = assertNotNull(frames.poll(5, TimeUnit.SECONDS))
            assertEquals(64 to 36, sought.width to sought.height)
            position.set(500)
            player.resume()
            assertNotNull(frames.poll(5, TimeUnit.SECONDS))
            player.pause()
            runBlocking { player.closeAsync().join() }
            assertFalse(decodeThread.isAlive)
        } finally {
            runBlocking { player.closeAsync().join() }
            directory.deleteRecursively()
        }
    }
}
