/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.ui.player

import androidx.compose.animation.Crossfade
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ToggleButtonShapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.size.Scale
import com.metrolist.shared.api.artwork.YouTubeCanvasClip
import com.metrolist.shared.settings.PlayerBackgroundStyle
import com.metrolist.shared.ui.components.BlurredArtworkBackground
import com.metrolist.shared.ui.components.LiquidBackground
import com.metrolist.shared.ui.components.LocalVisualWorkActive
import com.metrolist.shared.ui.components.PrefetchPlayerArtwork
import com.metrolist.shared.ui.components.images.AsyncImage
import com.metrolist.shared.ui.theme.uiEnhanced
import com.metrolist.shared.ui.utils.artworkHorizontalProgressiveBlur
import com.metrolist.shared.ui.utils.supportsNativeBlur
import com.metrolist.shared.ui.utils.toImageBitmap
import com.metrolist.shared.youtube.playback.PlaybackResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

internal fun lerp(
    start: Float,
    stop: Float,
    fraction: Float,
): Float = start + fraction * (stop - start)

internal data class FullscreenTransitionBounds(
    val bottomTransitionStartFraction: Float,
    val bottomTransitionEndFraction: Float,
)

@OptIn(ExperimentalAnimationApi::class)
@Composable
internal fun PlayerBackgroundArtwork(
    thumbnailUrl: String,
    artworkCrossfade: PlayerArtworkCrossfade,
    content: @Composable (String) -> Unit,
) {
    val prepared = LocalPlayerTrackArtwork.current
    val transitionState = remember { SeekableTransitionState(thumbnailUrl) }
    val transition = rememberTransition(transitionState)
    LaunchedEffect(thumbnailUrl, artworkCrossfade.nextArtworkUrl, artworkCrossfade.progress) {
        when (val next = playerBackgroundTransition(thumbnailUrl, transitionState.targetState, artworkCrossfade)) {
            is PlayerBackgroundTransition.Animate -> transitionState.animateTo(next.artworkUrl)
            is PlayerBackgroundTransition.Snap -> transitionState.snapTo(next.artworkUrl)
            is PlayerBackgroundTransition.Seek -> transitionState.seekTo(next.fraction, next.artworkUrl)
        }
    }
    transition.Crossfade(
        modifier = Modifier.fillMaxSize(),
        animationSpec = tween(500, easing = LinearEasing),
    ) { currentUrl ->
        var retained by remember { mutableStateOf<PlayerTrackArtwork?>(null) }
        retained = retainPlayerBackgroundArtwork(currentUrl, prepared, retained)
        CompositionLocalProvider(LocalPlayerTrackArtwork provides retained) {
            content(currentUrl)
        }
    }
}

@Composable
internal fun LyricsPlayerBackground(
    playerBackground: PlayerBackgroundStyle,
    thumbnailUrl: String?,
    backgroundColor: Color,
    gradientColorStops: Array<Pair<Float, Color>>,
    alpha: Float,
    artworkCrossfade: PlayerArtworkCrossfade = PlayerArtworkCrossfade(),
) {
    if (alpha <= 0f) return

    when (playerBackground) {
        PlayerBackgroundStyle.BLURRED_ARTWORK -> {
            if (thumbnailUrl != null) {
                PlayerBackgroundArtwork(thumbnailUrl, artworkCrossfade) { currentUrl ->
                    PlayerBlurredArtworkBackground(
                        thumbnailUrl = currentUrl,
                        alpha = alpha,
                    )
                }
            } else {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .alpha(alpha)
                            .background(backgroundColor),
                )
            }
        }

        PlayerBackgroundStyle.GRADIENT,
        PlayerBackgroundStyle.ANIMATED_GRADIENT,
        -> {
            val stops =
                rememberAnimatedGradientStops(
                    gradientColorStops = gradientColorStops,
                    animate = playerBackground == PlayerBackgroundStyle.ANIMATED_GRADIENT,
                )
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .alpha(alpha)
                        .drawBehind {
                            drawRect(
                                if (gradientColorStops.isNotEmpty()) {
                                    Brush.verticalGradient(
                                        colorStops = stops(),
                                    )
                                } else {
                                    Brush.verticalGradient(listOf(backgroundColor, backgroundColor))
                                },
                            )
                        }.background(Color.Black.copy(alpha = 0.2f)),
            )
        }

        PlayerBackgroundStyle.SOLID -> {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .alpha(alpha)
                        .background(backgroundColor),
            )
        }

        PlayerBackgroundStyle.LIQUID -> {
            if (thumbnailUrl != null) {
                LiquidBackground(
                    artworkUrl = thumbnailUrl,
                    modifier = Modifier.fillMaxSize().alpha(alpha),
                    animate = LocalPlayerBackgroundAnimating.current,
                    isLyricsVisible = true,
                )
            } else {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .alpha(alpha)
                            .background(backgroundColor),
                )
            }
        }
    }
}

