package com.metrolist.shared.db

import com.metrolist.shared.db.entities.HistoryProvenance
import com.metrolist.shared.db.entities.PlaylistEntity
import com.metrolist.shared.db.entities.PlaylistSongMap
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
import kotlin.test.assertTrue

class HistoryIndexMigrationTest {
    @Test
    fun migrationPreservesRowsAndIndexesBrowseAndProvenanceLookups() =
        runBlocking {
            val directory = Files.createTempDirectory("history-indexes")
            val path = directory.resolve("music.db")
            val original = getDesktopDatabaseBuilder(path.toFile()).build()
            try {
                val eventId =
                    requireNotNull(original.recordHistoryPlay(SongItem("song", "Song", null, emptyList(), null), 1000, playTimeMs = 10))
                original.dao().insert(HistoryProvenance("legacy", 7, "song", 1000, null, eventId))
                original.dao().upsert(PlaylistEntity(id = "local", name = "Playlist", browseId = "remote"))
                original.dao().insert(PlaylistSongMap(playlistId = "local", songId = "song", position = 0))
            } finally {
                original.close()
            }
            JdbcSQLiteDriver().open(path.toString()).use { connection ->
                fun exec(sql: String) = connection.prepare(sql).use { it.step() }
                exec("DROP INDEX index_event_songId_timestamp_remoteOrder_id")
                exec("ALTER TABLE song DROP COLUMN isUnavailable")
                exec("ALTER TABLE event DROP COLUMN remoteAccountKey")
                exec("ALTER TABLE event DROP COLUMN remoteDateLabel")
                exec("ALTER TABLE event DROP COLUMN remoteOrder")
                exec("ALTER TABLE lyrics DROP COLUMN isUserSelected")
                exec("DROP INDEX index_playlist_browseId")
                exec("DROP INDEX index_history_provenance_legacyId_songId")
                val schema =
                    sequenceOf(Path.of("schemas"), Path.of("shared/schemas"))
                        .map { it.resolve("com.metrolist.shared.db.MusicDatabase/55.json") }
                        .first { Files.exists(it) }
                val hash =
                    Json
                        .parseToJsonElement(Files.readString(schema))
                        .jsonObject
                        .getValue("database")
                        .jsonObject
                        .getValue("identityHash")
                        .jsonPrimitive.content
                connection.prepare("UPDATE room_master_table SET identity_hash = ? WHERE id = 42").use {
                    it.bindText(1, hash)
                    it.step()
                }
                exec("PRAGMA user_version = 55")
            }
            val migrated = getDesktopDatabaseBuilder(path.toFile()).build()
            try {
                assertEquals(1, migrated.dao().eventCount().first())
                assertEquals("local", migrated.dao().findPlaylistEntity("remote")?.id)
                assertEquals(
                    listOf("song"),
                    migrated
                        .dao()
                        .playlistSongs("local")
                        .first()
                        .map { it.song.id },
                )
            } finally {
                migrated.close()
            }
            try {
                JdbcSQLiteDriver().open(path.toString()).use { connection ->
                    fun plan(sql: String): String =
                        connection.prepare("EXPLAIN QUERY PLAN $sql").use {
                            buildList { while (it.step()) add(it.getText(3)) }.joinToString("\n")
                        }
                    assertTrue(
                        plan("SELECT * FROM playlist WHERE id = 'remote' OR browseId = 'remote'").contains("index_playlist_browseId"),
                    )
                    assertTrue(
                        plan("SELECT * FROM history_provenance WHERE source != 'old' AND legacyId = 7 AND songId = 'song'")
                            .contains("index_history_provenance_legacyId_songId"),
                    )
                    val pagePlan =
                        plan(
                            "SELECT * FROM event WHERE timestamp <= 1000 AND (timestamp < 1000 OR id < 10) ORDER BY timestamp DESC, id DESC LIMIT 100",
                        )
                    assertTrue(pagePlan.contains("SEARCH event USING INDEX index_event_timestamp"), pagePlan)
                    connection.prepare("PRAGMA user_version").use {
                        it.step()
                        assertEquals(DATABASE_VERSION.toLong(), it.getLong(0))
                    }
                }
            } finally {
                directory.toFile().deleteRecursively()
            }
        }
}
