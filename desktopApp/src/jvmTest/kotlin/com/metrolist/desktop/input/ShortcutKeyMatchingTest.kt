package com.metrolist.desktop.input

import androidx.compose.ui.input.key.Key
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.awt.event.KeyEvent as AwtKeyEvent

class ShortcutKeyMatchingTest {
    @Test
    fun lettersMatchTheProducedCharacter() {
        assertTrue(keyCodeMatches(ShortcutKeyCode.Awt(AwtKeyEvent.VK_A, 'q'), Key.Q, AwtKeyEvent.VK_Q))
        assertFalse(keyCodeMatches(ShortcutKeyCode.Awt(AwtKeyEvent.VK_Q, 'a'), Key.Q, AwtKeyEvent.VK_Q))
        assertTrue(keyCodeMatches(ShortcutKeyCode.Compose(Key.Q), Key.Q, AwtKeyEvent.VK_Q))
    }

    @Test
    fun symbolsMatchTheirPhysicalKeyCode() {
        assertTrue(keyCodeMatches(ShortcutKeyCode.Awt(AwtKeyEvent.VK_SLASH, '?'), Key.Slash, AwtKeyEvent.VK_SLASH))
        assertFalse(keyCodeMatches(ShortcutKeyCode.Awt(AwtKeyEvent.VK_1, '!'), Key.Slash, AwtKeyEvent.VK_SLASH))
    }
}
