package com.metrolist.android.playback

import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.metrolist.android.db.QueryCallbackDriver
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.db.entities.CachedAudioEntity
import com.metrolist.shared.db.entities.KIND_CACHE
import com.metrolist.shared.db.entities.KIND_DOWNLOAD
import com.metrolist.shared.db.entities.SongEntity
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playback.ffmpeg.DirectPlaybackCache
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.youtube.playback.PlaybackResource
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
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
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class AudioCacheRemovalTest {
    @Test
    fun bulkDownloadedIdsValidateOnlyDownloadCandidatesAndMatchSingleChecks() =
        runBlocking {
            val context = RuntimeEnvironment.getApplication()
            val reads = AtomicInteger()
            val database =
                Room
                    .inMemoryDatabaseBuilder(context, MusicDatabase::class.java)
                    .setDriver(QueryCallbackDriver { sql -> if (sql.startsWith("SELECT * FROM cached_audio")) reads.incrementAndGet() })
                    .build()
            val file = File(context.cacheDir, "batch-audio").apply { writeBytes(ByteArray(2048)) }
            val empty = File(context.cacheDir, "batch-empty").apply { writeBytes(byteArrayOf()) }
            try {
                val manager = AudioCacheManager(context, database)
                for ((id, path, kind) in listOf(
                    Triple("valid", file.path, KIND_DOWNLOAD),
                    Triple("cache", file.path, KIND_CACHE),
                    Triple("empty", empty.path, KIND_DOWNLOAD),
                    Triple("missing", file.path + "-missing", KIND_DOWNLOAD),
                    Triple("unselected", file.path, KIND_DOWNLOAD),
                )) {
                    database.dao().upsert(
                        CachedAudioEntity(
                            id,
                            id,
                            "Artist",
                            filePath = path,
                            fileSize = 2048,
                            cachedAt = 1,
                            lastAccessedAt = 1,
                            cacheLocation = "INTERNAL",
                            kind = kind,
                        ),
                    )
                }
                val candidates = listOf("valid", "valid", "cache", "empty", "missing") + (1..1200).map { "absent-$it" }
                assertEquals(setOf("valid"), manager.downloadedIds(candidates))
                assertTrue(manager.downloadedIds(emptyList()).isEmpty())
                for (id in candidates.take(5)) assertEquals(manager.isDownloaded(id), id in manager.downloadedIds(candidates))
                reads.set(0)
                assertEquals(setOf("valid", "cache"), manager.cachedSongIds(candidates.toSet()))
                assertEquals(1, reads.get())
                assertEquals(1L, database.dao().cachedAudio("valid")?.lastAccessedAt)
                assertEquals(setOf("valid", "cache", "unselected"), manager.cachedSongIds())
            } finally {
                database.close()
                file.delete()
                empty.delete()
            }
        }

    @Test
    fun unlimitedCachingStoresMultipleSongsWhenAutomaticQueueLoadingIsDisabled() =
        runBlocking {
            Settings.replaceSharedForTest(Settings(MapSettings()))
            Settings.shared.autoLoadMore = false
            Settings.shared.maxSongCacheSize = -1
            val context = RuntimeEnvironment.getApplication()
            val database = Room.inMemoryDatabaseBuilder(context, MusicDatabase::class.java).setDriver(AndroidSQLiteDriver()).build()
            val requests = AtomicInteger()
            val client =
                OkHttpClient
                    .Builder()
                    .addInterceptor {
                        requests.incrementAndGet()
                        error("Automatic caching must use captured playback bytes")
                    }.build()
            DirectPlaybackCache.clear()
            try {
                val manager = AudioCacheManager(context, database, client)
                for ((index, id) in listOf("uncoupled-cache-first", "uncoupled-cache-second", "uncoupled-cache-disabled").withIndex()) {
                    if (index == 2) Settings.shared.maxSongCacheSize = 0
                    val resource =
                        PlaybackResource(
                            id,
                            "https://audio.example/$id",
                            emptyMap(),
                            null,
                            null,
                            contentLengthBytes = 4096L,
                            mimeType = "audio/mpeg",
                        )
                    val capture = requireNotNull(DirectPlaybackCache.begin(resource, playbackCaptureDirectory(context), 8192L))
                    capture.record(0L, ByteArray(4096), 4096L)
                    capture.close()
                    manager.cacheSong(SongItem(id, id, null, listOf(ArtistTiny(null, "Artist")), null), resource)
                }
                assertEquals(0, requests.get())
                assertEquals(setOf("uncoupled-cache-first", "uncoupled-cache-second"), manager.cachedSongIds())
                assertEquals(8192L, manager.getCacheSizeBytes())
            } finally {
                DirectPlaybackCache.clear()
                database.close()
                Settings.replaceSharedForTest(null)
                listOf("uncoupled-cache-first", "uncoupled-cache-second", "uncoupled-cache-disabled").forEach { id ->
                    File(context.filesDir, "audio_cache/$id.mp3").delete()
                }
            }
        }

    @Test
    fun explicitlyRemovedCacheSongIsNotAddedAgainByAutomaticCaching() =
        runBlocking {
            Settings.replaceSharedForTest(Settings(MapSettings()))
            Settings.shared.autoLoadMore = true
            Settings.shared.maxSongCacheSize = 1
            val context = RuntimeEnvironment.getApplication()
            val database = Room.inMemoryDatabaseBuilder(context, MusicDatabase::class.java).setDriver(AndroidSQLiteDriver()).build()
            val audioFile = File(context.cacheDir, "cached-song.opus").apply { writeBytes(ByteArray(5_000)) }
            try {
                database.dao().upsert(SongEntity("song", "Song", isCached = true))
                database.dao().upsert(
                    CachedAudioEntity(
                        "song",
                        "Song",
                        "Artist",
                        filePath = audioFile.path,
                        fileSize = audioFile.length(),
                        cachedAt = 1,
                        lastAccessedAt = 1,
                        cacheLocation = "INTERNAL",
                        kind = KIND_CACHE,
                    ),
                )
                val manager = AudioCacheManager(context, database)

                assertTrue(manager.removeCache("song", playbackInstanceId = 10L))
                manager.cacheSong(
                    SongItem("song", "Song", null, emptyList(), null),
                    PlaybackResource(
                        videoId = "song",
                        url = "https://audio.example/song",
                        headers = emptyMap(),
                        loudnessDb = null,
                        expiresAt = null,
                    ),
                    playbackInstanceId = 10L,
                    isCurrentSong = true,
                )

                assertNull(database.dao().cachedAudio("song"))
                assertFalse(audioFile.exists())
            } finally {
                database.close()
                audioFile.delete()
            }
        }

    @Test
    fun manualCacheRemovalAppliesUntilTheSongStartsInANewPlaybackInstance() {
        assertTrue(shouldSkipManuallyRemovedCache(true, 10L, 10L, isCurrentSong = true))
        assertTrue(shouldSkipManuallyRemovedCache(true, 10L, 11L, isCurrentSong = false))
        assertFalse(shouldSkipManuallyRemovedCache(true, 10L, 11L, isCurrentSong = true))
        assertFalse(shouldSkipManuallyRemovedCache(false, null, 10L, isCurrentSong = false))
    }

    @Test
    fun customFolderIdentityIncludesProviderAndTreeInsteadOfOnlyStorageKind() {
        val document = "content://provider/tree/folder-a/document/folder-a%2Faudio.opus"
        assertTrue(audioBelongsToTree(document, "content://provider/tree/folder-a"))
        assertFalse(audioBelongsToTree(document, "content://provider/tree/folder-b"))
        assertFalse(audioBelongsToTree(document, "content://other-provider/tree/folder-a"))
        assertFalse(audioBelongsToTree("/downloads/audio.opus", "content://provider/tree/folder-a"))
    }

    @Test
    fun failedDeletionRetainsRecordsForRetryWhileAbsentFilesCanBeRemoved() =
        runBlocking {
            Settings.replaceSharedForTest(Settings(MapSettings()))
            val context = RuntimeEnvironment.getApplication()
            val database = Room.inMemoryDatabaseBuilder(context, MusicDatabase::class.java).setDriver(AndroidSQLiteDriver()).build()
            val directory = File(context.cacheDir, "undeletable-audio").apply { mkdirs() }
            val child = File(directory, "child").apply { writeText("audio") }
            try {
                val manager = AudioCacheManager(context, database)
                for (kind in listOf(KIND_CACHE, KIND_DOWNLOAD)) {
                    database.dao().upsert(SongEntity("song", "Song", isCached = kind == KIND_CACHE, isDownloaded = kind == KIND_DOWNLOAD))
                    val entry =
                        CachedAudioEntity(
                            "song",
                            "Song",
                            "Artist",
                            filePath = directory.path,
                            fileSize = 5,
                            cachedAt = 1,
                            lastAccessedAt = 1,
                            cacheLocation = "INTERNAL",
                            kind = kind,
                        )
                    database.dao().upsert(entry)
                    assertFalse(if (kind == KIND_DOWNLOAD) manager.removeDownload("song") else manager.removeCache("song"))
                    assertFalse(if (kind == KIND_DOWNLOAD) manager.clearDownloads() else manager.clearCache())
                    assertEquals(entry, database.dao().cachedAudio("song"))
                }
                child.delete()
                assertTrue(manager.removeDownload("song"))
                assertNull(database.dao().cachedAudio("song"))
                database.dao().upsert(
                    CachedAudioEntity(
                        "song",
                        "Song",
                        "Artist",
                        filePath = directory.path,
                        fileSize = 5,
                        cachedAt = 1,
                        lastAccessedAt = 1,
                        cacheLocation = "INTERNAL",
                        kind = KIND_DOWNLOAD,
                    ),
                )
                assertTrue(manager.removeDownload("song"))
                assertNull(database.dao().cachedAudio("song"))
            } finally {
                database.close()
                directory.deleteRecursively()
            }
        }
}
