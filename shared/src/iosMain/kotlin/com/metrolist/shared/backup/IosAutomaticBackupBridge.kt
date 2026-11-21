@file:OptIn(ExperimentalForeignApi::class)

package com.metrolist.shared.backup

import com.metrolist.shared.settings.Settings
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import platform.Foundation.NSDate
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSUserDomainMask
import platform.Foundation.timeIntervalSince1970

private const val AUTOMATIC_BACKUP_SETTINGS_CHANGED = "com.metrolist.automaticBackupSettingsChanged"
private const val BACKUP_FILENAME_PREFIX = "metrolist-backup"
private const val MAX_RETAINED_BACKUPS = 10
private var activeAutomaticBackup: Job? = null

private fun automaticBackupDirectory(): String? =
    (NSFileManager.defaultManager.URLsForDirectory(NSDocumentDirectory, NSUserDomainMask).firstOrNull() as? NSURL)
        ?.path
        ?.plus("/Metrolist/Automatic Backups")

fun iosAutomaticBackupNeedsCleanup(): Boolean {
    val directory = automaticBackupDirectory() ?: return false
    val entries =
        NSFileManager.defaultManager
            .contentsOfDirectoryAtPath(directory, error = null)
            .orEmpty()
            .filterIsInstance<String>()
    return entries.any { it.endsWith(".tmp") } || entries.count { it.endsWith(".zip") } > MAX_RETAINED_BACKUPS
}

fun iosAutomaticBackupEnabled(): Boolean = Settings.shared.autoBackupEnabled

fun iosAutomaticBackupIntervalMillis(): Long = AutoBackupFrequency.fromSetting(Settings.shared.autoBackupFrequency).intervalMillis

fun iosAutomaticBackupLastSuccessEpochMillis(): Long = Settings.shared.autoBackupLastSuccessEpochMillis

fun notifyIosAutomaticBackupSettingsChanged() {
    NSNotificationCenter.defaultCenter.postNotificationName(AUTOMATIC_BACKUP_SETTINGS_CHANGED, `object` = null)
}

fun createIosAutomaticBackup(completion: (Boolean) -> Unit) {
    if (activeAutomaticBackup != null) {
        completion(false)
        return
    }
    val backupJob =
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch(start = CoroutineStart.LAZY) {
            try {
                if (!Settings.shared.autoBackupEnabled) {
                    completion(false)
                    return@launch
                }
                val fileManager = NSFileManager.defaultManager
                val backupDirectory = automaticBackupDirectory()
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
                        if (isAutoBackupDue(
                                Settings.shared.autoBackupLastSuccessEpochMillis,
                                (NSDate().timeIntervalSince1970 * 1000).toLong(),
                                AutoBackupFrequency.fromSetting(Settings.shared.autoBackupFrequency),
                            )
                        ) {
                            IosBackupRestoreManager().backup(destination).getOrThrow()
                            Settings.shared.autoBackupLastSuccessEpochMillis = (NSDate().timeIntervalSince1970 * 1000).toLong()
                            Settings.shared.flush()
                        }
                        iosBackupOperationMutex.withLock {
                            val entries =
                                fileManager
                                    .contentsOfDirectoryAtPath(backupDirectory, error = null)
                                    .orEmpty()
                                    .filterIsInstance<String>()
                            entries
                                .filter { it.endsWith(".tmp") }
                                .forEach { filename -> check(fileManager.removeItemAtPath("$backupDirectory/$filename", error = null)) }
                            val files = entries.filter { it.endsWith(".zip") }
                            files
                                .map { filename ->
                                    val path = "$backupDirectory/$filename"
                                    val modifiedAt =
                                        (fileManager.attributesOfItemAtPath(path, error = null)?.get(NSFileModificationDate) as? NSDate)
                                            ?.timeIntervalSince1970 ?: 0.0
                                    filename to modifiedAt
                                }.sortedByDescending { it.second }
                                .drop(MAX_RETAINED_BACKUPS)
                                .forEach { (filename, _) ->
                                    check(fileManager.removeItemAtPath("$backupDirectory/$filename", error = null))
                                }
                        }
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