@Composable
internal fun PlayerBlurredArtworkBackground(
    thumbnailUrl: String,
    alpha: Float,
    isLyricsVisible: Boolean = true,
) {
    val density = LocalDensity.current
    val playerBackgroundBlurRadiusPx = with(density) { 150.dp.toPx() }
    val lyricsBackgroundBlurRadiusPx = with(density) { 180.dp.toPx() }
    BlurredArtworkBackground(
        url = thumbnailUrl,
        modifier = Modifier.fillMaxSize(),
        blurRadiusPx = if (isLyricsVisible) lyricsBackgroundBlurRadiusPx else playerBackgroundBlurRadiusPx,
        scrimAlpha = 0.3f,
        lyricsGradient = isLyricsVisible,
        pendingScrimAlpha = 0.5f,
    )
}

@Composable
internal fun FullscreenThumbnailBackground(
    thumbnailUrl: String,
    playerBackground: PlayerBackgroundStyle,
    backgroundColor: Color,
    gradientColorStops: Array<Pair<Float, Color>>,
    alpha: Float,
    transitionBounds: FullscreenTransitionBounds?,
    isLandscape: Boolean,
    animatedCoverUrl: String? = null,
    animatedCoverHeaders: Map<String, String> = emptyMap(),
    animatedCoverPlaybackResource: PlaybackResource? = null,
    animatedCoverClips: List<YouTubeCanvasClip> = emptyList(),
    animatedCoverCacheKey: String? = null,
    animatedCoverVideoBytes: ByteArray? = null,
    onAnimatedCoverError: (String) -> Unit = {},
    canvasContent: (@Composable (Modifier, ((ImageBitmap?) -> Unit)?) -> Unit)? = null,
    canvasReady: Boolean = false,
    canvasKey: Pair<String, String>? = null,
    canvasTall: Boolean = false,
    artworkCrossfade: PlayerArtworkCrossfade = PlayerArtworkCrossfade(),
) {
    if (isLandscape) {
        LandscapeFullscreenThumbnailBackground(
            thumbnailUrl = thumbnailUrl,
            playerBackground = playerBackground,
            backgroundColor = backgroundColor,
            gradientColorStops = gradientColorStops,
            alpha = alpha,
            animatedCoverUrl = animatedCoverUrl,
            animatedCoverHeaders = animatedCoverHeaders,
            animatedCoverPlaybackResource = animatedCoverPlaybackResource,
            animatedCoverClips = animatedCoverClips,
            animatedCoverCacheKey = animatedCoverCacheKey,
            animatedCoverVideoBytes = animatedCoverVideoBytes,
            onAnimatedCoverError = onAnimatedCoverError,
            canvasContent = canvasContent,
            canvasReady = canvasReady,
            canvasKey = canvasKey,
            artworkCrossfade = artworkCrossfade,
        )
        return
    }

    var coverReady by remember(thumbnailUrl, animatedCoverUrl) { mutableStateOf(false) }
    var canvasFrame by remember(animatedCoverUrl, canvasKey, canvasContent != null) { mutableStateOf<ImageBitmap?>(null) }
    // Reading the bitmap here would recompose the whole background for every video frame.
    val hasClipFrame by remember(animatedCoverUrl, canvasKey, canvasContent != null) { derivedStateOf { canvasFrame != null } }
    val clipBleedAlpha by animateFloatAsState(if (hasClipFrame) 1f else 0f, tween(CANVAS_CROSSFADE_DURATION_MS))
    val videoVisible by key(thumbnailUrl, canvasKey, animatedCoverUrl) {
        animateFloatAsState(if (canvasReady || coverReady) 1f else 0f, tween(CANVAS_CROSSFADE_DURATION_MS))
    }
    val hasVideo = animatedCoverUrl != null || canvasContent != null
    val rawStart = transitionBounds?.bottomTransitionStartFraction ?: 0.58f
    val rawEnd = transitionBounds?.bottomTransitionEndFraction ?: 0.66f
    val fadeStart = rawStart.coerceIn(0f, 0.75f)
    val mirrorSeam =
        rawEnd
            .coerceAtLeast(fadeStart + 0.06f)
            .coerceIn(0f, 0.85f)
    val statusBarFadeHeight = WindowInsets.systemBars.asPaddingValues().calculateTopPadding() + 56.dp

    fun fadeMask(
        start: Float,
        seam: Float,
    ) = Brush.verticalGradient(
        colorStops =
            arrayOf(
                0.0f to Color.Black,
                (start - 0.03f).coerceAtLeast(0f) to Color.Black,
                start to Color.Black,
                seam to Color.Transparent,
                1.0f to Color.Transparent,
            ),
    )
    BoxWithConstraints(
        modifier =
            Modifier
                .fillMaxSize()
                .clipToBounds()
                .alpha(alpha),
    ) {
        val density = LocalDensity.current
        val seam = if (canvasTall) (maxWidth * (4f / 3f) / maxHeight).coerceIn(0.3f, 0.85f) else mirrorSeam
        val seamFadeStart = if (canvasTall) (seam - (mirrorSeam - fadeStart)).coerceAtLeast(0f) else fadeStart
        val artworkFadeMask = fadeMask(seamFadeStart, seam)
        val artworkSize =
            with(density) {
                IntSize(maxWidth.roundToPx().coerceAtLeast(1), (maxHeight * seam).roundToPx().coerceAtLeast(1))
            }
        // Animated artwork keeps the cropped full-bleed layout; only static covers get the uncropped bleed.
        val usesArtworkBleed = !hasVideo && !canvasTall
        if (!usesArtworkBleed) {
            when (playerBackground) {
                PlayerBackgroundStyle.GRADIENT,
                PlayerBackgroundStyle.ANIMATED_GRADIENT,
                -> {
                    val stops =
                        rememberAnimatedGradientStops(
                            gradientColorStops,
                            playerBackground == PlayerBackgroundStyle.ANIMATED_GRADIENT,
                        )
                    if (gradientColorStops.isNotEmpty()) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .drawBehind { drawRect(Brush.verticalGradient(colorStops = stops())) },
                        )
                    }
                }

                PlayerBackgroundStyle.LIQUID -> {
                    LiquidBackground(
                        artworkUrl = thumbnailUrl,
                        modifier = Modifier.fillMaxSize(),
                        animate = LocalPlayerBackgroundAnimating.current,
                    )
                }

                PlayerBackgroundStyle.SOLID,
                PlayerBackgroundStyle.BLURRED_ARTWORK,
                -> {}
            }
        }

        PlayerBackgroundArtwork(thumbnailUrl, artworkCrossfade) { currentUrl ->
            val artworkBitmap = rememberFullscreenArtworkBitmap(currentUrl, artworkSize)
            if (usesArtworkBleed) {
                FullscreenArtworkBleed(
                    url = currentUrl,
                    artwork = artworkBitmap,
                    viewport = with(density) { IntSize(maxWidth.roundToPx(), maxHeight.roundToPx()) },
                )
                return@PlayerBackgroundArtwork
            }
            val backdropBitmap = if (canvasTall) null else canvasFrame ?: artworkBitmap
            if (backdropBitmap != null && hasVideo) {
                FullscreenBlurredBackdropCanvas(
                    artwork = backdropBitmap,
                    scrimAlpha = BLURRED_ARTWORK_SCRIM_ALPHA,
                )
            }

            if (artworkBitmap != null) {
                Box(Modifier.fillMaxSize().graphicsLayer { this.alpha = 1f - videoVisible }) {
                    FullscreenSharpArtworkCanvas(
                        artwork = artworkBitmap,
                        mirrorSeamFraction = seam,
                        alphaMask = artworkFadeMask,
                    )
                }

                if (playerBackground == PlayerBackgroundStyle.BLURRED_ARTWORK) {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .drawWithContent {
                                    drawRect(
                                        brush =
                                            Brush.verticalGradient(
                                                colorStops =
                                                    arrayOf(
                                                        0.0f to Color.Transparent,
                                                        seam to Color.Transparent,
                                                        0.92f to Color.Black.copy(alpha = 0.18f),
                                                        1.0f to Color.Black.copy(alpha = 0.38f),
                                                    ),
                                            ),
                                    )
                                },
                    )
                }

                if (canvasTall) {
                    FullscreenMirroredBlurCanvas(
                        artwork = artworkBitmap,
                        mirrorSeamFraction = seam,
                        mirrorFadeStart = seamFadeStart,
                        scrimAlpha = BLURRED_ARTWORK_SCRIM_ALPHA,
                    )
                    ClipFrameBleed(
                        frame = { canvasFrame },
                        alpha = { clipBleedAlpha },
                        seam = seam,
                        fadeStart = seamFadeStart,
                    )
                }
            } else {
                AsyncImage(
                    url = currentUrl,
                    contentDescription = null,
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                            .drawWithContent {
                                drawContent()
                                drawRect(brush = artworkFadeMask, blendMode = BlendMode.DstIn)
                            },
                    contentScale = ContentScale.Crop,
                )
            }
        }

        if (animatedCoverUrl != null || canvasContent != null) {
            val videoModifier =
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        this.alpha = if (canvasContent != null) 1f else videoVisible
                        compositingStrategy = CompositingStrategy.Offscreen
                    }.drawWithContent {
                        drawContent()
                        drawRect(brush = artworkFadeMask, blendMode = BlendMode.DstIn)
                    }
            if (canvasContent != null && canvasTall) {
                Box(videoModifier) {
                    canvasContent(Modifier.fillMaxWidth().aspectRatio(3f / 4f).align(Alignment.TopCenter)) { canvasFrame = it }
                }
            } else if (canvasContent != null) {
                canvasContent(videoModifier) { canvasFrame = it }
            } else if (animatedCoverUrl != null) {
                AnimatedCoverVideo(
                    url = animatedCoverUrl,
                    cacheKey = animatedCoverCacheKey,
                    videoBytes = animatedCoverVideoBytes,
                    headers = animatedCoverHeaders,
                    playbackResource = animatedCoverPlaybackResource,
                    clips = animatedCoverClips,
                    onError = {
                        coverReady = false
                        canvasFrame = null
                        onAnimatedCoverError(animatedCoverUrl)
                    },
                    onReadyChanged = {
                        coverReady = it
                        if (!it) canvasFrame = null
                    },
                    onFrame = { canvasFrame = it },
                    modifier = videoModifier,
                )
            }
        }

        Box(
            modifier =
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(statusBarFadeHeight)
                    .background(
                        Brush.verticalGradient(
                            colorStops =
                                arrayOf(
                                    0.0f to Color.Black.copy(alpha = 0.52f),
                                    0.45f to Color.Black.copy(alpha = 0.20f),
                                    1.0f to Color.Transparent,
                                ),
                        ),
                    ),
        )
    }
}

