package com.metrolist.desktop.utils

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ArtworkSizingInterceptorTest {
    @Test
    fun originalVideoUrlsSelectTheirRenditionAfterResolvingTheRequestSize() {
        val small = "https://i.ytimg.com/vi/video/mqdefault.jpg"
        val large = "https://i.ytimg.com/vi/video/maxresdefault.jpg"
        val fetched = mutableListOf<String>()
        val png = Image.makeFromBitmap(Bitmap().apply { allocN32Pixels(8, 8) }).encodeToData(EncodedImageFormat.PNG)!!.bytes
        val client =
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    val request = chain.request()
                    fetched += request.url.toString()
                    Response
                        .Builder()
                        .request(request)
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
                        add(ArtworkSizingInterceptor { requireNotNull(loader) })
                        add(OkHttpNetworkFetcherFactory(callFactory = { client }))
                    }.memoryCache(null)
                    .diskCache(null)
                    .build()
            for (size in listOf(64, 128, 1080, 64)) {
                val request =
                    ImageRequest
                        .Builder(PlatformContext.INSTANCE)
                        .data(small)
                        .size(size, size)
                        .build()
                assertIs<SuccessResult>(runBlocking { requireNotNull(loader).execute(request) })
            }
            assertEquals(listOf(small, small, large, small), fetched)
        } finally {
            loader?.shutdown()
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }
}
