package com.metrolist.shared.ui.utils

import androidx.compose.ui.graphics.ImageBitmap
import coil3.Image
import coil3.request.ImageRequest

internal expect fun ImageRequest.Builder.configurePreparedArtwork(): ImageRequest.Builder

expect fun Image.toImageBitmap(): ImageBitmap

internal expect fun ImageBitmap.markPixelsImmutable()

/** Wraps non-premultiplied ARGB pixels in a bitmap so shared code can draw pixel work it computed itself. */
internal expect fun pixelsToImageBitmap(
    pixels: IntArray,
    width: Int,
    height: Int,
): ImageBitmap
