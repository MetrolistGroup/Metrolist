package com.metrolist.android.playback

import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.metrolist.android.db.executeTestSql
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.db.entities.CachedAudioEntity
import com.metrolist.shared.db.entities.FormatEntity
import com.metrolist.shared.db.entities.KIND_CACHE
import com.metrolist.shared.db.entities.KIND_DOWNLOAD
import com.metrolist.shared.db.entities.SongEntity
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playback.ffmpeg.DirectPlaybackCache
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.youtube.playback.PlaybackResource
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
import org.robolectric.shadows.ShadowMediaScannerConnection
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class SharedCacheWebmTest {
    @Test
    fun failedSharedCacheRemuxDoesNotPublishAudio() =
        runBlocking {
            val context = RuntimeEnvironment.getApplication()
            Settings.replaceSharedForTest(
                Settings(MapSettings()).apply {
                    audioCacheLocation = AudioCacheManager.LOCATION_SHARED_MEDIA
                    maxSongCacheSize = 1
                },
            )
            val database = Room.inMemoryDatabaseBuilder(context, MusicDatabase::class.java).setDriver(AndroidSQLiteDriver()).build()
            val manager = AudioCacheManager(context, database)
            val resource =
                PlaybackResource(
                    "invalid-webm",
                    "https://media.example.test/audio",
                    emptyMap(),
                    null,
                    null,
                    contentLengthBytes = 4096,
                    mimeType = "audio/webm",
                )
            try {
                val capture = checkNotNull(DirectPlaybackCache.begin(resource, playbackCaptureDirectory(context), 8192))
                capture.record(0, ByteArray(4096), 4096)
                capture.close()
                manager.cacheSong(SongItem(resource.videoId, "Track", null, listOf(ArtistTiny(null, "Artist")), null), resource)
                assertNull(database.dao().cachedAudio(resource.videoId))
                assertTrue(
                    manager
                        .cacheDirectory()
                        .listFiles()
                        .orEmpty()
                        .isEmpty(),
                )
                assertTrue(File(context.cacheDir, "cache-export").listFiles().orEmpty().isEmpty())
                assertTrue(ShadowMediaScannerConnection.getSavedPaths().isEmpty())
            } finally {
                DirectPlaybackCache.clear()
                manager.cacheDirectory().deleteRecursively()
                database.close()
                Settings.replaceSharedForTest(null)
            }
        }

    @Test
    fun migrationUpdatesBothRowsAndMediaIndexWithoutChangingCacheHistory() =
        runBlocking {
            val context = RuntimeEnvironment.getApplication()
            Settings.replaceSharedForTest(Settings(MapSettings()).apply { audioCacheLocation = AudioCacheManager.LOCATION_INTERNAL })
            val database = Room.inMemoryDatabaseBuilder(context, MusicDatabase::class.java).setDriver(AndroidSQLiteDriver()).build()
            val directory = File(context.cacheDir, "old-shared-cache").apply { mkdirs() }
            val original = File(directory, "track.webm").apply { writeBytes(ByteArray(4096)) }
            val collision = File(directory, "track.opus").apply { writeText("another recording") }
            val entry =
                CachedAudioEntity(
                    "track",
                    "Title",
                    "Artist",
                    album = "Album",
                    filePath = original.path,
                    mimeType = "audio/webm; codecs=opus",
                    fileSize = original.length(),
                    cachedAt = 7,
                    lastAccessedAt = 9,
                    cacheLocation = AudioCacheManager.LOCATION_SHARED_MEDIA,
                )
            val format = FormatEntity("track", 251, "audio/webm", "opus", 128000, 48000, original.length(), null, playbackUrl = null)
            val converted = ByteArray(2048) { 3 }
            try {
                database.dao().upsert(SongEntity("track", "Title", isCached = true))
                database.dao().upsert(entry)
                database.dao().upsert(format)
                val manager = AudioCacheManager(context, database)
                manager.migrateSharedCacheWebm { source, output, metadata ->
                    assertTrue(original.exists())
                    assertEquals(context.cacheDir, source.parentFile!!.parentFile)
                    assertEquals(AudioExportMetadata("Title", "Artist", "Album"), metadata)
                    output.writeBytes(converted)
                    true
                }
                val migrated = checkNotNull(database.dao().cachedAudio("track"))
                assertEquals(entry.copy(filePath = migrated.filePath, mimeType = "audio/ogg", fileSize = 2048), migrated)
                assertEquals(format.copy(mimeType = "audio/ogg", contentLength = 2048), database.dao().format("track").first())
                assertTrue(
                    database
                        .dao()
                        .getSongById("track")!!
                        .song.isCached,
                )
                assertFalse(original.exists())
                assertArrayEquals(converted, File(migrated.filePath).readBytes())
                assertEquals("another recording", collision.readText())
                assertEquals(setOf(original.path, migrated.filePath), ShadowMediaScannerConnection.getSavedPaths())
                var remuxedAgain = false
                manager.migrateSharedCacheWebm { _, _, _ ->
                    remuxedAgain = true
                    false
                }
                assertFalse(remuxedAgain)
                assertEquals(migrated, database.dao().cachedAudio("track"))
                assertTrue(File(context.cacheDir, "cache-export").listFiles().orEmpty().isEmpty())
            } finally {
                directory.deleteRecursively()
                database.close()
                Settings.replaceSharedForTest(null)
            }
        }

    @Test
    fun failedMigrationPreservesSourceAndMetadataAndContinuesWithOtherEntries() =
        runBlocking {
            val context = RuntimeEnvironment.getApplication()
            Settings.replaceSharedForTest(Settings(MapSettings()))
            val database = Room.inMemoryDatabaseBuilder(context, MusicDatabase::class.java).setDriver(AndroidSQLiteDriver()).build()
            val directory = File(context.cacheDir, "migration-failures").apply { mkdirs() }
            val entries =
                listOf("conversion-fails", "too-small", "commit-fails", "success", "private", "download", "m4a").mapIndexed { index, id ->
                    val source =
                        File(directory, "$id.${if (id == "m4a") "m4a" else "webm"}")
                            .apply { writeBytes(ByteArray(4096)) }
                    CachedAudioEntity(
                        id,
                        id,
                        "Artist",
                        filePath = source.path,
                        mimeType = if (id == "m4a") "audio/mp4" else "audio/webm",
                        fileSize = 4096,
                        cachedAt = index.toLong(),
                        lastAccessedAt = index.toLong(),
                        cacheLocation =
                            if (id ==
                                "private"
                            ) {
                                AudioCacheManager.LOCATION_INTERNAL
                            } else {
                                AudioCacheManager.LOCATION_SHARED_MEDIA
                            },
                        kind = if (id == "download") KIND_DOWNLOAD else KIND_CACHE,
                    )
                }
            try {
                entries.forEach { database.dao().upsert(it) }
                val format = FormatEntity("commit-fails", 251, "audio/webm", "opus", 128000, 48000, 4096, null, playbackUrl = null)
                database.dao().upsert(format)
                database.executeTestSql(
                    "CREATE TRIGGER reject_format BEFORE INSERT ON format WHEN NEW.id = 'commit-fails' BEGIN SELECT RAISE(ABORT, 'fixture'); END",
                )
                val attempted = mutableListOf<String>()
                val manager = AudioCacheManager(context, database)
                manager.migrateSharedCacheWebm { _, output, metadata ->
                    attempted.add(metadata.title)
                    output.writeBytes(ByteArray(if (metadata.title == "too-small") 16 else 2048))
                    metadata.title != "conversion-fails"
                }
                assertEquals(listOf("conversion-fails", "too-small", "commit-fails", "success"), attempted)
                entries.filter { it.songId != "success" }.forEach { entry ->
                    assertEquals(entry, database.dao().cachedAudio(entry.songId))
                    assertTrue(File(entry.filePath).exists())
                }
                assertEquals(format, database.dao().format("commit-fails").first())
                assertFalse(File(directory, "commit-fails.opus").exists())
                assertEquals("audio/ogg", database.dao().cachedAudio("success")!!.mimeType)
                assertTrue(File(context.cacheDir, "cache-export").listFiles().orEmpty().isEmpty())
                database.executeTestSql("DROP TRIGGER reject_format")
                manager.migrateSharedCacheWebm { _, output, _ ->
                    output.writeBytes(ByteArray(2048))
                    true
                }
                for (id in listOf("conversion-fails", "too-small", "commit-fails")) {
                    assertEquals("audio/ogg", database.dao().cachedAudio(id)!!.mimeType)
                    assertFalse(File(directory, "$id.webm").exists())
                }
            } finally {
                directory.deleteRecursively()
                database.close()
                Settings.replaceSharedForTest(null)
            }
        }
}
