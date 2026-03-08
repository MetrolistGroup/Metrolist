package com.metrolist.android.ui

import com.metrolist.android.ui.components.CanvasFrameSampler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CanvasFrameSamplerTest {
    @Test
    fun returnsNullForInvalidDimensions() {
        val sampler = CanvasFrameSampler(intervalMs = 250L)
        assertNull(sampler.sampleSize(nowMs = 1000L, width = 0, height = 100))
        assertNull(sampler.sampleSize(nowMs = 1000L, width = 100, height = 0))
        assertNull(sampler.sampleSize(nowMs = 1000L, width = -1, height = -1))
    }

    @Test
    fun throttlesSamplesWithinInterval() {
        val sampler = CanvasFrameSampler(intervalMs = 250L)
        val first = sampler.sampleSize(nowMs = 1000L, width = 1920, height = 1080)
        assertNotNull(first)
        assertEquals(48 to 27, first)

        assertNull(sampler.sampleSize(nowMs = 1100L, width = 1920, height = 1080))
        assertNull(sampler.sampleSize(nowMs = 1249L, width = 1920, height = 1080))

        val second = sampler.sampleSize(nowMs = 1250L, width = 1920, height = 1080)
        assertNotNull(second)
        assertEquals(48 to 27, second)
    }

    @Test
    fun doesNotThrottleWhenIntervalIsZero() {
        val sampler = CanvasFrameSampler(intervalMs = 0L)
        assertNotNull(sampler.sampleSize(nowMs = 1000L, width = 1920, height = 1080))
        assertNotNull(sampler.sampleSize(nowMs = 1001L, width = 1920, height = 1080))
        assertNotNull(sampler.sampleSize(nowMs = 1002L, width = 1920, height = 1080))
    }

    @Test
    fun resetAllowsImmediateResampling() {
        val sampler = CanvasFrameSampler(intervalMs = 250L)
        assertNotNull(sampler.sampleSize(nowMs = 1000L, width = 1000, height = 1000))
        assertNull(sampler.sampleSize(nowMs = 1050L, width = 1000, height = 1000))

        sampler.reset()
        val afterReset = sampler.sampleSize(nowMs = 1050L, width = 1000, height = 1000)
        assertNotNull(afterReset)
        assertEquals(48 to 48, afterReset)
    }

    @Test
    fun preservesAspectRatioForVerticalVideo() {
        val sampler = CanvasFrameSampler(intervalMs = 250L)
        val size = sampler.sampleSize(nowMs = 1000L, width = 1080, height = 1920)
        assertNotNull(size)
        assertEquals(27 to 48, size)
    }

    @Test
    fun detectsLetterboxAndCalculatesZoomFactor() {
        val sampler = CanvasFrameSampler(intervalMs = 250L)
        val w = 48
        val h = 27
        val pixels = IntArray(w * h)

        val activeColor = -0x1f1f20
        val blackColor = -0x1000000
        for (y in 0 until h) {
            val color = if (y in 4..22) activeColor else blackColor
            for (x in 0 until w) {
                pixels[y * w + x] = color
            }
        }

        val zoom = sampler.detectLetterboxZoom(pixels, w, h)
        assertEquals(27f / 19f, zoom, 0.01f)
    }

    @Test
    fun returnsNoZoomForStandardVideoWithoutLetterbox() {
        val sampler = CanvasFrameSampler(intervalMs = 250L)
        val w = 48
        val h = 27
        val pixels = IntArray(w * h) { -0x1f1f20 }
        val zoom = sampler.detectLetterboxZoom(pixels, w, h)
        assertEquals(1.0f, zoom, 0.01f)
    }

    @Test
    fun returnsNoZoomForEntirelyDarkFrame() {
        val sampler = CanvasFrameSampler(intervalMs = 250L)
        val w = 48
        val h = 27
        val pixels = IntArray(w * h) { -0x1000000 }
        val zoom = sampler.detectLetterboxZoom(pixels, w, h)
        assertEquals(1.0f, zoom, 0.01f)
    }
}