@Composable
private fun LandscapeFullscreenThumbnailBackground(
    thumbnailUrl: String,
    playerBackground: PlayerBackgroundStyle,
    backgroundColor: Color,
    gradientColorStops: Array<Pair<Float, Color>>,
    alpha: Float,
    animatedCoverUrl: String? = null,
    animatedCoverHeaders: Map<String, String> = emptyMap(),
    animatedCoverPlaybackResource: PlaybackResource? = null,
    animatedCoverClips: List<YouTubeCanvasClip> = emptyList(),
    animatedCoverCacheKey: String? = null,
    animatedCoverVideoBytes: ByteArray? = null,
    onAnimatedCoverError: (String) -> Unit = {},
    canvasContent: (@Composable (Modifier, ((ImageBitmap?) -> Unit)?) -> Unit)? = null,
    canvasReady: Boolean = false,
    canvasKey: Pair<String, String>? = null,
    artworkCrossfade: PlayerArtworkCrossfade = PlayerArtworkCrossfade(),
) {
    var coverReady by remember(thumbnailUrl, animatedCoverUrl) { mutableStateOf(false) }
    var canvasFrame by remember(animatedCoverUrl, canvasKey, canvasContent != null) { mutableStateOf<ImageBitmap?>(null) }
    val videoVisible by key(thumbnailUrl, canvasKey, animatedCoverUrl) {
        animateFloatAsState(if (canvasReady || coverReady) 1f else 0f, tween(CANVAS_CROSSFADE_DURATION_MS))
    }
    val hasVideo = animatedCoverUrl != null || canvasContent != null

    BoxWithConstraints(
        modifier =
            Modifier
                .fillMaxSize()
                .clipToBounds()
                .alpha(alpha)
                .background(backgroundColor),
    ) {
        val density = LocalDensity.current
        val fullWidthPx = with(density) { maxWidth.roundToPx().coerceAtLeast(1) }
        val fullHeightPx = with(density) { maxHeight.roundToPx().coerceAtLeast(1) }
        val artworkSize = IntSize(fullWidthPx, fullHeightPx)

        if (playerBackground == PlayerBackgroundStyle.GRADIENT || playerBackground == PlayerBackgroundStyle.ANIMATED_GRADIENT) {
            val stops = rememberAnimatedGradientStops(gradientColorStops, playerBackground == PlayerBackgroundStyle.ANIMATED_GRADIENT)
            if (gradientColorStops.isNotEmpty()) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .drawBehind { drawRect(Brush.horizontalGradient(colorStops = stops())) },
                )
            }
        } else if (playerBackground == PlayerBackgroundStyle.LIQUID) {
            LiquidBackground(
                artworkUrl = thumbnailUrl,
                modifier = Modifier.fillMaxSize(),
                animate = LocalPlayerBackgroundAnimating.current,
            )
        }

        val layoutDirection = LocalLayoutDirection.current
        val cutoutStart = WindowInsets.displayCutout.asPaddingValues().calculateStartPadding(layoutDirection)

        val artWidth = maxOf(maxHeight * 1.25f, maxWidth * 0.62f).coerceAtMost(maxWidth * 0.70f)
        val artWidthPx = with(density) { artWidth.toPx() }

        PlayerBackgroundArtwork(thumbnailUrl, artworkCrossfade) { currentUrl ->
            val artworkBitmap = rememberFullscreenArtworkBitmap(currentUrl, artworkSize)
            val backdropBitmap = canvasFrame ?: artworkBitmap
            if (backdropBitmap != null) {
                val bw = backdropBitmap.width.toFloat()
                val bh = backdropBitmap.height.toFloat()
                val cropOverride =
                    if (bw > 0f && bh > 0f) {
                        val scale = maxOf(fullWidthPx.toFloat() / bw, fullHeightPx.toFloat() / bh)
                        val imgW = bw * scale
                        val imgH = bh * scale
                        floatArrayOf(
                            (fullWidthPx.toFloat() - imgW) / 2f,
                            (fullHeightPx.toFloat() - imgH) / 2f,
                            imgW,
                            imgH,
                        )
                    } else {
                        null
                    }
                FullscreenBlurredBackdropCanvas(
                    artwork = backdropBitmap,
                    blurRadius = FullscreenBackdropBlurRadius,
                    scrimAlpha = 0.10f,
                    cropOverride = cropOverride,
                )
            }
        }

        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            colorStops =
                                arrayOf(
                                    0.0f to Color.Transparent,
                                    0.50f to Color.Transparent,
                                    0.78f to Color.Black.copy(alpha = 0.12f),
                                    1.0f to Color.Black.copy(alpha = 0.22f),
                                ),
                        ),
                    ),
        )

        val statusBarFadeHeight = WindowInsets.systemBars.asPaddingValues().calculateTopPadding() + 24.dp
        Box(
            modifier =
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(statusBarFadeHeight)
                    .background(
                        Brush.verticalGradient(
                            colorStops =
                                arrayOf(
                                    0.0f to Color.Black.copy(alpha = 0.35f),
                                    0.60f to Color.Black.copy(alpha = 0.12f),
                                    1.0f to Color.Transparent,
                                ),
                        ),
                    ),
        )

        val leftTransitionWidth = if (cutoutStart > 0.dp) maxOf(cutoutStart + 48.dp, artWidth * 0.18f) else 0.dp
        val leftProgressiveFraction =
            if (leftTransitionWidth > 0.dp) {
                (leftTransitionWidth / artWidth).coerceIn(0.15f, 0.25f)
            } else {
                0f
            }
        val leftFadeFraction = leftProgressiveFraction * 0.90f
        val fadeStartFraction = 0.85f
        val fadeEndFraction = 1.0f
        val artworkFadeMask =
            remember(leftFadeFraction, fadeStartFraction, fadeEndFraction) {
                if (leftFadeFraction > 0f) {
                    Brush.horizontalGradient(
                        colorStops =
                            arrayOf(
                                0.00f to Color.Transparent,
                                leftFadeFraction * 0.20f to Color.Black.copy(alpha = 0.25f),
                                leftFadeFraction * 0.40f to Color.Black.copy(alpha = 0.60f),
                                leftFadeFraction * 0.73f to Color.Black.copy(alpha = 0.90f),
                                leftFadeFraction to Color.Black,
                                fadeStartFraction to Color.Black,
                                0.89f to Color.Black.copy(alpha = 0.90f),
                                0.94f to Color.Black.copy(alpha = 0.60f),
                                0.97f to Color.Black.copy(alpha = 0.25f),
                                fadeEndFraction to Color.Transparent,
                            ),
                    )
                } else {
                    Brush.horizontalGradient(
                        colorStops =
                            arrayOf(
                                0.00f to Color.Black,
                                fadeStartFraction to Color.Black,
                                0.89f to Color.Black.copy(alpha = 0.90f),
                                0.94f to Color.Black.copy(alpha = 0.60f),
                                0.97f to Color.Black.copy(alpha = 0.25f),
                                fadeEndFraction to Color.Transparent,
                            ),
                    )
                }
            }

        Box(
            modifier =
                Modifier
                    .fillMaxHeight()
                    .width(artWidth)
                    .align(Alignment.CenterStart)
                    .artworkHorizontalProgressiveBlur(
                        leftStartFraction = if (leftProgressiveFraction > 0f) 0.02f else 0f,
                        leftEndFraction = leftProgressiveFraction,
                        rightStartFraction = 0.82f,
                        rightEndFraction = 0.98f,
                        maxRadius = 56.dp,
                    ),
        ) {
            PlayerBackgroundArtwork(thumbnailUrl, artworkCrossfade) { currentUrl ->
                if (hasVideo) {
                    val videoReady = (canvasContent != null && canvasReady) || (animatedCoverUrl != null && coverReady)
                    if (!videoReady) {
                        AsyncImage(
                            url = currentUrl,
                            contentDescription = null,
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                                    .drawWithContent {
                                        drawContent()
                                        drawRect(brush = artworkFadeMask, blendMode = BlendMode.DstIn)
                                    },
                            contentScale = ContentScale.Crop,
                        )
                    }
                } else {
                    AsyncImage(
                        url = currentUrl,
                        contentDescription = null,
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                                .drawWithContent {
                                    drawContent()
                                    drawRect(brush = artworkFadeMask, blendMode = BlendMode.DstIn)
                                },
                        contentScale = ContentScale.Crop,
                    )
                }
            }

            if (animatedCoverUrl != null || canvasContent != null) {
                val videoModifier =
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            this.alpha = if (canvasContent != null) 1f else videoVisible
                            compositingStrategy = CompositingStrategy.Offscreen
                        }.drawWithContent {
                            drawContent()
                            drawRect(brush = artworkFadeMask, blendMode = BlendMode.DstIn)
                        }
                if (canvasContent != null) {
                    canvasContent(videoModifier) { canvasFrame = it }
                } else if (animatedCoverUrl != null) {
                    AnimatedCoverVideo(
                        url = animatedCoverUrl,
                        cacheKey = animatedCoverCacheKey,
                        videoBytes = animatedCoverVideoBytes,
                        headers = animatedCoverHeaders,
                        playbackResource = animatedCoverPlaybackResource,
                        clips = animatedCoverClips,
                        onError = {
                            coverReady = false
                            onAnimatedCoverError(animatedCoverUrl)
                        },
                        onReadyChanged = { coverReady = it },
                        modifier = videoModifier,
                        onFrame = { canvasFrame = it },
                    )
                }
            }
        }
    }
}

