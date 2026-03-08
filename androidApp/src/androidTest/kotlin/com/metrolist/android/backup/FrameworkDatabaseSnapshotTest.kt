package com.metrolist.android.backup

import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

class FrameworkDatabaseSnapshotTest {
    @Test
    fun snapshotsCommittedRowsWithoutChangingSourceOrJournalMode() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "snapshot-test-${UUID.randomUUID()}")
        check(directory.mkdirs())
        try {
            for (walEnabled in listOf(false, true)) {
                val source = File(directory, "source-$walEnabled.db")
                val snapshot = File(directory, "snapshot-$walEnabled.db")
                SQLiteDatabase.openOrCreateDatabase(source, null).use { database ->
                    if (walEnabled) assertTrue(database.enableWriteAheadLogging())
                    database.rawQuery("PRAGMA wal_autocheckpoint=0", null).use { cursor ->
                        assertTrue(cursor.moveToFirst())
                        assertEquals(0, cursor.getInt(0))
                    }
                    database.execSQL("CREATE TABLE sample (id INTEGER PRIMARY KEY, value TEXT NOT NULL)")
                    database.execSQL("INSERT INTO sample VALUES (1, 'committed')")
                    val originalJournalMode =
                        database.rawQuery("PRAGMA journal_mode", null).use { cursor ->
                            assertTrue(cursor.moveToFirst())
                            cursor.getString(0)
                        }

                    assertThrows(IllegalStateException::class.java) {
                        createFrameworkDatabaseSnapshot(database, source, File(directory, "./${source.name}"))
                    }
                    assertTrue(source.isFile)
                    snapshot.writeText("previous snapshot")
                    assertThrows(IllegalStateException::class.java) {
                        createFrameworkDatabaseSnapshot(database, File(directory, "wrong.db"), snapshot)
                    }
                    assertEquals("previous snapshot", snapshot.readText())

                    createFrameworkDatabaseSnapshot(database, source, snapshot)
                    database.rawQuery("PRAGMA journal_mode", null).use { cursor ->
                        assertTrue(cursor.moveToFirst())
                        assertEquals(originalJournalMode, cursor.getString(0))
                    }
                    database.rawQuery("SELECT value FROM sample", null).use { cursor ->
                        assertTrue(cursor.moveToFirst())
                        assertEquals("committed", cursor.getString(0))
                    }
                    database.execSQL("INSERT INTO sample VALUES (2, 'after snapshot')")
                    SQLiteDatabase.openDatabase(snapshot.path, null, SQLiteDatabase.OPEN_READONLY).use { copy ->
                        copy.rawQuery("PRAGMA integrity_check", null).use { cursor ->
                            assertTrue(cursor.moveToFirst())
                            assertEquals("ok", cursor.getString(0))
                        }
                        copy.rawQuery("SELECT COUNT(*) FROM sample WHERE value = 'committed'", null).use { cursor ->
                            assertTrue(cursor.moveToFirst())
                            assertEquals(1, cursor.getInt(0))
                        }
                        copy.rawQuery("SELECT COUNT(*) FROM sample", null).use { cursor ->
                            assertTrue(cursor.moveToFirst())
                            assertEquals(1, cursor.getInt(0))
                        }
                    }
                }
            }
        } finally {
            directory.deleteRecursively()
        }
    }
}
