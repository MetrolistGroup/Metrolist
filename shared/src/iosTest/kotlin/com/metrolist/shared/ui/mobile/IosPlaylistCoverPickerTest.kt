package com.metrolist.shared.ui.mobile

import kotlinx.cinterop.ExperimentalForeignApi
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.impl.use
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(ExperimentalForeignApi::class)
class IosPlaylistCoverPickerTest {
    @Test
    fun convertsPngToBoundedJpegWithoutUpscaling() {
        for ((width, height) in listOf(3 to 2, 2048 to 1024)) {
            val encoded = png(width, height)
            val normalized = normalizeIosPlaylistCover(encoded)
            assertTrue(normalized.size <= MAX_IOS_PLAYLIST_COVER_BYTES)
            assertEquals(0xFF, normalized[0].toInt() and 0xFF)
            assertEquals(0xD8, normalized[1].toInt() and 0xFF)
            Image.makeFromEncoded(normalized).use { image ->
                assertEquals(minOf(width, 1024), image.width)
                assertEquals(if (width > 1024) height / 2 else height, image.height)
            }
        }
    }

    @Test
    fun rejectsEmptyCorruptAndOversizedImages() {
        assertFailsWith<IllegalArgumentException> { normalizeIosPlaylistCover(byteArrayOf()) }
        assertFailsWith<IllegalStateException> { normalizeIosPlaylistCover(byteArrayOf(1, 2, 3)) }
        assertFailsWith<IllegalArgumentException> { normalizeIosPlaylistCover(ByteArray(MAX_IOS_PLAYLIST_COVER_BYTES + 1)) }
        assertFailsWith<IllegalArgumentException> { normalizeIosPlaylistCover(png(4097, 4096)) }
    }

    private fun png(
        width: Int,
        height: Int,
    ): ByteArray =
        Bitmap().use { bitmap ->
            check(bitmap.allocN32Pixels(width, height))
            bitmap.erase(0xFFFF0000.toInt())
            Image.makeFromBitmap(bitmap).use { image ->
                checkNotNull(image.encodeToData(EncodedImageFormat.PNG)).use { it.bytes }
            }
        }
}
