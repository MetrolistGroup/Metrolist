package com.metrolist.shared.playback

import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class VideoPlaybackClockTest {
    @Test
    fun videoFollowsAudioClockJumpsAndDoesNotAdvanceDuringUnderruns() {
        val file = Files.createTempFile("metrolist-video-clock", ".y4m")
        val audioPosition = AtomicLong(0L)
        val renderedPosition = AtomicLong(-1L)
        val frames = LinkedBlockingQueue<Long>()
        val samples = LinkedBlockingQueue<Long>()
        val testThread = Thread.currentThread()
        val player =
            VideoPlayer(onFrame = {
                renderedPosition.set(it.timestampMs)
                frames.offer(it.timestampMs)
            })
        try {
            Files.newOutputStream(file).use { output ->
                output.write("YUV4MPEG2 W64 H48 F25:1 Ip A1:1 C420jpeg\n".toByteArray())
                val pixels = ByteArray(64 * 48 * 3 / 2) { 128.toByte() }
                repeat(100) {
                    output.write("FRAME\n".toByteArray())
                    output.write(pixels)
                }
            }
            assertTrue(
                player.load(file.toString(), positionProvider = {
                    audioPosition.get().also { if (Thread.currentThread() !== testThread) samples.offer(it) }
                }),
            )
            player.play(startPaused = false)
            awaitFrame(frames, 0L)
            awaitSamples(samples, audioPosition.get())
            assertEquals(0L, renderedPosition.get(), "Video must not invent progress while audio is stalled")

            audioPosition.set(1_000L)
            awaitFrame(frames, 1_000L)
            assertEquals(1_000L, player.getStats().audioClockMs, "Do not hide a real audio-clock jump")
            awaitSamples(samples, audioPosition.get())
            assertEquals(1_000L, renderedPosition.get())

            player.pause()
            audioPosition.set(2_000L)
            assertTrue(renderedPosition.get() <= 1_040L)
            player.setPlaybackSpeed(2f)
            player.resume()
            awaitFrame(frames, 2_000L)
            awaitSamples(samples, 2_000L)
            assertEquals(2_000L, renderedPosition.get(), "Speed must not advance video ahead of the audio renderer")
        } finally {
            player.close()
            Files.deleteIfExists(file)
        }
    }

    private fun awaitFrame(
        frames: LinkedBlockingQueue<Long>,
        expected: Long,
    ) {
        var position: Long
        do {
            position = assertNotNull(frames.poll(5, TimeUnit.SECONDS))
        } while (position < expected)
        assertEquals(expected, position)
    }

    private fun awaitSamples(
        samples: LinkedBlockingQueue<Long>,
        expected: Long,
    ) {
        repeat(3) {
            while (assertNotNull(samples.poll(5, TimeUnit.SECONDS)) != expected) { }
        }
    }

    @Test
    fun liveVideoTimelineNormalizesPtsToAudioClockAndHandlesDiscontinuities() {
        val timeline = LiveVideoTimeline()
        val frame1 = timeline.normalize(rawPtsMs = 23_233_780L, audioClockMs = 500L)
        assertEquals(500L, frame1)

        val frame2 = timeline.normalize(rawPtsMs = 23_233_813L, audioClockMs = 533L)
        assertEquals(533L, frame2)

        val frame3 = timeline.normalize(rawPtsMs = 23_233_846L, audioClockMs = 566L)
        assertEquals(566L, frame3)

        val frameAfterJump = timeline.normalize(rawPtsMs = 23_250_000L, audioClockMs = 1_000L)
        assertEquals(1_000L, frameAfterJump)

        timeline.reset()
        val frameAfterReset = timeline.normalize(rawPtsMs = 100_000L, audioClockMs = 0L)
        assertEquals(0L, frameAfterReset)
    }

    @Test
    fun liveVideoTimelineNormalizesWithAudioBroadcastPts() {
        val timeline = LiveVideoTimeline()
        val inSync = timeline.normalizeWithAudioPts(videoPtsMs = 23_233_780L, audioPtsMs = 23_233_780L, audioClockMs = 1_000L)
        assertEquals(1_000L, inSync)

        val future = timeline.normalizeWithAudioPts(videoPtsMs = 23_233_846L, audioPtsMs = 23_233_780L, audioClockMs = 1_000L)
        assertEquals(1_066L, future)

        val late = timeline.normalizeWithAudioPts(videoPtsMs = 23_233_680L, audioPtsMs = 23_233_780L, audioClockMs = 1_000L)
        assertEquals(900L, late)
    }
}
