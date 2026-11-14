package com.metrolist.shared.ui.playback

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackKeyFocusTest {
    @Test
    fun pointerButtonsYieldToPlaybackButKeyboardNavigationKeepsFocus() {
        val focus = PlaybackKeyFocus()
        assertTrue(focus.allowsMediaKeys(backgroundFocused = false))
        focus.keyboardNavigation = true
        assertFalse(focus.allowsMediaKeys(backgroundFocused = false))
        assertTrue(focus.allowsMediaKeys(backgroundFocused = true))
        focus.keyboardNavigation = false
        assertTrue(focus.allowsMediaKeys(backgroundFocused = false))
    }

    @Test
    fun slidersAndTextInputRetainKeysUntilAllFocusOwnersReleaseThem() {
        val focus = PlaybackKeyFocus()
        val slider = Any()
        val otherSlider = Any()
        focus.setFocused(slider, true)
        focus.setFocused(otherSlider, true)
        focus.setFocused(slider, false)
        assertTrue(focus.retainsFocus)
        assertFalse(focus.allowsMediaKeys(backgroundFocused = true))
        val oldTextInput = Any()
        val newTextInput = Any()
        focus.setTextInputActive(oldTextInput, true)
        focus.setFocused(otherSlider, false)
        assertTrue(focus.retainsFocus)
        focus.setTextInputActive(newTextInput, true)
        focus.setTextInputActive(oldTextInput, false)
        assertTrue(focus.retainsFocus)
        focus.setTextInputActive(newTextInput, false)
        assertFalse(focus.retainsFocus)
        assertTrue(focus.allowsMediaKeys(backgroundFocused = false))
    }
}
