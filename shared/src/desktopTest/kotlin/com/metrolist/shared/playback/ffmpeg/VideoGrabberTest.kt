package com.metrolist.shared.playback.ffmpeg

import com.metrolist.shared.playback.restartLoop
import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.avutil.Free_Pointer_BytePointer
import org.bytedeco.ffmpeg.global.avcodec.avcodec_alloc_context3
import org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_BGRA
import org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_P010LE
import org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_VAAPI
import org.bytedeco.ffmpeg.global.avutil.av_buffer_create
import org.bytedeco.ffmpeg.global.avutil.av_frame_alloc
import org.bytedeco.ffmpeg.global.avutil.av_frame_get_buffer
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.IntPointer
import org.bytedeco.javacpp.Pointer
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class VideoGrabberTest {
    @Test
    fun hardwareFormatCallbackRemainsAliveUntilCodecShutdownCompletes() {
        FFmpegFrameGrabber.tryLoad()
        val grabber = FFmpegFrameGrabber("unused")
        val codec = assertNotNull(avcodec_alloc_context3(null))
        val formatCallback =
            object : AVCodecContext.Get_format_AVCodecContext_IntPointer() {
                override fun call(
                    context: AVCodecContext?,
                    formats: IntPointer?,
                ): Int = AV_PIX_FMT_BGRA
            }.retainReference<AVCodecContext.Get_format_AVCodecContext_IntPointer>()
        var retainedDuringCodecClose = false
        val freeCallback =
            object : Free_Pointer_BytePointer() {
                override fun call(
                    opaque: Pointer?,
                    data: BytePointer?,
                ) {
                    retainedDuringCodecClose = formatCallback.referenceCount() > 0
                }
            }.retainReference<Free_Pointer_BytePointer>()
        try {
            BytePointer(1L).use { data ->
                codec.hw_device_ctx(assertNotNull(av_buffer_create(data, 1L, freeCallback, null, 0)))
                codec.get_format(formatCallback)
                field("video_c").set(grabber, codec)
                field("get_format_callback").set(grabber, formatCallback)
                grabber.release()
                assertTrue(retainedDuringCodecClose, "Native codec destruction must precede callback release")
                assertTrue(formatCallback.referenceCount() <= 0)
            }
        } finally {
            grabber.release()
            freeCallback.releaseReference()
        }
    }

    @Test
    fun directConversionDoesNotAllocateRgbStorageAndRewindKeepsDecoder() {
        val directory = createTempDirectory("video-grabber").toFile()
        val file = File(directory, "video.y4m")
        file.writeBytes(
            "YUV4MPEG2 W64 H36 F25:1 Ip A1:1 C420jpeg\n".toByteArray() +
                (0 until 3)
                    .flatMap { index ->
                        (
                            "FRAME\n".toByteArray() + ByteArray(64 * 36) { (100 + index).toByte() } +
                                ByteArray(64 * 36 / 2) { 128.toByte() }
                        ).toList()
                    }.toByteArray(),
        )
        val grabber = FFmpegFrameGrabber(file).apply { pixelFormat = AV_PIX_FMT_BGRA }
        try {
            grabber.start()
            assertNull(field("image_ptr").get(grabber))
            val codec = field("video_c").get(grabber)
            repeat(3) {
                field("hardwarePictureValid").setBoolean(grabber, true)
                assertEquals(Frame.Type.VIDEO, grabber.grabFrame(false, true, false, false, false)?.type)
                assertFalse(field("hardwarePictureValid").getBoolean(grabber))
            }
            assertNull(grabber.grabFrame(false, true, false, false, false))
            repeat(3) {
                assertEquals(0L, assertNotNull(restartLoop(grabber)).timestamp)
                assertSame(codec, field("video_c").get(grabber))
                BytePointer(64L * 36 * 4).use { output ->
                    assertTrue(grabber.processImageToAddress(output.address(), 64 * 4, 64, 36))
                }
                assertNull(field("image_ptr").get(grabber))
                repeat(2) { assertNotNull(grabber.grabFrame(false, true, false, false, false)) }
                assertNull(grabber.grabFrame(false, true, false, false, false))
            }
            grabber.setTimestamp(40_000L, true)
            assertEquals(Frame.Type.VIDEO, assertNotNull(grabber.grabFrame(false, true, true, false, false)).type)
            assertNotNull(field("image_ptr").get(grabber))
        } finally {
            grabber.release()
            directory.deleteRecursively()
        }
    }

    @Test
    fun unsupportedPlanarFormatReusesDownloadedHardwareFrameForBgra() {
        FFmpegFrameGrabber.tryLoad()
        val grabber = FFmpegFrameGrabber("unused").apply { pixelFormat = AV_PIX_FMT_BGRA }
        val hardware = assertNotNull(av_frame_alloc()).format(AV_PIX_FMT_VAAPI).width(64).height(36)
        val downloaded = assertNotNull(av_frame_alloc()).format(AV_PIX_FMT_P010LE).width(64).height(36)
        assertTrue(av_frame_get_buffer(downloaded, 32) >= 0)
        field("picture").set(grabber, hardware)
        field("hardware_picture").set(grabber, downloaded)
        field("hardwarePictureValid").setBoolean(grabber, true)
        try {
            BytePointer(64L * 36 * 4).use { output ->
                assertNull(
                    grabber.processPlanarImageToAddresses(
                        output.address(),
                        64,
                        output.address(),
                        32,
                        output.address(),
                        32,
                        64,
                        36,
                    ),
                )
                // The dummy hardware frame cannot be transferred: BGRA must reuse the downloaded P010.
                assertTrue(grabber.processImageToAddress(output.address(), 64 * 4, 64, 36))
                assertSame(downloaded, field("hardware_picture").get(grabber) as AVFrame)
            }
            grabber.setTimestamp(0L, false)
            assertFalse(field("hardwarePictureValid").getBoolean(grabber))
        } finally {
            grabber.release()
        }
    }

    @Test
    fun audioDecoderDoesNotOpenVideoForMuxedInput() {
        val fixture = assertNotNull(javaClass.getResource("/ffmpeg/canvas-source.mp4"))
        val decoder = AudioDecoder(File(fixture.toURI()).path)
        try {
            assertFalse(decoder.frameGrabber.hasVideo())
            assertNull(field("video_c").get(decoder.frameGrabber))
            assertNotNull(decoder.readPCMFrame())
        } finally {
            decoder.frameGrabber.release()
        }
    }

    private fun field(name: String) = FFmpegFrameGrabber::class.java.getDeclaredField(name).apply { isAccessible = true }
}
