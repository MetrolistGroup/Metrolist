package com.metrolist.android.backup

import androidx.room3.useWriterConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import com.metrolist.shared.db.entities.SongEntity
import com.metrolist.shared.db.getDatabaseBuilder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [24, 29, 34])
class RoomDatabaseSnapshotTest {
    @Test
    fun failedRoomValidationRestoresOriginalDatabaseBytes() =
        runBlocking {
            val context = RuntimeEnvironment.getApplication()
            context.deleteDatabase("song.db")
            val source = context.getDatabasePath("song.db")
            val database = getDatabaseBuilder(context).build()
            try {
                database.dao().upsert(SongEntity(id = "original", title = "Keep my library"))
            } finally {
                database.close()
            }
            val before = source.readBytes()
            val invalid = File(context.cacheDir, "unsupported.db")
            val pendingDirectory = File(context.cacheDir, "pending-test-restore")
            try {
                AndroidSQLiteDriver().open(invalid.path).use { connection ->
                    connection.execSQL("CREATE TABLE unsupported (id TEXT)")
                    connection.execSQL("PRAGMA user_version = 35")
                }
                val pending = PendingRestore(pendingDirectory)
                pending.stage(invalid, "settings")
                val failure =
                    runCatching {
                        pending.install(source, { "original settings" }, { }) {
                            val restored = getDatabaseBuilder(context).build()
                            try {
                                runBlocking { restored.dao().eventCount().first() }
                            } finally {
                                restored.close()
                            }
                        }
                    }.exceptionOrNull()
                assertTrue(failure is IllegalStateException)
                assertArrayEquals(before, source.readBytes())
                assertFalse(pending.request.exists())
                val recovered = getDatabaseBuilder(context).build()
                try {
                    assertEquals(
                        "Keep my library",
                        recovered
                            .dao()
                            .getSongById("original")
                            ?.song
                            ?.title,
                    )
                } finally {
                    recovered.close()
                }
            } finally {
                context.deleteDatabase("song.db")
                invalid.delete()
                pendingDirectory.deleteRecursively()
            }
        }

    @Test
    fun snapshotRejectsAFileOtherThanTheDatabaseRoomOpened() =
        runBlocking {
            val context = RuntimeEnvironment.getApplication()
            val wrongSource = File(context.cacheDir, "other.db").apply { writeText("Keep source") }
            val destination = File(context.cacheDir, "snapshot.db").apply { writeText("Keep destination") }
            context.deleteDatabase("song.db")
            val database = getDatabaseBuilder(context).build()
            try {
                val failure = runCatching { database.createRoomDatabaseSnapshot(wrongSource, destination) }.exceptionOrNull()
                assertTrue(failure is IllegalStateException)
                assertEquals("Unexpected database snapshot source", failure?.message)
                assertEquals("Keep source", wrongSource.readText())
                assertEquals("Keep destination", destination.readText())
                database.dao().upsert(SongEntity(id = "saved", title = "Still writable"))
                assertEquals(
                    "Still writable",
                    database
                        .dao()
                        .getSongById("saved")
                        ?.song
                        ?.title,
                )
            } finally {
                database.close()
                context.deleteDatabase("song.db")
                wrongSource.delete()
                destination.delete()
            }
        }

    @Test
    fun snapshotRestoresCommittedWalDataAndLeavesSourceWritable() =
        runBlocking {
            val context = RuntimeEnvironment.getApplication()
            val source = context.getDatabasePath("song.db")
            val snapshot = File(context.cacheDir, "snapshot.db")
            context.deleteDatabase("song.db")
            val database = getDatabaseBuilder(context).build()
            try {
                database.dao().upsert(SongEntity(id = "saved", title = "Saved"))
                database.useWriterConnection { connection ->
                    connection.usePrepared("PRAGMA wal_autocheckpoint = 0") { it.step() }
                }
                database.dao().upsert(SongEntity(id = "wal", title = "Committed to WAL"))
                assertTrue(File("${source.path}-wal").length() > 0)
                database.createRoomDatabaseSnapshot(source, snapshot)
                database.dao().upsert(SongEntity(id = "later", title = "After snapshot"))
                AndroidSQLiteDriver().open(snapshot.path).use { connection ->
                    connection.prepare("SELECT id FROM song ORDER BY id").use { statement ->
                        val ids = buildList { while (statement.step()) add(statement.getText(0)) }
                        assertEquals(listOf("saved", "wal"), ids)
                    }
                    connection.prepare("PRAGMA integrity_check").use {
                        check(it.step())
                        assertEquals("ok", it.getText(0))
                    }
                }
                database.close()
                context.deleteDatabase("song.db")
                snapshot.copyTo(source)
                val restored = getDatabaseBuilder(context).build()
                try {
                    assertEquals(
                        "Committed to WAL",
                        restored
                            .dao()
                            .getSongById("wal")
                            ?.song
                            ?.title,
                    )
                    assertEquals(null, restored.dao().getSongById("later"))
                } finally {
                    restored.close()
                }
                assertFalse(File("${snapshot.path}-wal").exists())
            } finally {
                database.close()
                context.deleteDatabase("song.db")
                snapshot.delete()
            }
        }
}
