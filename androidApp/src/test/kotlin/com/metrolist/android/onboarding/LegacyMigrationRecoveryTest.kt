package com.metrolist.android.onboarding

import android.database.sqlite.SQLiteDatabase
import androidx.room3.useReaderConnection
import com.metrolist.shared.db.DATABASE_VERSION
import com.metrolist.shared.db.checkAndroidDatabaseVersion
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

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class LegacyMigrationRecoveryTest {
    @Test
    fun currentKmpDatabaseIsNotOfferedForLegacyMigration() =
        runBlocking {
            val context = RuntimeEnvironment.getApplication()
            val database = getDatabaseBuilder(context).build()
            try {
                database.useReaderConnection { }
                assertFalse(LegacyInstallMigration(context).hasLegacyInstallation())
            } finally {
                database.close()
                context.deleteDatabase("song.db")
            }
        }

    @Test
    fun unreadableLibraryCanBePreservedWhileSetupContinuesWithAnEmptyDatabase() =
        runBlocking {
            val context = RuntimeEnvironment.getApplication()
            Settings.replaceSharedForTest(Settings(MapSettings()))
            val original = context.getDatabasePath("song.db")
            val recoveryRoot = File(context.filesDir, "legacy-library-recovery")
            try {
                original.parentFile?.mkdirs()
                val bytes = "unreadable legacy database".toByteArray()
                original.writeBytes(bytes)
                checkAndroidDatabaseVersion(context)
                assertArrayEquals(bytes, original.readBytes())
                assertFalse(OnboardingManager(context).isComplete)
                val migration = LegacyInstallMigration(context)
                assertTrue(migration.hasLegacyInstallation())
                assertTrue(migration.migrate().isFailure)
                migration.startFresh().getOrThrow()
                assertFalse(migration.hasLegacyInstallation())
                val recovery = recoveryRoot.listFiles()!!.single()
                assertArrayEquals(bytes, File(recovery, "song.db").readBytes())
                SQLiteDatabase.openDatabase(original.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                    assertEquals(DATABASE_VERSION, db.version)
                    db.rawQuery("SELECT COUNT(*) FROM song", null).use { cursor ->
                        assertTrue(cursor.moveToFirst())
                        assertEquals(0, cursor.getInt(0))
                    }
                }
            } finally {
                context.deleteDatabase("song.db")
                recoveryRoot.deleteRecursively()
                Settings.replaceSharedForTest(null)
            }
        }
}
