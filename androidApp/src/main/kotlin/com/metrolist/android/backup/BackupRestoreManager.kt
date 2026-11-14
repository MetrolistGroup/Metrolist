/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.android.backup

import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Build
import android.os.Process
import androidx.core.database.sqlite.transaction
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room3.exclusiveTransaction
import androidx.room3.useReaderConnection
import androidx.room3.useWriterConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.metrolist.shared.backup.BACKUP_DATABASE_ENTRY
import com.metrolist.shared.backup.BACKUP_MANIFEST_ENTRY
import com.metrolist.shared.backup.BACKUP_SETTINGS_ENTRY
import com.metrolist.shared.backup.BackupArchiveLimits
import com.metrolist.shared.backup.BackupManifest
import com.metrolist.shared.backup.BackupPlatform
import com.metrolist.shared.backup.LEGACY_SETTINGS_ENTRY
import com.metrolist.shared.backup.checkpointBackupDatabase
import com.metrolist.shared.backup.copyLegacyBackupTables
import com.metrolist.shared.backup.requireBackupSize
import com.metrolist.shared.backup.requireSupportedFormat
import com.metrolist.shared.backup.shouldConvertRestoredDatabase
import com.metrolist.shared.backup.shouldMigrateDatabaseLikes
import com.metrolist.shared.backup.withLegacyArtistNameAliases
import com.metrolist.shared.db.DATABASE_VERSION
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.db.entities.toSongItem
import com.metrolist.shared.db.getDatabaseBuilder
import com.metrolist.shared.discord.DiscordTokenStore
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.settings.ArtistNameAliases
import com.metrolist.shared.settings.PortableSettings
import com.metrolist.shared.settings.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

internal fun createFrameworkDatabaseSnapshot(
    database: SQLiteDatabase,
    source: File,
    destination: File,
) {
    check(File(database.path).canonicalFile == source.canonicalFile) { "Unexpected database snapshot source" }
    check(source.canonicalFile != destination.canonicalFile) { "Database snapshot must not replace its source" }
    check(destination.parentFile?.let { it.mkdirs() || it.isDirectory } != false) { "Could not create snapshot directory" }
    destination.delete()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        database.execSQL("VACUUM main INTO '${destination.canonicalPath.sqliteLiteral()}'")
        return
    }

    // Android 9 compatibility WAL is not reflected by isWriteAheadLoggingEnabled.
    val wasWriteAheadLoggingEnabled =
        database.rawQuery("PRAGMA journal_mode", null).use { cursor ->
            check(cursor.moveToFirst()) { "Could not read database journal mode" }
            cursor.getString(0).equals("wal", ignoreCase = true)
        }
    database.disableWriteAheadLogging()
    try {
        database.rawQuery("PRAGMA journal_mode", null).use { cursor ->
            check(cursor.moveToFirst() && !cursor.getString(0).equals("wal", ignoreCase = true)) {
                "Could not disable write-ahead logging for snapshot"
            }
        }
        database.transaction {
            source.copyTo(destination, overwrite = true)
        }
    } finally {
        if (wasWriteAheadLoggingEnabled) {
            check(database.enableWriteAheadLogging()) { "Could not restore write-ahead logging" }
        }
    }
}

internal suspend fun MusicDatabase.createRoomDatabaseSnapshot(
    source: File,
    destination: File,
) {
    check(source.canonicalFile != destination.canonicalFile) { "Database snapshot must not replace its source" }
    check(destination.parentFile?.let { it.mkdirs() || it.isDirectory } != false) { "Could not create snapshot directory" }
    useReaderConnection { connection ->
        connection.usePrepared("PRAGMA database_list") { statement ->
            check(statement.step() && File(statement.getText(2)).canonicalFile == source.canonicalFile) {
                "Unexpected database snapshot source"
            }
        }
    }
    val destinationWal = File("${destination.path}-wal")
    destination.delete()
    destinationWal.delete()
    File("${destination.path}-shm").delete()
    try {
        useWriterConnection<Unit> { connection ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                connection.usePrepared("VACUUM main INTO '${destination.canonicalPath.sqliteLiteral()}'") { it.step() }
            } else {
                connection.exclusiveTransaction {
                    source.copyTo(destination, overwrite = true)
                    val sourceWal = File("${source.path}-wal")
                    if (sourceWal.isFile) sourceWal.copyTo(destinationWal, overwrite = true)
                }
            }
        }
        AndroidSQLiteDriver().open(destination.path).use(::checkpointBackupDatabase)
    } catch (error: Throwable) {
        destination.delete()
        throw error
    } finally {
        destinationWal.delete()
        File("${destination.path}-shm").delete()
    }
}

