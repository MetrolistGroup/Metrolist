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
import com.metrolist.shared.settings.Settings
import timber.log.Timber
import java.io.File
import java.util.UUID

object AppFontManager {
    private const val MAX_FONT_BYTES = 20L * 1024L * 1024L
    private val supportedExtensions = setOf("ttf", "otf")
    private val storedFileName = Regex("app_font(?:_[a-f0-9-]{36})?\\.(?:ttf|otf)")

    data class ImportedFont(
        val fileName: String,
        val displayName: String,
    )

    suspend fun importFont(
        context: Context,
        uri: Uri,
        select: (ImportedFont) -> String,
    ): Result<ImportedFont> =
        selectCustomFont(
            prepare = { prepareFont(context, uri) },
            select = select,
            flushSelection = Settings.shared::flush,
            remove = { removeImportedFile(context, it) },
            onCleanupFailure = ::logCleanupFailure,
        )

    private fun prepareFont(
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
            val temporaryFile = File.createTempFile("app_font_", ".importing", directory)
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
                // A new path invalidates both Compose's state and Android's path-based typeface cache.
                val target = File(directory, "app_font_${UUID.randomUUID()}.$extension")
                check(temporaryFile.renameTo(target)) { "Font could not be saved" }
                ImportedFont(target.name, displayName)
            } finally {
                temporaryFile.delete()
            }
        }

    fun loadFontFamily(
        context: Context,
        fileName: String,
    ): FontFamily {
        if (!storedFileName.matches(fileName)) return FontFamily.Default
        val file = File(fontDirectory(context), fileName)
        return runCatching { FontFamily(Typeface.createFromFile(file)) }.getOrDefault(FontFamily.Default)
    }

    suspend fun retireImportedFont(
        context: Context,
        fileName: String,
    ) = retireCustomFont(
        fileName = fileName,
        flushSelection = Settings.shared::flush,
        remove = { removeImportedFile(context, it) },
        onCleanupFailure = ::logCleanupFailure,
    )

    private fun removeImportedFile(
        context: Context,
        fileName: String,
    ) {
        if (storedFileName.matches(fileName)) File(fontDirectory(context), fileName).delete()
    }

    private fun logCleanupFailure(error: Exception) {
        Timber.w(error, "Keeping the previous custom font because cleanup could not finish")
    }

    fun hasImportedFont(
        context: Context,
        fileName: String,
    ): Boolean = storedFileName.matches(fileName) && File(fontDirectory(context), fileName).isFile

    private fun fontDirectory(context: Context) = File(context.filesDir, "fonts")

    private fun Context.displayName(uri: Uri): String? =
        contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
}
