package com.metrolist.android.playback

import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.DefaultDownloadIndex
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadProgress
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.metrolist.android.db.executeTestSql
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.db.entities.ArtistEntity
import com.metrolist.shared.db.entities.CachedAudioEntity
import com.metrolist.shared.db.entities.FormatEntity
import com.metrolist.shared.db.entities.KIND_CACHE
import com.metrolist.shared.db.entities.KIND_DOWNLOAD
import com.metrolist.shared.db.entities.SongArtistMap
import com.metrolist.shared.db.entities.SongEntity
import com.metrolist.shared.db.entities.toSongItem
import com.metrolist.shared.settings.Settings
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
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
import kotlin.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class LegacyDownloadImportTest {
    @Test
    fun completedLegacyDownloadsAreIndexedWithoutDeletingSkippedOrImportedSpans() =
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
                val legacyDate = Instant.fromEpochMilliseconds(1_500_000_000_000).toLocalDateTime(TimeZone.UTC)
                database.dao().insert(SongEntity(id = "done", title = "Title done", dateDownload = legacyDate))
                database.dao().insert(SongEntity(id = "partial", title = "Title partial"))
                database.dao().upsert(FormatEntity("done", 251, "audio/webm", "opus", 160_000, 48_000, 8192L, null, playbackUrl = null))

                val before = legacy.walkTopDown().filter { it.isFile }.associate { it.relativeTo(legacy).path to it.readBytes() }
                val manager = AudioCacheManager(context, database)
                repeat(2) { manager.importLegacyDownloads(legacy) }

                val imported = checkNotNull(database.dao().cachedAudio("done"))
                assertEquals(KIND_DOWNLOAD, imported.kind)
                assertEquals(1_500_000_000_000, imported.cachedAt)
                assertEquals(legacyDate, checkNotNull(database.dao().song("done").first()).song.dateDownload)
                assertEquals("audio/webm", imported.mimeType)
                assertArrayEquals(audio, readAudio(File(imported.filePath)))
                assertTrue(checkNotNull(database.dao().song("done").first()).song.isDownloaded)
                assertNull(database.dao().cachedAudio("partial"))
                assertNull(database.dao().cachedAudio("orphan"))
                assertTrue(legacy.exists())
                before.forEach { (path, bytes) -> assertArrayEquals(path, bytes, File(legacy, path).readBytes()) }
            } finally {
                database.close()
                legacy.deleteRecursively()
                File(context.filesDir, "downloads").deleteRecursively()
                File(context.filesDir, "legacy-audio").deleteRecursively()
                Settings.replaceSharedForTest(null)
            }
        }

    @Test
    fun migratedDownloadDatesAreRepairedWithoutTheLegacyCache() =
        runBlocking {
            val context = RuntimeEnvironment.getApplication()
            val database = Room.inMemoryDatabaseBuilder(context, MusicDatabase::class.java).setDriver(AndroidSQLiteDriver()).build()
            val missingLegacy = File(context.filesDir, "missing-legacy-downloads")
            try {
                val entries = listOf("newer", "older", "undated", "cached", "earlier")
                entries.forEachIndexed { index, id ->
                    val date = if (id == "undated") null else (entries.size - index) * 1000L
                    database.dao().insert(
                        SongEntity(
                            id = id,
                            title = id,
                            dateDownload = date?.let { Instant.fromEpochMilliseconds(it).toLocalDateTime(TimeZone.UTC) },
                        ),
                    )
                    database.dao().upsert(
                        CachedAudioEntity(
                            songId = id,
                            title = id,
                            artist = "Artist",
                            filePath = "/downloads/$id",
                            fileSize = 8192,
                            cachedAt = if (id == "earlier") 500 else 10_000L,
                            lastAccessedAt = 20_000,
                            cacheLocation = "DOWNLOAD_INTERNAL",
                            kind = if (id == "cached") KIND_CACHE else KIND_DOWNLOAD,
                        ),
                    )
                }
                val before = entries.associateWith { checkNotNull(database.dao().cachedAudio(it)) }
                val manager = AudioCacheManager(context, database)
                repeat(2) { manager.importLegacyDownloads(missingLegacy) }

                assertEquals(before.getValue("newer").copy(cachedAt = 5000), database.dao().cachedAudio("newer"))
                assertEquals(before.getValue("older").copy(cachedAt = 4000), database.dao().cachedAudio("older"))
                listOf("undated", "cached", "earlier").forEach { assertEquals(before[it], database.dao().cachedAudio(it)) }
                assertEquals(
                    listOf("undated", "newer", "older", "earlier"),
                    database
                        .dao()
                        .cachedAudioByKind(KIND_DOWNLOAD)
                        .first()
                        .map { it.songId },
                )
            } finally {
                database.close()
            }
        }

    @Test
    fun playerCacheRestoresFourHundredSongsWithMetadataDatesAndDownloadIndexKeys() =
        withLegacyCaches { downloads, player, provider, database, manager ->
            val context = RuntimeEnvironment.getApplication()
            val date = 1_500_000_000_000L
            database.dao().upsert(ArtistEntity("artist", "Stored artist"))
            repeat(400) { index ->
                val id = "cached-$index"
                database.dao().insert(
                    SongEntity(
                        id = id,
                        title = "Stored title $index",
                        thumbnailUrl = "https://example.test/art/$index",
                        albumName = "Stored album",
                        dateDownload = Instant.fromEpochMilliseconds(date + index).toLocalDateTime(TimeZone.UTC),
                    ),
                )
                database.dao().upsert(SongArtistMap(id, "artist", 0))
                write(player, id)
            }
            database.dao().insert(SongEntity("download-in-player", "Stored download", dateDownload = null))
            write(player, "download-in-player:251")
            val downloadIndex = DefaultDownloadIndex(provider)
            downloadIndex.putDownload(completedDownload("download-in-player", "download-in-player:251", date - 1000))
            val before = legacyBytes(downloads, player)
            downloads.release()
            player.release()

            assertTrue(manager.needsLegacyAudioImport())
            val originalIndex = context.getDatabasePath(StandaloneDatabaseProvider.DATABASE_NAME).readBytes()
            repeat(2) { manager.reconcileStorage() }

            assertFalse(manager.needsLegacyAudioImport())
            val cached = database.dao().cachedAudioByKind(KIND_CACHE).first()
            assertEquals(400, cached.size)
            assertEquals((399 downTo 0).map { "cached-$it" }, cached.map { it.songId })
            cached.forEachIndexed { index, entry ->
                assertEquals("Stored title ${399 - index}", entry.title)
                assertEquals("Stored artist", entry.artist)
                assertEquals("Stored album", entry.album)
                assertEquals("https://example.test/art/${399 - index}", entry.thumbnailUrl)
                assertEquals(date + 399 - index, entry.cachedAt)
                assertArrayEquals(audio, readAudio(File(entry.filePath)))
            }
            assertEquals(400L * audio.size, manager.getCacheSizeBytes())
            assertEquals(401, manager.cachedSongIds().size)
            val imported = checkNotNull(database.dao().cachedAudio("download-in-player"))
            assertEquals(KIND_DOWNLOAD, imported.kind)
            assertEquals(date - 1000, imported.cachedAt)
            assertTrue(manager.isDownloaded(imported.songId))
            val resource =
                checkNotNull(manager.getCachedPlaybackResource(checkNotNull(database.dao().song(imported.songId).first()).toSongItem()))
            assertTrue(resource.url.startsWith("legacy-audio:"))
            assertEquals(audio.size.toLong(), resource.contentLengthBytes)
            before.forEach { (file, bytes) -> assertArrayEquals(file.path, bytes, file.readBytes()) }

            assertArrayEquals(originalIndex, context.getDatabasePath(StandaloneDatabaseProvider.DATABASE_NAME).readBytes())
            assertTrue(manager.removeCache("cached-0"))
            manager.importLegacyDownloads()
            assertNull(database.dao().cachedAudio("cached-0"))
            assertEquals(
                399,
                database
                    .dao()
                    .cachedAudioByKind(KIND_CACHE)
                    .first()
                    .size,
            )
            assertTrue(manager.clearCache())
            manager.importLegacyDownloads()
            assertTrue(
                database
                    .dao()
                    .cachedAudioByKind(KIND_CACHE)
                    .first()
                    .isEmpty(),
            )
            assertTrue(manager.isDownloaded("download-in-player"))
            assertArrayEquals(audio, readAudio(File(checkNotNull(database.dao().cachedAudio("download-in-player")).filePath)))
            assertTrue(manager.removeDownload("download-in-player"))
            manager.importLegacyDownloads()
            assertNull(database.dao().cachedAudio("download-in-player"))
            assertTrue(File(context.filesDir, "exoplayer").isDirectory)
        }

    @Test
    fun fragmentedAudioSeeksAndCopiesWhilePartialGappedAndUnknownEntriesStayUntouched() =
        withLegacyCaches { downloads, player, provider, database, manager ->
            listOf("fragmented", "partial", "gapped", "format-length", "index-length", "completed-bytes", "removing").forEach {
                database.dao().insert(SongEntity(it, "Title $it"))
            }
            write(downloads, "fragmented", 0, 4096)
            write(downloads, "fragmented", 4096, 4096)
            write(downloads, "partial", 0, 1024)
            write(downloads, "gapped", 0, 1024)
            write(downloads, "gapped", 2048, 6144)
            write(downloads, "unmapped:251")
            listOf("fragmented", "partial", "gapped").forEach { setLength(downloads, it, audio.size.toLong()) }
            listOf("format-length", "index-length", "completed-bytes", "removing").forEach { write(player, it, 0, audio.size.toLong()) }
            database.dao().upsert(
                FormatEntity("format-length", 251, "audio/webm", "opus", 160_000, 48_000, audio.size.toLong(), null, playbackUrl = null),
            )
            val index = DefaultDownloadIndex(provider)
            index.putDownload(completedDownload("index-length", "index-length", 1000))
            index.putDownload(completedDownload("completed-bytes", "completed-bytes", 2000, -1))
            index.putDownload(
                completedDownload("removing", "removing", 3000).let {
                    Download(it.request, Download.STATE_REMOVING, it.startTimeMs, it.updateTimeMs, it.contentLength, 0, 0)
                },
            )
            val before = legacyBytes(downloads, player)
            downloads.release()
            player.release()
            repeat(2) { manager.importLegacyDownloads() }

            listOf("partial", "gapped", "unmapped:251", "removing").forEach { assertNull(database.dao().cachedAudio(it)) }
            listOf("fragmented", "format-length", "index-length", "completed-bytes").forEach {
                val entry = checkNotNull(database.dao().cachedAudio(it))
                assertArrayEquals(audio, readAudio(File(entry.filePath)))
            }
            val fragmented = File(checkNotNull(database.dao().cachedAudio("fragmented")).filePath)
            val source = LegacyAudioSource(fragmented)
            val buffer = ByteArray(4096)
            assertEquals(6, source.readAtMediaPosition(4090, buffer, 0, buffer.size))
            assertArrayEquals(audio.copyOfRange(4090, 4096), buffer.copyOf(6))
            source.seekToMediaPosition(4096)
            assertEquals(4096, source.read(buffer, 0, buffer.size))
            assertArrayEquals(audio.copyOfRange(4096, 8192), buffer)
            assertEquals(-1, source.read(buffer, 0, 1))
            source.close()
            val copy = File(fragmented.parentFile, "export.tmp")
            LegacyAudioFile.read(fragmented).copyTo(copy)
            assertArrayEquals(audio, copy.readBytes())
            before.forEach { (file, bytes) -> assertArrayEquals(file.path, bytes, file.readBytes()) }
        }

    @Test
    fun failedRoomWriteRetainsEverySpanAndOtherSongsImportBeforeRetry() =
        withLegacyCaches { downloads, player, _, database, manager ->
            listOf("bad", "good").forEach {
                database.dao().insert(SongEntity(it, it))
                write(downloads, it)
            }
            val before = legacyBytes(downloads, player)
            downloads.release()
            player.release()
            database.executeTestSql(
                "CREATE TRIGGER reject_legacy BEFORE INSERT ON cached_audio WHEN NEW.songId = 'bad' BEGIN SELECT RAISE(ABORT, 'fixture'); END",
            )
            try {
                manager.importLegacyDownloads()
                fail("A failed write must keep recovery pending")
            } catch (_: Exception) {
                assertTrue(manager.needsLegacyAudioImport())
                assertNull(database.dao().cachedAudio("bad"))
                assertTrue(manager.isDownloaded("good"))
                before.forEach { (file, bytes) -> assertArrayEquals(file.path, bytes, file.readBytes()) }
            }
            database.executeTestSql("DROP TRIGGER reject_legacy")
            repeat(2) { manager.importLegacyDownloads() }
            assertTrue(manager.isDownloaded("bad"))
            assertFalse(manager.needsLegacyAudioImport())
            before.forEach { (file, bytes) -> assertArrayEquals(file.path, bytes, file.readBytes()) }
        }

    @Test
    fun clearingCacheKeepsUnplayedSpansReadableUntilThePlayerCloses() =
        withLegacyCaches { downloads, player, _, database, manager ->
            database.dao().insert(SongEntity("playing", "Playing"))
            write(player, "playing", 0, 4096)
            write(player, "playing", 4096, 4096)
            setLength(player, "playing", audio.size.toLong())
            downloads.release()
            player.release()
            manager.importLegacyDownloads()
            val entry = checkNotNull(database.dao().cachedAudio("playing"))
            val source = LegacyAudioSource(File(entry.filePath))
            try {
                assertTrue(manager.clearCache())
                assertNull(database.dao().cachedAudio("playing"))
                val bytes = ByteArray(audio.size)
                var position = 0
                while (position < bytes.size) {
                    val read = source.readAtMediaPosition(position.toLong(), bytes, position, bytes.size - position)
                    check(read > 0)
                    position += read
                }
                assertArrayEquals(audio, bytes)
                manager.importLegacyDownloads()
                assertNull(database.dao().cachedAudio("playing"))
            } finally {
                source.close()
            }
        }

    @Test
    fun missingIndexNeverOpensSimpleCacheOrDeletesUnindexedFiles() =
        withLegacyCaches { downloads, player, provider, _, manager ->
            write(player, "unindexed")
            val before = legacyBytes(downloads, player)
            downloads.release()
            player.release()
            provider.close()
            RuntimeEnvironment.getApplication().deleteDatabase(StandaloneDatabaseProvider.DATABASE_NAME)
            repeat(2) {
                try {
                    manager.importLegacyDownloads()
                    fail("Missing index must keep recovery pending")
                } catch (_: Exception) {
                    assertTrue(manager.needsLegacyAudioImport())
                    before.forEach { (file, bytes) -> assertArrayEquals(file.path, bytes, file.readBytes()) }
                }
            }
        }

    private fun readAudio(file: File): ByteArray {
        val source = LegacyAudioSource(file)
        return try {
            val bytes = ByteArray(source.mediaSize.toInt())
            var offset = 0
            while (offset < bytes.size) {
                val read = source.read(bytes, offset, bytes.size - offset)
                check(read > 0)
                offset += read
            }
            bytes
        } finally {
            source.close()
        }
    }

    private fun write(
        cache: SimpleCache,
        key: String,
        position: Long = 0,
        length: Long = C.LENGTH_UNSET.toLong(),
    ) {
        CacheWriter(
            CacheDataSource(cache, ByteArrayDataSource(audio)),
            DataSpec
                .Builder()
                .setUri(key)
                .setKey(key)
                .setPosition(position)
                .setLength(length)
                .build(),
            null,
            null,
        ).cache()
    }

    private fun setLength(
        cache: SimpleCache,
        key: String,
        length: Long,
    ) {
        cache.applyContentMetadataMutations(key, ContentMetadataMutations().also { ContentMetadataMutations.setContentLength(it, length) })
    }

    private fun completedDownload(
        id: String,
        key: String,
        date: Long,
        length: Long = audio.size.toLong(),
    ): Download =
        Download(
            DownloadRequest.Builder(id, id.toUri()).setCustomCacheKey(key).build(),
            Download.STATE_COMPLETED,
            date,
            date + 100,
            length,
            0,
            0,
            DownloadProgress().apply {
                bytesDownloaded = audio.size.toLong()
                percentDownloaded = 100f
            },
        )

    private fun legacyBytes(vararg caches: SimpleCache): Map<File, ByteArray> =
        caches
            .flatMap { cache -> cache.keys.flatMap { key -> cache.getCachedSpans(key).map { checkNotNull(it.file) } } }
            .associateWith { it.readBytes() }

    private fun withLegacyCaches(
        test: suspend (SimpleCache, SimpleCache, StandaloneDatabaseProvider, MusicDatabase, AudioCacheManager) -> Unit,
    ) = runBlocking {
        Settings.replaceSharedForTest(Settings(MapSettings()))
        val context = RuntimeEnvironment.getApplication()
        val directories = listOf("download", "exoplayer", "legacy-audio", "audio_cache", "downloads").map { File(context.filesDir, it) }
        directories.forEach { it.deleteRecursively() }
        val marker = File(context.filesDir, "legacy-audio-import-v2.complete").apply { delete() }
        context.deleteDatabase(StandaloneDatabaseProvider.DATABASE_NAME)
        val provider = StandaloneDatabaseProvider(context)
        val downloads = SimpleCache(directories[0], NoOpCacheEvictor(), provider)
        val player = SimpleCache(directories[1], NoOpCacheEvictor(), provider)
        val database = Room.inMemoryDatabaseBuilder(context, MusicDatabase::class.java).setDriver(AndroidSQLiteDriver()).build()
        try {
            test(downloads, player, provider, database, AudioCacheManager(context, database))
        } finally {
            downloads.release()
            player.release()
            provider.close()
            database.close()
            directories.forEach { it.deleteRecursively() }
            marker.delete()
            context.deleteDatabase(StandaloneDatabaseProvider.DATABASE_NAME)
            Settings.replaceSharedForTest(null)
        }
    }

    private val audio = ByteArray(8192) { (it % 251).toByte() }
}
