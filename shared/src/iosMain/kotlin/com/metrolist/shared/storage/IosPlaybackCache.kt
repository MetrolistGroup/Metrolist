@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package com.metrolist.shared.storage

import com.metrolist.shared.playback.IOS_OPUS_MAX_INPUT_BYTES
import com.metrolist.shared.playback.canRetireIosCaptureFor
import com.metrolist.shared.playback.canReuseCachedAudio
import com.metrolist.shared.playback.isWebmOpus
import com.metrolist.shared.playback.opus.OpusMp4Remuxer
import com.metrolist.shared.playback.requiresIosAudioCapture
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.youtube.playback.PlaybackResource
import io.ktor.client.HttpClient
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import platform.AVFoundation.AVAssetResourceLoader
import platform.AVFoundation.AVAssetResourceLoaderDelegateProtocol
import platform.AVFoundation.AVAssetResourceLoadingRequest
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.resourceLoader
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSFileHandle
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSFileSystemFreeSize
import platform.Foundation.NSNumber
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSURL
import platform.Foundation.NSURLErrorCannotLoadFromNetwork
import platform.Foundation.NSURLErrorDomain
import platform.Foundation.NSUUID
import platform.Foundation.NSUserDomainMask
import platform.Foundation.closeFile
import platform.Foundation.create
import platform.Foundation.fileHandleForUpdatingAtPath
import platform.Foundation.seekToFileOffset
import platform.UniformTypeIdentifiers.UTType
import platform.darwin.NSObject
import platform.darwin.dispatch_get_main_queue

internal class IosPlaybackCache(
    private val client: HttpClient,
    private val scope: CoroutineScope,
    private val onFilesChanged: () -> Unit = {},
    private val availableSpace: (String) -> Long = { path ->
        (NSFileManager.defaultManager.attributesOfFileSystemForPath(path, null)?.get(NSFileSystemFreeSize) as? NSNumber)
            ?.longLongValue ?: 0L
    },
) {
    private val captures = mutableListOf<IosPlaybackCapture>()
    private val files = NSFileManager.defaultManager
    val directory: String by lazy {
        val caches = NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true).first() as String
        "$caches/metrolist/playback"
    }
    private val initializedDirectory: String by lazy {
        directory.also { path ->
            check(
                !files.fileExistsAtPath(path) || files.removeItemAtPath(path, error = null),
            ) { "Could not clear abandoned playback captures" }
            check(files.createDirectoryAtPath(path, withIntermediateDirectories = true, attributes = null, error = null))
        }
    }

    suspend fun initialize() = withContext(Dispatchers.Default) { runCatching { initializedDirectory } }

    fun acquire(resource: PlaybackResource): IosPlaybackCapture? {
        val remuxOpus = resource.isWebmOpus()
        val required = resource.requiresIosAudioCapture
        val publish = Settings.shared.songCacheEnabled && Settings.shared.maxSongCacheSize != 0 && !remuxOpus && !resource.isSabr
        if ((!publish && !required) ||
            (!resource.isSabr && (!resource.isDirectFile || !isTrustedAudioDownloadUrl(resource.url))) ||
            (!remuxOpus && !isIosPlayableAudioMimeType(resource.mimeType))
        ) {
            return null
        }
        captures
            .filter { it.canRetireFor(resource) }
            .forEach { expired ->
                expired.mayPublish = false
                discard(expired)
            }
        if (captures.any { it.failedFor(resource) }) return null
        captures.lastOrNull { it.matches(resource) && (it.mayPublish || (required && !publish && it.users > 0)) }?.let {
            it.bind(resource)
            it.users++
            return it
        }
        val configured = Settings.shared.maxSongCacheSize.toLong() * AUDIO_RANGE_BYTES
        val budget =
            if (remuxOpus) {
                IOS_OPUS_MAX_INPUT_BYTES
            } else if (!publish ||
                configured < 0L
            ) {
                MAX_CAPTURE_BYTES
            } else {
                minOf(configured, MAX_CAPTURE_BYTES)
            }
        val captureDirectory = runCatching { initializedDirectory }.getOrNull() ?: return null
        val knownLength = (resource.sabrBootstrap?.contentLengthBytes ?: resource.contentLengthBytes)?.takeIf { it > 0L }
        if (knownLength != null && knownLength !in MINIMUM_AUDIO_BYTES..budget) return null
        var reservation: Long
        while (true) {
            val pending = captures.sumOf { it.pendingBytes }
            val available = availableSpace(captureDirectory) - DISK_RESERVE_BYTES - pending
            reservation = knownLength ?: minOf(budget - captures.sumOf { it.reservation }, available)
            if (captures.size < 4 && reservation in MINIMUM_AUDIO_BYTES..budget &&
                captures.sumOf { it.reservation } <= budget - reservation && available >= reservation
            ) {
                break
            }
            val unused = captures.firstOrNull { it.users == 0 } ?: return null
            discard(unused)
        }
        return runCatching {
            val path = "$captureDirectory/${NSUUID.UUID().UUIDString}.part"
            check(files.createFileAtPath(path, contents = null, attributes = null))
            val file =
                NSFileHandle.fileHandleForUpdatingAtPath(path) ?: run {
                    files.removeItemAtPath(path, error = null)
                    return null
                }
            IosPlaybackCapture(resource, reservation, path, file, client, scope, onFilesChanged, remuxOpus).also {
                it.mayPublish = publish
                it.users++
                captures.add(it)
            }
        }.getOrNull()
    }

    fun release(capture: IosPlaybackCapture?) {
        if (capture == null) return
        check(capture.users > 0)
        capture.users--
        if (capture.users == 0) {
            capture.cancelRequests()
            if (!capture.mayPublish) discard(capture)
        }
    }

    suspend fun acquireForPublication(resource: PlaybackResource): IosPlaybackCapture? =
        withContext(Dispatchers.Main) {
            captures.lastOrNull { it.owns(resource) }?.takeIf { it.mayPublish && !it.failed }?.also {
                it.resumeRequests()
                it.users++
            }
        }

    suspend fun invalidate() =
        withContext(Dispatchers.Main) {
            captures.toList().forEach {
                it.mayPublish = false
                if (it.users == 0) discard(it)
            }
        }

    fun close() {
        captures.toList().forEach {
            it.mayPublish = false
            discard(it)
        }
    }

    private fun discard(capture: IosPlaybackCapture) {
        captures.remove(capture)
        capture.cancelRequests()
        capture.close()
    }

    companion object {
        private const val MAX_CAPTURE_BYTES = 512L * 1024 * 1024
        internal const val DISK_RESERVE_BYTES = 16L * 1024 * 1024
    }
}

