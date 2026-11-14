package com.metrolist.shared.ui.utils

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import coil3.Image
import coil3.request.ImageRequest
import coil3.toBitmap
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo

internal actual fun ImageRequest.Builder.configurePreparedArtwork(): ImageRequest.Builder = this

actual fun Image.toImageBitmap(): ImageBitmap = toBitmap().asComposeImageBitmap()

internal actual fun ImageBitmap.markPixelsImmutable() {
    asSkiaBitmap().setImmutable()
}

internal actual fun pixelsToImageBitmap(
    pixels: IntArray,
    width: Int,
    height: Int,
): ImageBitmap {
    // BGRA_8888 is the little-endian byte order of an ARGB Int.
    val bytes = ByteArray(pixels.size * Int.SIZE_BYTES)
    pixels.forEachIndexed { index, argb ->
        bytes[index * 4] = argb.toByte()
        bytes[index * 4 + 1] = (argb shr 8).toByte()
        bytes[index * 4 + 2] = (argb shr 16).toByte()
        bytes[index * 4 + 3] = (argb shr 24).toByte()
    }
    val bitmap = Bitmap()
    bitmap.installPixels(ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.UNPREMUL), bytes, width * Int.SIZE_BYTES)
    return bitmap.asComposeImageBitmap()
}