@Composable
private fun FullscreenArtworkBackdropCanvas(
    artwork: ImageBitmap,
    scrimAlpha: Float,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val fullWidthPx = with(density) { maxWidth.toPx() }
        val fullHeightPx = with(density) { maxHeight.toPx() }
        val artworkTransform =
            remember(artwork, fullWidthPx, fullHeightPx) {
                calculateFullscreenCropTransform(
                    imageWidth = artwork.width.toFloat(),
                    imageHeight = artwork.height.toFloat(),
                    dstWidth = fullWidthPx,
                    dstHeight = fullHeightPx,
                )
            }

        Canvas(
            modifier =
                Modifier
                    .fillMaxSize()
                    .drawWithContent {
                        drawContent()
                        drawRect(Color.Black.copy(alpha = scrimAlpha))
                    },
        ) {
            drawFullscreenCrop(artwork, artworkTransform)
        }
    }
}

@Composable
private fun rememberFullscreenArtworkBitmap(
    thumbnailUrl: String,
    targetSize: IntSize,
): ImageBitmap? {
    LocalPlayerTrackArtwork.current?.takeIf { it.url == thumbnailUrl }?.let { return it.bitmap }
    val context = LocalPlatformContext.current
    var artwork by remember { mutableStateOf<ImageBitmap?>(null) }

    val request =
        remember(context, thumbnailUrl, targetSize) {
            ImageRequest
                .Builder(context)
                .data(thumbnailUrl)
                .size(targetSize.width, targetSize.height)
                .scale(Scale.FILL)
                .build()
        }
    PrefetchPlayerArtwork(request, targetSize)
    LaunchedEffect(request) {
        val loaded =
            withContext(Dispatchers.IO) {
                val result = runCatching { SingletonImageLoader.get(context).execute(request) }.getOrNull()
                (result as? SuccessResult)?.image?.toImageBitmap()
            }
        if (loaded != null) {
            artwork = loaded
        }
    }

    return artwork
}

