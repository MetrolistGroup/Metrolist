package com.metrolist.shared.ui.components.images

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import coil3.compose.rememberAsyncImagePainter
import coil3.compose.rememberConstraintsSizeResolver
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Scale
import com.metrolist.shared.ui.player.ShareMiniPlayerArtwork
import com.metrolist.shared.utils.rememberCachedArtworkPreview

@Composable
actual fun AsyncImage(
    url: String?,
    modifier: Modifier,
    contentDescription: String?,
    contentScale: ContentScale,
    alignment: androidx.compose.ui.Alignment,
    shape: Shape?,
    error: Painter?,
    previewUrl: String?,
    saturation: Float,
) {
    val context = LocalPlatformContext.current

    val sizeResolver = rememberConstraintsSizeResolver()
    val preview = rememberCachedArtworkPreview(url, previewUrl)
    val decodeScale = if (contentScale == ContentScale.Fit || contentScale == ContentScale.Inside) Scale.FIT else Scale.FILL
    val request =
        remember(url, context, sizeResolver, decodeScale, preview) {
            ImageRequest
                .Builder(context)
                .data(url)
                .placeholderMemoryCacheKey(preview?.first)
                .error(preview?.second?.image)
                .size(sizeResolver)
                .scale(decodeScale)
                .crossfade(true)
                .build()
        }
    val painter = rememberAsyncImagePainter(model = request, contentScale = contentScale)
    val state by painter.state.collectAsState()
    ShareMiniPlayerArtwork(url, (state as? AsyncImagePainter.State.Success)?.result)
    val errorPainter = error.takeIf { state is AsyncImagePainter.State.Error }
    val showImage = state is AsyncImagePainter.State.Success || errorPainter != null

    val saturationFilter =
        remember(saturation) {
            ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(saturation) })
        }

    Box(modifier = (if (shape != null) modifier.clip(shape) else modifier).then(sizeResolver)) {
        if (!url.isNullOrBlank() && !showImage && preview == null) {
            if (previewUrl != null && previewUrl != url) {
                AsyncImage(previewUrl, Modifier.fillMaxSize(), contentScale = contentScale, alignment = alignment, saturation = saturation)
            } else {
                ImageLoadingPlaceholder(
                    modifier = Modifier.fillMaxSize(),
                    animated = state !is AsyncImagePainter.State.Error,
                )
            }
        }
        Image(
            painter = errorPainter ?: painter,
            contentDescription = contentDescription,
            modifier = Modifier.fillMaxSize(),
            contentScale = contentScale,
            alignment = alignment,
            colorFilter = saturationFilter,
        )
    }
}
