package com.metrolist.shared.ui.components.lyrics

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import coil3.compose.rememberAsyncImagePainter
import com.metrolist.shared.ui.components.artworkBackdropRequest
import com.metrolist.shared.ui.player.LocalPlayerTrackArtwork
import com.metrolist.shared.ui.utils.toImageBitmap
import kotlinx.coroutines.delay

internal fun lyricsContrastScrimAlpha(
    textColor: Color,
    backgrounds: List<Color>,
): Float {
    val textLuminance = textColor.luminance()

    fun readable(alpha: Float): Boolean =
        backgrounds.all { background ->
            val luminance =
                Color.Black
                    .copy(alpha = alpha)
                    .compositeOver(background)
                    .luminance()
            (textLuminance + 0.05f) / (luminance + 0.05f) >= 4.5f
        }

    if (readable(0f)) return 0f
    var low = 0f
    var high = 1f
    repeat(16) {
        val middle = (low + high) / 2f
        if (readable(middle)) high = middle else low = middle
    }
    return high
}

internal fun lyricsPaletteTextColor(
    scheme: ColorScheme,
    lightText: Boolean,
): Color =
    if (lightText) {
        if (scheme.primaryFixed.luminance() >= scheme.onPrimaryFixed.luminance()) scheme.primaryFixed else scheme.onPrimaryFixed
    } else {
        scheme.onPrimaryFixedVariant
    }

private fun Color.upperBound(other: Color): Color = Color(maxOf(red, other.red), maxOf(green, other.green), maxOf(blue, other.blue))

internal fun saturatedLyricsArtworkColor(
    color: Color,
    matrix: ColorMatrix,
): Color {
    val values = matrix.values

    fun channel(row: Int): Float =
        (values[row * 5] * color.red + values[row * 5 + 1] * color.green + values[row * 5 + 2] * color.blue).coerceIn(0f, 1f)
    return Color(channel(0), channel(1), channel(2), color.alpha)
}

@Composable
fun LyricsContrastScrim(
    backgroundColors: List<Color>,
    artworkUrl: String? = null,
    artworkScrimAlpha: Float = 0f,
    artworkWhiteAlpha: Float = 0f,
    artworkSaturation: Float = 1f,
    isLiquid: Boolean = false,
    visible: Boolean = true,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = Modifier.fillMaxSize(),
        enter = EnterTransition.None,
        exit = fadeOut(tween(500)),
    ) {
        val textColor = resolveLyricsTextColor(Color.White)
        val context = LocalPlatformContext.current
        val prepared = LocalPlayerTrackArtwork.current?.takeIf { it.url == artworkUrl }?.bitmap
        val bitmap =
            if (artworkUrl != null && prepared == null) {
                val request = remember(context, artworkUrl) { artworkBackdropRequest(context, artworkUrl) }
                val painter = key(artworkUrl) { rememberAsyncImagePainter(request) }
                val state by painter.state.collectAsState()
                remember(state) { (state as? AsyncImagePainter.State.Success)?.result?.image?.toImageBitmap() }
            } else {
                prepared
            }
        val underlay = backgroundColors.fold(Color.Black) { bound, color -> bound.upperBound(color) }
        val (sourceUpperBound, hasTransparency) =
            remember(bitmap, artworkSaturation, isLiquid) {
                val matrix = ColorMatrix().apply { setToSaturation(if (isLiquid) 2.5f else artworkSaturation) }
                bitmap?.toPixelMap()?.let { pixels ->
                    var bound = Color.Black
                    var transparent = false
                    repeat(64) { y ->
                        repeat(64) { x ->
                            val pixel = pixels[x * pixels.width / 64, y * pixels.height / 64]
                            bound = bound.upperBound(saturatedLyricsArtworkColor(pixel, matrix))
                            if (pixel.alpha < 1f) transparent = true
                        }
                    }
                    bound to transparent
                } ?: (Color.White to true)
            }
        val artworkUpperBound = if (isLiquid || hasTransparency) sourceUpperBound.upperBound(underlay) else sourceUpperBound
        var previousUpperBound by remember { mutableStateOf(artworkUpperBound) }
        LaunchedEffect(artworkUpperBound) {
            previousUpperBound = previousUpperBound.upperBound(artworkUpperBound)
            delay(1000)
            previousUpperBound = artworkUpperBound
        }
        val backgrounds =
            if (artworkUrl != null) {
                val bound = artworkUpperBound.upperBound(previousUpperBound)
                val treated =
                    Color.White.copy(alpha = if (isLiquid) 13f / 255f else artworkWhiteAlpha).compositeOver(
                        Color.Black.copy(alpha = if (isLiquid) 128f / 255f else artworkScrimAlpha).compositeOver(bound),
                    )
                if (bitmap == null) backgroundColors + treated else listOf(treated)
            } else {
                backgroundColors
            }
        val alpha = lyricsContrastScrimAlpha(textColor, backgrounds)
        if (alpha > 0f) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = alpha)))
    }
}
