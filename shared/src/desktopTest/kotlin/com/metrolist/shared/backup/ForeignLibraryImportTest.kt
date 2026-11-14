package com.metrolist.shared.backup

import androidx.sqlite.execSQL
import com.metrolist.shared.db.JdbcSQLiteDriver
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.db.entities.PlaylistEntity
import com.metrolist.shared.db.entities.PlaylistSongMap
import com.metrolist.shared.db.entities.SongEntity
import com.metrolist.shared.db.getDatabaseBuilder
import com.metrolist.shared.model.SongItem
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// DDL is copied from the empty upstream fixtures (InnerTune v12, OuterTune v20, ViMusic v23, Kreate v36, SimpMusic v26).
class ForeignLibraryImportTest {
    private val directory = Files.createTempDirectory("foreign-import").toFile()

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    @Test
    fun innerTuneBackupMergesIntoExistingLibraryWithoutDuplicatesOnReimport() =
        runBlocking {
            val source =
                sourceDatabase(
                    "innertune.db",
                    INNERTUNE_DDL +
                        listOf(
                            "INSERT INTO song VALUES ('aaaaaaaaaaa','Liked song',200,'https://img/a',NULL,NULL,1,5000,1700000000000)",
                            "INSERT INTO song VALUES ('bbbbbbbbbbb','Playlist song',180,NULL,NULL,NULL,0,0,NULL)",
                            "INSERT INTO song VALUES ('/sdcard/x.mp3','Local file',10,NULL,NULL,NULL,1,0,NULL)",
                            "INSERT INTO song VALUES ('ccccccccccc','Cached only',100,NULL,NULL,NULL,0,0,NULL)",
                            "INSERT INTO artist VALUES ('UCartist0000000000000001','Artist One',NULL,0,1700000000000)",
                            "INSERT INTO artist VALUES ('UCartist0000000000000002','Not saved',NULL,0,NULL)",
                            "INSERT INTO album VALUES ('MPREb_album1','Album One',2020,NULL,NULL,10,3000,0,1700000000000)",
                            "INSERT INTO song_artist_map VALUES ('bbbbbbbbbbb','UCartist0000000000000001',0)",
                            "INSERT INTO playlist VALUES ('p1','Road',NULL)",
                            "INSERT INTO playlist VALUES ('p2','Saved mix','PLremote')",
                            "INSERT INTO playlist_song_map (playlistId,songId,position) VALUES ('p1','aaaaaaaaaaa',1)",
                            "INSERT INTO playlist_song_map (playlistId,songId,position) VALUES ('p1','bbbbbbbbbbb',0)",
                            "INSERT INTO playlist_song_map (playlistId,songId,position) VALUES ('p1','/sdcard/x.mp3',2)",
                            "INSERT INTO playlist_song_map (playlistId,songId,position) VALUES ('p2','aaaaaaaaaaa',0)",
                            "INSERT INTO event (songId,timestamp,playTime) VALUES ('aaaaaaaaaaa',1700000100000,90000)",
                        ),
                )
            val library = read(source)
            assertEquals(ForeignLibraryFamily.INNERTUNE, library.family)

            withDestination { database ->
                val dao = database.dao()
                dao.upsert(SongEntity(id = "aaaaaaaaaaa", title = "Existing title"))
                dao.upsert(SongEntity(id = "bbbbbbbbbbb", title = "Existing B"))
                val road = PlaylistEntity(name = "Road", isLocal = true)
                dao.insert(road)
                dao.insert(PlaylistSongMap(playlistId = road.id, songId = "bbbbbbbbbbb", position = 0))

                val likes = mutableListOf<SongItem>()
                val summary = database.importForeignLibrary(library) { likes += it }

                assertEquals(2, summary.songs)
                assertEquals(1, summary.likes)
                assertEquals(1, summary.plays)
                assertEquals(2, summary.playlists)
                assertEquals(1, summary.skippedSongs)
                assertEquals(listOf("aaaaaaaaaaa"), likes.map(SongItem::id))
                val liked = assertNotNull(dao.getSongById("aaaaaaaaaaa")).song
                assertEquals("Existing title", liked.title)
                assertTrue(liked.liked)
                assertNotNull(liked.inLibrary)
                assertEquals(5000, liked.totalPlayTime)
                assertNull(dao.getSongById("ccccccccccc"))
                assertNull(dao.getSongById("/sdcard/x.mp3"))
                assertEquals(listOf("bbbbbbbbbbb", "aaaaaaaaaaa"), playlistSongIds(database, road.id))
                val remote = assertNotNull(dao.findPlaylistEntity("PLremote"))
                assertFalse(remote.isLocal)
                assertEquals(listOf("aaaaaaaaaaa"), playlistSongIds(database, remote.id))
                assertNotNull(dao.getArtistById("UCartist0000000000000001")?.bookmarkedAt)
                assertNull(dao.getArtistById("UCartist0000000000000002"))
                assertNotNull(
                    dao
                        .album("MPREb_album1")
                        .first()
                        ?.album
                        ?.bookmarkedAt,
                )

                val again = database.importForeignLibrary(library) { likes += it }
                assertEquals(0, again.likes)
                assertEquals(0, again.plays)
                assertEquals(1, likes.size)
                assertEquals(1, dao.eventCount().first())
                assertEquals(listOf("bbbbbbbbbbb", "aaaaaaaaaaa"), playlistSongIds(database, road.id))
                assertEquals(1, dao.allPlaylists().first().count { it.name == "Road" })
            }
        }

