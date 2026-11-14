package com.metrolist.shared.crash

import kotlinx.serialization.json.Json
import java.io.File
import java.io.RandomAccessFile

class FileCrashStore(
    private val directory: File,
) : CrashStore {
    private val json = Json { ignoreUnknownKeys = true }

    override fun save(report: CrashReport) =
        locked {
            require(report.id.matches(Regex("[0-9]+-[0-9]+")))
            val destination = File(directory, "${report.id}.json")
            val temporary = File(directory, "${report.id}.tmp")
            try {
                temporary.outputStream().use { stream ->
                    stream.write(json.encodeToString(report.redacted()).toByteArray())
                    stream.fd.sync()
                }
                check(temporary.renameTo(destination)) { "Could not publish crash report" }
                reports().drop(50).forEach { File(directory, "${it.id}.json").delete() }
                val marker = File(directory, "pending.tmp")
                marker.writeText(report.id)
                check(marker.renameTo(File(directory, "pending")))
            } finally {
                temporary.delete()
            }
            Unit
        }

    override fun list(): List<CrashReport> = locked { reports() }

    fun pendingReportId(): String? =
        locked {
            val marker = File(directory, "pending")
            if (!marker.isFile) null else marker.readText().takeIf { id -> reports().any { it.id == id } }
        }

    fun acknowledge(id: String) =
        locked {
            val marker = File(directory, "pending")
            if (marker.isFile && marker.readText() == id) check(marker.delete())
        }

    override fun clear() =
        locked {
            directory
                .listFiles()
                .orEmpty()
                .filter { it.extension == "json" || it.extension == "tmp" || it.name == "pending" }
                .forEach { check(it.delete()) }
        }

    private fun reports(): List<CrashReport> =
        directory
            .listFiles()
            .orEmpty()
            .filter { it.extension == "json" }
            .mapNotNull {
                runCatching { json.decodeFromString<CrashReport>(it.readText()).redacted() }.getOrNull()
            }.sortedWith(compareByDescending<CrashReport> { it.timestamp }.thenByDescending { it.id })

    private fun <T> locked(block: () -> T): T =
        synchronized(lock) {
            check(directory.isDirectory || directory.mkdirs())
            RandomAccessFile(File(directory, ".lock"), "rw").use { file -> file.channel.lock().use { block() } }
        }

    private companion object {
        val lock = Any()
    }
}
