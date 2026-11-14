package com.metrolist.shared.playback.ffmpeg

import com.metrolist.shared.youtube.playback.PlaybackEventLog
import org.bytedeco.ffmpeg.global.avutil.AVERROR_INVALIDDATA
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FFmpegFrameGrabberTest {
    @Test(timeout = 30_000)
    fun mediaReadFailureRecordsNativeCodeWithoutChangingEofHandling() {
        val pcmSize = 48_000 * 2 * 10
        val bytes =
            ByteBuffer
                .allocate(44 + pcmSize)
                .order(ByteOrder.LITTLE_ENDIAN)
                .apply {
                    put("RIFF".toByteArray())
                    putInt(36 + pcmSize)
                    put("WAVEfmt ".toByteArray())
                    putInt(16)
                    putShort(1)
                    putShort(1)
                    putInt(48_000)
                    putInt(96_000)
                    putShort(2)
                    putShort(16)
                    put("data".toByteArray())
                    putInt(pcmSize)
                }.array()
        var failRead = false
        val input =
            object : ByteArrayInputStream(bytes) {
                override fun read(
                    buffer: ByteArray,
                    offset: Int,
                    length: Int,
                ): Int {
                    if (failRead) throw IOException("synthetic media read failure")
                    return super.read(buffer, offset, length)
                }
            }
        val grabber = FFmpegFrameGrabber(input, 0)
        PlaybackEventLog.clear()
        try {
            grabber.start(false)
            failRead = true
            var frames = 0
            while (grabber.grabSamples() != null) {
                assertTrue(++frames < 300, "The failed input must eventually drain")
            }
            assertTrue(PlaybackEventLog.exportText().contains("av_read_frame() error -1:"))
        } finally {
            grabber.release()
            PlaybackEventLog.clear()
        }
    }

    @Test(timeout = 30_000)
    fun failedCustomInputPreservesDemuxerError() {
        val error =
            assertFailsWith<FFmpegFrameGrabber.Companion.Exception> {
                AudioDecoder(ByteArrayInputStream(ByteArray(32)), maximumSize = 0)
            }

        assertTrue(error.message.orEmpty().contains("avformat_open_input() error $AVERROR_INVALIDDATA:"), error.message)
        assertTrue(error.message.orEmpty().contains("Invalid data found when processing input"), error.message)
    }

    @Test(timeout = 30_000)
    fun failedCustomInputReleasesItsCallbackRegistration() {
        val registrations = inputStreams()
        val initialSize = registrations.size

        repeat(3) {
            var closed = false
            val input =
                object : ByteArrayInputStream(ByteArray(32)) {
                    override fun close() {
                        closed = true
                        super.close()
                    }
                }

            assertFailsWith<FFmpegFrameGrabber.Companion.Exception> { AudioDecoder(input, maximumSize = 0) }

            assertTrue(closed)
            assertEquals(initialSize, registrations.size)
        }
    }

    @Test(timeout = 30_000)
    fun customInputSupportsSeekAndReleasesItsCallbackRegistration() {
        val registrations = inputStreams()
        val initialSize = registrations.size
        val bytes = assertNotNull(javaClass.getResourceAsStream("/ffmpeg/aac-140.m4a")).use { it.readBytes() }
        val decoder = AudioDecoder(ByteArrayInputStream(bytes), maximumSize = bytes.size)
        try {
            assertEquals(initialSize + 1, registrations.size)
            assertNotNull(decoder.readPCMFrame())
            decoder.seekTo(1_000L)
            assertNotNull(decoder.readPCMFrame())
            val seekPosition = decoder.getCurrentPosition()
            assertTrue(seekPosition in 950L..1_050L, "Forward seek landed at ${seekPosition}ms")
            decoder.seekTo(0L)
            assertNotNull(decoder.readPCMFrame())
            assertTrue(decoder.getCurrentPosition() < 100L)
        } finally {
            decoder.close()
        }
        assertEquals(initialSize, registrations.size)
    }

    private fun inputStreams(): Map<*, *> =
        FFmpegFrameGrabber::class.java
            .getDeclaredField("inputStreams")
            .apply { isAccessible = true }
            .get(null) as Map<*, *>
}
