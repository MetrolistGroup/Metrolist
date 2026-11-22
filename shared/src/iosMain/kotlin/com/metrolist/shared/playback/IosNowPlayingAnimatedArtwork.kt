@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package com.metrolist.shared.playback

import coil3.BitmapImage
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.maxBitmapSize
import coil3.size.Precision
import coil3.size.Size
import com.metrolist.shared.api.artwork.AnimatedArtworkByteRange
import com.metrolist.shared.api.artwork.AnimatedArtworkDownloadPlan
import com.metrolist.shared.api.artwork.MAX_ANIMATED_ARTWORK_VIDEO_BYTES
import com.metrolist.shared.api.artwork.isSupportedMp4SegmentUrl
import com.metrolist.shared.api.artwork.isTrustedAnimatedArtworkUrl
import com.metrolist.shared.api.artwork.isValidAnimatedArtworkRangeResponse
import com.metrolist.shared.api.artwork.parseAnimatedArtworkMediaPlaylist
import com.metrolist.shared.network.readTextLimited
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.state.GlobalYouTube
import com.metrolist.shared.utils.Logger
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.URLBuilder
import io.ktor.http.isSuccess
import io.ktor.http.takeFrom
import io.ktor.utils.io.readBuffer
import kotlinx.atomicfu.atomic
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.readByteArray
import okio.ByteString.Companion.encodeUtf8
import org.jetbrains.skia.Image
import org.jetbrains.skia.impl.use
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSFileHandle
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate
import platform.Foundation.NSFileSize
import platform.Foundation.NSNumber
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSUUID
import platform.Foundation.NSUserDomainMask
import platform.Foundation.closeFile
import platform.Foundation.create
import platform.Foundation.fileHandleForWritingAtPath
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.writeData
import platform.UIKit.UIImage
import kotlin.time.Duration.Companion.seconds

class IosNowPlayingAnimatedArtworkSpec(
    val squareArtworkId: String?,
    val squareVideoUrl: String?,
    val squarePreviewImageUrl: String?,
    val squarePreviewImage: UIImage?,
    val tallArtworkId: String?,
    val tallVideoUrl: String?,
    val tallPreviewImageUrl: String?,
    val tallPreviewImage: UIImage?,
)

interface IosNowPlayingAnimatedArtworkProvider {
    val isSupported: Boolean

    fun createEntries(
        spec: IosNowPlayingAnimatedArtworkSpec,
        squareFallbackImage: UIImage?,
    ): Map<String, Any>
}

object IosNowPlayingAnimatedArtworkBridge {
    private var provider: IosNowPlayingAnimatedArtworkProvider? = null

    val isSupported: Boolean
        get() = provider?.isSupported == true

    fun register(provider: IosNowPlayingAnimatedArtworkProvider) {
        this.provider = provider
    }

    internal fun createEntries(
        spec: IosNowPlayingAnimatedArtworkSpec,
        squareFallbackImage: UIImage?,
    ): Map<String, Any> = provider?.createEntries(spec, squareFallbackImage).orEmpty()
}

private val animatedArtworkFetchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

internal object IosNowPlayingAnimatedArtworkCache {
    private const val TAG = "IosAnimatedNowPlaying"
    private const val MAX_CACHE_BYTES = 64L * 1024L * 1024L
    private const val MAX_PLAYLIST_BYTES = 1024 * 1024
    private const val CHUNK_BYTES = 64L * 1024L

    private val mutex = Mutex()
    private val inFlight = mutableMapOf<String, CompletableDeferred<String?>>()
    private val activeTempFileNames = mutableSetOf<String>()
    private val activeFileNames = atomic(emptySet<String>())

