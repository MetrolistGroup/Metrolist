@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.desktop.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.metrolist.desktop.state.app.AppState
import com.metrolist.desktop.state.app.UiState
import com.metrolist.desktop.state.content.ArtistState
import com.metrolist.desktop.state.playback.ListenTogetherState
import com.metrolist.desktop.state.playback.LyricsState
import com.metrolist.desktop.state.playback.PlayerState
import com.metrolist.shared.api.lyrics.LyricsUtils
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.close_video_fullscreen
import com.metrolist.shared.generated.resources.copy_debug_info
import com.metrolist.shared.generated.resources.copy_url_at_current_time
import com.metrolist.shared.generated.resources.next
import com.metrolist.shared.generated.resources.pause
import com.metrolist.shared.generated.resources.play
import com.metrolist.shared.generated.resources.previous
import com.metrolist.shared.generated.resources.stats_for_nerds
import com.metrolist.shared.generated.resources.switch_to_audio
import com.metrolist.shared.generated.resources.thumbnail
import com.metrolist.shared.model.RepeatMode
import com.metrolist.shared.model.isLive
import com.metrolist.shared.model.previewThumbnailUrl
import com.metrolist.shared.playback.PendingSeekPosition
import com.metrolist.shared.settings.PlayerType
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.settings.displayName
import com.metrolist.shared.ui.components.LiveBadge
import com.metrolist.shared.ui.components.PlayerConnectedButtonGroup
import com.metrolist.shared.ui.components.PlayerLikeShareButtons
import com.metrolist.shared.ui.components.PlayerPrimaryControls
import com.metrolist.shared.ui.components.PopupVisibility
import com.metrolist.shared.ui.components.VintagePlayerControls
import com.metrolist.shared.ui.components.VintagePlayerControlsText
import com.metrolist.shared.ui.components.images.AsyncImage
import com.metrolist.shared.ui.components.lyrics.rememberCaptionLyricsPositionState
import com.metrolist.shared.ui.components.sliders.rememberPendingSeekPosition
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.menu.CursorDropdownMenu
import com.metrolist.shared.ui.menu.rememberCursorContextMenuState
import com.metrolist.shared.ui.theme.uiEnhanced
import com.metrolist.shared.utils.OsUtil
import com.metrolist.shared.utils.ShareUrls
import com.metrolist.shared.utils.formatDuration
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import java.awt.Point
import java.awt.Toolkit
import java.awt.image.BufferedImage
import kotlin.time.Duration.Companion.milliseconds

