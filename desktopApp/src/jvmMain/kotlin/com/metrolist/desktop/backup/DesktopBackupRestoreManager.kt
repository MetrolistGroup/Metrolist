package com.metrolist.desktop.backup

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.metrolist.desktop.history.HistoryRepository
import com.metrolist.desktop.utils.desktopRestartCommand
import com.metrolist.desktop.utils.prepareDesktopRelaunch
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
import com.metrolist.shared.backup.requireSupportedBackupDatabaseVersion
import com.metrolist.shared.backup.requireSupportedFormat
import com.metrolist.shared.backup.shouldConvertRestoredDatabase
import com.metrolist.shared.backup.shouldMigrateDatabaseLikes
import com.metrolist.shared.backup.withLegacyArtistNameAliases
import com.metrolist.shared.db.DATABASE_VERSION
import com.metrolist.shared.db.JdbcSQLiteDriver
import com.metrolist.shared.db.entities.toSongItem
import com.metrolist.shared.db.getDatabaseBuilder
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.backup_create_failed
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.settings.ArtistNameAliases
import com.metrolist.shared.settings.PortableSettings
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.utils.XdgUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.compose.resources.getString
import org.sqlite.SQLiteConfig
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.sql.Connection
import java.sql.DriverManager
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.system.exitProcess

object DesktopBackupRestoreManager {
    private val json = Json { ignoreUnknownKeys = true }
    internal var databaseFileOverride: File? = null
    internal var restoreDirOverride: File? = null
    private val databaseFile get() = databaseFileOverride ?: File(XdgUtils.getXdgDataHome(), "metrolist/metrolist.db")
    private val restoreDir get() = restoreDirOverride ?: File(XdgUtils.getXdgStateHome(), "metrolist/pending-restore")

    fun backup(destination: File): Result<Unit> =
        runCatching {
            val protectedPaths =
                listOf(
                    databaseFile,
                    File(databaseFile.path + "-wal"),
                    File(databaseFile.path + "-shm"),
                    File(databaseFile.path + "-journal"),
                ).map { it.canonicalFile }
            check(destination.canonicalFile !in protectedPaths) { runBlocking { getString(Res.string.backup_create_failed) } }
            ensureCurrentDatabaseExists()
            if (databaseFileOverride == null) HistoryRepository.initialize()
            check(
                destination.absoluteFile.parentFile?.let { it.mkdirs() || it.isDirectory } != false,
            ) { "Could not create backup directory" }
            restoreDir.parentFile.mkdirs()
            val snapshot = File(restoreDir.parentFile, "backup-database.tmp")
            val archive = createPrivateBackupFile(destination.absoluteFile.parentFile.toPath(), ".${destination.name}.").toFile()
            snapshot.delete()
            try {
                snapshotDatabase(databaseFile, snapshot)
                ZipOutputStream(archive.outputStream().buffered()).use { zip ->
                    zip.putNextEntry(ZipEntry(BACKUP_MANIFEST_ENTRY))
                    zip.write(
                        json
                            .encodeToString(
                                BackupManifest.serializer(),
                                BackupManifest(BackupManifest.CURRENT_FORMAT_VERSION, BackupPlatform.DESKTOP, DATABASE_VERSION),
                            ).toByteArray(),
                    )
                    zip.closeEntry()
                    zip.putNextEntry(ZipEntry(BACKUP_SETTINGS_ENTRY))
                    zip.write(
                        json.encodeToString(PortableSettings.serializer(), Settings.shared.exportBackupSettings()).toByteArray().also {
                            check(
                                it.size <= BackupArchiveLimits.SETTINGS_BYTES,
                            ) { runBlocking { getString(Res.string.backup_create_failed) } }
                        },
                    )
                    zip.closeEntry()
                    zip.putNextEntry(ZipEntry(BACKUP_DATABASE_ENTRY))
                    snapshot.inputStream().buffered().use { it.copyTo(zip, BackupArchiveLimits.DATABASE_BYTES) }
                    zip.closeEntry()
                }
                atomicMove(archive, destination)
            } finally {
                snapshot.delete()
                archive.delete()
            }
        }