    private val directory: String by lazy {
        val caches = NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true).first() as String
        "$caches/metrolist/now_playing_animated_artwork"
    }

    internal fun fileNameForArtworkId(artworkId: String): String = "${artworkId.encodeUtf8().sha256().hex()}.mp4"

    internal fun updateActiveArtworkIds(activeIds: Set<String>) {
        activeFileNames.value = activeIds.mapTo(mutableSetOf(), ::fileNameForArtworkId)
    }

    internal suspend fun clearInactive() =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val pinned = activeFileNames.value
                val fileManager = NSFileManager.defaultManager
                val entries =
                    fileManager
                        .contentsOfDirectoryAtPath(directory, error = null)
                        .orEmpty()
                        .filterIsInstance<String>()
                for (name in entries) {
                    if (name !in pinned && name !in activeTempFileNames) {
                        fileManager.removeItemAtPath("$directory/$name", error = null)
                    }
                }
            }
        }

    internal suspend fun getOrDownloadVideoPath(
        artworkId: String,
        sourceUrl: String,
        httpClient: HttpClient = GlobalYouTube.client,
    ): String? {
        if (!isTrustedAnimatedArtworkUrl(sourceUrl)) return null
        val fileName = fileNameForArtworkId(artworkId)
        val destination = "$directory/$fileName"

        val existingOrFlight =
            mutex.withLock {
                val fileManager = NSFileManager.defaultManager
                if (fileManager.fileExistsAtPath(destination) && fileSizeBytes(fileManager, destination) > 0L) {
                    touchFile(fileManager, destination)
                    return@withLock Result.success(destination)
                }
                inFlight[fileName]?.let { return@withLock Result.failure(InFlightHandle(it)) }
                if (!Settings.shared.isAnimatedArtworkAllowedOnNetwork()) {
                    return@withLock Result.success(null)
                }
                val deferred = CompletableDeferred<String?>()
                inFlight[fileName] = deferred
                Result.failure(OwnerHandle(deferred))
            }

        if (existingOrFlight.isSuccess) return existingOrFlight.getOrNull()
        val handle = existingOrFlight.exceptionOrNull()
        if (handle is InFlightHandle) return handle.deferred.await()

        val owner = (handle as OwnerHandle).deferred
        var resolvedPath: String? = null
        try {
            resolvedPath =
                withTimeoutOrNull(25.seconds) {
                    downloadVideoToCache(sourceUrl, destination, httpClient)
                }
            if (resolvedPath != null) {
                mutex.withLock {
                    evictExcessLocked()
                }
            }
            return resolvedPath
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Logger.w(TAG, "Failed to prepare lock screen animated artwork: ${error::class.simpleName}")
            return null
        } finally {
            mutex.withLock {
                if (inFlight[fileName] === owner) inFlight.remove(fileName)
            }
            owner.complete(resolvedPath)
        }
    }

    private suspend fun downloadVideoToCache(
        sourceUrl: String,
        destination: String,
        httpClient: HttpClient,
    ): String? {
        val fileManager = NSFileManager.defaultManager
        if (!ensureDirectory(fileManager)) return null
        val plan = resolveDownloadPlan(sourceUrl, httpClient) ?: return null
        val tempFileName = "${NSUUID().UUIDString}.mp4.tmp"
        val tempPath = "$directory/$tempFileName"
        mutex.withLock { activeTempFileNames += tempFileName }
        var published = false
        var handle: NSFileHandle? = null
        try {
            if (!fileManager.createFileAtPath(tempPath, contents = null, attributes = null)) return null
            val openHandle = NSFileHandle.fileHandleForWritingAtPath(tempPath) ?: return null
            handle = openHandle

            val succeeded =
                when (plan) {
                    is AnimatedArtworkDownloadPlan.SingleFile -> {
                        val expectedRange = plan.byteLength?.let { AnimatedArtworkByteRange(length = it, offset = 0L) }
                        val maxBytes = plan.byteLength ?: MAX_ANIMATED_ARTWORK_VIDEO_BYTES
                        streamIntoFile(
                            httpClient = httpClient,
                            url = plan.url,
                            expectedRange = expectedRange,
                            allowFullResponseAtZero = true,
                            file = openHandle,
                            maxBytes = maxBytes,
                        ) > 0L
                    }

                    is AnimatedArtworkDownloadPlan.Segments -> {
                        var totalWritten = 0L
                        var ok = true
                        for (segment in plan.segments) {
                            val remaining = MAX_ANIMATED_ARTWORK_VIDEO_BYTES - totalWritten
                            if (remaining <= 0L) {
                                ok = false
                                break
                            }
                            val written =
                                streamIntoFile(
                                    httpClient = httpClient,
                                    url = segment.url,
                                    expectedRange = segment.byteRange,
                                    allowFullResponseAtZero = false,
                                    file = openHandle,
                                    maxBytes = remaining,
                                )
                            if (written <= 0L) {
                                ok = false
                                break
                            }
                            totalWritten += written
                        }
                        ok && totalWritten > 0L
                    }
                }

            openHandle.closeFile()
            handle = null

            if (!succeeded || fileSizeBytes(fileManager, tempPath) <= 0L) {
                return null
            }

            if (fileManager.fileExistsAtPath(destination)) {
                touchFile(fileManager, destination)
                return destination
            }
            if (fileManager.moveItemAtPath(tempPath, toPath = destination, error = null)) {
                published = true
                return destination
            }
            return destination.takeIf { fileManager.fileExistsAtPath(it) && fileSizeBytes(fileManager, it) > 0L }
        } finally {
            handle?.closeFile()
            if (!published && fileManager.fileExistsAtPath(tempPath)) {
                fileManager.removeItemAtPath(tempPath, error = null)
            }
            withContext(NonCancellable) {
                mutex.withLock { activeTempFileNames.remove(tempFileName) }
            }
        }
    }

    private suspend fun resolveDownloadPlan(
        sourceUrl: String,
        httpClient: HttpClient,
    ): AnimatedArtworkDownloadPlan? {
        if (!isTrustedAnimatedArtworkUrl(sourceUrl)) return null
        val path =
            try {
                URLBuilder()
                    .takeFrom(sourceUrl)
                    .build()
                    .encodedPath
                    .lowercase()
            } catch (_: Exception) {
                return null
            }
        if (isSupportedMp4SegmentUrl(sourceUrl)) {
            return AnimatedArtworkDownloadPlan.SingleFile(sourceUrl)
        }
        if (!path.endsWith(".m3u8")) return null
        val response = httpClient.prepareGet(sourceUrl).readTextLimited(MAX_PLAYLIST_BYTES)
        val body = response.body
        if (!response.status.isSuccess() || body.isNullOrBlank()) return null
        return parseAnimatedArtworkMediaPlaylist(sourceUrl, body)
    }

    private suspend fun streamIntoFile(
        httpClient: HttpClient,
        url: String,
        expectedRange: AnimatedArtworkByteRange?,
        allowFullResponseAtZero: Boolean,
        file: NSFileHandle,
        maxBytes: Long,
    ): Long {
        if (!isTrustedAnimatedArtworkUrl(url)) return -1L
        return httpClient
            .prepareGet(url) {
                expectedRange?.let { header(HttpHeaders.Range, it.httpRangeHeader) }
            }.execute { response ->
                if (!response.status.isSuccess()) return@execute -1L
                if (expectedRange != null &&
                    !isValidAnimatedArtworkRangeResponse(
                        statusCode = response.status.value,
                        contentRange = response.headers[HttpHeaders.ContentRange],
                        expectedRange = expectedRange,
                        allowFullResponseAtZero = allowFullResponseAtZero,
                    )
                ) {
                    return@execute -1L
                }
                val effectiveMaxBytes = expectedRange?.length?.coerceAtMost(maxBytes) ?: maxBytes
                val declaredLength = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
                if (declaredLength != null &&
                    (declaredLength > effectiveMaxBytes || (expectedRange != null && declaredLength != expectedRange.length))
                ) {
                    return@execute -1L
                }
                val channel = response.bodyAsChannel()
                var totalWritten = 0L
                try {
                    while (!channel.isClosedForRead) {
                        val chunk = channel.readBuffer(CHUNK_BYTES).readByteArray()
                        if (chunk.isEmpty()) continue
                        totalWritten += chunk.size
                        if (totalWritten > effectiveMaxBytes) return@execute -1L
                        val wrote =
                            chunk.usePinned { pinned ->
                                val data = NSData.create(pinned.addressOf(0), chunk.size.toULong())
                                file.writeData(data, error = null)
                            }
                        if (!wrote) return@execute -1L
                    }
                    if (expectedRange != null && totalWritten != expectedRange.length) return@execute -1L
                    totalWritten
                } finally {
                    channel.cancel(null)
                }
            }
    }

    private fun ensureDirectory(fileManager: NSFileManager): Boolean {
        val created =
            fileManager.fileExistsAtPath(directory) ||
                fileManager.createDirectoryAtPath(
                    directory,
                    withIntermediateDirectories = true,
                    attributes = null,
                    error = null,
                )
        if (!created) return false
        NSURL.fileURLWithPath(directory).setResourceValue(true, NSURLIsExcludedFromBackupKey, error = null)
        return true
    }

    private fun evictExcessLocked() {
        val fileManager = NSFileManager.defaultManager
        val names =
            fileManager
                .contentsOfDirectoryAtPath(directory, error = null)
                .orEmpty()
                .filterIsInstance<String>()
        var totalSize = 0L
        val entries = mutableListOf<CachedEntry>()
        for (name in names) {
            val path = "$directory/$name"
            if (name.endsWith(".tmp")) {
                if (name !in activeTempFileNames) {
                    fileManager.removeItemAtPath(path, error = null)
                }
                continue
            }
            val attrs = fileManager.attributesOfItemAtPath(path, error = null)
            val size = (attrs?.get(NSFileSize) as? NSNumber)?.longLongValue ?: 0L
            val modified = (attrs?.get(NSFileModificationDate) as? NSDate)?.timeIntervalSince1970 ?: 0.0
            totalSize += size
            entries += CachedEntry(name, path, size, modified)
        }
        if (totalSize <= MAX_CACHE_BYTES) return
        entries.sortBy { it.modifiedAt }
        val pinned = activeFileNames.value
        for (entry in entries) {
            if (totalSize <= MAX_CACHE_BYTES) break
            // Never remove files referenced by the active MPMediaItemAnimatedArtwork in nowPlayingInfo.
            if (entry.name in pinned) continue
            if (fileManager.removeItemAtPath(entry.path, error = null)) {
                totalSize -= entry.size
            }
        }
    }

    private fun fileSizeBytes(
        fileManager: NSFileManager,
        path: String,
    ): Long = (fileManager.attributesOfItemAtPath(path, error = null)?.get(NSFileSize) as? NSNumber)?.longLongValue ?: 0L

    private fun touchFile(
        fileManager: NSFileManager,
        path: String,
    ) {
        fileManager.setAttributes(mapOf<Any?, Any?>(NSFileModificationDate to NSDate()), ofItemAtPath = path, error = null)
    }

    private class CachedEntry(
        val name: String,
        val path: String,
        val size: Long,
        val modifiedAt: Double,
    )

    private class InFlightHandle(
        val deferred: CompletableDeferred<String?>,
    ) : Exception()

    private class OwnerHandle(
        val deferred: CompletableDeferred<String?>,
    ) : Exception()
}

