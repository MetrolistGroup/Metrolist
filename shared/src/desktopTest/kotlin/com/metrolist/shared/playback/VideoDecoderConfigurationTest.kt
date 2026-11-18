package com.metrolist.shared.playback

import com.metrolist.shared.playback.ffmpeg.FFmpegFrameGrabber
import kotlinx.coroutines.runBlocking
import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VideoDecoderConfigurationTest {
    @Test
    fun loopingVideoOpensSoftwareDecoderWithOneThread() {
        val player = VideoPlayer(onFrame = {}, onEnded = {}, looping = true)
        try {
            val videoPath = fixturePath()
            assertTrue(player.load(videoPath, emptyMap()) { 0L })

            val grabber = grabber(player)
            val codec = codec(grabber)
            assertNull(grabber.options["hwaccel"])
            assertEquals(1, codec.thread_count())
        } finally {
            runBlocking { player.closeAsync().join() }
        }
    }

    @Test
    fun regularVideoOpensWithAutomaticHardwareAndOptimalThreadSettings() {
        val player = VideoPlayer(onFrame = {}, onEnded = {})
        try {
            val videoPath = fixturePath()
            assertTrue(player.load(videoPath, emptyMap()) { 0L })

            val grabber = grabber(player)
            assertEquals("auto", grabber.options["hwaccel"])
            assertEquals(OPTIMAL_VIDEO_THREADS, grabber.videoOptions["threads"])
        } finally {
            runBlocking { player.closeAsync().join() }
        }
    }

    private fun fixturePath(): String = File(assertNotNull(javaClass.getResource("/ffmpeg/canvas-source.mp4")).toURI()).path

    private fun grabber(player: VideoPlayer): FFmpegFrameGrabber =
        VideoPlayer::class.java
            .getDeclaredField("grabber")
            .apply { isAccessible = true }
            .get(player) as FFmpegFrameGrabber

    private fun codec(grabber: FFmpegFrameGrabber) =
        FFmpegFrameGrabber::class.java
            .getDeclaredField("video_c")
            .apply { isAccessible = true }
            .get(grabber) as AVCodecContext

    private companion object {
        val OPTIMAL_VIDEO_THREADS = minOf(8, maxOf(4, Runtime.getRuntime().availableProcessors())).toString()
    }
}
