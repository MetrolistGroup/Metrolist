@file:OptIn(InternalCoilApi::class)

package com.metrolist.shared.utils

import coil3.ImageLoader
import coil3.annotation.InternalCoilApi
import coil3.decode.DataSource
import coil3.disk.DiskCache
import coil3.intercept.Interceptor
import coil3.memory.MemoryCache
import coil3.network.CacheNetworkResponse
import coil3.network.HttpException
import coil3.request.CachePolicy
import coil3.request.ErrorResult
import coil3.request.ImageResult
import coil3.request.SuccessResult
import coil3.size.Dimension
import coil3.size.Precision
import com.metrolist.shared.youtube.playback.PlaybackNetworkStateStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.ByteString.Companion.encodeUtf8
import okio.IOException
import kotlin.uuid.Uuid

private const val VERIFIED_ARTWORK_SOURCE = "x-metrolist-artwork-source"

class ArtworkSizingInterceptor(
    private val isNetworkConnected: () -> Boolean = { PlaybackNetworkStateStore.current.connected },
    private val imageLoader: () -> ImageLoader,
) : Interceptor {
    private val refreshMutex = Mutex()
    private val refreshes = mutableMapOf<String, CompletableDeferred<Unit>>()

    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val request = chain.request
        val url = request.data as? String ?: return chain.proceed()
        val width = (chain.size.width as? Dimension.Pixels)?.px
        val height = (chain.size.height as? Dimension.Pixels)?.px
        val resolvedSize = maxOf(width ?: 0, height ?: 0)
        val canonicalUrl = canonicalArtworkUrl(url)
        val sizedUrl =
            if (resolvedSize == 0 || request.diskCacheKey == canonicalUrl) {
                canonicalUrl
            } else {
                bucketedArtworkUrl(url, resolvedSize)
            }
        val connected = isNetworkConnected()
        var cachedFallback: SuccessResult? = null

        suspend fun needsSourceRefresh(result: SuccessResult): Boolean {
            if (result.image.width <= 0 || result.image.height <= 0) return false
            if (result.dataSource == DataSource.NETWORK || sizedArtworkUrl(url, 1) == sizedArtworkUrl(url, 2)) return false
            val verified = hasVerifiedSource(result)
            return connected && !verified &&
                maxOf(result.image.width, result.image.height) < resolvedSize.coerceAtMost(MAX_ARTWORK_SIZE_PX)
        }
        if (resolvedSize > 0 && request.memoryCacheKey == null && request.diskCacheKey == null) {
            val loader = imageLoader()
            for (candidate in artworkCacheCandidates(url, resolvedSize)) {
                val inMemory =
                    request.memoryCachePolicy.readEnabled &&
                        loader.memoryCache?.get(MemoryCache.Key(candidate, request.memoryCacheKeyExtras)) != null
                val onDisk =
                    !inMemory && request.diskCachePolicy.readEnabled &&
                        withContext(Dispatchers.IO) {
                            loader.diskCache?.openSnapshot(candidate)?.use { true } == true
                        }
                if (!inMemory && !onDisk) continue
                val cached =
                    chain
                        .withRequest(
                            request
                                .newBuilder()
                                .data(candidate)
                                .precision(request.defined.precision ?: Precision.INEXACT)
                                .networkCachePolicy(CachePolicy.DISABLED)
                                .build(),
                        ).proceed()
                if (cached is SuccessResult) {
                    if (!needsSourceRefresh(cached)) return cached
                    cachedFallback = cached
                    break
                }
            }
        }
        val sizedRequest =
            chain.withRequest(
                request
                    .newBuilder()
                    .data(sizedUrl)
                    .apply {
                        if (!connected) networkCachePolicy(CachePolicy.DISABLED)
                    }.build(),
            )
        var result = if (cachedFallback != null) sizedRequest.refreshArtwork() else sizedRequest.proceed()
        if (cachedFallback == null && result is SuccessResult && needsSourceRefresh(result)) {
            cachedFallback = result
            result = sizedRequest.refreshArtwork()
        }
        if (result is SuccessResult && cachedFallback == null) rememberVerifiedSource(result, request.diskCachePolicy.writeEnabled)
        if (result !is SuccessResult) cachedFallback?.let { return it }
        if (result is SuccessResult || !request.diskCachePolicy.readEnabled || request.diskCacheKey != null) {
            return sizedRequest.withVideoArtworkFallback(result)
        }
        val loader = imageLoader()
        val isHttpFailure = (result as? ErrorResult)?.throwable is HttpException
        if (connected && !isHttpFailure && withContext(Dispatchers.IO) { loader.diskCache?.remove(sizedUrl) == true }) {
            result = sizedRequest.proceed()
            if (result is SuccessResult) {
                rememberVerifiedSource(result, request.diskCachePolicy.writeEnabled)
                return result
            }
        }
        for (candidate in (listOf(url) + artworkCacheCandidates(url, 1).reversed()).distinct()) {
            val onDisk = withContext(Dispatchers.IO) { loader.diskCache?.openSnapshot(candidate)?.use { true } == true }
            if (!onDisk) continue
            val cached =
                chain
                    .withRequest(
                        request
                            .newBuilder()
                            .data(candidate)
                            .precision(request.defined.precision ?: Precision.INEXACT)
                            .networkCachePolicy(CachePolicy.DISABLED)
                            .build(),
                    ).proceed()
            if (cached is SuccessResult) return cached
        }
        return sizedRequest.withVideoArtworkFallback(result)
    }

    private suspend fun hasVerifiedSource(result: SuccessResult): Boolean {
        val source =
            result.request.data
                .toString()
                .encodeUtf8()
                .sha256()
                .hex()
        val loader = imageLoader()
        if (result.dataSource == DataSource.MEMORY_CACHE) {
            val value = result.memoryCacheKey?.let { loader.memoryCache?.get(it) }
            return value != null && value.image === result.image && value.extras[VERIFIED_ARTWORK_SOURCE] == source
        }
        val key = result.diskCacheKey ?: return false
        val cache = loader.diskCache ?: return false
        val verified =
            withContext(Dispatchers.IO) {
                try {
                    cache.openSnapshot(key)?.use { snapshot ->
                        cache.fileSystem.read(snapshot.metadata) {
                            CacheNetworkResponse.readFrom(this).headers[VERIFIED_ARTWORK_SOURCE] == source
                        }
                    } == true
                } catch (_: IOException) {
                    false
                }
            }
        if (verified) rememberVerifiedSourceInMemory(result, source)
        return verified
    }

    private fun rememberVerifiedSourceInMemory(
        result: SuccessResult,
        source: String,
    ) {
        val key = result.memoryCacheKey ?: return
        val cache = imageLoader().memoryCache ?: return
        val value = cache[key] ?: return
        if (value.image === result.image) {
            cache[key] = MemoryCache.Value(value.image, value.extras + (VERIFIED_ARTWORK_SOURCE to source))
        }
    }

    private suspend fun rememberVerifiedSource(
        result: SuccessResult,
        writeDisk: Boolean,
    ) {
        if (result.dataSource != DataSource.NETWORK || result.image.width <= 0 || result.image.height <= 0) return
        val url = result.request.data.toString()
        if (sizedArtworkUrl(url, 1) == sizedArtworkUrl(url, 2)) return
        val source = url.encodeUtf8().sha256().hex()
        rememberVerifiedSourceInMemory(result, source)
        if (!writeDisk) return
        val key = result.diskCacheKey ?: return
        val cache = imageLoader().diskCache ?: return
        withContext(Dispatchers.IO) {
            try {
                cache.openSnapshot(key)?.use { snapshot ->
                    val response = cache.fileSystem.read(snapshot.metadata) { CacheNetworkResponse.readFrom(this) }
                    val editor = snapshot.closeAndOpenEditor() ?: return@use
                    try {
                        // Keep verification with the encoded entry so eviction and replacement remove it too.
                        val headers =
                            response.headers
                                .newBuilder()
                                .set(VERIFIED_ARTWORK_SOURCE, source)
                                .build()
                        cache.fileSystem.write(editor.metadata) { CacheNetworkResponse.writeTo(response.copy(headers = headers), this) }
                        editor.commit()
                    } catch (_: IOException) {
                        runCatching { editor.abort() }
                    }
                }
            } catch (_: IOException) {
                return@withContext
            }
        }
    }

    private suspend fun Interceptor.Chain.refreshArtwork(): ImageResult {
        val key = request.diskCacheKey ?: request.data.toString()
        while (true) {
            val pending = CompletableDeferred<Unit>()
            val active = refreshMutex.withLock { refreshes.getOrPut(key) { pending } }
            if (active !== pending) {
                active.await()
                continue
            }
            try {
                // A previous refresh may have repaired this entry while this request waited.
                val cached =
                    withRequest(
                        request
                            .newBuilder()
                            .diskCachePolicy(if (request.diskCachePolicy.readEnabled) CachePolicy.READ_ONLY else CachePolicy.DISABLED)
                            .networkCachePolicy(CachePolicy.DISABLED)
                            .build(),
                    ).proceed()
                if (cached is SuccessResult && hasVerifiedSource(cached)) return cached
                val result = refreshArtworkExclusively()
                if (result is SuccessResult) rememberVerifiedSource(result, request.diskCachePolicy.writeEnabled)
                return result
            } finally {
                withContext(NonCancellable) {
                    refreshMutex.withLock {
                        refreshes.remove(key)
                        pending.complete(Unit)
                    }
                }
            }
        }
    }

    private suspend fun Interceptor.Chain.refreshArtworkExclusively(): ImageResult {
        val refresh =
            request
                .newBuilder()
                .memoryCachePolicy(if (request.memoryCachePolicy.writeEnabled) CachePolicy.WRITE_ONLY else CachePolicy.DISABLED)
                .diskCachePolicy(CachePolicy.DISABLED)
                .build()
        val cache =
            imageLoader().diskCache?.takeIf { request.diskCachePolicy.writeEnabled }
                ?: return withRequest(refresh).proceed()
        val key = request.diskCacheKey ?: request.data.toString()
        val temporaryKey = "$key#refresh=${Uuid.random()}"
        var previous: DiskCache.Snapshot? = null
        try {
            withContext(Dispatchers.IO) { previous = cache.openSnapshot(key) }
            // Coil commits response bytes before decoding, so refresh a separate entry first.
            val result =
                withRequest(
                    refresh
                        .newBuilder()
                        .diskCacheKey(temporaryKey)
                        .diskCachePolicy(CachePolicy.WRITE_ONLY)
                        .memoryCachePolicy(CachePolicy.DISABLED)
                        .build(),
                ).proceed()
            if (result !is SuccessResult) return result
            val committed =
                withContext(Dispatchers.IO) {
                    cache.openSnapshot(temporaryKey)?.use { snapshot ->
                        val editor = previous?.closeAndOpenEditor() ?: cache.openEditor(key) ?: return@use false
                        try {
                            cache.fileSystem.copy(snapshot.metadata, editor.metadata)
                            cache.fileSystem.copy(snapshot.data, editor.data)
                            editor.commit()
                            true
                        } catch (_: IOException) {
                            runCatching { editor.abort() }
                            false
                        }
                    } ?: false
                }
            val refreshed = result.copy(request = request, diskCacheKey = key.takeIf { committed })
            if (!committed || !request.memoryCachePolicy.writeEnabled || imageLoader().memoryCache == null) return refreshed
            // Populate memory through Coil so it keeps the canonical disk key and sampling metadata.
            val cached =
                withRequest(
                    refresh
                        .newBuilder()
                        .diskCacheKey(key)
                        .diskCachePolicy(CachePolicy.READ_ONLY)
                        .networkCachePolicy(CachePolicy.DISABLED)
                        .build(),
                ).proceed()
            return if (cached is SuccessResult) {
                cached.copy(dataSource = result.dataSource)
            } else {
                refreshed
            }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                previous?.close()
                cache.remove(temporaryKey)
            }
        }
    }
}
