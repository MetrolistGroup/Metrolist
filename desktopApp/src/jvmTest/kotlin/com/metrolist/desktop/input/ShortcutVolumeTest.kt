package com.metrolist.desktop.input

import androidx.compose.ui.input.key.Key
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.awt.event.KeyEvent as AwtKeyEvent

class ShortcutVolumeTest {
    @Test
    fun regularAndNumpadMinusAreVolumeDecreaseShortcuts() {
        for (key in listOf(Key.Minus, Key.NumPadSubtract, AwtKeyEvent.VK_MINUS, AwtKeyEvent.VK_SUBTRACT)) {
            assertTrue(isVolumeDecreaseShortcut(key))
        }
        for (key in listOf(Key.Equals, Key.NumPadAdd, AwtKeyEvent.VK_EQUALS, AwtKeyEvent.VK_ADD)) {
            assertFalse(isVolumeDecreaseShortcut(key))
        }
    }
}
