package com.metrolist.shared.ui.utils

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import coil3.Image
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap

internal actual fun ImageRequest.Builder.configurePreparedArtwork(): ImageRequest.Builder = allowHardware(false)

actual fun Image.toImageBitmap(): ImageBitmap {
    val bitmap = toBitmap()
    // Software canvases used by image export cannot draw hardware-backed bitmaps.
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && bitmap.config == Bitmap.Config.HARDWARE) {
        checkNotNull(bitmap.copy(Bitmap.Config.ARGB_8888, false)).asImageBitmap()
    } else {
        bitmap.asImageBitmap()
    }
}

internal actual fun ImageBitmap.markPixelsImmutable() = Unit

internal actual fun pixelsToImageBitmap(
    pixels: IntArray,
    width: Int,
    height: Int,
): ImageBitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888).asImageBitmap()
