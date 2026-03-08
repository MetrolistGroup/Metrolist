package com.metrolist.shared.ui.components.sliders

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WaveGeometryTest {
    @Test
    fun capSearchAccountsForNonuniformHorizontalTravel() {
        for (stroke in listOf(2f, 4f, 10f, 30f)) {
            val wavelength = stroke * 10f
            for (phase in 0..20) {
                val shift = wavelength * phase / 20f
                val length = wavelength * 8f
                val positionX = { distance: Float -> -wavelength + distance * distance / length }
                for (edge in listOf(stroke / 2f, wavelength * 2f)) {
                    val distance = distanceAtX(edge + shift, length, positionX)
                    val position = positionX(distance) - shift
                    assertTrue(abs(position - edge) < 0.01f, "stroke=$stroke phase=$phase edge=$edge actual=$position")
                }
            }
        }
    }

    @Test
    fun thickWaveFitsInsideTheRoundThumb() {
        for (stroke in listOf(2f, 4f, 10f)) {
            val radius = maxOf(8f, stroke)
            val amplitude = waveAmplitude(10f * stroke / 4f, stroke, radius)
            // A quadratic wave reaches half its control point's height.
            assertTrue(amplitude / 2f + stroke / 2f <= radius)
        }
        assertEquals(6f, waveAmplitude(10f, 4f, 8f))
        assertEquals(0f, waveAmplitude(4f, 10f, 10f))
    }
}
