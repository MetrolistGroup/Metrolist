package com.metrolist.music.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class CoilBitmapLoaderTest {
    @Test
    fun `large artwork is decoded at a sampled size`() {
        assertEquals(8, calculateArtworkInSampleSize(width = 4_000, height = 3_000))
    }

    @Test
    fun `portrait artwork is sampled based on its longest edge`() {
        assertEquals(8, calculateArtworkInSampleSize(width = 1_000, height = 4_000))
    }

    @Test
    fun `small artwork keeps full decode resolution`() {
        assertEquals(1, calculateArtworkInSampleSize(width = 512, height = 256))
    }

    @Test
    fun `invalid dimensions use the safe default sample size`() {
        assertEquals(1, calculateArtworkInSampleSize(width = 0, height = 1_000))
        assertEquals(1, calculateArtworkInSampleSize(width = 1_000, height = 1_000, maxDimension = 0))
    }
}
