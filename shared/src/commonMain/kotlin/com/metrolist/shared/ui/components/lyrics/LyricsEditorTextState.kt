package com.metrolist.shared.ui.components.lyrics

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.text.TextRange
import com.metrolist.shared.api.lyrics.LyricsEditor

internal class LyricsEditorTextState(
    private val editor: LyricsEditor,
) {
    val field = TextFieldState(editor.current.text, TextRange(editor.current.selectionStart, editor.current.selectionEnd))

    fun syncInput() {
        val text = field.text.toString()
        val selection = field.selection
        if (text != editor.current.text || selection.start != editor.current.selectionStart ||
            selection.end != editor.current.selectionEnd
        ) {
            editor.edit(text, selection.start, selection.end)
            if (editor.current.text != text) resetInput()
        }
    }

    fun resetInput() {
        val current = editor.current
        field.edit {
            replace(0, length, current.text)
            selection = TextRange(current.selectionStart, current.selectionEnd)
        }
    }
}
