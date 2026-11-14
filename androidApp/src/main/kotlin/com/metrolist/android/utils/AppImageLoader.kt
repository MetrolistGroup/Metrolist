@file:OptIn(ExperimentalCoilApi::class)

/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.android.utils

import android.content.Context
import coil3.ImageLoader
import coil3.annotation.ExperimentalCoilApi
import coil3.memory.MemoryCache
import coil3.network.DeDupeConcurrentRequestStrategy
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.CachePolicy
import com.metrolist.shared.network.PublicArtworkDns
import com.metrolist.shared.network.sharedOkHttpClient
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.storage.ResizableImageDiskCache
import com.metrolist.shared.utils.ArtworkSizingInterceptor
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.Path.Companion.toPath
import okio.buffer
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.Interceptor as OkHttpInterceptor

private const val MAX_REMOTE_ARTWORK_BYTES = 12L * 1024 * 1024

fun createAppImageLoader(context: Context): ImageLoader {
    val appContext = context.applicationContext
    val maxImageCacheSize = Settings.shared.maxImageCacheSize
    val memoryCacheRatio = 0.25
    val okHttpClient =
        sharedOkHttpClient
            .newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .dns(PublicArtworkDns)
            .addNetworkInterceptor(ResponseSizeLimitInterceptor)
            .build()

    lateinit var imageLoader: ImageLoader
    imageLoader =
        ImageLoader
            .Builder(appContext)
            .components {
                add(ArtworkSizingInterceptor { imageLoader })
                add(
                    OkHttpNetworkFetcherFactory(
                        concurrentRequestStrategy = { DeDupeConcurrentRequestStrategy() },
                        callFactory = { okHttpClient },
                    ),
                )
            }.memoryCache {
                MemoryCache
                    .Builder()
                    .maxSizeBytes((Runtime.getRuntime().maxMemory() * memoryCacheRatio).toLong())
                    .build()
            }.diskCache {
                ResizableImageDiskCache(
                    appContext.cacheDir
                        .resolve("coil")
                        .absolutePath
                        .toPath(),
                    maxImageCacheSize * 1024 * 1024L,
                )
            }.diskCachePolicy(CachePolicy.ENABLED)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .build()
    return imageLoader
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
