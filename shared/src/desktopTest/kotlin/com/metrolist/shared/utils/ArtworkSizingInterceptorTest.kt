package com.metrolist.shared.utils

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.annotation.ExperimentalCoilApi
import coil3.asImage
import coil3.decode.BlackholeDecoder
import coil3.decode.DataSource
import coil3.disk.DiskCache
import coil3.intercept.Interceptor
import coil3.memory.MemoryCache
import coil3.network.DeDupeConcurrentRequestStrategy
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.ImageResult
import coil3.request.SuccessResult
import coil3.request.maxBitmapSize
import coil3.size.Precision
import coil3.size.Size
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Path.Companion.toOkioPath
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ArtworkSizingInterceptorTest {
    private val png =
        Image
            .makeFromBitmap(Bitmap().apply { allocN32Pixels(8, 8) })
            .encodeToData(EncodedImageFormat.PNG)!!
            .bytes

    @Test
    fun notificationArtworkCannotStoreASmallSourceUnderTheFullSizeCacheKey() = checkNotificationArtworkCache(false)

    @Test
    fun undersizedCoversFromTheOldNotificationCacheAreRefreshed() = checkNotificationArtworkCache(true)

    @Test
    fun refreshedCoverUsesTheCanonicalDiskKeyInMemory() = checkNotificationArtworkCache(stale = true, memoryCacheEnabled = true)

    @Test
    fun diskCoverSurvivesARefreshFailureAndRemainsAvailableOffline() = checkFailedRefresh(memoryOnly = false)

    @Test
    fun diskCoverSurvivesAnUndecodableRefreshAndRemainsAvailableOffline() =
        checkFailedRefresh(memoryOnly = false, undecodableResponse = true)

    @Test
    fun explicitDiskKeyCoverSurvivesAnUndecodableRefreshAndRemainsAvailableOffline() =
        checkFailedRefresh(memoryOnly = false, undecodableResponse = true, explicitDiskKey = true)

    @Test
    fun memoryOnlyCoverSurvivesARefreshFailure() = checkFailedRefresh(memoryOnly = true)

    @Test
    fun explicitMemoryKeyCoverSurvivesARefreshFailure() = checkFailedRefresh(memoryOnly = true, explicitMemoryKey = true)

    @Test
    fun smallDiskSourceIsReusedAcrossOnlineLoadsAndRestarts() = checkSmallSourceCache(memoryCacheEnabled = false)

    @Test
    fun smallMemorySourceIsReusedAcrossOnlineLoadsAndRestarts() = checkSmallSourceCache(memoryCacheEnabled = true)

    @Test
    fun smallMemoryOnlySourceIsReusedAcrossOnlineLoads() = checkSmallSourceCache(memoryCacheEnabled = true, diskCacheEnabled = false)

    @Test
    fun oldDiskCoverIsRefreshedOnlyOnceWhenTheSourceStaysSmall() = checkSmallSourceCache(memoryCacheEnabled = false, stale = true)

    @Test
    fun oldCoverIsRefreshedOnlyOnceInMemoryWhenTheSourceStaysSmall() = checkSmallSourceCache(memoryCacheEnabled = true, stale = true)

    @Test
    fun concurrentRefreshesKeepTheSuccessfulRepairWhenAnotherResponseWouldFail() = checkConcurrentRefresh(failFirst = false)

    @Test
    fun failedRefreshAllowsAWaitingRequestToRepairTheCover() = checkConcurrentRefresh(failFirst = true)

    @OptIn(ExperimentalCoilApi::class)
    private fun checkConcurrentRefresh(failFirst: Boolean) =
        runBlocking {
            val url = "https://lh3.googleusercontent.com/concurrent=s1200"
            val small = Image.makeFromBitmap(Bitmap().apply { allocN32Pixels(544, 544) }).encodeToData(EncodedImageFormat.PNG)!!.bytes
            val sharp = Image.makeFromBitmap(Bitmap().apply { allocN32Pixels(1200, 1200) }).encodeToData(EncodedImageFormat.PNG)!!.bytes
            val fetches = AtomicInteger()
            val firstStarted = CompletableDeferred<Unit>()
            val secondStarted = CompletableDeferred<Unit>()
            val waiterReadOldCover = CompletableDeferred<Unit>()
            val releaseFirst = CountDownLatch(1)
            val releaseSecond = CountDownLatch(1)
            var online = true
            val client =
                OkHttpClient
                    .Builder()
                    .addInterceptor { chain ->
                        val number = fetches.incrementAndGet()
                        if (number == 1) {
                            firstStarted.complete(Unit)
                            check(releaseFirst.await(10, TimeUnit.SECONDS))
                        } else {
                            secondStarted.complete(Unit)
                            check(releaseSecond.await(10, TimeUnit.SECONDS))
                        }
                        val succeeds = if (failFirst) number == 2 else number == 1
                        Response
                            .Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body((if (succeeds) sharp else "invalid image".encodeToByteArray()).toResponseBody())
                            .build()
                    }.build()
            val directory = Files.createTempDirectory("concurrent-artwork-refresh").toFile()
            val cache = DiskCache.Builder().directory(directory.toOkioPath()).build()
            var loader: ImageLoader? = null
            try {
                val editor = assertNotNull(cache.openEditor(url))
                cache.fileSystem.write(editor.metadata) {}
                cache.fileSystem.write(editor.data) { write(small) }
                editor.commit()
                loader =
                    ImageLoader
                        .Builder(PlatformContext.INSTANCE)
                        .components {
                            add(ArtworkSizingInterceptor(isNetworkConnected = { online }) { requireNotNull(loader) })
                            add(
                                object : Interceptor {
                                    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
                                        val result = chain.proceed()
                                        if (chain.request.memoryCacheKey == "waiter" && result is SuccessResult &&
                                            result.image.width == 544
                                        ) {
                                            waiterReadOldCover.complete(Unit)
                                        }
                                        return result
                                    }
                                },
                            )
                            add(
                                OkHttpNetworkFetcherFactory(
                                    callFactory = { client },
                                    concurrentRequestStrategy = { DeDupeConcurrentRequestStrategy() },
                                ),
                            )
                        }.diskCache(cache)
                        .memoryCache(null)
                        .build()
                val request =
                    ImageRequest
                        .Builder(PlatformContext.INSTANCE)
                        .data(url)
                        .size(1200)
                        .precision(Precision.INEXACT)
                        .memoryCachePolicy(CachePolicy.DISABLED)
                        .memoryCacheKey("leader")
                        .build()
                val leader = async { loader.execute(request) }
                withTimeout(10_000) { firstStarted.await() }
                val waiter = async { loader.execute(request.newBuilder().memoryCacheKey("waiter").build()) }
                withTimeout(10_000) { waiterReadOldCover.await() }
                withTimeoutOrNull(1_000) { secondStarted.await() }
                releaseFirst.countDown()
                val first = assertIs<SuccessResult>(withTimeout(10_000) { leader.await() })
                releaseSecond.countDown()
                val second = assertIs<SuccessResult>(withTimeout(10_000) { waiter.await() })
                assertEquals(if (failFirst) 544 else 1200, first.image.width)
                assertNotNull(cache.openSnapshot(url)).use { snapshot ->
                    assertContentEquals(sharp, cache.fileSystem.read(snapshot.data) { readByteArray() })
                }
                assertEquals(1200, second.image.width)
                assertEquals(if (failFirst) 2 else 1, fetches.get())
                online = false
                val offline = assertIs<SuccessResult>(loader.execute(request))
                assertEquals(DataSource.DISK, offline.dataSource)
                assertEquals(1200, offline.image.width)
                assertEquals(1200, offline.image.height)
                assertEquals(if (failFirst) 2 else 1, fetches.get())
            } finally {
                releaseFirst.countDown()
                releaseSecond.countDown()
                loader?.shutdown()
                cache.shutdown()
                client.dispatcher.executorService.shutdown()
                client.connectionPool.evictAll()
                directory.deleteRecursively()
            }
        }

    @OptIn(ExperimentalCoilApi::class)
    @Test
    fun prefetchRetainsValidDiskBytesWhenTheServerWouldReturnAnInvalidImage() {
        val url = "https://lh3.googleusercontent.com/prefetch=s256"
        var online = true
        var fetches = 0
        val client =
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    fetches++
                    Response
                        .Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("invalid image".toResponseBody())
                        .build()
                }.build()
        val directory = Files.createTempDirectory("prefetch-artwork").toFile()
        val cache = DiskCache.Builder().directory(directory.toOkioPath()).build()
        var loader: ImageLoader? = null
        try {
            val editor = assertNotNull(cache.openEditor(url))
            cache.fileSystem.write(editor.metadata) {}
            cache.fileSystem.write(editor.data) { write(png) }
            editor.commit()
            loader =
                ImageLoader
                    .Builder(PlatformContext.INSTANCE)
                    .components {
                        add(ArtworkSizingInterceptor(isNetworkConnected = { online }) { requireNotNull(loader) })
                        add(OkHttpNetworkFetcherFactory(callFactory = { client }))
                    }.diskCache(cache)
                    .memoryCache(null)
                    .build()
            runBlocking {
                val prefetch =
                    assertIs<SuccessResult>(
                        loader.execute(
                            ImageRequest
                                .Builder(PlatformContext.INSTANCE)
                                .data(url)
                                .size(256)
                                .memoryCachePolicy(CachePolicy.DISABLED)
                                .decoderFactory(BlackholeDecoder.Factory())
                                .build(),
                        ),
                    )
                assertTrue(prefetch.image.width <= 0 && prefetch.image.height <= 0)
                assertNotNull(cache.openSnapshot(url)).use { snapshot ->
                    assertContentEquals(png, cache.fileSystem.read(snapshot.data) { readByteArray() })
                }
                assertEquals(0, fetches)
                online = false
                val offline =
                    assertIs<SuccessResult>(
                        loader.execute(
                            ImageRequest
                                .Builder(PlatformContext.INSTANCE)
                                .data(url)
                                .size(256)
                                .memoryCachePolicy(CachePolicy.DISABLED)
                                .networkCachePolicy(CachePolicy.DISABLED)
                                .build(),
                        ),
                    )
                assertEquals(DataSource.DISK, offline.dataSource)
                assertEquals(8, offline.image.width)
                assertEquals(8, offline.image.height)
                assertEquals(0, fetches)
            }
        } finally {
            loader?.shutdown()
            cache.shutdown()
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
            directory.deleteRecursively()
        }
    }

    @Test
    fun iosNowPlayingCacheReusePreservesExactPrecisionAndBitmapLimit() = checkNowPlayingArtworkPrecision(fallback = false)

    @Test
    fun iosNowPlayingFallbackPreservesExactPrecisionAndBitmapLimit() = checkNowPlayingArtworkPrecision(fallback = true)

    private fun checkNowPlayingArtworkPrecision(fallback: Boolean) {
        val url = "https://lh3.googleusercontent.com/now-playing=s544"
        val key = if (fallback) url else canonicalArtworkUrl(url)
        val bitmap = Bitmap().apply { allocN32Pixels(1200, 1200) }
        val bytes = Image.makeFromBitmap(bitmap).encodeToData(EncodedImageFormat.PNG)!!.bytes
        var fetches = 0
        val client =
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    fetches++
                    Response
                        .Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(503)
                        .message("Unavailable")
                        .body(ByteArray(0).toResponseBody())
                        .build()
                }.build()
        val directory = Files.createTempDirectory("now-playing-artwork").toFile()
        val cache = DiskCache.Builder().directory(directory.toOkioPath()).build()
        var loader: ImageLoader? = null
        try {
            val editor = assertNotNull(cache.openEditor(key))
            cache.fileSystem.write(editor.metadata) {}
            cache.fileSystem.write(editor.data) { write(bytes) }
            editor.commit()
            loader =
                ImageLoader
                    .Builder(PlatformContext.INSTANCE)
                    .components {
                        add(ArtworkSizingInterceptor(isNetworkConnected = { true }) { requireNotNull(loader) })
                        add(OkHttpNetworkFetcherFactory(callFactory = { client }))
                    }.diskCache(cache)
                    .memoryCache { MemoryCache.Builder().maxSizeBytes(16 * 1024 * 1024).build() }
                    .build()
            assertNotNull(loader.memoryCache)[MemoryCache.Key(key)] = MemoryCache.Value(bitmap.asImage())
            val result =
                assertIs<SuccessResult>(
                    runBlocking {
                        loader.execute(
                            ImageRequest
                                .Builder(PlatformContext.INSTANCE)
                                .data(url)
                                .size(600, 600)
                                .maxBitmapSize(Size(600, 600))
                                .precision(Precision.EXACT)
                                .build(),
                        )
                    },
                )
            assertEquals(600, result.image.width)
            assertEquals(600, result.image.height)
            assertEquals(Precision.EXACT, result.request.precision)
            assertEquals(Size(600, 600), result.request.maxBitmapSize)
            assertEquals(DataSource.DISK, result.dataSource)
            assertEquals(if (fallback) 1 else 0, fetches)
        } finally {
            loader?.shutdown()
            cache.shutdown()
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
            directory.deleteRecursively()
        }
    }

    private fun checkSmallSourceCache(
        memoryCacheEnabled: Boolean,
        diskCacheEnabled: Boolean = true,
        stale: Boolean = false,
    ) {
        val url = "https://lh3.googleusercontent.com/small=s1200"
        val bytes =
            Image
                .makeFromBitmap(Bitmap().apply { allocN32Pixels(544, 544) })
                .encodeToData(EncodedImageFormat.PNG)!!
                .bytes
        var fetches = 0
        val client =
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    fetches++
                    Response
                        .Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .header("Cache-Control", "max-age=3600")
                        .body(bytes.toResponseBody())
                        .build()
                }.build()
        val directory = Files.createTempDirectory("small-artwork").toFile()
        var cache = if (diskCacheEnabled) DiskCache.Builder().directory(directory.toOkioPath()).build() else null
        var loader: ImageLoader? = null

        fun createLoader() =
            ImageLoader
                .Builder(PlatformContext.INSTANCE)
                .components {
                    add(ArtworkSizingInterceptor(isNetworkConnected = { true }) { requireNotNull(loader) })
                    add(OkHttpNetworkFetcherFactory(callFactory = { client }))
                }.diskCache(cache)
                .memoryCache(if (memoryCacheEnabled) MemoryCache.Builder().maxSizeBytes(4 * 1024 * 1024).build() else null)
                .build()
        try {
            if (stale) runBlocking { seedLegacyCover(client, assertNotNull(cache), url) }
            loader = createLoader()
            val request =
                ImageRequest
                    .Builder(PlatformContext.INSTANCE)
                    .data(url)
                    .size(1200)
                    .precision(Precision.INEXACT)
                    .build()
            runBlocking {
                repeat(4) {
                    val result = assertIs<SuccessResult>(requireNotNull(loader).execute(request))
                    assertEquals(544, result.image.width)
                    assertEquals(544, result.image.height)
                }
                assertEquals(if (stale) 2 else 1, fetches)
                if (diskCacheEnabled) {
                    requireNotNull(loader).shutdown()
                    assertNotNull(cache).shutdown()
                    cache = DiskCache.Builder().directory(directory.toOkioPath()).build()
                    loader = createLoader()
                    repeat(2) {
                        val result = assertIs<SuccessResult>(requireNotNull(loader).execute(request))
                        assertEquals(544, result.image.width)
                        assertEquals(544, result.image.height)
                    }
                    assertEquals(if (stale) 2 else 1, fetches)
                }
            }
        } finally {
            loader?.shutdown()
            cache?.shutdown()
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
            directory.deleteRecursively()
        }
    }

    private suspend fun seedLegacyCover(
        client: OkHttpClient,
        cache: DiskCache,
        url: String,
    ) {
        val legacyLoader =
            ImageLoader
                .Builder(PlatformContext.INSTANCE)
                .components { add(OkHttpNetworkFetcherFactory(callFactory = { client })) }
                .diskCache(cache)
                .memoryCache(null)
                .build()
        try {
            assertIs<SuccessResult>(
                legacyLoader.execute(
                    ImageRequest
                        .Builder(PlatformContext.INSTANCE)
                        .data(bucketedArtworkUrl(url, 512))
                        .diskCacheKey(canonicalArtworkUrl(url))
                        .size(512)
                        .build(),
                ),
            )
        } finally {
            legacyLoader.shutdown()
        }
    }

    private fun checkFailedRefresh(
        memoryOnly: Boolean,
        explicitMemoryKey: Boolean = false,
        undecodableResponse: Boolean = false,
        explicitDiskKey: Boolean = false,
    ) {
        val url = "https://lh3.googleusercontent.com/stale=s1200"
        val cacheKey = if (explicitMemoryKey) "custom-artwork" else url
        val bytes =
            Image
                .makeFromBitmap(Bitmap().apply { allocN32Pixels(544, 544) })
                .encodeToData(EncodedImageFormat.PNG)!!
                .bytes
        var online = true
        var failNetwork = memoryOnly
        var fetches = 0
        val client =
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    fetches++
                    if (failNetwork && !undecodableResponse) throw IOException("CDN unavailable")
                    Response
                        .Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .header("Cache-Control", "max-age=3600")
                        .body((if (failNetwork) "invalid image".encodeToByteArray() else bytes).toResponseBody())
                        .build()
                }.build()
        val directory = Files.createTempDirectory("stale-artwork").toFile()
        var loader: ImageLoader? = null
        try {
            loader =
                ImageLoader
                    .Builder(PlatformContext.INSTANCE)
                    .components {
                        add(ArtworkSizingInterceptor(isNetworkConnected = { online }) { requireNotNull(loader) })
                        add(OkHttpNetworkFetcherFactory(callFactory = { client }))
                    }.diskCache(if (memoryOnly) null else DiskCache.Builder().directory(directory.toOkioPath()).build())
                    .memoryCache(if (memoryOnly) MemoryCache.Builder().maxSizeBytes(4 * 1024 * 1024).build() else null)
                    .build()
            val preview = Bitmap().apply { allocN32Pixels(544, 544) }.asImage()
            if (memoryOnly) {
                assertNotNull(loader.memoryCache)[MemoryCache.Key(cacheKey)] = MemoryCache.Value(preview)
            } else {
                runBlocking { seedLegacyCover(client, assertNotNull(loader.diskCache), url) }
            }
            val seedFetches = fetches
            failNetwork = true
            val request =
                ImageRequest
                    .Builder(PlatformContext.INSTANCE)
                    .data(url)
                    .size(1200)
                    .precision(Precision.INEXACT)
                    .apply {
                        if (explicitMemoryKey) memoryCacheKey(cacheKey)
                        if (explicitDiskKey) diskCacheKey(url)
                    }.build()
            val result = assertIs<SuccessResult>(runBlocking { loader.execute(request) })
            assertTrue(fetches > seedFetches)
            assertEquals(544, result.image.width)
            assertEquals(if (memoryOnly) DataSource.MEMORY_CACHE else DataSource.DISK, result.dataSource)
            if (memoryOnly) {
                assertSame(preview, result.image)
                assertSame(preview, assertNotNull(loader.memoryCache)[MemoryCache.Key(cacheKey)]?.image)
            } else {
                assertNotNull(loader.diskCache).openSnapshot(url)!!.use { snapshot ->
                    assertContentEquals(bytes, snapshot.data.toFile().readBytes())
                }
            }
            val attempts = fetches
            online = false
            assertIs<SuccessResult>(runBlocking { loader.execute(request) })
            assertEquals(attempts, fetches)
        } finally {
            loader?.shutdown()
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
            directory.deleteRecursively()
        }
    }

    private fun checkNotificationArtworkCache(
        stale: Boolean,
        memoryCacheEnabled: Boolean = false,
    ) {
        val url = "https://lh3.googleusercontent.com/cover=w60-h60-l90-rj"
        val fetched = mutableListOf<String>()
        var online = true
        val client =
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    val source = chain.request().url.toString()
                    fetched += source
                    val edge = if ((stale && fetched.size == 1) || source != canonicalArtworkUrl(url)) 544 else 1200
                    val bytes =
                        Image
                            .makeFromBitmap(Bitmap().apply { allocN32Pixels(edge, edge) })
                            .encodeToData(EncodedImageFormat.PNG)!!
                            .bytes
                    Response
                        .Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .header("Cache-Control", "max-age=3600")
                        .body(bytes.toResponseBody())
                        .build()
                }.build()
        val directory = Files.createTempDirectory("notification-artwork").toFile()
        var loader: ImageLoader? = null
        try {
            loader =
                ImageLoader
                    .Builder(PlatformContext.INSTANCE)
                    .components {
                        add(ArtworkSizingInterceptor(isNetworkConnected = { online }) { requireNotNull(loader) })
                        add(OkHttpNetworkFetcherFactory(callFactory = { client }))
                    }.diskCache { DiskCache.Builder().directory(directory.toOkioPath()).build() }
                    .memoryCache(if (memoryCacheEnabled) MemoryCache.Builder().maxSizeBytes(16 * 1024 * 1024).build() else null)
                    .build()
            runBlocking {
                if (stale) {
                    seedLegacyCover(client, assertNotNull(loader.diskCache), url)
                } else {
                    assertIs<SuccessResult>(
                        loader.execute(
                            ImageRequest
                                .Builder(PlatformContext.INSTANCE)
                                .data(url)
                                .size(512)
                                .diskCacheKey(canonicalArtworkUrl(url))
                                .build(),
                        ),
                    )
                }
                val display =
                    assertIs<SuccessResult>(
                        loader.execute(
                            ImageRequest
                                .Builder(PlatformContext.INSTANCE)
                                .data(url)
                                .size(1200)
                                .build(),
                        ),
                    )
                assertEquals(1200, display.image.width)
                assertEquals(1200, display.image.height)
                assertEquals(canonicalArtworkUrl(url), display.diskCacheKey)
                if (memoryCacheEnabled) {
                    val memory =
                        assertIs<SuccessResult>(
                            loader.execute(
                                ImageRequest
                                    .Builder(PlatformContext.INSTANCE)
                                    .data(url)
                                    .size(1200)
                                    .build(),
                            ),
                        )
                    assertEquals(DataSource.MEMORY_CACHE, memory.dataSource)
                    assertEquals(canonicalArtworkUrl(url), memory.diskCacheKey)
                    assertNotNull(loader.memoryCache).clear()
                }
                online = false
                val offline =
                    assertIs<SuccessResult>(
                        loader.execute(
                            ImageRequest
                                .Builder(PlatformContext.INSTANCE)
                                .data(url)
                                .size(1200)
                                .build(),
                        ),
                    )
                assertEquals(DataSource.DISK, offline.dataSource)
                assertEquals(1200, offline.image.width)
                assertEquals(1200, offline.image.height)
            }
            assertEquals(
                if (stale) listOf(bucketedArtworkUrl(url, 512), canonicalArtworkUrl(url)) else listOf(canonicalArtworkUrl(url)),
                fetched,
            )
        } finally {
            loader?.shutdown()
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
            directory.deleteRecursively()
        }
    }

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
            assertEquals(listOf("$base=w1200-h1200-s-l90-rj", "$base=w1200-h1200-s-l90-rj"), fetched)
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
