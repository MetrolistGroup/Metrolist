package com.metrolist.shared.ui.components.lyrics

import androidx.compose.ui.text.TextRange
import com.metrolist.shared.api.lyrics.LyricsEditor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class LyricsEditorTextStateTest {
    @Test
    fun loadedDraftReplacesInitialTextBeforeInputIsObserved() {
        val editor = LyricsEditor("[00:01.00] Original")
        val state = LyricsEditorTextState(editor)
        editor.edit("[00:02.00] Draft", 4, 2)
        state.resetInput()
        state.syncInput()
        assertEquals("[00:02.00] Draft", editor.current.text)
        assertEquals(TextRange(4, 2), state.field.selection)
    }

    @Test
    fun toolbarEditUsesPendingInputAndReversedSelection() {
        val editor = LyricsEditor("[00:01.00] First\n[00:02.00] Second")
        val state = LyricsEditorTextState(editor)
        val secondStart = editor.current.text.indexOf('\n') + 1
        state.field.edit {
            replace(length, length, " edited")
            selection = TextRange(length, secondStart)
        }
        state.syncInput()
        editor.shift(500)
        state.resetInput()
        assertEquals("[00:01.00] First\n[00:02.50] Second edited", state.field.text.toString())
        assertEquals(TextRange(state.field.text.length, secondStart), state.field.selection)
        editor.undo()
        state.resetInput()
        assertEquals("[00:01.00] First\n[00:02.00] Second edited", state.field.text.toString())
        assertEquals(TextRange(state.field.text.length, secondStart), state.field.selection)
    }

    @Test
    fun selectionChangesDoNotCreateUndoEntriesAndInputCanBeFlushedImmediately() {
        val editor = LyricsEditor("[00:01.00] Original")
        val state = LyricsEditorTextState(editor)
        state.field.edit { selection = TextRange(8, 2) }
        state.syncInput()
        assertFalse(editor.canUndo)
        assertEquals(8, editor.current.selectionStart)
        assertEquals(2, editor.current.selectionEnd)
        state.field.edit { replace(length, length, " edited") }
        state.syncInput()
        assertEquals("[00:01.00] Original edited", editor.current.text)
    }
}
