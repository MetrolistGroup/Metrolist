package com.metrolist.android.backup

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.metrolist.android.BuildConfig
import com.metrolist.shared.backup.AutoBackupDestination
import com.metrolist.shared.backup.AutoBackupFrequency
import com.metrolist.shared.backup.DriveAccountConnectionState
import com.metrolist.shared.backup.DriveCleanupException
import com.metrolist.shared.backup.DriveReauthorizationRequiredException
import com.metrolist.shared.backup.GoogleDriveBackupClient
import com.metrolist.shared.backup.defaultBackupFilename
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.utils.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import org.koin.core.context.GlobalContext
import java.io.File
import java.util.concurrent.TimeUnit

class AutomaticBackupWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = backupMutex.withLock { doWorkLocked() }

    private suspend fun doWorkLocked(): Result {
        if (inputData.getBoolean(PRUNE_ONLY_KEY, false)) {
            return runCatching { pruneBackups(File(applicationContext.filesDir, BACKUP_DIRECTORY)) }
                .fold({ Result.success() }, { Result.retry() })
        }
        val settings = Settings.shared
        if (!settings.autoBackupEnabled) return Result.success()

        val backupDestination = AutoBackupDestination.fromSetting(settings.autoBackupDestination)
        val brokerUrl = BuildConfig.DRIVE_BROKER_URL.takeIf(String::isNotBlank)
        val session = settings.driveBrokerSession
        if (backupDestination == AutoBackupDestination.GOOGLE_DRIVE) {
            check(brokerUrl != null) { "Google Drive broker is not configured" }
            check(session.isNotBlank()) { "Google Drive account is not connected" }
        }
        val directory =
            when (backupDestination) {
                AutoBackupDestination.LOCAL -> File(applicationContext.filesDir, BACKUP_DIRECTORY)
                AutoBackupDestination.GOOGLE_DRIVE -> File(applicationContext.cacheDir, BACKUP_DIRECTORY)
            }
        val destination = File(directory, "${defaultBackupFilename(BACKUP_FILENAME_PREFIX)}.zip")
        var archiveCreated = false
        return try {
            check(directory.isDirectory || directory.mkdirs()) { "Could not create automatic backup directory" }
            if (backupDestination == AutoBackupDestination.GOOGLE_DRIVE) clearStagedArchives(directory)
            archiveCreated = true
            BackupRestoreManager(applicationContext, GlobalContext.get().get<MusicDatabase>())
                .backup(destination)
                .getOrThrow()
            if (backupDestination == AutoBackupDestination.GOOGLE_DRIVE) {
                if (AutoBackupDestination.fromSetting(settings.autoBackupDestination) != AutoBackupDestination.GOOGLE_DRIVE) {
                    return Result.success()
                }
                try {
                    uploadArchive(destination, checkNotNull(brokerUrl), session)
                } catch (cleanup: DriveCleanupException) {
                    DriveAccountConnectionState.recordCleanupFailure()
                }
            }
            settings.autoBackupLastSuccessEpochMillis = System.currentTimeMillis()
            runCatching { settings.flush() }
            if (backupDestination == AutoBackupDestination.LOCAL) {
                val pruneResult = runCatching { pruneBackups(directory) }
                if (pruneResult.isFailure) {
                    WorkManager
                        .getInstance(applicationContext)
                        .enqueueUniqueWork(
                            PRUNE_WORK_NAME,
                            ExistingWorkPolicy.KEEP,
                            OneTimeWorkRequestBuilder<AutomaticBackupWorker>()
                                .setInputData(workDataOf(PRUNE_ONLY_KEY to true))
                                .build(),
                        )
                }
            } else {
                clearStagedArchives(directory)
            }
            Result.success()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (reauthorization: DriveReauthorizationRequiredException) {
            DriveAccountConnectionState.requireReauthorization(session)
            Result.failure()
        } catch (_: Exception) {
            Result.retry()
        } finally {
            if (archiveCreated && backupDestination == AutoBackupDestination.GOOGLE_DRIVE) {
                clearStagedArchives(directory)
            }
        }
    }

    private suspend fun uploadArchive(
        destination: File,
        brokerUrl: String,
        session: String,
    ) {
        val driveClient = GoogleDriveBackupClient(brokerUrl)
        try {
            if (Settings.shared.driveCleanupPending) {
                try {
                    driveClient.cleanupBackups(session)
                    DriveAccountConnectionState.clearCleanupFailure()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Logger.w("AutomaticBackup", "Could not retry Drive cleanup", error)
                    DriveAccountConnectionState.recordCleanupFailure()
                }
            }
            driveClient.uploadBackup(
                session,
                destination.name,
                destination.length(),
            ) { SystemFileSystem.source(Path(destination.absolutePath)) }
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
                    .onFailure { error -> Logger.w("AutomaticBackup", "Could not remove staged backup archive", error) }
            }
    }

    private fun pruneBackups(directory: File) {
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

    companion object {
        private val backupMutex = Mutex()
        private const val BACKUP_DIRECTORY = "automatic-backups"
        private const val BACKUP_FILENAME_PREFIX = "metrolist-backup"
        private const val MAX_RETAINED_BACKUPS = 10
        private const val WORK_NAME = "automatic-backup"
        private const val PRUNE_WORK_NAME = "automatic-backup-prune"
        private const val PRUNE_ONLY_KEY = "pruneOnly"

        fun schedule(
            context: Context,
            enabled: Boolean,
            frequency: AutoBackupFrequency,
        ) {
            val workManager = WorkManager.getInstance(context)
            if (!enabled) {
                workManager.cancelUniqueWork(WORK_NAME)
                return
            }
            val request =
                PeriodicWorkRequestBuilder<AutomaticBackupWorker>(frequency.intervalMillis, TimeUnit.MILLISECONDS)
                    .build()
            workManager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}