@Composable
fun VideoFullscreenOverlay(
    colorScheme: ColorScheme,
    toggleWindowFullscreen: () -> Unit,
) {
    val visible = PlayerState.isExpanded && UiState.showVideoFullscreen && PlayerState.isVideoAvailable && PlayerState.currentTrack != null
    val visibility = remember { MutableTransitionState(false) }
    visibility.targetState = visible
    SideEffect { UiState.videoFullscreenObscuresPlayer = visibility.isIdle && visibility.currentState && visible }
    DisposableEffect(Unit) { onDispose { UiState.videoFullscreenObscuresPlayer = false } }
    val showQueue = UiState.showVideoFullscreenQueue
    val showLyrics = UiState.showVideoFullscreenLyrics
    var subtitleOffset by remember { mutableStateOf(IntOffset.Zero) }
    var showDetailsDialog by remember { mutableStateOf(false) }

    AnimatedVisibility(
        visibleState = visibility,
        enter = fadeIn(tween(220)),
        exit = fadeOut(tween(220)),
    ) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize().background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            val availableWidth = maxWidth
            val widthClass = currentWindowAdaptiveInfoV2().windowSizeClass.minWidthDp
            val preferredWidth =
                if (widthClass < 600) {
                    320.dp
                } else if (widthClass < 840) {
                    360.dp
                } else {
                    420.dp
                }

            val drawerWidth = minOf(preferredWidth, (availableWidth - 260.dp).coerceAtLeast(260.dp))
            SideEffect {
                UiState.videoFullscreenPanels.canShowBoth = availableWidth - drawerWidth * 2 >= 480.dp
            }

            val animatedQueueWidth by animateDpAsState(
                targetValue = drawerWidth,
                animationSpec = spring(dampingRatio = 0.82f, stiffness = 380f),
                label = "queueWidth",
            )

            val animatedLyricsWidth by animateDpAsState(
                targetValue = drawerWidth,
                animationSpec = tween(durationMillis = 280, easing = EaseOutCubic),
                label = "lyricsWidth",
            )

            val totalActiveDrawerWidth =
                (if (showQueue) drawerWidth else 0.dp) +
                    (if (showLyrics) drawerWidth else 0.dp)
            val isOverlayMode =
                availableWidth < 760.dp || (availableWidth - totalActiveDrawerWidth) < 480.dp

            val targetVideoEndPadding = if (isOverlayMode) 0.dp else totalActiveDrawerWidth
            val animatedVideoEndPadding by animateDpAsState(
                targetValue = targetVideoEndPadding,
                animationSpec = tween(durationMillis = 280, easing = EaseOutCubic),
                label = "videoEndPadding",
            )

            val onQueueClickAction = {
                val showQueue = !UiState.showVideoFullscreenQueue
                UiState.showVideoFullscreenQueue = showQueue
            }
            val onLyricsClickAction = {
                val showLyrics = !UiState.showVideoFullscreenLyrics
                UiState.showVideoFullscreenLyrics = showLyrics
                if (showLyrics && LyricsState.currentLyricsEntries.isEmpty()) {
                    PlayerState.currentTrack?.let(LyricsState::fetchLyrics)
                }
            }

            Box(modifier = Modifier.fillMaxSize()) {
                VideoPlayerArea(
                    colorScheme = colorScheme,
                    showQueue = showQueue,
                    onQueueClick = onQueueClickAction,
                    showLyrics = showLyrics,
                    onLyricsClick = onLyricsClickAction,
                    subtitleOffset = subtitleOffset,
                    onSubtitleOffsetChange = { subtitleOffset = it },
                    toggleWindowFullscreen = toggleWindowFullscreen,
                    onShowDetailsDialog = { showDetailsDialog = true },
                    modifier = Modifier.fillMaxSize().padding(end = animatedVideoEndPadding.coerceAtLeast(0.dp)),
                )

                Row(
                    modifier = Modifier.fillMaxHeight().align(Alignment.CenterEnd),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AnimatedVisibility(
                        visible = showQueue,
                        modifier = Modifier.fillMaxHeight(),
                        enter =
                            expandHorizontally(
                                expandFrom = Alignment.End,
                                animationSpec = spring(dampingRatio = 0.82f, stiffness = 380f),
                            ) +
                                slideInHorizontally(
                                    initialOffsetX = { it },
                                    animationSpec = spring(dampingRatio = 0.82f, stiffness = 380f),
                                ) + fadeIn(),
                        exit =
                            shrinkHorizontally(
                                shrinkTowards = Alignment.End,
                                animationSpec = spring(dampingRatio = 0.88f, stiffness = 450f),
                            ) +
                                slideOutHorizontally(
                                    targetOffsetX = { it },
                                    animationSpec = spring(dampingRatio = 0.88f, stiffness = 450f),
                                ) + fadeOut(),
                    ) {
                        DesktopQueueContent(
                            onDismiss = { UiState.showVideoFullscreenQueue = false },
                            colorScheme = colorScheme,
                            modifier = Modifier.width(animatedQueueWidth.coerceAtLeast(0.dp)),
                        )
                    }

                    LyricsDrawer(
                        drawerOpen = showLyrics,
                        animatedDrawerWidth = animatedLyricsWidth.coerceAtLeast(0.dp),
                        colorScheme = colorScheme,
                        onClose = { UiState.showVideoFullscreenLyrics = false },
                    )
                }
            }
        }
    }

    PopupVisibility(visible = showDetailsDialog) {
        MediaDetailsDialog(
            song = PlayerState.currentTrack,
            onDismiss = { showDetailsDialog = false },
        )
    }
}

