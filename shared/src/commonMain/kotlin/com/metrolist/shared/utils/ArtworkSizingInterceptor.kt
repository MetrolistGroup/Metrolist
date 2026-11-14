package com.metrolist.shared.utils

import coil3.ImageLoader
import coil3.intercept.Interceptor
import coil3.memory.MemoryCache
import coil3.network.HttpException
import coil3.request.CachePolicy
import coil3.request.ErrorResult
import coil3.request.ImageResult
import coil3.request.SuccessResult
import coil3.size.Dimension
import coil3.size.Precision
import com.metrolist.shared.youtube.playback.PlaybackNetworkStateStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

class ArtworkSizingInterceptor(
    private val isNetworkConnected: () -> Boolean = { PlaybackNetworkStateStore.current.connected },
    private val imageLoader: () -> ImageLoader,
) : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val request = chain.request
        val url = request.data as? String ?: return chain.proceed()
        val width = (chain.size.width as? Dimension.Pixels)?.px
        val height = (chain.size.height as? Dimension.Pixels)?.px
        val resolvedSize = maxOf(width ?: 0, height ?: 0)
        val sizedUrl = if (resolvedSize > 0) bucketedArtworkUrl(url, resolvedSize) else canonicalArtworkUrl(url)
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
                                .networkCachePolicy(CachePolicy.DISABLED)
                                .build(),
                        ).proceed()
                if (cached is SuccessResult) return cached
            }
        }
        val connected = isNetworkConnected()
        val sizedRequest =
            chain.withRequest(
                request
                    .newBuilder()
                    .data(sizedUrl)
                    .apply {
                        if (!connected) networkCachePolicy(CachePolicy.DISABLED)
                    }.build(),
            )
        var result = sizedRequest.proceed()
        if (result is SuccessResult || !request.diskCachePolicy.readEnabled || request.diskCacheKey != null) {
            return sizedRequest.withVideoArtworkFallback(result)
        }
        val loader = imageLoader()
        val isHttpFailure = (result as? ErrorResult)?.throwable is HttpException
        if (connected && !isHttpFailure && withContext(Dispatchers.IO) { loader.diskCache?.remove(sizedUrl) == true }) {
            result = sizedRequest.proceed()
            if (result is SuccessResult) return result
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
                            .precision(Precision.INEXACT)
                            .networkCachePolicy(CachePolicy.DISABLED)
                            .build(),
                    ).proceed()
            if (cached is SuccessResult) return cached
        }
        return sizedRequest.withVideoArtworkFallback(result)
    }
}
