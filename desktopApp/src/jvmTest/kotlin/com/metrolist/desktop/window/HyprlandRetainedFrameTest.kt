package com.metrolist.desktop.window

import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.IRect
import org.jetbrains.skia.ImageInfo
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HyprlandRetainedFrameTest {
    @Test
    fun retainedFrameOwnsItsPixelsAfterTheScreenshotCloses() {
        val image =
            Bitmap().use { bitmap ->
                assertTrue(bitmap.allocPixels(ImageInfo(2, 2, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL), 32))
                bitmap.erase(0xff123456.toInt())
                bitmap.erase(0x80402010.toInt(), IRect.makeXYWH(1, 1, 1, 1))
                assertNotNull(copyHyprlandFrame(bitmap)).also {
                    assertIs<DataBufferInt>(it.raster.dataBuffer)
                    bitmap.erase(0)
                }
            }

        assertEquals(0xff123456.toInt(), image.getRGB(0, 0))
        assertEquals(0xff123456.toInt(), image.getRGB(1, 0))
        assertEquals(0xff123456.toInt(), image.getRGB(0, 1))
        assertEquals(0x80402010.toInt(), image.getRGB(1, 1))
        assertEquals(BufferedImage.TYPE_INT_ARGB_PRE, image.type)
    }
}