@Composable
private fun VideoPlayerArea(
    colorScheme: ColorScheme,
    showQueue: Boolean,
    onQueueClick: () -> Unit,
    showLyrics: Boolean,
    onLyricsClick: () -> Unit,
    subtitleOffset: IntOffset,
    onSubtitleOffsetChange: (IntOffset) -> Unit,
    toggleWindowFullscreen: () -> Unit,
    onShowDetailsDialog: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var isControlsVisible by remember { mutableStateOf(true) }
    var lastActivityTime by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var isDraggingSeekbar by remember { mutableStateOf(false) }
    val bottomControlsInteractionSource = remember { MutableInteractionSource() }
    val isBottomControlsHovered by bottomControlsInteractionSource.collectIsHoveredAsState()
    val topControlsInteractionSource = remember { MutableInteractionSource() }
    val isTopControlsHovered by topControlsInteractionSource.collectIsHoveredAsState()

    val areControlsVisible =
        isControlsVisible ||
            !PlayerState.isPlaying ||
            isDraggingSeekbar ||
            isBottomControlsHovered ||
            isTopControlsHovered

    val resetInactivity =
        remember {
            {
                isControlsVisible = true
                lastActivityTime = System.currentTimeMillis()
            }
        }

    LaunchedEffect(Unit) {
        resetInactivity()
    }

    LaunchedEffect(isBottomControlsHovered, isTopControlsHovered) {
        if (isBottomControlsHovered || isTopControlsHovered) {
            resetInactivity()
        }
    }

    LaunchedEffect(
        isControlsVisible,
        PlayerState.isPlaying,
        isDraggingSeekbar,
        isBottomControlsHovered,
        isTopControlsHovered,
    ) {
        if (!isControlsVisible ||
            !PlayerState.isPlaying ||
            isDraggingSeekbar ||
            isBottomControlsHovered ||
            isTopControlsHovered
        ) {
            return@LaunchedEffect
        }
        while (true) {
            delay(250.milliseconds)
            if (System.currentTimeMillis() - lastActivityTime >= 2500L) {
                isControlsVisible = false
                break
            }
        }
    }

    val blankCursor =
        remember {
            try {
                val img = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
                val cursor = Toolkit.getDefaultToolkit().createCustomCursor(img, Point(0, 0), "blank_cursor")
                PointerIcon(cursor)
            } catch (_: Exception) {
                PointerIcon.Default
            }
        }

    val currentPointerIcon = if (!areControlsVisible) blankCursor else PointerIcon.Default

    Box(
        modifier =
            modifier
                .pointerHoverIcon(currentPointerIcon)
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            when (event.type) {
                                PointerEventType.Move,
                                PointerEventType.Enter,
                                PointerEventType.Press,
                                PointerEventType.Release,
                                PointerEventType.Scroll,
                                -> {
                                    resetInactivity()
                                }

                                PointerEventType.Exit -> {
                                }
                            }
                        }
                    }
                },
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            VideoFullscreenDisplay(
                onTogglePlay = {
                    resetInactivity()
                    PlayerState.togglePlay()
                },
                modifier = Modifier.fillMaxSize(),
            )

            if (PlayerState.isVideoResolving) {
                LoadingIndicator(
                    modifier = Modifier.size(48.dp),
                    color = Color.White,
                )
            }

            if (PlayerState.isSubtitleActive && !showLyrics) {
                val castState by PlayerState.castManager.state.collectAsState()
                val smoothPosition =
                    rememberCaptionLyricsPositionState(
                        lyrics = LyricsState.currentLyricsEntries,
                        currentPositionFlow = PlayerState.playbackPosition,
                        positionReader = PlayerState::currentMediaPositionMs,
                        playbackSpeed =
                            PlayerState.player.playbackSpeed
                                .collectAsState()
                                .value,
                        timingOffsetMs = LyricsState.currentLyricsOffsetMs,
                        isPlaying = PlayerState.isPlaying,
                        songId = PlayerState.currentTrack?.id,
                        isCasting = castState.isConnected,
                        finalLineDurationMs = LyricsUtils.FINAL_DYNAMIC_LINE_DURATION_MS,
                    )
                val animatedBottomPadding by animateDpAsState(
                    targetValue = if (areControlsVisible) 220.dp else 120.dp,
                    animationSpec = spring(dampingRatio = 0.82f, stiffness = 380f),
                    label = "subtitleBottomPadding",
                )
                SubtitleOverlay(
                    lyricsPositionState = smoothPosition,
                    offset = subtitleOffset,
                    onOffsetChange = onSubtitleOffsetChange,
                    bottomPadding = animatedBottomPadding.coerceAtLeast(0.dp),
                )
            }

            if (UiState.showVideoStatsForNerds) {
                VideoStatsForNerdsOverlay(
                    modifier =
                        Modifier
                            .align(Alignment.TopStart)
                            .padding(start = 16.dp, top = 68.dp),
                    onClose = { UiState.showVideoStatsForNerds = false },
                )
            }
        }

        if (!OsUtil.isWindows) {
            AnimatedVisibility(
                visible = areControlsVisible,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 16.dp, end = if (PlayerState.isFullscreen) 64.dp else 16.dp),
                enter = fadeIn(tween(200)),
                exit = fadeOut(tween(200)),
            ) {
                IconButton(
                    onClick = PlayerState::toggleFullscreenVideo,
                    modifier =
                        Modifier
                            .hoverable(topControlsInteractionSource)
                            .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                            .size(40.dp),
                    shapes = IconButtonDefaults.shapes(),
                ) {
                    Icon(MetrolistIcons.Outlined.MusicNote, stringResource(Res.string.switch_to_audio), tint = Color.White)
                }
            }

            if (PlayerState.isFullscreen) {
                AnimatedVisibility(
                    visible = areControlsVisible,
                    modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
                    enter = fadeIn(tween(200)),
                    exit = fadeOut(tween(200)),
                ) {
                    IconButton(
                        onClick = {
                            toggleWindowFullscreen()
                        },
                        modifier =
                            Modifier
                                .hoverable(topControlsInteractionSource)
                                .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                                .size(40.dp),
                        shapes = IconButtonDefaults.shapes(),
                    ) {
                        Icon(
                            imageVector = MetrolistIcons.Default.Close,
                            contentDescription = stringResource(Res.string.close_video_fullscreen),
                            tint = Color.White,
                        )
                    }
                }
            } else {
                AnimatedVisibility(
                    visible = areControlsVisible,
                    modifier = Modifier.align(Alignment.TopStart).padding(16.dp),
                    enter = fadeIn(tween(200)),
                    exit = fadeOut(tween(200)),
                ) {
                    IconButton(
                        onClick = {
                            UiState.showVideoFullscreen = false
                            PlayerState.exitExpanded()
                        },
                        modifier =
                            Modifier
                                .hoverable(topControlsInteractionSource)
                                .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                                .size(40.dp),
                        shapes = IconButtonDefaults.shapes(),
                    ) {
                        Icon(
                            imageVector = MetrolistIcons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(Res.string.close_video_fullscreen),
                            tint = Color.White,
                        )
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = areControlsVisible,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(200)),
        ) {
            FullscreenVideoControls(
                colorScheme = colorScheme,
                showQueue = showQueue,
                onQueueClick = {
                    resetInactivity()
                    onQueueClick()
                },
                showLyrics = showLyrics,
                onLyricsClick = {
                    resetInactivity()
                    onLyricsClick()
                },
                onDraggingChange = { isDraggingSeekbar = it },
                onUserInteract = resetInactivity,
                modifier = Modifier.hoverable(bottomControlsInteractionSource),
                toggleWindowFullscreen = toggleWindowFullscreen,
                onShowDetailsDialog = onShowDetailsDialog,
            )
        }
    }
}

