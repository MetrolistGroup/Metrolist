package com.metrolist.shared.ui.screens

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.decode.DataSource
import coil3.memory.MemoryCache
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.size.Size
import com.metrolist.shared.ui.components.artworkBackdropRequest
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame

class ArtistPhotoRequestTest {
    @Test
    fun `a photo decoded once is reused by every screen despite the blurred backdrop`() =
        runBlocking {
            val file = File.createTempFile("artist-photo", ".png")
            try {
                val bitmap = Bitmap().apply { allocN32Pixels(1600, 2000) }
                file.writeBytes(Image.makeFromBitmap(bitmap).encodeToData(EncodedImageFormat.PNG)!!.bytes)
                val url = file.toURI().toString()
                val context = PlatformContext.INSTANCE
                val loader =
                    ImageLoader
                        .Builder(context)
                        .memoryCache { MemoryCache.Builder().maxSizeBytes(64L * 1024 * 1024).build() }
                        .build()

                val preview = loader.execute(artistPhotoRequest(context, url)) as SuccessResult
                loader.execute(artworkBackdropRequest(context, url))
                val original =
                    loader.execute(
                        ImageRequest
                            .Builder(context)
                            .data(url)
                            .size(Size.ORIGINAL)
                            .build(),
                    ) as SuccessResult
                assertNotEquals(preview.memoryCacheKey, original.memoryCacheKey)

                repeat(2) {
                    val result = loader.execute(artistPhotoRequest(context, url)) as SuccessResult
                    assertEquals(DataSource.MEMORY_CACHE, result.dataSource)
                    assertSame(preview.image, result.image)
                }
            } finally {
                file.delete()
            }
        }
}
