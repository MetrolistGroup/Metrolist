/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.desktop.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.openFilePicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun rememberMetroEqProfilePicker(onPicked: (fileName: String, content: String) -> Unit): () -> Unit {
    val scope = rememberCoroutineScope()
    return {
        scope.launch {
            val file = runCatching { FileKit.openFilePicker(type = FileKitType.File(listOf("txt"))) }.getOrNull()?.file
            if (file != null) {
                val content = withContext(Dispatchers.IO) { file.takeIf { it.length() <= 1_000_000 }?.readText().orEmpty() }
                onPicked(file.name, content)
            }
        }
    }
}
