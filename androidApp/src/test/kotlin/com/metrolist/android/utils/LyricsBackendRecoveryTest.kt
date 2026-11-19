package com.metrolist.android.utils

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import javax.imageio.ImageIO
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class LyricsBackendRecoveryTest {
    @Test fun timings() {
        assertEquals(144,lyricsVideoFrameCount(6000,24))
        assertEquals(145,lyricsVideoFrameCount(6001,24))
        assertEquals(2,lyricsVideoFrameCount(1,24))
        assertEquals(5_958_333L,lyricsFrameTimeUs(143,24))
        assertEquals(6_000_000L,lyricsFrameTimeUs(144,24))
    }
    @Test fun paddedYuvRed() {
        val y = ByteBuffer.allocate(16); val u = ByteBuffer.allocate(8); val v = ByteBuffer.allocate(8)
        y.position(2); u.position(1); v.position(1)
        writeLyricsYuv420(IntArray(4) { 0xffff0000.toInt() },2,2,listOf(LyricsYuvPlane(y,4,1),LyricsYuvPlane(u,3,2),LyricsYuvPlane(v,3,2)))
        assertEquals(82,y.get(2).toInt() and 255); assertEquals(82,y.get(6).toInt() and 255)
        assertEquals(90,u.get(1).toInt() and 255); assertEquals(240,v.get(1).toInt() and 255)
        assertEquals(2,y.position()); assertEquals(1,u.position())
    }
    @Test fun gifDecodesAcrossDictionaryBoundaries() {
        val random = Random(902)
        for (side in listOf(1,17,64,320)) {
            val pixels = IntArray(side*side) { lyricsGifPaletteColor(random.nextInt(256)) }
            val stream = ByteArrayOutputStream()
            val writer = LyricsGifWriter(stream,side,side)
            writer.frame(pixels,10); writer.frame(IntArray(pixels.size) { 0x00ff00 },10); writer.finish()
            ImageIO.createImageInputStream(ByteArrayInputStream(stream.toByteArray())).use { input ->
                val reader = ImageIO.getImageReadersByFormatName("gif").next()
                try {
                    reader.input = input
                    assertEquals(2,reader.getNumImages(true))
                    val first = reader.read(0)
                    for (i in pixels.indices) assertEquals(lyricsGifPaletteColor(lyricsGifPaletteIndex(pixels[i])),first.getRGB(i%side,i/side) and 0xffffff)
                    assertEquals(0x00ff00,reader.read(1).getRGB(0,0) and 0xffffff)
                } finally { reader.dispose() }
            }
        }
    }
    @Test fun grayRemainsNeutral() {
        for (g in 0..255) {
            val rgb = lyricsGifPaletteColor(lyricsGifPaletteIndex((g shl 16) or (g shl 8) or g))
            assertEquals(rgb ushr 16 and 255,rgb and 255)
            assertEquals(rgb ushr 8 and 255,rgb and 255)
            assertTrue(kotlin.math.abs((rgb and 255)-g) <= 4)
        }
    }
    @Test fun capIsEnforced() {
        val output = ByteArrayOutputStream()
        try { LyricsGifWriter(output,320,320,20); fail("cap ignored") } catch (_: IllegalStateException) {}
        assertTrue(output.size() <= 20)
    }
}
