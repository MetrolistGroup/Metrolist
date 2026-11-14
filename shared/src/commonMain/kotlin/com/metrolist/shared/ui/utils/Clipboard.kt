package com.metrolist.shared.ui.utils

import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard

internal expect fun textClipEntry(text: String): ClipEntry

suspend fun Clipboard.setText(text: String) {
    setClipEntry(textClipEntry(text))
}
