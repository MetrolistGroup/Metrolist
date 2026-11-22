@file:OptIn(ExperimentalForeignApi::class)

package com.metrolist.shared.backup

import com.metrolist.shared.settings.Settings
import com.metrolist.shared.utils.Logger
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import platform.Foundation.NSBundle
import platform.Foundation.NSCachesDirectory
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
                val settings = Settings.shared
                val destinationType = AutoBackupDestination.fromSetting(settings.autoBackupDestination)
                val brokerUrl = if (destinationType == AutoBackupDestination.GOOGLE_DRIVE) iosDriveBrokerUrl() else null
                val session =
                    if (destinationType == AutoBackupDestination.GOOGLE_DRIVE) {
                        settings.driveBrokerSession.takeIf(String::isNotBlank)
                    } else {
                        null
                    }
                val backupBasePath =
                    when (destinationType) {
                        AutoBackupDestination.LOCAL -> {
                            (fileManager.URLsForDirectory(NSDocumentDirectory, NSUserDomainMask).firstOrNull() as? NSURL)?.path
                        }

                        AutoBackupDestination.GOOGLE_DRIVE -> {
                            (fileManager.URLsForDirectory(NSCachesDirectory, NSUserDomainMask).firstOrNull() as? NSURL)?.path
                        }
                    }
                val backupDirectory =
                    backupBasePath?.plus(
                        if (destinationType == AutoBackupDestination.LOCAL) {
                            "/Metrolist/Automatic Backups"
                        } else {
                            "/Metrolist/Automatic Backup Upload"
                        },
                    )
                val destination = backupDirectory?.plus("/${defaultBackupFilename(BACKUP_FILENAME_PREFIX)}.zip")
                val result =
                    runCatching {
                        check(destinationType != AutoBackupDestination.GOOGLE_DRIVE || (brokerUrl != null && session != null))
                        check(
                            backupDirectory != null &&
                                fileManager.createDirectoryAtPath(
                                    backupDirectory,
                                    withIntermediateDirectories = true,
                                    attributes = null,
                                    error = null,
                                ),
                        )
                        if (destinationType == AutoBackupDestination.LOCAL) {
                            check(NSURL.fileURLWithPath(backupDirectory).setResourceValue(true, NSURLIsExcludedFromBackupKey, error = null))
                        }
                        check(destination != null)
                        if (destinationType == AutoBackupDestination.GOOGLE_DRIVE) {
                            retryDriveCleanup(checkNotNull(brokerUrl), checkNotNull(session))
                        }
                        val isDue =
                            isAutoBackupDue(
                                settings.autoBackupLastSuccessEpochMillis,
                                (NSDate().timeIntervalSince1970 * 1000).toLong(),
                                AutoBackupFrequency.fromSetting(settings.autoBackupFrequency),
                            )
                        if (isDue) {
                            when (destinationType) {
                                AutoBackupDestination.LOCAL -> {
                                    IosBackupRestoreManager().backup(destination).getOrThrow()
                                }

                                AutoBackupDestination.GOOGLE_DRIVE -> {
                                    val activeSession = settings.driveBrokerSession
                                    check(activeSession == session && activeSession.isNotBlank())
                                    try {
                                        IosBackupRestoreManager().backup(destination).getOrThrow()
                                        if (!settings.autoBackupEnabled ||
                                            AutoBackupDestination.fromSetting(settings.autoBackupDestination) != destinationType
                                        ) {
                                            return@runCatching false
                                        }
                                        val archivePath = Path(destination)
                                        val archiveSize = checkNotNull(SystemFileSystem.metadataOrNull(archivePath)).size
                                        val driveClient = GoogleDriveBackupClient(checkNotNull(brokerUrl))
                                        try {
                                            val uploadError =
                                                runCatching {
                                                    driveClient.uploadBackup(
                                                        activeSession,
                                                        destination.substringAfterLast('/'),
                                                        archiveSize,
                                                    ) { SystemFileSystem.source(archivePath) }
                                                }.exceptionOrNull()
                                            if (uploadError is CancellationException) throw uploadError
                                            if (uploadError is DriveCleanupException) {
                                                DriveAccountConnectionState.recordCleanupFailure()
                                            } else if (uploadError != null) {
                                                throw uploadError
                                            }
                                        } finally {
                                            driveClient.close()
                                        }
                                    } finally {
                                        if (fileManager.fileExistsAtPath(destination)) {
                                            runCatching { check(fileManager.removeItemAtPath(destination, error = null)) }
                                                .onFailure { error ->
                                                    Logger.w("AutomaticBackup", "Could not remove staged backup archive", error)
                                                }
                                        }
                                    }
                                }
                            }
                            settings.autoBackupLastSuccessEpochMillis = (NSDate().timeIntervalSince1970 * 1000).toLong()
                            settings.flush()
                        }
                        cleanupLocalBackups(fileManager)
                        if (destinationType == AutoBackupDestination.GOOGLE_DRIVE) {
                            val entries =
                                fileManager
                                    .contentsOfDirectoryAtPath(backupDirectory, error = null)
                                    .orEmpty()
                                    .filterIsInstance<String>()
                            entries.forEach { filename ->
                                fileManager.removeItemAtPath("$backupDirectory/$filename", error = null)
                            }
                        }
                        true
                    }
                if (result.exceptionOrNull() is DriveReauthorizationRequiredException) {
                    DriveAccountConnectionState.requireReauthorization(checkNotNull(session))
                }
                completion(result.getOrDefault(false))
            } finally {
                if (activeAutomaticBackup === currentCoroutineContext()[Job]) activeAutomaticBackup = null
            }
        }
    activeAutomaticBackup = backupJob
    backupJob.start()
}

private suspend fun cleanupLocalBackups(fileManager: NSFileManager) {
    val directory = automaticBackupDirectory() ?: return
    iosBackupOperationMutex.withLock {
        val entries =
            fileManager
                .contentsOfDirectoryAtPath(directory, error = null)
                .orEmpty()
                .filterIsInstance<String>()
        entries
            .filter { it.endsWith(".tmp") }
            .forEach { filename -> check(fileManager.removeItemAtPath("$directory/$filename", error = null)) }
        entries
            .map { filename ->
                val path = "$directory/$filename"
                val modifiedAt =
                    (fileManager.attributesOfItemAtPath(path, error = null)?.get(NSFileModificationDate) as? NSDate)
                        ?.timeIntervalSince1970 ?: 0.0
                filename to modifiedAt
            }.filter { it.first.endsWith(".zip") }
            .sortedByDescending { it.second }
            .drop(MAX_RETAINED_BACKUPS)
            .forEach { (filename, _) ->
                check(fileManager.removeItemAtPath("$directory/$filename", error = null))
            }
    }
}

private suspend fun retryDriveCleanup(
    brokerUrl: String,
    session: String,
) {
    if (!Settings.shared.driveCleanupPending) return
    val client = GoogleDriveBackupClient(brokerUrl)
    try {
        client.cleanupBackups(session)
        DriveAccountConnectionState.clearCleanupFailure()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Logger.w("AutomaticBackup", "Could not retry Drive cleanup", error)
        DriveAccountConnectionState.recordCleanupFailure()
    } finally {
        client.close()
    }
}

internal fun iosDriveBrokerUrl(): String? =
    (NSBundle.mainBundle.infoDictionary?.get("DriveBrokerUrl") as? String)
        ?.takeIf { it.startsWith("https://") }

fun cancelIosAutomaticBackup() {
    activeAutomaticBackup?.cancel()
}
