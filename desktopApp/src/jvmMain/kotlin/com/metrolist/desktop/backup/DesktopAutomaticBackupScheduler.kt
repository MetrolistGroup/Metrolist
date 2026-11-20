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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

object DesktopAutomaticBackupScheduler {
    private var scope: CoroutineScope? = null
    private var job: Job? = null

    fun start(applicationScope: CoroutineScope) {
        scope = applicationScope
        schedule()
    }

    fun schedule() {
        val activeScope = scope ?: return
        job?.cancel()
        val settings = Settings.shared
        if (!settings.autoBackupEnabled) return

        val frequency = AutoBackupFrequency.fromSetting(settings.autoBackupFrequency)
        job =
            activeScope.launch {
                while (true) {
                    if (settings.credentialsPending) {
                        delay(CREDENTIAL_RECOVERY_POLL_MILLIS)
                        continue
                    }
                    val now = System.currentTimeMillis()
                    if (isAutoBackupDue(settings.autoBackupLastSuccessEpochMillis, now, frequency)) {
                        val directory = File(XdgUtils.getXdgDataHome(), "metrolist/automatic-backups")
                        val destination = File(directory, "${defaultBackupFilename(BACKUP_FILENAME_PREFIX)}.zip")
                        val result =
                            withContext(Dispatchers.IO) {
                                runCatching {
                                    DesktopBackupRestoreManager.backup(destination).getOrThrow()
                                    pruneBackups(directory)
                                }
                            }
                        if (result.isSuccess) {
                            settings.autoBackupLastSuccessEpochMillis = System.currentTimeMillis()
                            runCatching { settings.flush() }
                                .onFailure { error ->
                                    AppLogger.warning(
                                        "AutomaticBackup",
                                        "Could not persist automatic backup time: ${error.message ?: "Unknown error"}",
                                    )
                                }
                        } else {
                            AppLogger.warning(
                                "AutomaticBackup",
                                "Automatic backup failed: ${result.exceptionOrNull()?.message ?: "Unknown error"}",
                            )
                        }
                        delay(if (result.isSuccess) frequency.intervalMillis else FAILURE_RETRY_MILLIS)
                    } else {
                        val nextDue = settings.autoBackupLastSuccessEpochMillis + frequency.intervalMillis
                        delay((nextDue - now).coerceAtLeast(1L))
                    }
                }
            }
    }

    private fun pruneBackups(directory: File) {
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
