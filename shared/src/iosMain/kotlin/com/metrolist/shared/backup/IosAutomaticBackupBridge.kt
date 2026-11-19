package com.metrolist.shared.backup

import com.metrolist.shared.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import platform.Foundation.NSDate
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSUserDomainMask

private const val AUTOMATIC_BACKUP_SETTINGS_CHANGED = "com.metrolist.automaticBackupSettingsChanged"
private const val BACKUP_FILENAME_PREFIX = "metrolist-backup"
private const val MAX_RETAINED_BACKUPS = 10
private var activeAutomaticBackup: Job? = null

fun notifyIosAutomaticBackupSettingsChanged() {
    NSNotificationCenter.defaultCenter.postNotificationName(AUTOMATIC_BACKUP_SETTINGS_CHANGED, `object` = null)
}

fun createIosAutomaticBackup(completion: (Boolean) -> Unit) {
    activeAutomaticBackup?.cancel()
    activeAutomaticBackup =
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            if (!Settings.shared.autoBackupEnabled) {
                activeAutomaticBackup = null
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
                    check(NSURL.fileURLWithPath(backupDirectory).setResourceValue(true, NSURLIsExcludedFromBackupKey, error = null))
                    check(destination != null)
                    IosBackupRestoreManager().backup(destination).getOrThrow()
                    Settings.shared.autoBackupLastSuccessEpochMillis = (NSDate().timeIntervalSince1970 * 1000).toLong()
                    Settings.shared.flush()
                    val files = fileManager.contentsOfDirectoryAtPath(backupDirectory, error = null).orEmpty()
                    files
                        .sortedByDescending { it as String }
                        .drop(MAX_RETAINED_BACKUPS)
                        .forEach { fileManager.removeItemAtPath("$backupDirectory/$it", error = null) }
                    true
                }.getOrDefault(false)
            activeAutomaticBackup = null
            completion(succeeded)
        }
}

fun cancelIosAutomaticBackup() {
    activeAutomaticBackup?.cancel()
}
