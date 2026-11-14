package com.metrolist.shared.backup

import androidx.sqlite.execSQL
import com.metrolist.shared.db.JdbcSQLiteDriver
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.db.entities.SongEntity
import com.metrolist.shared.db.getDatabaseBuilder
import com.metrolist.shared.settings.Settings
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PrismBackupImportTest {
    private val directory = Files.createTempDirectory("prism-import").toFile()
    private val driver = JdbcSQLiteDriver()

    @BeforeTest
    fun setUp() {
        Settings.replaceSharedForTest(Settings(MapSettings()))
    }

    @AfterTest
    fun cleanUp() {
        Settings.replaceSharedForTest(null)
        directory.deleteRecursively()
    }

    @Test
    fun companionMetadataFillsDatabaseGapsWithoutReplacingListeningState() {
        val companion = checkNotNull(prismArchiveFile(directory, "files/library/prism.json"))
        companion.parentFile.mkdirs()
        companion.writeText(
            """{"lists":[{"title":"List","songs":[{"id":"song","title":"Track","artists":[{"id":"UCartist","name":"Artist"}],"album":{"id":"album","title":"Album"},"durationSec":180,"thumbnail":"cover"}]}]}""",
        )
        val library =
            ForeignLibrary(
                ForeignLibraryFamily.PRISM,
                listOf(ForeignSong("song", "song", liked = true, totalPlayTimeMs = 99)),
                emptyList(),
                emptyList(),
                emptyList(),
                emptyList(),
            )
        val song = library.withPrismArchive(directory).songs.single()
        assertEquals("Track", song.title)
        assertEquals(listOf(ForeignArtist("UCartist", "Artist")), song.artists)
        assertEquals("Album", song.albumName)
        assertEquals(180, song.durationSeconds)
        assertEquals("cover", song.thumbnailUrl)
        assertTrue(song.liked)
        assertEquals(99, song.totalPlayTimeMs)
    }

    @Test
    fun malformedOptionalFavoritesDoNotDiscardValidLibraryCollections() =
        runBlocking {
            val source = sourceDatabase()
            for (favorites in listOf("<map>", "<map><string name=\"favorites\">not-json</string></map>")) {
                val archive = archive(source, companions() + ("shared_prefs/artist_prefs.xml" to favorites))
                withDestination { database ->
                    val summary = archive.inputStream().use { database.importForeignBackup(it, staging(), driver) }
                    assertEquals(ForeignLibraryFamily.PRISM, summary.family)
                    assertNotNull(database.dao().getSongById("ddddddddddd"))
                    assertNotNull(database.dao().findPlaylistByName("My Prism list"))
                    assertNotNull(database.dao().getArtistById("UCfollowed")?.bookmarkedAt)
                    assertNotNull(database.dao().getArtistById("UCstarred"))
                }
            }
        }

    @Test
    fun detectsSchemaAndReadsLikesArtistCreditsDurationsAndListeningTime() {
        val source = sourceDatabase()
        val before = source.readBytes()
        driver.open(source.path).use { connection ->
            assertEquals(ForeignLibraryFamily.PRISM, connection.detectForeignLibraryFamily())
            val library = connection.readForeignLibrary()
            val song = library.songs.first()
            assertEquals(205, song.durationSeconds)
            assertEquals("Test album", song.albumName)
            assertEquals("MPRE_album", song.albumId)
            assertEquals("https://example.test/song.jpg", song.thumbnailUrl)
            assertTrue(song.liked)
            assertTrue(song.isVideo)
            assertEquals(1700000000000, song.likedAt)
            assertEquals(120000, song.totalPlayTimeMs)
            assertEquals(listOf(ForeignArtist("UCartist1", "Comma, Artist"), ForeignArtist(null, "Guest")), song.artists)
            assertEquals(listOf(ForeignArtist("UCartist2", "Legacy artist"), ForeignArtist(null, "Guest")), library.songs[1].artists)
            assertTrue(library.songs[2].artists.isEmpty())
            assertEquals(4, library.plays.size)
            assertEquals(ForeignPlay("bbbbbbbbbbb", 1700000300000, 15000), library.plays[2])
            assertFails { connection.execSQL("DELETE FROM songs") }
        }
        assertContentEquals(before, source.readBytes())
    }

    @Test
    fun olderPrismSchemaWorksWithoutArtistJsonAndUnrelatedSongsTableIsRejected() {
        val source = sourceDatabase()
        driver.open(source.path).use { connection ->
            connection.execSQL("ALTER TABLE songs DROP COLUMN artistsJson")
            connection.execSQL("ALTER TABLE songs DROP COLUMN primaryArtist")
            connection.execSQL("ALTER TABLE play_events DROP COLUMN source")
            connection.execSQL("ALTER TABLE play_events DROP COLUMN excluded")
            val library = connection.readForeignLibrary()
            assertEquals(ForeignLibraryFamily.PRISM, library.family)
            assertEquals(
                "Comma",
                library.songs
                    .first()
                    .artists
                    .first()
                    .name,
            )
        }
        driver.open(directory.resolve("unrelated.db").path).use { connection ->
            connection.execSQL("CREATE TABLE songs (id TEXT, title TEXT, liked INTEGER)")
            assertNull(connection.detectForeignLibraryFamily())
        }
    }

    @Test
    fun zipImportsCollectionsBookmarksAndHistoryAndReimportPreservesExistingData() =
        runBlocking {
            val source = sourceDatabase()
            val archive = archive(source, companions())
            val before = archive.readBytes()
            withDestination { database ->
                val dao = database.dao()
                dao.upsert(SongEntity(id = "aaaaaaaaaaa", title = "Existing title", totalPlayTime = 150000))
                val summary = archive.inputStream().use { database.importForeignBackup(it, staging(), driver) }
                assertEquals(ForeignLibraryFamily.PRISM, summary.family)
                assertEquals(4, summary.songs)
                assertEquals(1, summary.likes)
                assertEquals(3, summary.playlists)
                assertEquals(3, summary.plays)
                assertEquals(3, summary.skippedSongs)
                assertFalse(staging().exists())
                val liked = assertNotNull(dao.getSongById("aaaaaaaaaaa")).song
                assertEquals("Existing title", liked.title)
                assertEquals(150000, liked.totalPlayTime)
                assertTrue(liked.liked)
                assertEquals(LocalDateTime(2023, 11, 14, 22, 13, 20), liked.likedDate)
                assertEquals(15000, dao.getSongById("bbbbbbbbbbb")?.song?.totalPlayTime)
                assertNull(dao.getSongById("ccccccccccc"))
                assertNull(dao.getSongById("local:42"))
                assertNull(dao.getSongById("not-a-video-id"))
                assertNull(dao.getSongById("ggggggggggg"))
                val playlist = assertNotNull(dao.findPlaylistByName("My Prism list"))
                assertTrue(playlist.isLocal)
                assertEquals("A personal collection", playlist.description)
                assertEquals(LocalDateTime(2023, 11, 14, 22, 13, 20), playlist.createdAt)
                assertEquals(listOf("ddddddddddd", "aaaaaaaaaaa"), songIds(database, playlist.id))
                val remote = assertNotNull(dao.findPlaylistEntity("PLsaved"))
                assertFalse(remote.isLocal)
                assertTrue(remote.isEditable)
                assertEquals(listOf("eeeeeeeeeee", "bbbbbbbbbbb"), songIds(database, remote.id))
                assertEquals("https://example.test/list.jpg", remote.thumbnailUrl)
                assertNotNull(dao.findPlaylistByName("Empty list"))
                val companionSong = assertNotNull(dao.getSongById("ddddddddddd"))
                assertTrue(companionSong.song.explicit)
                assertTrue(companionSong.song.isUploaded)
                assertEquals("JSON artist", companionSong.orderedArtists.single().name)
                assertTrue(assertNotNull(dao.getSongById("eeeeeeeeeee")).song.isEpisode)
                assertNotNull(dao.getArtistById("UCstarred")?.bookmarkedAt)
                assertEquals("Star & Artist", dao.getArtistById("UCstarred")?.name)
                assertEquals(LocalDateTime(2023, 11, 14, 22, 13, 20), dao.getArtistById("UCstarred")?.bookmarkedAt)
                assertNotNull(dao.getArtistById("UCfollowed")?.bookmarkedAt)
                assertNotNull(
                    dao
                        .album("MPRE_saved")
                        .first()
                        ?.album
                        ?.bookmarkedAt,
                )
                assertNotNull(
                    dao
                        .album("MPRE_remote")
                        .first()
                        ?.album
                        ?.bookmarkedAt,
                )
                assertEquals(listOf(15000L, 30000L, 90000L), dao.events().first().map { it.event.playTime })
                val again = archive.inputStream().use { database.importForeignBackup(it, staging(), driver) }
                assertEquals(0, again.likes)
                assertEquals(0, again.plays)
                assertEquals(3, dao.allPlaylists().first().size)
                assertEquals(listOf("ddddddddddd", "aaaaaaaaaaa"), songIds(database, playlist.id))
            }
            assertContentEquals(before, archive.readBytes())
        }

    @Test
    fun rawBackupImportsOnlyDatabaseData() =
        runBlocking {
            val source = sourceDatabase()
            val before = source.readBytes()
            withDestination { database ->
                val summary = source.inputStream().use { database.importForeignBackup(it, staging(), driver) }
                assertEquals(2, summary.songs)
                assertEquals(3, summary.plays)
                assertEquals(0, summary.playlists)
                assertEquals(
                    120000,
                    database
                        .dao()
                        .getSongById("aaaaaaaaaaa")
                        ?.song
                        ?.totalPlayTime,
                )
                assertTrue(assertNotNull(database.dao().getSongById("aaaaaaaaaaa")).song.isVideo)
            }
            assertContentEquals(before, source.readBytes())
        }

    @Test
    fun corruptCompanionFailsBeforeMergingAndCleansStaging() =
        runBlocking {
            val archive = archive(sourceDatabase(), mapOf("files/library/prism.json" to "{broken"))
            withDestination { database ->
                assertFails { archive.inputStream().use { database.importForeignBackup(it, staging(), driver) } }
                assertNull(database.dao().getSongById("aaaaaaaaaaa"))
                assertFalse(staging().exists())
            }
        }

    @Test
    fun artistPreferencesRejectExternalEntitiesBeforeMerging() =
        runBlocking {
            val archive =
                archive(
                    sourceDatabase(),
                    mapOf(
                        "shared_prefs/artist_prefs.xml" to
                            """<!DOCTYPE map [<!ENTITY external SYSTEM "file:///nonexistent-prism-secret">]><map><string name="favorites">&external;</string></map>""",
                    ),
                )
            withDestination { database ->
                assertFails { archive.inputStream().use { database.importForeignBackup(it, staging(), driver) } }
                assertNull(database.dao().getSongById("aaaaaaaaaaa"))
                assertFalse(staging().exists())
            }
        }

    @Test
    fun companionPathsAreExactAndLargePrismArchivesKeepTheirLibrary() =
        runBlocking {
            val entries =
                (1..300).associate { "files/artwork/$it.jpg" to "synthetic artwork" } +
                    mapOf("files/library/../../prism.json" to "{broken", "other/prism.json" to "{broken") + companions()
            val archive = archive(sourceDatabase(), entries)
            withDestination { database ->
                val result = archive.inputStream().use { database.importForeignBackup(it, staging(), driver) }
                assertEquals(3, result.playlists)
                assertFalse(directory.resolve("prism.json").exists())
            }
        }

    @Test
    fun zipReplaysPrismWalWithoutChangingTheSource() =
        runBlocking {
            val source = sourceDatabase()
            driver.open(source.path).use { connection ->
                connection.execSQL("PRAGMA journal_mode = WAL")
                connection.execSQL("PRAGMA wal_autocheckpoint = 0")
                connection.execSQL("UPDATE songs SET title = 'Title from WAL' WHERE id = 'aaaaaaaaaaa'")
                val sourceBytes = source.readBytes()
                val wal = File(source.path + "-wal")
                val walBytes = wal.readBytes()
                val zipFile = directory.resolve("wal.zip")
                ZipOutputStream(zipFile.outputStream()).use { zip ->
                    listOf(source to "prism.db", wal to "prism.db-wal").forEach { (file, name) ->
                        zip.putNextEntry(ZipEntry("databases/$name"))
                        file.inputStream().use { it.copyTo(zip) }
                    }
                }
                withDestination { database ->
                    zipFile.inputStream().use { database.importForeignBackup(it, staging(), driver) }
                    assertEquals(
                        "Title from WAL",
                        database
                            .dao()
                            .getSongById("aaaaaaaaaaa")
                            ?.song
                            ?.title,
                    )
                }
                assertContentEquals(sourceBytes, source.readBytes())
                assertContentEquals(walBytes, wal.readBytes())
            }
        }

    @Test
    fun oversizedCompanionFailsBeforeMerging() =
        runBlocking {
            val archive =
                archive(
                    sourceDatabase(),
                    mapOf("files/library/prism.json" to " ".repeat(BackupArchiveLimits.SETTINGS_BYTES.toInt() + 1)),
                )
            withDestination { database ->
                assertFails { archive.inputStream().use { database.importForeignBackup(it, staging(), driver) } }
                assertNull(database.dao().getSongById("aaaaaaaaaaa"))
                assertFalse(staging().exists())
            }
        }

    private fun sourceDatabase(): File {
        val file = directory.resolve("source.db")
        val sql = checkNotNull(javaClass.getResource("/backup/prism.sql")).readText()
        driver.open(file.path).use { connection ->
            sql
                .lineSequence()
                .filterNot { it.startsWith("--") }
                .joinToString("\n")
                .split(';')
                .filter(String::isNotBlank)
                .forEach(connection::execSQL)
        }
        return file
    }

    private fun archive(
        source: File,
        entries: Map<String, String>,
    ): File =
        directory.resolve("backup.zip").apply {
            ZipOutputStream(outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("prism-backup.json"))
                zip.write("""{"format":1,"app":"com.prism.music"}""".toByteArray())
                zip.putNextEntry(ZipEntry("databases/prism.db"))
                source.inputStream().use { it.copyTo(zip) }
                entries.forEach { (path, contents) ->
                    zip.putNextEntry(ZipEntry(path))
                    zip.write(contents.toByteArray())
                }
            }
        }

    private fun companions() =
        mapOf(
            "files/library/prism.json" to """{
            "lists":[{"id":"prism:mine","title":"My Prism list","description":"A personal collection",
                "createdAt":1700000000000,"updatedAt":1700000001000,"songs":[
                    {"id":"ddddddddddd","title":"Playlist only","artists":[{"name":"JSON artist","id":"UCjson"}],"explicit":true,"isUpload":true},
                    {"id":"aaaaaaaaaaa","title":"Liked track"},{"id":"local:42","title":"Local track"},
                    {"id":"fffffffffff","title":"Live station","isLive":true}]},
                {"id":"prism:empty","title":"Empty list","songs":[],"createdAt":1700000000000,"updatedAt":1700000000000}],
            "albums":[{"id":"MPRE_saved","title":"Saved album","subtitle":"Artist","savedAt":1700000000000}]
        }""",
            "files/offline/library.json" to """{
            "playlists":[{"kind":"playlist","id":"VLPLsaved","title":"Remote list","thumbnail":"https://example.test/list.jpg"},
                {"kind":"playlist","id":"LM","title":"Likes"},{"kind":"playlist","id":"RDmix","title":"Mix","isMix":true},
                {"kind":"podcast","id":"MPSPshow","title":"Show"}],
            "albums":[{"kind":"album","id":"MPRE_remote","title":"Remote album"}],
            "artists":[{"kind":"artist","id":"UCfollowed","title":"Followed artist"},
                {"kind":"artist","id":"UCstarred","title":"Star & Artist"}]
        }""",
            "files/offline/pages/VLPLsaved.json" to """{"id":"PLsaved","album":false,"title":"Remote list","owned":true,"songs":[
            {"id":"eeeeeeeeeee","title":"Episode","isEpisode":true},{"id":"bbbbbbbbbbb","title":"History track"}]}""",
            "files/offline/pages/PLnotSaved.json" to """{"id":"PLnotSaved","album":false,"title":"Only browsed","songs":[
            {"id":"ggggggggggg","title":"Unrelated cache"}]}""",
            "shared_prefs/artist_prefs.xml" to
                """<map><string name="favorites">[{"id":"UCstarred","name":"Star &amp; Artist","at":1700000000000}]</string></map>""",
            "shared_prefs/settings.xml" to "<map><string name=\"cookie\">synthetic-cookie</string></map>",
        )

    private fun staging() = directory.resolve("staging")

    private suspend fun withDestination(block: suspend (MusicDatabase) -> Unit) {
        val database = getDatabaseBuilder(directory.resolve("metrolist.db")).build()
        try {
            block(database)
        } finally {
            database.close()
        }
    }

    private suspend fun songIds(
        database: MusicDatabase,
        playlistId: String,
    ) = database
        .dao()
        .playlistSongs(playlistId)
        .first()
        .map { it.song.id }
}
