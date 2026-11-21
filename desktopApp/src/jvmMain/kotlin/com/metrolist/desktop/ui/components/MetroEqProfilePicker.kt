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
import java.io.File
import java.io.IOException

private const val MAX_METRO_EQ_PROFILE_BYTES = 1_000_000

@Composable
internal fun rememberMetroEqProfilePicker(onPicked: (fileName: String, content: String) -> Unit): () -> Unit {
    val scope = rememberCoroutineScope()
    return {
        scope.launch {
            val file = runCatching { FileKit.openFilePicker(type = FileKitType.File(listOf("txt"))) }.getOrNull()?.file
            if (file != null) {
                val content = withContext(Dispatchers.IO) { readMetroEqProfile(file) }
                onPicked(file.name, content)
            }
        }
    }
}

// Empty content reaches the same localized invalid-profile message as a malformed import.
internal fun readMetroEqProfile(file: File): String =
    try {
        if (file.length() > MAX_METRO_EQ_PROFILE_BYTES) {
            ""
        } else {
            val bytes = file.inputStream().use { it.readNBytes(MAX_METRO_EQ_PROFILE_BYTES + 1) }
            bytes.takeIf { it.size <= MAX_METRO_EQ_PROFILE_BYTES }?.toString(Charsets.UTF_8).orEmpty()
        }
    } catch (_: IOException) {
        ""
    }
