package com.metrolist.desktop.storage

import com.metrolist.desktop.ui.screens.downloadedCollectionSnapshot
import com.metrolist.shared.api.lyrics.LyricsFetcher
import com.metrolist.shared.api.lyrics.LyricsTranslationClient
import com.metrolist.shared.db.entities.SongEntity
import com.metrolist.shared.db.getDatabaseBuilder
import com.metrolist.shared.db.searchOfflineMedia
import com.metrolist.shared.db.upsertHistorySong
import com.metrolist.shared.model.LyricsProvider
import com.metrolist.shared.model.LyricsWithProvider
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.isPodcast
import com.metrolist.shared.repository.LyricsRepository
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.state.PodcastLibrary
import com.metrolist.shared.state.PodcastPlaybackProgress
import com.metrolist.shared.youtube.playback.PlaybackResource
import com.russhwolf.settings.PropertiesSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.Files
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DownloadStorageTest {
    @Test
    fun downloadedCollectionRestoresPodcastResumeAndSavesLaterProgressOffline() =
        runBlocking {
            val root = Files.createTempDirectory("downloaded-podcast")
            val database = getDatabaseBuilder(root.resolve("music.db").toFile()).build()
            try {
                database.dao().upsert(
                    SongEntity(
                        "episode",
                        "Stored episode",
                        duration = 7200,
                        albumId = "podcast",
                        isEpisode = true,
                        playbackPosition = 120_000L,
                    ),
                )
                val files =
                    listOf(
                        DownloadedFile("episode", "Episode", "Author", null, "/audio", null, 7200, 1234),
                        DownloadedFile("legacy", "Legacy", "Artist", null, "/legacy", null, 60, 1234),
                    )
                val stored = database.dao().getSongsByIds(files.map { it.songId })
                val (songs, _) = downloadedCollectionSnapshot(files, stored)
                val episode = songs.first()
                assertTrue(episode.isPodcast)
                assertEquals("podcast", episode.podcastId)
                assertEquals("Stored episode", episode.title)
                assertEquals(120_000L, PodcastLibrary.resumePosition(database, episode))
                assertEquals(files.last().toSongItem(), songs.last())
                val progress =
                    PodcastPlaybackProgress(
                        this,
                        { song, position, duration, metadata ->
                            PodcastLibrary.updatePlaybackPosition(database, song, position, duration, metadata)
                        },
                        { throw it },
                    )
                progress.update(episode, 180_000L, 7_200_000L, playing = false)
                progress.flush()?.join()
                assertEquals(180_000L, PodcastLibrary.resumePosition(database, episode))
            } finally {
                database.close()
                root.toFile().deleteRecursively()
            }
        }

    @Test
    fun downloadSnapshotIncludesAudioAndArtworkSizesAndRefreshesAfterRemoval() {
        val root = Files.createTempDirectory("download-snapshot").toFile()
        try {
            val audio = root.resolve("song.m4a").apply { writeBytes(ByteArray(2048)) }
            val art = root.resolve("song.jpg").apply { writeBytes(ByteArray(512)) }
            val entry = DownloadedFile("song", "Song", "Artist", null, audio.path, art.path, 60, 1234)
            val (songs, metadata) = downloadedCollectionSnapshot(listOf(entry))
            assertEquals(listOf("song"), songs.map { it.id })
            assertEquals(2560L, metadata.getValue("song").fileSize)
            art.delete()
            assertEquals(2048L, downloadedCollectionSnapshot(listOf(entry)).second.getValue("song").fileSize)
            assertTrue(downloadedCollectionSnapshot(emptyList()).first.isEmpty())
            assertTrue(downloadedCollectionSnapshot(emptyList()).second.isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun offlineLyricsWaitForPersistenceAndFailedFetchesRemainRetryable() =
        runBlocking {
            Settings.replaceSharedForTest(Settings(PropertiesSettings(Properties())))
            val path = Files.createTempFile("download-lyrics", ".db")
            Files.delete(path)
            val database = getDatabaseBuilder(path.toFile()).build()
            val client = HttpClient(CIO)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var fail = true
            var calls = 0
            val fetchedLyrics = LyricsWithProvider("[00:01.00]Saved words for offline listening", "LrcLib")
            val fetcher =
                object : LyricsFetcher {
                    override suspend fun getLyrics(
                        title: String,
                        artist: String,
                        duration: Long?,
                        album: String?,
                        videoId: String?,
                        provider: LyricsProvider,
                        isVideoContext: Boolean,
                    ): LyricsWithProvider? {
                        calls++
                        if (fail) throw IOException("Offline")
                        entered.complete(Unit)
                        release.await()
                        return fetchedLyrics
                    }
                }
            try {
                val repository = LyricsRepository(fetcher, LyricsTranslationClient(client), database.dao())
                val song = SongItem("download", "Song", null, emptyList(), null, duration = 123)
                assertNull(repository.prefetchDownloadLyrics(song, listOf(LyricsProvider.LRCLIB)))
                assertNull(database.dao().cachedLyrics(song.id))
                assertTrue(database.dao().cachedLyricsIds(listOf(song.id, "missing")).isEmpty())
                fail = false
                val pending = async { repository.prefetchDownloadLyrics(song, listOf(LyricsProvider.LRCLIB)) }
                entered.await()
                assertFalse(pending.isCompleted)
                assertNull(database.dao().cachedLyrics(song.id))
                release.complete(Unit)
                assertEquals(fetchedLyrics, pending.await())
                val saved = assertNotNull(database.dao().cachedLyrics(song.id))
                assertEquals(fetchedLyrics.lyrics, saved.lyrics)
                assertEquals(listOf(song.id), database.dao().cachedLyricsIds(listOf(song.id, "missing")))
                assertEquals(2, calls)
                fail = true
                assertEquals(fetchedLyrics, repository.prefetchDownloadLyrics(song, listOf(LyricsProvider.LRCLIB)))
                assertEquals(saved, database.dao().cachedLyrics(song.id))
                assertEquals(2, calls)
            } finally {
                client.close()
                database.close()
                Files.deleteIfExists(path)
                Settings.replaceSharedForTest(null)
            }
        }

    @Test
    fun downloadLyricsUseMillisecondsAndPreserveUnknownDuration() =
        runBlocking {
            Settings.replaceSharedForTest(Settings(PropertiesSettings(Properties())))
            val durations = mutableListOf<Long?>()
            val fetcher =
                object : LyricsFetcher {
                    override suspend fun getLyrics(
                        title: String,
                        artist: String,
                        duration: Long?,
                        album: String?,
                        videoId: String?,
                        provider: LyricsProvider,
                        isVideoContext: Boolean,
                    ): LyricsWithProvider? {
                        durations += duration
                        return null
                    }
                }
            val client = HttpClient(CIO)
            try {
                val repository = LyricsRepository(fetcher, LyricsTranslationClient(client))
                val song = SongItem("download", "Song", null, emptyList(), null, duration = 123)
                repository.prefetchDownloadLyrics(song, listOf(LyricsProvider.LRCLIB))
                repository.prefetchDownloadLyrics(song.copy(duration = null), listOf(LyricsProvider.LRCLIB))
                assertEquals(listOf(123_000L, null), durations)
            } finally {
                client.close()
                Settings.replaceSharedForTest(null)
            }
        }

    @Test
    fun offlineAvailabilityKeepsBothSourcesAndDropsDeletedFiles() =
        runBlocking {
            val databasePath = Files.createTempFile("metrolist-offline-availability", ".db")
            Files.deleteIfExists(databasePath)
            val database = getDatabaseBuilder(databasePath.toFile()).build()
            val downloaded = Files.createTempFile("metrolist-downloaded", ".m4a")
            val cached = Files.createTempFile("metrolist-cached", ".m4a")
            try {
                val downloads =
                    listOf(
                        DownloadedFile("both", "Title", "Artist", null, downloaded.toString(), null, null, 0),
                        DownloadedFile("missing", "Missing", "Artist", null, downloaded.toString(), null, null, 0),
                    )
                val cachedIds = setOf("both", "missing")
                val available = {
                    desktopSongOfflineSnapshot(
                        downloads,
                        cachedIds,
                        downloadPath = { id -> downloaded.toString().takeIf { id == "both" && Files.exists(downloaded) } },
                        cachePath = { id -> cached.toString().takeIf { id == "both" && Files.exists(cached) } },
                    )
                }
                val provider =
                    desktopSongOfflineAvailability(
                        database = database,
                        downloads = { downloads },
                        cachedIds = { cachedIds },
                        downloadPath = { id -> downloaded.toString().takeIf { id == "both" && Files.exists(downloaded) } },
                        cachePath = { id -> cached.toString().takeIf { id == "both" && Files.exists(cached) } },
                    )
                assertEquals(setOf("both"), provider.songIds())
                assertEquals(setOf("both"), available().downloadedIds)
                assertEquals(setOf("both"), available().cachedIds)
                Files.delete(cached)
                assertEquals(setOf("both"), provider.songIds())
                assertEquals(setOf("both"), available().downloadedIds)
                assertEquals(emptySet(), available().cachedIds)
                Files.delete(downloaded)
                assertEquals(emptySet(), provider.songIds())
                assertEquals(emptySet(), available().downloadedIds)
            } finally {
                database.close()
                Files.deleteIfExists(databasePath)
                Files.deleteIfExists(downloaded)
                Files.deleteIfExists(cached)
            }
        }

    @Test
    fun legacyDownloadsAreRestoredBeforeOfflineQueriesAndKeepExistingMetadata() =
        runBlocking {
            val path = Files.createTempFile("metrolist-legacy-downloads", ".db")
            Files.deleteIfExists(path)
            var database = getDatabaseBuilder(path.toFile()).build()
            try {
                val legacy =
                    Json.decodeFromString<DownloadedFile>(
                        """{"songId":"legacy","title":"Legacy song","artist":"Legacy artist","thumbnailUrl":null,"audioFilePath":"/audio","thumbnailFilePath":null,"duration":120,"downloadDate":0}""",
                    )
                val existing = legacy.copy(songId = "existing", title = "Outdated title")
                database.dao().upsertHistorySong(SongItem("existing", "Saved title", null, emptyList(), null))
                val stored = assertNotNull(database.dao().getSongById("existing")).song
                database.dao().upsert(stored.copy(customTitle = "Custom title", liked = true))
                val provider =
                    desktopSongOfflineAvailability(
                        database = database,
                        downloads = { listOf(legacy, existing) },
                        cachedIds = { emptySet() },
                        downloadPath = { "/audio" },
                        cachePath = { null },
                    )
                val ids = provider.songIds()
                assertEquals(setOf("legacy", "existing"), ids)
                assertEquals(listOf("legacy"), database.searchOfflineMedia("Legacy song", ids).filterIsInstance<SongItem>().map { it.id })
                assertEquals("Custom title", assertNotNull(database.dao().getSongById("existing")).song.customTitle)
                assertTrue(assertNotNull(database.dao().getSongById("existing")).song.liked)
                database.close()
                database = getDatabaseBuilder(path.toFile()).build()
                restoreDownloadedSongs(database, listOf(legacy, existing))
                assertTrue(assertNotNull(database.dao().getSongById("legacy")).song.isDownloaded)
                assertEquals("Legacy artist", assertNotNull(database.dao().getSongById("legacy")).orderedArtists.single().name)
                assertEquals("Custom title", assertNotNull(database.dao().getSongById("existing")).song.customTitle)
            } finally {
                database.close()
                Files.deleteIfExists(path)
            }
        }

    @Test
    fun savedAudioMetadataRetainsLoudnessAndReadsOlderIndexes() {
        val cached =
            CachedSongEntry(
                "song",
                "Title",
                "Artist",
                "/audio",
                fileSize = 1000,
                cachedAt = 0,
                loudnessDb = 2.0,
                perceptualLoudnessDb = -17.0,
            )
        val download =
            DownloadedFile("song", "Title", "Artist", null, "/audio", null, null, 0, loudnessDb = 2.0, perceptualLoudnessDb = -17.0)
        val resources =
            listOf(
                Json.decodeFromString<CachedSongEntry>(Json.encodeToString(cached)).toPlaybackResource(song = null),
                Json.decodeFromString<DownloadedFile>(Json.encodeToString(download)).toPlaybackResource(song = null),
            )
        resources.forEach {
            assertEquals(2.0, it.loudnessDb)
            assertEquals(-17.0, it.perceptualLoudnessDb)
            assertEquals("/audio", it.url)
        }
        val oldCacheJson = Json.encodeToString(cached.copy(loudnessDb = null, perceptualLoudnessDb = null))
        val oldDownloadJson = Json.encodeToString(download.copy(loudnessDb = null, perceptualLoudnessDb = null))
        assertFalse(oldCacheJson.contains("loudnessDb"))
        assertFalse(oldDownloadJson.contains("loudnessDb"))
        assertEquals(null, Json.decodeFromString<CachedSongEntry>(oldCacheJson).toPlaybackResource(song = null).perceptualLoudnessDb)
        assertEquals(null, Json.decodeFromString<DownloadedFile>(oldDownloadJson).toPlaybackResource(song = null).perceptualLoudnessDb)
    }

    @Test
    fun audioFileNamesAreHumanReadableAndCrossPlatformSafe() {
        assertEquals(
            "Song _ Name by Artist_ Name",
            DownloadManager.buildAudioBaseName("Song / Name", "Artist: Name", "videoId"),
        )
        assertEquals("_CON", DownloadManager.buildAudioBaseName("CON", "", "videoId"))
        assertEquals("videoId", DownloadManager.buildAudioBaseName("", "", "videoId"))
    }

    @Test
    fun songIdsAllowOnlySafeFileNameCharacters() {
        assertTrue(DownloadManager.isValidSongId("dQw4w9WgXcQ"))
        assertTrue(DownloadManager.isValidSongId("LM_track-1"))
        assertFalse(DownloadManager.isValidSongId("../outside"))
        assertFalse(DownloadManager.isValidSongId("folder/song"))
        assertFalse(DownloadManager.isValidSongId("folder\\song"))
        assertFalse(DownloadManager.isValidSongId(""))
    }

    @Test
    fun playlistDownloadStateRequiresEveryLoadedSongToFinish() {
        val songs =
            listOf(
                SongItem("first", "First", null, emptyList(), null),
                SongItem("second", "Second", null, emptyList(), null),
            )

        assertEquals(DownloadState.NOT_DOWNLOADED, playlistDownloadState(emptyList(), emptyMap()))
        assertEquals(DownloadState.NOT_DOWNLOADED, playlistDownloadState(songs, mapOf("first" to DownloadState.DOWNLOADED)))
        assertEquals(
            DownloadState.DOWNLOADING,
            playlistDownloadState(songs, mapOf("first" to DownloadState.DOWNLOADED, "second" to DownloadState.QUEUED)),
        )
        assertEquals(
            DownloadState.DOWNLOADED,
            playlistDownloadState(songs, mapOf("first" to DownloadState.DOWNLOADED, "second" to DownloadState.DOWNLOADED)),
        )
        assertEquals(
            75,
            playlistDownloadProgress(
                songs,
                mapOf("first" to DownloadState.DOWNLOADED, "second" to DownloadState.DOWNLOADING),
                mapOf("second" to 0.5f),
            ),
        )
    }

    @Test
    fun deletingDownloadsCancelsTheirTrackedEnrichmentJobs() {
        val active = Job()
        val enrichment = Job()
        val retained = Job()
        val activeJobs = mutableMapOf<String, Job>("delete" to active)
        val enrichmentJobs = mutableMapOf<String, Job>("delete" to enrichment, "keep" to retained)

        cancelDownloadJobs(setOf("delete"), activeJobs)
        cancelDownloadJobs(setOf("delete"), enrichmentJobs)

        assertTrue(active.isCancelled)
        assertTrue(enrichment.isCancelled)
        assertFalse(retained.isCancelled)
        assertEquals(setOf("keep"), enrichmentJobs.keys)
    }

    @Test
    fun manifestsAreNotTreatedAsDownloadableMediaFiles() {
        val resource = PlaybackResource("video", "https://example.com/audio", emptyMap(), null, null)
        assertTrue(resource.isDirectFile)
        assertFalse(resource.copy(mimeType = "application/x-mpegURL").isDirectFile)
        assertFalse(resource.copy(mimeType = "application/vnd.apple.mpegurl").isDirectFile)
        assertFalse(resource.copy(mimeType = "application/dash+xml").isDirectFile)
        assertFalse(resource.copy(url = "https://example.com/live.m3u8?token=value").isDirectFile)
        assertFalse(resource.copy(url = "https://example.com/stream.mpd?token=value").isDirectFile)
    }

    @Test
    fun byteRangesRequireExactBoundedContentRange() {
        assertEquals(HttpByteRange(0, 0, 100), parseHttpByteRange("bytes 0-0/100"))
        assertEquals(HttpByteRange(10, 19, 100), parseHttpByteRange("bytes 10-19/100"))
        assertEquals(null, parseHttpByteRange("bytes 10-100/100"))
        assertEquals(null, parseHttpByteRange("garbage"))
    }

    @Test
    fun promotionReplacesDestinationAndRemovesTempFile() {
        val directory = Files.createTempDirectory("metrolist-download-test").toFile()
        try {
            val temp = directory.resolve("track.tmp").apply { writeBytes(ByteArray(2048) { 1 }) }
            val destination = directory.resolve("track.audio").apply { writeBytes(byteArrayOf(9)) }

            assertTrue(DownloadManager.promoteTempFile(temp, destination, minimumBytes = 1024))
            assertFalse(temp.exists())
            assertContentEquals(ByteArray(2048) { 1 }, destination.readBytes())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun promotionRejectsIncompleteTempFile() {
        val directory = Files.createTempDirectory("metrolist-download-test").toFile()
        try {
            val temp = directory.resolve("track.tmp").apply { writeBytes(ByteArray(32)) }
            val destination = directory.resolve("track.audio")

            assertFalse(DownloadManager.promoteTempFile(temp, destination, minimumBytes = 1024))
            assertFalse(destination.exists())
        } finally {
            directory.deleteRecursively()
        }
    }
}