internal suspend fun preloadIosAnimatedArtworkPreviewImage(
    url: String,
    targetWidth: Int,
    targetHeight: Int,
): UIImage? {
    if (!isTrustedAnimatedArtworkUrl(url) || !Settings.shared.isAnimatedArtworkAllowedOnNetwork()) return null
    val width = targetWidth.coerceIn(1, 1024)
    val height = targetHeight.coerceIn(1, 1024)
    val context = PlatformContext.INSTANCE
    val request =
        ImageRequest
            .Builder(context)
            .data(url)
            .size(width, height)
            .maxBitmapSize(Size(width, height))
            .precision(Precision.INEXACT)
            .build()
    val bitmap =
        ((SingletonImageLoader.get(context).execute(request) as? SuccessResult)?.image as? BitmapImage)?.bitmap
            ?: return null
    return withContext(Dispatchers.Default) {
        Image.makeFromBitmap(bitmap).use { skiaImage ->
            skiaImage.encodeToData()?.use { data ->
                data.bytes.usePinned { bytes ->
                    UIImage.imageWithData(NSData.create(bytes.addressOf(0), data.size.toULong()))
                }
            }
        }
    }
}

fun fetchIosAnimatedArtworkPreviewImage(
    url: String,
    targetWidth: Int,
    targetHeight: Int,
    completion: (UIImage?) -> Unit,
) {
    animatedArtworkFetchScope.launch {
        val image =
            try {
                withTimeoutOrNull(10.seconds) {
                    preloadIosAnimatedArtworkPreviewImage(url, targetWidth, targetHeight)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
        completion(image)
    }
}

fun fetchIosAnimatedArtworkVideoFilePath(
    artworkId: String,
    sourceUrl: String,
    completion: (String?) -> Unit,
) {
    animatedArtworkFetchScope.launch {
        val path =
            try {
                IosNowPlayingAnimatedArtworkCache.getOrDownloadVideoPath(artworkId, sourceUrl)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
        completion(path)
    }
}
