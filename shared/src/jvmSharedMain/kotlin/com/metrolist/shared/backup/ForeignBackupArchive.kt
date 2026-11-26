/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.backup

import androidx.sqlite.SQLiteDriver
import com.metrolist.shared.db.MusicDatabase
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.util.zip.ZipInputStream

private val SQLITE_HEADER = "SQLite format 3\u0000".encodeToByteArray()

private const val MAX_FOREIGN_ARCHIVE_ENTRIES = 4096
private const val IMPORT_STAGING_PREFIX = "import-"
private const val IMPORT_STAGING_SUFFIX = ".tmp"
private const val IMPORT_STAGING_LOCK = ".active"
private const val STALE_IMPORT_STAGING_AGE_MS = 24 * 60 * 60 * 1000L

suspend fun MusicDatabase.importForeignBackup(
    input: InputStream,
    stagingDirectory: File,
    driver: SQLiteDriver,
): ForeignImportSummary {
    val importStaging = createImportStagingDirectory(stagingDirectory)
    try {
        val library =
            stageForeignDatabases(input, importStaging.directory).firstNotNullOfOrNull { candidate ->
                driver.open(candidate.path).use { connection ->
                    connection.takeIf { it.detectForeignLibraryFamily() != null }?.readForeignLibrary()
                }
            } ?: throw UnsupportedForeignLibraryException()
        return importForeignLibrary(library.withPrismArchive(importStaging.directory))
    } finally {
        importStaging.close()
        importStaging.directory.deleteRecursively()
        if (stagingDirectory.list()?.isEmpty() == true) stagingDirectory.delete()
    }
}

private class ImportStagingDirectory(
    val directory: File,
    private val lockFile: RandomAccessFile,
    private val lock: FileLock,
) {
    fun close() {
        try {
            lock.release()
        } finally {
            lockFile.close()
        }
    }
}

private fun createImportStagingDirectory(stagingDirectory: File): ImportStagingDirectory {
    if (stagingDirectory.exists() && !stagingDirectory.isDirectory) {
        check(stagingDirectory.delete()) { "Could not create import staging directory" }
    }
    check(stagingDirectory.mkdirs() || stagingDirectory.isDirectory) { "Could not create import staging directory" }
    val parent = checkNotNull(stagingDirectory.absoluteFile.parentFile)
    cleanupStaleImportStagingDirectories(stagingDirectory)
    cleanupStaleLegacyStagingDirectories(stagingDirectory, parent)
    repeat(10) {
        val candidate = File.createTempFile(IMPORT_STAGING_PREFIX, IMPORT_STAGING_SUFFIX, stagingDirectory)
        check(candidate.delete()) { "Could not create import staging directory" }
        check(candidate.mkdir()) { "Could not create import staging directory" }
        val lockFile = RandomAccessFile(File(candidate, IMPORT_STAGING_LOCK), "rw")
        val lock = lockFile.channel.tryLock()
        if (lock != null) return ImportStagingDirectory(candidate, lockFile, lock)
        lockFile.close()
        candidate.deleteRecursively()
    }
    error("Could not create a unique import staging directory")
}

private fun cleanupStaleImportStagingDirectories(stagingDirectory: File) {
    val cutoff = System.currentTimeMillis() - STALE_IMPORT_STAGING_AGE_MS
    stagingDirectory.listFiles()?.forEach { candidate ->
        if (!candidate.name.startsWith(IMPORT_STAGING_PREFIX) || !candidate.name.endsWith(IMPORT_STAGING_SUFFIX)) return@forEach
        if (candidate.lastModified() >= cutoff) return@forEach
        if (!candidate.isDirectory) {
            candidate.delete()
            return@forEach
        }

        val lockFile = File(candidate, IMPORT_STAGING_LOCK)
        val lockHandle =
            try {
                RandomAccessFile(lockFile, "rw")
            } catch (_: IOException) {
                return@forEach
            }
        val lock =
            try {
                lockHandle.channel.tryLock()
            } catch (_: OverlappingFileLockException) {
                null
            } catch (_: IOException) {
                null
            }
        if (lock == null) {
            lockHandle.close()
            return@forEach
        }
        try {
            candidate.listFiles()?.filterNot { it == lockFile }?.forEach(File::deleteRecursively)
        } finally {
            lock.release()
            lockHandle.close()
        }
        lockFile.delete()
        candidate.delete()
    }
}

