package com.metrolist.android.backup

import java.io.File

internal class PendingRestore(
    private val directory: File,
) {
    val request: File get() = File(directory, "request.json")
    private val stagedDatabase get() = File(directory, "database")
    private val rollbackDirectory get() = File(directory, "rollback")
    private val installing get() = File(directory, "installing")
    private val committed get() = File(directory, "committed")

    fun stage(
        database: File?,
        settings: String,
    ) {
        check(!request.exists()) { "A restore is already pending" }
        check(directory.deleteRecursively()) { "Could not clear completed restore" }
        val staging = File(directory.path + ".staging")
        staging.deleteRecursively()
        check(staging.mkdirs()) { "Could not stage restore" }
        database?.copyDurablyTo(File(staging, "database"))
        File(staging, "request.json").writeDurably(settings)
        check(staging.renameTo(directory)) { "Could not prepare restore" }
    }

    fun install(
        currentDatabase: File,
        exportSettings: () -> String,
        restoreSettings: (String) -> Unit,
        applyRestore: () -> Unit,
    ) {
        if (!request.exists()) return
        if (committed.exists()) {
            clearCompletedRestore()
            return
        }
        val databaseFiles = listOf(currentDatabase, File(currentDatabase.path + "-wal"), File(currentDatabase.path + "-shm"))

        fun rollback() {
            if (stagedDatabase.exists()) {
                databaseFiles.forEach { current ->
                    val backup = File(rollbackDirectory, current.name)
                    if (backup.exists()) backup.copyDurablyTo(current) else check(!current.exists() || current.delete())
                }
            }
            restoreSettings(File(rollbackDirectory, "settings.json").readText())
        }
        if (installing.exists()) {
            rollback()
            check(installing.delete()) { "Could not complete restore rollback" }
        }
        rollbackDirectory.deleteRecursively()
        check(rollbackDirectory.mkdirs()) { "Could not preserve library" }
        if (stagedDatabase.exists()) {
            databaseFiles.filter(File::exists).forEach { it.copyDurablyTo(File(rollbackDirectory, it.name)) }
        }
        File(rollbackDirectory, "settings.json").writeDurably(exportSettings())
        installing.writeDurably("")
        try {
            if (stagedDatabase.exists()) {
                databaseFiles.forEach { check(!it.exists() || it.delete()) { "Could not replace database" } }
                stagedDatabase.copyDurablyTo(currentDatabase)
            }
            applyRestore()
            committed.writeDurably("")
        } catch (error: Throwable) {
            try {
                rollback()
            } catch (rollbackError: Throwable) {
                error.addSuppressed(rollbackError)
                throw error
            }
            clearCompletedRestore()
            throw error
        }
        clearCompletedRestore()
    }

    private fun clearCompletedRestore() {
        check(request.delete()) { "Could not complete restore" }
        directory.deleteRecursively()
    }
}

private fun File.copyDurablyTo(destination: File) {
    inputStream().use { input ->
        destination.outputStream().use { output ->
            input.copyTo(output)
            output.fd.sync()
        }
    }
}

private fun File.writeDurably(value: String) {
    outputStream().use { output ->
        output.write(value.toByteArray())
        output.fd.sync()
    }
}
