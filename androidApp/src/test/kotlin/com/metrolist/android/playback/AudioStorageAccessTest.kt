package com.metrolist.android.playback

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.db.entities.CachedAudioEntity
import com.metrolist.shared.db.entities.KIND_DOWNLOAD
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.settings.Settings
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowParcelFileDescriptor

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33], shadows = [UnknownSizeParcelFileDescriptor::class])
class AudioStorageAccessTest {
    @Test
    fun unknownLengthRemainsPlayableAndRevokedAccessDoesNotDeleteTheFile() =
        runBlocking {
            val context = RuntimeEnvironment.getApplication()
            val provider = UnknownLengthProvider()
            provider.attachInfo(context, ProviderInfo().apply { authority = "audio-storage-test" })
            ShadowContentResolver.registerProviderInternal("audio-storage-test", provider)
            Settings.replaceSharedForTest(
                Settings(MapSettings()).apply {
                    downloadLocation = AudioCacheManager.LOCATION_CUSTOM
                    customDownloadPath = "content://audio-storage-test/tree/folder"
                },
            )
            val database = Room.inMemoryDatabaseBuilder(context, MusicDatabase::class.java).setDriver(AndroidSQLiteDriver()).build()
            try {
                val song = SongItem("unknown-size", "Track", null, emptyList(), null)
                val entry =
                    CachedAudioEntity(
                        song.id,
                        song.title,
                        "Artist",
                        filePath = "content://audio-storage-test/tree/folder/document/file",
                        fileSize = 2048L,
                        cachedAt = 1L,
                        lastAccessedAt = 1L,
                        cacheLocation = "CUSTOM",
                        kind = KIND_DOWNLOAD,
                    )
                database.dao().upsert(entry)
                val manager = AudioCacheManager(context, database)
                assertTrue(manager.isDownloaded(song.id))
                assertEquals(setOf(song.id), manager.downloadedIds(listOf(song.id)))
                assertEquals(setOf(song.id), manager.cachedSongIds())
                assertNotNull(manager.getCachedPlaybackResource(song))
                assertNull(manager.getCachedPlaybackResource(song)?.contentLengthBytes)
                manager.reconcileStorage()
                assertEquals(0, provider.deletions)

                provider.denied = true
                assertFalse(manager.isDownloaded(song.id))
                assertTrue(manager.downloadedIds(listOf(song.id)).isEmpty())
                assertTrue(manager.cachedSongIds().isEmpty())
                assertNull(manager.getCachedPlaybackResource(song))
                manager.reconcileStorage()
                assertNotNull(database.dao().cachedAudio(song.id))
                assertEquals(0, provider.deletions)

                provider.denied = false
                assertTrue(manager.isDownloaded(song.id))
            } finally {
                database.close()
                Settings.replaceSharedForTest(null)
            }
        }

    private class UnknownLengthProvider : ContentProvider() {
        var denied = false
        var deletions = 0

        override fun onCreate(): Boolean = true

        override fun openAssetFile(
            uri: Uri,
            mode: String,
        ): AssetFileDescriptor {
            if (denied) throw SecurityException("Access revoked")
            val pipe = ParcelFileDescriptor.createPipe()
            ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { it.write(ByteArray(2048)) }
            return AssetFileDescriptor(pipe[0], 0L, AssetFileDescriptor.UNKNOWN_LENGTH)
        }

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor {
            if (denied) throw SecurityException("Access revoked")
            return MatrixCursor(arrayOf(OpenableColumns.SIZE)).apply { addRow(arrayOf<Any?>(null)) }
        }

        override fun getType(uri: Uri): String = "audio/mpeg"

        override fun insert(
            uri: Uri,
            values: ContentValues?,
        ): Uri? = null

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int = 0

        override fun delete(
            uri: Uri,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int {
            deletions++
            return 1
        }
    }
}

// Robolectric backs pipes with regular files, which otherwise report a known stat size.
@Implements(ParcelFileDescriptor::class)
class UnknownSizeParcelFileDescriptor : ShadowParcelFileDescriptor() {
    @Implementation
    override fun getStatSize(): Long = -1L
}
