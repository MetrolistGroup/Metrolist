package com.metrolist.shared.playback.ffmpeg

import com.metrolist.shared.youtube.playback.PlaybackResource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile

object DirectPlaybackCache {
    private const val MAX_CAPTURES = 4
    private const val MAX_CAPTURE_BYTES = 512L * 1024 * 1024

    private data class Key(
        val videoId: String,
        val itag: Int?,
        val mimeType: String?,
        val codecs: String?,
        val size: Long,
    )

    private val captures = LinkedHashMap<Key, Capture>()
    private val initializedDirectories = mutableSetOf<File>()

    private fun keyOf(resource: PlaybackResource): Key =
        Key(
            videoId = resource.videoId,
            itag = resource.itag,
            mimeType =
                resource.mimeType
                    ?.substringBefore(';')
                    ?.trim()
                    ?.lowercase(),
            codecs = resource.codecs,
            size = resource.contentLengthBytes ?: 0L,
        )

    fun reconcileDirectory(directory: File) {
        synchronized(initializedDirectories) {
            if (!initializedDirectories.add(directory)) return
            directory.mkdirs()
            directory
                .listFiles()
                ?.filter { it.name.startsWith("playback-") && it.extension == "part" }
                ?.forEach { it.delete() }
        }
    }

    @Synchronized
    fun begin(
        resource: PlaybackResource,
        directory: File,
        budgetBytes: Long,
        prefetch: Boolean = false,
    ): Capture? {
        val size = resource.contentLengthBytes ?: return null
        if (!resource.isDirectFile || !resource.url.startsWith("https://") ||
            budgetBytes < 1024L || size !in 1024L..minOf(budgetBytes, MAX_CAPTURE_BYTES)
        ) {
            return null
        }
        val key = keyOf(resource)
        captures[key]?.let { existing ->
            if (existing.matches(size) && existing.attach(prefetch)) return existing
            captures.remove(key)
            existing.discard()
        }
        while (captures.size >= MAX_CAPTURES ||
            captures.values.sumOf { it.size } + size > budgetBytes
        ) {
            val oldest =
                captures.entries.firstOrNull { !it.value.isAttached() && !it.value.isComplete }
                    ?: captures.entries.firstOrNull { !it.value.isAttached() }
                    ?: return null
            captures.remove(oldest.key)
            oldest.value.discard()
        }
        return Capture(directory, size) { discarded ->
            synchronized(this@DirectPlaybackCache) { captures.remove(key, discarded) }
        }.also {
            captures[key] = it
            it.attach(prefetch)
        }
    }

    fun clear(directory: File? = null) {
        val snapshot = synchronized(this) { captures.values.toList().also { captures.clear() } }
        snapshot.forEach(Capture::discard)
        directory?.let(::reconcileDirectory)
    }

    suspend fun awaitCompletion(
        resource: PlaybackResource,
        httpClient: OkHttpClient = directMediaHttpClient,
    ): Boolean = find(resource)?.awaitCompletion(resource, httpClient) == true

    suspend fun copyComplete(
        resource: PlaybackResource,
        destination: File,
    ): Boolean =
        withContext(Dispatchers.IO) {
            find(resource)?.copyTo(destination) == true
        }

    @Synchronized
    private fun find(resource: PlaybackResource): Capture? = resource.contentLengthBytes?.let { captures[keyOf(resource)] }

