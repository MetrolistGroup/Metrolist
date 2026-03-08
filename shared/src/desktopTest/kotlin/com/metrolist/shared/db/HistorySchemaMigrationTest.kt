package com.metrolist.shared.db

import com.metrolist.shared.model.SongItem
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HistorySchemaMigrationTest {
    @Test
    fun databaseBuilderUsesTheNonDestructiveMigrationFrom54() =
        runBlocking<Unit> {
            val path = Files.createTempFile("history-index-migration", ".db")
            Files.delete(path)
            val original = getDesktopDatabaseBuilder(path.toFile()).build()
            try {
                original.recordHistoryPlay(SongItem("song", "Song", null, emptyList(), null), 1000, playTimeMs = 100)
            } finally {
                original.close()
            }
            val connection = JdbcSQLiteDriver().open(path.toString())
            try {
                fun exec(sql: String) = connection.prepare(sql).use { it.step() }
                exec("DROP INDEX index_event_songId_timestamp_remoteOrder_id")
                exec("ALTER TABLE song DROP COLUMN isUnavailable")
                exec("ALTER TABLE event DROP COLUMN remoteAccountKey")
                exec("ALTER TABLE event DROP COLUMN remoteDateLabel")
                exec("ALTER TABLE event DROP COLUMN remoteOrder")
                exec("ALTER TABLE lyrics DROP COLUMN isUserSelected")
                exec("DROP INDEX index_event_timestamp")
                exec("DROP TABLE history_stat_exclusion")
                val schemaPath =
                    sequenceOf(
                        Path.of("schemas/com.metrolist.shared.db.MusicDatabase/54.json"),
                        Path.of("shared/schemas/com.metrolist.shared.db.MusicDatabase/54.json"),
                    ).first { Files.exists(it) }
                val hash =
                    Json
                        .parseToJsonElement(Files.readString(schemaPath))
                        .jsonObject
                        .getValue("database")
                        .jsonObject
                        .getValue("identityHash")
                        .jsonPrimitive.content
                connection.prepare("UPDATE room_master_table SET identity_hash = ? WHERE id = 42").use {
                    it.bindText(1, hash)
                    it.step()
                }
                exec("PRAGMA user_version = 54")
            } finally {
                connection.close()
            }
            val migrated = getDesktopDatabaseBuilder(path.toFile()).build()
            try {
                assertEquals(1, migrated.dao().eventCount().first())
            } finally {
                migrated.close()
            }
            val verified = JdbcSQLiteDriver().open(path.toString())
            try {
                verified.prepare("SELECT COUNT(*) FROM history_snapshot").use {
                    assertTrue(it.step())
                    assertEquals(1L, it.getLong(0))
                }
            } finally {
                verified.close()
                Files.deleteIfExists(path)
            }
        }

    @Test
    fun timestampIndexMigrationPreservesSnapshotsAndUsesARangeScan() =
        runBlocking<Unit> {
            val db = JdbcSQLiteDriver().open(":memory:")
            try {
                fun exec(sql: String) = db.prepare(sql).use { assertFalse(it.step()) }
                exec("PRAGMA foreign_keys = ON")
                exec("CREATE TABLE event (id INTEGER PRIMARY KEY, timestamp INTEGER NOT NULL)")
                exec("CREATE TABLE history_snapshot (eventId INTEGER PRIMARY KEY REFERENCES event(id) ON DELETE CASCADE)")
                exec("INSERT INTO event VALUES (1, 1000)")
                exec("INSERT INTO history_snapshot VALUES (1)")
                MIGRATION_54_55.migrate(db)
                db.prepare("SELECT COUNT(*) FROM history_snapshot").use {
                    assertTrue(it.step())
                    assertEquals(1, it.getLong(0))
                }
                db.prepare("EXPLAIN QUERY PLAN SELECT * FROM event WHERE timestamp BETWEEN 500 AND 1500").use {
                    assertTrue(it.step())
                    assertTrue(it.getText(3).contains("index_event_timestamp"))
                    assertTrue(it.getText(3).contains("SEARCH"))
                }
            } finally {
                db.close()
            }
        }

    @Test
    fun upgradeKeepsExistingEventTimeAndCascadesSnapshotsButNotProvenance() =
        runBlocking<Unit> {
            val db = JdbcSQLiteDriver().open(":memory:")
            try {
                fun exec(sql: String) = db.prepare(sql).use { assertFalse(it.step()) }
                exec("PRAGMA foreign_keys = ON")
                exec("CREATE TABLE song (id TEXT PRIMARY KEY)")
                exec(
                    "CREATE TABLE event (id INTEGER PRIMARY KEY, songId TEXT NOT NULL, timestamp INTEGER NOT NULL, playTime INTEGER NOT NULL, source TEXT NOT NULL, remoteFeedbackToken TEXT, remoteSynced INTEGER NOT NULL, FOREIGN KEY(songId) REFERENCES song(id))",
                )
                exec("INSERT INTO song VALUES ('song')")
                exec("INSERT INTO event VALUES (1,'song',1765000000000,30000,'LOCAL',NULL,0)")
                exec("INSERT INTO event VALUES (2,'song',1765000000001,240000,'YOUTUBE','remote',1)")
                MIGRATION_50_51.migrate(db)
                db.prepare("SELECT id, playTime, playTimeKnown, remoteFeedbackToken FROM event ORDER BY id").use {
                    assertTrue(it.step())
                    assertEquals(1L, it.getLong(0))
                    assertEquals(30_000L, it.getLong(1))
                    assertEquals(1L, it.getLong(2))
                    assertTrue(it.step())
                    assertEquals(2L, it.getLong(0))
                    assertEquals(240_000L, it.getLong(1))
                    assertEquals(0L, it.getLong(2))
                    assertEquals("remote", it.getText(3))
                }
                exec("INSERT INTO history_snapshot (eventId,title,artists) VALUES (1,'old','artist')")
                MIGRATION_51_52.migrate(db)
                db.prepare("SELECT artistNamesJson, albumId FROM history_snapshot WHERE eventId = 1").use {
                    assertTrue(it.step())
                    assertTrue(it.isNull(0))
                    assertTrue(it.isNull(1))
                }
                exec("UPDATE history_snapshot SET remoteFeedbackToken = 'shared-token' WHERE eventId = 1")
                exec("INSERT INTO history_snapshot (eventId,title,artists,remoteFeedbackToken) VALUES (2,'other','artist','shared-token')")
                listOf("sorted_song_artist_map", "sorted_song_album_map", "playlist_song_map_preview").forEach {
                    exec("CREATE VIEW $it AS SELECT 1")
                }
                MIGRATION_52_53.migrate(db)
                db.prepare("SELECT COUNT(*) FROM history_snapshot WHERE remoteFeedbackToken = 'shared-token'").use {
                    assertTrue(it.step())
                    assertEquals(2L, it.getLong(0))
                }
                db.prepare("PRAGMA index_info(index_history_snapshot_remoteFeedbackToken)").use {
                    assertTrue(it.step())
                    assertEquals("remoteFeedbackToken", it.getText(2))
                }
                exec("INSERT INTO history_provenance (source,legacyId,songId,playedAt,eventId) VALUES ('old',1,'song',1765000000000,1)")
                exec("DELETE FROM event WHERE id = 1")
                db.prepare("SELECT eventId FROM history_provenance WHERE legacyId = 1").use {
                    assertTrue(it.step())
                    assertTrue(it.isNull(0))
                }
                db.prepare("SELECT COUNT(*) FROM history_snapshot").use {
                    assertTrue(it.step())
                    assertEquals(1L, it.getLong(0))
                }
            } finally {
                db.close()
            }
        }
}
