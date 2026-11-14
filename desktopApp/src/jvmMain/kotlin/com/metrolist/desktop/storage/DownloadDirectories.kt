package com.metrolist.desktop.storage

import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal class DownloadDirectories(
    private val defaultDirectory: File,
    private val file: File,
) {
    private var approved: Set<String>? = null

    private fun approvedDirectories(): Set<String> =
        approved ?: (if (Files.notExists(file.toPath())) emptySet() else Json.decodeFromString<Set<String>>(file.readText()))
            .also { approved = it }

    @Synchronized
    fun rememberChange(
        current: File,
        destination: File,
    ) {
        check(destination.mkdirs() || destination.isDirectory) { "Download directory is unavailable" }
        val probe = Files.createTempFile(destination.toPath(), ".metrolist-", ".tmp")
        Files.delete(probe)
        val directories = approvedDirectories() + current.canonicalPath + destination.canonicalPath
        val temporary = Files.createTempFile(file.parentFile.toPath(), "download-directories-", ".tmp")
        try {
            Files.writeString(temporary, Json.encodeToString(directories))
            try {
                Files.move(temporary, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            approved = directories
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    @Synchronized
    fun safeFile(
        path: String,
        current: File,
    ): File? =
        runCatching {
            val candidate = File(path).canonicalFile
            if (!candidate.isFile) return@runCatching null
            val currentRoots = listOf(defaultDirectory.canonicalFile, current.canonicalFile)
            if (currentRoots.any { candidate.toPath().startsWith(it.toPath()) }) return@runCatching candidate
            candidate.takeIf { approvedDirectories().any { root -> it.toPath().startsWith(File(root).toPath()) } }
        }.getOrNull()
}
