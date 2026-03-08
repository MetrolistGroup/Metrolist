package com.metrolist.android.playback

import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.db.entities.CachedAudioEntity
import com.metrolist.shared.db.entities.KIND_CACHE
import com.metrolist.shared.db.entities.KIND_DOWNLOAD
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.youtube.playback.PlaybackResource
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class AudioOrphanCleanupTest {
    @Test
    fun reopenedStorageAndClearRemoveOnlyUnindexedInternalAudio() =
        runBlocking {
            Settings.replaceSharedForTest(Settings(MapSettings()))
            val context = RuntimeEnvironment.getApplication()
            val database = Room.inMemoryDatabaseBuilder(context, MusicDatabase::class.java).setDriver(AndroidSQLiteDriver()).build()
            val manager = AudioCacheManager(context, database)
            val indexed =
                listOf(manager.cacheDirectory(), manager.downloadDirectory()).mapIndexed { index, directory ->
                    File(directory, "indexed-$index.m4a").apply { writeBytes(ByteArray(2048)) }
                }
            val foreign = File(context.filesDir, "keep-nonaudio").apply { writeText("Keep me") }
            try {
                for ((index, kind) in listOf(KIND_CACHE, KIND_DOWNLOAD).withIndex()) {
                    database.dao().upsert(
                        CachedAudioEntity(
                            "indexed-$index",
                            "Song",
                            "Artist",
                            filePath = indexed[index].path,
                            fileSize = 2048,
                            cachedAt = 1,
                            lastAccessedAt = 1,
                            cacheLocation = "INTERNAL",
                            kind = kind,
                        ),
                    )
                }

                fun seedOrphans(): List<File> =
                    listOf(
                        manager.cacheDirectory(),
                        manager.downloadDirectory(),
                        File(context.cacheDir, "cache-export"),
                        File(context.cacheDir, "download-export"),
                    ).flatMap { directory ->
                        directory.mkdirs()
                        listOf("abandoned.tmp", "unindexed.m4a").map { name -> File(directory, name).apply { writeBytes(ByteArray(2048)) } }
                    }
                val abandoned = seedOrphans()
                AudioCacheManager(context, database).reconcileStorage()
                assertTrue(abandoned.none(File::exists))
                assertTrue(indexed.all(File::exists))
                val cleared = seedOrphans()
                assertTrue(manager.clearCache())
                assertTrue(manager.clearDownloads())
                assertTrue(cleared.none(File::exists))
                assertTrue(indexed.none(File::exists))
                assertEquals("Keep me", foreign.readText())
            } finally {
                manager.cacheDirectory().deleteRecursively()
                manager.downloadDirectory().deleteRecursively()
                database.close()
                foreign.delete()
                Settings.replaceSharedForTest(null)
            }
        }

    @Test
    fun clearPreservesActiveAutomaticCacheTemporaryFileAndInvalidatesPublication() =
        runBlocking {
            Settings.replaceSharedForTest(Settings(MapSettings()))
            val context = RuntimeEnvironment.getApplication()
            val database = Room.inMemoryDatabaseBuilder(context, MusicDatabase::class.java).setDriver(AndroidSQLiteDriver()).build()
            val reading = CountDownLatch(1)
            val release = CountDownLatch(1)
            val bytes = ByteArray(4096)
            val client =
                OkHttpClient
                    .Builder()
                    .addInterceptor { chain ->
                        val source =
                            object : ForwardingSource(Buffer().write(bytes)) {
                                var reads = 0

                                override fun read(
                                    sink: Buffer,
                                    byteCount: Long,
                                ): Long {
                                    if (reads++ == 1) {
                                        reading.countDown()
                                        check(release.await(10, TimeUnit.SECONDS))
                                    }
                                    return super.read(sink, minOf(byteCount, 2048))
                                }
                            }.buffer()
                        Response
                            .Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("fixture")
                            .body(
                                object : ResponseBody() {
                                    override fun contentType() = null

                                    override fun contentLength() = -1L

                                    override fun source() = source
                                },
                            ).build()
                    }.build()
            val manager = AudioCacheManager(context, database, client)
            val song = SongItem("active", "Song", null, emptyList(), null)
            val resource =
                PlaybackResource(
                    song.id,
                    "https://audio.example/test",
                    emptyMap(),
                    null,
                    null,
                    mimeType = "audio/mp4",
                    requireBoundedRange = false,
                )
            val transfer = launch(Dispatchers.IO) { manager.cacheSong(song, resource) }
            try {
                assertTrue(reading.await(10, TimeUnit.SECONDS))
                val temporary = File(manager.cacheDirectory(), "active.tmp")
                assertTrue(temporary.exists())
                assertTrue(manager.clearCache())
                assertTrue(temporary.exists())
                assertEquals(2048L, temporary.length())
                release.countDown()
                transfer.join()
                assertFalse(temporary.exists())
                assertFalse(manager.isCached(song.id))
            } finally {
                release.countDown()
                transfer.join()
                manager.cacheDirectory().deleteRecursively()
                database.close()
                Settings.replaceSharedForTest(null)
            }
        }
}
