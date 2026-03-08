package com.metrolist.android.playback

import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.metrolist.android.db.QueryCallbackDriver
import com.metrolist.android.db.executeTestSql
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.db.entities.CachedAudioEntity
import com.metrolist.shared.db.entities.KIND_CACHE
import com.metrolist.shared.db.entities.KIND_DOWNLOAD
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.youtube.playback.PlaybackResource
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class AudioCachePublicationTest {
    private val bytes = ByteArray(4096) { (it % 251).toByte() }
    private val song = SongItem("publication", "Track", null, listOf(ArtistTiny("artist", "Artist")), null)
    private val resource =
        PlaybackResource(
            song.id,
            "https://media.example.test/audio",
            emptyMap(),
            null,
            null,
            contentLengthBytes = bytes.size.toLong(),
            mimeType = "audio/mp4",
            requireBoundedRange = false,
        )
    private val client =
        OkHttpClient
            .Builder()
            .addInterceptor { chain ->
                Response
                    .Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("fixture")
                    .body(bytes.toResponseBody())
                    .build()
            }.build()

    @Test
    fun unknownLengthEvictsOnlyWhatTheCompletedFileNeedsAndRejectsOversizeTransfers() =
        runBlocking {
            Settings.replaceSharedForTest(Settings(MapSettings()).apply { maxSongCacheSize = 1 })
            val context = RuntimeEnvironment.getApplication()
            val database = Room.inMemoryDatabaseBuilder(context, MusicDatabase::class.java).setDriver(AndroidSQLiteDriver()).build()
            var responseBytes = ByteArray(400 * 1024) { 1 }
            val streamingClient =
                OkHttpClient
                    .Builder()
                    .addInterceptor { chain ->
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

                                    override fun source() = Buffer().write(responseBytes)
                                },
                            ).build()
                    }.build()
            val manager = AudioCacheManager(context, database, streamingClient)
            try {
                for (id in listOf("one", "two", "three")) {
                    manager.cacheSong(song.copy(id = id), resource.copy(videoId = id, contentLengthBytes = null))
                }
                assertFalse(manager.isCached("one"))
                assertTrue(manager.isCached("two"))
                assertTrue(manager.isCached("three"))
                responseBytes = ByteArray(2 * 1024 * 1024)
                manager.cacheSong(song.copy(id = "too-large"), resource.copy(videoId = "too-large", contentLengthBytes = null))
                assertTrue(manager.isCached("two"))
                assertTrue(manager.isCached("three"))
                assertNull(database.dao().cachedAudio("too-large"))
                assertFalse(
                    manager
                        .cacheDirectory()
                        .listFiles()
                        .orEmpty()
                        .any { it.extension == "tmp" },
                )
            } finally {
                manager.cacheDirectory().deleteRecursively()
                database.close()
                Settings.replaceSharedForTest(null)
            }
        }

    @Test
    fun unknownLengthDirectAudioIsCachedWithinTheConfiguredBudget() =
        runBlocking {
            Settings.replaceSharedForTest(Settings(MapSettings()).apply { maxSongCacheSize = 1 })
            val context = RuntimeEnvironment.getApplication()
            val database = Room.inMemoryDatabaseBuilder(context, MusicDatabase::class.java).setDriver(AndroidSQLiteDriver()).build()
            val manager = AudioCacheManager(context, database, client)
            try {
                val old = File(manager.cacheDirectory(), "old.m4a").apply { writeBytes(ByteArray(512 * 1024)) }
                database.dao().upsert(
                    CachedAudioEntity(
                        "old",
                        "Old",
                        "Artist",
                        filePath = old.path,
                        fileSize = old.length(),
                        cachedAt = 1,
                        lastAccessedAt = 1,
                        cacheLocation = "INTERNAL",
                        kind = KIND_CACHE,
                    ),
                )
                manager.cacheSong(song, resource.copy(contentLengthBytes = null))
                assertTrue(manager.isCached("old"))
                assertTrue(manager.isCached(song.id))
                assertArrayEquals(bytes, File(checkNotNull(database.dao().cachedAudio(song.id)).filePath).readBytes())
                val second = song.copy(id = "second-unknown")
                manager.cacheSong(second, resource.copy(videoId = second.id, contentLengthBytes = null))
                assertTrue(manager.isCached(song.id))
                assertTrue(manager.isCached(second.id))
                val failingClient = OkHttpClient.Builder().addInterceptor { throw IOException("Transfer failed") }.build()
                AudioCacheManager(context, database, failingClient).cacheSong(
                    song.copy(id = "failed-unknown"),
                    resource.copy(videoId = "failed-unknown", contentLengthBytes = null),
                )
                assertTrue(manager.isCached(song.id))
                assertTrue(manager.isCached(second.id))
                assertNull(database.dao().cachedAudio("failed-unknown"))
                Settings.shared.maxSongCacheSize = 0
                manager.enforceCacheLimit()
                assertFalse(manager.isCached(song.id))
                assertEquals(0L, manager.getCacheSizeBytes())
            } finally {
                manager.cacheDirectory().deleteRecursively()
                database.close()
                Settings.replaceSharedForTest(null)
            }
        }

    @Test
    fun failedOrOversizedUnknownLengthTransferKeepsExistingCache() =
        runBlocking {
            Settings.replaceSharedForTest(Settings(MapSettings()).apply { maxSongCacheSize = 1 })
            val context = RuntimeEnvironment.getApplication()
            val database = Room.inMemoryDatabaseBuilder(context, MusicDatabase::class.java).setDriver(AndroidSQLiteDriver()).build()
            val failingClient =
                OkHttpClient
                    .Builder()
                    .addInterceptor { chain ->
                        val body =
                            if (chain
                                    .request()
                                    .url.encodedPath
                                    .endsWith("oversized")
                            ) {
                                ByteArray(2 * 1024 * 1024)
                            } else {
                                ByteArray(10)
                            }
                        Response
                            .Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("fixture")
                            .body(body.toResponseBody())
                            .build()
                    }.build()
            val manager = AudioCacheManager(context, database, failingClient)
            try {
                val old = File(manager.cacheDirectory(), "old.m4a").apply { writeBytes(ByteArray(512 * 1024)) }
                val entry =
                    CachedAudioEntity(
                        "old",
                        "Old",
                        "Artist",
                        filePath = old.path,
                        fileSize = old.length(),
                        cachedAt = 1,
                        lastAccessedAt = 1,
                        cacheLocation = "INTERNAL",
                        kind = KIND_CACHE,
                    )
                database.dao().upsert(entry)
                for (suffix in listOf("truncated", "oversized")) {
                    manager.cacheSong(song, resource.copy(url = "https://media.example.test/$suffix", contentLengthBytes = null))
                    assertEquals(entry, database.dao().cachedAudio("old"))
                    assertEquals(512 * 1024L, old.length())
                    assertNull(database.dao().cachedAudio(song.id))
                    assertFalse(
                        manager
                            .cacheDirectory()
                            .listFiles()
                            .orEmpty()
                            .any { it.name.endsWith(".tmp") },
                    )
                }
            } finally {
                manager.cacheDirectory().deleteRecursively()
                database.close()
                Settings.replaceSharedForTest(null)
            }
        }

    @Test
    fun failedMigrationCleansTemporaryFilesAndStillMovesOtherEntries() =
        runBlocking {
            Settings.replaceSharedForTest(Settings(MapSettings()))
            val context = RuntimeEnvironment.getApplication()
            val database = Room.inMemoryDatabaseBuilder(context, MusicDatabase::class.java).setDriver(AndroidSQLiteDriver()).build()
            val manager = AudioCacheManager(context, database, client)
            val sources =
                listOf("bad-migration", "good-migration").map { id ->
                    File(context.cacheDir, "$id.source").apply { writeBytes(bytes) }
                }
            try {
                for ((index, source) in sources.withIndex()) {
                    val id = if (index == 0) "bad-migration" else "good-migration"
                    database.dao().upsert(
                        CachedAudioEntity(
                            id,
                            "Track",
                            "Artist",
                            filePath = source.path,
                            fileSize = bytes.size.toLong(),
                            mimeType = "audio/mp4",
                            cachedAt = index.toLong(),
                            lastAccessedAt = index.toLong(),
                            cacheLocation = "INTERNAL",
                            kind = KIND_DOWNLOAD,
                        ),
                    )
                }
                database.executeTestSql(
                    "CREATE TRIGGER reject_bad BEFORE INSERT ON cached_audio WHEN NEW.songId = 'bad-migration' BEGIN SELECT RAISE(ABORT, 'fixture'); END",
                )
                try {
                    manager.migrateDownloadsToConfiguredLocation()
                    fail("Migration should report the failed entry")
                } catch (_: Exception) {
                    assertEquals(sources[0].path, database.dao().cachedAudio("bad-migration")?.filePath)
                    assertTrue(sources[0].exists())
                    assertFalse(sources[1].exists())
                    assertTrue(File(checkNotNull(database.dao().cachedAudio("good-migration")).filePath).isFile)
                    assertFalse(File(context.cacheDir, "download-export").listFiles().orEmpty().any { it.name.contains("migrate") })
                }
            } finally {
                sources.forEach(File::delete)
                manager.downloadDirectory().deleteRecursively()
                database.close()
                Settings.replaceSharedForTest(null)
            }
        }

    @Test
    fun failedPublicationKeepsPreviousCacheAndRemovesUntrackedDownload() =
        runBlocking {
            Settings.replaceSharedForTest(Settings(MapSettings()))
            val context = RuntimeEnvironment.getApplication()
            val database = Room.inMemoryDatabaseBuilder(context, MusicDatabase::class.java).setDriver(AndroidSQLiteDriver()).build()
            val source = File(context.cacheDir, "publication-source.cache").apply { writeBytes(bytes) }
            val manager = AudioCacheManager(context, database, client)
            try {
                for (migration in listOf(false, true)) {
                    val previous =
                        CachedAudioEntity(
                            song.id,
                            song.title,
                            "Artist",
                            filePath = source.path,
                            fileSize = source.length(),
                            cachedAt = 1,
                            lastAccessedAt = 1,
                            cacheLocation = "INTERNAL",
                            kind = if (migration) KIND_DOWNLOAD else KIND_CACHE,
                        )
                    database.dao().upsert(previous)
                    database.executeTestSql(
                        "CREATE TRIGGER reject_download BEFORE INSERT ON cached_audio " +
                            "WHEN NEW.kind = 'DOWNLOAD' BEGIN SELECT RAISE(ABORT, 'fixture publication failure'); END",
                    )
                    try {
                        if (migration) manager.migrateDownloadsToConfiguredLocation() else manager.downloadSong(song, resource)
                        fail("Publication must fail")
                    } catch (_: Exception) {
                        assertEquals(previous, database.dao().cachedAudio(song.id))
                        assertTrue("The previous offline copy must survive a failed commit", source.exists())
                        assertArrayEquals(bytes, source.readBytes())
                        assertTrue(
                            manager
                                .downloadDirectory()
                                .listFiles()
                                .orEmpty()
                                .isEmpty(),
                        )
                    } finally {
                        database.executeTestSql("DROP TRIGGER reject_download")
                    }
                }
            } finally {
                source.delete()
                manager.downloadDirectory().deleteRecursively()
                database.close()
                Settings.replaceSharedForTest(null)
            }
        }

    @Test
    fun cancellationDuringPublicationFinishesTheFileAndMetadataTogether() =
        runBlocking {
            Settings.replaceSharedForTest(Settings(MapSettings()))
            val context = RuntimeEnvironment.getApplication()
            val publishingJob = AtomicReference<Job?>()
            val database =
                Room
                    .inMemoryDatabaseBuilder(context, MusicDatabase::class.java)
                    .setDriver(
                        QueryCallbackDriver { sql ->
                            if (sql.startsWith("INSERT") && sql.contains("cached_audio")) publishingJob.getAndSet(null)?.cancel()
                        },
                    ).build()
            val manager = AudioCacheManager(context, database, client)
            try {
                val download = launch(Dispatchers.IO, start = CoroutineStart.LAZY) { manager.downloadSong(song, resource) }
                publishingJob.set(download)
                download.start()
                download.join()
                assertTrue(download.isCancelled)
                val entry = database.dao().cachedAudio(song.id)
                assertEquals(KIND_DOWNLOAD, entry?.kind)
                assertArrayEquals(bytes, File(checkNotNull(entry).filePath).readBytes())
                assertTrue(
                    database
                        .dao()
                        .getSongById(song.id)!!
                        .song.isDownloaded,
                )
                assertFalse(
                    manager
                        .downloadDirectory()
                        .listFiles()
                        .orEmpty()
                        .any { it.extension == "tmp" },
                )
            } finally {
                manager.downloadDirectory().deleteRecursively()
                database.close()
                Settings.replaceSharedForTest(null)
            }
        }

    @Test
    fun cancelledTransferDoesNotPublishAudio() =
        runBlocking {
            Settings.replaceSharedForTest(Settings(MapSettings()))
            val context = RuntimeEnvironment.getApplication()
            val database = Room.inMemoryDatabaseBuilder(context, MusicDatabase::class.java).setDriver(AndroidSQLiteDriver()).build()
            val manager = AudioCacheManager(context, database, client)
            val control = AudioDownloadControl()
            try {
                try {
                    manager.downloadSong(song, resource, control) { _, _ -> control.cancel() }
                    fail("A cancelled transfer must not publish")
                } catch (_: Exception) {
                    assertTrue(control.isCancelled)
                    assertNull(database.dao().cachedAudio(song.id))
                    assertTrue(
                        manager
                            .downloadDirectory()
                            .listFiles()
                            .orEmpty()
                            .isEmpty(),
                    )
                }
            } finally {
                manager.downloadDirectory().deleteRecursively()
                database.close()
                Settings.replaceSharedForTest(null)
            }
        }
}
