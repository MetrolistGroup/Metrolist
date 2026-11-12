/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import android.Manifest
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.IOException

object DownloadExporter {
    private const val TAG = "DownloadExporter"
    private const val PREFS_NAME = "exported_downloads"

    data class ExportFormat(
        val extension: String,
        val mimeType: String,
    )

    /**
     * Exports a completed song download from [downloadCache] to the public Downloads folder.
     */
    suspend fun exportToDownloads(
        context: Context,
        downloadCache: Cache,
        songId: String,
        title: String,
        artist: String,
        mimeType: String? = null,
        album: String? = null,
    ): Result<Uri?> = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(songId, false)) {
            Timber.tag(TAG).d("Song $songId was already exported previously; skipping")
            return@withContext Result.success(null)
        }

        val dataSource = CacheDataSource.Factory()
            .setCache(downloadCache)
            .setUpstreamDataSourceFactory(null)
            .setCacheWriteDataSinkFactory(null)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            .createDataSource()

        val dataSpec = DataSpec.Builder()
            .setKey(songId)
            .setUri(songId.toUri())
            .build()

        try {
            dataSource.open(dataSpec)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to open cached stream for song $songId")
            return@withContext Result.failure(e)
        }

        val firstChunk = ByteArray(8192)
        val firstRead: Int
        try {
            firstRead = dataSource.read(firstChunk, 0, firstChunk.size)
            if (firstRead <= 0) {
                dataSource.close()
                return@withContext Result.failure(IOException("Empty stream for song $songId in download cache"))
            }
        } catch (e: Exception) {
            try { dataSource.close() } catch (_: Exception) {}
            Timber.tag(TAG).e(e, "Failed to read initial bytes from cache for song $songId")
            return@withContext Result.failure(e)
        }

        val format = detectFormat(firstChunk, firstRead, mimeType)
        val cleanArtist = sanitizeFilename(artist).takeIf { it.isNotBlank() && !it.equals("Unknown", ignoreCase = true) }
        val cleanTitle = sanitizeFilename(title).ifBlank { "Song_$songId" }
        val baseName = if (cleanArtist != null) "$cleanArtist - $cleanTitle" else cleanTitle
        val initialFileName = "$baseName.${format.extension}"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/Metrolist"

            var finalFileName = initialFileName
            var counter = 1
            while (fileExistsInMediaStore(resolver, finalFileName, relativePath)) {
                finalFileName = "$baseName ($counter).${format.extension}"
                counter++
                if (counter > 100) break
            }

            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, finalFileName)
                put(MediaStore.MediaColumns.MIME_TYPE, format.mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "$relativePath/")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }

            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            if (uri == null) {
                try { dataSource.close() } catch (_: Exception) {}
                val error = IOException("Failed to create MediaStore entry in $relativePath")
                Timber.tag(TAG).e(error)
                return@withContext Result.failure(error)
            }

            try {
                resolver.openOutputStream(uri)?.use { outputStream ->
                    outputStream.write(firstChunk, 0, firstRead)
                    val buffer = ByteArray(8192)
                    while (true) {
                        val bytesRead = dataSource.read(buffer, 0, buffer.size)
                        if (bytesRead == C.RESULT_END_OF_INPUT || bytesRead <= 0) break
                        outputStream.write(buffer, 0, bytesRead)
                    }
                    outputStream.flush()
                } ?: throw IOException("Failed to open output stream for $uri")

                contentValues.clear()
                contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, contentValues, null, null)

                prefs.edit().putBoolean(songId, true).apply()
                Timber.tag(TAG).i("Successfully exported song $songId as $finalFileName to $relativePath")
                Result.success(uri)
            } catch (e: Exception) {
                try { resolver.delete(uri, null, null) } catch (_: Exception) {}
                Timber.tag(TAG).e(e, "Error writing exported file for $songId")
                Result.failure(e)
            } finally {
                try { dataSource.close() } catch (_: Exception) {}
            }
        } else {
            // Android 9 and below
            val permissionGranted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) == PackageManager.PERMISSION_GRANTED

            if (!permissionGranted) {
                try { dataSource.close() } catch (_: Exception) {}
                Timber.tag(TAG).w("WRITE_EXTERNAL_STORAGE not granted on Android <= 9 (API ${Build.VERSION.SDK_INT}); skipping export")
                return@withContext Result.success(null)
            }

            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val metrolistDir = File(downloadsDir, "Metrolist").apply { mkdirs() }
            var targetFile = File(metrolistDir, initialFileName)
            var counter = 1
            while (targetFile.exists()) {
                targetFile = File(metrolistDir, "$baseName ($counter).${format.extension}")
                counter++
                if (counter > 100) break
            }

            try {
                targetFile.outputStream().use { outputStream ->
                    outputStream.write(firstChunk, 0, firstRead)
                    val buffer = ByteArray(8192)
                    while (true) {
                        val bytesRead = dataSource.read(buffer, 0, buffer.size)
                        if (bytesRead == C.RESULT_END_OF_INPUT || bytesRead <= 0) break
                        outputStream.write(buffer, 0, bytesRead)
                    }
                    outputStream.flush()
                }

                prefs.edit().putBoolean(songId, true).apply()
                Timber.tag(TAG).i("Successfully exported song $songId as ${targetFile.name} to ${targetFile.absolutePath}")
                Result.success(Uri.fromFile(targetFile))
            } catch (e: Exception) {
                targetFile.delete()
                Timber.tag(TAG).e(e, "Error writing exported file for $songId on Android <= 9")
                Result.failure(e)
            } finally {
                try { dataSource.close() } catch (_: Exception) {}
            }
        }
    }

    fun sanitizeFilename(name: String): String {
        val invalidCharsRegex = Regex("""[\\/:*?"<>|\r\n\t]""")
        var cleaned = invalidCharsRegex.replace(name, "_")
            .trim()
            .trim('.')
            .trim()
        if (cleaned.length > 120) {
            cleaned = cleaned.take(120).trimEnd()
        }
        return cleaned
    }

    private fun detectFormat(firstChunk: ByteArray, length: Int, hintMimeType: String?): ExportFormat {
        // MP4 / M4A: byte 4..7 is "ftyp"
        if (length >= 8 &&
            firstChunk[4] == 'f'.code.toByte() &&
            firstChunk[5] == 't'.code.toByte() &&
            firstChunk[6] == 'y'.code.toByte() &&
            firstChunk[7] == 'p'.code.toByte()
        ) {
            return ExportFormat(extension = "m4a", mimeType = "audio/mp4")
        }

        // WebM / EBML: 0x1A 0x45 0xDF 0xA3 (Opus container)
        if (length >= 4 &&
            (firstChunk[0].toInt() and 0xFF) == 0x1A &&
            (firstChunk[1].toInt() and 0xFF) == 0x45 &&
            (firstChunk[2].toInt() and 0xFF) == 0xDF &&
            (firstChunk[3].toInt() and 0xFF) == 0xA3
        ) {
            return ExportFormat(extension = "opus", mimeType = "audio/webm")
        }

        // Ogg: "OggS"
        if (length >= 4 &&
            firstChunk[0] == 'O'.code.toByte() &&
            firstChunk[1] == 'g'.code.toByte() &&
            firstChunk[2] == 'g'.code.toByte() &&
            firstChunk[3] == 'S'.code.toByte()
        ) {
            return ExportFormat(extension = "ogg", mimeType = "audio/ogg")
        }

        // MP3: "ID3" or syncword 0xFF 0xFB/0xF3
        if (length >= 3 &&
            firstChunk[0] == 'I'.code.toByte() &&
            firstChunk[1] == 'D'.code.toByte() &&
            firstChunk[2] == '3'.code.toByte()
        ) {
            return ExportFormat(extension = "mp3", mimeType = "audio/mpeg")
        }

        // Check hint MIME type
        if (hintMimeType != null) {
            if (hintMimeType.contains("mp4", ignoreCase = true)) {
                return ExportFormat(extension = "m4a", mimeType = "audio/mp4")
            }
            if (hintMimeType.contains("webm", ignoreCase = true)) {
                return ExportFormat(extension = "opus", mimeType = "audio/webm")
            }
            if (hintMimeType.contains("ogg", ignoreCase = true)) {
                return ExportFormat(extension = "ogg", mimeType = "audio/ogg")
            }
        }

        // Default fallback to m4a
        return ExportFormat(extension = "m4a", mimeType = "audio/mp4")
    }

    private fun fileExistsInMediaStore(
        resolver: ContentResolver,
        displayName: String,
        relativePath: String,
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val uri = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
        val selectionArgs = arrayOf(displayName, "%$relativePath%")
        return try {
            resolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
                cursor.moveToFirst()
            } ?: false
        } catch (_: Exception) {
            false
        }
    }
}
