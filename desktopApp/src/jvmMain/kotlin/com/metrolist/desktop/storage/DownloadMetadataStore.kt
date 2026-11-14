package com.metrolist.desktop.storage

import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal class DownloadMetadataStore(
    private val file: File,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private var entries: List<DownloadedFile>? = null

    fun load(): List<DownloadedFile> =
        entries ?: (
            if (Files.notExists(file.toPath())) emptyList() else json.decodeFromString<List<DownloadedFile>>(file.readText())
        ).also { entries = it }

    fun mutate(block: (MutableList<DownloadedFile>) -> Unit): List<DownloadedFile> {
        val next = load().toMutableList().also(block)
        val temp = Files.createTempFile(file.parentFile.toPath(), "downloads-", ".tmp")
        try {
            Files.writeString(temp, json.encodeToString(next))
            try {
                Files.move(temp, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp, file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            entries = next
            return next
        } finally {
            Files.deleteIfExists(temp)
        }
    }
}
