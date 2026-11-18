package com.metrolist.android.backup

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.metrolist.shared.backup.BACKUP_DATABASE_ENTRY
import com.metrolist.shared.backup.BACKUP_MANIFEST_ENTRY
import com.metrolist.shared.backup.BACKUP_SETTINGS_ENTRY
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.db.entities.SongEntity
import com.metrolist.shared.db.getDatabaseBuilder
import com.metrolist.shared.settings.Settings
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [34])
class ConcurrentBackupExportTest {
    @Test
    fun secondExportSurvivesCleanupFromCancelledFirstExport() =
        withBackupDatabase { context, database ->
            coroutineScope {
                val first = BackupDocument(File(context.cacheDir, "first.zip"), blocked = true)
                val second = BackupDocument(File(context.cacheDir, "second.zip"), blocked = true)
                registerDocuments(context, first, second)

                database.dao().upsert(SongEntity(id = "first-snapshot", title = "Original song"))
                val firstExport = async { BackupRestoreManager(context, database).backup(first.uri) }
                try {
                    withTimeout(15_000) { first.opened.await() }
                    // Navigating back cancels the old screen scope while the provider call is blocked.
                    firstExport.cancel()
                    database.dao().upsert(SongEntity(id = "second-snapshot", title = "New song"))
                    val secondExport = async { BackupRestoreManager(context, database).backup(second.uri) }
                    withTimeout(15_000) { second.opened.await() }
                    first.release.countDown()
                    withTimeout(15_000) { firstExport.join() }
                    second.release.countDown()

                    val export = withTimeout(15_000) { secondExport.await() }
                    assertTrue("Second export failed: ${export.exceptionOrNull()}", export.isSuccess)
                    assertEquals(listOf("first-snapshot", "second-snapshot"), backedUpSongIds(second.file))
                } finally {
                    first.release.countDown()
                    second.release.countDown()
                }
            }
        }

    @Test
    fun singleExportContainsRoomSnapshot() =
        withBackupDatabase { context, database ->
            val document = BackupDocument(File(context.cacheDir, "single.zip"), blocked = false)
            registerDocuments(context, document)
            database.dao().upsert(SongEntity(id = "single-snapshot", title = "Saved song"))

            BackupRestoreManager(context, database).backup(document.uri).getOrThrow()

            assertTrue(document.opened.isCompleted)
            assertEquals(listOf("single-snapshot"), backedUpSongIds(document.file))
        }

    private fun withBackupDatabase(block: suspend (Application, MusicDatabase) -> Unit) =
        runBlocking {
            val context = RuntimeEnvironment.getApplication()
            Settings.replaceSharedForTest(Settings(MapSettings()))
            val database = getDatabaseBuilder(context).build()
            try {
                block(context, database)
            } finally {
                database.close()
                context.deleteDatabase(BACKUP_DATABASE_ENTRY)
                Settings.replaceSharedForTest(null)
            }
        }

    private fun registerDocuments(
        context: Application,
        vararg documents: BackupDocument,
    ) {
        val provider = BackupDocumentProvider(documents.associateBy { it.uri })
        provider.attachInfo(context, ProviderInfo().apply { authority = BACKUP_AUTHORITY })
        ShadowContentResolver.registerProviderInternal(BACKUP_AUTHORITY, provider)
    }

    private fun backedUpSongIds(archive: File): List<String> {
        val snapshot = File.createTempFile("verify-backup-", ".db", archive.parentFile)
        return try {
            ZipFile(archive).use { zip ->
                assertEquals(
                    setOf(BACKUP_DATABASE_ENTRY, BACKUP_MANIFEST_ENTRY, BACKUP_SETTINGS_ENTRY),
                    zip
                        .entries()
                        .asSequence()
                        .map { it.name }
                        .toSet(),
                )
                zip.getInputStream(zip.getEntry(BACKUP_DATABASE_ENTRY)).use { input ->
                    snapshot.outputStream().use { input.copyTo(it) }
                }
            }
            AndroidSQLiteDriver().open(snapshot.path).use { connection ->
                connection.prepare("PRAGMA integrity_check").use { query ->
                    assertTrue(query.step())
                    assertEquals("ok", query.getText(0))
                }
                connection.prepare("SELECT id FROM song ORDER BY id").use { query ->
                    buildList { while (query.step()) add(query.getText(0)) }
                }
            }
        } finally {
            snapshot.delete()
        }
    }

    private class BackupDocument(
        val file: File,
        blocked: Boolean,
    ) {
        val uri: Uri = Uri.parse("content://$BACKUP_AUTHORITY/${file.name}")
        val opened = CompletableDeferred<Unit>()
        val release = CountDownLatch(if (blocked) 1 else 0)
    }

    private class BackupDocumentProvider(
        val documents: Map<Uri, BackupDocument>,
    ) : ContentProvider() {
        override fun onCreate(): Boolean = true

        override fun openAssetFile(
            uri: Uri,
            mode: String,
        ): AssetFileDescriptor {
            assertEquals("wt", mode)
            val document = documents.getValue(uri)
            val fd = ParcelFileDescriptor.open(document.file, ParcelFileDescriptor.parseMode(mode))
            try {
                document.opened.complete(Unit)
                check(document.release.await(30, TimeUnit.SECONDS)) { "Backup document gate timed out" }
                return AssetFileDescriptor(fd, 0, AssetFileDescriptor.UNKNOWN_LENGTH)
            } catch (error: Throwable) {
                fd.close()
                throw error
            }
        }

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor? = null

        override fun getType(uri: Uri): String = "application/zip"

        override fun insert(
            uri: Uri,
            values: ContentValues?,
        ): Uri? = null

        override fun delete(
            uri: Uri,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int = 0

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int = 0
    }

    private companion object {
        const val BACKUP_AUTHORITY = "backup-export-test"
    }
}
