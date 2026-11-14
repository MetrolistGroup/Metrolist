package com.metrolist.shared.storage

import com.metrolist.shared.playback.fetchSabrAudio
import com.metrolist.shared.youtube.playback.PlaybackResource
import io.ktor.client.HttpClient
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

internal class ReadThroughAudio(
    private val client: HttpClient,
    private val resource: PlaybackResource,
    private val maxBytes: Long,
    scope: CoroutineScope,
    private val write: (Long, ByteArray, Int) -> Unit,
    private val read: (Long, Int) -> ByteArray,
    private val resourceForRequest: () -> PlaybackResource = { resource },
    private val onRequestFinished: () -> Unit = {},
) {
    private val job = SupervisorJob(scope.coroutineContext[Job])
    private val fetchScope = CoroutineScope(scope.coroutineContext + job)
    private val lock = SynchronizedObject()
    private val slots = Semaphore(4)
    private val requests = mutableMapOf<Long, Request>()
    private val writtenEnds = mutableMapOf<Long, Long>()
    private val changes = MutableStateFlow(0L)
    private val chunkSize = resource.rangeChunkSizeBytes.coerceIn(1L, AUDIO_RANGE_BYTES)
    private var total = resource.contentLengthBytes?.takeIf { it > 0L }
    private var mimeType = resource.mimeType
    private var wholeResponse = resource.isSabr
    private var closed = false
    private var acceptingRequests = true

    val reservation: Long get() = synchronized(lock) { if (closed) 0L else total ?: maxBytes }

    val pendingBytes: Long
        get() =
            synchronized(lock) {
                var end = 0L
                var written = 0L
                for ((start, rangeEnd) in writtenEnds.toList().sortedBy { it.first }) {
                    written += (rangeEnd - maxOf(start, end)).coerceAtLeast(0L)
                    end = maxOf(end, rangeEnd)
                }
                (reservation - written).coerceAtLeast(0L)
            }

    private class Request(
        val start: Long,
    ) {
        val metadata = CompletableDeferred<Pair<String?, Long>>()
        lateinit var result: Deferred<Unit>
        var availableEnd = start
        var failure: Throwable? = null
    }

    suspend fun information(): Pair<String?, Long> = request(0L).metadata.await()

    suspend fun readAt(
        offset: Long,
        length: Int,
    ): ByteArray {
        require(offset >= 0L && length > 0)
        val size = information().second
        if (offset >= size) return ByteArray(0)
        val request = request(offset / chunkSize * chunkSize)
        while (true) {
            val revision = changes.value
            val bytes =
                synchronized(lock) {
                    check(!closed)
                    request.failure?.let { throw it }
                    val count = minOf(length.toLong(), size - offset, request.availableEnd - offset)
                    if (count <= 0L) {
                        null
                    } else {
                        read(offset, count.toInt()).also {
                            check(it.size == count.toInt()) { "Captured audio is incomplete" }
                        }
                    }
                }
            if (bytes != null) return bytes
            changes.first { it != revision }
        }
    }

    suspend fun copyComplete(
        mayContinue: () -> Boolean = { true },
        copy: (String?, Long) -> Unit,
    ) {
        check(mayContinue()) { "Playback cache publication was invalidated" }
        val size = information().second
        var offset = 0L
        while (offset < size) {
            check(mayContinue()) { "Playback cache publication was invalidated" }
            request(offset).result.await()
            offset += if (wholeResponse) size - offset else minOf(chunkSize, size - offset)
        }
        val type =
            synchronized(lock) {
                check(!closed && mayContinue()) { "Playback cache publication was invalidated" }
                check(isCompleteAudioDownload(size, resource.contentLengthBytes?.takeIf { it > 0L }))
                mimeType
            }
        copy(type, size)
    }

    fun cancelPendingRequests() =
        synchronized(lock) {
            acceptingRequests = false
            requests.values.forEach {
                if (!it.result.isCompleted) {
                    it.result.cancel()
                    it.metadata.cancel()
                }
            }
        }

    fun resumeRequests() = synchronized(lock) { acceptingRequests = true }

    fun close(closeFile: () -> Unit) =
        synchronized(lock) {
            if (closed) return@synchronized
            closed = true
            acceptingRequests = false
            job.cancel()
            requests.values.forEach { it.metadata.cancel() }
            changes.value++
            // All file access holds this lock, so eviction can reclaim disk space synchronously.
            closeFile()
        }

    private suspend fun request(offset: Long): Request {
        val context = currentCoroutineContext()
        return synchronized(lock) {
            context.ensureActive()
            check(job.isActive && acceptingRequests) { "Playback capture is closed or unused" }
            val start = if (wholeResponse) 0L else offset
            requests[start]?.takeUnless { it.result.isCancelled }?.let { return@synchronized it }
            val request = Request(start)
            request.result =
                fetchScope.async(start = CoroutineStart.LAZY) {
                    try {
                        slots.withPermit {
                            val expected = synchronized(lock) { total }
                            val currentResource = resourceForRequest().copy(rangeChunkSizeBytes = chunkSize)
                            val writeBytes: suspend (Long, ByteArray, Int) -> Unit = { position, bytes, length ->
                                val context = currentCoroutineContext()
                                synchronized(lock) {
                                    context.ensureActive()
                                    check(!closed)
                                    write(position, bytes, length)
                                    writtenEnds[start] = maxOf(writtenEnds[start] ?: start, position + length)
                                    request.availableEnd = position + length
                                    changes.value++
                                }
                            }
                            val result =
                                if (currentResource.isSabr) {
                                    // shortcut: prepare the whole SABR file, use progressive fragments when startup latency requires it.
                                    val size = fetchSabrAudio(client, currentResource, maxBytes, write = writeBytes)
                                    AudioRangeResult(currentResource.mimeType, size, size, partial = false)
                                } else {
                                    fetchAudioRange(
                                        client,
                                        currentResource,
                                        start,
                                        expected,
                                        maxBytes,
                                        onResponse = { mime, size, partial ->
                                            // AVFoundation needs a length before decoding; leave unbounded streams to native HTTPS.
                                            checkNotNull(size) { "Playback capture requires an audio length" }
                                            val context = currentCoroutineContext()
                                            synchronized(lock) {
                                                context.ensureActive()
                                                check(!closed)
                                                if (start == 0L) wholeResponse = !partial
                                                mimeType = mime ?: mimeType
                                                total = size
                                                request.metadata.complete(mimeType to size)
                                            }
                                        },
                                        write = writeBytes,
                                    )
                                }
                            synchronized(lock) {
                                check(isCompleteAudioDownload(result.total, total)) { "Captured audio length does not match" }
                                total = result.total
                                mimeType = result.mimeType ?: mimeType
                                request.metadata.complete(mimeType to result.total)
                            }
                        }
                    } catch (error: Throwable) {
                        synchronized(lock) {
                            request.failure = error
                            request.metadata.completeExceptionally(error)
                            changes.value++
                        }
                        throw error
                    } finally {
                        onRequestFinished()
                    }
                }
            requests[start] = request
            request.result.start()
            request
        }
    }
}
