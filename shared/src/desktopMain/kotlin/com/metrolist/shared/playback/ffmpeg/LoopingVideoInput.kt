package com.metrolist.shared.playback.ffmpeg

import coil3.disk.DiskCache
import com.metrolist.shared.api.artwork.isHlsArtworkUrl
import com.metrolist.shared.network.sharedOkHttpClient
import com.metrolist.shared.storage.DesktopAnimatedArtworkCache
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

internal class LoopingVideoInput(
    private val url: String,
    private val headers: Map<String, String>,
    private val maxBytes: Long = 32L * 1024 * 1024,
    private val cache: DesktopAnimatedArtworkCache? = runCatching { DesktopAnimatedArtworkCache.get() }.getOrNull(),
) : InputStream(),
    SeekableMediaInput {
    private val lock = Any()
    private var call: Call? = null
    private var file: File? = null
    private var reader: RandomAccessFile? = null
    private var snapshot: DiskCache.Snapshot? = null
    private val cacheKey = url + "\n" + headers.toSortedMap(String.CASE_INSENSITIVE_ORDER).entries.joinToString("\n")
    private var closed = false

    override val mediaSize: Long?
        get() = synchronized(lock) { reader?.length() }
    override val mediaPosition: Long
        get() = synchronized(lock) { reader?.filePointer ?: 0L }
    override val bufferedBytePosition: Long
        get() = mediaSize ?: 0L

    fun prepare(): Boolean {
        val generation = cache?.generation() ?: 0L
        try {
            synchronized(lock) {
                if (closed) return false
                cache?.open(cacheKey)?.let { cached ->
                    try {
                        reader = RandomAccessFile(cached.data.toFile(), "r")
                        snapshot = cached
                        return true
                    } catch (error: Exception) {
                        cached.close()
                        throw error
                    }
                }
            }
        } catch (_: Exception) {
        }
        var temporary: File? = null
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        try {
            temporary = File.createTempFile("metrolist-loop-", ".media")
            temporary.outputStream().use { output ->
                if (isHlsArtworkUrl(url)) {
                    val playlist = ByteArrayOutputStream()
                    transfer(url, playlist, 256 * 1024L, deadline)
                    val segments = artworkSegments(playlist.toString(Charsets.UTF_8), url.toHttpUrl()) ?: return false
                    var total = 0L
                    for (segment in segments) {
                        total += transfer(segment.url.toString(), output, maxBytes - total, deadline, segment)
                    }
                } else {
                    transfer(url, output, maxBytes, deadline)
                }
            }
            synchronized(lock) {
                if (closed) return false
                val cached = runCatching { cache?.store(cacheKey, temporary!!, generation) }.getOrNull()
                if (cached != null) {
                    try {
                        reader = RandomAccessFile(cached.data.toFile(), "r")
                        snapshot = cached
                    } catch (error: Exception) {
                        cached.close()
                        throw error
                    }
                } else {
                    reader = RandomAccessFile(temporary!!, "r")
                    file = temporary
                    temporary = null
                }
            }
            return true
        } catch (_: Exception) {
            return false
        } finally {
            synchronized(lock) { call = null }
            temporary?.delete()
        }
    }

    private fun transfer(
        target: String,
        output: OutputStream,
        limit: Long,
        deadline: Long,
        segment: ArtworkSegment? = null,
    ): Long {
        val remaining = deadline - System.nanoTime()
        if (remaining <= 0 || limit <= 0) throw IOException("Artwork download limit reached")
        val request =
            Request
                .Builder()
                .url(target)
                .apply {
                    headers.forEach { (key, value) -> header(key, value) }
                    header("Accept-Encoding", "identity")
                    if (segment?.offset != null && segment.length != null) {
                        header("Range", "bytes=${segment.offset}-${segment.offset + segment.length - 1}")
                    }
                }.build()
        val download = sharedOkHttpClient.newCall(request)
        download.timeout().timeout(remaining, TimeUnit.NANOSECONDS)
        synchronized(lock) {
            if (closed) throw IOException("Artwork input closed")
            call = download
        }
        download.execute().use { response ->
            if (!response.isSuccessful) throw IOException("Artwork HTTP ${response.code}")
            val body = response.body
            if (!isHlsArtworkUrl(url) && body.contentType()?.toString()?.contains("mpegurl", ignoreCase = true) == true) {
                throw IOException("Unexpected artwork playlist")
            }
            if (body.contentLength() > limit) throw IOException("Artwork exceeds file limit")
            if (segment?.offset != null && segment.length != null) {
                val expected = "bytes ${segment.offset}-${segment.offset + segment.length - 1}/"
                if (response.code != 206 || response.header("Content-Range")?.startsWith(expected) != true) {
                    throw IOException("Artwork range mismatch")
                }
            } else if (response.code != 200) {
                throw IOException("Incomplete artwork response")
            }
            body.byteStream().use { input ->
                val buffer = ByteArray(16 * 1024)
                var total = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > limit) throw IOException("Artwork exceeds file limit")
                    output.write(buffer, 0, count)
                }
                if (total == 0L || (body.contentLength() >= 0 && total != body.contentLength()) ||
                    (segment?.length != null && total != segment.length)
                ) {
                    throw IOException("Incomplete artwork response")
                }
                return total
            }
        }
    }

    override fun read(): Int = synchronized(lock) { reader?.read() ?: -1 }

    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int = synchronized(lock) { reader?.read(buffer, offset, length) ?: -1 }

    override fun readAtMediaPosition(
        position: Long,
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int =
        synchronized(lock) {
            val input = reader ?: return -1
            input.seek(position)
            input.read(buffer, offset, length)
        }

    override fun seekToMediaPosition(position: Long): Long =
        synchronized(lock) {
            val input = checkNotNull(reader)
            input.seek(position.coerceIn(0L, input.length()))
            input.filePointer
        }

    override fun updatePlaybackPosition(positionMs: Long) = Unit

    override fun close() {
        synchronized(lock) {
            closed = true
            call?.cancel()
            try {
                reader?.close()
            } finally {
                reader = null
                try {
                    snapshot?.close()
                } finally {
                    snapshot = null
                    file?.delete()
                    file = null
                }
            }
        }
    }
}