    fun stageRestore(source: File): Result<Unit> = stageRestore(source, arm = true)

    private fun stageRestore(
        source: File,
        arm: Boolean,
    ): Result<Unit> =
        runCatching {
            val manifest = preflightManifest(source)
            restoreDir.parentFile?.let { check(it.mkdirs() || it.isDirectory) { "Could not create restore directory" } }
            restoreDir.deleteRecursively()
            check(restoreDir.mkdirs() || restoreDir.isDirectory) { "Could not create restore staging directory" }
            restrictToOwner(restoreDir, directory = true)
            var foundData = false
            var foundLegacySettings = false
            var entryCount = 0
            var extractedBytes = 0L
            ZipInputStream(source.inputStream().buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    check(++entryCount <= BackupArchiveLimits.ENTRY_COUNT) { "Backup contains too many entries" }
                    val destination =
                        when (entry.name) {
                            BACKUP_DATABASE_ENTRY, "$BACKUP_DATABASE_ENTRY-wal" -> File(restoreDir, entry.name)
                            BACKUP_SETTINGS_ENTRY -> File(restoreDir, BACKUP_SETTINGS_ENTRY)
                            ArtistNameAliases.BACKUP_FILENAME -> File(restoreDir, ArtistNameAliases.BACKUP_FILENAME)
                            LEGACY_SETTINGS_ENTRY -> File(restoreDir, LEGACY_SETTINGS_ENTRY).also { foundLegacySettings = true }
                            else -> null
                        }
                    if (entry.name == BACKUP_MANIFEST_ENTRY) {
                        extractedBytes += zip.discard(BackupArchiveLimits.MANIFEST_BYTES)
                    } else if (destination != null) {
                        check(!destination.exists()) { "Backup contains duplicate ${entry.name}" }
                        val limit =
                            if (entry.name == BACKUP_DATABASE_ENTRY || entry.name == "$BACKUP_DATABASE_ENTRY-wal") {
                                BackupArchiveLimits.DATABASE_BYTES
                            } else {
                                BackupArchiveLimits.SETTINGS_BYTES
                            }
                        extractedBytes += destination.outputStream().buffered().use { zip.copyTo(it, limit) }
                        restrictToOwner(destination, directory = false)
                        foundData = true
                    } else if (entry.name == "$BACKUP_DATABASE_ENTRY-shm") {
                        extractedBytes += zip.discard(BackupArchiveLimits.DATABASE_BYTES)
                    } else {
                        extractedBytes += zip.discard(BackupArchiveLimits.UNKNOWN_ENTRY_BYTES)
                    }
                    check(extractedBytes <= BackupArchiveLimits.TOTAL_UNCOMPRESSED_BYTES) { "Backup is too large" }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
            check(foundData) { "Backup does not contain Metrolist data" }
            val stagedWal = File(restoreDir, "$BACKUP_DATABASE_ENTRY-wal")
            check(!stagedWal.exists() || File(restoreDir, BACKUP_DATABASE_ENTRY).exists()) { "Backup WAL has no database" }
            val portableSettings = File(restoreDir, BACKUP_SETTINGS_ENTRY).takeIf(File::exists)
            portableSettings?.let {
                json.decodeFromString(PortableSettings.serializer(), it.readText()).also(Settings.shared::validatePortableSettings)
            }
            if (foundLegacySettings && portableSettings == null) {
                val portable = readLegacySettings(File(restoreDir, LEGACY_SETTINGS_ENTRY))
                File(restoreDir, BACKUP_SETTINGS_ENTRY).writeText(json.encodeToString(PortableSettings.serializer(), portable))
                restrictToOwner(File(restoreDir, BACKUP_SETTINGS_ENTRY), directory = false)
            }
            File(restoreDir, ArtistNameAliases.BACKUP_FILENAME).takeIf(File::exists)?.let { aliases ->
                val settingsFile = File(restoreDir, BACKUP_SETTINGS_ENTRY)
                val settings =
                    settingsFile.takeIf(File::exists)?.let {
                        json.decodeFromString(PortableSettings.serializer(), it.readText())
                    }
                val portable = settings.withLegacyArtistNameAliases(aliases.readText())
                settingsFile.writeText(json.encodeToString(PortableSettings.serializer(), portable))
                restrictToOwner(settingsFile, directory = false)
                aliases.delete()
            }
            File(restoreDir, LEGACY_SETTINGS_ENTRY).delete()
            File(restoreDir, BACKUP_DATABASE_ENTRY).takeIf(File::exists)?.let { staged ->
                if (stagedWal.exists()) {
                    JdbcSQLiteDriver().open(staged.absolutePath).use(::checkpointBackupDatabase)
                    requireBackupSize(staged.length(), BackupArchiveLimits.DATABASE_BYTES)
                }
                val version = validateDatabase(staged)
                manifest?.databaseVersion?.let { declared ->
                    check(declared == version) { "Backup database version does not match its manifest" }
                }
                ensureCurrentDatabaseExists()
                val requiresConversion =
                    shouldConvertRestoredDatabase(
                        backupVersion = version,
                        backupIdentityHash = databaseIdentityHash(staged),
                        currentVersion = DATABASE_VERSION,
                        currentIdentityHash = databaseIdentityHash(databaseFile),
                    )
                if (requiresConversion) convertLegacyDatabase(staged)
                DriverManager.getConnection("jdbc:sqlite:${staged.absolutePath}").use { connection ->
                    connection.createStatement().use {
                        it.execute("INSERT OR REPLACE INTO history_import_policy (id, enabled) VALUES (1, 0)")
                    }
                }
            }
            if (arm) markRestoreReady()
        }.onFailure { restoreDir.deleteRecursively() }

    fun applyPendingRestore(settings: Settings): Boolean = applyPendingRestore(settings, ::verifyRestoredDatabaseOpens)

    internal fun applyPendingRestore(
        settings: Settings,
        verifyRestored: () -> List<SongItem>,
    ): Boolean {
        if (!File(restoreDir, MARKER).exists()) {
            if (databaseFile.exists()) cleanupDatabaseRecoveryFiles()
            return false
        }
        val restoredSettings =
            File(restoreDir, BACKUP_SETTINGS_ENTRY).takeIf(File::exists)?.let {
                json.decodeFromString(PortableSettings.serializer(), it.readText())
            }
        val migrateDatabaseLikes =
            shouldMigrateDatabaseLikes(restoredSettings?.entries?.mapTo(mutableSetOf()) { it.key })
        val settingsSnapshot = File(restoreDir, "previous-settings.json")
        val preservedMarker = File(restoreDir, "database-preserved")
        val installationMarker = File(restoreDir, "installation-started")
        val previousDatabase = File(databaseFile.path + ".restore-backup")
        val previousWal = File(databaseFile.path + ".restore-backup-wal")
        val previousShm = File(databaseFile.path + ".restore-backup-shm")
        val installationStarted =
            installationMarker.exists() || preservedMarker.exists() ||
                listOf(previousDatabase, previousWal, previousShm, File(databaseFile.path + ".restore-staged")).any(File::exists)
        val savedSettings =
            try {
                settingsSnapshot.takeIf(File::exists)?.let { json.decodeFromString(PortableSettings.serializer(), it.readText()) }
            } catch (error: SerializationException) {
                if (installationStarted) throw error
                null
            }
        val previousSettings =
            savedSettings ?: run {
                check(!installationStarted) { "Cannot recreate rollback settings after installation began" }
                settings.exportBackupSettings().also { snapshot ->
                    val temporary = createPrivateBackupFile(restoreDir.toPath(), ".previous-settings.").toFile()
                    try {
                        FileOutputStream(temporary).use { output ->
                            output.bufferedWriter().use { writer ->
                                writer.write(json.encodeToString(PortableSettings.serializer(), snapshot))
                                writer.flush()
                                output.fd.sync()
                            }
                        }
                        // Windows atomic moves cannot replace the incomplete pre-install snapshot.
                        Files.deleteIfExists(settingsSnapshot.toPath())
                        Files.move(
                            temporary.toPath(),
                            settingsSnapshot.toPath(),
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING,
                        )
                    } finally {
                        temporary.delete()
                    }
                }
            }
        installationMarker.writeText("1")
        val interrupted = previousDatabase.exists()
        val hadDatabase = interrupted || databaseFile.exists()
        val hadWal = previousWal.exists() || (!preservedMarker.exists() && File(databaseFile.path + "-wal").exists())
        val hadShm = previousShm.exists() || (!preservedMarker.exists() && File(databaseFile.path + "-shm").exists())
        var databasePreserved = interrupted
        var databasePromoted = false
        var databaseLikedSongs = emptyList<SongItem>()
        try {
            File(restoreDir, BACKUP_DATABASE_ENTRY).takeIf(File::exists)?.let { staged ->
                databaseFile.parentFile.mkdirs()
                if (hadDatabase && !databasePreserved) {
                    Files.move(databaseFile.toPath(), previousDatabase.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    databasePreserved = true
                }
                if (!preservedMarker.exists()) {
                    if (!previousWal.exists()) moveIfExists(File(databaseFile.path + "-wal"), previousWal)
                    if (!previousShm.exists()) moveIfExists(File(databaseFile.path + "-shm"), previousShm)
                    preservedMarker.writeText("1")
                }
                Files.deleteIfExists(File(databaseFile.path + "-wal").toPath())
                Files.deleteIfExists(File(databaseFile.path + "-shm").toPath())
                val stagedLiveDatabase = File(databaseFile.path + ".restore-staged")
                Files.deleteIfExists(stagedLiveDatabase.toPath())
                Files.copy(staged.toPath(), stagedLiveDatabase.toPath(), StandardCopyOption.REPLACE_EXISTING)
                atomicMove(stagedLiveDatabase, databaseFile)
                databasePromoted = true
                val verifiedLikes = verifyRestored()
                if (migrateDatabaseLikes) databaseLikedSongs = verifiedLikes
            }
            if (restoredSettings == null) settings.artistNameAliasesJson = "{}"
            restoredSettings?.let(settings::importBackupSettings)
            if (databasePromoted && migrateDatabaseLikes) {
                settings.likedSongsJson = json.encodeToString(databaseLikedSongs)
                settings.likedSongIds = databaseLikedSongs.joinToString(",") { it.id }
            }
            Files.deleteIfExists(File(restoreDir, MARKER).toPath())
            runCatching {
                restoreDir.deleteRecursively()
                cleanupDatabaseRecoveryFiles()
            }
            return true
        } catch (error: Throwable) {
            val rollback =
                runCatching {
                    if (databasePromoted || databasePreserved) {
                        restoreDatabaseFiles(previousDatabase, previousWal, previousShm, hadDatabase, hadWal, hadShm, databasePromoted)
                    }
                    settings.restoreBackupSettingsSnapshot(previousSettings)
                }
            rollback.onFailure(error::addSuppressed)
            if (rollback.isSuccess) {
                Files.deleteIfExists(File(restoreDir, MARKER).toPath())
                runCatching {
                    restoreDir.deleteRecursively()
                    cleanupDatabaseRecoveryFiles()
                }
            }
            throw error
        }
    }

    private fun cleanupDatabaseRecoveryFiles() {
        File(databaseFile.path + ".restore-staged").delete()
        for (suffix in listOf("", "-wal", "-shm")) File(databaseFile.path + ".restore-backup" + suffix).delete()
    }

    private fun markRestoreReady() {
        File(restoreDir, MARKER).writeText("1")
        restrictToOwner(File(restoreDir, MARKER), directory = false)
    }

    fun stageRestoreAndRestart(
        source: File,
        beforeRestart: () -> Unit = {},
    ): Result<Unit> = stageRestoreAndRestart(source, beforeRestart, ::restart)

    internal fun stageRestoreAndRestart(
        source: File,
        beforeRestart: () -> Unit,
        restartApp: (() -> Unit) -> Result<Unit>,
    ): Result<Unit> =
        stageRestore(source, arm = false).mapCatching {
            try {
                restartApp {
                    beforeRestart()
                    markRestoreReady()
                }.getOrThrow()
            } catch (error: Exception) {
                try {
                    Files.deleteIfExists(File(restoreDir, MARKER).toPath())
                    restoreDir.deleteRecursively()
                } catch (cleanupError: Exception) {
                    error.addSuppressed(cleanupError)
                }
                throw error
            }
        }

    fun restart(beforeExit: () -> Unit = {}): Result<Unit> =
        runCatching {
            val appCommand = desktopRestartCommand()
            val process =
                if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
                    ProcessBuilder(listOf("cmd", "/c", "ping -n 2 127.0.0.1 >nul & start \"\"") + appCommand)
                } else {
                    ProcessBuilder(
                        listOf(
                            "sh",
                            "-c",
                            $$"while kill -0 \"$1\" 2>/dev/null; do sleep 0.1; done; shift; exec \"$@\"",
                            "metrolist-restart",
                            ProcessHandle.current().pid().toString(),
                        ) +
                            appCommand,
                    )
                }
            val helper = process.prepareDesktopRelaunch().inheritIO().start()
            try {
                beforeExit()
                exitProcess(0)
            } catch (error: Throwable) {
                helper.destroyForcibly()
                throw error
            }
        }

    private fun preflightManifest(source: File): BackupManifest? =
        ZipFile(source).use { zip ->
            val entries = zip.entries().asSequence().toList()
            check(entries.size <= BackupArchiveLimits.ENTRY_COUNT) { "Backup contains too many entries" }
            val manifests = entries.filter { it.name == BACKUP_MANIFEST_ENTRY }
            check(manifests.size <= 1) { "Backup contains duplicate manifests" }
            manifests.singleOrNull()?.let { entry ->
                json
                    .decodeFromString(
                        BackupManifest.serializer(),
                        zip.getInputStream(entry).readLimited(BackupArchiveLimits.MANIFEST_BYTES).decodeToString(),
                    ).also { manifest ->
                        manifest.requireSupportedFormat()
                        manifest.databaseVersion?.let { version ->
                            requireSupportedBackupDatabaseVersion(version, DATABASE_VERSION)
                        }
                    }
            }
        }

    private fun snapshotDatabase(
        source: File,
        destination: File,
    ) {
        check(source.isFile) { "Database file does not exist" }
        check(destination.parentFile?.let { it.mkdirs() || it.isDirectory } != false) { "Could not create database snapshot directory" }
        Files.deleteIfExists(destination.toPath())
        val sourcePath = source.canonicalFile
        val destinationPath = destination.canonicalFile
        DriverManager.getConnection("jdbc:sqlite:${sourcePath.path}").use { connection ->
            connection.createStatement().use { it.execute("VACUUM main INTO '${destinationPath.path.sqliteLiteral()}'") }
        }
        validateDatabase(destinationPath)
    }

    private fun validateDatabase(file: File): Int {
        readOnlyConnection(file).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("PRAGMA integrity_check").use { result ->
                    check(result.next() && result.getString(1) == "ok") { "Backup database failed integrity check" }
                }
                val songColumns =
                    statement.executeQuery("PRAGMA main.table_info('song')").use { result ->
                        buildSet { while (result.next()) add(result.getString("name")) }
                    }
                check(songColumns.containsAll(setOf("id", "title"))) {
                    "Backup database schema is not supported"
                }
                statement.executeQuery("PRAGMA user_version").use { result ->
                    check(result.next()) { "Backup database version is unavailable" }
                    val version = result.getInt(1)
                    requireSupportedBackupDatabaseVersion(version, DATABASE_VERSION)
                    return version
                }
            }
        }
    }

    private fun databaseIdentityHash(file: File): String? =
        readOnlyConnection(file).use { connection ->
            runCatching {
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT identity_hash FROM room_master_table WHERE id = 42").use { result ->
                        if (result.next()) result.getString(1) else null
                    }
                }
            }.getOrNull()
        }

    private fun readOnlyConnection(file: File): Connection {
        check(file.isFile) { "Database file does not exist" }
        val uri = file.canonicalFile.toURI().toASCIIString()
        return SQLiteConfig().apply { setReadOnly(true) }.createConnection("jdbc:sqlite:$uri?mode=ro&immutable=1")
    }

    private fun ensureCurrentDatabaseExists() {
        databaseFile.parentFile?.let { check(it.mkdirs() || it.isDirectory) { "Could not create database directory" } }
        if (!databaseFile.exists()) {
            val database = getDatabaseBuilder(databaseFile).build()
            try {
                runBlocking { database.dao().allSongs().first() }
            } finally {
                database.close()
            }
        }
    }

    private fun convertLegacyDatabase(legacy: File) {
        val legacyVersion = validateDatabase(legacy)
        ensureCurrentDatabaseExists()
        val converted = File(restoreDir, "converted-$BACKUP_DATABASE_ENTRY")
        snapshotDatabase(databaseFile, converted)
        JdbcSQLiteDriver().open(converted.absolutePath).use { copyLegacyBackupTables(it, legacy.absolutePath, legacyVersion) }
        check(validateDatabase(converted) == DATABASE_VERSION) { "Legacy database conversion failed" }
        Files.move(converted.toPath(), legacy.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    private fun readLegacySettings(file: File): PortableSettings {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        return try {
            val store = PreferenceDataStoreFactory.create(scope = scope) { file }
            val values =
                runBlocking {
                    store.data
                        .first()
                        .asMap()
                        .mapKeys { it.key.name }
                }
            Settings.shared.portableSettingsFromLegacy(values).also(Settings.shared::validatePortableSettings)
        } finally {
            scope.cancel()
        }
    }

    private fun verifyRestoredDatabaseOpens(): List<SongItem> {
        val database = getDatabaseBuilder(databaseFile).build()
        return try {
            runBlocking {
                database.dao().eventCount().first()
                database
                    .dao()
                    .likedSongsByCreateDateAsc()
                    .first()
                    .map { it.toSongItem() }
            }
        } finally {
            database.close()
        }
    }

    private fun restoreDatabaseFiles(
        database: File,
        wal: File,
        shm: File,
        hadDatabase: Boolean,
        hadWal: Boolean,
        hadShm: Boolean,
        promoted: Boolean,
    ) {
        if (promoted || !hadDatabase) Files.deleteIfExists(databaseFile.toPath())
        if (hadDatabase && database.exists()) {
            Files.copy(database.toPath(), databaseFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        restoreSidecar(File(databaseFile.path + "-wal"), wal, hadWal, promoted)
        restoreSidecar(File(databaseFile.path + "-shm"), shm, hadShm, promoted)
    }

    private fun restoreSidecar(
        current: File,
        backup: File,
        existed: Boolean,
        promoted: Boolean,
    ) {
        if (backup.exists()) {
            Files.deleteIfExists(current.toPath())
            Files.copy(backup.toPath(), current.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } else if (!existed || promoted) {
            Files.deleteIfExists(current.toPath())
        }
    }

    private fun moveIfExists(
        source: File,
        destination: File,
    ) {
        if (source.exists()) {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun InputStream.copyTo(
        output: OutputStream,
        limit: Long,
    ): Long {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            total += read
            check(total <= limit) { "Backup entry is too large" }
            output.write(buffer, 0, read)
        }
        return total
    }

    private fun InputStream.readLimited(limit: Long): ByteArray =
        ByteArrayOutputStream().use { output ->
            copyTo(output, limit)
            output.toByteArray()
        }

    private fun InputStream.discard(limit: Long): Long =
        copyTo(
            object : OutputStream() {
                override fun write(value: Int) = Unit
            },
            limit,
        )

    private fun atomicMove(
        source: File,
        destination: File,
    ) {
        try {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (_: FileAlreadyExistsException) {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun restrictToOwner(
        file: File,
        directory: Boolean,
    ) {
        runCatching {
            Files.setPosixFilePermissions(
                file.toPath(),
                if (directory) {
                    setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)
                } else {
                    setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
                },
            )
        }
    }

    private fun String.sqliteLiteral(): String = replace("'", "''")

    private const val MARKER = "ready"
}
