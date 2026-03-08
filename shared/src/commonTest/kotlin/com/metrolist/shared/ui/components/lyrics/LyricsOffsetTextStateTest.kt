package com.metrolist.shared.ui.components.lyrics

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.text.TextRange
import kotlin.test.Test
import kotlin.test.assertEquals

class LyricsOffsetTextStateTest {
    @Test
    fun offsetUpdatesKeepCursorAndReversedSelectionWithinTheNewText() {
        val state = TextFieldState("-1250", TextRange(4, 1))
        state.updateOffsetText(1500)
        assertEquals("1500", state.text.toString())
        assertEquals(TextRange(4, 1), state.selection)
        state.updateOffsetText(50)
        assertEquals("50", state.text.toString())
        assertEquals(TextRange(2, 1), state.selection)
        state.edit { selection = TextRange.Zero }
        state.updateOffsetText(100)
        assertEquals(TextRange.Zero, state.selection)
    }
}
