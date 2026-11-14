package com.metrolist.shared.playback.ffmpeg

import com.metrolist.innertubex.sabr.SabrChunk
import com.metrolist.innertubex.sabr.SabrProtocolException
import com.metrolist.innertubex.sabr.findSabrProtocolException
import com.metrolist.shared.playback.SeekableMediaSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.RandomAccessFile
import java.net.SocketTimeoutException
import java.nio.channels.FileChannel
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

internal interface SeekableMediaInput : SeekableMediaSource

internal interface PlaybackPositionInput {
    val streamFailure: Throwable?

    fun updatePlaybackPosition(positionMs: Long)

    fun beginPlaybackSeek(positionMs: Long) = updatePlaybackPosition(positionMs)

    fun endPlaybackSeek(positionMs: Long) = updatePlaybackPosition(positionMs)
}

@Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
class SabrInputStream(
    override val mediaSize: Long?,
    private val streamFactory: (initialPlayerTimeMs: Long, playbackPositionMs: () -> Long) -> Flow<SabrChunk>,
    private val expectedDurationMs: Long? = null,
    private val startupTimeoutMs: Long = DEFAULT_STARTUP_TIMEOUT_MS,
    private val readTimeoutMs: Long = DEFAULT_READ_TIMEOUT_MS,
    private val seekPrerollMs: Long = DEFAULT_SEEK_PREROLL_MS,
    startImmediately: Boolean = true,
    tempDirectory: File = File(checkNotNull(System.getProperty("java.io.tmpdir")) { "JVM temporary directory is unavailable" }),
) : InputStream(),
    SeekableMediaInput,
    PlaybackPositionInput {
    private val stateLock = Object()
    private val playbackPositionLock = Object()
    private val cacheCommitLock = Any()
    private var backing = CacheBacking(tempDirectory, mediaSize)
    private val cacheLock get() = backing
    private val cacheFile get() = backing.data
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var playbackPositionMs = 0L
    private var playbackSeekInProgress = false

    @Volatile
    private var closed = false

    @Volatile
    private var failure: Throwable? = null

    private val cachedRanges = mutableListOf<LongRange>()
    private val cachedChunkEndTimes = mutableMapOf<Long, Long>()
    private var readPosition = 0L
    private var completed = false
    private var receivedMedia = false

    @Volatile
    private var producerGeneration = 0L
    private var producer: Job? = null
    private var producerSuspended = false
    private var producerInitialPlayerTimeMs = 0L
    private var startupDeadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(startupTimeoutMs)

    init {
        require(mediaSize == null || mediaSize >= 0L) { "SABR media size must not be negative" }
        require(expectedDurationMs == null || expectedDurationMs >= 0L) { "SABR duration must not be negative" }
        require(startupTimeoutMs > 0L) { "SABR startup timeout must be positive" }
        require(readTimeoutMs > 0L) { "SABR read timeout must be positive" }
        require(seekPrerollMs >= 0L) { "SABR seek preroll must not be negative" }
        if (startImmediately) startProducer(0L)
    }

    fun shareCachedDataFrom(source: SabrInputStream) {
        if (source === this || mediaSize != source.mediaSize) return
        // Only unopened replacements can acquire backing; no replacement-to-source lock nesting.
        val snapshot =
            synchronized(source.stateLock) {
                if (source.closed || source.failure != null) return
                source.backing.retain()
                CacheSnapshot(source.backing, source.cachedRanges.toList(), source.cachedChunkEndTimes.toMap(), source.completed)
            }
        try {
            synchronized(stateLock) {
                check(producer == null && !closed && cachedRanges.isEmpty())
                backing.release()
                backing = snapshot.backing
                cachedRanges.addAll(snapshot.ranges)
                cachedChunkEndTimes.putAll(snapshot.times)
                receivedMedia = cachedRanges.isNotEmpty()
                completed = isFullyCached() || (snapshot.completed && cachedRanges.singleOrNull()?.first == 0L)
            }
        } catch (error: Throwable) {
            snapshot.backing.release()
            throw error
        }
    }

    fun suspendProducer() {
        synchronized(stateLock) {
            if (closed || producerSuspended) return
            producerSuspended = true
            producerGeneration++
            producer?.cancel()
            producer = null
            stateLock.notifyAll()
        }
    }

    fun resumeProducer(positionMs: Long) {
        updatePlaybackPosition(positionMs)
        synchronized(stateLock) {
            if (closed || !producerSuspended) return
            producerSuspended = false
            startIfNeeded(readPosition)
            stateLock.notifyAll()
        }
    }

    private fun startIfNeeded(position: Long) {
        if (producer != null || completed || closed || producerSuspended || failure != null) return
        val target = synchronized(playbackPositionLock) { (playbackPositionMs - seekPrerollMs).coerceAtLeast(0L) }
        val prefixEnd = cachedEndAt(0L)
        startProducer(if (position == 0L && prefixEnd == null) 0L else maxOf(target, cachedChunkEndTimes[prefixEnd] ?: 0L))
    }

    private fun startProducer(initialPlayerTimeMs: Long) {
        val generation =
            synchronized(stateLock) {
                synchronized(cacheCommitLock) {
                    producerGeneration++
                    failure = null
                    completed = false
                    producerInitialPlayerTimeMs = initialPlayerTimeMs
                    if (!receivedMedia) {
                        startupDeadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(startupTimeoutMs)
                    }
                    producerGeneration
                }
            }
        producer?.cancel()
        producer =
            scope.launch {
                try {
                    streamFactory(initialPlayerTimeMs) { synchronized(playbackPositionLock) { playbackPositionMs } }
                        .collect { append(generation, it) }
                    synchronized(stateLock) {
                        if (closed || producerGeneration != generation) return@launch
                        val lastMediaEndMs = cachedChunkEndTimes.values.maxOrNull()
                        val minimumCompleteEndMs = expectedDurationMs?.minus(EARLY_END_TOLERANCE_MS)?.coerceAtLeast(0L)
                        if (minimumCompleteEndMs != null && lastMediaEndMs != null && lastMediaEndMs < minimumCompleteEndMs) {
                            failure =
                                IOException(
                                    "SABR stream ended at $lastMediaEndMs ms before expected duration $expectedDurationMs ms",
                                )
                        }
                        completed = true
                        stateLock.notifyAll()
                    }
                } catch (cancelled: CancellationException) {
                    recordFailure(generation, cancelled)
                } catch (error: Throwable) {
                    recordFailure(generation, error)
                }
            }
    }

    override val mediaPosition: Long
        get() = synchronized(stateLock) { readPosition }

    override val bufferedBytePosition: Long
        get() = synchronized(stateLock) { cachedEndAt(readPosition) ?: readPosition }

    override val streamFailure: Throwable?
        get() = failure

    override fun read(): Int {
        val byte = ByteArray(1)
        return if (read(byte, 0, 1) < 0) -1 else byte[0].toInt() and 0xff
    }

    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        checkBounds(buffer, offset, length)
        if (length == 0) return 0
        val position = synchronized(stateLock) { readPosition }
        val count = readAtMediaPosition(position, buffer, offset, length)
        if (count > 0) {
            synchronized(stateLock) {
                if (readPosition == position) readPosition += count
            }
        }
        return count
    }

    override fun readAtMediaPosition(
        position: Long,
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        checkBounds(buffer, offset, length)
        require(position >= 0L) { "SABR read position must not be negative" }
        if (length == 0) return 0
        mediaSize?.let {
            require(position <= it) { "SABR read position exceeds media size" }
            if (position == it) return -1
        }

        val count: Int
        synchronized(stateLock) {
            startIfNeeded(position)
            var availableEnd = cachedEndAt(position)
            var readDeadlineNanos =
                if (receivedMedia) {
                    System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(readTimeoutMs)
                } else {
                    startupDeadlineNanos
                }
            while (!closed && failure == null && !completed && availableEnd == null) {
                try {
                    if (producerSuspended) {
                        stateLock.wait()
                        readDeadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(readTimeoutMs)
                        availableEnd = cachedEndAt(position)
                        continue
                    }
                    val remainingNanos = readDeadlineNanos - System.nanoTime()
                    if (remainingNanos <= 0L) {
                        failure =
                            SocketTimeoutException(
                                if (receivedMedia) {
                                    "SABR stream stalled while waiting for media"
                                } else {
                                    "SABR stream produced no media before the startup deadline"
                                },
                            )
                        completed = true
                        break
                    }
                    val remainingMillis =
                        TimeUnit.NANOSECONDS
                            .toMillis(remainingNanos)
                            .coerceAtLeast(1L)
                    stateLock.wait(remainingMillis)
                } catch (error: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw InterruptedIOException("SABR read interrupted").apply { initCause(error) }
                }
                availableEnd = cachedEndAt(position)
            }
            if (availableEnd == null) {
                if (!closed && completed && failure == null && (mediaSize != null || cachedRanges.any { it.first > position })) {
                    failure = IOException("SABR stream ended before media byte $position")
                }
                failure?.let { error ->
                    throw IOException("SABR stream failed", error)
                }
            }
            if (closed || availableEnd == null) return -1

            count = minOf(length.toLong(), availableEnd - position).toInt()
        }

        synchronized(cacheLock) {
            if (closed) return -1
            if (Thread.currentThread().isInterrupted) throw InterruptedIOException("SABR read interrupted")
            // Media3 interrupts cancelled loads. FileChannel would close the shared cache on interruption.
            cacheFile.seek(position)
            cacheFile.readFully(buffer, offset, count)
        }
        return count
    }

    override fun seekToMediaPosition(position: Long): Long {
        require(position >= 0L) { "SABR seek position must not be negative" }
        mediaSize?.let { require(position <= it) { "SABR seek position exceeds media size" } }
        synchronized(stateLock) {
            check(!closed) { "SABR stream is closed" }
            readPosition = position
            if (failure?.findSabrProtocolException() != null) return position
            if (producerSuspended) return position
            if (producer == null) {
                if (position < (mediaSize ?: Long.MAX_VALUE)) startIfNeeded(position)
                return position
            }
            if (position < (mediaSize ?: Long.MAX_VALUE) && !isFullyCached()) {
                val uncached = cachedEndAt(position) == null
                val targetTimeMs =
                    synchronized(playbackPositionLock) {
                        if (position == 0L && uncached) 0L else (playbackPositionMs - seekPrerollMs).coerceAtLeast(0L)
                    }
                val shouldRestart =
                    (
                        targetTimeMs < producerInitialPlayerTimeMs &&
                            (cachedChunkEndTimes[cachedEndAt(position)] ?: Long.MIN_VALUE) < producerInitialPlayerTimeMs
                    ) ||
                        (
                            uncached && (
                                completed || producer?.isActive != true ||
                                    (producerInitialPlayerTimeMs == 0L && targetTimeMs > 0L) ||
                                    (targetTimeMs - producerInitialPlayerTimeMs) >= SEEK_RESTART_THRESHOLD_MS
                            )
                        )
                if (shouldRestart) {
                    startProducer(targetTimeMs)
                }
            }
        }
        return position
    }

    override fun updatePlaybackPosition(positionMs: Long) {
        synchronized(playbackPositionLock) {
            if (!playbackSeekInProgress) {
                playbackPositionMs = positionMs.coerceAtLeast(0L)
            }
        }
    }

    override fun beginPlaybackSeek(positionMs: Long) {
        synchronized(playbackPositionLock) {
            playbackSeekInProgress = true
            playbackPositionMs = positionMs.coerceAtLeast(0L)
        }
    }

    override fun endPlaybackSeek(positionMs: Long) {
        synchronized(playbackPositionLock) {
            playbackPositionMs = positionMs.coerceAtLeast(0L)
            playbackSeekInProgress = false
        }
    }

    private fun cancelStream(): Boolean {
        synchronized(stateLock) {
            if (closed) return false
            closed = true
            stateLock.notifyAll()
        }
        scope.cancel()
        return true
    }

    override fun close() {
        if (cancelStream()) backing.release()
    }

    fun closeAsync(): Job {
        val dispose = cancelStream()
        return CoroutineScope(Dispatchers.IO).launch { if (dispose) backing.release() }
    }

    fun retainCompleteCapture(): CompleteCapture? =
        synchronized(stateLock) {
            val size = mediaSize ?: return null
            if (closed || failure != null || size <= 0L || cachedRanges.singleOrNull() != 0L until size) return null
            backing.retain()
            CompleteCapture(backing, size)
        }

    class CompleteCapture internal constructor(
        private val backing: CacheBacking,
        val size: Long,
    ) : AutoCloseable {
        private var closed = false

        suspend fun copyTo(destination: File): Boolean =
            withContext(Dispatchers.IO) {
                synchronized(this@CompleteCapture) {
                    if (closed) return@withContext false
                    backing.retain()
                }
                try {
                    synchronized(backing) {
                        if (backing.data.length() != size) return@withContext false
                    }
                    destination.outputStream().buffered().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var position = 0L
                        while (position < size) {
                            currentCoroutineContext().ensureActive()
                            if (Thread.currentThread().isInterrupted) throw InterruptedIOException("SABR copy interrupted")
                            val count = minOf(buffer.size.toLong(), size - position).toInt()
                            synchronized(backing) {
                                backing.data.seek(position)
                                backing.data.readFully(buffer, 0, count)
                            }
                            output.write(buffer, 0, count)
                            position += count
                        }
                    }
                    true
                } catch (error: Throwable) {
                    destination.delete()
                    throw error
                } finally {
                    backing.release()
                }
            }

        @Synchronized
        override fun close() {
            if (closed) return
            closed = true
            backing.release()
        }

        fun closeAsync(): Job = CoroutineScope(Dispatchers.IO).launch { close() }
    }

    internal fun cacheFileForTest(): File = synchronized(cacheLock) { backing.file }

    internal fun cacheChannelForTest(): FileChannel = synchronized(cacheLock) { cacheFile.channel }

    private data class CacheSnapshot(
        val backing: CacheBacking,
        val ranges: List<LongRange>,
        val times: Map<Long, Long>,
        val completed: Boolean,
    )

    internal class CacheBacking(
        private val directory: File,
        private val size: Long?,
    ) {
        private val references = AtomicInteger(1)
        private var cache: Pair<File, RandomAccessFile>? = null
        val cachedRanges = mutableListOf<LongRange>()
        val data: RandomAccessFile get() = open().second
        val file: File get() = open().first

        fun addCachedRange(range: LongRange) {
            if (range.isEmpty()) return
            cachedRanges += range
            cachedRanges.sortBy(LongRange::first)
            val merged = mutableListOf<LongRange>()
            cachedRanges.forEach { current ->
                val previous = merged.lastOrNull()
                if (previous != null && current.first <= previous.last + 1L) {
                    merged[merged.lastIndex] = previous.first..maxOf(previous.last, current.last)
                } else {
                    merged += current
                }
            }
            cachedRanges.clear()
            cachedRanges += merged
        }

        private fun open(): Pair<File, RandomAccessFile> {
            cache?.let { return it }
            check(references.get() > 0)
            return createCache(directory, size).also { cache = it }
        }

        fun retain() {
            check(references.getAndIncrement() > 0)
        }

        fun release() {
            val remaining = references.decrementAndGet()
            check(remaining >= 0)
            if (remaining != 0) return
            synchronized(this) {
                cache?.let { (file, data) ->
                    runCatching { data.close() }
                    runCatching { file.delete() }
                }
                cache = null
            }
        }
    }

    private fun append(
        generation: Long,
        chunk: SabrChunk,
    ) {
        if (chunk.data.isEmpty()) return
        val endRangeExclusive = chunk.endRangeExclusive
        if (mediaSize != null && endRangeExclusive > mediaSize) {
            throw IOException("SABR stream exceeded its declared media size")
        }
        synchronized(cacheCommitLock) {
            val cache = backing
            synchronized(cache) {
                if (closed || producerGeneration != generation || backing !== cache) return
                cache.cachedRanges.forEach { cachedRange ->
                    val overlapStart = maxOf(chunk.startRange, cachedRange.first)
                    val overlapEndExclusive = minOf(endRangeExclusive, cachedRange.last + 1L)
                    if (overlapStart < overlapEndExclusive) {
                        val comparisonBuffer = ByteArray(minOf(8_192L, overlapEndExclusive - overlapStart).toInt())
                        var compared = overlapStart
                        while (compared < overlapEndExclusive) {
                            val count = minOf(comparisonBuffer.size.toLong(), overlapEndExclusive - compared).toInt()
                            cache.data.seek(compared)
                            cache.data.readFully(comparisonBuffer, 0, count)
                            val chunkOffset = (compared - chunk.startRange).toInt()
                            for (index in 0 until count) {
                                if (chunk.data[chunkOffset + index] != comparisonBuffer[index]) {
                                    throw SabrProtocolException(
                                        "SABR audio chunk ${chunk.sequenceNumber ?: "unknown"} differs from cached byte " +
                                            (compared + index),
                                    )
                                }
                            }
                            compared += count
                        }
                    }
                }
                cache.data.seek(chunk.startRange)
                cache.data.write(chunk.data)
                cache.addCachedRange(chunk.startRange until endRangeExclusive)
            }
        }
        synchronized(stateLock) {
            if (closed || producerGeneration != generation) return
            receivedMedia = true
            addCachedRange(chunk.startRange until endRangeExclusive)
            val startMs = chunk.startMs
            val durationMs = chunk.durationMs
            if (!chunk.isInitialization && startMs != null && durationMs != null &&
                startMs >= 0L && durationMs > 0L && startMs <= Long.MAX_VALUE - durationMs
            ) {
                cachedChunkEndTimes[endRangeExclusive] = startMs + durationMs
            }
            cachedChunkEndTimes.keys.retainAll(cachedRanges.map { it.last + 1L }.toSet())
            if (mediaSize != null && isFullyCached()) {
                completed = true
            }
            stateLock.notifyAll()
        }
    }

    private fun isFullyCached(): Boolean {
        val size = mediaSize ?: return false
        return (cachedEndAt(0L) ?: 0L) >= size
    }

    private fun cachedEndAt(position: Long): Long? = cachedRanges.firstOrNull { position in it }?.let { it.last + 1L }

    private fun addCachedRange(range: LongRange) {
        if (range.isEmpty()) return
        cachedRanges += range
        cachedRanges.sortBy(LongRange::first)
        val merged = mutableListOf<LongRange>()
        cachedRanges.forEach { current ->
            val previous = merged.lastOrNull()
            if (previous != null && current.first <= previous.last + 1L) {
                merged[merged.lastIndex] = previous.first..maxOf(previous.last, current.last)
            } else {
                merged += current
            }
        }
        cachedRanges.clear()
        cachedRanges += merged
    }

    private fun recordFailure(
        generation: Long,
        error: Throwable,
    ) {
        synchronized(stateLock) {
            if (closed || producerGeneration != generation) return
            failure = error
            completed = true
            stateLock.notifyAll()
        }
    }

    private fun checkBounds(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ) {
        if (offset < 0 || length < 0 || length > buffer.size - offset) {
            throw IndexOutOfBoundsException("offset=$offset length=$length size=${buffer.size}")
        }
    }

    companion object {
        const val DEFAULT_SEEK_PREROLL_MS = 10_000L
        private const val SEEK_RESTART_THRESHOLD_MS = 15_000L
        private const val DEFAULT_STARTUP_TIMEOUT_MS = 15_000L
        private const val EARLY_END_TOLERANCE_MS = 2_000L

        private const val DEFAULT_READ_TIMEOUT_MS = 90_000L

        fun createCache(
            tempDirectory: File,
            mediaSize: Long?,
        ): Pair<File, RandomAccessFile> {
            require(mediaSize == null || mediaSize >= 0L) { "SABR media size must not be negative" }
            val file = File.createTempFile("metrolist-sabr-", ".media", tempDirectory)
            return try {
                val data = RandomAccessFile(file, "rw")
                runCatching { file.delete() }
                file to data
            } catch (error: Throwable) {
                runCatching { file.delete() }
                throw error
            }
        }
    }
}