private data class FullscreenCropTransform(
    val offsetX: Float,
    val offsetY: Float,
    val width: Float,
    val height: Float,
)

private fun calculateFullscreenCropTransform(
    imageWidth: Float,
    imageHeight: Float,
    dstWidth: Float,
    dstHeight: Float,
): FullscreenCropTransform {
    val scale = max(dstWidth / imageWidth, dstHeight / imageHeight)
    val width = imageWidth * scale
    val height = imageHeight * scale
    return FullscreenCropTransform(
        offsetX = (dstWidth - width) / 2f,
        offsetY = (dstHeight - height) / 2f,
        width = width,
        height = height,
    )
}

private fun DrawScope.drawFullscreenCrop(
    artwork: ImageBitmap,
    transform: FullscreenCropTransform,
) {
    drawImage(
        image = artwork,
        dstOffset = IntOffset(transform.offsetX.roundToInt(), transform.offsetY.roundToInt()),
        dstSize = IntSize(transform.width.roundToInt(), transform.height.roundToInt()),
    )
}

@Composable
private fun FullscreenBlurredBackdropCanvas(
    artwork: ImageBitmap,
    blurRadius: Dp = FullscreenBackdropBlurRadius,
    scrimAlpha: Float = 0f,
    cropOverride: FloatArray? = null,
) {
    BlurredArtworkBackground(
        artwork = artwork,
        modifier = Modifier.fillMaxSize(),
        blurRadiusPx = with(LocalDensity.current) { blurRadius.toPx() },
        scrimAlpha = if (supportsNativeBlur()) scrimAlpha else max(scrimAlpha, 0.34f),
        cropOverride = cropOverride,
    )
}

