/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.shared.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.metrolist.shared.constants.MiniPlayerHeight
import com.metrolist.shared.constants.ModernMiniPlayerHorizontalPadding
import com.metrolist.shared.constants.modernMiniPlayerHeight
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.loading
import com.metrolist.shared.generated.resources.next
import com.metrolist.shared.generated.resources.pause
import com.metrolist.shared.generated.resources.play
import com.metrolist.shared.generated.resources.previous
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.isLive
import com.metrolist.shared.model.isVideoSong
import com.metrolist.shared.model.previewThumbnailUrl
import com.metrolist.shared.settings.MiniPlayerType
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.settings.displayName
import com.metrolist.shared.ui.components.images.AsyncImage
import com.metrolist.shared.ui.design.expressive.components.ExpressiveMiniPlayer
import com.metrolist.shared.ui.haptics.rememberMetrolistHaptics
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.theme.getGradientColors
import com.metrolist.shared.ui.theme.rememberArtworkSeedColor
import com.metrolist.shared.ui.utils.artworkBlur
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import kotlin.math.absoluteValue

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MiniPlayer(
    song: SongItem,
    isPlaying: Boolean,
    isLoading: Boolean,
    positionMs: () -> Long,
    durationMs: Long,
    onTogglePlay: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
    isLiked: Boolean = false,
    onLike: (() -> Unit)? = null,
    artistId: String? = null,
    isArtistSubscribed: Boolean = false,
    onSubscribeToggle: (() -> Unit)? = null,
    onAddToPlaylist: (() -> Unit)? = null,
    canSkipNext: Boolean = true,
    canSkipPrevious: Boolean = true,
    error: String? = null,
    isActive: Boolean = true,
    playbackEnabled: Boolean = true,
) {
    val haptics = rememberMetrolistHaptics()
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val active = isActive && lifecycleState == Lifecycle.State.RESUMED
    val marqueeModifier = rememberTrackMarquee(song.id, active, 3_000L)
    if (Settings.shared.miniPlayerType == MiniPlayerType.EXPRESSIVE) {
        val pixelSensitivity = (173f - Settings.shared.swipeSensitivity.coerceIn(0f, 100f)) / 100f
        ExpressiveMiniPlayer(
            song = song,
            isPlaying = isPlaying,
            isLoading = isLoading,
            active = active,
            onTogglePlay = {
                haptics.toggle(!isPlaying)
                onTogglePlay()
            },
            onSkipNext = onSkipNext,
            onSkipPrevious = onSkipPrevious,
            onExpand = onExpand,
            canSkipNext = canSkipNext,
            canSkipPrevious = canSkipPrevious,
            playbackEnabled = playbackEnabled,
            swipeThresholdPx = with(LocalDensity.current) { 80.dp.toPx() } * pixelSensitivity,
            isVisible = true,
            modifier = modifier.padding(horizontal = 12.dp),
            progress = { if (durationMs > 0L) (positionMs().toFloat() / durationMs).coerceIn(0f, 1f) else 0f },
            progressAvailable = !song.isLive && durationMs > 0L,
            isLiked = isLiked,
            isArtistSubscribed = isArtistSubscribed,
            onLike = onLike,
            onSubscribeToggle = onSubscribeToggle.takeIf { !artistId.isNullOrBlank() },
            onAddToPlaylist = onAddToPlaylist,
        )
        return
    }
    val isModern = Settings.shared.miniPlayerType == MiniPlayerType.MODERN
    val isVideoSong = song.isVideoSong
    val cornerRadius = if (isModern) modernMiniPlayerHeight(LocalDensity.current) / 2 else 16.dp

    val bgStyle = Settings.shared.miniPlayerBackgroundStyle
    val useSolidBlack = bgStyle == "SOLID_BLACK"
    val useBlur = bgStyle == "BLURRED_ARTWORK" && song.thumbnail != null
    val useGradient = bgStyle == "GRADIENT"
    val baseSeedColor = MaterialTheme.colorScheme.primary
    val artworkSeedColor = if (useGradient && active) rememberArtworkSeedColor(song.thumbnail) else null
    val effectiveSeedColor = artworkSeedColor ?: baseSeedColor
    val gradientColors = remember(effectiveSeedColor) { getGradientColors(effectiveSeedColor) }

    val backgroundColor = if (useSolidBlack) Color.Black else MaterialTheme.colorScheme.surfaceContainer
    val contentColor = if (useSolidBlack || useBlur || useGradient) Color.White else MaterialTheme.colorScheme.onSurface
    val secondaryContentColor = if (useBlur) contentColor else contentColor.copy(alpha = 0.7f)
    val buttonBorderColor = contentColor.copy(alpha = 0.25f)

    val layoutDirection = LocalLayoutDirection.current
    val playbackAction = stringResource(if (isPlaying) Res.string.pause else Res.string.play)
    val coroutineScope = rememberCoroutineScope()
    var dragOffsetX by remember { mutableFloatStateOf(0f) }
    var returnAnimation by remember { mutableStateOf<Job?>(null) }
    val sensitivityFactor = (173f - Settings.shared.swipeSensitivity.coerceIn(0f, 100f)) / 100f
    val swipeThreshold = with(LocalDensity.current) { 80.dp.toPx() } * sensitivityFactor
    val progress = { if (durationMs > 0L) (positionMs().toFloat() / durationMs).coerceIn(0f, 1f) else 0f }

    PlayerSheetSurface(
        modifier =
            modifier
                .fillMaxWidth()
                .height(if (isModern) modernMiniPlayerHeight(LocalDensity.current) else MiniPlayerHeight)
                .padding(horizontal = if (isModern) ModernMiniPlayerHorizontalPadding else 0.dp)
                .graphicsLayer {
                    translationX = dragOffsetX
                }.pointerInput(canSkipNext, canSkipPrevious, layoutDirection, swipeThreshold) {
                    detectHorizontalDragGestures(
                        onDragStart = {
                            returnAnimation?.cancel()
                            dragOffsetX = 0f
                        },
                        onDragEnd = {
                            val logicalOffset = if (layoutDirection == LayoutDirection.Rtl) -dragOffsetX else dragOffsetX
                            if (logicalOffset < -swipeThreshold && canSkipNext) {
                                onSkipNext()
                            } else if (logicalOffset > swipeThreshold && canSkipPrevious) {
                                onSkipPrevious()
                            }
                            returnAnimation?.cancel()
                            returnAnimation =
                                coroutineScope.launch {
                                    Animatable(dragOffsetX).animateTo(
                                        targetValue = 0f,
                                        animationSpec =
                                            spring(
                                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                                stiffness = Spring.StiffnessLow,
                                            ),
                                    ) {
                                        dragOffsetX = value
                                    }
                                }
                        },
                        onDragCancel = {
                            returnAnimation?.cancel()
                            returnAnimation =
                                coroutineScope.launch {
                                    Animatable(dragOffsetX).animateTo(0f) { dragOffsetX = value }
                                }
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            dragOffsetX += dragAmount
                            change.consume()
                        },
                    )
                },
        onClick = onExpand,
        cornerRadius = cornerRadius,
        elevation = 8.dp,
        color = backgroundColor,
        border =
            BorderStroke(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
            ),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (useBlur) {
                AsyncImage(
                    url = song.previewThumbnailUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().artworkBlur(24.dp, backgroundColor),
                    contentScale = ContentScale.Crop,
                )
                Box(
                    modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)),
                )
            } else if (useGradient) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .background(Brush.horizontalGradient(gradientColors))
                            .background(Color.Black.copy(alpha = 0.2f)),
                )
            }

            if (isModern) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    val trackColor = buttonBorderColor.copy(alpha = 0.2f)
                    val primaryColor = MaterialTheme.colorScheme.primary
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier =
                            Modifier
                                .size(48.dp)
                                .drawWithCache {
                                    val strokeWidthPx = 3.dp.toPx()
                                    val inset = strokeWidthPx / 2
                                    val outline =
                                        Path().apply {
                                            addRoundRect(
                                                RoundRect(inset, inset, size.width - inset, size.height - inset, CornerRadius(6.dp.toPx())),
                                                direction = Path.Direction.Clockwise,
                                            )
                                        }
                                    val measure = PathMeasure().apply { setPath(outline, false) }
                                    val playedOutline = Path()
                                    onDrawWithContent {
                                        drawContent()
                                        if (isVideoSong) {
                                            drawPath(outline, trackColor, style = Stroke(strokeWidthPx))
                                            playedOutline.reset()
                                            measure.getSegment(0f, measure.length * progress(), playedOutline)
                                            drawPath(playedOutline, primaryColor, style = Stroke(strokeWidthPx, cap = StrokeCap.Round))
                                        } else {
                                            val diameter = size.minDimension - strokeWidthPx
                                            val topLeft = Offset((size.width - diameter) / 2, (size.height - diameter) / 2)
                                            val arcSize = Size(diameter, diameter)

                                            drawArc(
                                                color = trackColor,
                                                startAngle = 0f,
                                                sweepAngle = 360f,
                                                useCenter = false,
                                                topLeft = topLeft,
                                                size = arcSize,
                                                style = Stroke(width = strokeWidthPx),
                                            )
                                            drawArc(
                                                color = primaryColor,
                                                startAngle = -90f,
                                                sweepAngle = 360f * progress(),
                                                useCenter = false,
                                                topLeft = topLeft,
                                                size = arcSize,
                                                style = Stroke(width = strokeWidthPx, cap = StrokeCap.Round),
                                            )
                                        }
                                    }
                                }.clip(if (isVideoSong) RoundedCornerShape(6.dp) else CircleShape)
                                .semantics { contentDescription = playbackAction }
                                .clickable(enabled = playbackEnabled, role = Role.Button, onClick = {
                                    haptics.toggle(!isPlaying)
                                    onTogglePlay()
                                }),
                    ) {
                        AsyncImage(
                            url = song.previewThumbnailUrl,
                            contentDescription = null,
                            modifier =
                                if (isVideoSong) {
                                    Modifier.size(38.dp).clip(RoundedCornerShape(6.dp))
                                } else {
                                    Modifier.size(38.dp).clip(CircleShape)
                                },
                            contentScale = ContentScale.Crop,
                        )

                        if (isLoading && active) {
                            val loadingText = stringResource(Res.string.loading)
                            LoadingIndicator(
                                modifier = Modifier.size(24.dp).semantics { contentDescription = loadingText },
                                color = MaterialTheme.colorScheme.primary,
                            )
                        } else if (!isPlaying) {
                            Box(
                                modifier =
                                    (
                                        if (isVideoSong) {
                                            Modifier.size(38.dp).clip(RoundedCornerShape(6.dp))
                                        } else {
                                            Modifier.size(38.dp).clip(CircleShape)
                                        }
                                    ).background(Color.Black.copy(alpha = 0.35f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = MetrolistIcons.Outlined.PlayArrow,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.width(14.dp))

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = song.title,
                            color = contentColor,
                            fontSize = 13.sp,
                            lineHeight = 16.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = marqueeModifier,
                        )

                        Text(
                            text = song.artists.joinToString { it.displayName }.takeIf { it.isNotBlank() } ?: "",
                            color = secondaryContentColor,
                            fontSize = 11.sp,
                            lineHeight = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = marqueeModifier,
                        )
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    MiniPlayerActions(
                        isLiked = isLiked,
                        isArtistSubscribed = isArtistSubscribed,
                        onAddToPlaylist = onAddToPlaylist,
                        onLike = onLike,
                        onSubscribeToggle = onSubscribeToggle.takeIf { artistId != null },
                        borderColor = buttonBorderColor,
                        contentColor = secondaryContentColor,
                    )
                }
            } else {
                Row(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AsyncImage(
                        url = song.previewThumbnailUrl,
                        contentDescription = null,
                        modifier = Modifier.size(44.dp).clip(RoundedCornerShape(4.dp)),
                        contentScale = ContentScale.Crop,
                    )

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = song.title,
                            color = contentColor,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = marqueeModifier,
                        )
                        Text(
                            text = song.artists.joinToString { it.displayName }.takeIf { it.isNotBlank() } ?: "",
                            color = secondaryContentColor,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = marqueeModifier,
                        )
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(
                            onClick = {
                                haptics.toggle(!isPlaying)
                                onTogglePlay()
                            },
                            enabled = playbackEnabled,
                            shapes = IconButtonDefaults.shapes(),
                        ) {
                            if (isLoading && active) {
                                val loadingText = stringResource(Res.string.loading)
                                LoadingIndicator(
                                    modifier = Modifier.size(24.dp).semantics { contentDescription = loadingText },
                                    color = contentColor,
                                )
                            } else {
                                Icon(
                                    imageVector = if (isPlaying) MetrolistIcons.Rounded.Pause else MetrolistIcons.Rounded.PlayArrow,
                                    contentDescription = stringResource(if (isPlaying) Res.string.pause else Res.string.play),
                                    tint = contentColor,
                                )
                            }
                        }

                        IconButton(
                            onClick = onSkipNext,
                            enabled = canSkipNext,
                            colors =
                                IconButtonDefaults.iconButtonColors(
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                ),
                            shapes = IconButtonDefaults.shapes(),
                        ) {
                            Icon(
                                imageVector = MetrolistIcons.Rounded.SkipNext,
                                contentDescription = stringResource(Res.string.next),
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                }

                val progressColor = MaterialTheme.colorScheme.primary
                Box(
                    modifier =
                        Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .height(3.dp)
                            .background(contentColor.copy(alpha = 0.1f))
                            .drawWithContent {
                                drawContent()
                                val width = size.width * progress()
                                val left = if (layoutDirection == LayoutDirection.Rtl) size.width - width else 0f
                                drawRect(progressColor, topLeft = Offset(left, 0f), size = Size(width, size.height))
                            },
                )
            }
        }
    }
}