internal class IosPlaybackCapture(
    private val resource: PlaybackResource,
    maximumBytes: Long,
    private val path: String,
    private val file: NSFileHandle,
    client: HttpClient,
    private val scope: CoroutineScope,
    private val onFilesChanged: () -> Unit,
    remuxOpus: Boolean = false,
) : NSObject(),
    AVAssetResourceLoaderDelegateProtocol {
    var users = 0
    val reservation: Long get() = source.reservation
    val pendingBytes: Long get() = source.pendingBytes
    private val publicationAllowed = MutableStateFlow(true)
    var mayPublish: Boolean
        get() = publicationAllowed.value
        set(value) {
            publicationAllowed.value = value
        }
    var failed = false
        private set
    var failure: Throwable? = null
        private set
    private val currentResource = MutableStateFlow(resource)
    private val resources = mutableListOf(resource)
    private val requests = mutableMapOf<AVAssetResourceLoadingRequest, Job>()
    private val files = NSFileManager.defaultManager
    private val source =
        ReadThroughAudio(
            client,
            resource,
            maximumBytes,
            CoroutineScope(scope.coroutineContext + Dispatchers.Default),
            write = { offset, bytes, length ->
                val free =
                    (files.attributesOfFileSystemForPath(path.substringBeforeLast('/'), null)?.get(NSFileSystemFreeSize) as? NSNumber)
                        ?.longLongValue ?: 0L
                check(free - IosPlaybackCache.DISK_RESERVE_BYTES >= length) { "Insufficient space for playback capture" }
                file.seekToFileOffset(offset.toULong())
                val data = bytes.usePinned { NSData.create(it.addressOf(0), length.toULong()) }
                check(file.writeData(data, error = null)) { "Could not capture audio" }
            },
            read = { offset, length ->
                file.seekToFileOffset(offset.toULong())
                val data = checkNotNull(file.readDataUpToLength(length.toULong(), error = null))
                data.bytes?.readBytes(data.length.toInt()) ?: ByteArray(0)
            },
            resourceForRequest = { currentResource.value },
            onRequestFinished = onFilesChanged,
        )

    // shortcut: buffer up to 32 MiB of input, use progressive fragments to support larger files or faster startup.
    private val remuxed =
        if (remuxOpus) {
            scope.async(Dispatchers.Default, start = CoroutineStart.LAZY) {
                runCatching {
                    val size = source.information().second
                    require(size in MINIMUM_AUDIO_BYTES..IOS_OPUS_MAX_INPUT_BYTES) { "Opus input exceeds the remux buffer limit" }
                    source.copyComplete { _, _ -> }
                    val input = ByteArray(size.toInt())
                    var offset = 0
                    while (offset < input.size) {
                        val bytes = source.readAt(offset.toLong(), minOf(64 * 1024, input.size - offset))
                        check(bytes.isNotEmpty()) { "Incomplete Opus input" }
                        bytes.copyInto(input, offset)
                        offset += bytes.size
                    }
                    OpusMp4Remuxer.remux(input)
                }.onFailure { if (it is CancellationException) throw it }
            }
        } else {
            null
        }

    internal suspend fun playbackInformation(): Pair<String?, Long> =
        remuxed?.await()?.getOrThrow()?.let { "audio/mp4" to it.size.toLong() } ?: source.information()

    internal suspend fun playbackBytes(
        offset: Long,
        length: Int,
    ): ByteArray {
        val bytes = remuxed?.await()?.getOrThrow() ?: return source.readAt(offset, length)
        require(offset in 0..bytes.size.toLong() && length > 0) { "Invalid Opus byte range" }
        return bytes.copyOfRange(offset.toInt(), (offset + minOf(length.toLong(), bytes.size - offset)).toInt())
    }

    val asset =
        AVURLAsset(uRL = checkNotNull(NSURL.URLWithString("metrolist-audio://${NSUUID.UUID().UUIDString}/audio")), options = null).also {
            it.resourceLoader.setDelegate(this, dispatch_get_main_queue())
        }

    fun bind(resource: PlaybackResource) {
        resumeRequests()
        currentResource.value = resource
        if (resource !in resources) resources.add(resource)
        while (resources.size > 8) resources.removeAt(0)
    }

    fun owns(resource: PlaybackResource): Boolean = resource in resources

    fun canRetireFor(replacement: PlaybackResource): Boolean = failed && currentResource.value.canRetireIosCaptureFor(replacement, failure)

    fun markFailed(cause: Throwable? = null) {
        if (cause != null) failure = cause
        failed = true
        cancelRequests()
        close()
    }

    fun failedFor(other: PlaybackResource): Boolean =
        failed && resource.isSabr == other.isSabr && (!resource.isSabr || resource.profileId == other.profileId) &&
            resource.videoId == other.videoId && resource.itag == other.itag &&
            resource.mimeType == other.mimeType && resource.codecs == other.codecs

    fun matches(other: PlaybackResource): Boolean =
        resource.isSabr == other.isSabr && resource.videoId == other.videoId &&
            (
                resource == other ||
                    (
                        !resource.isSabr && resource.contentLengthBytes?.let { size ->
                            canReuseCachedAudio(other, resource.itag, resource.mimeType, resource.codecs, size)
                        } == true
                    )
            )

    @ObjCSignatureOverride
    override fun resourceLoader(
        resourceLoader: AVAssetResourceLoader,
        shouldWaitForLoadingOfRequestedResource: AVAssetResourceLoadingRequest,
    ): Boolean {
        if (users == 0) return false
        val request = shouldWaitForLoadingOfRequestedResource
        val job =
            scope.launch(Dispatchers.Main, start = CoroutineStart.LAZY) {
                try {
                    val (mimeType, size) = withContext(Dispatchers.Default) { playbackInformation() }
                    request.contentInformationRequest?.let {
                        it.contentType = mimeType?.substringBefore(';')?.let { mime -> UTType.typeWithMIMEType(mime)?.identifier }
                        it.contentLength = size
                        it.byteRangeAccessSupported = true
                    }
                    request.dataRequest?.let { data ->
                        var offset = maxOf(data.requestedOffset, data.currentOffset)
                        val end =
                            if (data.requestsAllDataToEndOfResource) {
                                size
                            } else {
                                data.requestedOffset + minOf(data.requestedLength, size - data.requestedOffset)
                            }
                        while (offset < end) {
                            val bytes = withContext(Dispatchers.Default) { playbackBytes(offset, minOf(64 * 1024L, end - offset).toInt()) }
                            check(bytes.isNotEmpty()) { "Captured audio ended early" }
                            if (request.cancelled) return@launch
                            data.respondWithData(bytes.usePinned { NSData.create(it.addressOf(0), bytes.size.toULong()) })
                            offset += bytes.size
                        }
                    }
                    if (!request.cancelled) request.finishLoading()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    markFailed(error)
                    if (!request.cancelled) request.finishLoadingWithError(NSError(NSURLErrorDomain, NSURLErrorCannotLoadFromNetwork, null))
                } finally {
                    requests.remove(request)
                }
            }
        requests[request] = job
        job.start()
        return true
    }

    @ObjCSignatureOverride
    override fun resourceLoader(
        resourceLoader: AVAssetResourceLoader,
        didCancelLoadingRequest: AVAssetResourceLoadingRequest,
    ) {
        requests.remove(didCancelLoadingRequest)?.cancel()
    }

    fun cancelRequests() {
        requests.values.toList().forEach { it.cancel() }
        requests.clear()
        if (remuxed?.isCompleted == false) remuxed.cancel()
        source.cancelPendingRequests()
    }

    fun resumeRequests() = source.resumeRequests()

    suspend fun copyCompleted(destination: String): Pair<String?, Long> {
        var result: Pair<String?, Long>? = null
        source.copyComplete(mayContinue = { mayPublish }) { mimeType, size ->
            val actual = (files.attributesOfItemAtPath(path, null)?.get(NSFileSize) as? NSNumber)?.longLongValue
            check(actual == size) { "Captured audio length does not match" }
            check(files.copyItemAtPath(path, toPath = destination, error = null)) { "Could not copy captured audio" }
            result = mimeType to size
        }
        return checkNotNull(result)
    }

    fun close() {
        try {
            source.close {
                file.closeFile()
                files.removeItemAtPath(path, error = null)
            }
        } finally {
            onFilesChanged()
        }
    }
}