    @Test
    fun outerTuneSchemaWithoutPlayTimeColumnsIsRead() {
        val source =
            sourceDatabase(
                "outertune.db",
                listOf(
                    "CREATE TABLE `song` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `duration` INTEGER NOT NULL, `thumbnailUrl` TEXT, `inLibrary` INTEGER, `isLocal` INTEGER NOT NULL DEFAULT 0, `localPath` TEXT, `dateDownload` INTEGER, `liked` INTEGER NOT NULL, `likedDate` INTEGER, `trackNumber` INTEGER, `discNumber` INTEGER, `albumId` TEXT, `albumName` TEXT, `year` INTEGER, `date` INTEGER, `dateModified` INTEGER, PRIMARY KEY(`id`))",
                    "CREATE TABLE `playlist` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `browseId` TEXT, `isEditable` INTEGER NOT NULL DEFAULT 1, `bookmarkedAt` INTEGER, `thumbnailUrl` TEXT, `remoteSongCount` INTEGER, `playEndpointParams` TEXT, `shuffleEndpointParams` TEXT, `radioEndpointParams` TEXT, `isLocal` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`id`))",
                    "CREATE TABLE `playlist_song_map` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `playlistId` TEXT NOT NULL, `songId` TEXT NOT NULL, `position` INTEGER NOT NULL, `setVideoId` TEXT)",
                    "INSERT INTO song (id,title,duration,liked,likedDate,isLocal) VALUES ('aaaaaaaaaaa','Song',100,1,1600000000000,0)",
                    "INSERT INTO song (id,title,duration,liked,isLocal,localPath) VALUES ('dddddddddddd','Local',100,1,1,'/x.mp3')",
                ),
            )
        val library = read(source)
        assertEquals(ForeignLibraryFamily.INNERTUNE, library.family)
        val song = library.songs.single { it.id == "aaaaaaaaaaa" }
        assertTrue(song.liked)
        assertEquals(1600000000000, song.likedAt)
        assertEquals(0, song.totalPlayTimeMs)
        assertTrue(library.songs.single { it.id == "dddddddddddd" }.isLocal)
    }

