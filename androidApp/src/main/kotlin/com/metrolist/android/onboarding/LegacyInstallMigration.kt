package com.metrolist.android.onboarding

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room3.useReaderConnection
import com.metrolist.android.backup.BackupRestoreManager
import com.metrolist.android.backup.createFrameworkDatabaseSnapshot
import com.metrolist.shared.backup.BACKUP_DATABASE_ENTRY
import com.metrolist.shared.db.getDatabaseBuilder
import com.metrolist.shared.settings.ArtistNameAliases
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class LegacyInstallMigration(
    private val context: Context,
) {
    private val operationMutex = Mutex()
    private var completedResult: Result<Unit>? = null
    private val _operationInProgress = MutableStateFlow(false)
    val operationInProgress = _operationInProgress.asStateFlow()
    private val _operationResult = MutableStateFlow<Result<Unit>?>(null)
    val operationResult = _operationResult.asStateFlow()

    init {
        recoverInterruptedMigration()
    }

    fun hasLegacyInstallation(): Boolean {
        val database = context.getDatabasePath(DATABASE_NAME)
        if (!database.isFile) return false
        return runCatching {
            SQLiteDatabase.openDatabase(database.path, null, SQLiteDatabase.OPEN_READONLY) { }.use { db ->
                val identity =
                    db.rawQuery("SELECT identity_hash FROM room_master_table WHERE id = 42", null).use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    }
                !isKmpIdentity(identity)
            }
        }.getOrDefault(true)
    }

    suspend fun migrate(): Result<Unit> =
        operationMutex.withLock {
            (completedResult ?: runOperation { migrateBlocking() }).also { _operationResult.value = it }
        }

    suspend fun startFresh(): Result<Unit> =
        operationMutex.withLock {
            runOperation { startFreshBlocking() }.also { _operationResult.value = it }
        }

    private suspend fun startFreshBlocking() {
        check(hasLegacyInstallation()) { "No legacy Metrolist database was found" }
        val recovery = File(context.filesDir, "legacy-library-recovery/${UUID.randomUUID()}")
        check(recovery.mkdirs()) { "Could not create library recovery directory" }
        migrationMarker.writeText(MARKER_STAGING)
        try {
            val backups = archiveDatabaseFiles()
            migrationMarker.writeText(MARKER_ARCHIVED)
            backups.forEach { (original, backup) ->
                File(recovery, original.name).outputStream().use { output ->
                    backup.inputStream().use { it.copyTo(output) }
                    output.fd.sync()
                }
            }
            val emptyDatabase = getDatabaseBuilder(context).build()
            try {
                emptyDatabase.useReaderConnection { }
            } finally {
                emptyDatabase.close()
            }
            migrationMarker.writeText(MARKER_INSTALLED)
            backups.forEach { it.second.delete() }
            migrationMarker.delete()
        } catch (error: Throwable) {
            restoreBackups(replaceAll = migrationMarker.readText() == MARKER_ARCHIVED)
            migrationMarker.delete()
            recovery.deleteRecursively()
            throw error
        }
    }

    fun clearResult() {
        _operationResult.value = null
    }

    private suspend fun runOperation(block: suspend () -> Unit): Result<Unit> {
        _operationInProgress.value = true
        return try {
            withContext(NonCancellable + Dispatchers.IO) {
                runCatching {
                    block()
                }.also {
                    if (it.isSuccess) completedResult = it
                    _operationResult.value = it
                }
            }
        } finally {
            _operationInProgress.value = false
        }
    }

    private suspend fun migrateBlocking() {
        check(hasLegacyInstallation()) { "No legacy Metrolist database was found" }
        val current = context.getDatabasePath(DATABASE_NAME)
        val snapshot = File(context.cacheDir, "onboarding-legacy-$BACKUP_DATABASE_ENTRY")
        snapshot.delete()
        createSnapshot(current, snapshot)

        migrationMarker.writeText(MARKER_STAGING)
        val backups = mutableListOf<Pair<File, File>>()
        try {
            backups += archiveDatabaseFiles()
            migrationMarker.writeText(MARKER_ARCHIVED)
            val emptyDatabase = getDatabaseBuilder(context).build()
            emptyDatabase.useReaderConnection { }
            val converter =
                BackupRestoreManager(
                    context = context,
                    database = emptyDatabase,
                )
            try {
                converter.convertLegacyDatabase(snapshot, current)
                emptyDatabase.close()
                check(context.deleteDatabase(DATABASE_NAME) || !current.exists()) { "Could not replace the temporary database" }
                check(snapshot.copyTo(current, overwrite = true).isFile) { "Could not install the migrated library" }
                converter.verifyRestoredDatabaseOpens()
            } finally {
                emptyDatabase.close()
            }
            ArtistNameAliases.restore(
                ArtistNameAliases.fromLegacyPreferences(
                    context.getSharedPreferences(ArtistNameAliases.LEGACY_PREFERENCES_NAME, Context.MODE_PRIVATE).all,
                ),
            )
            migrationMarker.writeText(MARKER_INSTALLED)
            backups.forEach { it.second.delete() }
            snapshot.delete()
            migrationMarker.delete()
        } catch (error: Throwable) {
            restoreBackups(replaceAll = migrationMarker.readText() == MARKER_ARCHIVED)
            snapshot.delete()
            migrationMarker.delete()
            throw error
        }
    }

    private fun createSnapshot(
        source: File,
        destination: File,
    ) {
        SQLiteDatabase.openDatabase(source.path, null, SQLiteDatabase.OPEN_READWRITE) { }.use { database ->
            createFrameworkDatabaseSnapshot(database, source, destination)
        }
        check(destination.isFile && destination.length() > 0L) { "Could not snapshot the legacy database" }
    }

    private fun archiveDatabaseFiles(): List<Pair<File, File>> =
        databaseFiles().mapNotNull { source ->
            if (!source.exists()) return@mapNotNull null
            val backup = backupFor(source)
            backup.delete()
            check(source.renameTo(backup)) { "Could not stage ${source.name}" }
            source to backup
        }

    private val migrationMarker: File
        get() = File(context.filesDir, "legacy-database-migration.state")

    private fun backupFor(source: File): File = File(source.parentFile, "${source.name}.v13-migration-backup")

    private fun recoverInterruptedMigration() {
        if (!migrationMarker.exists()) return
        when (migrationMarker.readText()) {
            MARKER_INSTALLED -> databaseFiles().forEach { backupFor(it).delete() }
            MARKER_ARCHIVED -> restoreBackups(replaceAll = true)
            else -> restoreBackups(replaceAll = false)
        }
        migrationMarker.delete()
    }

    private fun restoreBackups(replaceAll: Boolean) {
        val files = databaseFiles()
        if (files.none { backupFor(it).exists() }) return
        if (replaceAll) {
            files.forEach { current ->
                check(!current.exists() || current.delete()) { "Could not remove ${current.name} during recovery" }
            }
        }
        files.forEach { original ->
            val backup = backupFor(original)
            if (backup.exists()) {
                original.delete()
                check(backup.renameTo(original)) { "Could not restore ${original.name}" }
            }
        }
    }

    private fun databaseFiles(): List<File> {
        val database = context.getDatabasePath(DATABASE_NAME)
        return listOf(database, File("${database.path}-wal"), File("${database.path}-shm"))
    }

    companion object {
        private const val DATABASE_NAME = "song.db"
        private const val MARKER_STAGING = "staging"
        private const val MARKER_ARCHIVED = "archived"
        private const val MARKER_INSTALLED = "installed"
        private val KMP_IDENTITY_HASHES =
            setOf(
                "ab917eed5930e2e178a42ef5b3d591f2",
                "1bb9fdc7307b0d971f558159b1b4babd",
                "13e578bb5e23ac684507aa55c5dffbb8",
                "f49560b8795dcd167abbda9b6648e4c1",
                "ddc8cfa71888f44e5c60070b2c0a6d95",
                "3c6be733ec5070e0c77075f266e55cfe",
                "f9dcf29fe3bf6a19566c256ed40f3ab2",
                "e7e4f07ce4641e2a1c5ffc29f13c2b44",
                "7b8f98cc39e22247f55efd73850c79cc",
                "fe6500ef7caaa9f120a7eb055e41e43f",
                "e5f6373e45223baefabe1bd7711c90c4",
                "6d6fbb8ee43f3a034805108cff296cb0",
                "c22dd17615df8590dbde079d295d435b",
                "70a0a22a93c39fa7bf427e2f3a583c6d",
                "e728c939ff31d9b513199cc5d0cf30d3",
                "3000198572fdeb0ead03537936ecef18",
                "7d38267e633c7b68a94b1f35c8a10c5c",
                "aaf3870d956fad2ee273a66334655711",
                "545a8df4d11eae58f0733235cfd7c2be",
                "1ca196e130ae9567d8c4105c5b58c64f",
            )

        internal fun isKmpIdentity(identity: String?): Boolean = identity in KMP_IDENTITY_HASHES
    }
}
