package com.metrolist.shared.playback

import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Image
import org.jetbrains.skia.Picture
import org.jetbrains.skia.PictureRecorder
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VideoDisposalTest {
    @Test
    fun disposalWaitsForNativePreparationAndRejectsQueuedLoads() {
        val bytes = assertNotNull(javaClass.getResourceAsStream("/ffmpeg/canvas-source.mp4")).use { it.readBytes() }
        val source = BlockingVideoSource(bytes)
        val callbacks = AtomicInteger()
        val executor = Executors.newSingleThreadExecutor()
        val player = VideoPlayer(onFrame = { callbacks.incrementAndGet() }, onVideoSizeChanged = { _, _ -> callbacks.incrementAndGet() })
        try {
            val load = executor.submit<Boolean> { player.load(source) { 0L } }
            assertTrue(source.readStarted.await(10, TimeUnit.SECONDS))
            val disposal = player.disposeAsync()
            assertTrue(source.closed.await(5, TimeUnit.SECONDS))
            assertFalse(disposal.isCompleted, "The unpublished native grabber still belongs to prepare")
            source.releaseRead.countDown()
            assertFalse(load.get(10, TimeUnit.SECONDS))
            runBlocking { disposal.join() }

            val lateSource = BlockingVideoSource(bytes)
            assertFalse(player.load(lateSource) { 0L })
            assertTrue(lateSource.closed.await(5, TimeUnit.SECONDS))
            assertEquals(1L, lateSource.readStarted.count, "Disposed players must not enter native preparation")
            player.play()
            player.resume()
            assertEquals(0, callbacks.get())
            assertNull(field("grabber").get(player))
            assertNull(field("decodeThread").get(player))
        } finally {
            source.releaseRead.countDown()
            runBlocking { player.disposeAsync().join() }
            executor.shutdownNow()
        }
    }

    @Test
    fun rapidCreateDrawAndDisposeRetainsRecordedPixelsAndStopsDecoders() {
        val fixture = File(assertNotNull(javaClass.getResource("/ffmpeg/canvas-source.mp4")).toURI())
        val executor = Executors.newSingleThreadExecutor()
        try {
            repeat(16) { iteration ->
                val frames = LinkedBlockingQueue<VideoFrame>()
                val drawing = CountDownLatch(1)
                val releaseDraw = CountDownLatch(1)
                val player = VideoPlayer(onFrame = { frames.offer(it) }, looping = true)
                val nextFrames = LinkedBlockingQueue<VideoFrame>()
                val nextPlayer = VideoPlayer(onFrame = { nextFrames.offer(it) }, looping = true)
                try {
                    player.setTargetSize(17 + iteration, 31 + iteration, fillViewport = iteration % 2 == 0)
                    assertTrue(player.load(fixture.path) { 0L })
                    player.play(startPaused = iteration % 2 == 0)
                    val frame = assertNotNull(frames.poll(10, TimeUnit.SECONDS))
                    val decoder = field("decodeThread").get(player) as Thread
                    val draw =
                        executor.submit<Picture> {
                            PictureRecorder().use { recorder ->
                                val canvas = recorder.beginRecording(Rect.makeWH(64f, 64f))
                                assertTrue(
                                    frame.drawSafely {
                                        drawing.countDown()
                                        assertTrue(releaseDraw.await(10, TimeUnit.SECONDS))
                                        val bitmap = assertNotNull(planeY ?: bgraBitmap)
                                        Image.makeFromBitmap(bitmap).use { image -> canvas.drawImage(image, 0f, 0f) }
                                    },
                                )
                                recorder.finishRecordingAsPicture()
                            }
                        }
                    assertTrue(drawing.await(5, TimeUnit.SECONDS))
                    val disposal = player.disposeAsync()
                    assertFalse(disposal.isCompleted)
                    assertTrue(nextPlayer.load(fixture.path) { 0L })
                    nextPlayer.play(startPaused = true)
                    assertNotNull(nextFrames.poll(10, TimeUnit.SECONDS))
                    runBlocking { nextPlayer.disposeAsync().join() }
                    releaseDraw.countDown()
                    val picture = draw.get(10, TimeUnit.SECONDS)
                    runBlocking { disposal.join() }
                    picture.use {
                        Surface.makeRasterN32Premul(64, 64).use { surface -> surface.canvas.drawPicture(it) }
                    }
                    assertFalse(decoder.isAlive)
                    assertFalse(frame.drawSafely { error("Disposed frame must not access native bitmaps") })
                    val callbackCount = frames.size
                    player.resume()
                    player.seekTo(200)
                    player.pause()
                    assertEquals(callbackCount, frames.size)
                    assertFalse(player.load(fixture.path) { 0L })
                } finally {
                    releaseDraw.countDown()
                    runBlocking { player.disposeAsync().join() }
                    runBlocking { nextPlayer.disposeAsync().join() }
                }
            }
        } finally {
            executor.shutdownNow()
        }
    }

    private fun field(name: String) = VideoPlayer::class.java.getDeclaredField(name).apply { isAccessible = true }

    private class BlockingVideoSource(
        bytes: ByteArray,
    ) : ByteArrayInputStream(bytes),
        SeekableMediaSource {
        val readStarted = CountDownLatch(1)
        val releaseRead = CountDownLatch(1)
        val closed = CountDownLatch(1)
        override val mediaSize = bytes.size.toLong()
        override val mediaPosition get() = pos.toLong()

        override fun read(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ): Int {
            readStarted.countDown()
            check(releaseRead.await(10, TimeUnit.SECONDS))
            return if (closed.count == 0L) -1 else super.read(buffer, offset, length)
        }

        override fun readAtMediaPosition(
            position: Long,
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ): Int {
            val previous = pos
            seekToMediaPosition(position)
            return super.read(buffer, offset, length).also { pos = previous }
        }

        override fun seekToMediaPosition(position: Long): Long {
            pos = position.coerceIn(0, mediaSize).toInt()
            return mediaPosition
        }

        override fun updatePlaybackPosition(positionMs: Long) = Unit

        override fun close() {
            closed.countDown()
        }
    }
}
