package com.metrolist.desktop.ui.components

import androidx.compose.ui.layout.ContentScale
import com.metrolist.shared.playback.VideoColorRange
import com.metrolist.shared.playback.VideoColorSpace
import com.metrolist.shared.playback.VideoFrame
import com.metrolist.shared.playback.VideoPixelFormat
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Picture
import org.jetbrains.skia.PictureRecorder
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class VideoRendererLifecycleTest {
    @Test
    fun recordedFramesKeepTheirPixelsAfterDecoderReuseAndDisposal() {
        for (format in VideoPixelFormat.entries) {
            repeat(16) { iteration ->
                val planes = planes(format, 17 + iteration, 31 + iteration)
                val frame = frame(format, planes, iteration)
                try {
                    record(frame).use { picture ->
                        val expected = pixels(picture)
                        assertEquals(255.toByte(), expected[3])
                        planes.forEach { it.erase(0x40202020).notifyPixelsChanged() }
                        record(frame).use { reused -> assertFalse(expected.contentEquals(pixels(reused))) }
                        assertContentEquals(expected, pixels(picture), "Decoder reused $format pixels")
                        frame.close()
                        planes.forEach { it.reset() }
                        assertContentEquals(expected, pixels(picture), "Decoder disposed $format pixels")
                        Surface.makeRasterN32Premul(64, 64).use { surface ->
                            assertFalse(drawVideoFrame(frame, surface.canvas, 64f, 64f, ContentScale.Crop))
                        }
                    }
                } finally {
                    frame.close()
                    planes.forEach { it.close() }
                }
            }
        }
    }

    @Test
    fun disposalWaitsForRecordingAndThePictureCanBeReplayedAfterRelease() {
        val executor = Executors.newFixedThreadPool(2)
        val drawing = CountDownLatch(1)
        val disposing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val planes = planes(VideoPixelFormat.YUV420P, 32, 48)
        val frame = frame(VideoPixelFormat.YUV420P, planes, 0)
        try {
            val recording =
                executor.submit<Picture> {
                    var picture: Picture? = null
                    assertTrue(
                        frame.drawSafely {
                            drawing.countDown()
                            assertTrue(release.await(5, TimeUnit.SECONDS))
                            picture = record(frame)
                        },
                    )
                    assertNotNull(picture)
                }
            assertTrue(drawing.await(5, TimeUnit.SECONDS))
            val disposal =
                executor.submit {
                    disposing.countDown()
                    frame.close()
                    planes.forEach { it.reset() }
                }
            assertTrue(disposing.await(5, TimeUnit.SECONDS))
            assertFailsWith<TimeoutException> { disposal.get(100, TimeUnit.MILLISECONDS) }
            release.countDown()
            recording.get(5, TimeUnit.SECONDS).use { picture ->
                disposal.get(5, TimeUnit.SECONDS)
                assertEquals(255.toByte(), pixels(picture)[3])
            }
        } finally {
            release.countDown()
            executor.shutdown()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
            frame.close()
            planes.forEach { it.close() }
        }
    }

    @Test
    fun invalidFrameDoesNotPreventTheNextFrameFromDrawing() {
        val planes = planes(VideoPixelFormat.BGRA, 32, 48)
        try {
            Surface.makeRasterN32Premul(64, 64).use { surface ->
                val invalid = VideoFrame(32, 48, VideoPixelFormat.YUV420P)
                assertFalse(drawVideoFrame(invalid, surface.canvas, 64f, 64f, ContentScale.Crop))
                val valid = frame(VideoPixelFormat.BGRA, planes, 0)
                assertTrue(drawVideoFrame(valid, surface.canvas, 64f, 64f, ContentScale.Crop))
                valid.close()
            }
        } finally {
            planes.forEach { it.close() }
        }
    }

    private fun record(frame: VideoFrame): Picture =
        PictureRecorder().use { recorder ->
            val canvas = recorder.beginRecording(Rect.makeWH(64f, 64f))
            assertTrue(drawVideoFrame(frame, canvas, 64f, 64f, ContentScale.Crop))
            recorder.finishRecordingAsPicture()
        }

    private fun pixels(picture: Picture): ByteArray =
        Surface.makeRasterN32Premul(64, 64).use { surface ->
            surface.canvas.drawPicture(picture)
            surface.makeImageSnapshot().use { image ->
                Bitmap().use { bitmap ->
                    assertTrue(bitmap.allocPixels(ImageInfo(64, 64, ColorType.BGRA_8888, ColorAlphaType.PREMUL)))
                    assertTrue(image.readPixels(bitmap))
                    assertNotNull(bitmap.readPixels())
                }
            }
        }

    private fun planes(
        format: VideoPixelFormat,
        width: Int,
        height: Int,
    ): List<Bitmap> {
        val count =
            when (format) {
                VideoPixelFormat.BGRA -> 1
                VideoPixelFormat.NV12 -> 2
                VideoPixelFormat.YUV420P -> 3
            }
        return List(count) { index ->
            Bitmap().apply {
                val w = if (index == 0) width else (width + 1) / 2
                val h = if (index == 0) height else (height + 1) / 2
                val colorType = if (format == VideoPixelFormat.YUV420P) ColorType.ALPHA_8 else ColorType.BGRA_8888
                assertTrue(allocPixels(ImageInfo(w, h, colorType, ColorAlphaType.PREMUL)))
                erase(if (index == 0) 0xff52799b.toInt() else 0xff808080.toInt())
            }
        }
    }

    private fun frame(
        format: VideoPixelFormat,
        planes: List<Bitmap>,
        iteration: Int,
    ) = VideoFrame(
        width = planes[0].width,
        height = planes[0].height,
        format = format,
        colorSpace = VideoColorSpace.entries[iteration % VideoColorSpace.entries.size],
        colorRange = VideoColorRange.entries[iteration % VideoColorRange.entries.size],
        planeY = planes[0].takeUnless { format == VideoPixelFormat.BGRA },
        planeU = planes.getOrNull(1).takeIf { format == VideoPixelFormat.YUV420P },
        planeV = planes.getOrNull(2),
        planeUV = planes.getOrNull(1).takeIf { format == VideoPixelFormat.NV12 },
        bgraBitmap = planes[0].takeIf { format == VideoPixelFormat.BGRA },
    )
}
