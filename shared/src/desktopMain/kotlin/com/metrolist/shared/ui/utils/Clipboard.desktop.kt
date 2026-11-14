package com.metrolist.shared.ui.utils

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ClipEntry
import java.awt.datatransfer.StringSelection

@OptIn(ExperimentalComposeUiApi::class)
internal actual fun textClipEntry(text: String): ClipEntry = ClipEntry(StringSelection(text))