private fun cleanupStaleLegacyStagingDirectories(
    stagingDirectory: File,
    parent: File,
) {
    val cutoff = System.currentTimeMillis() - STALE_IMPORT_STAGING_AGE_MS
    val legacyPrefix = "${stagingDirectory.name}-"
    parent.listFiles()?.forEach { candidate ->
        if (!candidate.name.startsWith(legacyPrefix) || !candidate.name.endsWith(IMPORT_STAGING_SUFFIX)) return@forEach
        if (candidate.lastModified() < cutoff) candidate.deleteRecursively()
    }
    stagingDirectory.listFiles()?.forEach { candidate ->
        if (candidate.name.startsWith(IMPORT_STAGING_PREFIX) && candidate.name.endsWith(IMPORT_STAGING_SUFFIX)) return@forEach
        if (candidate.lastModified() < cutoff) candidate.deleteRecursively()
    }
}

internal fun stageForeignDatabases(
    input: InputStream,
    directory: File,
): List<File> {
    val buffered = BufferedInputStream(input)
    buffered.mark(SQLITE_HEADER.size)
    val header = buffered.readHeader()
    buffered.reset()
    if (header.contentEquals(SQLITE_HEADER)) {
        val target = File(directory, "source.db")
        target.outputStream().buffered().use { buffered.copyLimited(it, BackupArchiveLimits.DATABASE_BYTES) }
        return listOf(target)
    }
    val databases = mutableListOf<File>()
    var total = 0L
    var entries = 0
    ZipInputStream(buffered).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            check(++entries <= MAX_FOREIGN_ARCHIVE_ENTRIES) { "Backup contains too many entries" }
            val prismFile = prismArchiveFile(directory, entry.name)
            if (!entry.isDirectory && prismFile != null) {
                if (prismFile.exists()) throw UnsupportedForeignLibraryException()
                val parent = checkNotNull(prismFile.parentFile)
                check(parent.mkdirs() || parent.isDirectory)
                total += prismFile.outputStream().buffered().use { zip.copyLimited(it, BackupArchiveLimits.SETTINGS_BYTES) }
                check(total <= BackupArchiveLimits.TOTAL_UNCOMPRESSED_BYTES) { "Backup is too large" }
                continue
            }
            val name = entry.name.substringAfterLast('/').substringAfterLast('\\')
            val isSidecar = name.endsWith("-wal") || name.endsWith("-shm")
            val target = File(directory, name)
            if (entry.isDirectory || name.isBlank() || name.startsWith(".") || target.exists() || "exoplayer" in name.lowercase()) {
                total += zip.copyLimited(DiscardingOutputStream, BackupArchiveLimits.UNKNOWN_ENTRY_BYTES)
            } else {
                total += target.outputStream().buffered().use { zip.copyLimited(it, BackupArchiveLimits.DATABASE_BYTES) }
                val isDatabase = !isSidecar && target.inputStream().use { it.readHeader() }.contentEquals(SQLITE_HEADER)
                if (isDatabase) {
                    databases += target
                } else if (!isSidecar) {
                    target.delete()
                }
            }
            check(total <= BackupArchiveLimits.TOTAL_UNCOMPRESSED_BYTES) { "Backup is too large" }
        }
    }
    if (databases.isEmpty()) throw UnsupportedForeignLibraryException()
    return databases
}

private object DiscardingOutputStream : OutputStream() {
    override fun write(value: Int) = Unit

    override fun write(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ) = Unit
}

private fun InputStream.readHeader(): ByteArray {
    val header = ByteArray(SQLITE_HEADER.size)
    var read = 0
    while (read < header.size) {
        val count = read(header, read, header.size - read)
        if (count < 0) break
        read += count
    }
    return header.copyOf(read)
}

private fun InputStream.copyLimited(
    output: OutputStream,
    maxBytes: Long,
): Long {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    while (true) {
        val read = read(buffer)
        if (read < 0) return total
        total += read
        check(total <= maxBytes) { "Backup entry is too large" }
        output.write(buffer, 0, read)
    }
}
