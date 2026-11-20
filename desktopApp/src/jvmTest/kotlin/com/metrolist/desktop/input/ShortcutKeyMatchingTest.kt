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

    @Test
    fun controlModifiedLetterEventsMatchTheirLetter() {
        val shortcuts =
            listOf(
                Triple('\u000B', Key.K, AwtKeyEvent.VK_K),
                Triple('\u0006', Key.F, AwtKeyEvent.VK_F),
                Triple('\u000C', Key.L, AwtKeyEvent.VK_L),
            )

        shortcuts.forEach { (controlCharacter, key, keyCode) ->
            val normalizedCharacter = normalizeControlCharacter(controlCharacter, controlDown = true)
            assertTrue(keyCodeMatches(ShortcutKeyCode.Awt(AwtKeyEvent.VK_UNDEFINED, normalizedCharacter), key, keyCode))
        }

        assertFalse(keyCodeMatches(ShortcutKeyCode.Awt(AwtKeyEvent.VK_UNDEFINED, '\u000B'), Key.K, AwtKeyEvent.VK_K))
    }
}
