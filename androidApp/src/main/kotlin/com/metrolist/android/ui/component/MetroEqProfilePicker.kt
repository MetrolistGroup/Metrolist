/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.android.ui.component

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.metrolist.android.utils.readBoundedText
import kotlinx.coroutines.CancellationException
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
                val (name, content) = withContext(Dispatchers.IO) { readMetroEqProfile(context.contentResolver, uri) }
                onPicked(name, content)
            }
        }
    return { launcher.launch(arrayOf("text/*")) }
}

internal fun readMetroEqProfile(
    resolver: ContentResolver,
    uri: Uri,
): Pair<String, String> {
    val columns = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
    val metadata =
        readDocumentPart {
            resolver.query(uri, columns, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                val name = if (nameIndex >= 0) cursor.getString(nameIndex) else null
                val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else null
                name to size
            }
        }
    val name = metadata?.first?.takeUnless(String::isBlank) ?: uri.lastPathSegment.orEmpty()
    val size = metadata?.second
    val content =
        if (size == null || size <= MAX_EQ_PROFILE_BYTES) {
            readDocumentPart { resolver.openInputStream(uri)?.use { it.readBoundedText(MAX_EQ_PROFILE_BYTES) } }
        } else {
            null
        }
    return name to content.orEmpty()
}

private const val MAX_EQ_PROFILE_BYTES = 1_000_000

private inline fun <T> readDocumentPart(read: () -> T): T? =
    try {
        read()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
