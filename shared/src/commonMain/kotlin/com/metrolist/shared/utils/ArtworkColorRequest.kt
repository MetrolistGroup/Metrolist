package com.metrolist.shared.utils

import coil3.request.ImageRequest
import coil3.size.Precision

fun ImageRequest.Builder.artworkColors(url: String): ImageRequest.Builder {
    val source = artworkColorSourceUrl(url)
    // Palette sampling must not depend on which display sizes the image loader has already cached.
    return data(source)
        .size(128, 128)
        .precision(Precision.EXACT)
        .memoryCacheKey("artwork-colors:$source")
        .diskCacheKey(source)
}
