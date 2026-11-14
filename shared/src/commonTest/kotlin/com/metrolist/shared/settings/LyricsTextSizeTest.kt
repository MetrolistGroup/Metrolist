package com.metrolist.shared.settings

import kotlin.math.round
import kotlin.test.Test
import kotlin.test.assertEquals

class LyricsTextSizeTest {
    @Test
    fun pinchUsesTheSettingsRangeAndCanReverseAtEitherLimit() {
        assertEquals(30f, zoomedLyricsTextSize(20f, 1.5f))
        assertEquals(LyricsTextSizeRange.endInclusive, zoomedLyricsTextSize(36f, 2f))
        assertEquals(20f, zoomedLyricsTextSize(40f, 0.5f))
        assertEquals(LyricsTextSizeRange.start, zoomedLyricsTextSize(20f, 0.1f))
        assertEquals(15f, zoomedLyricsTextSize(10f, 1.5f))
        for (zoom in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertEquals(24f, zoomedLyricsTextSize(24f, zoom))
        }
    }

    @Test
    fun smallPinchUpdatesAccumulateBeforeRoundingTheSavedSize() {
        var size = 20f
        repeat(100) { size = zoomedLyricsTextSize(size, 1.001f) }
        assertEquals(22f, round(size))
    }
}
