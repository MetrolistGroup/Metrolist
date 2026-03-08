/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.toPath
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.rectangle
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.play
import com.metrolist.shared.generated.resources.queue_current_paused
import com.metrolist.shared.generated.resources.queue_current_playing
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.theme.rememberReducedMotion
import com.metrolist.shared.ui.utils.toPolygonShape
import kotlinx.coroutines.flow.collectLatest
import org.jetbrains.compose.resources.stringResource
import kotlin.math.ceil

internal const val OVERLAY_SCRIM_ALPHA = 0.6f

internal const val ACTIVE_THUMBNAIL_SCRIM_ALPHA = 0.6f

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun currentSongArtworkShape(): Shape = MaterialShapes.Cookie9Sided.toPolygonShape()

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun animatedCurrentSongArtworkShape(
    isCurrent: Boolean,
    inactiveShape: Shape,
): Shape {
    val progress by animateFloatAsState(
        targetValue = if (isCurrent) 1f else 0f,
        animationSpec = if (rememberReducedMotion()) snap() else MaterialTheme.motionScheme.defaultSpatialSpec(),
        label = "currentSongArtworkShape",
    )
    val currentShape = currentSongArtworkShape()
    return remember(progress, inactiveShape, currentShape) {
        object : Shape {
            override fun createOutline(
                size: Size,
                layoutDirection: LayoutDirection,
                density: Density,
            ): Outline {
                if (progress <= 0f || size.minDimension <= 0f) {
                    return inactiveShape.createOutline(size, layoutDirection, density)
                }
                if (progress >= 1f) return currentShape.createOutline(size, layoutDirection, density)
                val inactiveOutline = inactiveShape.createOutline(size, layoutDirection, density)
                val radius = (inactiveOutline as? Outline.Rounded)?.roundRect?.topLeftCornerRadius?.x ?: 0f
                val morph =
                    Morph(
                        RoundedPolygon
                            .rectangle(
                                rounding = CornerRounding((radius * 2f / size.minDimension).coerceIn(0f, 1f)),
                            ).normalized(),
                        MaterialShapes.Cookie9Sided,
                    )
                return Outline.Generic(
                    morph.toPath(progress).apply { transform(Matrix().apply { scale(size.width, size.height) }) },
                )
            }
        }
    }
}

internal const val CURRENT_SONG_ARTWORK_ROTATION_CYCLE_MILLIS = 20_000L
private const val ARTWORK_SHAPE_SYMMETRY_DEGREES = 40f

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun rememberArtworkShapeRotation(
    spinning: Boolean,
    motionEnabled: Boolean,
): State<Float> {
    val angle = remember { Animatable(0f) }
    val motionScheme = MaterialTheme.motionScheme
    val visualWork = LocalVisualWorkActive.current
    LaunchedEffect(spinning, motionEnabled, motionScheme, visualWork) {
        if (!motionEnabled) {
            angle.snapTo(0f)
            return@LaunchedEffect
        }
        if (!spinning) {
            val target = ceil(angle.value / ARTWORK_SHAPE_SYMMETRY_DEGREES) * ARTWORK_SHAPE_SYMMETRY_DEGREES
            if (target != angle.value) {
                angle.animateTo(
                    targetValue = target,
                    animationSpec = motionScheme.defaultSpatialSpec(),
                )
            }
            return@LaunchedEffect
        }
        visualWork.collectLatest { visible ->
            if (!visible) return@collectLatest
            while (true) {
                angle.animateTo(
                    targetValue = angle.value + 360f,
                    animationSpec = tween(CURRENT_SONG_ARTWORK_ROTATION_CYCLE_MILLIS.toInt(), easing = LinearEasing),
                )
                angle.snapTo(angle.value % 360f)
            }
        }
    }
    return angle.asState()
}

@Composable
fun rememberCurrentSongArtworkRotation(
    isCurrent: Boolean,
    isPlaying: Boolean,
): State<Float> {
    val motionScale = rememberCoroutineScope().coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f
    return rememberArtworkShapeRotation(
        spinning = isCurrent && isPlaying,
        motionEnabled = isCurrent && motionScale > 0f && !rememberReducedMotion(),
    )
}

@Composable
fun BoxScope.OverlayPlayButton(visible: Boolean) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.align(Alignment.Center),
    ) {
        OverlayPlayBadge(iconSize = 20.dp)
    }
}

@Composable
fun BoxScope.SongThumbnailPlayOverlay(
    isActive: Boolean,
    isPlaying: Boolean,
    shape: Shape,
) {
    if (Settings.shared.uiEnhancements) {
        NowPlayingBarsOverlay(isActive, isPlaying)
    } else if (isActive) {
        Box(
            modifier = Modifier.matchParentSize().clip(shape).background(Color.Black.copy(alpha = ACTIVE_THUMBNAIL_SCRIM_ALPHA)),
            contentAlignment = Alignment.Center,
        ) {
            if (isPlaying) {
                NowPlayingBars(isPlaying = true, color = Color.White)
            } else {
                Icon(
                    MetrolistIcons.Rounded.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    } else {
        OverlayPlayButton(visible = true)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun BoxScope.SongThumbnailPlayingIndicator(
    isActive: Boolean,
    isPlaying: Boolean,
    shape: Shape,
) {
    AnimatedVisibility(
        visible = isActive,
        enter = fadeIn(MaterialTheme.motionScheme.fastEffectsSpec()),
        exit = fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()),
        modifier = Modifier.matchParentSize(),
    ) {
        Box(Modifier.fillMaxSize().clip(shape)) {
            Box(
                modifier =
                    Modifier
                        .align(Alignment.BottomEnd)
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (isPlaying) MetrolistIcons.Rounded.PlayArrow else MetrolistIcons.Default.Pause,
                    contentDescription =
                        stringResource(
                            if (isPlaying) Res.string.queue_current_playing else Res.string.queue_current_paused,
                        ),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun BoxScope.NowPlayingBarsOverlay(
    isActive: Boolean,
    isPlaying: Boolean,
) {
    if (!isActive) return
    Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.4f)), contentAlignment = Alignment.Center) {
        NowPlayingBars(isPlaying, Color.White)
    }
}

@Composable
fun BoxScope.AlbumPlayButton(
    visible: Boolean,
    onClick: () -> Unit,
) {
    if (Settings.shared.uiEnhancements) return
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
    ) {
        OverlayPlayBadge(onClick = onClick, iconSize = 24.dp)
    }
}

fun albumPlayOverlay(onClick: (() -> Unit)?): (@Composable BoxScope.() -> Unit)? =
    onClick?.let { click -> { AlbumPlayButton(visible = true, onClick = click) } }

@Composable
private fun OverlayPlayBadge(
    onClick: (() -> Unit)? = null,
    iconSize: Dp = 20.dp,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = OVERLAY_SCRIM_ALPHA))
                .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier),
    ) {
        Icon(
            MetrolistIcons.Rounded.PlayArrow,
            contentDescription = if (onClick != null) stringResource(Res.string.play) else null,
            tint = Color.White,
            modifier = Modifier.size(iconSize),
        )
    }
}
