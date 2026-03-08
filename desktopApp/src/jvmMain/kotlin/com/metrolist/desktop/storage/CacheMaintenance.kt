package com.metrolist.desktop.storage

import java.io.File

internal fun cacheEntriesByLastAccess(entries: List<CachedSongEntry>): List<CachedSongEntry> = entries.sortedBy { it.lastAccessedAt }

internal fun reconcileCacheDirectory(
    directory: File,
    entries: List<CachedSongEntry>,
) {
    val referenced = entries.flatMap { listOfNotNull(it.filePath, it.thumbnailPath) }.map { File(it).canonicalPath }.toSet()
    val managedExtensions = setOf("audio", "webm", "mp3", "m4a", "ogg", "flac", "mp4", "jpg", "png", "webp", "avif", "tmp")
    directory.canonicalFile.listFiles()?.forEach { file ->
        if (file.isFile && file.extension in managedExtensions && file.absolutePath !in referenced) {
            file.delete()
        }
    }
}
