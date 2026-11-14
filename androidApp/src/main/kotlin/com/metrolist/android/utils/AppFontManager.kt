/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.android.utils

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.ui.text.font.FontFamily
import java.io.File

object AppFontManager {
    private const val MAX_FONT_BYTES = 20L * 1024L * 1024L
    private val supportedExtensions = setOf("ttf", "otf")
    private val storedFileNames = supportedExtensions.mapTo(mutableSetOf()) { "app_font.$it" }

    data class ImportedFont(
        val fileName: String,
        val displayName: String,
    )

    fun importFont(
        context: Context,
        uri: Uri,
    ): Result<ImportedFont> =
        runCatching {
            val displayName =
                context.displayName(uri)
                    ?: uri.lastPathSegment?.substringAfterLast('/')
                    ?: error("Font name is unavailable")
            val extension = displayName.substringAfterLast('.', "").lowercase()
            require(extension in supportedExtensions) { "Only TTF and OTF fonts are supported" }

            val directory = fontDirectory(context).apply { mkdirs() }
            val temporaryFile = File(directory, "app_font.importing")
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    temporaryFile.outputStream().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var copied = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            copied += count
                            require(copied <= MAX_FONT_BYTES) { "Font is larger than 20 MB" }
                            output.write(buffer, 0, count)
                        }
                    }
                } ?: error("Font could not be opened")

                Typeface.createFromFile(temporaryFile)
                val target = File(directory, "app_font.$extension")
                storedFileNames
                    .asSequence()
                    .map(directory::resolve)
                    .filterNot { it == target }
                    .forEach(File::delete)
                temporaryFile.copyTo(target, overwrite = true)
                ImportedFont(target.name, displayName)
            } finally {
                temporaryFile.delete()
            }
        }

    fun loadFontFamily(
        context: Context,
        fileName: String,
    ): FontFamily {
        if (fileName !in storedFileNames) return FontFamily.Default
        val file = File(fontDirectory(context), fileName)
        return runCatching { FontFamily(Typeface.createFromFile(file)) }.getOrDefault(FontFamily.Default)
    }

    fun removeImportedFont(context: Context) {
        storedFileNames.forEach { File(fontDirectory(context), it).delete() }
    }

    fun hasImportedFont(
        context: Context,
        fileName: String,
    ): Boolean = fileName in storedFileNames && File(fontDirectory(context), fileName).isFile

    private fun fontDirectory(context: Context) = File(context.filesDir, "fonts")

    private fun Context.displayName(uri: Uri): String? =
        contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
}
