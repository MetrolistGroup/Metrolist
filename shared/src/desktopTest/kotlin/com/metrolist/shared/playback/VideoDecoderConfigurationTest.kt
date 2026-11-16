package com.metrolist.shared.playback

import com.metrolist.shared.playback.ffmpeg.FFmpegFrameGrabber
import kotlinx.coroutines.runBlocking
import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VideoDecoderConfigurationTest {
    @Test
    fun loopingVideoOpensSoftwareDecoderWithOneThread() {
        val directory = createTempDirectory("looping-video-decoder").toFile()
        val player = VideoPlayer(onFrame = {}, onEnded = {}, looping = true)
        try {
            val file = videoFile(directory)
            assertTrue(player.load(file.path, emptyMap()) { 0L })

            val grabber = grabber(player)
            val codec = codec(grabber)
            assertFalse(grabber.isHardwareAccelerated)
            assertEquals(1, codec.thread_count())
        } finally {
            runBlocking { player.closeAsync().join() }
            directory.deleteRecursively()
        }
    }

    @Test
    fun regularVideoOpensWithAutomaticHardwareAndOptimalThreadSettings() {
        val directory = createTempDirectory("regular-video-decoder").toFile()
        val player = VideoPlayer(onFrame = {}, onEnded = {})
        try {
            val file = videoFile(directory)
            assertTrue(player.load(file.path, emptyMap()) { 0L })

            val grabber = grabber(player)
            assertEquals("auto", grabber.options["hwaccel"])
            assertEquals(OPTIMAL_VIDEO_THREADS, grabber.videoOptions["threads"])
        } finally {
            runBlocking { player.closeAsync().join() }
            directory.deleteRecursively()
        }
    }

    private fun videoFile(directory: File): File =
        File(directory, "video.y4m").apply {
            writeBytes(
                "YUV4MPEG2 W64 H36 F25:1 Ip A1:1 C420jpeg\nFRAME\n".toByteArray() +
                    ByteArray(64 * 36) { 100 } + ByteArray(64 * 36 / 2) { 128.toByte() },
            )
        }

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
