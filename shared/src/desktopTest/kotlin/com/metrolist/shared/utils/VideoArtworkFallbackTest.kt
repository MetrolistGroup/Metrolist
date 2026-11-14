package com.metrolist.shared.utils

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.size.Precision
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Path.Companion.toOkioPath
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VideoArtworkFallbackTest {
    @Test
    fun unavailableLargeArtworkFallsBackWithoutPoisoningItsCache() = checkArtwork(404)

    @Test
    fun undecodableLargeArtworkFallsBackWithoutPoisoningItsCache() = checkArtwork(200)

    @Test
    fun ordinaryRequestsKeepTheCached404AndReuseTheFallback() = checkArtwork(404, explicitCacheKeys = false)

    private fun checkArtwork(
        initialStatus: Int,
        explicitCacheKeys: Boolean = true,
    ) {
        val smallUrl = "https://i.ytimg.com/vi/video/mqdefault.jpg"
        val largeUrl = "https://i.ytimg.com/vi/video/maxresdefault.jpg"
        val smallImage = png(320, 180)
        val largeImage = png(1280, 720)
        val fetched = mutableListOf<String>()
        var largeAvailable = false
        val client =
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    val request = chain.request()
                    val url = request.url.toString()
                    fetched += url
                    val large = url == largeUrl
                    val code = if (large && !largeAvailable) initialStatus else 200
                    val bytes =
                        if (large) {
                            if (largeAvailable) largeImage else ByteArray(0)
                        } else {
                            smallImage
                        }
                    Response
                        .Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(code)
                        .message(if (code == 200) "OK" else "Not Found")
                        .header("Cache-Control", "max-age=3600")
                        .body(bytes.toResponseBody())
                        .build()
                }.build()
        val directory = Files.createTempDirectory("video-artwork").toFile()
        var loader: ImageLoader? = null
        try {
            loader =
                ImageLoader
                    .Builder(PlatformContext.INSTANCE)
                    .components {
                        add(ArtworkSizingInterceptor(isNetworkConnected = { true }) { requireNotNull(loader) })
                        add(OkHttpNetworkFetcherFactory(callFactory = { client }))
                    }.memoryCache { MemoryCache.Builder().maxSizeBytes(8L * 1024 * 1024).build() }
                    .diskCache { DiskCache.Builder().directory(directory.toOkioPath()).build() }
                    .build()
            val request =
                ImageRequest
                    .Builder(PlatformContext.INSTANCE)
                    .data(smallUrl)
                    .size(720, 720)
                    .precision(Precision.INEXACT)
                    .apply {
                        if (explicitCacheKeys) {
                            memoryCacheKey(largeUrl)
                            diskCacheKey(largeUrl)
                        }
                    }.build()
            val fallback = assertIs<SuccessResult>(runBlocking { requireNotNull(loader).execute(request) })
            assertEquals(320, fallback.image.width)
            assertEquals(180, fallback.image.height)
            assertEquals(listOf(largeUrl, smallUrl), fetched)
            assertNull(loader.memoryCache?.get(MemoryCache.Key(largeUrl)))
            assertNotNull(loader.memoryCache?.get(MemoryCache.Key(smallUrl)))

            if (!explicitCacheKeys) {
                repeat(2) {
                    loader.memoryCache?.clear()
                    assertIs<SuccessResult>(runBlocking { requireNotNull(loader).execute(request) })
                    assertEquals(listOf(largeUrl, smallUrl), fetched)
                }
            }

            val offlineRequest = request.newBuilder().networkCachePolicy(CachePolicy.DISABLED).build()
            assertIs<SuccessResult>(runBlocking { requireNotNull(loader).execute(offlineRequest) })
            assertEquals(listOf(largeUrl, smallUrl), fetched)

            largeAvailable = true
            loader.evictArtworkCache(smallUrl)
            val upgraded = assertIs<SuccessResult>(runBlocking { requireNotNull(loader).execute(request) })
            assertTrue(upgraded.image.width > 320)
            assertTrue(upgraded.image.height > 180)
            assertEquals(16f / 9f, upgraded.image.width.toFloat() / upgraded.image.height)
            assertEquals(listOf(largeUrl, smallUrl, largeUrl), fetched)
            assertNotNull(loader.memoryCache?.get(MemoryCache.Key(largeUrl)))
        } finally {
            loader?.shutdown()
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
            directory.deleteRecursively()
        }
    }

    private fun png(
        width: Int,
        height: Int,
    ): ByteArray =
        Image
            .makeFromBitmap(Bitmap().apply { allocN32Pixels(width, height) })
            .encodeToData(EncodedImageFormat.PNG)!!
            .bytes
}
