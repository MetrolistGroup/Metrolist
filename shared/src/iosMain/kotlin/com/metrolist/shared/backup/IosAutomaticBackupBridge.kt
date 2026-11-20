package com.metrolist.shared.backup

import com.metrolist.shared.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import platform.Foundation.NSDate
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSUserDomainMask

private const val AUTOMATIC_BACKUP_SETTINGS_CHANGED = "com.metrolist.automaticBackupSettingsChanged"
private const val BACKUP_FILENAME_PREFIX = "metrolist-backup"
private const val MAX_RETAINED_BACKUPS = 10
private var activeAutomaticBackup: Job? = null

fun iosAutomaticBackupEnabled(): Boolean = Settings.shared.autoBackupEnabled

fun iosAutomaticBackupIntervalMillis(): Long =
    AutoBackupFrequency.fromSetting(Settings.shared.autoBackupFrequency).intervalMillis

fun iosAutomaticBackupLastSuccessEpochMillis(): Long = Settings.shared.autoBackupLastSuccessEpochMillis

fun notifyIosAutomaticBackupSettingsChanged() {
    NSNotificationCenter.defaultCenter.postNotificationName(AUTOMATIC_BACKUP_SETTINGS_CHANGED, `object` = null)
}

fun createIosAutomaticBackup(completion: (Boolean) -> Unit) {
    activeAutomaticBackup?.cancel()
    val backupJob =
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch(start = CoroutineStart.LAZY) {
            try {
                if (!Settings.shared.autoBackupEnabled) {
                    completion(false)
                    return@launch
                }
                val fileManager = NSFileManager.defaultManager
                val documentsPath = fileManager.URLsForDirectory(NSDocumentDirectory, NSUserDomainMask).firstOrNull()?.path
                val backupDirectory = documentsPath?.plus("/Metrolist/Automatic Backups")
                val destination = backupDirectory?.plus("/${defaultBackupFilename(BACKUP_FILENAME_PREFIX)}.zip")
                val succeeded =
                    runCatching {
                        check(
                            backupDirectory != null &&
                                fileManager.createDirectoryAtPath(
                                    backupDirectory,
                                    withIntermediateDirectories = true,
                                    attributes = null,
                                    error = null,
                                ),
                        )
                        check(
                            NSURL.fileURLWithPath(backupDirectory).setResourceValue(
                                true,
                                NSURLIsExcludedFromBackupKey,
                                error = null,
                            ),
                        )
                        check(destination != null)
                        IosBackupRestoreManager().backup(destination).getOrThrow()
                        Settings.shared.autoBackupLastSuccessEpochMillis =
                            (NSDate().timeIntervalSince1970 * 1000).toLong()
                        Settings.shared.flush()
                        val files = fileManager.contentsOfDirectoryAtPath(backupDirectory, error = null).orEmpty()
                        files
                            .mapNotNull { (it as? String)?.let { filename ->
                                val path = "$backupDirectory/$filename"
                                val modifiedAt =
                                    (fileManager.attributesOfItemAtPath(path, error = null)?.get(NSFileModificationDate) as? NSDate)
                                        ?.timeIntervalSince1970 ?: 0.0
                                filename to modifiedAt
                            } }
                            .sortedByDescending { it.second }
                            .drop(MAX_RETAINED_BACKUPS)
                            .forEach { (filename, _) -> fileManager.removeItemAtPath("$backupDirectory/$filename", error = null) }
                        true
                    }.getOrDefault(false)
                completion(succeeded)
            } finally {
                if (activeAutomaticBackup === currentCoroutineContext()[Job]) activeAutomaticBackup = null
            }
        }
    activeAutomaticBackup = backupJob
    backupJob.start()
}

fun cancelIosAutomaticBackup() {
    activeAutomaticBackup?.cancel()
}
