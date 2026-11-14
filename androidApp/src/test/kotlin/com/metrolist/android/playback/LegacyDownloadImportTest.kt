package com.metrolist.android.playback

import androidx.media3.common.C
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.db.entities.FormatEntity
import com.metrolist.shared.db.entities.KIND_DOWNLOAD
import com.metrolist.shared.db.entities.SongEntity
import com.metrolist.shared.settings.Settings
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class LegacyDownloadImportTest {
    @Test
    fun completedLegacyDownloadsMoveToInternalStorageAndTheLegacyCacheIsRemoved() =
        runBlocking {
            Settings.replaceSharedForTest(Settings(MapSettings()))
            val context = RuntimeEnvironment.getApplication()
            val legacy = File(context.filesDir, "legacy-download-test").apply { deleteRecursively() }
            val audio = ByteArray(8192) { it.toByte() }
            SimpleCache(legacy, NoOpCacheEvictor(), StandaloneDatabaseProvider(context)).apply {
                fun write(
                    key: String,
                    length: Long,
                ) = CacheWriter(
                    CacheDataSource(this, ByteArrayDataSource(audio)),
                    DataSpec
                        .Builder()
                        .setUri(key)
                        .setKey(key)
                        .setLength(length)
                        .build(),
                    null,
                    null,
                ).cache()
                write("done", C.LENGTH_UNSET.toLong())
                write("partial", 1024L)
                applyContentMetadataMutations(
                    "partial",
                    ContentMetadataMutations().also { ContentMetadataMutations.setContentLength(it, audio.size.toLong()) },
                )
                write("orphan", C.LENGTH_UNSET.toLong())
                release()
            }
            val database = Room.inMemoryDatabaseBuilder(context, MusicDatabase::class.java).setDriver(AndroidSQLiteDriver()).build()
            try {
                listOf("done", "partial").forEach { database.dao().insert(SongEntity(id = it, title = "Title $it")) }
                database.dao().upsert(FormatEntity("done", 251, "audio/webm", "opus", 160_000, 48_000, 8192L, null, playbackUrl = null))

                AudioCacheManager(context, database).importLegacyDownloads(legacy)

                val imported = checkNotNull(database.dao().cachedAudio("done"))
                assertEquals(KIND_DOWNLOAD, imported.kind)
                assertEquals("audio/webm", imported.mimeType)
                assertTrue(imported.filePath.endsWith("/downloads/done.webm"))
                assertArrayEquals(audio, File(imported.filePath).readBytes())
                assertTrue(checkNotNull(database.dao().song("done").first()).song.isDownloaded)
                assertNull(database.dao().cachedAudio("partial"))
                assertNull(database.dao().cachedAudio("orphan"))
                assertFalse(legacy.exists())
            } finally {
                database.close()
                legacy.deleteRecursively()
                File(context.filesDir, "downloads").deleteRecursively()
                Settings.replaceSharedForTest(null)
            }
        }
}