@Composable
private fun FullscreenVideoControls(
    colorScheme: ColorScheme,
    showQueue: Boolean,
    onQueueClick: () -> Unit,
    showLyrics: Boolean,
    onLyricsClick: () -> Unit,
    onDraggingChange: (Boolean) -> Unit,
    onUserInteract: () -> Unit,
    toggleWindowFullscreen: () -> Unit,
    onShowDetailsDialog: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var isDragging by remember { mutableStateOf(false) }
    var dragProgress by remember { mutableStateOf(0f) }
    val position = rememberPlaybackSecondPosition()
    val duration by PlayerState.player.duration.collectAsState(0L)
    val isLive = playerIsLiveStream()
    val isListenTogetherGuest = ListenTogetherState.isListenTogetherGuest()
    var sliderWidthPx by remember { mutableIntStateOf(0) }
    val smoothPosition = rememberPlayerProgressPosition(duration, sliderWidthPx, isLive)
    var pendingSeek by rememberPendingSeekPosition(
        songId = PlayerState.currentTrack?.id,
        durationMs = duration,
        enabled = !isListenTogetherGuest && !isLive && duration > 0L,
        position = { PlayerState.playbackPosition.value },
        renderedPosition = { smoothPosition.longValue },
    )
    // Read the initial slider value without subscribing this row to position samples.
    val sampledProgress = if (duration > 0L) PlayerState.playbackPosition.value.toFloat() / duration else 0f
    val playerMenu = rememberCursorContextMenuState()
    val swapControls = PlayerState.swapPlayerControls

    BoxWithConstraints(
        modifier =
            modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.7f),
                            Color.Black.copy(alpha = 0.9f),
                        ),
                    ),
                ).padding(top = 48.dp),
    ) {
        val controlsWidth = maxWidth
        val isWideControls = controlsWidth >= 960.dp

        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp).padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                val canSeek = !isListenTogetherGuest && !isLive

                if (isLive) {
                    LiveBadge()
                    LiveProgressBar(
                        modifier = Modifier.weight(1f),
                        color = colorScheme.primary,
                    )
                    val elapsedPosition = PlayerState.player.currentPosition.collectAsState(0L)
                    PlayerProgressTime(
                        positionProvider = { elapsedPosition.value },
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.9f),
                    )
                } else {
                    PlayerProgressTime(
                        positionProvider = {
                            if (isDragging) (dragProgress * duration).toLong() else pendingSeek?.targetMs ?: position.value
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.9f),
                    )
                    PlayerSlider(
                        value =
                            if (isDragging || pendingSeek != null) dragProgress else sampledProgress.coerceIn(0f, 1f),
                        valueProvider = {
                            when {
                                isDragging -> dragProgress
                                pendingSeek != null -> pendingSeek!!.targetMs.toFloat() / duration
                                duration > 0L -> (smoothPosition.longValue.toFloat() / duration).coerceIn(0f, 1f)
                                else -> 0f
                            }
                        },
                        onValueChange = {
                            if (canSeek) {
                                pendingSeek = null
                                isDragging = true
                                onDraggingChange(true)
                                dragProgress = it
                                onUserInteract()
                            }
                        },
                        onValueChangeFinished = {
                            if (canSeek) {
                                pendingSeek = PendingSeekPosition((dragProgress * duration).toLong(), PlayerState.playbackPosition.value)
                                isDragging = false
                                onDraggingChange(false)
                                PlayerState.seekTo(dragProgress)
                                onUserInteract()
                            }
                        },
                        style = Settings.shared.sliderStyle,
                        isPlaying = PlayerState.isPlaying,
                        color = colorScheme.primary,
                        bufferedValue = PlayerState.bufferedProgress,
                        enabled = canSeek,
                        modifier = Modifier.weight(1f).onSizeChanged { sliderWidthPx = it.width },
                    )
                    Text(
                        text = formatDuration(duration),
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.9f),
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth().height(64.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                val infoSection: @Composable (Modifier) -> Unit = { sectionModifier ->
                    Row(
                        modifier = sectionModifier,
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = if (swapControls) Arrangement.Center else Arrangement.Start,
                    ) {
                        val track = PlayerState.currentTrack
                        if (track != null) {
                            AsyncImage(
                                url = track.previewThumbnailUrl,
                                contentDescription = stringResource(Res.string.thumbnail),
                                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(uiEnhanced(4.dp, 8.dp))),
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f, fill = false).padding(end = 12.dp)) {
                                Text(
                                    track.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier =
                                        Modifier.basicMarquee(
                                            iterations = 1,
                                            initialDelayMillis = 1500,
                                            velocity = 30.dp,
                                        ),
                                )
                                Row(
                                    modifier =
                                        Modifier.basicMarquee(
                                            iterations = 1,
                                            initialDelayMillis = 1500,
                                            velocity = 30.dp,
                                        ),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    track.artists.forEachIndexed { idx, artist ->
                                        Text(
                                            artist.displayName,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = Color.White.copy(alpha = 0.7f),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier =
                                                Modifier.clickable(
                                                    enabled = artist.id != null,
                                                    indication = null,
                                                    interactionSource = remember { MutableInteractionSource() },
                                                ) {
                                                    onUserInteract()
                                                    artist.id?.let {
                                                        UiState.showVideoFullscreen = false
                                                        PlayerState.exitExpanded()
                                                        ArtistState
                                                            .fetchArtistData(it)
                                                    }
                                                },
                                        )
                                        if (idx < track.artists.lastIndex) {
                                            Text(
                                                ", ",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = Color.White.copy(alpha = 0.7f),
                                                maxLines = 1,
                                            )
                                        }
                                    }
                                }
                            }
                            if (isWideControls) {
                                Spacer(Modifier.width(16.dp))
                                val isLiked = PlayerState.isLiked(track.id)
                                Box {
                                    PlayerLikeShareButtons(
                                        isLiked = isLiked,
                                        onLikeClick = {
                                            onUserInteract()
                                            PlayerState.toggleLike(track)
                                        },
                                        onShareClick = {
                                            onUserInteract()
                                            AppState.shareLink(ShareUrls.song(track.id), track.title)
                                        },
                                        onAltShareClick = {
                                            onUserInteract()
                                            AppState.shareLink(ShareUrls.song(track.id), track.title)
                                        },
                                        onMoreClick = {
                                            onUserInteract()
                                            playerMenu.openAnchored()
                                        },
                                        isMoreActive = playerMenu.expanded,
                                        groupHeight = 36.dp,
                                        iconSize = 18.dp,
                                    )

                                    PlayerMenuPanel(
                                        expanded = playerMenu.expanded,
                                        offset = playerMenu.offset,
                                        onDismiss = playerMenu::dismiss,
                                        onShowAddToPlaylistDialog = {
                                            PlayerState.currentTrack?.let { song ->
                                                PlayerState.showAddToPlaylistSong = song
                                            }
                                            playerMenu.dismiss()
                                        },
                                        onShowDetailsDialog = {
                                            playerMenu.dismiss()
                                            onShowDetailsDialog()
                                        },
                                        onShowSleepTimerDialog = null,
                                    )
                                }
                            }
                        }
                    }
                }

                val primaryControlsSection: @Composable (Modifier) -> Unit = { sectionModifier ->
                    Box(
                        modifier = sectionModifier,
                        contentAlignment = if (swapControls) Alignment.CenterStart else Alignment.Center,
                    ) {
                        if (!isListenTogetherGuest) {
                            if (Settings.shared.playerType == PlayerType.VINTAGE ||
                                Settings.shared.playerType == PlayerType.CLASSIC
                            ) {
                                VintagePlayerControls(
                                    text =
                                        VintagePlayerControlsText(
                                            previous = stringResource(Res.string.previous),
                                            play = stringResource(Res.string.play),
                                            pause = stringResource(Res.string.pause),
                                            next = stringResource(Res.string.next),
                                        ),
                                    isPlaying = PlayerState.isPlaying,
                                    isLoading = PlayerState.isPlaybackLoading,
                                    onSkipPrevious =
                                        if (PlayerState.canSkipPrevious) {
                                            {
                                                onUserInteract()
                                                PlayerState.skipPrevious()
                                            }
                                        } else {
                                            null
                                        },
                                    onTogglePlay = {
                                        onUserInteract()
                                        PlayerState.togglePlay()
                                    },
                                    onSkipNext = {
                                        onUserInteract()
                                        PlayerState.skipNext()
                                    },
                                    buttonSize = if (isWideControls) 64.dp else 52.dp,
                                    playButtonSize = if (isWideControls) 72.dp else 60.dp,
                                    iconSize = if (isWideControls) 48.dp else 38.dp,
                                    playIconSize = if (isWideControls) 56.dp else 46.dp,
                                    spacing = if (isWideControls) 20.dp else 12.dp,
                                    modifier = Modifier.widthIn(max = if (isWideControls) 320.dp else 240.dp),
                                )
                            } else {
                                PlayerPrimaryControls(
                                    isPlaying = PlayerState.isPlaying,
                                    isLoading = PlayerState.isPlaybackLoading,
                                    playText = stringResource(Res.string.play),
                                    pauseText = stringResource(Res.string.pause),
                                    onSkipPrevious =
                                        if (PlayerState.canSkipPrevious) {
                                            {
                                                onUserInteract()
                                                PlayerState.skipPrevious()
                                            }
                                        } else {
                                            null
                                        },
                                    onTogglePlay = {
                                        onUserInteract()
                                        PlayerState.togglePlay()
                                    },
                                    onSkipNext = {
                                        onUserInteract()
                                        PlayerState.skipNext()
                                    },
                                    modifier = Modifier.widthIn(max = if (isWideControls) 280.dp else 200.dp),
                                    buttonHeight = 52.dp,
                                    showLabels = isWideControls,
                                    useRoundedRect = Settings.shared.useRoundedRectPlayButton,
                                )
                            }
                        }
                    }
                }

                if (swapControls) {
                    primaryControlsSection(Modifier.weight(1f))
                    infoSection(Modifier.weight(1.35f))
                } else {
                    infoSection(Modifier.weight(1.35f))
                    primaryControlsSection(Modifier.weight(1f))
                }

                Row(
                    modifier = Modifier.weight(1.15f),
                    horizontalArrangement = Arrangement.End,
                ) {
                    PlayerConnectedButtonGroup(
                        onQueueClick = {
                            onUserInteract()
                            onQueueClick()
                        },
                        onLyricsClick = {
                            onUserInteract()
                            onLyricsClick()
                        },
                        isLyricsActive = showLyrics,
                        isQueueActive = showQueue,
                        modifier = Modifier,
                        compact = false,
                        volume = PlayerState.volume,
                        onVolumeChange = {
                            onUserInteract()
                            PlayerState.setVolumeLevel(it)
                        },
                        onMiniplayerClick = {
                            onUserInteract()
                            PlayerState.showMiniplayer = !PlayerState.showMiniplayer
                        },
                        showMiniplayer = PlayerState.showMiniplayer,
                        onFullscreenClick = {
                            onUserInteract()
                            val leavingFullscreen = PlayerState.isFullscreen || UiState.isNativeFullscreen
                            toggleWindowFullscreen()
                            if (leavingFullscreen) PlayerState.closeFullscreenPlayer()
                        },
                        isFullscreen = PlayerState.isFullscreen,
                        onShuffleClick =
                            if (!isListenTogetherGuest) {
                                {
                                    onUserInteract()
                                    PlayerState.toggleShuffle()
                                }
                            } else {
                                null
                            },
                        isShuffled = PlayerState.shuffleEnabled,
                        onRepeatClick =
                            if (!isListenTogetherGuest) {
                                {
                                    onUserInteract()
                                    PlayerState.repeatMode = RepeatMode.entries[(PlayerState.repeatMode.ordinal + 1) % 3]
                                }
                            } else {
                                null
                            },
                        repeatMode = PlayerState.repeatMode,
                        onSleepTimerClick = null,
                        isSleepTimerActive = false,
                        sleepTimerContentDescription = null,
                        onVideoClick = {
                            onUserInteract()
                            PlayerState.toggleFullscreenVideo()
                        },
                        isVideoActive = true,
                        onSubtitleClick = {
                            onUserInteract()
                            PlayerState.isSubtitleActive = !PlayerState.isSubtitleActive
                            if (PlayerState.isSubtitleActive && LyricsState.currentLyricsEntries.isEmpty()) {
                                PlayerState.currentTrack?.let(LyricsState::fetchLyrics)
                            }
                        },
                        isSubtitleActive = PlayerState.isSubtitleActive,
                    )
                }
            }
        }
    }
}

