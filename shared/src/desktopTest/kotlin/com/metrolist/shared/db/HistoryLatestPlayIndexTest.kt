package com.metrolist.shared.db

import com.metrolist.shared.model.SongItem
import com.metrolist.shared.settings.Settings
import com.russhwolf.settings.MapSettings
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

class HistoryLatestPlayIndexTest {
    @Test
    fun migrationIndexesLatestPlayLookupWithoutSortingRepeatedPlays() =
        runBlocking {
            Settings.replaceSharedForTest(Settings(MapSettings()))
            val directory = Files.createTempDirectory("history-latest-index")
            val path = directory.resolve("music.db")
            try {
                val original = getDesktopDatabaseBuilder(path.toFile()).build()
                var latest = 0L
                try {
                    original.withDatabaseTransaction {
                        repeat(5_000) { index ->
                            latest =
                                requireNotNull(
                                    original.recordHistoryPlay(SongItem("repeated", "Repeated", null, emptyList(), null), index.toLong()),
                                )
                        }
                    }
                } finally {
                    original.close()
                }
                JdbcSQLiteDriver().open(path.toString()).use { connection ->
                    fun exec(sql: String) = connection.prepare(sql).use { it.step() }
                    exec("DROP INDEX index_event_songId_timestamp_remoteOrder_id")
                    exec("ALTER TABLE event DROP COLUMN remoteAccountKey")
                    exec("ALTER TABLE lyrics DROP COLUMN userLyrics")
                    exec("ALTER TABLE lyrics DROP COLUMN userLyricsDraft")
                    exec("ALTER TABLE lyrics DROP COLUMN stashedUserLyrics")
                    exec("ALTER TABLE lyrics DROP COLUMN stashedUserLyricsDraft")
                    val schema =
                        sequenceOf(Path.of("schemas"), Path.of("shared/schemas"))
                            .map { it.resolve("com.metrolist.shared.db.MusicDatabase/61.json") }
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
                    exec("PRAGMA user_version = 61")
                }
                val migrated = getDesktopDatabaseBuilder(path.toFile()).build()
                try {
                    assertEquals(listOf(latest), migrated.recentVisibleHistory(100).map { it.id })
                    assertEquals(setOf(latest), migrated.visibleHistoryIds(""))
                    assertEquals(5_000, migrated.history().size)
                } finally {
                    migrated.close()
                }
                JdbcSQLiteDriver().open(path.toString()).use { connection ->
                    val sql = "SELECT e.id FROM event e WHERE 1 = 1 ${latestHistorySongFilter(null, null)}"
                    val plan =
                        connection.prepare("EXPLAIN QUERY PLAN $sql").use {
                            it.bindText(1, "")
                            it.bindText(2, "")
                            buildList { while (it.step()) add(it.getText(3)) }.joinToString("\n")
                        }
                    assertTrue(plan.contains("SEARCH latest USING INDEX index_event_songId_timestamp_remoteOrder_id"), plan)
                    assertFalse(plan.contains("USE TEMP B-TREE"), plan)
                    connection.prepare("SELECT COUNT(*) FROM history_snapshot").use {
                        assertTrue(it.step())
                        assertEquals(5_000L, it.getLong(0))
                    }
                }
            } finally {
                directory.toFile().deleteRecursively()
                Settings.replaceSharedForTest(null)
            }
        }
}
