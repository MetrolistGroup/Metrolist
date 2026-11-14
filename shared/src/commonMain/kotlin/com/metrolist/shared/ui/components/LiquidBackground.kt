package com.metrolist.shared.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import coil3.BitmapImage
import coil3.Image
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import coil3.size.Precision
import com.metrolist.shared.ui.player.LocalPlayerTrackArtwork
import kotlinx.coroutines.flow.collectLatest
import kotlin.time.Duration.Companion.milliseconds

internal data class LayerArt(
    val painter: Painter,
    val pixelWidth: Float?,
    val pixelHeight: Float?,
    val image: Image? = null,
)

@Composable
internal expect fun PlatformLiquidBackgroundCanvas(
    modifier: Modifier,
    activeArt: LayerArt,
    previousArt: LayerArt?,
    crossfadeAlpha: () -> Float,
    rotationTimeMs: () -> Long,
)

private val liquidArtCache = mutableMapOf<String, LayerArt>()
private var lastRenderedArt: LayerArt? = null
private var globalLiquidRotationTimeMs = 0L

private fun cacheLiquidArt(
    key: String,
    art: LayerArt,
) {
    if (liquidArtCache.size >= 8) {
        val firstKey = liquidArtCache.keys.firstOrNull()
        if (firstKey != null) liquidArtCache.remove(firstKey)
    }
    liquidArtCache[key] = art
}

@Composable
fun LiquidBackground(
    artworkUrl: String?,
    modifier: Modifier = Modifier,
    isLyricsVisible: Boolean = false,
    animate: Boolean = true,
    isPlaying: Boolean = true,
    visible: Boolean = true,
) {
    val prepared = LocalPlayerTrackArtwork.current?.takeIf { it.url == artworkUrl }
    val context = LocalPlatformContext.current
    val request =
        remember(artworkUrl, context) {
            ImageRequest
                .Builder(context)
                .data(artworkUrl)
                .size(256, 256)
                .precision(Precision.INEXACT)
                .build()
        }
    val painterState =
        if (prepared == null) {
            val painter = key(artworkUrl) { rememberAsyncImagePainter(model = request) }
            val state by painter.state.collectAsState()
            state
        } else {
            null
        }
    val incomingArt =
        remember(prepared, painterState) {
            if (prepared != null) {
                prepared.bitmap?.let { LayerArt(BitmapPainter(it), it.width.toFloat(), it.height.toFloat(), prepared.image) }
            } else {
                (painterState as? AsyncImagePainter.State.Success)?.let { state ->
                    val bitmapImage = state.result.image as? BitmapImage
                    LayerArt(state.painter, bitmapImage?.width?.toFloat(), bitmapImage?.height?.toFloat(), state.result.image)
                }
            }
        }

    var activeArt by remember {
        mutableStateOf(if (prepared != null) incomingArt else artworkUrl?.let { liquidArtCache[it] })
    }
    var previousArt by remember {
        mutableStateOf(if (prepared == null && !artworkUrl.isNullOrBlank() && activeArt == null) lastRenderedArt else null)
    }
    val crossfadeAlpha =
        remember {
            Animatable(if (activeArt != null && previousArt != null) 0f else 1f)
        }

    LaunchedEffect(artworkUrl, painterState, prepared, incomingArt) {
        if (artworkUrl.isNullOrBlank() || painterState is AsyncImagePainter.State.Error) {
            activeArt = null
            previousArt = null
            lastRenderedArt = null
            crossfadeAlpha.snapTo(1f)
        } else if (incomingArt != null) {
            val newArt = incomingArt
            if (prepared == null) {
                cacheLiquidArt(artworkUrl, newArt)
                lastRenderedArt = newArt
            }

            val prev = activeArt ?: previousArt
            if (prev != null && (prev.painter != newArt.painter || prev.image != newArt.image)) {
                previousArt = prev
                activeArt = newArt
                crossfadeAlpha.snapTo(0f)
                crossfadeAlpha.animateTo(1f, tween(1000))
                previousArt = null
            } else {
                previousArt = null
                activeArt = newArt
                crossfadeAlpha.snapTo(1f)
            }
        }
    }

    val rotationTimeMs = remember { mutableLongStateOf(globalLiquidRotationTimeMs) }
    val visibility = LocalVisualWorkActive.current
    val rotationEnabled = animate && isPlaying && visible
    LaunchedEffect(rotationEnabled, visibility) {
        if (!rotationEnabled) return@LaunchedEffect
        visibility.collectLatest { shown ->
            if (!shown) return@collectLatest
            val origin = withFrameMillis { it } - rotationTimeMs.longValue
            while (true) {
                withPlaybackAnimationFrame(50.milliseconds) {
                    val cur = it - origin
                    rotationTimeMs.longValue = cur
                    globalLiquidRotationTimeMs = cur
                }
            }
        }
    }
    val readRotation = remember { { rotationTimeMs.longValue } }

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .clipToBounds(),
    ) {
        val hasActive = activeArt != null
        val currentActive =
            if ((prepared != null && incomingArt == null) || artworkUrl.isNullOrBlank() ||
                painterState is AsyncImagePainter.State.Error
            ) {
                null
            } else {
                activeArt ?: previousArt
            }
        val currentPrev = if (hasActive) previousArt else null

        if (currentActive != null) {
            PlatformLiquidBackgroundCanvas(
                modifier = Modifier.fillMaxSize(),
                activeArt = currentActive,
                previousArt = currentPrev,
                crossfadeAlpha = { if (hasActive) crossfadeAlpha.value else 1f },
                rotationTimeMs = readRotation,
            )
        } else {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black),
            )
        }
    }
}
