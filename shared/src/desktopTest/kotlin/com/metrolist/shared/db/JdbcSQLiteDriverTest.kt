package com.metrolist.shared.db

import com.metrolist.shared.db.entities.PlaylistEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.sql.DriverManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JdbcSQLiteDriverTest {
    @Test
    fun readsAndWritesCompleteAfterACompetingExclusiveTransaction() {
        val path = Files.createTempFile("metrolist-jdbc-busy", ".db")
        val connection = JdbcSQLiteDriver().open(path.toString())
        try {
            connection.prepare("CREATE TABLE sample (value INTEGER)").use { assertFalse(it.step()) }
            connection.prepare("INSERT INTO sample VALUES (1)").use { assertFalse(it.step()) }
            repeat(2) { operation ->
                val locked = CountDownLatch(1)
                val attempting = CountDownLatch(1)
                val holder =
                    Thread {
                        DriverManager.getConnection("jdbc:sqlite:$path").use { other ->
                            other.createStatement().use { it.execute("BEGIN EXCLUSIVE") }
                            locked.countDown()
                            check(attempting.await(30, TimeUnit.SECONDS))
                            other.createStatement().use { it.execute("COMMIT") }
                        }
                    }.apply { start() }
                try {
                    assertTrue(locked.await(10, TimeUnit.SECONDS))
                    attempting.countDown()
                    if (operation == 0) {
                        connection.prepare("SELECT value FROM sample").use {
                            assertTrue(it.step())
                            assertEquals(1, it.getLong(0))
                        }
                    } else {
                        connection.prepare("INSERT INTO sample VALUES (2)").use { assertFalse(it.step()) }
                    }
                } finally {
                    attempting.countDown()
                    holder.join()
                }
            }
            connection.prepare("SELECT COUNT(*) FROM sample").use {
                assertTrue(it.step())
                assertEquals(2, it.getLong(0))
            }
        } finally {
            connection.close()
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun supportsRoomStyleTransactionsAndBindings() {
        val path = Files.createTempFile("metrolist-jdbc-driver", ".db")
        try {
            val connection = JdbcSQLiteDriver().open(path.toString())
            connection.prepare("CREATE TABLE sample (id INTEGER PRIMARY KEY, title TEXT, payload BLOB)").use {
                assertFalse(it.step())
            }
            connection.prepare("BEGIN IMMEDIATE TRANSACTION").use {
                assertFalse(it.step())
            }
            assertTrue(connection.inTransaction())
            connection.prepare("INSERT INTO sample(id, title, payload) VALUES (?, ?, ?)").use {
                it.bindLong(1, 7)
                it.bindText(2, "playlist")
                it.bindBlob(3, byteArrayOf(1, 2, 3))
                assertFalse(it.step())
            }
            connection.prepare("COMMIT").use {
                assertFalse(it.step())
            }
            assertFalse(connection.inTransaction())
            connection.prepare("SELECT id, title, payload FROM sample").use {
                assertTrue(it.step())
                assertEquals(7, it.getLong(0))
                assertEquals("playlist", it.getText(1))
                assertTrue(byteArrayOf(1, 2, 3).contentEquals(it.getBlob(2)))
                assertFalse(it.step())
            }
            connection.close()
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun preservesMetadataWhenAStatementIsResetAndReused() {
        val path = Files.createTempFile("metrolist-jdbc-reset", ".db")
        try {
            val connection = JdbcSQLiteDriver().open(path.toString())
            connection.prepare("SELECT 1 AS value").use { statement ->
                assertEquals(1, statement.getColumnCount())
                assertEquals("value", statement.getColumnName(0))
                assertTrue(statement.step())
                assertEquals(1, statement.getLong(0))
                statement.reset()
                assertEquals(1, statement.getColumnCount())
                assertEquals("value", statement.getColumnName(0))
                assertTrue(statement.step())
                assertEquals(1, statement.getLong(0))
            }
            connection.close()
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun opensRoomAndPersistsPlaylist() =
        runBlocking {
            val path = Files.createTempFile("metrolist-room-jdbc", ".db")
            Files.deleteIfExists(path)
            val database = getDesktopDatabaseBuilder(path.toFile()).build()
            try {
                database.dao().upsert(
                    PlaylistEntity(
                        id = "PL_TEST",
                        name = "JDBC playlist",
                        description = "One native SQLite implementation",
                    ),
                )
                database.dao().upsert(
                    PlaylistEntity(
                        id = "PL_TEST",
                        name = "Updated JDBC playlist",
                        description = "Upsert conflict handling",
                    ),
                )
                val stored =
                    database
                        .dao()
                        .allPlaylists()
                        .first()
                        .single()
                assertEquals("Updated JDBC playlist", stored.name)
                assertEquals("Upsert conflict handling", stored.description)
            } finally {
                database.close()
                Files.deleteIfExists(path)
            }
        }
}
