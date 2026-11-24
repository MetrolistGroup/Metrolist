package com.metrolist.desktop.backup

import com.metrolist.desktop.utils.AppLogger
import com.metrolist.shared.backup.AutoBackupDestination
import com.metrolist.shared.backup.AutoBackupFrequency
import com.metrolist.shared.backup.DriveAccountConnectionState
import com.metrolist.shared.backup.DriveCleanupException
import com.metrolist.shared.backup.DriveReauthorizationRequiredException
import com.metrolist.shared.backup.GoogleDriveBackupClient
import com.metrolist.shared.backup.defaultBackupFilename
import com.metrolist.shared.backup.isAutoBackupDue
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.utils.XdgUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import java.io.File
import kotlin.concurrent.withLock

object DesktopAutomaticBackupScheduler {
    private var scope: CoroutineScope? = null
    private var job: Job? = null
    private var pruningPending = false

    fun start(applicationScope: CoroutineScope) {
        pruningPending = true
        scope = applicationScope
        schedule()
    }

    fun schedule() {
        val activeScope = scope ?: return
        val previousJob = job
        previousJob?.cancel()
        val settings = Settings.shared
        if (!settings.autoBackupEnabled) return

        val frequency = AutoBackupFrequency.fromSetting(settings.autoBackupFrequency)
        val initialDestination = AutoBackupDestination.fromSetting(settings.autoBackupDestination)
        job =
            activeScope.launch {
                previousJob?.join()
                var destination = initialDestination
                while (true) {
                    if (settings.credentialsPending) {
                        delay(CREDENTIAL_RECOVERY_POLL_MILLIS)
                        continue
                    }
                    if (DriveAccountConnectionState.normalizeAutoBackupDestination(DesktopDriveSignIn.isConfigured())) {
                        destination = AutoBackupDestination.LOCAL
                    }
                    if (AutoBackupDestination.fromSetting(settings.autoBackupDestination) != destination) return@launch
                    if (destination == AutoBackupDestination.GOOGLE_DRIVE && !DriveAccountConnectionState.connected.value) return@launch
                    if (pruningPending) {
                        val localDirectory = File(XdgUtils.getXdgDataHome(), "metrolist/automatic-backups")
                        val pruneResult = withContext(Dispatchers.IO) { runCatching { pruneBackups(localDirectory) } }
                        pruningPending = pruneResult.isFailure
                        if (pruneResult.isFailure) {
                            AppLogger.warning(
                                "AutomaticBackup",
                                "Could not prune automatic backups: ${pruneResult.exceptionOrNull()?.message ?: "Unknown error"}",
                            )
                        }
                    }
                    val now = System.currentTimeMillis()
                    if (isAutoBackupDue(settings.autoBackupLastSuccessEpochMillis, now, frequency)) {
                        val directory =
                            when (destination) {
                                AutoBackupDestination.LOCAL -> File(XdgUtils.getXdgDataHome(), "metrolist/automatic-backups")
                                AutoBackupDestination.GOOGLE_DRIVE -> File(XdgUtils.getXdgCacheHome(), "metrolist/automatic-backups")
                            }
                        val backupFile = File(directory, "${defaultBackupFilename(BACKUP_FILENAME_PREFIX)}.zip")
                        var driveSession: String? = null
                        val result: Result<Boolean> =
                            runCatching {
                                val brokerUrl =
                                    if (destination == AutoBackupDestination.GOOGLE_DRIVE) {
                                        DesktopDriveSignIn.requireBrokerUrl().also {
                                            driveSession = settings.driveBrokerSession.takeIf(String::isNotBlank)
                                                ?: error("Google Drive account is not connected")
                                        }
                                    } else {
                                        null
                                    }
                                try {
                                    if (destination == AutoBackupDestination.GOOGLE_DRIVE) {
                                        withContext(Dispatchers.IO) { clearStagedArchives(directory) }
                                    }
                                    withContext(Dispatchers.IO + NonCancellable) {
                                        DesktopBackupRestoreManager.backup(backupFile).getOrThrow()
                                    }
                                    if (destination == AutoBackupDestination.LOCAL) {
                                        withContext(Dispatchers.IO + NonCancellable) {
                                            settings.autoBackupLastSuccessEpochMillis = System.currentTimeMillis()
                                            pruningPending = true
                                            runCatching { settings.flush() }
                                                .onFailure { error ->
                                                    AppLogger.warning("AutomaticBackup", "Could not persist automatic backup time", error)
                                                }
                                        }
                                        true
                                    } else {
                                        if (AutoBackupDestination.fromSetting(settings.autoBackupDestination) != destination) {
                                            return@runCatching false
                                        }
                                        try {
                                            uploadBackup(backupFile, checkNotNull(brokerUrl), checkNotNull(driveSession))
                                        } catch (cleanup: DriveCleanupException) {
                                            DriveAccountConnectionState.recordCleanupFailure()
                                        }
                                        withContext(Dispatchers.IO + NonCancellable) {
                                            settings.autoBackupLastSuccessEpochMillis = System.currentTimeMillis()
                                            runCatching { settings.flush() }
                                                .onFailure { error ->
                                                    AppLogger.warning("AutomaticBackup", "Could not persist automatic backup time", error)
                                                }
                                        }
                                        true
                                    }
                                } finally {
                                    if (destination == AutoBackupDestination.GOOGLE_DRIVE) {
                                        withContext(Dispatchers.IO + NonCancellable) { clearStagedArchives(directory) }
                                    }
                                }
                            }
                        val error = result.exceptionOrNull()
                        if (error is CancellationException) throw error
                        var retryImmediately = false
                        if (error is DriveReauthorizationRequiredException &&
                            DriveAccountConnectionState.requireReauthorization(checkNotNull(driveSession))
                        ) {
                            destination = AutoBackupDestination.LOCAL
                            pruningPending = true
                            retryImmediately = true
                        }
                        if (result.getOrNull() == true) {
                            if (destination == AutoBackupDestination.LOCAL) {
                                val pruneResult = withContext(Dispatchers.IO) { runCatching { pruneBackups(directory) } }
                                pruningPending = pruneResult.isFailure
                                if (pruningPending) {
                                    AppLogger.warning(
                                        "AutomaticBackup",
                                        "Could not prune automatic backups: ${pruneResult.exceptionOrNull()?.message ?: "Unknown error"}",
                                    )
                                }
                            } else {
                                withContext(Dispatchers.IO) { clearStagedArchives(directory) }
                            }
                        } else if (error != null) {
                            AppLogger.warning(
                                "AutomaticBackup",
                                "Automatic backup failed: ${error.message ?: "Unknown error"}",
                            )
                        }
                        if (!retryImmediately) delay(FAILURE_RETRY_MILLIS)
                    } else {
                        val nextDue = settings.autoBackupLastSuccessEpochMillis + frequency.intervalMillis
                        val untilNextBackup = (nextDue - now).coerceAtLeast(1L)
                        delay(minOf(untilNextBackup, FAILURE_RETRY_MILLIS))
                    }
                }
            }
    }

