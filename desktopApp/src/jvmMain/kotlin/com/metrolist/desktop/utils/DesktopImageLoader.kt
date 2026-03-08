@file:OptIn(ExperimentalCoilApi::class)

package com.metrolist.desktop.utils

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.annotation.ExperimentalCoilApi
import coil3.intercept.Interceptor
import coil3.memory.MemoryCache
import coil3.network.DeDupeConcurrentRequestStrategy
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.CachePolicy
import coil3.request.ImageResult
import coil3.request.SuccessResult
import coil3.size.Dimension
import com.metrolist.shared.network.PublicArtworkDns
import com.metrolist.shared.network.sharedOkHttpClient
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.storage.ResizableImageDiskCache
import com.metrolist.shared.utils.XdgUtils
import com.metrolist.shared.utils.artworkCacheCandidates
import com.metrolist.shared.utils.bucketedArtworkUrl
import com.metrolist.shared.utils.canonicalArtworkUrl
import com.metrolist.shared.utils.evictArtworkCache
import com.metrolist.shared.utils.withVideoArtworkFallback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.Path.Companion.toPath
import okio.buffer
import java.io.File
import java.io.IOException
import okhttp3.Interceptor as OkHttpInterceptor

private const val MAX_REMOTE_ARTWORK_BYTES = 12L * 1024 * 1024
private const val MEMORY_CACHE_BYTES = 16L * 1024 * 1024

object DesktopImageLoader {
    private var imageLoader: ImageLoader? = null

    @Synchronized
    fun create(context: PlatformContext): ImageLoader {
        imageLoader?.let { return it }
        val cacheDir = File(XdgUtils.getXdgCacheHome(), "metrolist/image_cache").also { check(it.mkdirs() || it.isDirectory) }

        return build(context, cacheDir).also { imageLoader = it }
    }

    internal fun build(
        context: PlatformContext,
        cacheDir: File,
    ): ImageLoader {
        lateinit var loader: ImageLoader
        loader =
            ImageLoader
                .Builder(context)
                .components {
                    add(ArtworkSizingInterceptor { loader })
                    add(
                        OkHttpNetworkFetcherFactory(
                            concurrentRequestStrategy = { DeDupeConcurrentRequestStrategy() },
                            callFactory = {
                                sharedOkHttpClient
                                    .newBuilder()
                                    .dns(PublicArtworkDns)
                                    .addNetworkInterceptor(ResponseSizeLimitInterceptor)
                                    .build()
                            },
                        ),
                    )
                }.memoryCache {
                    MemoryCache
                        .Builder()
                        .maxSizeBytes(MEMORY_CACHE_BYTES)
                        .build()
                }.diskCache {
                    ResizableImageDiskCache(cacheDir.absolutePath.toPath(), Settings.shared.maxImageCacheSize * 1024L * 1024L)
                }.memoryCachePolicy(CachePolicy.ENABLED)
                .diskCachePolicy(CachePolicy.ENABLED)
                .build()
        return loader
    }

    @Synchronized
    fun evictArtwork(url: String?) {
        imageLoader?.evictArtworkCache(url)
    }

    @Synchronized
    fun clearCache() {
        imageLoader?.memoryCache?.clear()
        imageLoader?.diskCache?.clear()
    }

    @Synchronized
    fun release() {
        imageLoader?.shutdown()
        imageLoader?.diskCache?.shutdown()
        imageLoader = null
    }
}

internal class ArtworkSizingInterceptor(
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
        val sizedChain = if (sizedUrl == url) chain else chain.withRequest(request.newBuilder().data(sizedUrl).build())
        return sizedChain.withVideoArtworkFallback(sizedChain.proceed())
    }
}

private object ResponseSizeLimitInterceptor : OkHttpInterceptor {
    override fun intercept(chain: OkHttpInterceptor.Chain): okhttp3.Response {
        val request = chain.request()
        val url = request.url
        if (!url.isHttps || url.port != 443 || url.username.isNotEmpty() || url.password.isNotEmpty()) {
            throw IOException("Remote artwork URL is not trusted")
        }
        val response = chain.proceed(request)
        val body = response.body
        if (body.contentLength() > MAX_REMOTE_ARTWORK_BYTES) {
            response.close()
            throw IOException("Remote artwork exceeds the response size limit")
        }

        var bytesRead = 0L
        val limitedSource =
            object : ForwardingSource(body.source()) {
                override fun read(
                    sink: Buffer,
                    byteCount: Long,
                ): Long {
                    val read = super.read(sink, minOf(byteCount, MAX_REMOTE_ARTWORK_BYTES + 1 - bytesRead))
                    if (read > 0L) bytesRead += read
                    if (bytesRead > MAX_REMOTE_ARTWORK_BYTES) {
                        response.close()
                        throw IOException("Remote artwork exceeds the response size limit")
                    }
                    return read
                }
            }.buffer()
        return response
            .newBuilder()
            .body(limitedSource.asResponseBody(body.contentType(), body.contentLength()))
            .build()
    }
}
