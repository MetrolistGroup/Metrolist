package com.metrolist.shared.backup

import com.metrolist.shared.db.JdbcSQLiteDriver
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BackupDatabaseValidationTest {
    @Test
    fun checkpointKeepsWalRowsWhenOnlyTheDatabaseIsMoved() =
        runBlocking<Unit> {
            val directory = Files.createTempDirectory("backup-wal").toFile()
            val source = directory.resolve("source.db")
            val staged = directory.resolve("staged.db")
            val driver = JdbcSQLiteDriver()
            try {
                driver.open(source.path).use { connection ->
                    listOf(
                        "PRAGMA journal_mode=WAL",
                        "PRAGMA wal_autocheckpoint=0",
                        "CREATE TABLE song (id INTEGER PRIMARY KEY, title TEXT)",
                        "PRAGMA wal_checkpoint(TRUNCATE)",
                        "INSERT INTO song VALUES (1, 'Committed to WAL')",
                    ).forEach { sql -> connection.prepare(sql).use { it.step() } }
                    source.copyTo(staged)
                    directory.resolve("source.db-wal").copyTo(directory.resolve("staged.db-wal"))
                    driver.open(staged.path).use { stagedConnection ->
                        checkpointBackupDatabase(stagedConnection)
                        val installed = directory.resolve("installed.db")
                        staged.copyTo(installed)
                        driver.open(installed.path).use { installedConnection ->
                            installedConnection.prepare("SELECT title FROM song").use {
                                assertTrue(it.step())
                                assertEquals("Committed to WAL", it.getText(0))
                            }
                        }
                    }
                }
            } finally {
                directory.deleteRecursively()
            }
        }

    @Test
    fun rejectsMissingRoomTableEvenWhenIdentityHashMatches() =
        runBlocking<Unit> {
            val driver = JdbcSQLiteDriver()
            val expected = driver.open(":memory:")
            val actual = driver.open(":memory:")
            try {
                val commonSchema =
                    listOf(
                        "CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)",
                        "INSERT INTO room_master_table VALUES (42, 'matching-hash')",
                        "CREATE TABLE event (id INTEGER PRIMARY KEY)",
                    )
                commonSchema.forEach { sql ->
                    expected.prepare(sql).use { it.step() }
                    actual.prepare(sql).use { it.step() }
                }
                expected.prepare("CREATE TABLE playlist (id TEXT PRIMARY KEY)").use { it.step() }

                assertFailsWith<IllegalStateException> { validateBackupRoomSchema(expected, actual) }
            } finally {
                expected.close()
                actual.close()
            }
        }

    @Test
    fun rejectsUnexpectedPersistentSchemaObjects() =
        runBlocking<Unit> {
            val driver = JdbcSQLiteDriver()
            val expected = driver.open(":memory:")
            val actual = driver.open(":memory:")
            try {
                listOf(
                    "CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)",
                    "INSERT INTO room_master_table VALUES (42, 'matching-hash')",
                    "CREATE TABLE event (id INTEGER PRIMARY KEY)",
                ).forEach { sql ->
                    expected.prepare(sql).use { it.step() }
                    actual.prepare(sql).use { it.step() }
                }
                actual.prepare("CREATE TRIGGER injected AFTER INSERT ON event BEGIN SELECT 1; END").use { it.step() }
                actual.prepare("CREATE INDEX injected_index ON event(id)").use { it.step() }

                assertFailsWith<IllegalStateException> { validateBackupRoomSchema(expected, actual) }
            } finally {
                expected.close()
                actual.close()
            }
        }

    @Test
    fun rejectsChangedTriggerDefinition() =
        runBlocking<Unit> {
            val driver = JdbcSQLiteDriver()
            val expected = driver.open(":memory:")
            val actual = driver.open(":memory:")
            try {
                listOf(
                    "CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)",
                    "INSERT INTO room_master_table VALUES (42, 'matching-hash')",
                    "CREATE TABLE event (id INTEGER PRIMARY KEY)",
                ).forEach { sql ->
                    expected.prepare(sql).use { it.step() }
                    actual.prepare(sql).use { it.step() }
                }
                expected.prepare("CREATE TRIGGER injected AFTER INSERT ON event BEGIN SELECT 1; END").use { it.step() }
                actual.prepare("CREATE TRIGGER injected AFTER INSERT ON event BEGIN SELECT 2; END").use { it.step() }

                assertFailsWith<IllegalStateException> { validateBackupRoomSchema(expected, actual) }
            } finally {
                expected.close()
                actual.close()
            }
        }

    @Test
    fun acceptsEquivalentIndexDefinitionsWithDifferentQuoting() =
        runBlocking<Unit> {
            val driver = JdbcSQLiteDriver()
            val expected = driver.open(":memory:")
            val actual = driver.open(":memory:")
            try {
                listOf(
                    "CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)",
                    "INSERT INTO room_master_table VALUES (42, 'matching-hash')",
                    "CREATE TABLE event (id INTEGER PRIMARY KEY, remoteFeedbackToken TEXT)",
                ).forEach { sql ->
                    expected.prepare(sql).use { it.step() }
                    actual.prepare(sql).use { it.step() }
                }
                expected
                    .prepare("CREATE UNIQUE INDEX `index_event_remoteFeedbackToken` ON `event` (`remoteFeedbackToken`)")
                    .use { it.step() }
                actual
                    .prepare("CREATE UNIQUE INDEX index_event_remoteFeedbackToken ON event(remoteFeedbackToken)")
                    .use { it.step() }

                validateBackupRoomSchema(expected, actual)
            } finally {
                expected.close()
                actual.close()
            }
        }

    @Test
    fun rejectsIndexColumnDifferences() =
        runBlocking<Unit> {
            val driver = JdbcSQLiteDriver()
            val expected = driver.open(":memory:")
            val actual = driver.open(":memory:")
            try {
                listOf(
                    "CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)",
                    "INSERT INTO room_master_table VALUES (42, 'matching-hash')",
                    "CREATE TABLE event (id INTEGER PRIMARY KEY, remoteFeedbackToken TEXT)",
                ).forEach { sql ->
                    expected.prepare(sql).use { it.step() }
                    actual.prepare(sql).use { it.step() }
                }
                expected.prepare("CREATE INDEX event_token ON event(remoteFeedbackToken)").use { it.step() }
                actual.prepare("CREATE INDEX event_token ON event(id)").use { it.step() }

                assertFailsWith<IllegalStateException> { validateBackupRoomSchema(expected, actual) }
            } finally {
                expected.close()
                actual.close()
            }
        }

    @Test
    fun rejectsForeignKeyViolationsThatPassIntegrityCheck() =
        runBlocking<Unit> {
            val connection = JdbcSQLiteDriver().open(":memory:")
            try {
                connection.prepare("PRAGMA foreign_keys = OFF").use { it.step() }
                connection.prepare("CREATE TABLE parent(id INTEGER PRIMARY KEY)").use { it.step() }
                connection.prepare("CREATE TABLE child(parentId INTEGER REFERENCES parent(id))").use { it.step() }
                connection.prepare("INSERT INTO child VALUES (42)").use { it.step() }
                connection.prepare("PRAGMA integrity_check").use {
                    assertEquals(true, it.step())
                    assertEquals("ok", it.getText(0))
                }

                assertFailsWith<IllegalStateException> { validateBackupDatabase(connection) }
            } finally {
                connection.close()
            }
        }
}
