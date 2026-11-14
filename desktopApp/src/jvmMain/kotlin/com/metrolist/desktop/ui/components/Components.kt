/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import coil3.BitmapImage
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import coil3.compose.asPainter
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import com.metrolist.desktop.state.app.UiState
import com.metrolist.desktop.state.playback.PlayerArtworkPreview
import com.metrolist.shared.constants.ThumbnailCornerRadius
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.theme.ThemeColorExtractor
import com.metrolist.shared.ui.utils.ArtworkBlurStyle
import com.metrolist.shared.ui.utils.cachedArtworkBlur
import com.metrolist.shared.utils.artworkColors
import com.metrolist.shared.utils.canonicalArtworkUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import coil3.compose.AsyncImage as CoilAsyncImage
import org.jetbrains.skia.Image as SkiaImage

private const val THEME_SAMPLE_WIDTH = 128

private fun SkiaImage.scaledTo(maxWidth: Int): SkiaImage {
    if (width <= maxWidth) return this
    val scale = maxWidth.toFloat() / width
    val dstH = (height * scale).toInt().coerceAtLeast(1)
    return org.jetbrains.skia.Surface.makeRasterN32Premul(maxWidth, dstH).use { surface ->
        org.jetbrains.skia.Paint().use { paint ->
            paint.isAntiAlias = true
            surface.canvas.drawImageRect(
                this,
                org.jetbrains.skia.Rect
                    .makeWH(width.toFloat(), height.toFloat()),
                org.jetbrains.skia.Rect
                    .makeWH(maxWidth.toFloat(), dstH.toFloat()),
                org.jetbrains.skia.SamplingMode.LINEAR,
                paint,
                true,
            )
        }
        surface.makeImageSnapshot()
    }
}

private data class ArtworkThemeData(
    val seedColor: Color,
)

private val artworkThemeCache =
    object : LinkedHashMap<String, ArtworkThemeData>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ArtworkThemeData>): Boolean = size > 15
    }
private val artworkThemeCacheMutex = Mutex()
private val artworkThemeRequestMutexes = ConcurrentHashMap<String, Mutex>()

@Composable
internal fun AsyncImage(
    url: String,
    modifier: Modifier = Modifier,
    extractColor: Boolean = false,
    shape: Shape = RoundedCornerShape(ThumbnailCornerRadius),
    contentScale: ContentScale = ContentScale.Crop,
    alignment: Alignment = Alignment.Center,
    maxDecodeWidth: Int? = null,
    artworkPreview: PlayerArtworkPreview? = null,
    saturation: Float = 1f,
    blur: ArtworkBlurStyle? = null,
) {
    val context = LocalPlatformContext.current
    val canonicalUrl = remember(url) { canonicalArtworkUrl(url) }
    val artworkColorsEnabled = Settings.shared.materialYouEnabled && Settings.shared.dynamicTheme

    val saturationMatrix =
        remember(saturation) {
            ColorMatrix().apply { setToSaturation(saturation) }
        }
    val saturationFilter =
        remember(saturationMatrix) {
            ColorFilter.colorMatrix(saturationMatrix)
        }

    LaunchedEffect(canonicalUrl, extractColor, artworkColorsEnabled) {
        if (!extractColor || !artworkColorsEnabled) return@LaunchedEffect
        updateArtworkTheme(canonicalUrl) { artworkUrl ->
            val requestMutex = artworkThemeRequestMutexes.computeIfAbsent(artworkUrl) { Mutex() }
            try {
                withContext(Dispatchers.IO) {
                    requestMutex.withLock {
                        val themeData =
                            artworkThemeCacheMutex.withLock { artworkThemeCache[artworkUrl] }
                                ?: loadArtworkTheme(context, url).also { theme ->
                                    artworkThemeCacheMutex.withLock { artworkThemeCache[artworkUrl] = theme }
                                }
                        themeData.seedColor
                    }
                }
            } finally {
                artworkThemeRequestMutexes.remove(artworkUrl, requestMutex)
            }
        }
    }

    val imageModifier =
        if (blur != null) {
            modifier.cachedArtworkBlur(blur, if (url.isBlank()) RectangleShape else shape)
        } else {
            modifier.clip(shape)
        }
    if (url.isBlank()) {
        val backgroundModifier = if (blur == null) modifier else imageModifier
        Box(
            backgroundModifier.background(MaterialTheme.colorScheme.surfaceVariant, shape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(MetrolistIcons.Default.MusicNote, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f))
        }
        return
    }
    val previewImage = artworkPreview?.image
    val previewPainter = remember(previewImage, context) { previewImage?.asPainter(context) }
    val model =
        remember(url, maxDecodeWidth, context) {
            if (maxDecodeWidth == null) {
                url
            } else {
                ImageRequest
                    .Builder(context)
                    .data(url)
                    .size(maxDecodeWidth, maxDecodeWidth)
                    .build()
            }
        }
    var hasError by remember(canonicalUrl, artworkPreview) { mutableStateOf(false) }

    Box(imageModifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        key(canonicalUrl, artworkPreview) {
            CoilAsyncImage(
                model = model,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = contentScale,
                alignment = alignment,
                placeholder = previewPainter,
                onSuccess = {
                    hasError = false
                    artworkPreview?.retain(it.result.image)
                },
                onError = { hasError = true },
                colorFilter = saturationFilter,
            )
        }
        if (hasError) {
            Icon(
                MetrolistIcons.Default.BrokenImage,
                null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f),
            )
        }
    }
}

internal suspend fun updateArtworkTheme(
    url: String,
    loadSeedColor: suspend (String) -> Color?,
) {
    val seedColor =
        if (url.isBlank()) {
            null
        } else {
            try {
                loadSeedColor(url)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                null
            }
        }
    currentCoroutineContext().ensureActive()
    UiState.artworkSeedColor = seedColor
}

private suspend fun loadArtworkTheme(
    context: PlatformContext,
    url: String,
): ArtworkThemeData {
    val request =
        ImageRequest
            .Builder(context)
            .artworkColors(url)
            .build()
    val result = SingletonImageLoader.get(context).execute(request) as? SuccessResult ?: error("Artwork theme request failed")
    val bitmap = (result.image as? BitmapImage)?.bitmap ?: error("Artwork theme request did not return a bitmap")
    return SkiaImage.makeFromBitmap(bitmap).use { rawImage ->
        val sampledImage = rawImage.scaledTo(THEME_SAMPLE_WIDTH)
        try {
            val extractor = ThemeColorExtractor()
            ArtworkThemeData(
                seedColor = extractor.extractSeedColor(sampledImage),
            )
        } finally {
            if (sampledImage !== rawImage) sampledImage.close()
        }
    }
}
