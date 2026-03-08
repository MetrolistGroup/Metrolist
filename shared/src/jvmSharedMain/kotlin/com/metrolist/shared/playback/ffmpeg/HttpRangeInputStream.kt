package com.metrolist.shared.playback.ffmpeg

import com.metrolist.shared.network.sharedOkHttpClient
import com.metrolist.shared.playback.PlaybackHttpStatusCarrier
import com.metrolist.shared.utils.Logger
import com.metrolist.shared.youtube.playback.PlaybackBandwidthMeter
import com.metrolist.shared.youtube.playback.PlaybackEventKind
import com.metrolist.shared.youtube.playback.PlaybackEventLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration.Companion.milliseconds

private const val STARTUP_PROBE_CHUNK_SIZE = 65_536
private const val DEFAULT_CHUNK_SIZE = 4_194_304
private const val DEFAULT_STARTUP_REQUEST_TIMEOUT_MS = 15_000L
private const val DEFAULT_REQUEST_TIMEOUT_MS = 20_000L
private const val DEFAULT_ESTIMATED_MEDIA_SIZE = 100_000_000L
private const val MAX_CONCURRENT_CHUNK_REQUESTS = 3
private const val MAX_CACHED_CHUNKS = 4

internal val directMediaHttpClient =
    sharedOkHttpClient
        .newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .addInterceptor(DirectMediaRedirectInterceptor)
        .retryOnConnectionFailure(true)
        .build()

