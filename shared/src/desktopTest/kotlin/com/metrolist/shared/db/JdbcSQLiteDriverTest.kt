package com.metrolist.shared.db

import androidx.sqlite.SQLITE_DATA_BLOB
import androidx.sqlite.SQLITE_DATA_FLOAT
import androidx.sqlite.SQLITE_DATA_INTEGER
import androidx.sqlite.SQLITE_DATA_NULL
import androidx.sqlite.SQLITE_DATA_TEXT
import com.metrolist.shared.db.entities.PlaylistEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.sql.DriverManager
import java.sql.Types
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
    fun rowTypesMatchObjectReadsForDeclaredAndExpressionColumns() {
        val connection = JdbcSQLiteDriver().open(":memory:")
        try {
            DriverManager.getConnection("jdbc:sqlite::memory:").use { previous ->
                for (sql in listOf(
                    "CREATE TABLE sample (i INTEGER, r REAL, t TEXT, b BLOB, d DATE, flag BOOLEAN, value)",
                    "INSERT INTO sample VALUES (42, 1.25, 'text', x'0001', 123, 1, NULL), " +
                        "(NULL, NULL, NULL, NULL, NULL, NULL, NULL), " +
                        "('text', x'01', 7, 2.5, 'date', 0, 'dynamic')",
                )) {
                    connection.prepare(sql).use { it.step() }
                    previous.createStatement().use { it.execute(sql) }
                }
                val sql =
                    "SELECT *, NULL AS n, 1 + 2 AS integer_expression, 1.5 * 2 AS float_expression, " +
                        "'a' || 'b' AS text_expression, x'' AS blob_expression, CAST(NULL AS TEXT) AS null_text, " +
                        "CAST(NULL AS INTEGER) AS null_integer, 9223372036854775807 AS large_integer FROM sample"
                previous.prepareStatement(sql).use { old ->
                    val metadata = old.metaData
                    val count = metadata.columnCount
                    val names = List(count) { metadata.getColumnName(it + 1) }
                    val beforeTypes = List(count) { previousMetadataType(metadata.getColumnType(it + 1)) }
                    connection.prepare(sql).use { lateMetadata ->
                        assertTrue(lateMetadata.step())
                        lateMetadata.reset()
                        assertEquals(count, lateMetadata.getColumnCount())
                        assertEquals(names, List(count, lateMetadata::getColumnName))
                        assertEquals(beforeTypes, List(count, lateMetadata::getColumnType))
                    }
                    connection.prepare(sql).use { statement ->
                        repeat(2) {
                            assertEquals(count, statement.getColumnCount())
                            assertEquals(names, List(count, statement::getColumnName))
                            assertEquals(beforeTypes, List(count, statement::getColumnType))
                            old.executeQuery().use { result ->
                                while (result.next()) {
                                    assertTrue(statement.step())
                                    repeat(count) { index ->
                                        val value = result.getObject(index + 1)
                                        val type =
                                            when (value) {
                                                null -> SQLITE_DATA_NULL
                                                is ByteArray -> SQLITE_DATA_BLOB
                                                is Float, is Double -> SQLITE_DATA_FLOAT
                                                is Number, is Boolean -> SQLITE_DATA_INTEGER
                                                else -> SQLITE_DATA_TEXT
                                            }
                                        assertEquals(value == null, statement.isNull(index), names[index])
                                        assertEquals(type, statement.getColumnType(index), names[index])
                                        if (value is String) assertEquals(value, statement.getText(index))
                                    }
                                }
                                assertFalse(statement.step())
                            }
                            statement.reset()
                        }
                    }
                }
            }
        } finally {
            connection.close()
        }
    }

    private fun previousMetadataType(type: Int): Int =
        when (type) {
            Types.BLOB, Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY -> SQLITE_DATA_BLOB
            Types.FLOAT, Types.REAL, Types.DOUBLE, Types.NUMERIC, Types.DECIMAL -> SQLITE_DATA_FLOAT
            Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT, Types.BIT, Types.BOOLEAN -> SQLITE_DATA_INTEGER
            Types.NULL -> SQLITE_DATA_NULL
            else -> SQLITE_DATA_TEXT
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