    fun listBackups(): List<File> =
        File(XdgUtils.getXdgDataHome(), "metrolist/automatic-backups")
            .listFiles { file -> file.isFile && file.extension == "zip" }
            .orEmpty()
            .sortedByDescending(File::lastModified)

    private fun pruneBackups(directory: File) =
        DesktopBackupRestoreManager.backupLock.withLock {
            directory
                .listFiles { file -> file.isFile && (file.extension == "tmp" || file.name.endsWith(".tmp")) }
                ?.forEach { it.delete() }
            directory
                .listFiles { file -> file.isFile && file.extension == "zip" }
                .orEmpty()
                .sortedByDescending(File::lastModified)
                .drop(MAX_RETAINED_BACKUPS)
                .forEach { check(it.delete()) { "Could not remove expired automatic backup" } }
        }

    private suspend fun uploadBackup(
        backupFile: File,
        brokerUrl: String,
        sessionToken: String,
    ) {
        val driveClient = GoogleDriveBackupClient(brokerUrl)
        try {
            if (Settings.shared.driveCleanupPending) {
                try {
                    driveClient.cleanupBackups(sessionToken)
                    DriveAccountConnectionState.clearCleanupFailure()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    AppLogger.warning("AutomaticBackup", "Could not retry Drive cleanup", error)
                    DriveAccountConnectionState.recordCleanupFailure()
                    error("Drive cleanup must succeed before uploading another backup")
                }
            }
            driveClient.uploadBackup(
                sessionToken,
                backupFile.name,
                backupFile.length(),
            ) { SystemFileSystem.source(Path(backupFile.absolutePath)) }
        } finally {
            driveClient.close()
        }
    }

    private fun clearStagedArchives(directory: File) {
        directory
            .listFiles { file -> file.isFile }
            .orEmpty()
            .forEach { file ->
                runCatching { check(file.delete()) { "Could not remove temporary backup archive" } }
                    .onFailure { error -> AppLogger.warning("AutomaticBackup", "Could not remove staged backup archive", error) }
            }
    }

    private const val BACKUP_FILENAME_PREFIX = "metrolist-backup"
    private const val CREDENTIAL_RECOVERY_POLL_MILLIS = 1_000L
    private const val FAILURE_RETRY_MILLIS = 5 * 60 * 1_000L
    private const val MAX_RETAINED_BACKUPS = 10
}
