package com.metrolist.desktop.input

import androidx.compose.ui.input.key.Key
import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun windowsSymbolsMatchPhysicalScanCodesAcrossLayouts() {
        val azertySlashPosition =
            ShortcutKeyCode.Awt(
                AwtKeyEvent.VK_1,
                '!',
                windowsPhysicalPositionForScanCode(0x35),
                physicalPositionsAvailable = true,
            )

        assertTrue(keyCodeMatches(azertySlashPosition, Key.Slash, AwtKeyEvent.VK_SLASH))
        assertFalse(keyCodeMatches(azertySlashPosition, Key.Comma, AwtKeyEvent.VK_COMMA))
        assertFalse(
            keyCodeMatches(
                ShortcutKeyCode.Awt(AwtKeyEvent.VK_SLASH, '/', physicalPositionsAvailable = true),
                Key.Slash,
                AwtKeyEvent.VK_SLASH,
            ),
        )
        assertEquals(PhysicalKeyPosition.SLASH, windowsPhysicalPositionForScanCode(0x35))
        assertEquals(PhysicalKeyPosition.COMMA, windowsPhysicalPositionForScanCode(0x33))
        assertEquals(PhysicalKeyPosition.EQUALS, windowsPhysicalPositionForScanCode(0x0D))
        assertEquals(PhysicalKeyPosition.NUMPAD_PLUS, windowsPhysicalPositionForScanCode(0x4E))
        assertEquals(null, windowsPhysicalPositionForScanCode(0x35, WINDOWS_LOW_LEVEL_EXTENDED_KEY_FLAG))
        assertEquals(0xBF, windowsVirtualKeyForAwtCode(AwtKeyEvent.VK_SLASH))
        assertEquals(0xBC, windowsVirtualKeyForAwtCode(AwtKeyEvent.VK_COMMA))
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
