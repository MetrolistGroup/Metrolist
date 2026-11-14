package com.metrolist.android.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopMiniPlayerLayoutTest {
    @Test
    fun phonesKeepTheMobilePlayerEvenWhenLandscapeEnablesDesktopInteractions() {
        assertFalse(shouldUseDesktopMiniPlayer(useDesktopUi = true, smallestScreenWidthDp = 393))
        assertFalse(shouldUseDesktopMiniPlayer(useDesktopUi = true, smallestScreenWidthDp = 599))
    }

    @Test
    fun tabletSizedPointerWindowsKeepTheDesktopPlayer() {
        assertTrue(shouldUseDesktopMiniPlayer(useDesktopUi = true, smallestScreenWidthDp = 600))
        assertTrue(shouldUseDesktopMiniPlayer(useDesktopUi = true, smallestScreenWidthDp = 800))
    }

    @Test
    fun touchLayoutsKeepTheSelectedMobilePlayer() {
        assertFalse(shouldUseDesktopMiniPlayer(useDesktopUi = false, smallestScreenWidthDp = 800))
    }
}
