package com.metrolist.android.utils

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.Build
import androidx.annotation.RequiresApi
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import kotlin.math.roundToInt

internal const val MAX_PLAYLIST_COVER_BYTES = 16 * 1024 * 1024
private const val MAX_PLAYLIST_COVER_PIXELS = 16_777_216L
private const val PLAYLIST_COVER_SIZE = 1024

internal fun readPlaylistCover(input: InputStream): ByteArray {
    val bytes = requireNotNull(input.readBoundedBytes(MAX_PLAYLIST_COVER_BYTES))
    val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) decodeScaled(bytes) else decodeScaledLegacy(bytes)
    return try {
        ByteArrayOutputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output))
            output.toByteArray()
        }
    } finally {
        bitmap.recycle()
    }
}

@RequiresApi(Build.VERSION_CODES.P)
private fun decodeScaled(bytes: ByteArray): Bitmap =
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
        val width = info.size.width
        val height = info.size.height
        require(width > 0 && height > 0 && width.toLong() * height <= MAX_PLAYLIST_COVER_PIXELS)
        val scale = minOf(1.0, PLAYLIST_COVER_SIZE.toDouble() / maxOf(width, height))
        decoder.setTargetSize((width * scale).roundToInt().coerceAtLeast(1), (height * scale).roundToInt().coerceAtLeast(1))
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }

private fun decodeScaledLegacy(bytes: ByteArray): Bitmap {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val width = bounds.outWidth
    val height = bounds.outHeight
    require(width > 0 && height > 0 && width.toLong() * height <= MAX_PLAYLIST_COVER_PIXELS)
    var sampleSize = 1
    while (maxOf(width, height) / (sampleSize * 2) >= PLAYLIST_COVER_SIZE) sampleSize *= 2
    val sampled =
        requireNotNull(
            BitmapFactory.decodeByteArray(
                bytes,
                0,
                bytes.size,
                BitmapFactory.Options().apply {
                    inSampleSize =
                        sampleSize
                },
            ),
        )
    val scale = minOf(1.0, PLAYLIST_COVER_SIZE.toDouble() / maxOf(sampled.width, sampled.height))
    if (scale == 1.0) return sampled
    val scaled =
        Bitmap.createScaledBitmap(
            sampled,
            (sampled.width * scale).roundToInt().coerceAtLeast(1),
            (sampled.height * scale).roundToInt().coerceAtLeast(1),
            true,
        )
    if (scaled !== sampled) sampled.recycle()
    return scaled
}
