package com.metrolist.android.utils

import android.content.ContentValues
import android.content.Context
import android.media.ExifInterface
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.metrolist.shared.viewmodels.ArtistPhoto
import com.metrolist.shared.viewmodels.downloadArtistPhoto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object ArtistPhotoSaver {
    private const val RELATIVE_DIR = "Metrolist"

    val needsStoragePermission: Boolean get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    suspend fun save(
        context: Context,
        photo: ArtistPhoto,
        artistName: String,
    ) = withContext(Dispatchers.IO) {
        val download = downloadArtistPhoto(photo, artistName)
        val staged = File.createTempFile("artist-photo", ".${download.extension}", context.cacheDir)
        try {
            staged.writeBytes(download.bytes)
            if (download.mimeType == "image/jpeg") fillMissingExif(staged, artistName)
            publish(context, staged, "${download.baseName}.${download.extension}", download.mimeType)
        } finally {
            staged.delete()
        }
    }

    private fun fillMissingExif(
        file: File,
        artistName: String,
    ) {
        val exif = ExifInterface(file.absolutePath)
        if (exif.getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION).isNullOrBlank()) {
            exif.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, artistName)
        }
        exif.saveAttributes()
    }

    private fun publish(
        context: Context,
        source: File,
        fileName: String,
        mimeType: String,
    ) {
        if (needsStoragePermission) {
            @Suppress("DEPRECATION")
            val directory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), RELATIVE_DIR)
            directory.mkdirs()
            val target = File(directory, fileName)
            source.copyTo(target, overwrite = true)
            MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf(mimeType), null)
            return
        }
        val values =
            ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, mimeType)
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$RELATIVE_DIR")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("Could not create photo file")
        try {
            resolver.openOutputStream(uri, "w")?.use { output -> source.inputStream().use { it.copyTo(output) } }
                ?: error("Could not open photo file")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }
}