class HttpRangeInputStream(
    private val httpClient: OkHttpClient = directMediaHttpClient,
    private val url: String,
    private val requestHeaders: Map<String, String>,
    mediaSize: Long = DEFAULT_ESTIMATED_MEDIA_SIZE,
    private val chunkSize: Int = DEFAULT_CHUNK_SIZE,
    initialChunkSize: Int? = null,
    private val startupRequestTimeoutMs: Long = DEFAULT_STARTUP_REQUEST_TIMEOUT_MS,
    private val requestTimeoutMs: Long = DEFAULT_REQUEST_TIMEOUT_MS,
    private val prefetchAhead: Boolean = false,
    private val onResponseStatus: (Int) -> Unit = {},
    private val onStreamExpired: ((Int?) -> Unit)? = null,
    private val cacheCapture: DirectPlaybackCache.Capture? = null,
    private val cacheCapturePrefetch: Boolean = false,
    private val targetRequestDurationMs: Long? = null,
) : InputStream(),
    SeekableMediaInput,
    PlaybackPositionInput {
    private val prefetchScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val chunkSemaphore = Semaphore(MAX_CONCURRENT_CHUNK_REQUESTS)
    private var position = 0L
    private val chunkCache = LinkedHashMap<Long, ByteArray>(MAX_CACHED_CHUNKS, 0.75f, true)
    private val inFlightChunks = mutableMapOf<Long, Deferred<ByteArray>>()
    private var activeChunkStart = -1L
    private var activeChunkBytes = byteArrayOf()
    private var nextDemandChunkSize = minOf(initialChunkSize ?: STARTUP_PROBE_CHUNK_SIZE, chunkSize)
    private var demandChunkLimit = if (targetRequestDurationMs != null) nextDemandChunkSize else chunkSize
    private var hasFetchedChunk = false
    private var prefetchJob: Job? = null
    private val activeCalls = mutableSetOf<Call>()
    private val createdAtMs = System.currentTimeMillis()
    private var chunkRequestCount = 0
    private val streamTag = Integer.toHexString(System.identityHashCode(this))

    @Volatile
    private var streamExpired = false

    @Volatile
    private var closed = false

    @Volatile
    private var reachedEof = false

    @Volatile
    override var mediaSize: Long = mediaSize
        private set

    @Volatile
    private var failure: Throwable? = null

    init {
        require(mediaSize >= 0L) { "Direct media size cannot be negative" }
        require(chunkSize > 0) { "Direct media chunk size must be positive" }
        require(initialChunkSize == null || initialChunkSize > 0) { "Direct media initial chunk size must be positive" }
        require(startupRequestTimeoutMs > 0L) { "Direct media startup timeout must be positive" }
        require(requestTimeoutMs > 0L) { "Direct media request timeout must be positive" }
        require(targetRequestDurationMs == null || targetRequestDurationMs in 1..minOf(startupRequestTimeoutMs, requestTimeoutMs)) {
            "Direct media target request duration must be positive and within the request timeouts"
        }
        if (this.mediaSize <= 0L) {
            val clen =
                Regex("[?&]clen=([0-9]+)")
                    .find(url)
                    ?.groupValues
                    ?.get(1)
                    ?.toLongOrNull()
            if (clen != null && clen > 0L) {
                this.mediaSize = clen
            }
        }
        val hasPot = url.contains("&pot=") || url.contains("?pot=")
        val host = runCatching { java.net.URI(url).host }.getOrNull() ?: "unknown"
        val headerNames = requestHeaders.keys.toList()
        Logger.d(
            "HttpRange",
            "Created: host=$host mediaSize=${this.mediaSize}" +
                " chunkSize=$chunkSize hasPot=$hasPot headers=$headerNames",
        )
    }

    override val mediaPosition: Long
        get() = position

    override val bufferedBytePosition: Long
        get() =
            synchronized(this) {
                var end =
                    if (activeChunkStart >= 0L && position in activeChunkStart..(activeChunkStart + activeChunkBytes.size)) {
                        activeChunkStart + activeChunkBytes.size
                    } else {
                        position
                    }
                synchronized(chunkCache) {
                    while (true) {
                        val nextChunkBytes = chunkCache[end] ?: break
                        if (nextChunkBytes.isEmpty()) break
                        end += nextChunkBytes.size
                    }
                }
                if (mediaSize > 0L) end.coerceAtMost(mediaSize) else end
            }

    override val streamFailure: Throwable?
        get() = failure

    override fun updatePlaybackPosition(positionMs: Long) = Unit

    override fun read(): Int {
        val byte = ByteArray(1)
        return if (read(byte, 0, 1) < 0) -1 else byte[0].toInt() and 0xff
    }

    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        val count = readAtMediaPosition(position, buffer, offset, length)
        if (count > 0) {
            synchronized(this) {
                position += count
                if (mediaSize > 0L && position >= mediaSize) reachedEof = true
            }
        } else if (count < 0) {
            reachedEof = true
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
        check(!closed) { "Direct media stream is closed" }
        require(position >= 0L && (mediaSize <= 0L || position <= mediaSize)) {
            "Direct media read position is outside the stream"
        }
        if (length == 0) return 0
        if (mediaSize > 0L && position >= mediaSize) {
            return -1
        }

        synchronized(this) {
            val cacheOffset = (position - activeChunkStart).toInt()
            if (position in activeChunkStart until activeChunkStart + activeChunkBytes.size) {
                val count =
                    minOf(
                        length.toLong(),
                        (activeChunkBytes.size - cacheOffset).toLong(),
                        if (mediaSize > 0L) mediaSize - position else Long.MAX_VALUE,
                    ).toInt()
                if (count > 0) {
                    activeChunkBytes.copyInto(buffer, offset, cacheOffset, cacheOffset + count)
                    return count
                }
            }
        }

        val (start, bytes) = fetchChunk(position)

        synchronized(this) {
            if (closed) return -1
            activeChunkStart = start
            activeChunkBytes = bytes
            if (prefetchAhead && !closed) {
                triggerPrefetchLoop(start, bytes.size.toLong())
            }
            val cacheOffset = (position - activeChunkStart).toInt()
            val count =
                minOf(
                    length.toLong(),
                    (activeChunkBytes.size - cacheOffset).toLong(),
                    if (mediaSize > 0L) mediaSize - position else Long.MAX_VALUE,
                ).toInt()
            if (count <= 0) {
                return -1
            }
            activeChunkBytes.copyInto(buffer, offset, cacheOffset, cacheOffset + count)
            return count
        }
    }

    override fun seekToMediaPosition(position: Long): Long {
        require(position >= 0L) { "Direct media seek position cannot be negative" }
        check(!closed) { "Direct media stream is closed" }
        val bounded = if (mediaSize > 0L) position.coerceAtMost(mediaSize) else position
        synchronized(this) {
            this.position = bounded
            reachedEof = false
            if (bounded < activeChunkStart || bounded >= activeChunkStart + activeChunkBytes.size) {
                prefetchJob?.cancel()
                activeChunkBytes = byteArrayOf()
                activeChunkStart = -1L
                nextDemandChunkSize = minOf(STARTUP_PROBE_CHUNK_SIZE, chunkSize)
            }
        }
        return bounded
    }

    override fun skip(n: Long): Long {
        if (n <= 0L) return 0L
        val oldPos = position
        val target = position + n
        val bounded = if (mediaSize > 0L) minOf(target, mediaSize) else target
        seekToMediaPosition(bounded)
        return bounded - oldPos
    }

    override fun available(): Int {
        if (closed || (mediaSize > 0L && position >= mediaSize)) return 0
        val offsetInChunk = (position - activeChunkStart).toInt()
        val inChunk = (activeChunkBytes.size - offsetInChunk).coerceAtLeast(0)
        val bounded = if (mediaSize > 0L) minOf(inChunk.toLong(), mediaSize - position).toInt() else inChunk
        return bounded
    }

    override fun close() {
        synchronized(this) {
            if (closed) return
            closed = true
        }
        val isCompleted = reachedEof
        cacheCapture?.close(cacheCapturePrefetch, playbackCompleted = isCompleted)
        prefetchScope.cancel()
        synchronized(activeCalls) { activeCalls.toList() }.forEach(Call::cancel)
        synchronized(inFlightChunks) {
            inFlightChunks.values.toList().forEach { it.cancel() }
            inFlightChunks.clear()
        }
        synchronized(this) {
            synchronized(chunkCache) { chunkCache.clear() }
            prefetchJob?.cancel()
            activeChunkBytes = byteArrayOf()
            activeChunkStart = -1L
        }
    }

    private suspend fun getOrDownloadChunk(
        start: Long,
        sizeToFetch: Int,
        acquirePermit: Boolean = true,
    ): ByteArray =
        coroutineScope {
            val cached = synchronized(chunkCache) { chunkCache[start] }
            if (cached != null) return@coroutineScope cached

            val candidate =
                async(Dispatchers.IO, start = CoroutineStart.LAZY) {
                    downloadChunkSuspend(start, sizeToFetch, acquirePermit).also { putChunk(start, it) }
                }
            val deferred =
                synchronized(inFlightChunks) {
                    inFlightChunks[start]?.takeUnless { it.isCancelled } ?: candidate.also { pending ->
                        inFlightChunks[start] = pending
                        pending.invokeOnCompletion {
                            synchronized(inFlightChunks) {
                                if (inFlightChunks[start] === pending) inFlightChunks.remove(start)
                            }
                        }
                    }
                }
            if (deferred !== candidate) candidate.cancel()
            deferred.start()
            try {
                deferred.await()
            } catch (cancelled: CancellationException) {
                if (closed || !coroutineContext.isActive || deferred === candidate) throw cancelled
                getOrDownloadChunk(start, sizeToFetch, acquirePermit)
            }
        }

    private fun cachedChunkAt(position: Long): Pair<Long, ByteArray>? =
        synchronized(chunkCache) {
            val start =
                chunkCache.entries
                    .firstOrNull { (start, bytes) -> position >= start && position - start < bytes.size }
                    ?.key
                    ?: return@synchronized null
            start to checkNotNull(chunkCache[start])
        }

    private fun fetchChunk(requestedPosition: Long): Pair<Long, ByteArray> {
        cachedChunkAt(requestedPosition)?.let { return it }

        val effectiveSize =
            synchronized(this) {
                val sequential =
                    activeChunkStart >= 0L && requestedPosition == activeChunkStart + activeChunkBytes.size
                if (!sequential && hasFetchedChunk) {
                    prefetchJob?.cancel()
                    nextDemandChunkSize = minOf(STARTUP_PROBE_CHUNK_SIZE, chunkSize)
                }
                hasFetchedChunk = true
                nextDemandChunkSize = minOf(nextDemandChunkSize, demandChunkLimit)
                nextDemandChunkSize
            }
        val bytes = runBlocking { getOrDownloadChunk(requestedPosition, effectiveSize, acquirePermit = false) }
        synchronized(this) {
            if (nextDemandChunkSize == effectiveSize) {
                nextDemandChunkSize = minOf(demandChunkLimit.toLong(), effectiveSize.toLong() * 2L).toInt()
            }
        }
        return requestedPosition to bytes
    }

    private fun triggerPrefetchLoop(
        currentStart: Long,
        currentChunkLen: Long,
    ) {
        prefetchJob?.cancel()
        val prefetchStarts = mutableListOf<Long>()
        var nextStart = currentStart + currentChunkLen
        for (i in 0 until 3) {
            if (mediaSize > 0L && nextStart >= mediaSize) break
            val isCached = synchronized(chunkCache) { chunkCache.containsKey(nextStart) }
            if (!isCached) {
                prefetchStarts.add(nextStart)
            }
            nextStart += chunkSize.toLong()
        }
        if (prefetchStarts.isNotEmpty()) {
            prefetchJob =
                prefetchScope.launch {
                    for (start in prefetchStarts) {
                        if (closed) break
                        try {
                            getOrDownloadChunk(start, chunkSize)
                        } catch (_: CancellationException) {
                            break
                        } catch (_: Exception) {
                        }
                        if (prefetchStarts.size > 1) delay(50.milliseconds)
                    }
                }
        }
    }

    private fun putChunk(
        start: Long,
        bytes: ByteArray,
    ) {
        synchronized(chunkCache) {
            if (closed) return
            chunkCache[start] = bytes
            while (chunkCache.size > MAX_CACHED_CHUNKS) {
                val iterator = chunkCache.entries.iterator()
                iterator.next()
                iterator.remove()
            }
        }
    }

    private suspend fun downloadChunkSuspend(
        start: Long,
        sizeToFetch: Int = chunkSize,
        acquirePermit: Boolean = true,
    ): ByteArray {
        var end =
            if (mediaSize > 0L) {
                minOf(start + sizeToFetch.toLong() - 1L, mediaSize - 1L)
            } else {
                start + sizeToFetch.toLong() - 1L
            }
        var expected = (end - start + 1L).toInt()
        val reqNum = ++chunkRequestCount
        cacheCapture?.read(start, expected)?.let { bytes ->
            Logger.d("HttpRange", "Chunk #$reqNum LOCAL: range=$start-$end size=${bytes.size}")
            failure = null
            return bytes
        }
        val timeoutMs = if (activeChunkStart < 0L) startupRequestTimeoutMs else requestTimeoutMs
        val elapsedSec = (System.currentTimeMillis() - createdAtMs) / 1000.0
        var lastError: Throwable? = null

        Logger.d(
            "HttpRange",
            "Chunk #$reqNum START: range=$start-$end expected=$expected timeout=${timeoutMs}ms",
        )

        for (attempt in 0..1) {
            if (closed) break
            val fetchStart = System.nanoTime()
            var bodyDurationMs = 0L
            try {
                val bytes =
                    withContext(Dispatchers.IO) {
                        if (acquirePermit) chunkSemaphore.acquire()
                        try {
                            val request =
                                Request
                                    .Builder()
                                    .url(url)
                                    .apply {
                                        requestHeaders.forEach { (name, value) -> header(name, value) }
                                    }.header("Accept", "*/*")
                                    .header("Accept-Encoding", "identity")
                                    .header("Range", "bytes=$start-$end")
                                    .build()
                            val call = httpClient.newCall(request)
                            call.timeout().timeout(timeoutMs, TimeUnit.MILLISECONDS)
                            synchronized(activeCalls) {
                                check(!closed) { "Direct media stream is closed" }
                                activeCalls += call
                            }
                            try {
                                call.executeCancellable { response ->
                                    onResponseStatus(response.code)
                                    if (response.code != 200 && response.code != 206) {
                                        throw HttpMediaStatusException(response.code, response.header("Retry-After"))
                                    }
                                    val total: Long?
                                    val responseSize: Long
                                    if (response.code == 206) {
                                        val range =
                                            response
                                                .header("Content-Range")
                                                ?.let { Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+|\\*)").matchEntire(it.trim()) }
                                                ?.groupValues
                                                ?: throw HttpRangeProtocolException("Direct media response has no valid Content-Range")
                                        val first = range[1].toLongOrNull()
                                        val last = range[2].toLongOrNull()
                                        total = range[3].toLongOrNull()
                                        if (
                                            first != start || last == null || last < start || last > end ||
                                            (range[3] != "*" && (total == null || total <= last)) ||
                                            last != (total?.let { minOf(end, it - 1L) } ?: end)
                                        ) {
                                            throw HttpRangeProtocolException("Direct media response does not match the requested range")
                                        }
                                        responseSize = last - start + 1L
                                    } else {
                                        if (start != 0L || mediaSize > expected) {
                                            throw HttpRangeProtocolException("Direct media server ignored the requested range")
                                        }
                                        total = null
                                        responseSize = mediaSize.takeIf { it > 0L } ?: -1L
                                    }
                                    val source = response.body.source()
                                    val bodyStart = System.nanoTime()
                                    if (source.request(expected.toLong() + 1L)) {
                                        throw HttpRangeProtocolException("Direct media response exceeds the requested range")
                                    }
                                    val bytes = source.buffer.readByteArray()
                                    bodyDurationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - bodyStart)
                                    if (bytes.isEmpty() || (responseSize >= 0L && bytes.size.toLong() != responseSize)) {
                                        throw HttpRangeProtocolException("Direct media response is truncated")
                                    }
                                    val verifiedTotal = total ?: bytes.size.toLong().takeIf { response.code == 200 }
                                    verifiedTotal?.let { mediaSize = it }
                                    cacheCapture?.record(start, bytes, verifiedTotal ?: -1L)
                                    bytes
                                }
                            } finally {
                                synchronized(activeCalls) {
                                    activeCalls -= call
                                }
                            }
                        } finally {
                            if (acquirePermit) chunkSemaphore.release()
                        }
                    }

                val fetchDurationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - fetchStart)
                targetRequestDurationMs?.let { targetMs ->
                    // Header latency does not scale with range size; only body transfer determines throughput.
                    synchronized(this) {
                        demandChunkLimit = transferSizedChunkLimit(bytes.size, bodyDurationMs, targetMs, chunkSize)
                    }
                }
                if (fetchDurationMs > 0 && bytes.isNotEmpty()) {
                    PlaybackBandwidthMeter.recordSample(bytes.size.toLong(), fetchDurationMs)
                }

                Logger.d(
                    "HttpRange",
                    "Chunk #$reqNum OK: range=$start-$end size=${bytes.size}" +
                        " elapsed=${"%.2f".format(elapsedSec)}s totalSize=$mediaSize",
                )
                failure = null
                return bytes
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                Logger.w(
                    "HttpRange",
                    "Chunk #$reqNum ERROR (attempt $attempt):" +
                        " range=$start-$end ${e::class.simpleName}: ${e.message}",
                )
                if (closed) break
                if (targetRequestDurationMs != null && e is InterruptedIOException) {
                    expected = minOf(expected, STARTUP_PROBE_CHUNK_SIZE)
                    end = start + expected - 1L
                    synchronized(this) {
                        demandChunkLimit = expected
                        nextDemandChunkSize = minOf(nextDemandChunkSize, demandChunkLimit)
                    }
                }
                val isFatalClientError =
                    e is HttpMediaStatusException &&
                        e.playbackHttpStatusCode in 400..499 &&
                        e.playbackHttpStatusCode != 403 &&
                        e.playbackHttpStatusCode != 408
                val isExpirableStatus =
                    e is HttpMediaStatusException &&
                        e.playbackHttpStatusCode in setOf(401, 403, 404, 410, 416)
                if (isExpirableStatus && !streamExpired) {
                    streamExpired = true
                    onStreamExpired?.invoke(e.playbackHttpStatusCode)
                }
                if (isFatalClientError || isExpirableStatus) {
                    failure = e
                    throw e
                }
                if (attempt < 1 && !closed) {
                    try {
                        delay(((attempt + 1) * 200L).milliseconds)
                    } catch (_: CancellationException) {
                        break
                    }
                }
            }
        }

        val finalError =
            when (lastError) {
                is IOException -> lastError
                null -> IOException("Direct media range request closed or failed")
                else -> IOException("Direct media range request failed", lastError)
            }
        if (!closed && lastError is HttpRangeProtocolException) {
            PlaybackEventLog.error(
                PlaybackEventKind.NETWORK,
                "HttpRange",
                "Media server returned an invalid byte-range response",
                lastError,
            )
        }
        failure = finalError
        throw finalError
    }

    private suspend fun Call.executeCancellable(readResponse: (Response) -> ByteArray): ByteArray =
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { cancel() }
            try {
                val bytes = execute().use(readResponse)
                if (continuation.isActive) continuation.resume(bytes)
            } catch (error: Throwable) {
                if (continuation.isActive) continuation.resumeWithException(error)
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
}

internal fun transferSizedChunkLimit(
    bytes: Int,
    elapsedMs: Long,
    targetMs: Long,
    maxChunkSize: Int,
): Int =
    (bytes.toDouble() * targetMs / elapsedMs.coerceAtLeast(1L))
        .coerceIn(minOf(STARTUP_PROBE_CHUNK_SIZE, maxChunkSize).toDouble(), maxChunkSize.toDouble())
        .toInt()

internal class HttpMediaStatusException(
    override val playbackHttpStatusCode: Int,
    val retryAfter: String? = null,
) : IOException("Direct media range returned HTTP $playbackHttpStatusCode"),
    PlaybackHttpStatusCarrier

private class HttpRangeProtocolException(
    message: String,
) : IOException(message)
