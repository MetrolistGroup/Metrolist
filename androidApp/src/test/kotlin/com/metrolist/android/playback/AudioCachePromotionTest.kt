package com.metrolist.android.playback

import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.db.entities.CachedAudioEntity
import com.metrolist.shared.db.entities.FormatEntity
import com.metrolist.shared.db.entities.KIND_CACHE
import com.metrolist.shared.db.entities.KIND_DOWNLOAD
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.youtube.playback.PlaybackResource
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
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
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class AudioCachePromotionTest {
    @Test
    fun candidateLookupAndAvailabilityDoNotMutateCacheLru() =
        runBlocking {
            Settings.replaceSharedForTest(Settings(MapSettings()))
            val context = RuntimeEnvironment.getApplication()
            val database = Room.inMemoryDatabaseBuilder(context, MusicDatabase::class.java).setDriver(AndroidSQLiteDriver()).build()
            val source = File(context.cacheDir, "candidate-lookup.cache").apply { writeBytes(ByteArray(2048)) }
            try {
                val entry =
                    CachedAudioEntity(
                        "selected",
                        "Track",
                        "Artist",
                        filePath = source.path,
                        fileSize = 2048,
                        cachedAt = 1,
                        lastAccessedAt = 1,
                        cacheLocation = "INTERNAL",
                        kind = KIND_DOWNLOAD,
                    )
                database.dao().upsert(entry)
                database.dao().upsert(entry.copy(songId = "unrelated"))
                database.dao().upsert(entry.copy(songId = "cache", kind = KIND_CACHE))
                database.dao().upsert(entry.copy(songId = "missing", filePath = source.path + "-missing"))
                val manager = AudioCacheManager(context, database)
                val candidates = List(901) { "unknown-$it" } + listOf("selected", "cache", "missing")
                assertEquals(setOf("selected"), manager.downloadedIds(candidates))
                val song = SongItem(entry.songId, entry.title, null, emptyList(), null)
                manager.getCachedPlaybackResource(song, touch = false)
                assertEquals(1L, database.dao().cachedAudio(song.id)!!.lastAccessedAt)
                manager.getCachedPlaybackResource(song)
                val touched = database.dao().cachedAudio(song.id)!!.lastAccessedAt
                assertTrue(touched > 1L)
                manager.getCachedPlaybackResource(song)
                assertEquals(touched, database.dao().cachedAudio(song.id)!!.lastAccessedAt)
            } finally {
                source.delete()
                database.close()
                Settings.replaceSharedForTest(null)
            }
        }

    @Test
    fun shortCleanResponseIsRejectedAndKnownRangesNeedNoProbe() =
        runBlocking {
            Settings.replaceSharedForTest(Settings(MapSettings()))
            val context = RuntimeEnvironment.getApplication()
            val database = Room.inMemoryDatabaseBuilder(context, MusicDatabase::class.java).setDriver(AndroidSQLiteDriver()).build()
            val bytes = ByteArray(4096) { (it % 251).toByte() }
            try {
                for (ranged in listOf(false, true)) {
                    val requests = AtomicInteger()
                    val client =
                        OkHttpClient
                            .Builder()
                            .addInterceptor { chain ->
                                requests.incrementAndGet()
                                val builder =
                                    Response
                                        .Builder()
                                        .request(chain.request())
                                        .protocol(Protocol.HTTP_1_1)
                                        .message("fixture")
                                if (ranged) {
                                    val range =
                                        chain
                                            .request()
                                            .header("Range")!!
                                            .removePrefix("bytes=")
                                            .split('-')
                                            .map(String::toInt)
                                    assertTrue(range[1] > range[0])
                                    builder
                                        .code(206)
                                        .header("Content-Range", "bytes ${range[0]}-${range[1]}/${bytes.size}")
                                        .body(bytes.copyOfRange(range[0], range[1] + 1).toResponseBody())
                                        .build()
                                } else {
                                    builder.code(200).body(bytes.copyOf(2048).toResponseBody()).build()
                                }
                            }.build()
                    val song = SongItem("response-$ranged", "Track", null, listOf(ArtistTiny("artist", "Artist")), null)
                    val resource =
                        PlaybackResource(
                            song.id,
                            "https://media.example.test/audio",
                            emptyMap(),
                            null,
                            null,
                            contentLengthBytes = bytes.size.toLong(),
                            mimeType = "audio/mp4",
                            requireBoundedRange = false,
                            useRangeChunks = ranged,
                            rangeChunkSizeBytes = 1024,
                        )
                    val manager = AudioCacheManager(context, database, client)
                    if (ranged) {
                        manager.downloadSong(song, resource)
                        val entry = checkNotNull(database.dao().cachedAudio(song.id))
                        assertArrayEquals(bytes, File(entry.filePath).readBytes())
                        assertEquals(4, requests.get())
                        manager.removeDownload(song.id)
                    } else {
                        try {
                            manager.downloadSong(song, resource)
                            fail("A short clean response must not publish audio")
                        } catch (_: DownloadTransferException) {
                            assertNull(database.dao().cachedAudio(song.id))
                        }
                    }
                }
            } finally {
                database.close()
                Settings.replaceSharedForTest(null)
            }
        }

    @Test
    fun completeMatchingCachePublishesDownloadWithoutNetwork() =
        runBlocking {
            Settings.replaceSharedForTest(Settings(MapSettings()))
            val context = RuntimeEnvironment.getApplication()
            val database = Room.inMemoryDatabaseBuilder(context, MusicDatabase::class.java).setDriver(AndroidSQLiteDriver()).build()
            val source = File(context.cacheDir, "promotion-source.cache")
            val bytes = ByteArray(4096) { (it % 251).toByte() }
            source.writeBytes(bytes)
            var destination: File? = null
            try {
                val song = SongItem("promotion", "Track", null, listOf(ArtistTiny("artist", "Artist")), null)
                database.dao().upsert(
                    CachedAudioEntity(
                        song.id,
                        song.title,
                        "Artist",
                        filePath = source.path,
                        mimeType = "audio/mp4",
                        fileSize = bytes.size.toLong(),
                        cachedAt = 1,
                        lastAccessedAt = 1,
                        cacheLocation = "INTERNAL",
                        kind = KIND_CACHE,
                    ),
                )
                database.dao().upsert(
                    FormatEntity(
                        song.id,
                        140,
                        "audio/mp4",
                        "mp4a.40.2",
                        128000,
                        44100,
                        bytes.size.toLong(),
                        null,
                        playbackUrl = null,
                    ),
                )
                val resource =
                    PlaybackResource(
                        song.id,
                        "https://invalid.example.test/must-not-fetch",
                        emptyMap(),
                        null,
                        null,
                        contentLengthBytes = bytes.size.toLong(),
                        itag = 140,
                        mimeType = "audio/mp4",
                        codecs = "mp4a.40.2",
                    )
                AudioCacheManager(context, database).downloadSong(song, resource)
                val entry = checkNotNull(database.dao().cachedAudio(song.id))
                destination = File(entry.filePath)
                assertEquals(KIND_DOWNLOAD, entry.kind)
                assertArrayEquals(bytes, destination.readBytes())
                assertFalse(source.exists())
                assertTrue(
                    database
                        .dao()
                        .getSongById(song.id)!!
                        .song.isDownloaded,
                )
            } finally {
                source.delete()
                destination?.delete()
                database.close()
                Settings.replaceSharedForTest(null)
            }
        }
}
