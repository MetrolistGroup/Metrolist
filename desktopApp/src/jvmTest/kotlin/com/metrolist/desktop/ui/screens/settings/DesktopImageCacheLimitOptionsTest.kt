package com.metrolist.desktop.ui.screens.settings

import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopImageCacheLimitOptionsTest {
    @Test
    fun restoredNonPresetImageCacheLimitKeepsItsSliderPosition() {
        val savedLimit = 1536L
        val options = desktopImageCacheLimitOptions(savedLimit.toInt())
        val sliderIndex = options.indexOf(savedLimit).coerceAtLeast(0)
        assertEquals(savedLimit, options[sliderIndex])
        assertEquals(1024L, options[sliderIndex - 1])
        assertEquals(2048L, options[sliderIndex + 1])
    }

    @Test
    fun regularPresetsStayOrderedAndUnique() {
        val presets = listOf(0L, 128L, 256L, 512L, 1024L, 2048L, 4096L, 8192L)
        assertEquals(presets, desktopImageCacheLimitOptions(0))
        assertEquals(presets, desktopImageCacheLimitOptions(512))
        assertEquals(presets + 16_384L, desktopImageCacheLimitOptions(16_384))
    }
}
