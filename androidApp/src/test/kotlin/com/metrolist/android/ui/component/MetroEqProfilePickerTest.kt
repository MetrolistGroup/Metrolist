package com.metrolist.android.ui.component

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.io.FileNotFoundException

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MetroEqProfilePickerTest {
    @Test
    fun metadataFailureStillAllowsReadingTheSelectedProfile() =
        withProvider(listOf(), listsMetadata = false) { provider ->
            assertEquals("Tune.txt" to PROFILE, loadProfile())
            assertEquals(1, provider.opened)
        }

    @Test
    fun missingSizeAndNameUseBoundedReadAndLastPathSegment() =
        withProvider(listOf(OpenableColumns.DISPLAY_NAME to null, OpenableColumns.SIZE to null)) { provider ->
            assertEquals("Tune.txt" to PROFILE, loadProfile())
            assertEquals(1, provider.opened)
        }

    @Test
    fun reorderedKnownSizeAboveLimitDoesNotOpenTheFile() =
        withProvider(listOf(OpenableColumns.SIZE to 1_000_001L, OpenableColumns.DISPLAY_NAME to "Large.txt")) { provider ->
            assertEquals("Large.txt" to "", loadProfile())
            assertEquals(0, provider.opened)
        }

    @Test
    fun missingDocumentReturnsTheExistingInvalidProfilePayload() =
        withProvider(listOf(), listsMetadata = false, readsFile = false) { provider ->
            assertEquals("Tune.txt" to "", loadProfile())
            assertEquals(1, provider.opened)
        }

    private fun loadProfile(): Pair<String, String> =
        readMetroEqProfile(RuntimeEnvironment.getApplication().contentResolver, Uri.parse("content://$AUTHORITY/Tune.txt"))

    private fun withProvider(
        columns: List<Pair<String, Any?>>,
        listsMetadata: Boolean = true,
        readsFile: Boolean = true,
        body: (ProfileProvider) -> Unit,
    ) {
        val context = RuntimeEnvironment.getApplication()
        val file = File.createTempFile("metro-eq", ".txt", context.cacheDir)
        try {
            file.writeText(PROFILE)
            val provider = ProfileProvider(file, columns, listsMetadata, readsFile)
            provider.attachInfo(context, ProviderInfo().apply { authority = AUTHORITY })
            ShadowContentResolver.registerProviderInternal(AUTHORITY, provider)
            body(provider)
        } finally {
            file.delete()
        }
    }

    private class ProfileProvider(
        private val file: File,
        private val columns: List<Pair<String, Any?>>,
        private val listsMetadata: Boolean,
        private val readsFile: Boolean,
    ) : ContentProvider() {
        var opened = 0
            private set

        override fun onCreate() = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor {
            check(listsMetadata) { "Profile metadata unavailable" }
            return MatrixCursor(columns.map { it.first }.toTypedArray()).apply { addRow(columns.map { it.second }.toTypedArray()) }
        }

        override fun openAssetFile(
            uri: Uri,
            mode: String,
        ): AssetFileDescriptor {
            opened++
            if (!readsFile) throw FileNotFoundException("Profile unavailable")
            return AssetFileDescriptor(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY), 0, file.length())
        }

        override fun getType(uri: Uri) = "text/plain"

        override fun insert(
            uri: Uri,
            values: ContentValues?,
        ): Uri? = null

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ) = 0

        override fun delete(
            uri: Uri,
            selection: String?,
            selectionArgs: Array<out String>?,
        ) = 0
    }

    private companion object {
        const val AUTHORITY = "metro-eq-test"
        const val PROFILE = "Preamp: -3 dB\nFilter 1: ON PK Fc 1000 Hz Gain 2 dB Q 1.0"
    }
}