    @Test
    fun viMusicFamilyMapsLikesDislikesDurationsArtistsAndHistory() {
        val source =
            sourceDatabase(
                "vimusic.db",
                listOf(
                    "CREATE TABLE `Song` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `artistsText` TEXT, `durationText` TEXT, `thumbnailUrl` TEXT, `likedAt` INTEGER, `totalPlayTimeMs` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                    "CREATE TABLE `Artist` (`id` TEXT NOT NULL, `name` TEXT, `thumbnailUrl` TEXT, `timestamp` INTEGER, `bookmarkedAt` INTEGER, PRIMARY KEY(`id`))",
                    "CREATE TABLE `Album` (`id` TEXT NOT NULL, `title` TEXT, `thumbnailUrl` TEXT, `year` TEXT, `authorsText` TEXT, `shareUrl` TEXT, `timestamp` INTEGER, `bookmarkedAt` INTEGER, PRIMARY KEY(`id`))",
                    "CREATE TABLE `SongArtistMap` (`songId` TEXT NOT NULL, `artistId` TEXT NOT NULL, PRIMARY KEY(`songId`, `artistId`))",
                    "CREATE TABLE `SongAlbumMap` (`songId` TEXT NOT NULL, `albumId` TEXT NOT NULL, `position` INTEGER, PRIMARY KEY(`songId`, `albumId`))",
                    "CREATE TABLE `Playlist` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `browseId` TEXT)",
                    "CREATE TABLE `SongPlaylistMap` (`songId` TEXT NOT NULL, `playlistId` INTEGER NOT NULL, `position` INTEGER NOT NULL, PRIMARY KEY(`songId`, `playlistId`))",
                    "CREATE TABLE `Event` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `songId` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `playTime` INTEGER NOT NULL)",
                    "INSERT INTO Song VALUES ('aaaaaaaaaaa','Liked','A, B','3:25',NULL,1700000000000,1000)",
                    "INSERT INTO Song VALUES ('bbbbbbbbbbb','Disliked','C','1:02:03',NULL,-1,0)",
                    "INSERT INTO Artist VALUES ('UCartist0000000000000001','Artist A',NULL,0,1700000000000)",
                    "INSERT INTO Album VALUES ('MPREb_album1','Album',NULL,'2019',NULL,NULL,0,NULL)",
                    "INSERT INTO SongArtistMap VALUES ('aaaaaaaaaaa','UCartist0000000000000001')",
                    "INSERT INTO SongAlbumMap VALUES ('aaaaaaaaaaa','MPREb_album1',0)",
                    "INSERT INTO Playlist (name,browseId) VALUES ('Mine',NULL)",
                    "INSERT INTO SongPlaylistMap VALUES ('bbbbbbbbbbb',1,1)",
                    "INSERT INTO SongPlaylistMap VALUES ('aaaaaaaaaaa',1,0)",
                    "INSERT INTO Event (songId,timestamp,playTime) VALUES ('aaaaaaaaaaa',1700000100000,1000)",
                ),
            )
        val library = read(source)
        assertEquals(ForeignLibraryFamily.VIMUSIC, library.family)
        val liked = library.songs.single { it.id == "aaaaaaaaaaa" }
        assertTrue(liked.liked)
        assertEquals(205, liked.durationSeconds)
        assertEquals(listOf(ForeignArtist("UCartist0000000000000001", "Artist A")), liked.artists)
        assertEquals("Album", liked.albumName)
        val disliked = library.songs.single { it.id == "bbbbbbbbbbb" }
        assertFalse(disliked.liked)
        assertEquals(3723, disliked.durationSeconds)
        assertEquals(listOf(ForeignArtist(null, "C")), disliked.artists)
        assertEquals(listOf(ForeignPlaylist("Mine", null, listOf("aaaaaaaaaaa", "bbbbbbbbbbb"))), library.playlists)
        assertEquals(listOf(ForeignPlay("aaaaaaaaaaa", 1700000100000, 1000)), library.plays)
        assertEquals(listOf("UCartist0000000000000001"), library.artists.map(ForeignBookmark::id))
        assertTrue(library.albums.isEmpty())
    }

