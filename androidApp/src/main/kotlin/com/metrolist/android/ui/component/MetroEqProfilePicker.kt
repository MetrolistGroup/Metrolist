/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.android.ui.component

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.metrolist.android.utils.readBoundedText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun rememberMetroEqProfilePicker(onPicked: (fileName: String, content: String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                val (name, content) =
                    withContext(Dispatchers.IO) {
                        val resolver = context.contentResolver
                        val columns = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
                        val (name, size) =
                            resolver.query(uri, columns, null, null, null)?.use {
                                if (it.moveToFirst()) it.getString(0).orEmpty() to it.getLong(1) else null
                            } ?: (uri.lastPathSegment.orEmpty() to 0L)
                        val content =
                            size.takeIf { it <= 1_000_000 }?.let {
                                runCatching { resolver.openInputStream(uri)?.use { it.readBoundedText(1_000_000) } }.getOrNull()
                            }
                        name to content.orEmpty()
                    }
                onPicked(name, content)
            }
        }
    return { launcher.launch(arrayOf("text/*")) }
}
