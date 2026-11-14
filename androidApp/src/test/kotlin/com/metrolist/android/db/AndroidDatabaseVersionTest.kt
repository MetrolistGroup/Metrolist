package com.metrolist.android.db

import android.database.sqlite.SQLiteDatabase
import com.metrolist.shared.db.DATABASE_VERSION
import com.metrolist.shared.db.DatabaseDowngradeException
import com.metrolist.shared.db.checkAndroidDatabaseVersion
import com.metrolist.shared.db.getDatabaseBuilder
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.nio.ByteBuffer

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class AndroidDatabaseVersionTest {
    private val context = RuntimeEnvironment.getApplication()

    @After
    fun tearDown() {
        context.deleteDatabase("song.db")
    }

    @Test
    fun newerVersionIsRejectedWithoutChangingTheFile() {
        val file = context.getDatabasePath("song.db")
        file.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { it.version = DATABASE_VERSION + 1 }
        val before = file.readBytes()

        val error = assertThrows(DatabaseDowngradeException::class.java) { checkAndroidDatabaseVersion(context) }

        assertEquals(DATABASE_VERSION + 1, error.databaseVersion)
        assertEquals(DATABASE_VERSION, error.supportedVersion)
        assertArrayEquals(before, file.readBytes())
    }

    @Test
    fun newerVersionInWalIsRejectedByStartupAndRoomWithoutChangingLibraryData() {
        val file = context.getDatabasePath("song.db")
        file.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { database ->
            database.execSQL("CREATE TABLE library (title TEXT)")
            database.version = DATABASE_VERSION
            assertTrue(database.enableWriteAheadLogging())
            database.compileStatement("PRAGMA wal_autocheckpoint = 0").use { it.simpleQueryForLong() }
            database.execSQL("INSERT INTO library VALUES ('Keep my music')")
            database.version = DATABASE_VERSION + 1
            val original = file.readBytes()
            assertEquals(DATABASE_VERSION, ByteBuffer.wrap(original, 60, 4).int)
            val wal = File("${file.path}-wal")
            val pendingWrites = wal.readBytes()
            assertTrue(pendingWrites.isNotEmpty())

            repeat(2) {
                val startupError = assertThrows(DatabaseDowngradeException::class.java) { checkAndroidDatabaseVersion(context) }
                val builderError = assertThrows(DatabaseDowngradeException::class.java) { getDatabaseBuilder(context) }
                listOf(startupError, builderError).forEach { error ->
                    assertEquals(DATABASE_VERSION + 1, error.databaseVersion)
                    assertEquals(DATABASE_VERSION, error.supportedVersion)
                }
                assertArrayEquals(original, file.readBytes())
                assertArrayEquals(pendingWrites, wal.readBytes())
            }
            database.rawQuery("SELECT title FROM library", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Keep my music", cursor.getString(0))
            }
        }
    }

    @Test
    fun supportedVersionsAndMissingFilesPassUnchanged() {
        val file = context.getDatabasePath("song.db")
        checkAndroidDatabaseVersion(context)
        assertFalse(file.exists())
        file.parentFile?.mkdirs()
        listOf(0, DATABASE_VERSION - 1, DATABASE_VERSION).forEach { version ->
            SQLiteDatabase.openOrCreateDatabase(file, null).use { it.version = version }
            val before = file.readBytes()
            checkAndroidDatabaseVersion(context)
            assertArrayEquals(before, file.readBytes())
        }
    }
}
