package com.metrolist.shared.utils

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.asImage
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Path.Companion.toOkioPath
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class ArtworkSizingInterceptorTest {
    private val png =
        Image
            .makeFromBitmap(Bitmap().apply { allocN32Pixels(8, 8) })
            .encodeToData(EncodedImageFormat.PNG)!!
            .bytes

    @Test
    fun `palette requests ignore cached display sizes and share the same artwork source`() {
        val base = "https://yt3.googleusercontent.com/palette"
        val fetched = mutableListOf<String>()
        val client =
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    fetched += chain.request().url.toString()
                    Response
                        .Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(png.toResponseBody())
                        .build()
                }.build()
        var loader: ImageLoader? = null
        try {
            loader =
                ImageLoader
                    .Builder(PlatformContext.INSTANCE)
                    .components {
                        add(ArtworkSizingInterceptor(isNetworkConnected = { true }) { requireNotNull(loader) })
                        add(OkHttpNetworkFetcherFactory(callFactory = { client }))
                    }.diskCache(null)
                    .memoryCache { MemoryCache.Builder().maxSizeBytes(1024 * 1024).build() }
                    .build()
            val preview = Bitmap().apply { allocN32Pixels(256, 256) }.asImage()
            assertNotNull(loader.memoryCache)[MemoryCache.Key("$base=s1200")] = MemoryCache.Value(preview)
            val first =
                assertIs<SuccessResult>(
                    runBlocking {
                        loader.execute(ImageRequest.Builder(PlatformContext.INSTANCE).artworkColors("$base=s544").build())
                    },
                )
            val second =
                assertIs<SuccessResult>(
                    runBlocking {
                        loader.execute(ImageRequest.Builder(PlatformContext.INSTANCE).artworkColors("$base=w1200-h1200").build())
                    },
                )
            assertNotSame(preview, first.image)
            assertSame(first.image, second.image)
            assertEquals(listOf("$base=s256"), fetched)
        } finally {
            loader?.shutdown()
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    @Test
    fun `player and notification requests retain valid square crop URLs`() {
        val base = "https://yt3.googleusercontent.com/cover"
        val url = "$base=w544-h544-s-l90-rj"
        val fetched = mutableListOf<String>()
        val client =
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    val request = chain.request()
                    fetched += request.url.toString()
                    val valid = request.url.encodedPath in listOf("/cover=w1200-h1200-s-l90-rj", "/cover=w544-h544-s-l90-rj")
                    Response
                        .Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(if (valid) 200 else 400)
                        .message(if (valid) "OK" else "Bad Request")
                        .body((if (valid) png else ByteArray(0)).toResponseBody())
                        .build()
                }.build()
        var loader: ImageLoader? = null
        try {
            loader =
                ImageLoader
                    .Builder(PlatformContext.INSTANCE)
                    .components {
                        add(ArtworkSizingInterceptor(isNetworkConnected = { true }) { requireNotNull(loader) })
                        add(OkHttpNetworkFetcherFactory(callFactory = { client }))
                    }.diskCache(null)
                    .memoryCache(null)
                    .build()
            for (size in listOf(1080, 512)) {
                val request =
                    ImageRequest
                        .Builder(PlatformContext.INSTANCE)
                        .data(url)
                        .size(size, size)
                        .apply { if (size == 512) diskCacheKey(canonicalArtworkUrl(url)) }
                        .build()
                assertIs<SuccessResult>(runBlocking { requireNotNull(loader).execute(request) })
            }
            assertEquals(listOf("$base=w1200-h1200-s-l90-rj", "$base=w544-h544-s-l90-rj"), fetched)
        } finally {
            loader?.shutdown()
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    @Test
    fun `an unreadable response is dropped from the disk cache and fetched again`() {
        val requests = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/") { exchange ->
            val body = if (requests.incrementAndGet() == 1) ByteArray(0) else png
            exchange.responseHeaders.add("Content-Type", if (body.isEmpty()) "text/html" else "image/png")
            exchange.sendResponseHeaders(200, if (body.isEmpty()) -1 else body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        val directory = Files.createTempDirectory("artwork-cache").toFile()
        var loader: ImageLoader? = null
        try {
            loader =
                ImageLoader
                    .Builder(PlatformContext.INSTANCE)
                    .components {
                        add(ArtworkSizingInterceptor(isNetworkConnected = { true }) { requireNotNull(loader) })
                        add(OkHttpNetworkFetcherFactory())
                    }.diskCache { DiskCache.Builder().directory(directory.toOkioPath()).build() }
                    .memoryCache(null)
                    .build()
            val url = "http://${server.address.hostString}:${server.address.port}/photo"
            val request =
                ImageRequest
                    .Builder(PlatformContext.INSTANCE)
                    .data(url)
                    .size(8, 8)
                    .build()
            val result = runBlocking { requireNotNull(loader).execute(request) }
            assertIs<SuccessResult>(result)
            assertEquals(2, requests.get())
        } finally {
            loader?.shutdown()
            server.stop(0)
            directory.deleteRecursively()
        }
    }

    @Test
    fun `cached preview finds bucketed thumbnails largest first`() {
        val loader =
            ImageLoader
                .Builder(PlatformContext.INSTANCE)
                .memoryCache { MemoryCache.Builder().maxSizeBytes(1024 * 1024).build() }
                .build()
        try {
            val url = "https://lh3.googleusercontent.com/preview=w226-h226"
            val cache = assertNotNull(loader.memoryCache)
            val image = Bitmap().apply { allocN32Pixels(8, 8) }.asImage()
            val candidates = artworkCacheCandidates(url, 1)
            assertEquals(listOf(256, 544, 1200, 2400).map { "https://lh3.googleusercontent.com/preview=s$it" }, candidates)
            candidates.forEach { cache[MemoryCache.Key(it)] = MemoryCache.Value(image) }
            candidates.reversed().forEach { candidate ->
                val preview = assertNotNull(loader.cachedArtworkPreview(url))
                assertEquals(MemoryCache.Key(candidate), preview.first)
                assertSame(image, preview.second.image)
                cache.remove(preview.first)
            }
        } finally {
            loader.shutdown()
        }
    }
}
