package com.metrolist.shared.backup

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.metrolist.shared.db.DATABASE_VERSION
import com.metrolist.shared.db.entities.toSongItem
import com.metrolist.shared.db.getIosDatabaseBuilder
import com.metrolist.shared.db.iosDatabaseFilePath
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.settings.ArtistNameAliases
import com.metrolist.shared.settings.PortableSettings
import com.metrolist.shared.settings.Settings
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import no.synth.kmpzip.io.InputStream
import no.synth.kmpzip.io.OutputStream
import no.synth.kmpzip.io.fileSeekableSource
import no.synth.kmpzip.zip.ZipEntry
import no.synth.kmpzip.zip.ZipFile
import no.synth.kmpzip.zip.ZipOutputStream
import okio.FileSystem
import okio.Path.Companion.toPath
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileProtectionComplete
import platform.Foundation.NSFileProtectionKey
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSUUID
import platform.Foundation.NSUserDefaults
import platform.posix.fclose
import platform.posix.feof
import platform.posix.fflush
import platform.posix.fopen
import platform.posix.fread
import platform.posix.free
import platform.posix.fwrite
import platform.posix.link
import platform.posix.realpath
import platform.posix.rename

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosBackupRestoreManager(
    private val databasePath: String = iosDatabaseFilePath(),
    private val settings: Settings = Settings.shared,
) {
    private val recoveryPath = "$databasePath.restore-state".toPath()
    private val previousDatabase = "$databasePath.restore-rollback"

    fun recoverInterruptedRestore() {
        val fs = FileSystem.SYSTEM
        if (!fs.exists(recoveryPath)) return
        check(fs.metadata(recoveryPath).size!! <= BackupArchiveLimits.SETTINGS_BYTES) { "Restore recovery state is too large" }
        val state = json.decodeFromString<RestoreRecovery>(fs.read(recoveryPath) { readUtf8() })
        if (state.hasDatabase) {
            removeDatabaseSidecars(databasePath)
            if (state.hadDatabase) {
                val recoveryCopy = "$databasePath.restore-recovery"
                fs.copy(previousDatabase.toPath(), recoveryCopy.toPath())
                replaceFile(recoveryCopy, databasePath)
            } else {
                fs.delete(databasePath.toPath(), mustExist = false)
            }
        }
        settings.restoreBackupSettingsSnapshot(state.settings)
        finishRestoreRecovery()
    }

    internal fun prepareRestoreRecovery(
        previousSettings: PortableSettings,
        hasDatabase: Boolean,
        hadDatabase: Boolean,
    ) {
        val fs = FileSystem.SYSTEM
        check(!fs.exists(recoveryPath)) { "An interrupted restore still needs recovery" }
        val staged = "$recoveryPath.tmp".toPath()
        check(
            NSFileManager.defaultManager.createFileAtPath(
                staged.toString(),
                contents = null,
                attributes =
                    mapOf(NSFileProtectionKey to NSFileProtectionComplete),
            ),
        ) {
            "Could not create protected restore recovery state"
        }
        check(NSURL.fileURLWithPath(staged.toString()).setResourceValue(true, NSURLIsExcludedFromBackupKey, error = null)) {
            "Could not exclude restore recovery state from backups"
        }
        val state = json.encodeToString(RestoreRecovery(previousSettings, hasDatabase, hadDatabase))
        check(state.encodeToByteArray().size <= BackupArchiveLimits.SETTINGS_BYTES) { "Restore recovery state is too large" }
        fs.write(staged) { writeUtf8(state) }
        fs.atomicMove(staged, recoveryPath)
    }

    private fun finishRestoreRecovery() {
        check(NSUserDefaults.standardUserDefaults.synchronize()) { "Could not persist restored settings" }
        FileSystem.SYSTEM.delete(recoveryPath, mustExist = true)
        NSFileManager.defaultManager.removeItemAtPath(previousDatabase, error = null)
    }

    @Serializable
    private data class RestoreRecovery(
        val settings: PortableSettings,
        val hasDatabase: Boolean,
        val hadDatabase: Boolean,
    )

    suspend fun backup(destinationPath: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            operationMutex.lock()
            try {
                backupLocked(destinationPath)
            } finally {
                operationMutex.unlock()
            }
        }

    private fun backupLocked(destinationPath: String): Result<Unit> {
        val fileManager = NSFileManager.defaultManager
        val snapshotPath = temporaryPath("backup-snapshot")
        val archivePath = "$destinationPath.${NSUUID().UUIDString}.tmp"
        val previousPath = "$destinationPath.${NSUUID().UUIDString}.previous"
        return try {
            val destination = resolveFilePath(destinationPath)
            val protectedPaths =
                listOf(databasePath, "$databasePath-wal", "$databasePath-shm", "$databasePath-journal")
                    .map(::resolveFilePath)
                    .toSet()
            check(destination !in protectedPaths) { "Backup destination cannot replace the live database or its sidecars" }
            snapshotDatabase(databasePath, snapshotPath)
            val version = databaseVersion(snapshotPath)
            val settingsBytes = json.encodeToString(PortableSettings.serializer(), settings.exportBackupSettings()).encodeToByteArray()
            check(settingsBytes.size <= BackupArchiveLimits.SETTINGS_BYTES) { "Backup settings are too large" }
            check(fileManager.createFileAtPath(archivePath, contents = null, attributes = null)) { "Could not create backup archive" }
            IosFileOutputStream(archivePath).use { output ->
                ZipOutputStream(output).use { zip ->
                    zip.writeEntry(
                        BACKUP_MANIFEST_ENTRY,
                        json
                            .encodeToString(
                                BackupManifest.serializer(),
                                BackupManifest(BackupManifest.CURRENT_FORMAT_VERSION, BackupPlatform.IOS, version),
                            ).encodeToByteArray(),
                    )
                    zip.writeEntry(BACKUP_SETTINGS_ENTRY, settingsBytes)
                    zip.putNextEntry(ZipEntry(BACKUP_DATABASE_ENTRY))
                    IosFileInputStream(snapshotPath).use { input -> input.copyTo(zip, BackupArchiveLimits.DATABASE_BYTES) }
                    zip.closeEntry()
                }
            }
            val hadDestination = fileManager.fileExistsAtPath(destinationPath)
            if (hadDestination) moveFile(destinationPath, previousPath)
            try {
                moveFile(archivePath, destinationPath)
                fileManager.removeItemAtPath(previousPath, error = null)
            } catch (error: Throwable) {
                if (hadDestination && fileManager.fileExistsAtPath(previousPath)) moveFile(previousPath, destinationPath)
                throw error
            }
            Result.success(Unit)
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            Result.failure(error)
        } finally {
            listOf(snapshotPath, archivePath).forEach { fileManager.removeItemAtPath(it, error = null) }
        }
    }

    suspend fun restore(
        sourcePath: String,
        closeDatabase: suspend () -> Unit,
        reopenDatabase: suspend () -> Unit,
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            operationMutex.lock()
            try {
                restoreLocked(sourcePath, closeDatabase, reopenDatabase)
            } finally {
                operationMutex.unlock()
            }
        }

    private suspend fun restoreLocked(
        sourcePath: String,
        closeDatabase: suspend () -> Unit,
        reopenDatabase: suspend () -> Unit,
    ): Result<Unit> {
        var databaseClosed = false
        return try {
            check(!FileSystem.SYSTEM.exists(recoveryPath)) { "An interrupted restore still needs recovery" }
            val stagedDatabase = temporaryPath("restore-staged")
            val fileManager = NSFileManager.defaultManager
            fileManager.removeItemAtPath(stagedDatabase, error = null)
            try {
                val restored =
                    try {
                        readArchive(sourcePath, stagedDatabase).also { backup ->
                            if (fileManager.fileExistsAtPath(stagedDatabase)) validateAndMigrateDatabase(stagedDatabase, backup.manifest)
                        }
                    } catch (error: Exception) {
                        if (error is CancellationException || error is BackupValidationException) throw error
                        throw BackupValidationException(BackupValidationFailure.INVALID_DATA, error)
                    }

                val previousSettings = settings.exportBackupSettings()
                val hadDatabase = fileManager.fileExistsAtPath(databasePath)
                var databaseBackupCreated = false
                var recoveryPrepared = false
                try {
                    if (restored.hasDatabase) {
                        databaseClosed = true
                        withContext(Dispatchers.Main) { closeDatabase() }
                        if (hadDatabase) {
                            checkpointDatabase(databasePath)
                            if (fileManager.fileExistsAtPath(previousDatabase)) {
                                check(fileManager.removeItemAtPath(previousDatabase, error = null)) {
                                    "Could not clear previous restore recovery file"
                                }
                            }
                            check(createHardLink(databasePath, previousDatabase) == 0) { "Could not preserve the current database" }
                            databaseBackupCreated = true
                        }
                        prepareRestoreRecovery(previousSettings, hasDatabase = true, hadDatabase = hadDatabase)
                        recoveryPrepared = true
                        removeDatabaseSidecars(databasePath)
                        replaceFile(stagedDatabase, databasePath)
                        verifyDatabaseOpens(databasePath)
                    }
                    if (!recoveryPrepared) {
                        prepareRestoreRecovery(previousSettings, hasDatabase = false, hadDatabase = hadDatabase)
                        recoveryPrepared = true
                    }
                    val migrateLikes = restored.hasDatabase && restored.migrateDatabaseLikes
                    val likes = if (migrateLikes) readLikedSongs(databasePath) else emptyList()
                    withContext(Dispatchers.Main) {
                        restored.settings?.let(settings::importBackupSettings)
                        if (migrateLikes) {
                            settings.likedSongsJson = json.encodeToString(likes)
                            settings.likedSongIds = likes.joinToString(",") { it.id }
                        }
                    }
                    finishRestoreRecovery()
                    Result.success(Unit)
                } catch (error: Throwable) {
                    val recovered =
                        runCatching {
                            withContext(NonCancellable + Dispatchers.Main) {
                                if (recoveryPrepared) {
                                    recoverInterruptedRestore()
                                } else {
                                    if (databaseBackupCreated) fileManager.removeItemAtPath(previousDatabase, error = null)
                                    settings.restoreBackupSettingsSnapshot(previousSettings)
                                }
                            }
                        }.onFailure(error::addSuppressed)
                    if (databaseClosed && recovered.isSuccess) {
                        runCatching { withContext(NonCancellable + Dispatchers.Main) { reopenDatabase() } }
                            .onFailure(error::addSuppressed)
                    }
                    databaseClosed = false
                    throw error
                }
            } finally {
                fileManager.removeItemAtPath(stagedDatabase, error = null)
                removeDatabaseSidecars(stagedDatabase)
            }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            Result.failure(error)
        }
    }

    private fun readArchive(
        sourcePath: String,
        stagedDatabase: String,
    ): RestoredBackup {
        validateZipEntryCount(sourcePath)
        ZipFile(fileSeekableSource(sourcePath)).use { archive ->
            val entries = archive.entries
            requireBackupSize(entries.size.toLong(), BackupArchiveLimits.ENTRY_COUNT.toLong())
            check(entries.map(ZipEntry::name).distinct().size == entries.size) { "Backup contains duplicate entries" }
            entries.forEach { entry ->
                check(isSafeEntryName(entry.name)) { "Backup contains an unsafe entry name" }
                val limit = entryLimit(entry.name)
                check(entry.size >= 0) { "Backup entry size is invalid" }
                requireBackupSize(entry.size, limit)
            }
            var totalSize = 0L
            entries.forEach { totalSize += it.size }
            requireBackupSize(totalSize, BackupArchiveLimits.TOTAL_UNCOMPRESSED_BYTES)
            var extractedSize = 0L

            fun accountExtracted(size: Long) {
                extractedSize += size
                requireBackupSize(extractedSize, BackupArchiveLimits.TOTAL_UNCOMPRESSED_BYTES)
            }
            val manifest =
                entries.singleOrNull { it.name == BACKUP_MANIFEST_ENTRY }?.let { entry ->
                    val bytes = readEntry(archive, entry, BackupArchiveLimits.MANIFEST_BYTES)
                    accountExtracted(bytes.size.toLong())
                    val parsed = json.decodeFromString(BackupManifest.serializer(), bytes.decodeToString())
                    parsed.requireSupportedFormat()
                    parsed
                }
            val databaseEntry = entries.singleOrNull { it.name == BACKUP_DATABASE_ENTRY }
            val walEntry = entries.singleOrNull { it.name == "$BACKUP_DATABASE_ENTRY-wal" }
            check(walEntry == null || databaseEntry != null) { "Backup WAL has no database" }
            val settingsEntry = entries.singleOrNull { it.name == BACKUP_SETTINGS_ENTRY }
            val legacySettingsEntry = entries.singleOrNull { it.name == LEGACY_SETTINGS_ENTRY }
            check(
                databaseEntry != null || settingsEntry != null || legacySettingsEntry != null,
            ) { "Backup does not contain Metrolist data" }
            databaseEntry?.let {
                accountExtracted(copyEntry(archive, it, stagedDatabase, BackupArchiveLimits.DATABASE_BYTES))
            }
            walEntry?.let {
                accountExtracted(copyEntry(archive, it, "$stagedDatabase-wal", BackupArchiveLimits.DATABASE_BYTES))
                withConnection(stagedDatabase, ::checkpointBackupDatabase)
                requireBackupSize(FileSystem.SYSTEM.metadata(stagedDatabase.toPath()).size!!, BackupArchiveLimits.DATABASE_BYTES)
            }
            val portableBytes =
                settingsEntry?.let {
                    readEntry(archive, it, BackupArchiveLimits.SETTINGS_BYTES).also { bytes -> accountExtracted(bytes.size.toLong()) }
                }
            val legacyBytes =
                legacySettingsEntry?.let {
                    readEntry(archive, it, BackupArchiveLimits.SETTINGS_BYTES).also { bytes -> accountExtracted(bytes.size.toLong()) }
                }
            var portableSettings =
                portableBytes?.let {
                    json.decodeFromString(PortableSettings.serializer(), it.decodeToString()).also(settings::validatePortableSettings)
                } ?: legacyBytes?.let {
                    settings.portableSettingsFromLegacy(decodeLegacyPreferences(it)).also(settings::validatePortableSettings)
                }
            entries.singleOrNull { it.name == ArtistNameAliases.BACKUP_FILENAME }?.let { entry ->
                val bytes = readEntry(archive, entry, BackupArchiveLimits.SETTINGS_BYTES)
                accountExtracted(bytes.size.toLong())
                portableSettings =
                    portableSettings.withLegacyArtistNameAliases(bytes.decodeToString()).also(settings::validatePortableSettings)
            }
            entries
                .filter {
                    it.name !in
                        setOf(
                            BACKUP_MANIFEST_ENTRY,
                            BACKUP_DATABASE_ENTRY,
                            "$BACKUP_DATABASE_ENTRY-wal",
                            BACKUP_SETTINGS_ENTRY,
                            LEGACY_SETTINGS_ENTRY,
                            ArtistNameAliases.BACKUP_FILENAME,
                        )
                }.forEach {
                    accountExtracted(drainEntry(archive, it, entryLimit(it.name)))
                }
            return RestoredBackup(
                manifest = manifest,
                settings = portableSettings,
                hasDatabase = databaseEntry != null,
                migrateDatabaseLikes = shouldMigrateDatabaseLikes(portableSettings?.entries?.mapTo(mutableSetOf()) { it.key }),
            )
        }
    }

    private suspend fun validateAndMigrateDatabase(
        path: String,
        manifest: BackupManifest?,
    ) {
        validateDatabaseIntegrity(path)
        val version = databaseVersion(path)
        check(version > 0) { "Backup database version is missing or invalid" }
        requireSupportedBackupDatabaseVersion(version, DATABASE_VERSION)
        manifest?.databaseVersion?.let { check(it == version) { "Backup database version does not match its manifest" } }
        if (shouldConvertRestoredDatabase(version, databaseIdentityHash(path), DATABASE_VERSION, databaseIdentityHash(databasePath))) {
            convertLegacyDatabase(path, version)
        }
        val migrated = getIosDatabaseBuilder(path).build()
        try {
            migrated.dao().eventCount().first()
        } finally {
            migrated.close()
        }
        check(databaseVersion(path) == DATABASE_VERSION) { "Backup database migration failed" }
        validateRoomSchema(path)
    }

    private suspend fun validateRoomSchema(path: String) {
        val referencePath = temporaryPath("schema-reference")
        val fileManager = NSFileManager.defaultManager
        try {
            val reference = getIosDatabaseBuilder(referencePath).build()
            try {
                reference.dao().eventCount().first()
            } finally {
                reference.close()
            }
            withConnection(referencePath) { expected ->
                withConnection(path) { actual -> validateBackupRoomSchema(expected, actual) }
            }
        } finally {
            listOf(referencePath, "$referencePath-wal", "$referencePath-shm").forEach {
                fileManager.removeItemAtPath(it, error = null)
            }
        }
    }

    private suspend fun verifyDatabaseOpens(path: String) {
        val restored = getIosDatabaseBuilder(path).build()
        try {
            restored.dao().eventCount().first()
        } finally {
            restored.close()
        }
    }

    private suspend fun readLikedSongs(path: String): List<SongItem> {
        val restored = getIosDatabaseBuilder(path).build()
        return try {
            restored
                .dao()
                .likedSongsByCreateDateAsc()
                .first()
                .map { it.toSongItem() }
        } finally {
            restored.close()
        }
    }

    private suspend fun convertLegacyDatabase(
        legacyPath: String,
        legacyVersion: Int,
    ) {
        val convertedPath = temporaryPath("converted")
        val previousPath = temporaryPath("legacy-previous")
        val fileManager = NSFileManager.defaultManager
        try {
            val current = getIosDatabaseBuilder(convertedPath).build()
            try {
                current.dao().eventCount().first()
            } finally {
                current.close()
            }
            val connection = BundledSQLiteDriver().open(convertedPath)
            try {
                copyLegacyBackupTables(connection, legacyPath, legacyVersion)
            } finally {
                connection.close()
            }
            withConnection(convertedPath) { checkpoint ->
                checkpoint.prepare("PRAGMA wal_checkpoint(TRUNCATE)").use {
                    check(it.step() && it.getLong(0) == 0L) { "Could not checkpoint converted database" }
                }
                0
            }
            validateDatabaseIntegrity(convertedPath)
            check(databaseVersion(convertedPath) == DATABASE_VERSION) { "Legacy database conversion failed" }
            moveFile(legacyPath, previousPath)
            try {
                moveFile(convertedPath, legacyPath)
                fileManager.removeItemAtPath(previousPath, error = null)
            } catch (error: Throwable) {
                if (fileManager.fileExistsAtPath(previousPath)) moveFile(previousPath, legacyPath)
                throw error
            }
        } finally {
            listOf(convertedPath, "$convertedPath-wal", "$convertedPath-shm").forEach {
                fileManager.removeItemAtPath(it, error = null)
            }
        }
    }

    private fun snapshotDatabase(
        source: String,
        destination: String,
    ) {
        NSFileManager.defaultManager.removeItemAtPath(destination, error = null)
        val connection = BundledSQLiteDriver().open(source)
        try {
            connection.prepare("VACUUM main INTO '${destination.sqliteLiteral()}'").use { it.step() }
        } finally {
            connection.close()
        }
        check(databaseVersion(destination) == DATABASE_VERSION) { "Could not create a current database snapshot" }
    }

    private fun databaseIdentityHash(path: String): String? =
        runCatching {
            withConnection(path) { connection ->
                connection.prepare("SELECT identity_hash FROM room_master_table WHERE id = 42").use {
                    if (it.step()) it.getText(0) else null
                }
            }
        }.getOrNull()

    private fun databaseVersion(path: String): Int =
        withConnection(path) { connection ->
            connection.prepare("PRAGMA user_version").use { statement ->
                check(statement.step()) { "Backup database version is unavailable" }
                statement.getLong(0).toInt()
            }
        }

    private inline fun <T> withConnection(
        path: String,
        block: (SQLiteConnection) -> T,
    ): T {
        val connection = BundledSQLiteDriver().open(path)
        return try {
            block(connection)
        } finally {
            connection.close()
        }
    }

    private fun validateDatabaseIntegrity(path: String) {
        withConnection(path, ::validateBackupDatabase)
    }

    private fun moveFile(
        source: String,
        destination: String,
    ) {
        check(NSFileManager.defaultManager.moveItemAtPath(source, toPath = destination, error = null)) {
            "Could not move ${source.substringAfterLast('/')}"
        }
    }

    private fun checkpointDatabase(path: String) {
        withConnection(path) { connection ->
            connection.prepare("PRAGMA wal_checkpoint(TRUNCATE)").use {
                check(it.step() && it.getLong(0) == 0L) { "Could not checkpoint current database" }
            }
        }
    }

    private fun removeDatabaseSidecars(path: String) {
        val fileManager = NSFileManager.defaultManager
        listOf("$path-wal", "$path-shm", "$path.lck").forEach { sidecar ->
            if (fileManager.fileExistsAtPath(sidecar)) {
                check(fileManager.removeItemAtPath(sidecar, error = null)) { "Could not remove database sidecar" }
            }
        }
    }

    private fun createHardLink(
        source: String,
        destination: String,
    ): Int =
        (source + '\u0000').encodeToByteArray().usePinned { sourceBytes ->
            (destination + '\u0000').encodeToByteArray().usePinned { destinationBytes ->
                link(sourceBytes.addressOf(0), destinationBytes.addressOf(0))
            }
        }

    private fun replaceFile(
        source: String,
        destination: String,
    ) {
        val result =
            (source + '\u0000').encodeToByteArray().usePinned { sourceBytes ->
                (destination + '\u0000').encodeToByteArray().usePinned { destinationBytes ->
                    rename(sourceBytes.addressOf(0), destinationBytes.addressOf(0))
                }
            }
        check(result == 0) { "Could not atomically replace database" }
    }

    private fun resolveFilePath(path: String): String {
        val absolute = if (path.startsWith('/')) path else "${realPath(".") ?: error("Current path is unavailable")}/$path"
        val normalized = normalizeAbsoluteBackupPath(absolute)
        realPath(normalized)?.let { return it }
        val filename = normalized.substringAfterLast('/')
        check(filename.isNotEmpty()) { "Backup path is invalid" }
        val parent = normalized.substringBeforeLast('/', "").ifEmpty { "/" }
        val resolvedParent = realPath(parent) ?: error("Backup destination directory does not exist")
        return if (resolvedParent == "/") "/$filename" else "$resolvedParent/$filename"
    }

    private fun realPath(path: String): String? {
        val resolved =
            (path + '\u0000').encodeToByteArray().usePinned { pathBytes ->
                realpath(pathBytes.addressOf(0), null)
            } ?: return null
        return try {
            resolved.toKString()
        } finally {
            free(resolved)
        }
    }

    private fun copyEntry(
        archive: ZipFile,
        entry: ZipEntry,
        destination: String,
        limit: Long,
    ): Long {
        check(NSFileManager.defaultManager.createFileAtPath(destination, contents = null, attributes = null)) {
            "Could not create staged database"
        }
        return IosFileOutputStream(destination).use { output -> archive.getInputStream(entry).use { it.copyTo(output, limit) } }
    }

    private fun readEntry(
        archive: ZipFile,
        entry: ZipEntry,
        limit: Long,
    ): ByteArray {
        val chunks = mutableListOf<ByteArray>()
        var total = 0
        archive.getInputStream(entry).use { input ->
            val buffer = ByteArray(IO_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                check(read > 0) { "Backup entry read made no progress" }
                total += read
                requireBackupSize(total.toLong(), limit)
                chunks += buffer.copyOf(read)
            }
        }
        return ByteArray(total).also { result ->
            var offset = 0
            chunks.forEach { chunk -> chunk.copyInto(result, offset).also { offset += chunk.size } }
        }
    }

    private fun drainEntry(
        archive: ZipFile,
        entry: ZipEntry,
        limit: Long,
    ): Long = archive.getInputStream(entry).use { it.copyTo(DiscardOutputStream, limit) }

    private fun isSafeEntryName(name: String): Boolean =
        name.isNotBlank() &&
            name.length <= 255 &&
            !name.startsWith('/') &&
            '\\' !in name &&
            '/' !in name &&
            name != "." &&
            name != ".."

    private fun entryLimit(name: String): Long =
        when (name) {
            BACKUP_DATABASE_ENTRY, "$BACKUP_DATABASE_ENTRY-wal", "$BACKUP_DATABASE_ENTRY-shm" -> BackupArchiveLimits.DATABASE_BYTES
            BACKUP_SETTINGS_ENTRY, LEGACY_SETTINGS_ENTRY, ArtistNameAliases.BACKUP_FILENAME -> BackupArchiveLimits.SETTINGS_BYTES
            BACKUP_MANIFEST_ENTRY -> BackupArchiveLimits.MANIFEST_BYTES
            else -> BackupArchiveLimits.UNKNOWN_ENTRY_BYTES
        }

    private fun validateZipEntryCount(path: String) {
        fileSeekableSource(path).use { source ->
            requireBackupSize(source.size, MAX_ARCHIVE_BYTES)
            check(source.size >= 22) { "Backup archive is truncated" }
            val length = minOf(source.size, 65_557L).toInt()
            val tail = ByteArray(length)
            var copied = 0
            while (copied < tail.size) {
                val count = source.read(source.size - tail.size + copied, tail, copied, tail.size - copied)
                check(count > 0) { "Backup archive is truncated" }
                copied += count
            }
            val eocd =
                (tail.size - 22 downTo 0).firstOrNull { offset ->
                    tail.readLeInt(offset) == 0x06054b50L &&
                        offset + 22 + tail.readLeShort(offset + 20) == tail.size
                } ?: error("Backup archive is corrupt")
            check(tail.readLeShort(eocd + 4) == 0 && tail.readLeShort(eocd + 6) == 0) { "Split archives are not supported" }
            val count = tail.readLeShort(eocd + 10)
            requireBackupSize(count.toLong(), BackupArchiveLimits.ENTRY_COUNT.toLong())
            check(tail.readLeShort(eocd + 8) == count) { "Backup archive is corrupt" }
            val centralSize = tail.readLeInt(eocd + 12)
            val centralOffset = tail.readLeInt(eocd + 16)
            check(centralSize != 0xffffffffL && centralOffset != 0xffffffffL && centralOffset + centralSize <= source.size) {
                "Backup archive is corrupt or uses unsupported ZIP64 features"
            }
            var position = centralOffset
            repeat(count) {
                val header = source.readFully(position, 46)
                check(header.readLeInt(0) == 0x02014b50L) { "Backup archive central directory is corrupt" }
                val nameLength = header.readLeShort(28)
                val extraLength = header.readLeShort(30)
                val commentLength = header.readLeShort(32)
                val recordLength = 46L + nameLength + extraLength + commentLength
                check(position + recordLength <= centralOffset + centralSize) { "Backup archive central directory is truncated" }
                val nameBytes = source.readFully(position + 46, nameLength)
                val name = nameBytes.decodeToString(throwOnInvalidSequence = true)
                check(isSafeEntryName(name)) { "Backup contains an unsafe entry name" }
                position += recordLength
            }
            check(position == centralOffset + centralSize) { "Backup archive central directory is corrupt" }
        }
    }

    private fun no.synth.kmpzip.io.SeekableSource.readFully(
        position: Long,
        length: Int,
    ): ByteArray =
        ByteArray(length).also { bytes ->
            var offset = 0
            while (offset < length) {
                val read = read(position + offset, bytes, offset, length - offset)
                check(read > 0) { "Backup archive is truncated" }
                offset += read
            }
        }

    private fun ByteArray.readLeShort(offset: Int): Int = (this[offset].toInt() and 0xff) or ((this[offset + 1].toInt() and 0xff) shl 8)

    private fun ByteArray.readLeInt(offset: Int): Long =
        (0..3).fold(0L) { value, shift -> value or ((this[offset + shift].toLong() and 0xff) shl (shift * 8)) }

    private fun temporaryPath(suffix: String): String = "$databasePath.$suffix-${NSUUID().UUIDString}"

    private fun String.sqliteLiteral(): String = replace("'", "''")

    private data class RestoredBackup(
        val manifest: BackupManifest?,
        val settings: PortableSettings?,
        val hasDatabase: Boolean,
        val migrateDatabaseLikes: Boolean,
    )

    private class IosFileInputStream(
        path: String,
    ) : InputStream() {
        private val file = openFile(path, "rb") ?: error("Could not open backup file")
        private var closed = false

        override fun read(): Int {
            val buffer = ByteArray(1)
            return when (read(buffer, 0, 1)) {
                -1 -> -1
                else -> buffer[0].toInt() and 0xff
            }
        }

        override fun read(
            b: ByteArray,
            off: Int,
            len: Int,
        ): Int {
            if (closed) error("Stream is closed")
            if (len == 0) return 0
            val count = b.usePinned { fread(it.addressOf(off), 1uL, len.toULong(), file).toInt() }
            if (count == 0) {
                check(feof(file) != 0) { "Could not read backup file" }
                return -1
            }
            return count
        }

        override fun close() {
            if (!closed) {
                check(fclose(file) == 0) { "Could not close backup file" }
                closed = true
            }
        }
    }

    private class IosFileOutputStream(
        path: String,
    ) : OutputStream() {
        private val file = openFile(path, "wb") ?: error("Could not create backup file")
        private var closed = false

        override fun write(b: Int) = write(byteArrayOf(b.toByte()))

        override fun write(
            b: ByteArray,
            off: Int,
            len: Int,
        ) {
            if (closed) error("Stream is closed")
            var written = 0
            while (written < len) {
                val count =
                    b.usePinned {
                        fwrite(it.addressOf(off + written), 1uL, (len - written).toULong(), file).toInt()
                    }
                check(count > 0) { "Could not write backup file" }
                written += count
            }
        }

        override fun flush() {
            check(fflush(file) == 0) { "Could not flush backup file" }
        }

        override fun close() {
            if (!closed) {
                check(fclose(file) == 0) { "Could not close backup file" }
                closed = true
            }
        }
    }

    private object DiscardOutputStream : OutputStream() {
        override fun write(b: Int) = Unit

        override fun write(
            b: ByteArray,
            off: Int,
            len: Int,
        ) = Unit

        override fun close() = Unit
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
        const val IO_BUFFER_SIZE = 64 * 1024

        private fun openFile(
            path: String,
            mode: String,
        ) = (path + '\u0000').encodeToByteArray().usePinned { pathBytes ->
            (mode + '\u0000').encodeToByteArray().usePinned { modeBytes ->
                fopen(pathBytes.addressOf(0), modeBytes.addressOf(0))
            }
        }

        const val MAX_ARCHIVE_BYTES = BackupArchiveLimits.TOTAL_UNCOMPRESSED_BYTES + 2L * 1024 * 1024

        // shortcut: One lock serializes all databases; use per-database locks if multiple profiles are supported.
        val operationMutex = Mutex()
    }
}

private fun ZipOutputStream.writeEntry(
    name: String,
    bytes: ByteArray,
) {
    putNextEntry(ZipEntry(name))
    write(bytes)
    closeEntry()
}

private fun InputStream.copyTo(
    output: OutputStream,
    limit: Long,
): Long {
    val buffer = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        val count = read(buffer)
        if (count < 0) return total
        check(count > 0) { "Backup entry read made no progress" }
        total += count
        requireBackupSize(total, limit)
        output.write(buffer, 0, count)
    }
}
