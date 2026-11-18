package com.metrolist.android.playback

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.AtomicFile
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.ContentMetadata
import com.metrolist.shared.playback.SeekableMediaSource
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.ByteString.Companion.encodeUtf8
import timber.log.Timber
import java.io.DataInputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.RandomAccessFile

internal data class LegacyDownload(
    val songId: String,
    val key: String,
    val state: Int,
    val length: Long,
    val bytesDownloaded: Long,
    val startedAt: Long,
)

internal data class LegacyCacheSpan(
    val file: File,
    val position: Long,
    val length: Long,
    val touchedAt: Long,
)

internal data class LegacyCacheEntry(
    val key: String,
    val length: Long?,
    val spans: List<LegacyCacheSpan>,
    val downloadCache: Boolean,
) {
    fun completeAudio(length: Long): LegacyAudioFile? {
        if (length <= 0) return null
        var position = 0L
        val audio = mutableListOf<LegacyAudioSpan>()
        for (span in spans.sortedWith(compareBy<LegacyCacheSpan> { it.position }.thenByDescending { it.length })) {
            if (position == length) break
            if (span.position > position) return null
            val end = span.position + span.length
            if (end <= position) continue
            val count = minOf(end, length) - position
            audio += LegacyAudioSpan(span.file.absolutePath, position - span.position, count, span.length)
            position += count
        }
        return if (position == length) LegacyAudioFile(audio) else null
    }
}

internal data class LegacyCacheSnapshot(
    val entries: List<LegacyCacheEntry>,
    val downloads: List<LegacyDownload>,
    val failures: List<Exception> = emptyList(),
)

// SimpleCache initialization can delete unindexed files. Read its v3 database and spans without opening a cache.
internal fun readLegacyCache(
    context: Context,
    downloadDirectory: File,
    playerDirectory: File,
): LegacyCacheSnapshot {
    val directories =
        listOf(downloadDirectory, playerDirectory).filter { directory ->
            directory.isDirectory && directory.walkTopDown().any { it.name.endsWith(".exo") }
        }
    if (directories.isEmpty()) {
        return LegacyCacheSnapshot(emptyList(), emptyList())
    }
    val databaseFile = context.getDatabasePath(StandaloneDatabaseProvider.DATABASE_NAME)
    check(databaseFile.isFile) { "Legacy audio index is missing; retaining cache files" }
    return SQLiteDatabase.openDatabase(databaseFile.path, null, SQLiteDatabase.OPEN_READONLY).use { index ->
        fun hasTable(name: String): Boolean =
            index.rawQuery("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?", arrayOf(name)).use { it.moveToFirst() }

        val failures = mutableListOf<Exception>()
        val downloads = mutableListOf<LegacyDownload>()
        if (hasTable("ExoPlayerDownloads")) {
            try {
                index
                    .rawQuery(
                        "SELECT id, custom_cache_key, uri, state, content_length, bytes_downloaded, start_time_ms FROM ExoPlayerDownloads",
                        null,
                    ).use { rows ->
                        while (rows.moveToNext()) {
                            downloads +=
                                LegacyDownload(
                                    rows.getString(0),
                                    rows.getString(1) ?: rows.getString(2),
                                    rows.getInt(3),
                                    rows.getLong(4),
                                    rows.getLong(5),
                                    rows.getLong(6),
                                )
                        }
                    }
            } catch (error: Exception) {
                Timber.w(error, "Unreadable legacy download index; retaining cache files")
                failures += error
            }
        }
        val entries =
            directories.flatMap { directory ->
                try {
                    val files = directory.walkTopDown().filter { it.isFile }.toList()
                    val uid = files.singleOrNull { it.parentFile == directory && it.name.matches(Regex("[0-9a-f]+\\.uid")) }
                    check(uid != null) { "Legacy audio cache UID is missing; retaining cache files" }
                    val suffix = uid.name.removeSuffix(".uid")
                    val table = "ExoPlayerCacheIndex$suffix"
                    check(hasTable(table)) { "Legacy audio content index is missing; retaining cache files" }
                    val fileMetadata = mutableMapOf<String, Pair<Long, Long>>()
                    val fileTable = "ExoPlayerCacheFileMetadata$suffix"
                    if (hasTable(fileTable)) {
                        index.rawQuery("SELECT name, length, last_touch_timestamp FROM $fileTable", null).use { rows ->
                            while (rows.moveToNext()) fileMetadata[rows.getString(0)] = rows.getLong(1) to rows.getLong(2)
                        }
                    }
                    val spans =
                        files
                            .mapNotNull { file ->
                                val match = Regex("(\\d+)\\.(\\d+)\\.(\\d+)\\.v3\\.exo").matchEntire(file.name) ?: return@mapNotNull null
                                val metadata = fileMetadata[file.name]
                                match.groupValues[1].toInt() to
                                    LegacyCacheSpan(
                                        file,
                                        match.groupValues[2].toLong(),
                                        metadata?.first ?: file.length(),
                                        metadata?.second ?: match.groupValues[3].toLong(),
                                    )
                            }.groupBy({ it.first }, { it.second })
                    buildList {
                        index.rawQuery("SELECT id, key, metadata FROM $table", null).use { rows ->
                            while (rows.moveToNext()) {
                                val length =
                                    try {
                                        legacyContentLength(rows.getBlob(2))
                                    } catch (error: Exception) {
                                        Timber.w(error, "Unreadable legacy cache metadata key=%s; retaining spans", rows.getString(1))
                                        failures += error
                                        continue
                                    }
                                add(
                                    LegacyCacheEntry(
                                        key = rows.getString(1),
                                        length = length,
                                        spans = spans[rows.getInt(0)].orEmpty(),
                                        downloadCache = directory == downloadDirectory,
                                    ),
                                )
                            }
                        }
                    }
                } catch (error: Exception) {
                    Timber.w(error, "Unreadable legacy audio index directory=%s; retaining spans", directory.name)
                    failures += error
                    emptyList()
                }
            }
        LegacyCacheSnapshot(entries, downloads, failures)
    }
}

