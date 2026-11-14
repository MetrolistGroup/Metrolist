package com.metrolist.desktop.utils

import kotlin.test.Test
import kotlin.test.assertEquals

class SystemVolumeTest {
    @Test
    fun mapsMprisVolumeToTheAppsClampedFloatVolume() {
        val applied = mutableListOf<Float>()
        for (requested in listOf(0.5, -0.4, 2.0, 0.15)) {
            applySystemVolume(requested, applied::add)
        }
        assertEquals(listOf(0.5f, 0f, 1f, 0.15f), applied)
    }

    @Test
    fun rejectsNonFiniteVolumesWithoutChangingAppState() {
        for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            applySystemVolume(value) { error("Invalid app volume") }
        }
    }
}