    @Test
    fun kreateSnakeCaseSchemaIsRead() {
        val source =
            sourceDatabase(
                "data.db",
                listOf(
                    "CREATE TABLE `songs` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `artists` TEXT, `duration` TEXT, `thumbnail_url` TEXT, `liked_at` INTEGER, `total_playtime` INTEGER NOT NULL, `is_explicit` INTEGER NOT NULL, `is_local` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                    "CREATE TABLE `playlists` (`name` TEXT NOT NULL, `browse_id` TEXT, `editable` INTEGER NOT NULL, `youtube` INTEGER NOT NULL, `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `is_pinned` INTEGER NOT NULL, `is_monthly` INTEGER NOT NULL)",
                    "CREATE TABLE `song_playlist_map` (`song_id` TEXT NOT NULL, `playlist_id` INTEGER NOT NULL, `position` INTEGER NOT NULL, `set_video_id` TEXT, PRIMARY KEY(`song_id`, `playlist_id`))",
                    "CREATE TABLE `playback_history` (`song_id` TEXT NOT NULL, `created_at` INTEGER NOT NULL, `time_spent` INTEGER NOT NULL, `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL)",
                    "INSERT INTO songs VALUES ('aaaaaaaaaaa','Song','Someone','4:00',NULL,1700000000000,240000,1,0)",
                    "INSERT INTO playlists (name,browse_id,editable,youtube,is_pinned,is_monthly) VALUES ('Synced','VLPLsynced',1,1,0,0)",
                    "INSERT INTO song_playlist_map VALUES ('aaaaaaaaaaa',1,0,NULL)",
                    "INSERT INTO playback_history (song_id,created_at,time_spent) VALUES ('aaaaaaaaaaa',1700000200000,240000)",
                ),
            )
        val library = read(source)
        assertEquals(ForeignLibraryFamily.KREATE, library.family)
        val song = library.songs.single()
        assertTrue(song.liked)
        assertTrue(song.explicit)
        assertEquals(240, song.durationSeconds)
        assertEquals(240000, song.totalPlayTimeMs)
        assertEquals(listOf(ForeignPlaylist("Synced", "PLsynced", listOf("aaaaaaaaaaa"), isEditable = true)), library.playlists)
        assertEquals(listOf(ForeignPlay("aaaaaaaaaaa", 1700000200000, 240000)), library.plays)
    }

