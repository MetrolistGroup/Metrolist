package com.metrolist.shared.backup

import com.metrolist.shared.api.lyrics.LyricsUtils
import com.metrolist.shared.db.DATABASE_VERSION
import com.metrolist.shared.db.JdbcSQLiteDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LegacyBackupDataMigrationTest {
    @Test
    fun appliesTheApplicableRoomDataMigrationsForEverySupportedVersion() {
        (listOf(1) + (36..DATABASE_VERSION)).forEach { version ->
            val connection = JdbcSQLiteDriver().open(":memory:")
            try {
                listOf(
                    "CREATE TABLE song (id TEXT PRIMARY KEY, lyricsOffset INTEGER NOT NULL DEFAULT 0)",
                    "CREATE TABLE lyrics (id TEXT PRIMARY KEY, offsetMs INTEGER NOT NULL DEFAULT 0)",
                    "CREATE TABLE speed_dial (id TEXT PRIMARY KEY, type TEXT NOT NULL)",
                    "CREATE TABLE playlist (id TEXT PRIMARY KEY, name TEXT NOT NULL, browseId TEXT, isEditable INTEGER NOT NULL, isLocal INTEGER NOT NULL, remoteSongCount INTEGER NOT NULL, thumbnailUrl TEXT)",
                    "CREATE TABLE playlist_song_map (playlistId TEXT NOT NULL)",
                    "CREATE TABLE history_import_policy (id INTEGER PRIMARY KEY, enabled INTEGER NOT NULL)",
                    "CREATE TABLE event (id INTEGER PRIMARY KEY, source TEXT NOT NULL, playTime INTEGER NOT NULL, playTimeKnown INTEGER NOT NULL)",
                    "INSERT INTO event VALUES (1, 'LOCAL', 30000, 1)",
                    "INSERT INTO event VALUES (2, 'YOUTUBE', 240000, 1)",
                    "INSERT INTO song VALUES ('song', 425)",
                    "INSERT INTO lyrics VALUES ('song', ${if (version < 45) 0 else 425})",
                    "INSERT INTO speed_dial VALUES ('MPSPpodcast', '${if (version < 45) "PLAYLIST" else "PODCAST"}')",
                    "INSERT INTO playlist VALUES ('LP12345678', 'Playlist', NULL, 0, 0, 0, NULL)",
                ).forEach { sql -> connection.prepare(sql).use { it.step() } }

                legacyBackupDataMigrations(version).forEach { sql -> connection.prepare(sql).use { it.step() } }

                connection.prepare("SELECT playTime, playTimeKnown FROM event WHERE id = 2").use {
                    assertEquals(true, it.step())
                    assertEquals(240_000L, it.getLong(0))
                    assertEquals(if (version < 51) 0L else 1L, it.getLong(1), "remote listening time for version $version")
                }
                connection.prepare("SELECT playTimeKnown FROM event WHERE id = 1").use {
                    assertEquals(true, it.step())
                    assertEquals(1L, it.getLong(0))
                }
                connection.prepare("SELECT enabled FROM history_import_policy WHERE id = 1").use {
                    assertEquals(version < 51, it.step(), "old backups suppress legacy import")
                    if (version < 51) assertEquals(0L, it.getLong(0))
                }
                connection.prepare("SELECT offsetMs FROM lyrics WHERE id = 'song'").use {
                    assertEquals(true, it.step())
                    val offset = it.getLong(0)
                    assertEquals(425L, offset, "lyrics offset for version $version")
                    val lines = LyricsUtils.parseLyrics("[00:01.00]First\n[00:02.00]Second")
                    assertEquals(
                        1,
                        LyricsUtils.findCurrentLineIndex(lines, LyricsUtils.lyricsPositionForOffset(1_575, offset.toInt())),
                        "restored positive offset advances lyrics for version $version",
                    )
                }
                connection.prepare("SELECT type FROM speed_dial WHERE id = 'MPSPpodcast'").use {
                    assertEquals(true, it.step())
                    assertEquals("PODCAST", it.getText(0), "speed dial type for version $version")
                }
                connection.prepare("SELECT 1 FROM playlist WHERE id = 'LP12345678'").use {
                    assertEquals(version >= 46, it.step(), "placeholder playlist for version $version")
                }
            } finally {
                connection.close()
            }
        }
    }

    @Test
    fun requiresCompleteKnownTableSetsForSupportedSchemas() {
        val tables =
            setOf(
                "song",
                "artist",
                "album",
                "playlist",
                "song_artist_map",
                "song_album_map",
                "album_artist_map",
                "playlist_song_map",
                "search_history",
                "format",
                "lyrics",
                "event",
                "related_song_map",
                "set_video_id",
                "playCount",
                "recognition_history",
                "speed_dial",
                "podcast",
            )
        requireSupportedBackupDatabaseTables(36, tables)
        requireSupportedBackupDatabaseTables(38, (tables - "speed_dial") + "speed_dial_item")
        requireSupportedBackupDatabaseTables(39, tables)
        requireSupportedBackupDatabaseTables(40, tables + "cached_audio")
        requireSupportedBackupDatabaseTables(50, tables + "import_track_match")
        requireSupportedBackupDatabaseTables(
            51,
            tables + setOf("import_track_match", "history_snapshot", "history_provenance", "history_import_policy"),
        )
        requireSupportedBackupDatabaseTables(
            55,
            tables +
                setOf("import_track_match", "history_snapshot", "history_provenance", "history_import_policy", "history_stat_exclusion"),
        )
        val version57Tables = tables + setOf("import_track_match", "history_snapshot", "history_provenance", "history_import_policy")
        requireSupportedBackupDatabaseTables(57, version57Tables)
        requireSupportedBackupDatabaseTables(58, version57Tables + "pending_scrobble")
        requireSupportedBackupDatabaseTables(59, version57Tables + "pending_scrobble")
        assertFailsWith<IllegalArgumentException> { requireSupportedBackupDatabaseTables(57, version57Tables + "pending_scrobble") }
        assertFailsWith<IllegalArgumentException> { requireSupportedBackupDatabaseTables(58, version57Tables) }
        assertFailsWith<IllegalArgumentException> { requireSupportedBackupDatabaseTables(59, version57Tables) }
        assertFailsWith<IllegalArgumentException> { requireSupportedBackupDatabaseTables(51, tables + "import_track_match") }
        assertFailsWith<IllegalArgumentException> { requireSupportedBackupDatabaseTables(50, tables) }
        requireSupportedBackupDatabaseTables(1, setOf("song", "artist", "playlist", "playlist_song"))
        requireSupportedBackupDatabaseTables(
            1,
            setOf("song", "artist", "playlist", "playlist_song", "speed_dial_item", "download"),
        )
        assertFailsWith<IllegalArgumentException> {
            requireSupportedBackupDatabaseTables(38, tables + "speed_dial_item")
        }
        assertFailsWith<IllegalArgumentException> {
            requireSupportedBackupDatabaseTables(39, tables + "cached_audio")
        }
        assertFailsWith<IllegalArgumentException> {
            requireSupportedBackupDatabaseTables(40, (tables - "speed_dial") + "cached_audio" + "speed_dial_item")
        }
        assertFailsWith<IllegalArgumentException> { requireSupportedBackupDatabaseTables(44, tables - "lyrics") }
        assertFailsWith<IllegalArgumentException> { requireSupportedBackupDatabaseTables(49, tables + "unknown_data") }
    }

    @Test
    fun rejectsUnsupportedHistoricalSchemaVersions() {
        assertFailsWith<IllegalArgumentException> { legacyBackupDataMigrations(35) }
        assertFailsWith<IllegalArgumentException> { legacyBackupDataMigrations(DATABASE_VERSION + 1) }
    }
}
