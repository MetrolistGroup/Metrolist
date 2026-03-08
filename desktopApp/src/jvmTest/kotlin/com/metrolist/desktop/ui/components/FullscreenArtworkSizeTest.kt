package com.metrolist.desktop.ui.components

import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FullscreenArtworkSizeTest {
    @Test
    fun artworkMatchesTheDesignAndScalesWithWindowHeight() {
        val cases =
            listOf(
                IntSize(800, 468) to IntSize(253, 253),
                IntSize(1280, 720) to IntSize(389, 389),
                IntSize(1920, 1030) to IntSize(556, 556),
                IntSize(3840, 2060) to IntSize(1112, 1112),
            )
        for ((viewport, expected) in cases) {
            val scale = fullscreenPlayerScale(viewport.width.dp, viewport.height.dp)
            val size =
                fullscreenArtworkSize(
                    viewport.width - 48,
                    viewport.height - (140 * scale).roundToInt(),
                    (332 * scale).roundToInt(),
                    1f,
                    (556 * scale).roundToInt(),
                )
            assertEquals(expected, size)
            assertTrue(size.width < 650 * scale)
        }
    }

    @Test
    fun lyricsKeepTheDesignArtworkSizeAndNarrowPanesReduceTheWholeColumn() {
        assertEquals(1f, fullscreenPlayerScale(960.dp, 1030.dp))
        assertEquals(IntSize(556, 556), fullscreenArtworkSize(872, 890, 332, 1f, 556))
        val scale = fullscreenPlayerScale(432.dp, 1030.dp)
        assertEquals(384f, 650 * scale, 0.01f)
        assertEquals(IntSize(328, 328), fullscreenArtworkSize(384, 947, 332, 1f, (556 * scale).roundToInt()))
    }

    @Test
    fun measuredControlsStillLimitArtworkForLargeFontsInlineLyricsAndOtherPlayerStyles() {
        assertEquals(IntSize(450, 450), fullscreenArtworkSize(1872, 890, 440, 1f, 556))
        assertEquals(IntSize(120, 120), fullscreenArtworkSize(752, 400, 280, 1f, 253))
    }

    @Test
    fun videoAspectRatioFitsWithinBothDimensions() {
        val ratio = 16f / 9f
        assertEquals(IntSize(640, 360), fullscreenArtworkSize(640, 890, 332, ratio, 556))
        val size = fullscreenArtworkSize(1232, 672, 280, ratio, 556)
        assertEquals(392, size.height)
        assertTrue(size.width <= 1232)
        assertEquals(ratio, size.width.toFloat() / size.height, 0.01f)
    }

    @Test
    fun controlsCanScrollWhenTheyExceedTheViewport() {
        assertEquals(IntSize.Zero, fullscreenArtworkSize(752, 300, 340, 1f, 556))
        assertEquals(IntSize.Zero, fullscreenArtworkSize(752, 0, 340, 1f, 556))
    }

    @Test
    fun interpolatedHeightStaysWithinResizedWidthAndMeasuredControls() {
        for (height in listOf(300, 400, 500, 556)) {
            assertEquals(IntSize(300, 300), fullscreenArtworkSize(300, 890, 332, 1f, height))
            assertEquals(IntSize(120, 120), fullscreenArtworkSize(752, 400, 280, 1f, height))
            assertEquals(IntSize.Zero, fullscreenArtworkSize(752, 300, 340, 1f, height))
        }
        assertEquals(IntSize(400, 400), fullscreenArtworkSize(872, 890, 332, 1f, 400))
        assertEquals(IntSize(556, 556), fullscreenArtworkSize(872, 890, 332, 1f, 556))
    }

    @Test
    fun interpolatedVideoHeightKeepsItsAspectRatioAfterAWindowShrink() {
        for (height in listOf(250, 350, 450, 556)) {
            assertEquals(IntSize(320, 180), fullscreenArtworkSize(320, 890, 332, 16f / 9f, height))
            assertEquals(IntSize(320, 180), fullscreenArtworkSize(1232, 512, 332, 16f / 9f, height))
        }
    }

    @Test
    fun displayDensityDoesNotChangeTheArtworkProportion() {
        assertEquals(IntSize(1112, 1112), fullscreenArtworkSize(3744, 1780, 664, 1f, 1112))
    }
}
