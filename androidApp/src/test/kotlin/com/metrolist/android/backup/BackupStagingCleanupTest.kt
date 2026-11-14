package com.metrolist.android.backup

import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import com.metrolist.shared.backup.BACKUP_DATABASE_ENTRY
import com.metrolist.shared.backup.BACKUP_MANIFEST_ENTRY
import com.metrolist.shared.backup.BACKUP_SETTINGS_ENTRY
import com.metrolist.shared.db.DATABASE_VERSION
import com.metrolist.shared.db.entities.SongEntity
import com.metrolist.shared.db.getDatabaseBuilder
import com.metrolist.shared.settings.Settings
import com.russhwolf.settings.MapSettings
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
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class BackupStagingCleanupTest {
    @Test
    fun stagedWalIsCheckpointedBeforeDatabaseVersionValidation() =
        runBlocking {
            val context = RuntimeEnvironment.getApplication()
            Settings.replaceSharedForTest(Settings(MapSettings()))
            val database = getDatabaseBuilder(context).build()
            val source = File(context.cacheDir, "wal-source.db")
            val archive = File(context.cacheDir, "wal.zip")
            try {
                SQLiteDatabase.openOrCreateDatabase(source, null).use { connection ->
                    connection.enableWriteAheadLogging()
                    connection.execSQL("CREATE TABLE song (id TEXT PRIMARY KEY, title TEXT)")
                    connection.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { assertTrue(it.moveToFirst()) }
                    connection.version = DATABASE_VERSION + 1
                    connection.execSQL("INSERT INTO song VALUES ('song', 'Committed to WAL')")
                    assertTrue(File(source.path + "-wal").length() > 0)
                    ZipOutputStream(archive.outputStream()).use { zip ->
                        listOf("-wal", "").forEach { suffix ->
                            zip.putNextEntry(ZipEntry("$BACKUP_DATABASE_ENTRY$suffix"))
                            File(source.path + suffix).inputStream().use { it.copyTo(zip) }
                            zip.closeEntry()
                        }
                    }
                }
                val error = BackupRestoreManager(context, database).restore(Uri.fromFile(archive)).exceptionOrNull()
                assertTrue(error is BackupRestoreManager.IncompatibleBackupException)
                assertEquals(DATABASE_VERSION + 1, (error as BackupRestoreManager.IncompatibleBackupException).backupVersion)
                for (suffix in listOf("", "-wal", "-shm")) {
                    assertFalse(File(context.cacheDir, "restore-$BACKUP_DATABASE_ENTRY$suffix").exists())
                }
                assertFalse(BackupRestoreManager.hasPendingRestore(context))
            } finally {
                database.close()
                archive.delete()
                source.delete()
                context.deleteDatabase("song.db")
                Settings.replaceSharedForTest(null)
            }
        }

    @Test
    fun invalidArchiveRemovesAlreadyExtractedDatabaseAndSettings() =
        runBlocking {
            val context = RuntimeEnvironment.getApplication()
            Settings.replaceSharedForTest(Settings(MapSettings()))
            val database = getDatabaseBuilder(context).build()
            val archive = File(context.cacheDir, "invalid.zip")
            try {
                database.dao().upsert(SongEntity(id = "original", title = "Keep my library"))
                val originalFiles =
                    listOf("", "-wal").associate { suffix ->
                        val file = File(context.getDatabasePath(BACKUP_DATABASE_ENTRY).path + suffix)
                        file to file.takeIf { it.exists() }?.readBytes()
                    }
                ZipOutputStream(archive.outputStream()).use { zip ->
                    listOf(
                        BACKUP_DATABASE_ENTRY to "database bytes",
                        BACKUP_SETTINGS_ENTRY to "{}",
                        BACKUP_MANIFEST_ENTRY to "invalid",
                    ).forEach { (name, value) ->
                        zip.putNextEntry(ZipEntry(name))
                        zip.write(value.toByteArray())
                        zip.closeEntry()
                    }
                }
                assertTrue(BackupRestoreManager(context, database).restore(Uri.fromFile(archive)).isFailure)
                assertFalse(File(context.cacheDir, "restore-$BACKUP_DATABASE_ENTRY").exists())
                assertFalse(File(context.cacheDir, "restore-$BACKUP_SETTINGS_ENTRY").exists())
                assertFalse(BackupRestoreManager.hasPendingRestore(context))
                originalFiles.forEach { (file, bytes) ->
                    if (bytes == null) assertFalse(file.exists()) else assertArrayEquals(bytes, file.readBytes())
                }
                assertEquals(
                    "Keep my library",
                    database
                        .dao()
                        .getSongById("original")
                        ?.song
                        ?.title,
                )
            } finally {
                database.close()
                archive.delete()
                context.deleteDatabase("song.db")
                Settings.replaceSharedForTest(null)
            }
        }
}