    @Test
    fun simpMusicZipBackupIsStagedDetectedAndMapped() =
        runBlocking {
            val source =
                sourceDatabase(
                    "Music Database",
                    listOf(
                        "CREATE TABLE `song` (`videoId` TEXT NOT NULL, `albumId` TEXT, `albumName` TEXT, `artistId` TEXT, `artistName` TEXT, `duration` TEXT NOT NULL, `durationSeconds` INTEGER NOT NULL, `isAvailable` INTEGER NOT NULL, `isExplicit` INTEGER NOT NULL, `likeStatus` TEXT NOT NULL, `thumbnails` TEXT, `title` TEXT NOT NULL, `videoType` TEXT NOT NULL, `category` TEXT, `resultType` TEXT, `liked` INTEGER NOT NULL, `totalPlayTime` INTEGER NOT NULL, `downloadState` INTEGER NOT NULL, `favoriteAt` INTEGER, `downloadedAt` INTEGER, `inLibrary` INTEGER NOT NULL, `canvasUrl` TEXT, `canvasThumbUrl` TEXT, PRIMARY KEY(`videoId`))",
                        "CREATE TABLE `local_playlist` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL, `thumbnail` TEXT, `inLibrary` INTEGER NOT NULL, `downloadedAt` INTEGER, `downloadState` INTEGER NOT NULL, `youtubePlaylistId` TEXT, `youtube_sync_state` INTEGER NOT NULL DEFAULT 0, `tracks` TEXT)",
                        "CREATE TABLE `pair_song_local_playlist` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `playlistId` INTEGER NOT NULL, `songId` TEXT NOT NULL, `position` INTEGER NOT NULL, `inPlaylist` INTEGER NOT NULL)",
                        "CREATE TABLE `playback_event` (`eventId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `timestamp` INTEGER NOT NULL, `videoId` TEXT NOT NULL, `albumBrowseId` TEXT, `durationSecond` INTEGER NOT NULL, `listenedSecond` INTEGER NOT NULL)",
                        "CREATE TABLE `playlist` (`id` TEXT NOT NULL, `author` TEXT, `description` TEXT NOT NULL, `duration` TEXT NOT NULL, `durationSeconds` INTEGER NOT NULL, `privacy` TEXT NOT NULL, `thumbnails` TEXT NOT NULL, `title` TEXT NOT NULL, `trackCount` INTEGER NOT NULL, `tracks` TEXT, `year` TEXT, `liked` INTEGER NOT NULL, `inLibrary` INTEGER NOT NULL, `favoriteAt` INTEGER, `downloadedAt` INTEGER, `downloadState` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                        "CREATE TABLE `artist` (`channelId` TEXT NOT NULL, `name` TEXT NOT NULL, `thumbnails` TEXT, `followed` INTEGER NOT NULL, `followedAt` INTEGER, `inLibrary` INTEGER NOT NULL, `nameLogoUrl` TEXT, `nameLogoColor` TEXT, PRIMARY KEY(`channelId`))",
                        "INSERT INTO song VALUES ('aaaaaaaaaaa','MPREb_album1','Album','[\"UCartist0000000000000001\",null]','[\"First\",\"Second\"]','3:00',180,1,0,'LIKE','https://img/a','Song','MUSIC_VIDEO_TYPE_ATV',NULL,NULL,1,7,0,1700000000000,NULL,1700000000000,NULL,NULL)",
                        "INSERT INTO local_playlist (title,inLibrary,downloadState) VALUES ('Local',0,0)",
                        "INSERT INTO pair_song_local_playlist (playlistId,songId,position,inPlaylist) VALUES (1,'aaaaaaaaaaa',0,1700000000000)",
                        "INSERT INTO playback_event (timestamp,videoId,durationSecond,listenedSecond) VALUES (1700000300000,'aaaaaaaaaaa',180,120)",
                        "INSERT INTO playlist VALUES ('VLPLsaved',NULL,'','',0,'PUBLIC','','Saved',1,'[\"aaaaaaaaaaa\"]',NULL,1,0,NULL,NULL,0)",
                        "INSERT INTO artist VALUES ('UCartist0000000000000001','First',NULL,1,1700000000000,0,NULL,NULL)",
                    ),
                )
            val archive = directory.resolve("simpmusic.backup")
            ZipOutputStream(archive.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("settings.preferences_pb"))
                zip.write(byteArrayOf(1, 2, 3))
                zip.putNextEntry(ZipEntry("Music Database"))
                source.inputStream().use { it.copyTo(zip) }
                zip.putNextEntry(ZipEntry("exoplayer_internal.db"))
                source.inputStream().use { it.copyTo(zip) }
            }
            val staged = archive.inputStream().use { stageForeignDatabases(it, directory.resolve("staging").apply { mkdirs() }) }
            assertEquals(listOf("Music Database"), staged.map(File::getName))

            val library = read(staged.single())
            assertEquals(ForeignLibraryFamily.SIMPMUSIC, library.family)
            val song = library.songs.single()
            assertEquals(listOf(ForeignArtist("UCartist0000000000000001", "First"), ForeignArtist(null, "Second")), song.artists)
            assertEquals(0, song.totalPlayTimeMs)
            assertEquals(1700000000000, song.likedAt)
            assertEquals(
                listOf(
                    ForeignPlaylist("Local", null, listOf("aaaaaaaaaaa"), true),
                    ForeignPlaylist("Saved", "PLsaved", listOf("aaaaaaaaaaa")),
                ),
                library.playlists,
            )
            assertEquals(listOf(ForeignPlay("aaaaaaaaaaa", 1700000300000, 120000)), library.plays)

