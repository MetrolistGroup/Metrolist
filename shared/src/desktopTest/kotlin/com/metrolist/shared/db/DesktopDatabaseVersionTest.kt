package com.metrolist.shared.db

import androidx.sqlite.execSQL
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopDatabaseVersionTest {
    @Test
    fun unreadableVersionDoesNotBlockRoomOrChangeTheFile() {
        val directory = createTempDirectory("database-version-corrupt").toFile()
        try {
            val file = File(directory, "library.db")
            val bytes = "unreadable legacy database".toByteArray()
            file.writeBytes(bytes)
            getDesktopDatabaseBuilder(file)
            assertContentEquals(bytes, file.readBytes())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun lockedVersionDoesNotBlockRoomOrChangeTheFile() {
        val directory = createTempDirectory("database-version-locked").toFile()
        try {
            val file = File(directory, "library.db")
            JdbcSQLiteDriver().open(file.absolutePath).use { connection ->
                connection.execSQL("PRAGMA user_version = $DATABASE_VERSION")
                connection.execSQL("BEGIN EXCLUSIVE")
                try {
                    val original = file.readBytes()
                    getDesktopDatabaseBuilder(file)
                    assertContentEquals(original, file.readBytes())
                } finally {
                    connection.execSQL("ROLLBACK")
                }
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun newerVersionInWalIsRejectedWithoutChangingDatabaseOrPendingWrites() {
        val directory = createTempDirectory("database-version-wal").toFile()
        try {
            val file = File(directory, "library.db")
            JdbcSQLiteDriver().open(file.absolutePath).use { connection ->
                connection.execSQL("CREATE TABLE library (title TEXT)")
                connection.execSQL("PRAGMA user_version = $DATABASE_VERSION")
                connection.execSQL("PRAGMA journal_mode = WAL")
                connection.execSQL("PRAGMA wal_autocheckpoint = 0")
                connection.execSQL("INSERT INTO library VALUES ('Keep my music')")
                connection.execSQL("PRAGMA user_version = ${DATABASE_VERSION + 1}")
                val original = file.readBytes()
                val wal = File("${file.absolutePath}-wal")
                val pendingWrites = wal.readBytes()
                assertTrue(pendingWrites.isNotEmpty())
                val error = assertFailsWith<DatabaseDowngradeException> { getDesktopDatabaseBuilder(file) }
                assertTrue(!error.message.isNullOrBlank())
                assertEquals(DATABASE_VERSION + 1, error.databaseVersion)
                assertEquals(DATABASE_VERSION, error.supportedVersion)
                assertContentEquals(original, file.readBytes())
                assertContentEquals(pendingWrites, wal.readBytes())
                connection.prepare("SELECT title FROM library").use {
                    assertTrue(it.step())
                    assertEquals("Keep my music", it.getText(0))
                }
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun newerDatabaseIsRejectedBeforeRoomOpensItAndRemainsUnchanged() {
        val directory = createTempDirectory("database-version").toFile()
        try {
            val file = File(directory, "library.db")
            JdbcSQLiteDriver().open(file.absolutePath).use { connection ->
                connection.execSQL("CREATE TABLE library (title TEXT)")
                connection.execSQL("INSERT INTO library VALUES ('Keep my music')")
                connection.execSQL("PRAGMA user_version = ${DATABASE_VERSION + 1}")
            }
            val original = file.readBytes()
            val error = assertFailsWith<DatabaseDowngradeException> { getDesktopDatabaseBuilder(file) }
            assertTrue(!error.message.isNullOrBlank())
            assertEquals(DATABASE_VERSION + 1, error.databaseVersion)
            assertEquals(DATABASE_VERSION, error.supportedVersion)
            assertContentEquals(original, file.readBytes())
            JdbcSQLiteDriver().open(file.absolutePath).use { connection ->
                connection.prepare("SELECT title FROM library").use {
                    it.step()
                    assertEquals("Keep my music", it.getText(0))
                }
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun existingSupportedVersionsAndNewInstallsStillReachRoom() {
        val directory = createTempDirectory("database-version").toFile()
        try {
            val file = File(directory, "library.db")
            getDesktopDatabaseBuilder(file)
            assertFalse(file.exists())
            listOf(0, 46, 49, DATABASE_VERSION).forEach { version ->
                JdbcSQLiteDriver().open(file.absolutePath).use { it.execSQL("PRAGMA user_version = $version") }
                getDesktopDatabaseBuilder(file)
            }
        } finally {
            directory.deleteRecursively()
        }
    }
}
