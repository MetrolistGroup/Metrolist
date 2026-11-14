package com.metrolist.shared.utils

import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageResult

suspend fun Interceptor.Chain.withVideoArtworkFallback(result: ImageResult): ImageResult {
    if (result !is ErrorResult) return result
    val url = request.data as? String ?: return result
    val fallback = videoArtworkFallbackUrl(url) ?: return result
    return withRequest(
        request
            .newBuilder()
            .data(fallback)
            .memoryCacheKey(fallback)
            .diskCacheKey(fallback)
            .build(),
    ).proceed()
}