@Composable
private fun VideoFullscreenDisplay(
    onTogglePlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var videoViewport by remember { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(videoViewport) {
        if (videoViewport.width > 0 && videoViewport.height > 0) {
            delay(320)
            PlayerState.setVideoTargetSize(videoViewport.width, videoViewport.height)
        }
    }

    var showContextMenu by remember { mutableStateOf(false) }
    var menuOffset by remember { mutableStateOf(DpOffset.Zero) }
    val density = LocalDensity.current
    var windowOrigin by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }

    Box(
        modifier =
            modifier
                .onGloballyPositioned { coordinates ->
                    windowOrigin = coordinates.localToWindow(androidx.compose.ui.geometry.Offset.Zero)
                }.pointerInput(density, windowOrigin) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            if (event.type == PointerEventType.Press) {
                                val change = event.changes.firstOrNull() ?: continue
                                if (event.buttons.isSecondaryPressed) {
                                    menuOffset =
                                        with(density) {
                                            DpOffset(
                                                (windowOrigin.x + change.position.x).toDp(),
                                                (windowOrigin.y + change.position.y).toDp(),
                                            )
                                        }
                                    showContextMenu = true
                                    continue
                                }

                                val startTime = System.currentTimeMillis()
                                val startPos = change.position
                                val slop = viewConfiguration.touchSlop
                                var isDrag = false

                                while (true) {
                                    val nextEvent = awaitPointerEvent(PointerEventPass.Main)
                                    val nextChange = nextEvent.changes.firstOrNull() ?: break
                                    if (nextEvent.type == PointerEventType.Release || !nextChange.pressed) {
                                        val elapsed = System.currentTimeMillis() - startTime
                                        if (!isDrag && elapsed <= 350L && nextEvent.type == PointerEventType.Release) {
                                            onTogglePlay()
                                        }
                                        break
                                    } else {
                                        val dist = (nextChange.position - startPos).getDistance()
                                        if (dist > slop || (System.currentTimeMillis() - startTime) > 350L) {
                                            isDrag = true
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
    ) {
        VideoRenderer(
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
            onViewportSizeChanged = { width, height -> videoViewport = IntSize(width, height) },
        )

        VideoContextMenu(
            expanded = showContextMenu,
            offset = menuOffset,
            onDismiss = { showContextMenu = false },
        )
    }
}

@Composable
fun VideoContextMenu(
    expanded: Boolean,
    offset: DpOffset,
    onDismiss: () -> Unit,
) {
    CursorDropdownMenu(
        expanded = expanded,
        offset = offset,
        onDismissRequest = onDismiss,
        modifier = Modifier.width(220.dp),
    ) {
        DropdownMenuItem(
            text = { Text(stringResource(Res.string.stats_for_nerds)) },
            leadingIcon = {
                Icon(
                    imageVector = MetrolistIcons.Default.Info,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            },
            trailingIcon = {
                if (UiState.showVideoStatsForNerds) {
                    Icon(
                        imageVector = MetrolistIcons.Default.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                }
            },
            onClick = {
                UiState.showVideoStatsForNerds = !UiState.showVideoStatsForNerds
                onDismiss()
            },
        )

        DropdownMenuItem(
            text = { Text(stringResource(Res.string.copy_debug_info)) },
            leadingIcon = {
                Icon(
                    imageVector = MetrolistIcons.Outlined.ContentCopy,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            },
            onClick = {
                PlayerState.copyVideoDebugInfo()
                onDismiss()
            },
        )

        val track = PlayerState.currentTrack
        if (track != null && !track.isLive) {
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.copy_url_at_current_time)) },
                leadingIcon = {
                    Icon(
                        imageVector = MetrolistIcons.Outlined.Share,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                },
                onClick = {
                    val sec = (PlayerState.player.currentPosition.value / 1000L).coerceAtLeast(0L)
                    AppState.copyToClipboard(ShareUrls.song(track.id, startSeconds = sec))
                    onDismiss()
                },
            )
        }
    }
}
