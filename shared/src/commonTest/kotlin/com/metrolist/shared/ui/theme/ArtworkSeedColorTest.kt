package com.metrolist.shared.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlin.test.Test
import kotlin.test.assertEquals

class ArtworkSeedColorTest {
    @Test
    fun blueAccentSurvivesWhiteAndBlackArtwork() {
        val blue = Color(0xFF0088FF)
        val seed =
            selectArtworkSeedColor(128, 128) { x, _ ->
                when {
                    x < 80 -> Color.White
                    x < 112 -> Color.Black
                    else -> blue
                }.toArgb()
            }
        assertEquals(blue, seed)
    }

    @Test
    fun narrowBlueDetailsAreNotSkippedBySampling() {
        val blue = Color(0xFF0088FF)
        val seed =
            selectArtworkSeedColor(128, 128) { x, y ->
                when {
                    x % 2 == 1 -> blue
                    y < 64 -> Color.White
                    else -> Color.Black
                }.toArgb()
            }
        assertEquals(blue, seed)
    }

    @Test
    fun yellowArtworkWithRedDetailsKeepsAYellowSeed() {
        val yellow = Color(0xFFFFDD00)
        val seed =
            selectArtworkSeedColor(128, 128) { x, _ ->
                when {
                    x < 112 -> yellow
                    x < 120 -> Color.Red
                    else -> Color.Black
                }.toArgb()
            }
        assertEquals(yellow, seed)
    }

    @Test
    fun grayscaleAndTransparentArtworkUseTheMaterialFallback() {
        assertEquals(
            ArtworkFallbackSeedColor,
            selectArtworkSeedColor(128, 128) { x, _ -> Color(x / 127f, x / 127f, x / 127f).toArgb() },
        )
        assertEquals(ArtworkFallbackSeedColor, selectArtworkSeedColor(128, 128) { _, _ -> 0x000088FF })
    }
}
