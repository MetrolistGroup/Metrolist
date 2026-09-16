/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.extensions

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ContentResolverExtTest {
    @Test
    fun `reads display name and size without opening file`() {
        val resolver = resolver(displayName = "song.flac", size = 123L)

        assertEquals("song.flac" to 123L, resolver.fileNameAndSize(FILE_URI))
    }

    @Test
    fun `uses descriptor length when provider omits size`() {
        val file = File(ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir, "upload.mp3")
        file.writeBytes(ByteArray(123))
        val resolver = resolver(displayName = "upload.mp3", size = null, file = file)

        assertEquals("upload.mp3" to 123L, resolver.fileNameAndSize(FILE_URI))
    }

    private fun resolver(
        displayName: String?,
        size: Long?,
        file: File? = null,
    ) = ApplicationProvider.getApplicationContext<android.content.Context>().let { context ->
        val provider =
            object : ContentProvider() {
                override fun onCreate() = true

                override fun query(
                    uri: Uri,
                    projection: Array<out String>?,
                    selection: String?,
                    selectionArgs: Array<out String>?,
                    sortOrder: String?,
                ): Cursor =
                    MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply {
                        addRow(arrayOf<Any?>(displayName, size))
                    }

                override fun openAssetFile(
                    uri: Uri,
                    mode: String,
                ): AssetFileDescriptor? =
                    file?.let {
                        AssetFileDescriptor(
                            ParcelFileDescriptor.open(it, ParcelFileDescriptor.MODE_READ_ONLY),
                            0,
                            it.length(),
                        )
                    }

                override fun getType(uri: Uri): String? = null

                override fun insert(
                    uri: Uri,
                    values: ContentValues?,
                ): Uri? = null

                override fun delete(
                    uri: Uri,
                    selection: String?,
                    selectionArgs: Array<out String>?,
                ) = 0

                override fun update(
                    uri: Uri,
                    values: ContentValues?,
                    selection: String?,
                    selectionArgs: Array<out String>?,
                ) = 0
            }
        val authority = checkNotNull(FILE_URI.authority)
        provider.attachInfo(context, ProviderInfo().apply { this.authority = authority })
        ShadowContentResolver.registerProviderInternal(authority, provider)
        context.contentResolver
    }

    private companion object {
        val FILE_URI: Uri = Uri.parse("content://uploads/song")
    }
}
