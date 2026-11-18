package com.metrolist.shared.ui.components

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ArtworkTextureBlurTest {
    @Test
    fun preservesUniformTranslucentColorsAndClampedEdges() {
        val color = 0x80402010.toInt()
        val pixels = IntArray(15) { color }
        blurArtworkPixels(pixels, 3, 5, 16)
        assertContentEquals(IntArray(15) { color }, pixels)
    }

    @Test
    fun transparentPixelsDoNotBleedTheirColorIntoVisiblePixels() {
        val pixels = IntArray(9) { 0x00ff0000 }
        pixels[4] = 0xff0000ff.toInt()
        blurArtworkPixels(pixels, 9, 1, 1)
        assertTrue(pixels.any { it ushr 24 in 1..254 })
        pixels.filter { it ushr 24 > 0 }.forEach {
            assertEquals(0, it and 0x00ff0000)
            assertEquals(255, it and 255)
        }
    }

    @Test
    fun zeroRadiusLeavesArtworkUnchangedAndInvalidDimensionsFail() {
        val pixels = intArrayOf(0xff123456.toInt())
        blurArtworkPixels(pixels, 1, 1, 0)
        assertEquals(0xff123456.toInt(), pixels.single())
        assertFailsWith<IllegalArgumentException> { blurArtworkPixels(pixels, 0, 1, 4) }
        assertFailsWith<IllegalArgumentException> { blurArtworkPixels(pixels, 2, 1, 4) }
    }

    @Test
    fun legacyRadiusTracksTheDisplayedCropAndTextureHeight() {
        assertEquals(16, artworkLegacyBlurRadius(150f, 600f, 128))
        assertEquals(8, artworkLegacyBlurRadius(150f, 600f, 64))
        assertTrue(artworkLegacyBlurRadius(24f, 500f, 128) < artworkLegacyBlurRadius(60f, 500f, 128))
        assertEquals(1, artworkLegacyBlurRadius(60f, 500f, 1))
    }
}
