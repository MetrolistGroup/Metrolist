@file:OptIn(ExperimentalCoilApi::class)

package com.metrolist.shared.utils

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.annotation.ExperimentalCoilApi
import coil3.disk.DiskCache
import coil3.network.DeDupeConcurrentRequestStrategy
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toOkioPath
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ArtworkConcurrentRequestsTest {
    @Test
    fun coldWaitersShareTheCachedBodyAndCancelledWaitersDoNotCancelTheLeader() = checkRequests(cacheEnabled = true)

    @Test
    fun disabledDiskCachingStillCompletesEveryRequest() = checkRequests(cacheEnabled = false)

    @Test
    fun failedLeaderAllowsAWaiterToFetchAndCacheTheImage() = checkRequests(cacheEnabled = true, failFirst = true)

    private fun checkRequests(
        cacheEnabled: Boolean,
        failFirst: Boolean = false,
    ) = runBlocking {
        val requests = AtomicInteger()
        val started = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val png = Image.makeFromBitmap(Bitmap().apply { allocN32Pixels(8, 8) }).encodeToData(EncodedImageFormat.PNG)!!.bytes
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/") { exchange ->
            val number = requests.incrementAndGet()
            if (number == 1) {
                started.complete(Unit)
                check(release.await(10, TimeUnit.SECONDS))
            }
            exchange.responseHeaders.add("Content-Type", "image/png")
            exchange.sendResponseHeaders(if (failFirst && number == 1) 500 else 200, png.size.toLong())
            exchange.responseBody.use { it.write(png) }
        }
        server.start()
        val directory = Files.createTempDirectory("concurrent-artwork").toFile()
        val loader =
            ImageLoader
                .Builder(PlatformContext.INSTANCE)
                .components { add(OkHttpNetworkFetcherFactory(concurrentRequestStrategy = { DeDupeConcurrentRequestStrategy() })) }
                .memoryCache(null)
                .diskCache { if (cacheEnabled) DiskCache.Builder().directory(directory.toOkioPath()).build() else null }
                .build()
        val url = "http://127.0.0.1:${server.address.port}/cover"

        fun request() =
            ImageRequest
                .Builder(PlatformContext.INSTANCE)
                .data(url)
                .size(128)
                .build()
        try {
            val leader = async { loader.execute(request()) }
            started.await()
            val waiters = List(4) { async { loader.execute(request()) } }
            delay(100)
            waiters.first().cancelAndJoin()
            release.countDown()
            if (!failFirst) assertIs<SuccessResult>(leader.await()) else leader.await()
            waiters.drop(1).awaitAll().forEach { assertIs<SuccessResult>(it) }
            assertEquals(
                if (cacheEnabled) {
                    if (failFirst) {
                        2
                    } else {
                        1
                    }
                } else {
                    4
                },
                requests.get(),
            )
            assertIs<SuccessResult>(loader.execute(request().newBuilder().data("$url?revision=2").build()))
            assertEquals(
                if (cacheEnabled) {
                    if (failFirst) {
                        3
                    } else {
                        2
                    }
                } else {
                    5
                },
                requests.get(),
            )
        } finally {
            release.countDown()
            loader.shutdown()
            server.stop(0)
            directory.deleteRecursively()
        }
    }
}
