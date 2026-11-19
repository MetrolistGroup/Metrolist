package com.metrolist.android.backup

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.metrolist.shared.backup.AutoBackupFrequency
import com.metrolist.shared.backup.defaultBackupFilename
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.settings.Settings
import org.koin.core.context.GlobalContext
import java.io.File
import java.util.concurrent.TimeUnit

class AutomaticBackupWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val settings = Settings.shared
        if (!settings.autoBackupEnabled) return Result.success()

        val directory = File(applicationContext.filesDir, BACKUP_DIRECTORY)
        val destination = File(directory, "${defaultBackupFilename(BACKUP_FILENAME_PREFIX)}.zip")
        val result =
            runCatching {
                check(directory.isDirectory || directory.mkdirs()) { "Could not create automatic backup directory" }
                BackupRestoreManager(applicationContext, GlobalContext.get().get<MusicDatabase>())
                    .backup(destination)
                    .getOrThrow()
                pruneBackups(directory)
                settings.autoBackupLastSuccessEpochMillis = System.currentTimeMillis()
                settings.flush()
            }
        return result.fold({ Result.success() }, { Result.retry() })
    }

    private fun pruneBackups(directory: File) {
        directory
            .listFiles { file -> file.isFile && file.extension == "zip" }
            .orEmpty()
            .sortedByDescending(File::lastModified)
            .drop(MAX_RETAINED_BACKUPS)
            .forEach { check(it.delete()) { "Could not remove expired automatic backup" } }
    }

    companion object {
        private const val BACKUP_DIRECTORY = "automatic-backups"
        private const val BACKUP_FILENAME_PREFIX = "metrolist-backup"
        private const val MAX_RETAINED_BACKUPS = 10
        private const val WORK_NAME = "automatic-backup"

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
