package com.metrolist.desktop.backup

import com.metrolist.desktop.utils.AppLogger
import com.metrolist.shared.backup.AutoBackupFrequency
import com.metrolist.shared.backup.defaultBackupFilename
import com.metrolist.shared.backup.isAutoBackupDue
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.utils.XdgUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
        job =
            activeScope.launch {
                previousJob?.join()
                while (true) {
                    if (settings.credentialsPending) {
                        delay(CREDENTIAL_RECOVERY_POLL_MILLIS)
                        continue
                    }
                    if (pruningPending) {
                        val directory = File(XdgUtils.getXdgDataHome(), "metrolist/automatic-backups")
                        val pruneResult = withContext(Dispatchers.IO) { runCatching { pruneBackups(directory) } }
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
                        val directory = File(XdgUtils.getXdgDataHome(), "metrolist/automatic-backups")
                        val destination = File(directory, "${defaultBackupFilename(BACKUP_FILENAME_PREFIX)}.zip")
                        val result =
                            withContext(Dispatchers.IO + NonCancellable) {
                                DesktopBackupRestoreManager.backup(destination).onSuccess {
                                    settings.autoBackupLastSuccessEpochMillis = System.currentTimeMillis()
                                    pruningPending = true
                                    runCatching { settings.flush() }
                                        .onFailure { error ->
                                            AppLogger.warning("AutomaticBackup", "Could not persist automatic backup time", error)
                                        }
                                }
                            }
                        if (result.isSuccess) {
                            val pruneResult = withContext(Dispatchers.IO) { runCatching { pruneBackups(directory) } }
                            pruningPending = pruneResult.isFailure
                            if (pruningPending) {
                                AppLogger.warning(
                                    "AutomaticBackup",
                                    "Could not prune automatic backups: ${pruneResult.exceptionOrNull()?.message ?: "Unknown error"}",
                                )
                            }
                        } else {
                            AppLogger.warning(
                                "AutomaticBackup",
                                "Automatic backup failed: ${result.exceptionOrNull()?.message ?: "Unknown error"}",
                            )
                        }
                        delay(FAILURE_RETRY_MILLIS)
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

    private const val BACKUP_FILENAME_PREFIX = "metrolist-backup"
    private const val CREDENTIAL_RECOVERY_POLL_MILLIS = 1_000L
    private const val FAILURE_RETRY_MILLIS = 5 * 60 * 1_000L
    private const val MAX_RETAINED_BACKUPS = 10
}
