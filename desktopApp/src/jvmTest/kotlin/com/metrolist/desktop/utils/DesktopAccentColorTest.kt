package com.metrolist.desktop.utils

import androidx.compose.ui.graphics.Color
import com.metrolist.shared.ui.theme.DefaultThemeColor
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopAccentColorTest {
    @Test
    fun windowsAccentCannotReplaceArtworkOrACustomColorMatchingTheDefault() {
        for (color in listOf(DefaultThemeColor, Color.Blue, Color.Yellow)) {
            assertEquals(
                color,
                resolveDesktopSeedColor(color, useSystemAccent = false, isWindows = true) { error("Unexpected registry read") },
            )
        }
    }

    @Test
    fun systemAccentConvertsWindowsAbgrAndKeepsTheConfiguredColorOnFailure() {
        assertEquals(
            Color(0xFF123456),
            resolveDesktopSeedColor(DefaultThemeColor, useSystemAccent = true, isWindows = true) { 0xFF563412.toInt() },
        )
        assertEquals(
            DefaultThemeColor,
            resolveDesktopSeedColor(DefaultThemeColor, useSystemAccent = true, isWindows = true) { error("Registry unavailable") },
        )
        assertEquals(
            DefaultThemeColor,
            resolveDesktopSeedColor(DefaultThemeColor, useSystemAccent = true, isWindows = false) { error("Unexpected registry read") },
        )
    }
}