private fun legacyContentLength(metadata: ByteArray): Long? =
    DataInputStream(metadata.inputStream()).use { input ->
        val count = input.readInt()
        check(count in 0..metadata.size) { "Invalid legacy cache metadata count" }
        var length: Long? = null
        repeat(count) {
            val key = input.readUTF()
            val size = input.readInt()
            check(size in 0..input.available()) { "Invalid legacy cache metadata size" }
            if (key == ContentMetadata.KEY_CONTENT_LENGTH && size == Long.SIZE_BYTES) {
                length = input.readLong().takeIf { it > 0L }
            } else {
                input.skipBytes(size)
            }
        }
        length
    }

@Serializable
internal data class LegacyAudioSpan(
    val path: String,
    val offset: Long,
    val length: Long,
    val fileLength: Long,
)

@Serializable
internal data class LegacyAudioFile(
    val spans: List<LegacyAudioSpan>,
) {
    val size: Long get() = spans.sumOf { it.length }

    fun verify() {
        check(spans.isNotEmpty()) { "Legacy audio was removed" }
        spans.forEach { span ->
            val file = File(span.path)
            if (!file.isFile) throw FileNotFoundException(span.path)
            check(span.offset >= 0 && span.length > 0 && span.offset <= span.fileLength - span.length) { "Invalid legacy audio span" }
            if (file.length() != span.fileLength) throw IOException("Legacy audio span length changed")
            file.inputStream().use { check(it.read() >= 0) { "Legacy audio span is empty" } }
        }
    }

    fun write(file: File) {
        check(file.parentFile!!.mkdirs() || file.parentFile!!.isDirectory)
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try {
            output.write(Json.encodeToString(this).toByteArray())
            atomic.finishWrite(output)
        } catch (error: Exception) {
            atomic.failWrite(output)
            throw error
        }
    }

    fun copyTo(target: File) {
        verify()
        target.outputStream().use { output ->
            spans.forEach { span ->
                RandomAccessFile(span.path, "r").use { input ->
                    input.seek(span.offset)
                    var remaining = span.length
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (remaining > 0) {
                        val read = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                        if (read < 0) throw IOException("Truncated legacy audio span")
                        output.write(buffer, 0, read)
                        remaining -= read
                    }
                }
            }
            output.fd.sync()
        }
        check(target.length() == size) { "Incomplete legacy audio copy" }
    }

    companion object {
        const val SCHEME = "legacy-audio"

        fun manifest(
            context: Context,
            songId: String,
        ): File = File(context.filesDir, "legacy-audio/${songId.encodeUtf8().sha256().hex()}.legacy")

        fun fromPath(
            context: Context,
            path: String,
        ): File? = File(path).takeIf { it.parentFile == File(context.filesDir, "legacy-audio") && it.extension == "legacy" }

        fun read(file: File): LegacyAudioFile = Json.decodeFromString(AtomicFile(file).openRead().bufferedReader().use { it.readText() })
    }
}

internal class LegacyAudioSource(
    file: File,
) : SeekableMediaSource {
    private val audio = LegacyAudioFile.read(file).also { it.verify() }
    override val mediaSize: Long = audio.size
    override var mediaPosition: Long = 0L
        private set
    private val files = mutableMapOf<String, RandomAccessFile>()

    init {
        try {
            // Open every span before cache clear can unlink files still needed by this playback.
            audio.spans.forEach { span -> files.getOrPut(span.path) { RandomAccessFile(span.path, "r") } }
        } catch (error: Exception) {
            files.values.forEach { it.close() }
            throw error
        }
    }

    @Synchronized
    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int = readAtMediaPosition(mediaPosition, buffer, offset, length).also { if (it > 0) mediaPosition += it }

    @Synchronized
    override fun readAtMediaPosition(
        position: Long,
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        require(position >= 0 && offset >= 0 && length >= 0 && offset <= buffer.size - length)
        if (length == 0) return 0
        if (position >= mediaSize) return -1
        var start = 0L
        for (span in audio.spans) {
            val end = start + span.length
            if (position < end) {
                val input = checkNotNull(files[span.path]) { "Legacy audio source is closed" }
                input.seek(span.offset + position - start)
                val read = input.read(buffer, offset, minOf(length.toLong(), end - position).toInt())
                if (read < 0) throw IOException("Truncated legacy audio span")
                return read
            }
            start = end
        }
        return -1
    }

    @Synchronized
    override fun seekToMediaPosition(position: Long): Long = position.coerceIn(0L, mediaSize).also { mediaPosition = it }

    override fun updatePlaybackPosition(positionMs: Long) = Unit

    @Synchronized
    override fun close() {
        files.values.forEach { it.close() }
        files.clear()
    }
}
