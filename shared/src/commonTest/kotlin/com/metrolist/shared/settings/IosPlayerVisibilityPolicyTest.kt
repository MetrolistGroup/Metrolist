package com.metrolist.shared.settings

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IosPlayerVisibilityPolicyTest {
    @Test
    fun collapsedPlayerRestoresStatusBarWithoutChangingRetainedLyricsMode() {
        assertTrue(deviceSettings(expanded = true).hideStatusBar)
        val collapsed = deviceSettings(expanded = false)
        assertFalse(collapsed.hideStatusBar)
        assertFalse(collapsed.keepScreenAwake)
        assertTrue(deviceSettings(expanded = true).hideStatusBar)
    }

    @Test
    fun expandedPlayerHidesStatusOnlyWhenRelevantModeIsEnabled() {
        assertFalse(deviceSettings(expanded = true, hideWithLyrics = false).hideStatusBar)
        assertFalse(deviceSettings(expanded = true, fullscreenLyrics = false).hideStatusBar)
        assertTrue(deviceSettings(expanded = true, fullscreenLyrics = false, immersive = true).hideStatusBar)
        assertFalse(deviceSettings(expanded = false, fullscreenLyrics = false, immersive = true).hideStatusBar)
    }

    @Test
    fun suppressedLyricsDoNotHideStatusBarOrKeepScreenAwake() {
        val suppressed = deviceSettings(expanded = true, lyrics = false, fullscreenLyrics = false)
        assertFalse(suppressed.hideStatusBar)
        assertFalse(suppressed.keepScreenAwake)
        assertTrue(deviceSettings(expanded = true).keepScreenAwake)
    }

    private fun deviceSettings(
        expanded: Boolean,
        lyrics: Boolean = true,
        fullscreenLyrics: Boolean = true,
        immersive: Boolean = false,
        hideWithLyrics: Boolean = true,
    ): IosDeviceSettings =
        mapIosDeviceSettings(
            keepScreenOnMode = KeepScreenOnMode.LYRICS,
            expandedPlayerVisible = expanded,
            lyricsVisible = lyrics,
            lyricsFullscreenVisible = fullscreenLyrics,
            isPlaying = true,
            immersivePlayer = immersive,
            hideStatusBarInLyricsFullscreen = hideWithLyrics,
        )
}
