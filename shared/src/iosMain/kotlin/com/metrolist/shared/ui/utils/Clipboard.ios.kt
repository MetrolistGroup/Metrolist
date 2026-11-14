package com.metrolist.shared.ui.utils

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ClipEntry

@OptIn(ExperimentalComposeUiApi::class)
internal actual fun textClipEntry(text: String): ClipEntry = ClipEntry.withPlainText(text)