@Composable
private fun FullscreenMirroredBlurCanvas(
    artwork: ImageBitmap,
    mirrorSeamFraction: Float,
    mirrorFadeStart: Float,
    blurRadius: Dp = FullscreenBottomBlurRadius,
    scrimAlpha: Float = 0f,
) {
    BlurredArtworkBackground(
        artwork = artwork,
        modifier = Modifier.fillMaxSize(),
        blurRadiusPx = with(LocalDensity.current) { blurRadius.toPx() },
        scrimAlpha = if (supportsNativeBlur()) scrimAlpha else max(scrimAlpha, 0.24f),
        mirrorSeam = mirrorSeamFraction,
        mirrorFadeStart = mirrorFadeStart,
    )
}

@Composable
private fun ClipFrameBleed(
    frame: () -> ImageBitmap?,
    alpha: () -> Float,
    seam: Float,
    fadeStart: Float,
) {
    val bitmap = frame() ?: return
    Box(Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha() }) {
        FullscreenMirroredBlurCanvas(
            artwork = bitmap,
            mirrorSeamFraction = seam,
            mirrorFadeStart = fadeStart,
            scrimAlpha = BLURRED_ARTWORK_SCRIM_ALPHA,
        )
    }
}

@Composable
private fun FullscreenSharpArtworkCanvas(
    artwork: ImageBitmap,
    mirrorSeamFraction: Float,
    alphaMask: Brush,
) {
    BoxWithConstraints(
        modifier =
            Modifier
                .fillMaxSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(
                        brush = alphaMask,
                        blendMode = BlendMode.DstIn,
                    )
                },
    ) {
        val density = LocalDensity.current
        val fullWidthPx = with(density) { maxWidth.toPx() }
        val fullHeightPx = with(density) { maxHeight.toPx() }
        val zoneHeightPx = fullHeightPx * mirrorSeamFraction
        val artworkTransform =
            remember(artwork, fullWidthPx, zoneHeightPx) {
                calculateFullscreenCropTransform(
                    imageWidth = artwork.width.toFloat(),
                    imageHeight = artwork.height.toFloat(),
                    dstWidth = fullWidthPx,
                    dstHeight = zoneHeightPx,
                )
            }

        Canvas(modifier = Modifier.fillMaxSize()) {
            drawFullscreenCrop(artwork, artworkTransform)
        }
    }
}

