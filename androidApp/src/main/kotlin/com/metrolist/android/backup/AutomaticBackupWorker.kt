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
import com.metrolist.shared.backup.AutoBackupFrequency
import com.metrolist.shared.backup.defaultBackupFilename
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.settings.Settings
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

        val directory = File(applicationContext.filesDir, BACKUP_DIRECTORY)
        val destination = File(directory, "${defaultBackupFilename(BACKUP_FILENAME_PREFIX)}.zip")
        val backupResult =
            runCatching {
                check(directory.isDirectory || directory.mkdirs()) { "Could not create automatic backup directory" }
                BackupRestoreManager(applicationContext, GlobalContext.get().get<MusicDatabase>())
                    .backup(destination)
                    .getOrThrow()
            }
        if (backupResult.isFailure) return Result.retry()

        settings.autoBackupLastSuccessEpochMillis = System.currentTimeMillis()
        runCatching { settings.flush() }
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
        return Result.success()
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