            withDestination { database ->
                val summary = database.importForeignLibrary(library) {}
                assertEquals(1, summary.likes)
                assertEquals(2, summary.playlists)
                assertNotNull(database.dao().findPlaylistByName("Local"))
                assertNotNull(database.dao().getArtistById("UCartist0000000000000001")?.bookmarkedAt)
            }
        }

    @Test
    fun rawDatabasesAreStagedAndUnknownFilesAreRejected() {
        val source = sourceDatabase("raw.db", listOf("CREATE TABLE unrelated (id INTEGER)"))
        val staged = source.inputStream().use { stageForeignDatabases(it, directory.resolve("raw").apply { mkdirs() }) }
        assertNull(JdbcSQLiteDriver().open(staged.single().path).use { it.detectForeignLibraryFamily() })

        val zip = ByteArrayOutputStream()
        ZipOutputStream(zip).use {
            it.putNextEntry(ZipEntry("notes.txt"))
            it.write("hello".encodeToByteArray())
        }
        assertFailsWith<UnsupportedForeignLibraryException> {
            stageForeignDatabases(zip.toByteArray().inputStream(), directory.resolve("zip").apply { mkdirs() })
        }
    }

    @Test
    fun durationTextParsing() {
        assertEquals(59, parseDurationText("0:59"))
        assertEquals(3723, parseDurationText("1:02:03"))
        assertNull(parseDurationText("live"))
        assertNull(parseDurationText("-1:00"))
    }

    private fun sourceDatabase(
        name: String,
        statements: List<String>,
    ): File {
        val file = directory.resolve(name)
        JdbcSQLiteDriver().open(file.absolutePath).use { connection -> statements.forEach(connection::execSQL) }
        return file
    }

    private fun read(file: File): ForeignLibrary = JdbcSQLiteDriver().open(file.absolutePath).use { it.readForeignLibrary() }

    private suspend fun withDestination(block: suspend (MusicDatabase) -> Unit) {
        val database = getDatabaseBuilder(directory.resolve("metrolist.db")).build()
        try {
            block(database)
        } finally {
            database.close()
        }
    }

    private suspend fun playlistSongIds(
        database: MusicDatabase,
        playlistId: String,
    ): List<String> =
        database
            .dao()
            .playlistSongs(playlistId)
            .first()
            .map { it.song.id }

    private companion object {
        val INNERTUNE_DDL =
            listOf(
                "CREATE TABLE `song` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `duration` INTEGER NOT NULL, `thumbnailUrl` TEXT, `albumId` TEXT, `albumName` TEXT, `liked` INTEGER NOT NULL, `totalPlayTime` INTEGER NOT NULL, `inLibrary` INTEGER, PRIMARY KEY(`id`))",
                "CREATE TABLE `artist` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `thumbnailUrl` TEXT, `lastUpdateTime` INTEGER NOT NULL, `bookmarkedAt` INTEGER, PRIMARY KEY(`id`))",
                "CREATE TABLE `album` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `year` INTEGER, `thumbnailUrl` TEXT, `themeColor` INTEGER, `songCount` INTEGER NOT NULL, `duration` INTEGER NOT NULL, `lastUpdateTime` INTEGER NOT NULL, `bookmarkedAt` INTEGER, PRIMARY KEY(`id`))",
                "CREATE TABLE `playlist` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `browseId` TEXT, PRIMARY KEY(`id`))",
                "CREATE TABLE `song_artist_map` (`songId` TEXT NOT NULL, `artistId` TEXT NOT NULL, `position` INTEGER NOT NULL, PRIMARY KEY(`songId`, `artistId`), FOREIGN KEY(`songId`) REFERENCES `song`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`artistId`) REFERENCES `artist`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                "CREATE TABLE `playlist_song_map` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `playlistId` TEXT NOT NULL, `songId` TEXT NOT NULL, `position` INTEGER NOT NULL, FOREIGN KEY(`playlistId`) REFERENCES `playlist`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`songId`) REFERENCES `song`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                "CREATE TABLE `event` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `songId` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `playTime` INTEGER NOT NULL, FOREIGN KEY(`songId`) REFERENCES `song`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            )
    }

    @Test
    fun repeatedPlaylistOccurrencesSurviveFirstImportAndReimport() =
        runBlocking {
            val ids = listOf("aaaaaaaaaaa", "bbbbbbbbbbb", "aaaaaaaaaaa")
            val library =
                ForeignLibrary(
                    family = ForeignLibraryFamily.INNERTUNE,
                    songs = ids.distinct().map { ForeignSong(it, it) },
                    playlists = listOf(ForeignPlaylist("Repeats", null, ids)),
                    plays = emptyList(),
                    artists = emptyList(),
                    albums = emptyList(),
                )
            withDestination { database ->
                repeat(2) {
                    database.importForeignLibrary(library) {}
                    val playlist = assertNotNull(database.dao().findPlaylistByName("Repeats"))
                    assertEquals(ids, playlistSongIds(database, playlist.id))
                }
            }
        }
}
