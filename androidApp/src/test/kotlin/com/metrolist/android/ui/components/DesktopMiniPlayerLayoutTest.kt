package com.metrolist.android.ui.components

import com.metrolist.shared.settings.MiniPlayerType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopMiniPlayerLayoutTest {
    @Test
    fun phonesKeepTheMobilePlayerEvenWhenLandscapeEnablesDesktopInteractions() {
        for (style in MiniPlayerType.entries) {
            assertFalse(shouldUseDesktopMiniPlayer(useDesktopUi = true, smallestScreenWidthDp = 393, miniPlayerType = style))
            assertFalse(shouldUseDesktopMiniPlayer(useDesktopUi = true, smallestScreenWidthDp = 599, miniPlayerType = style))
        }
    }

    @Test
    fun tabletPointerWindowsHonorTheSelectedStyle() {
        for (width in listOf(600, 800)) {
            assertTrue(
                shouldUseDesktopMiniPlayer(useDesktopUi = true, smallestScreenWidthDp = width, miniPlayerType = MiniPlayerType.VINTAGE),
            )
            assertFalse(
                shouldUseDesktopMiniPlayer(useDesktopUi = true, smallestScreenWidthDp = width, miniPlayerType = MiniPlayerType.MODERN),
            )
            assertFalse(
                shouldUseDesktopMiniPlayer(useDesktopUi = true, smallestScreenWidthDp = width, miniPlayerType = MiniPlayerType.EXPRESSIVE),
            )
        }
    }

    @Test
    fun touchLayoutsKeepTheSelectedMobilePlayer() {
        for (style in MiniPlayerType.entries) {
            assertFalse(shouldUseDesktopMiniPlayer(useDesktopUi = false, smallestScreenWidthDp = 800, miniPlayerType = style))
        }
    }
}
