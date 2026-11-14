package com.metrolist.shared.playback

import com.metrolist.shared.playback.ffmpeg.FFmpegFrameGrabber
import org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_NV12
import org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_YUV420P
import org.bytedeco.ffmpeg.global.avutil.av_frame_alloc
import org.bytedeco.ffmpeg.global.avutil.av_frame_get_buffer
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.Pointer
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class VideoPointerRenderingTest {
    @Test
    fun scalingSmallPortraitFramesStaysInsideVideoPlaneStorage() {
        FFmpegFrameGrabber.tryLoad()
        for (format in listOf(AV_PIX_FMT_YUV420P, AV_PIX_FMT_NV12)) {
            val grabber = FFmpegFrameGrabber("unused")
            val source = av_frame_alloc().width(720).height(1280).format(format)
            assertTrue(av_frame_get_buffer(source, 32) >= 0)
            val sourcePlanes = if (format == AV_PIX_FMT_NV12) 2 else 3
            repeat(sourcePlanes) { index ->
                source
                    .data(index)
                    .capacity(source.linesize(index).toLong() * if (index == 0) 1280 else 640)
                    .fill<Pointer>(if (index == 0) 100 else 128)
            }
            FFmpegFrameGrabber::class.java
                .getDeclaredField("picture")
                .apply { isAccessible = true }
                .set(grabber, source)
            try {
                for ((width, height) in listOf(4 to 6, 6 to 10, 318 to 564)) {
                    val planes = List(3) { Bitmap() }
                    val buffers = mutableListOf<BytePointer>()
                    try {
                        planes.forEachIndexed { index, bitmap ->
                            val planeWidth = if (index == 0) width else (width + 1) / 2
                            val planeHeight = if (index == 0) height else (height + 1) / 2
                            assertTrue(
                                bitmap.allocVideoPixels(ImageInfo(planeWidth, planeHeight, ColorType.ALPHA_8, ColorAlphaType.PREMUL)),
                            )
                            // Guard the same layout as the bitmap without risking corruption of the test process heap.
                            val size = bitmap.rowBytes.toLong() * (planeHeight - 1) + planeWidth
                            buffers += BytePointer(size + 64).apply { fill<Pointer>(197) }
                        }
                        assertNotNull(
                            grabber.processPlanarImageToAddresses(
                                buffers[0].address(),
                                planes[0].rowBytes,
                                buffers[1].address(),
                                planes[1].rowBytes,
                                buffers[2].address(),
                                planes[2].rowBytes,
                                width,
                                height,
                            ),
                        )
                        planes.forEachIndexed { index, bitmap ->
                            val size = bitmap.rowBytes.toLong() * (bitmap.height - 1) + bitmap.width
                            repeat(64) { offset ->
                                assertEquals(
                                    197.toByte(),
                                    buffers[index].get(size + offset),
                                    "format $format ${width}x$height plane $index offset $offset",
                                )
                            }
                        }
                    } finally {
                        buffers.forEach { it.close() }
                        planes.forEach { it.close() }
                    }
                }
            } finally {
                grabber.release()
            }
        }
    }
}