internal const val FULLSCREEN_TITLE_TRANSITION_OFFSET_FRACTION = 0.08f
internal const val FULLSCREEN_MIN_FADE_SPAN_FRACTION = 0.18f
internal val LandscapePlayerHorizontalPadding = 28.dp
internal val LandscapePlayerVerticalPadding = 20.dp
internal val LandscapePlayerPaneGap = 56.dp
internal val LandscapePlayerArtworkSize = 392.dp
internal const val LANDSCAPE_ARTWORK_HEIGHT_FRACTION = 0.82f
internal const val LANDSCAPE_ARTWORK_CONTROLS_WIDTH_FRACTION = 0.85f
internal val PlayerArtworkCornerRadius = 12.dp
private const val LANDSCAPE_FULLSCREEN_ARTWORK_BACKDROP_SCRIM_ALPHA = 0.48f
private val FullscreenBackdropBlurRadius = 48.dp
private val FullscreenBottomBlurRadius = 24.dp
private const val BLURRED_ARTWORK_SCRIM_ALPHA = 0.12f
internal const val BLURRED_ARTWORK_FULL_SCRIM_ALPHA = 0.5f
internal const val LEGACY_BLUR_FALLBACK_SCRIM_ALPHA = 0.56f

@Composable
fun PlayerGradientBackground(
    colors: List<Color>,
    animate: Boolean,
    modifier: Modifier = Modifier,
    active: Boolean = true,
    content: @Composable () -> Unit = {},
) {
    val stops = rememberAnimatedGradientStops(remember(colors) { playerGradientStops(colors) }, animate, active)
    Box(
        modifier.fillMaxSize().drawBehind {
            val colorStops = stops()
            if (colorStops.isNotEmpty()) drawRect(Brush.verticalGradient(colorStops = colorStops))
        },
    ) {
        content()
    }
}