    class Capture internal constructor(
        private val directory: File,
        internal val size: Long,
        private val onDiscard: (Capture) -> Unit = {},
    ) {
        private val changes = MutableStateFlow(0L)
        private val ranges = mutableListOf<LongRange>()
        private var file: File? = null
        private var output: RandomAccessFile? = null
        private var attachments = 0
        private var playbackAttachments = 0
        private var playbackEnded = false
        private var playbackCompleted = false
        private var completing = false
        private var complete = false
        private var consumed = false
        private var discarded = false
        private var spaceRemaining = 0L

        internal fun matches(expectedSize: Long): Boolean = size == expectedSize && !discarded

        @Synchronized
        internal fun attach(prefetch: Boolean = false): Boolean {
            if (discarded) return false
            attachments++
            if (!prefetch) {
                playbackAttachments++
                playbackEnded = false
            }
            notifyChanged()
            return true
        }

        @Synchronized
        internal fun isAttached(): Boolean = (attachments > 0 || completing) && !discarded

        val isComplete: Boolean
            @Synchronized get() = complete && !discarded

        @Synchronized
        fun read(
            start: Long,
            length: Int,
        ): ByteArray? {
            if (discarded || ranges.isEmpty()) return null
            if (start < 0L || length <= 0 || start > size - length) return null
            val end = start + length - 1L
            if (ranges.none { it.first <= start && it.last >= end }) return null
            val source = file?.takeIf { it.isFile && it.length() >= start + length } ?: return null
            return try {
                RandomAccessFile(source, "r").use { input ->
                    input.seek(start)
                    ByteArray(length).also(input::readFully)
                }
            } catch (_: Exception) {
                null
            }
        }

        fun record(
            start: Long,
            bytes: ByteArray,
            totalSize: Long,
        ) {
            val invalid =
                synchronized(this) {
                    if (discarded || complete) return
                    if (bytes.isEmpty() || totalSize != size || start < 0 || bytes.size > size - start) {
                        true
                    } else {
                        try {
                            if (output == null) {
                                reconcileDirectory(directory)
                                file =
                                    file?.takeIf { it.isFile } ?: File.createTempFile("playback-", ".part", directory).also {
                                        file = it
                                    }
                                output = RandomAccessFile(file, "rw")
                            }
                            if (spaceRemaining < bytes.size) {
                                spaceRemaining = minOf(8L * 1024 * 1024, directory.usableSpace - 1024 * 1024)
                            }
                            check(spaceRemaining >= bytes.size)
                            output!!.seek(start)
                            output!!.write(bytes)
                            spaceRemaining -= bytes.size
                            var first = start
                            var last = start + bytes.size - 1L
                            val iterator = ranges.iterator()
                            while (iterator.hasNext()) {
                                val range = iterator.next()
                                if (range.first <= last + 1L && range.last + 1L >= first) {
                                    first = minOf(first, range.first)
                                    last = maxOf(last, range.last)
                                    iterator.remove()
                                }
                            }
                            ranges.add(first..last)
                            ranges.sortBy { it.first }
                            if (ranges.size == 1 && ranges[0].first == 0L && ranges[0].last == size - 1L) {
                                complete = true
                                output!!.close()
                                output = null
                            }
                            notifyChanged()
                            false
                        } catch (_: Exception) {
                            true
                        }
                    }
                }
            if (invalid) discard()
        }

        fun close(
            prefetch: Boolean = false,
            playbackCompleted: Boolean = false,
        ) {
            synchronized(this) {
                if (discarded) return
                if (attachments > 0) attachments--
                if (!prefetch) {
                    if (playbackCompleted) this.playbackCompleted = true
                    if (playbackAttachments > 0) playbackAttachments--
                    if (playbackAttachments == 0 && !complete) playbackEnded = true
                }
                if (attachments == 0) {
                    runCatching { output?.close() }
                    output = null
                }
            }
            if (consumed) discard() else notifyChanged()
        }

        internal fun missingRanges(): List<LongRange> =
            synchronized(this) {
                if (discarded || size <= 0L) return emptyList()
                if (ranges.isEmpty()) return listOf(0L until size)
                val gaps = mutableListOf<LongRange>()
                var cursor = 0L
                for (range in ranges) {
                    if (range.first > cursor) {
                        gaps.add(cursor until range.first)
                    }
                    cursor = maxOf(cursor, range.last + 1L)
                }
                if (cursor < size) {
                    gaps.add(cursor until size)
                }
                gaps
            }

        internal suspend fun awaitCompletion(
            resource: PlaybackResource? = null,
            httpClient: OkHttpClient = directMediaHttpClient,
        ): Boolean {
            changes.first {
                synchronized(this) { discarded || complete || playbackEnded }
            }
            if (synchronized(this) { complete && !discarded }) return true
            if (synchronized(this) { discarded || !playbackCompleted } || resource == null) return false
            return fillGaps(resource, httpClient)
        }

        private suspend fun fillGaps(
            resource: PlaybackResource,
            httpClient: OkHttpClient,
        ): Boolean {
            val gaps =
                synchronized(this) {
                    if (discarded || complete) return isComplete
                    completing = true
                    missingRanges()
                }
            if (gaps.isEmpty()) {
                synchronized(this) { completing = false }
                return isComplete
            }
            try {
                for (gap in gaps) {
                    currentCoroutineContext().ensureActive()
                    if (!fetchRange(resource, gap.first, gap.last, httpClient)) {
                        return false
                    }
                }
                return isComplete
            } finally {
                synchronized(this) {
                    completing = false
                    if (attachments == 0 && !complete) {
                        runCatching { output?.close() }
                        output = null
                    }
                }
            }
        }

        private suspend fun fetchRange(
            resource: PlaybackResource,
            start: Long,
            end: Long,
            httpClient: OkHttpClient,
        ): Boolean =
            withContext(Dispatchers.IO) {
                val request =
                    Request
                        .Builder()
                        .url(resource.url)
                        .apply { resource.headers.forEach { (name, value) -> header(name, value) } }
                        .header("Accept", "*/*")
                        .header("Accept-Encoding", "identity")
                        .header("Range", "bytes=$start-$end")
                        .build()
                val call = httpClient.newCall(request)
                try {
                    call.execute().use { response ->
                        if (response.code == 200) {
                            if (start != 0L) return@withContext false
                        } else if (response.code != 206) {
                            return@withContext false
                        }
                        val stream = response.body.byteStream()
                        val buffer = ByteArray(64 * 1024)
                        var current = start
                        while (current <= end) {
                            currentCoroutineContext().ensureActive()
                            val toRead = minOf(buffer.size.toLong(), end - current + 1L).toInt()
                            val count = stream.read(buffer, 0, toRead)
                            if (count < 0) break
                            record(current, buffer.copyOf(count), size)
                            current += count
                        }
                        current > end
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    false
                }
            }

        internal fun copyTo(destination: File): Boolean {
            val copied =
                synchronized(this) {
                    if (!complete || consumed || discarded) return false
                    val source = file?.takeIf { it.length() == size } ?: return false
                    try {
                        source.copyTo(destination, overwrite = true)
                        consumed = true
                        true
                    } catch (_: Exception) {
                        destination.delete()
                        false
                    }
                }
            if (copied) {
                if (synchronized(this) { attachments == 0 }) discard()
            }
            return copied
        }

        fun discard() {
            synchronized(this) {
                if (discarded) return
                discarded = true
                complete = false
                runCatching { output?.close() }
                output = null
                file?.delete()
                file = null
                ranges.clear()
                notifyChanged()
            }
            onDiscard(this)
        }

        private fun notifyChanged() {
            changes.value++
        }
    }
}
