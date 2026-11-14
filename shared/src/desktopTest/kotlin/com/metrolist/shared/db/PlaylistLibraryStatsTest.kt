package com.metrolist.shared.db

import com.metrolist.shared.db.entities.AlbumEntity
import com.metrolist.shared.db.entities.CachedAudioEntity
import com.metrolist.shared.db.entities.KIND_DOWNLOAD
import com.metrolist.shared.db.entities.PlaylistEntity
import com.metrolist.shared.db.entities.PlaylistSongMap
import com.metrolist.shared.db.entities.SongEntity
import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.model.EpisodeItem
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.state.PodcastLibrary
import com.metrolist.shared.viewmodels.LibraryViewModel
import com.metrolist.shared.viewmodels.hydrateSongLibraryTimestamps
import com.metrolist.shared.viewmodels.mergeLibraryItems
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PlaylistLibraryStatsTest {
    @Test
    fun widgetPlaylistsRetainCustomCoversForBuiltinCollections() =
        runBlocking {
            val path = Files.createTempFile("widget-covers", ".db")
            Files.delete(path)
            val database = getDesktopDatabaseBuilder(path.toFile()).build()
            try {
                database.dao().upsert(
                    PlaylistEntity(
                        id = PlaylistEntity.WEEKLY_MOST_PLAYLIST_ID,
                        name = "Weekly",
                        isLocal = true,
                        bookmarkedAt = LocalDateTime(2026, 1, 1, 0, 0),
                        thumbnailUrl = "song.jpg",
                        customThumbnailUrl = "custom.jpg",
                    ),
                )
                val playlist =
                    database
                        .dao()
                        .widgetPlaylists()
                        .first()
                        .single()
                assertEquals("song.jpg", playlist.thumbnailUrl)
                assertEquals("custom.jpg", playlist.customThumbnailUrl)
            } finally {
                database.close()
                Files.deleteIfExists(path)
            }
        }

    @BeforeTest
    fun isolateSettings() {
        Settings.replaceSharedForTest(Settings(MapSettings()))
    }

    @AfterTest
    fun restoreSettings() {
        Settings.replaceSharedForTest(null)
    }

    @Test
    fun downloadedCollectionChecksAllTracksBeyondTheSqliteBindLimit() =
        runBlocking {
            val path = Files.createTempFile("download-many-tracks", ".db")
            Files.delete(path)
            val database = getDesktopDatabaseBuilder(path.toFile()).build()
            try {
                val ids = List(1200) { "song-$it" } + "quote'and\u0000nul"
                database.withDatabaseTransaction {
                    database.dao().upsert(PlaylistEntity(id = "local", name = "Local", isLocal = true))
                    ids.forEachIndexed { index, id ->
                        database.dao().upsert(SongEntity(id = id, title = id))
                        database.dao().insert(PlaylistSongMap(playlistId = "local", songId = id, position = index))
                    }
                }
                assertEquals(listOf("playlist:local"), database.dao().downloadedCollectionKeys(ids).first())
                assertEquals(emptyList(), database.dao().downloadedCollectionKeys(ids.dropLast(1)).first())
                assertEquals(emptyList(), database.dao().downloadedCollectionKeys(emptyList<String>()).first())
            } finally {
                database.close()
                Files.deleteIfExists(path)
            }
        }

    @Test
    fun batchedPreviewsMatchSinglePlaylistQueriesWithDuplicatesAndMissingArtwork() =
        runBlocking {
            val path = Files.createTempFile("metrolist-batch-previews", ".db")
            Files.deleteIfExists(path)
            val database = getDesktopDatabaseBuilder(path.toFile()).build()
            try {
                val dao = database.dao()
                for (id in listOf("first", "second", "empty")) {
                    dao.upsert(PlaylistEntity(id = id, name = id, isLocal = true))
                }
                val thumbnails = listOf(null, "", "one", "one", "two", "three", "four", "five")
                for ((index, thumbnail) in thumbnails.withIndex()) {
                    dao.upsert(SongEntity(id = "song-$index", title = "$index", thumbnailUrl = thumbnail, isVideo = index % 2 == 0))
                    dao.insert(PlaylistSongMap(playlistId = "first", songId = "song-$index", position = index))
                    dao.insert(PlaylistSongMap(playlistId = "second", songId = "song-$index", position = thumbnails.lastIndex - index))
                }
                val batch =
                    dao
                        .playlistPreviewThumbnails(listOf("first", "second", "empty") + (0..260).map { "unused-$it" })
                        .groupBy({ it.playlistId }, { it.thumbnailUrl })
                assertEquals(listOf("one", "two", "three", "four"), batch["first"])
                assertEquals(listOf("five", "four", "three", "two"), batch["second"])
                for (id in listOf("first", "second", "empty")) {
                    assertEquals(dao.playlistPreviewThumbnails(id), batch[id].orEmpty())
                }
            } finally {
                database.close()
                Files.deleteIfExists(path)
            }
        }

    @Test
    fun cachingByLocalIdKeepsRemoteIdentityAndReplacesRemovedSongs() =
        runBlocking {
            val path = Files.createTempFile("metrolist-playlist-identity", ".db")
            Files.deleteIfExists(path)
            val database = getDesktopDatabaseBuilder(path.toFile()).build()
            try {
                val dao = database.dao()
                dao.upsert(PlaylistEntity(id = "LPlocal", browseId = "PLremote", name = "Remote"))
                dao.upsert(SongEntity(id = "removed", title = "Removed"))
                dao.insert(PlaylistSongMap(playlistId = "LPlocal", songId = "removed", position = 0))
                database.cacheRemotePlaylist(
                    "LPlocal",
                    PlaylistItem("VLPLremote", "Remote", null),
                    listOf(SongItem("current", "Current", null, emptyList(), null, playlistSetVideoId = "set-current")),
                )
                assertEquals("PLremote", dao.findPlaylistEntity("LPlocal")?.browseId)
                assertEquals(listOf("current"), dao.playlistSongs("LPlocal").first().map { it.song.id })
                database.cacheRemotePlaylist("LPlocal", PlaylistItem("VLPLremote", "Remote", null), emptyList())
                assertEquals(emptyList(), dao.playlistSongs("LPlocal").first())
                assertEquals(0, dao.findPlaylistEntity("LPlocal")?.remoteSongCount)
            } finally {
                database.close()
                Files.deleteIfExists(path)
            }
        }

    @Test
    fun cachingCompleteRemoteMembershipPreservesLibraryAndSongState() =
        runBlocking {
            val path = Files.createTempFile("metrolist-playlist-cache", ".db")
            Files.deleteIfExists(path)
            val database = getDesktopDatabaseBuilder(path.toFile()).build()
            try {
                val dao = database.dao()
                val entity =
                    PlaylistEntity(id = "stored", browseId = "PLremote", name = "Remote", bookmarkedAt = LocalDateTime(2026, 1, 1, 0, 0))
                dao.upsert(entity)
                dao.upsert(SongEntity(id = "one", title = "One", liked = true, totalPlayTime = 123))
                database.cacheRemotePlaylist(
                    "VLPLremote",
                    PlaylistItem("VLPLremote", "Remote", null),
                    listOf(
                        SongItem("one", "One", null, emptyList(), null, playlistSetVideoId = "set-one"),
                        SongItem("two", "Two", null, emptyList(), null, playlistSetVideoId = "set-two"),
                    ) +
                        (3..1250).map { index ->
                            SongItem("song-$index", "Song $index", null, emptyList(), null, playlistSetVideoId = "set-$index")
                        },
                )
                assertEquals(entity.bookmarkedAt, dao.findPlaylistEntity("PLremote")?.bookmarkedAt)
                assertEquals(1250, dao.findPlaylistEntity("PLremote")?.remoteSongCount)
                assertEquals(
                    listOf("set-one", "set-two") + (3..1250).map { "set-$it" },
                    dao.playlistSongs("stored").first().map { it.map.setVideoId },
                )
                assertEquals(true, dao.getSongById("one")?.song?.liked)
                assertEquals(123L, dao.getSongById("one")?.song?.totalPlayTime)
            } finally {
                database.close()
                Files.deleteIfExists(path)
            }
        }

    @Test
    fun downloadedBadgesRequireEveryTrackAndReactToRemoval() =
        runBlocking {
            val path = Files.createTempFile("metrolist-download-badges", ".db")
            Files.deleteIfExists(path)
            val database = getDesktopDatabaseBuilder(path.toFile()).build()
            try {
                val dao = database.dao()
                dao.upsert(AlbumEntity(id = "album", title = "Album", songCount = 2, duration = 0))
                dao.upsert(PlaylistEntity(id = "local", name = "Local", isLocal = true))
                dao.upsert(PlaylistEntity(id = "partial", name = "Partial", browseId = "PLpartial", remoteSongCount = 3))
                dao.upsert(PlaylistEntity(id = "empty", name = "Empty", isLocal = true))
                dao.upsert(PlaylistEntity(id = "unknown", name = "Unknown", browseId = "PLunknown"))
                for ((index, id) in listOf("a", "b").withIndex()) {
                    dao.upsert(SongEntity(id = id, title = id, albumId = "album"))
                    dao.insert(PlaylistSongMap(playlistId = "local", songId = id, position = index))
                    dao.insert(PlaylistSongMap(playlistId = "partial", songId = id, position = index))
                    dao.insert(PlaylistSongMap(playlistId = "unknown", songId = id, position = index))
                }

                fun download(id: String) =
                    CachedAudioEntity(
                        songId = id,
                        title = id,
                        artist = "Artist",
                        filePath = "/$id",
                        fileSize = 10,
                        cachedAt = 1,
                        lastAccessedAt = 1,
                        cacheLocation = "internal",
                        kind = KIND_DOWNLOAD,
                    )
                dao.upsert(PlaylistEntity(id = "copy-one", name = "Copy one", browseId = "PLcopy", remoteSongCount = 1))
                dao.upsert(PlaylistEntity(id = "copy-two", name = "Copy two", browseId = "PLcopy", remoteSongCount = 1))
                dao.insert(PlaylistSongMap(playlistId = "copy-one", songId = "a"))
                dao.insert(PlaylistSongMap(playlistId = "copy-two", songId = "b"))
                assertEquals(listOf("playlist:PLcopy"), dao.downloadedCollectionKeys(listOf("a")).first())
                dao.clearPlaylist("copy-one")
                dao.clearPlaylist("copy-two")
                assertEquals(emptyList(), dao.downloadedCollectionKeys(listOf("a")).first())
                assertEquals(setOf("album:album", "playlist:local"), dao.downloadedCollectionKeys(listOf("a", "b")).first().toSet())
                dao.upsert(download("a"))
                assertEquals(emptyList(), dao.downloadedCollectionKeys().first())
                dao.upsert(download("b"))
                assertEquals(setOf("album:album", "playlist:local"), dao.downloadedCollectionKeys().first().toSet())
                dao.upsert(download("b").copy(kind = "CACHE"))
                assertEquals(emptyList(), dao.downloadedCollectionKeys().first())
                dao.upsert(download("b").copy(fileSize = 0))
                assertEquals(emptyList(), dao.downloadedCollectionKeys().first())
            } finally {
                database.close()
                Files.deleteIfExists(path)
            }
        }

    @Test
    fun largeLibrariesKeepPlaylistStatisticsWithinSqliteParameterLimits() =
        runBlocking {
            val directory = Files.createTempDirectory("playlist-stats-bind-limit")
            val database = getDesktopDatabaseBuilder(directory.resolve("music.db?limit_variable_number=999").toFile()).build()
            try {
                val dao = database.dao()
                val ids = (0..1000).map { "playlist-$it" }
                database.withDatabaseTransaction {
                    dao.upsert(SongEntity(id = "song", title = "Song", totalPlayTime = 123))
                    ids.forEach { id ->
                        dao.upsert(PlaylistEntity(id = id, name = id, isLocal = true))
                        dao.insert(PlaylistSongMap(playlistId = id, songId = "song"))
                    }
                }
                val stats = dao.playlistLibraryStats(ids + ids.first() + "missing")
                assertEquals(ids.toSet(), stats.map { it.playlistId }.toSet())
                assertEquals(ids.size, stats.size)
                stats.forEach {
                    assertEquals(1, it.songCount)
                    assertEquals(123L, it.playtime)
                }
                assertEquals(emptyList(), dao.playlistLibraryStats(emptyList()))
            } finally {
                database.close()
                directory.toFile().deleteRecursively()
            }
        }

    @Test
    fun loadsCountsAndPlaytimeForAllPlaylistsInOneAggregate() =
        runBlocking {
            val path = Files.createTempFile("metrolist-playlist-library-stats", ".db")
            Files.deleteIfExists(path)
            val database = getDesktopDatabaseBuilder(path.toFile()).build()
            val scope = CoroutineScope(StandardTestDispatcher())
            try {
                val dao = database.dao()
                dao.upsert(
                    PlaylistEntity(
                        id = "filled",
                        name = "Filled",
                        isLocal = true,
                        thumbnailUrl = "legacy-collage",
                        remoteSongCount = 1,
                    ),
                )
                dao.upsert(
                    PlaylistEntity(
                        id = "custom-cover",
                        name = "Custom cover",
                        isLocal = true,
                        thumbnailUrl = "legacy-collage",
                        customThumbnailUrl = "custom-cover.jpg",
                    ),
                )
                dao.upsert(PlaylistEntity(id = "empty", name = "Empty", isLocal = true, remoteSongCount = 5))
                val songAddedAt = LocalDateTime(2026, 1, 3, 4, 5)
                dao.upsert(
                    SongEntity(id = "one", title = "One", thumbnailUrl = "first-cover", totalPlayTime = 120, inLibrary = songAddedAt),
                )
                dao.upsert(SongEntity(id = "two", title = "Two", thumbnailUrl = "second-cover", totalPlayTime = -10))
                dao.upsert(SongEntity(id = "episode", title = "Episode", isEpisode = true, inLibrary = songAddedAt))
                PodcastLibrary.synchronize(database, emptyList(), listOf(EpisodeItem("episode", "Episode", null)))
                assertEquals(songAddedAt, dao.getSongById("episode")?.song?.inLibrary)
                dao.insert(PlaylistSongMap(playlistId = "filled", songId = "one", position = 0))
                dao.insert(PlaylistSongMap(playlistId = "filled", songId = "two", position = 1))

                val stats = dao.playlistLibraryStats(listOf("filled", "empty")).associateBy { it.playlistId }

                assertEquals(2, stats.getValue("filled").songCount)
                assertEquals(120L, stats.getValue("filled").playtime)
                assertEquals(0, stats.getValue("empty").songCount)
                assertNull(stats.getValue("empty").playtime)

                val expectedSongTimestamp = songAddedAt.toInstant(TimeZone.UTC).toEpochMilliseconds()
                val timestampedSongs =
                    hydrateSongLibraryTimestamps(
                        database,
                        List(999) { SongItem("missing-$it", "Missing", null, emptyList(), null) } +
                            listOf(
                                SongItem("one", "One", null, emptyList(), null),
                                EpisodeItem("episode", "Episode", null),
                            ),
                    )
                val expectedTimestamps = List<Long?>(999) { null } + listOf(expectedSongTimestamp, expectedSongTimestamp)
                assertEquals(expectedTimestamps, timestampedSongs.map { it.libraryAddedAt })
                assertEquals(expectedTimestamps, timestampedSongs.map { it.libraryUpdatedAt })

                dao.upsert(PlaylistEntity(id = "remote", name = "Remote", browseId = "PLremote"))
                dao.upsert(PlaylistEntity(id = "known", name = "Known", browseId = "PLknown", remoteSongCount = 7))
                dao.insert(PlaylistSongMap(playlistId = "known", songId = "one", position = 0))
                val chooserCounts = dao.editablePlaylistsByCreateDateAsc().first().associate { it.id to it.songCount }
                assertEquals(2, chooserCounts.getValue("filled"), "Local membership must replace a stale stored count")
                assertEquals(0, chooserCounts.getValue("empty"), "An empty local playlist must not retain a stale count")
                assertEquals(7, chooserCounts.getValue("known"), "A partial local membership must not replace the remote total")
                dao.upsert(
                    AlbumEntity(
                        id = "album",
                        title = "Album",
                        songCount = 0,
                        duration = 0,
                        bookmarkedAt = LocalDateTime(2026, 1, 1, 0, 0),
                    ),
                )
                val local = LibraryViewModel(scope).loadLocalItems(database)
                val playlists = local.filterIsInstance<PlaylistItem>().associateBy { it.id }
                assertEquals("2", playlists.getValue("filled").songCount)
                assertEquals("0", playlists.getValue("empty").songCount)
                assertEquals(listOf("first-cover", "second-cover"), playlists.getValue("filled").artworkThumbnails)
                assertEquals("legacy-collage", playlists.getValue("filled").thumbnail)
                assertEquals("custom-cover.jpg", playlists.getValue("custom-cover").thumbnail)
                assertEquals(emptyList(), playlists.getValue("empty").artworkThumbnails)
                assertEquals(emptyList(), playlists.getValue("PLknown").artworkThumbnails)
                assertEquals("7", playlists.getValue("PLknown").songCount)
                assertNotNull(playlists.getValue("PLknown").libraryAddedAt)
                assertNotNull(playlists.getValue("PLknown").libraryUpdatedAt)
                assertNull(playlists.getValue("PLremote").songCount)
                assertNull(local.filterIsInstance<AlbumItem>().single().songCount)
                assertNotNull(local.filterIsInstance<AlbumItem>().single().libraryAddedAt)
                assertEquals(expectedSongTimestamp, local.filterIsInstance<EpisodeItem>().single().libraryAddedAt)
                val merged =
                    mergeLibraryItems(
                        local,
                        listOf(
                            PlaylistItem(id = "PLremote", title = "Remote", thumbnail = null, songCount = "25"),
                            AlbumItem(id = "album", title = "Album", thumbnail = null, artists = emptyList(), songCount = 12),
                        ),
                    )
                assertEquals("25", merged.filterIsInstance<PlaylistItem>().first { it.id == "PLremote" }.songCount)
                assertEquals(12, merged.filterIsInstance<AlbumItem>().single().songCount)
            } finally {
                scope.cancel()
                database.close()
                Files.deleteIfExists(path)
            }
        }
}