internal fun playerGradientStops(colors: List<Color>): Array<Pair<Float, Color>> =
    when {
        colors.size >= 3 -> arrayOf(0.0f to colors[0], 0.5f to colors[1], 1.0f to colors[2])
        colors.isNotEmpty() -> arrayOf(0.0f to colors[0], 0.6f to colors[0].copy(alpha = 0.7f), 1.0f to Color.Black)
        else -> emptyArray()
    }

@Composable
internal fun rememberAnimatedGradientStops(
    gradientColorStops: Array<Pair<Float, Color>>,
    animate: Boolean,
    active: Boolean = true,
): () -> Array<Pair<Float, Color>> {
    val transitioningStops =
        gradientColorStops
            .map { (position, color) ->
                val animatedColor by animateColorAsState(color, tween(500))
                position to animatedColor
            }.toTypedArray()
    if (!animate || transitioningStops.size < 2) return { transitioningStops }
    val motionEnabled = LocalPlayerBackgroundAnimating.current && active
    val visibility = LocalVisualWorkActive.current

    val colors = transitioningStops.map { it.second }
    var gradientPhase by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(colors.size, motionEnabled, visibility) {
        if (!motionEnabled) return@LaunchedEffect
        visibility.collectLatest { shown ->
            if (!shown) return@collectLatest
            var lastUpdateTime = withFrameMillis { it }
            while (true) {
                delay(50.milliseconds)
                val updateTime = withFrameMillis { it }
                val elapsedMillis = (updateTime - lastUpdateTime).coerceIn(0L, 100L)
                gradientPhase = (gradientPhase + elapsedMillis * 0.00025f) % colors.size
                lastUpdateTime = updateTime
            }
        }
    }

    return {
        val baseIndex = gradientPhase.toInt()
        val fraction = gradientPhase - baseIndex

        fun colorAt(offset: Int): Color {
            val start = colors[(baseIndex + offset) % colors.size]
            val stop = colors[(baseIndex + offset + 1) % colors.size]
            return interpolatePlayerGradientColor(start, stop, fraction)
        }

        arrayOf(
            0.0f to colorAt(0),
            0.5f to colorAt(1),
            1.0f to colorAt(2),
        )
    }
}

internal const val ALBUM_ART_SHARED_KEY = "player_album_art"

internal fun Shape.asStaticShape(): Shape =
    object : Shape {
        override fun createOutline(
            size: Size,
            layoutDirection: LayoutDirection,
            density: Density,
        ): Outline = this@asStaticShape.createOutline(size, layoutDirection, density)
    }

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun staticConnectedLeadingButtonShapes(): ToggleButtonShapes {
    val shape = ButtonGroupDefaults.connectedLeadingButtonShape.asStaticShape()
    val pressShape = ButtonGroupDefaults.connectedLeadingButtonPressShape.asStaticShape()
    return ButtonGroupDefaults.connectedLeadingButtonShapes(
        shape = shape,
        pressedShape = pressShape,
        checkedShape = shape,
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun staticConnectedTrailingButtonShapes(): ToggleButtonShapes {
    val shape = ButtonGroupDefaults.connectedTrailingButtonShape.asStaticShape()
    val pressShape = ButtonGroupDefaults.connectedTrailingButtonPressShape.asStaticShape()
    return ButtonGroupDefaults.connectedTrailingButtonShapes(
        shape = shape,
        pressedShape = pressShape,
        checkedShape = shape,
    )
}