private fun String.sqliteLiteral(): String = replace("'", "''")

class BackupRestoreManager(
    private val context: Context,
    private val database: MusicDatabase,
) {
    suspend fun backup(uri: Uri): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                DiscordTokenStore.migrateLegacyCredentials(context)
                Settings.shared.flush()
                database.useReaderConnection { }
                val databasePath = context.getDatabasePath(BACKUP_DATABASE_ENTRY).path
                val snapshot = File(context.cacheDir, "backup-$BACKUP_DATABASE_ENTRY").canonicalFile
                val archive = File(context.cacheDir, "metrolist-backup.zip.tmp").canonicalFile
                check(snapshot.parentFile?.let { it.mkdirs() || it.isDirectory } != false) { "Could not create backup directory" }
                snapshot.delete()
                archive.delete()
                try {
                    createDatabaseSnapshot(File(databasePath), snapshot)
                    ZipOutputStream(archive.outputStream().buffered()).use { zip ->
                        zip.putNextEntry(ZipEntry(BACKUP_MANIFEST_ENTRY))
                        zip.write(
                            json
                                .encodeToString(
                                    BackupManifest.serializer(),
                                    BackupManifest(
                                        BackupManifest.CURRENT_FORMAT_VERSION,
                                        BackupPlatform.ANDROID,
                                        readDatabaseVersion(snapshot),
                                    ),
                                ).toByteArray(),
                        )
                        zip.closeEntry()
                        zip.putNextEntry(ZipEntry(BACKUP_SETTINGS_ENTRY))
                        zip.write(
                            json.encodeToString(PortableSettings.serializer(), Settings.shared.exportBackupSettings()).toByteArray().also {
                                check(it.size <= BackupArchiveLimits.SETTINGS_BYTES) { "Backup settings are too large" }
                            },
                        )
                        zip.closeEntry()
                        zip.putNextEntry(ZipEntry(BACKUP_DATABASE_ENTRY))
                        snapshot.inputStream().buffered().use { it.copyTo(zip, BackupArchiveLimits.DATABASE_BYTES) }
                        zip.closeEntry()
                    }
                    context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
                        archive.inputStream().buffered().use { it.copyTo(output) }
                        output.flush()
                    } ?: error("Could not open backup destination")
                } finally {
                    snapshot.delete()
                    archive.delete()
                }
            }
        }

    suspend fun restore(uri: Uri): Result<Unit> =
        withContext(Dispatchers.IO) {
            var restoreStage = RestoreStage.READING_BACKUP
            val stagedDb = File(context.cacheDir, "restore-$BACKUP_DATABASE_ENTRY")
            val stagedWal = File("${stagedDb.path}-wal")
            val stagedSettings = File(context.cacheDir, "restore-$BACKUP_SETTINGS_ENTRY")
            val stagedLegacySettings = File(context.cacheDir, "restore-$LEGACY_SETTINGS_ENTRY")

            runCatching {
                try {
                    database.useReaderConnection { }
                    val currentDbPath = context.getDatabasePath(BACKUP_DATABASE_ENTRY).path
                    val currentDbVersion = DATABASE_VERSION

                    stagedDb.delete()
                    stagedWal.delete()
                    File("${stagedDb.path}-shm").delete()
                    stagedSettings.delete()
                    stagedLegacySettings.delete()

                    var aliasJson: String? = null
                    var foundDb = false
                    var foundSettings = false
                    var foundLegacySettings = false
                    var manifest: BackupManifest? = null
                    var entryCount = 0
                    var extractedBytes = 0L
                    context.contentResolver.openInputStream(uri)?.use { rawInput ->
                        ZipInputStream(rawInput.buffered()).use { zip ->
                            var entry = zip.nextEntry
                            while (entry != null) {
                                check(++entryCount <= BackupArchiveLimits.ENTRY_COUNT) { "Backup contains too many entries" }
                                when (entry.name) {
                                    BACKUP_DATABASE_ENTRY -> {
                                        check(!foundDb) { "Backup contains duplicate database entries" }
                                        foundDb = true
                                        extractedBytes +=
                                            stagedDb.outputStream().buffered().use {
                                                zip.copyTo(it, BackupArchiveLimits.DATABASE_BYTES)
                                            }
                                    }

                                    BACKUP_SETTINGS_ENTRY -> {
                                        check(!foundSettings) { "Backup contains duplicate settings entries" }
                                        foundSettings = true
                                        extractedBytes +=
                                            stagedSettings.outputStream().buffered().use {
                                                zip.copyTo(it, BackupArchiveLimits.SETTINGS_BYTES)
                                            }
                                    }

                                    LEGACY_SETTINGS_ENTRY -> {
                                        check(!foundLegacySettings) { "Backup contains duplicate legacy settings entries" }
                                        foundLegacySettings = true
                                        extractedBytes +=
                                            stagedLegacySettings.outputStream().buffered().use {
                                                zip.copyTo(it, BackupArchiveLimits.SETTINGS_BYTES)
                                            }
                                    }

                                    ArtistNameAliases.BACKUP_FILENAME -> {
                                        check(aliasJson == null) { "Backup contains duplicate artist aliases" }
                                        val bytes = zip.readLimited(BackupArchiveLimits.SETTINGS_BYTES)
                                        extractedBytes += bytes.size
                                        aliasJson = bytes.decodeToString()
                                    }

                                    BACKUP_MANIFEST_ENTRY -> {
                                        check(manifest == null) { "Backup contains duplicate manifests" }
                                        val bytes = zip.readLimited(BackupArchiveLimits.MANIFEST_BYTES)
                                        extractedBytes += bytes.size
                                        manifest = json.decodeFromString(BackupManifest.serializer(), bytes.decodeToString())
                                    }

                                    "$BACKUP_DATABASE_ENTRY-wal" -> {
                                        check(!stagedWal.exists()) { "Backup contains duplicate WAL entries" }
                                        extractedBytes +=
                                            stagedWal.outputStream().buffered().use {
                                                zip.copyTo(it, BackupArchiveLimits.DATABASE_BYTES)
                                            }
                                    }

                                    "$BACKUP_DATABASE_ENTRY-shm" -> {
                                        extractedBytes += zip.discard(BackupArchiveLimits.DATABASE_BYTES)
                                    }

                                    else -> {
                                        extractedBytes += zip.discard(BackupArchiveLimits.UNKNOWN_ENTRY_BYTES)
                                    }
                                }
                                check(extractedBytes <= BackupArchiveLimits.TOTAL_UNCOMPRESSED_BYTES) { "Backup is too large" }
                                zip.closeEntry()
                                entry = zip.nextEntry
                            }
                        }
                    } ?: error("Could not open backup")

                    if (!foundDb && !foundSettings && !foundLegacySettings && aliasJson == null) {
                        error("Backup does not contain Metrolist data")
                    }
                    check(!stagedWal.exists() || foundDb) { "Backup WAL has no database" }
                    manifest?.requireSupportedFormat()

                    restoreStage = RestoreStage.READING_SETTINGS
                    var restoredSettings =
                        if (foundSettings) {
                            json
                                .decodeFromString(
                                    PortableSettings.serializer(),
                                    stagedSettings.readText(),
                                ).also(Settings.shared::validatePortableSettings)
                        } else if (foundLegacySettings) {
                            readLegacySettings(stagedLegacySettings)
                        } else {
                            null
                        }

                    aliasJson?.let { restoredSettings = restoredSettings.withLegacyArtistNameAliases(it) }

                    if (foundDb) {
                        restoreStage = RestoreStage.VALIDATING_DATABASE
                        if (stagedWal.exists()) {
                            AndroidSQLiteDriver().open(stagedDb.path).use(::checkpointBackupDatabase)
                            requireBackupSize(stagedDb.length(), BackupArchiveLimits.DATABASE_BYTES)
                        }
                        val backupMetadata = readDatabaseMetadata(stagedDb)
                        check(backupMetadata.version > 0) { "Backup database version is missing or invalid" }
                        if (backupMetadata.version > currentDbVersion) {
                            throw IncompatibleBackupException(backupMetadata.version, currentDbVersion)
                        }
                        val currentIdentityHash = readRoomIdentityHash(File(currentDbPath))
                        val requiresConversion =
                            shouldConvertRestoredDatabase(
                                backupVersion = backupMetadata.version,
                                backupIdentityHash = backupMetadata.identityHash,
                                currentVersion = currentDbVersion,
                                currentIdentityHash = currentIdentityHash,
                            )
                        if (requiresConversion) {
                            restoreStage = RestoreStage.CONVERTING_DATABASE
                            convertLegacyDatabase(stagedDb, File(currentDbPath))
                        }
                    }

                    restoreStage = RestoreStage.INSTALLING_DATABASE
                    Settings.shared.flush()
                    pendingRestore(context).stage(
                        database = stagedDb.takeIf { foundDb },
                        settings = json.encodeToString(PendingRestoreRequest(foundDb, restoredSettings)),
                    )
                    stagedDb.delete()
                    stagedSettings.delete()
                    stagedLegacySettings.delete()
                    restoreStage = RestoreStage.RESTARTING_APP
                    scheduleRestart()
                    withContext(Dispatchers.Main) {
                        Process.killProcess(Process.myPid())
                    }
                } finally {
                    stagedDb.delete()
                    File("${stagedDb.path}-wal").delete()
                    File("${stagedDb.path}-shm").delete()
                    stagedSettings.delete()
                    stagedLegacySettings.delete()
                }
            }.recoverCatching { error ->
                if (error is CancellationException || error is RestoreException) throw error
                throw RestoreFailedException(restoreStage, error)
            }
        }

    private suspend fun createDatabaseSnapshot(
        source: File,
        destination: File,
    ) {
        database.createRoomDatabaseSnapshot(source, destination)
        readDatabaseVersion(destination)
    }

    private fun java.io.InputStream.copyTo(
        output: java.io.OutputStream,
        maxBytes: Long,
    ): Long {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            total += read
            check(total <= maxBytes) { "Backup entry is too large" }
            output.write(buffer, 0, read)
        }
        return total
    }

    private fun java.io.InputStream.readLimited(maxBytes: Long): ByteArray =
        java.io.ByteArrayOutputStream().use { output ->
            copyTo(output, maxBytes)
            output.toByteArray()
        }

    private fun java.io.InputStream.discard(maxBytes: Long): Long =
        copyTo(
            object : java.io.OutputStream() {
                override fun write(value: Int) = Unit
            },
            maxBytes,
        )

    private suspend fun readLegacySettings(file: File): PortableSettings {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        return try {
            val store = PreferenceDataStoreFactory.create(scope = scope) { file }
            val values =
                store.data
                    .first()
                    .asMap()
                    .mapKeys { it.key.name }
            Settings.shared.portableSettingsFromLegacy(values).also(Settings.shared::validatePortableSettings)
        } finally {
            scope.cancel()
        }
    }

    internal suspend fun convertLegacyDatabase(
        legacy: File,
        current: File,
    ) {
        val legacyVersion = readDatabaseVersion(legacy)
        val converted = File(context.cacheDir, "converted-$BACKUP_DATABASE_ENTRY")
        try {
            createDatabaseSnapshot(current, converted)
            AndroidSQLiteDriver().open(converted.path).use { copyLegacyBackupTables(it, legacy.absolutePath, legacyVersion) }
            check(readDatabaseVersion(converted) == DATABASE_VERSION) { "Legacy database conversion failed" }
            converted.copyTo(legacy, overwrite = true)
        } finally {
            converted.delete()
            File("${converted.path}-wal").delete()
            File("${converted.path}-shm").delete()
        }
    }

    internal fun verifyRestoredDatabaseOpens(): List<SongItem> = verifyRestoredDatabaseOpens(context)

    private suspend fun scheduleRestart() =
        withContext(Dispatchers.Main) {
            context.startActivity(
                Intent(context, RestoreRestartActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                },
            )
        }

    private fun readDatabaseVersion(file: File): Int = readDatabaseMetadata(file).version

    private fun readDatabaseMetadata(file: File): DatabaseMetadata =
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { database ->
            val integrityCheck =
                database.rawQuery("PRAGMA integrity_check", null).use { cursor ->
                    cursor.moveToFirst() && cursor.getString(0) == "ok"
                }
            check(integrityCheck) { "Backup database failed integrity check" }
            DatabaseMetadata(database.version, database.readRoomIdentityHash())
        }

    private fun readRoomIdentityHash(file: File): String? =
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { database ->
            database.readRoomIdentityHash()
        }

    private fun SQLiteDatabase.readRoomIdentityHash(): String? =
        runCatching {
            rawQuery("SELECT identity_hash FROM room_master_table WHERE id = 42", null).use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()

    private data class DatabaseMetadata(
        val version: Int,
        val identityHash: String?,
    )

    open class RestoreException(
        message: String,
        cause: Throwable? = null,
    ) : Exception(message, cause)

    class IncompatibleBackupException(
        val backupVersion: Int,
        val currentVersion: Int,
    ) : RestoreException("Backup database version $backupVersion is newer than supported version $currentVersion")

    class RestoreFailedException(
        val stage: RestoreStage,
        cause: Throwable,
    ) : RestoreException(cause.message ?: "Restore failed", cause)

    enum class RestoreStage {
        READING_BACKUP,
        READING_SETTINGS,
        VALIDATING_DATABASE,
        CONVERTING_DATABASE,
        STOPPING_APP,
        INSTALLING_DATABASE,
        VERIFYING_DATABASE,
        APPLYING_SETTINGS,
        RESTARTING_APP,
    }

    @Serializable
    private data class PendingRestoreRequest(
        val hasDatabase: Boolean,
        val settings: PortableSettings?,
    )

    companion object {
        private fun verifyRestoredDatabaseOpens(context: Context): List<SongItem> {
            val restored = getDatabaseBuilder(context).build()
            return try {
                runBlocking {
                    restored.dao().eventCount().first()
                    restored
                        .dao()
                        .likedSongsByCreateDateAsc()
                        .first()
                        .map { it.toSongItem() }
                }
            } finally {
                restored.close()
            }
        }

        private val json = Json { ignoreUnknownKeys = true }

        private fun pendingRestore(context: Context) = PendingRestore(File(context.noBackupFilesDir, "pending-restore"))

        fun hasPendingRestore(context: Context): Boolean = pendingRestore(context).request.exists()

        fun applyPendingRestore(context: Context) {
            val pending = pendingRestore(context)
            if (!pending.request.exists()) return
            DiscordTokenStore.migrateLegacyCredentials(context)
            val request = json.decodeFromString<PendingRestoreRequest>(pending.request.readText())
            pending.install(
                currentDatabase = context.getDatabasePath(BACKUP_DATABASE_ENTRY),
                exportSettings = { json.encodeToString(Settings.shared.exportBackupSettings()) },
                restoreSettings = { Settings.shared.restoreBackupSettingsSnapshot(json.decodeFromString(it)) },
            ) {
                val likedSongs =
                    if (request.hasDatabase) {
                        verifyRestoredDatabaseOpens(context)
                    } else {
                        emptyList()
                    }
                if (request.settings == null) ArtistNameAliases.restore(emptyMap())
                request.settings?.let(Settings.shared::importBackupSettings)
                if (request.hasDatabase && shouldMigrateDatabaseLikes(request.settings?.entries?.mapTo(mutableSetOf()) { it.key })) {
                    Settings.shared.likedSongsJson = json.encodeToString(likedSongs)
                    Settings.shared.likedSongIds = likedSongs.joinToString(",") { it.id }
                }
                Settings.shared.flush()
            }
        }
    }
}
