package com.metrolist.shared.ui.player

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

class PlayerLayoutModeTest {
    @Test
    fun landscapeTabletsUseTwoPanesAtCommonDensities() {
        val screens = listOf(1280 to 800, 1920 to 1200, 2560 to 1600)
        for ((width, height) in screens) {
            for (density in listOf(1f, 1.5f, 2f)) {
                assertEquals(
                    PlayerLayoutMode.LANDSCAPE,
                    playerLayoutMode((width / density).dp, (height / density).dp),
                    "$width x $height at $density density",
                )
            }
        }
    }

    @Test
    fun landscapePhonesAndWideSplitWindowsUseTwoPanes() {
        for ((width, height) in listOf(800 to 360, 960 to 600, 640 to 400, 600 to 320, 600 to 480)) {
            assertEquals(PlayerLayoutMode.LANDSCAPE, playerLayoutMode(width.dp, height.dp))
        }
    }

    @Test
    fun portraitAndNarrowOrShortSplitWindowsKeepTheScrollableStack() {
        for ((width, height) in listOf(360 to 800, 800 to 1280, 600 to 600, 599 to 400, 800 to 319, 480 to 800)) {
            assertEquals(PlayerLayoutMode.PORTRAIT, playerLayoutMode(width.dp, height.dp))
        }
    }
}
