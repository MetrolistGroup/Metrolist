/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.shared.ui.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metrolist.shared.api.lyrics.LyricsUtils
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.album_art
import com.metrolist.shared.generated.resources.close
import com.metrolist.shared.generated.resources.enter_pip
import com.metrolist.shared.generated.resources.exit_fullscreen
import com.metrolist.shared.generated.resources.more_options
import com.metrolist.shared.generated.resources.pause
import com.metrolist.shared.generated.resources.play
import com.metrolist.shared.generated.resources.reset
import com.metrolist.shared.generated.resources.unknown
import com.metrolist.shared.model.RepeatMode
import com.metrolist.shared.playback.LocalPlayerSession
import com.metrolist.shared.playback.SleepTimerState
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.ui.LocalIsInPipMode
import com.metrolist.shared.ui.LocalIsTv
import com.metrolist.shared.ui.components.PlayerConnectedButtonGroup
import com.metrolist.shared.ui.components.PlayerLikeShareButtons
import com.metrolist.shared.ui.components.PlayerPrimaryControls
import com.metrolist.shared.ui.components.applySheetBackContentTransform
import com.metrolist.shared.ui.components.applySheetBackTransform
import com.metrolist.shared.ui.components.formatSleepTimerRemaining
import com.metrolist.shared.ui.components.images.AsyncImage
import com.metrolist.shared.ui.components.lyrics.ActiveLyricsLineContent
import com.metrolist.shared.ui.components.lyrics.rememberCaptionLyricsPositionState
import com.metrolist.shared.ui.components.rememberSheetBackProgress
import com.metrolist.shared.ui.components.sliders.collectPlaybackDisplayState
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.navigation.blockInput
import com.metrolist.shared.ui.navigation.navigationPopupSource
import com.metrolist.shared.ui.theme.uiEnhanced
import com.metrolist.shared.ui.utils.nativeBlurCompat
import com.metrolist.shared.utils.formatDuration
import com.metrolist.shared.viewmodels.PlayerViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

@Composable
fun FullscreenPlayerContent(
    viewModel: PlayerViewModel,
    currentSong: PlayerViewModel.SongUiModel?,
    isPlaying: Boolean,
    duration: Long,
    isLiked: Boolean,
    uiState: PlayerViewModel.PlayerUiState,
    textBackgroundColor: Color,
    showVideoSurface: Boolean,
    onNavigateToArtist: (String) -> Unit,
    onToggleLike: () -> Unit,
    onShareSong: () -> Unit,
    onAltShareSong: (() -> Unit)? = null,
    onToggleFullscreen: () -> Unit,
    onShowPlayerMenu: () -> Unit,
    onShowSleepTimer: () -> Unit,
    onOpenCast: () -> Unit,
    isCasting: Boolean,
    canControlPlayback: Boolean,
    backHandlerEnabled: Boolean = true,
) {
    var controlsVisible by remember { mutableStateOf(true) }
    var subtitleOffset by remember { mutableStateOf(IntOffset.Zero) }

    val isSplitScreen = uiState.isLyricsVisible || uiState.isQueueVisible
    val drawerVisibility = remember { MutableTransitionState(isSplitScreen) }
    drawerVisibility.targetState = isSplitScreen
    var queueClosing by remember { mutableStateOf(false) }
    val backProgress =
        rememberSheetBackProgress(enabled = backHandlerEnabled && uiState.isQueueVisible) {
            queueClosing = true
            try {
                drawerVisibility.targetState = false
                viewModel.setQueueVisible(false)
                snapshotFlow { drawerVisibility.isIdle }.first { it }
            } finally {
                queueClosing = false
            }
        }

    Box(
        modifier = Modifier.fillMaxSize(),
    ) {
        if (currentSong?.displayThumbnail != null) {
            AsyncImage(
                url = currentSong.displayThumbnail,
                contentDescription = null,
                modifier =
                    Modifier
                        .fillMaxSize()
                        .alpha(0.35f)
                        .nativeBlurCompat(120f, enabled = true),
                contentScale = ContentScale.Crop,
            )
        }

        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(if (isSplitScreen) 32.dp else 0.dp),
                contentAlignment = Alignment.Center,
            ) {
                val videoModifier =
                    Modifier
                        .then(
                            if (isSplitScreen) {
                                Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(
                                        16 / 9f,
                                    ).clip(RoundedCornerShape(uiEnhanced(16.dp, 24.dp)))
                            } else {
                                Modifier.fillMaxSize()
                            },
                        )

                if (showVideoSurface) {
                    LocalPlayerPlatform.current.MusicVideoSurface(
                        modifier = videoModifier,
                        cornerRadius = if (isSplitScreen) uiEnhanced(16.dp, 24.dp) else 0.dp,
                        fillContainer = false,
                        showControls = false,
                        useSurfaceView = false,
                        onFullscreenClick = null,
                        onPiPClick = null,
                    )
                } else if (currentSong?.displayThumbnail != null) {
                    AsyncImage(
                        url = currentSong.displayThumbnail,
                        contentDescription = stringResource(Res.string.album_art),
                        modifier =
                            Modifier
                                .fillMaxWidth(if (isSplitScreen) 1f else 0.8f)
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(uiEnhanced(16.dp, 28.dp)))
                                .shadow(24.dp, RoundedCornerShape(uiEnhanced(16.dp, 28.dp))),
                        contentScale = ContentScale.Crop,
                    )
                }
                val isInPipMode = LocalIsInPipMode.current

                if (!isInPipMode) {
                    FullscreenControlsOverlay(
                        currentSong = currentSong,
                        isPlaying = isPlaying,
                        duration = duration,
                        isLiked = isLiked,
                        uiState = uiState,
                        showVideoSurface = showVideoSurface,
                        onTogglePlay = viewModel::togglePlayPause,
                        onSkipNext = viewModel::next,
                        onSkipPrevious = viewModel::previous,
                        onToggleLike = onToggleLike,
                        onShareSong = onShareSong,
                        onToggleFullscreen = onToggleFullscreen,
                        onNavigateToArtist = onNavigateToArtist,
                        onToggleLyrics = { viewModel.setLyricsVisible(!uiState.isLyricsVisible) },
                        onToggleQueue = { viewModel.setQueueVisible(!uiState.isQueueVisible) },
                        onToggleShuffle = viewModel::toggleShuffle,
                        onToggleRepeat = viewModel::toggleRepeat,
                        onShowSleepTimer = onShowSleepTimer,
                        onShowPlayerMenu = onShowPlayerMenu,
                        onToggleSubtitle = { viewModel.setSubtitleActive(!uiState.isSubtitleActive) },
                        onOpenCast = onOpenCast,
                        isCasting = isCasting,
                        canControlPlayback = canControlPlayback,
                        visible = controlsVisible,
                        onVisibilityChange = { controlsVisible = it },
                        viewModel = viewModel,
                    )
                }
                if (uiState.isSubtitleActive && !uiState.isLyricsVisible) {
                    SubtitleOverlay(
                        uiState = uiState,
                        currentPositionFlow = viewModel.currentPosition,
                        isPlaying = isPlaying,
                        isCasting = isCasting,
                        offset = subtitleOffset,
                        onOffsetChange = { subtitleOffset = it },
                    )
                }
            }

            AnimatedVisibility(
                visibleState = drawerVisibility,
                enter = slideInHorizontally { it } + fadeIn(),
                exit = slideOutHorizontally { it } + fadeOut(),
                modifier = Modifier.fillMaxHeight().fillMaxWidth(0.45f),
            ) {
                Surface(
                    color = Color.Black.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(topStart = 32.dp, bottomStart = 32.dp),
                    modifier =
                        Modifier.fillMaxSize().padding(vertical = 32.dp).padding(end = 32.dp).graphicsLayer {
                            applySheetBackTransform(backProgress.value, 0f)
                        },
                ) {
                    Box(Modifier.fillMaxSize().graphicsLayer { applySheetBackContentTransform(backProgress.value) }) {
                        if (uiState.isLyricsVisible) {
                            Box(modifier = Modifier.padding(24.dp)) {
                                LyricsViewWithPosition(
                                    viewModel = viewModel,
                                    lyrics = uiState.lyrics,
                                    songwriters = uiState.lyricsSongwriters,
                                    agents = uiState.lyricsAgents,
                                    provider = uiState.lyricsProvider,
                                    timingOffsetMs = uiState.lyricsOffsetMs,
                                    onSeek = viewModel::seekToPosition,
                                    isLoading = uiState.isLyricsLoading,
                                    textColor = Color.White,
                                    isCasting = isCasting,
                                )
                            }
                        } else if (uiState.isQueueVisible || queueClosing) {
                            QueueScreen(
                                onClose = { viewModel.setQueueVisible(false) },
                                onShowSleepTimer = onShowSleepTimer,
                                onNavigateToArtist = onNavigateToArtist,
                                modifier = Modifier.fillMaxSize().blockInput(!uiState.isQueueVisible),
                                viewModel = viewModel,
                                backHandlerEnabled = backHandlerEnabled && uiState.isQueueVisible,
                                handleDismissalBack = false,
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun FullscreenControlsOverlay(
    currentSong: PlayerViewModel.SongUiModel?,
    isPlaying: Boolean,
    duration: Long,
    isLiked: Boolean,
    uiState: PlayerViewModel.PlayerUiState,
    showVideoSurface: Boolean,
    onTogglePlay: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    onToggleLike: () -> Unit,
    onShareSong: () -> Unit,
    onAltShareSong: (() -> Unit)? = null,
    onToggleFullscreen: () -> Unit,
    onNavigateToArtist: (String) -> Unit,
    onToggleLyrics: () -> Unit,
    onToggleQueue: () -> Unit,
    onToggleShuffle: () -> Unit,
    onToggleRepeat: () -> Unit,
    onShowSleepTimer: () -> Unit,
    onShowPlayerMenu: () -> Unit,
    onToggleSubtitle: () -> Unit,
    onOpenCast: () -> Unit,
    isCasting: Boolean,
    canControlPlayback: Boolean,
    visible: Boolean,
    onVisibilityChange: (Boolean) -> Unit,
    viewModel: PlayerViewModel,
) {
    val sleepTimerState by LocalPlayerSession.current?.sleepTimerState?.collectWhilePlayerActive()
        ?: remember { mutableStateOf(SleepTimerState()) }
    val isTv = LocalIsTv.current
    val videoFocusRequester = remember { FocusRequester() }
    val playPauseFocusRequester = remember { FocusRequester() }
    var remoteInteractionVersion by remember { mutableIntStateOf(0) }

    val isPlaybackLoading by viewModel.isPlaybackLoading.collectWhilePlayerActive()
    val contentActive = LocalPlayerContentActive.current
    LaunchedEffect(isPlaying, isPlaybackLoading, visible, remoteInteractionVersion, contentActive) {
        if (isPlaying && !isPlaybackLoading && visible && contentActive) {
            delay(5000.milliseconds)
            onVisibilityChange(false)
        }
    }
    LaunchedEffect(isTv, visible, canControlPlayback) {
        if (isTv) {
            withFrameNanos { }
            runCatching {
                if (visible && canControlPlayback) playPauseFocusRequester.requestFocus() else videoFocusRequester.requestFocus()
            }
        }
    }

    val currentSecond by viewModel.currentPosition.collectPlaybackDisplayState(contentActive && visible) {
        it.coerceAtLeast(0L) / 1_000L
    }

    val platform = LocalPlayerPlatform.current

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .focusRequester(videoFocusRequester)
                .onPreviewKeyEvent { event ->
                    if (!isTv || event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.DirectionUp,
                        Key.DirectionDown,
                        Key.DirectionLeft,
                        Key.DirectionRight,
                        Key.DirectionCenter,
                        Key.Enter,
                        Key.NumPadEnter,
                        -> {
                            remoteInteractionVersion++
                            if (!visible) {
                                onVisibilityChange(true)
                                true
                            } else {
                                false
                            }
                        }

                        else -> {
                            false
                        }
                    }
                }.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { onVisibilityChange(!visible) },
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.5f)),
            ) {
                val systemBarsPadding = WindowInsets.systemBars.asPaddingValues()
                val topPadding = systemBarsPadding.calculateTopPadding()
                val bottomPadding = systemBarsPadding.calculateBottomPadding()
                val leftPadding = systemBarsPadding.calculateStartPadding(LayoutDirection.Ltr)
                val rightPadding = systemBarsPadding.calculateEndPadding(LayoutDirection.Ltr)

                Row(
                    modifier =
                        Modifier
                            .align(Alignment.TopStart)
                            .padding(top = topPadding + 16.dp, start = leftPadding + 24.dp, end = rightPadding + 24.dp)
                            .fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = onToggleFullscreen,
                        modifier = Modifier.size(48.dp),
                        shapes = IconButtonDefaults.shapes(),
                    ) {
                        Icon(
                            imageVector = MetrolistIcons.Rounded.KeyboardArrowDown,
                            contentDescription = stringResource(Res.string.close),
                            tint = Color.White,
                            modifier = Modifier.size(32.dp),
                        )
                    }

                    Spacer(modifier = Modifier.width(16.dp))

                    Column {
                        Text(
                            text = currentSong?.title ?: stringResource(Res.string.unknown),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            maxLines = 1,
                        )
                        PlayerArtistLine(
                            artists = currentSong?.artists,
                            onNavigateToArtist = onNavigateToArtist,
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White.copy(alpha = 0.7f),
                            iterations = 0,
                        )
                    }
                }

                if (canControlPlayback) {
                    Box(
                        modifier = Modifier.align(Alignment.Center).widthIn(max = 300.dp).fillMaxWidth(),
                    ) {
                        PlayerPrimaryControls(
                            isPlaying = isPlaying,
                            isLoading = isPlaybackLoading && contentActive,
                            playText = stringResource(Res.string.play),
                            pauseText = stringResource(Res.string.pause),
                            onSkipPrevious = onSkipPrevious,
                            onTogglePlay = onTogglePlay,
                            onSkipNext = onSkipNext,
                            buttonHeight = 56.dp,
                            showLabels = true,
                            useRoundedRect = Settings.shared.useRoundedRectPlayButton,
                            playPauseFocusRequester = if (isTv) playPauseFocusRequester else null,
                        )
                    }
                }

                Column(
                    modifier =
                        Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = bottomPadding + 16.dp, start = leftPadding + 24.dp, end = rightPadding + 24.dp)
                            .fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        Text(
                            text = "${formatDuration(currentSecond * 1_000L)} /\u00a0${formatDuration(duration)}",
                            style = MaterialTheme.typography.labelLarge,
                            color = Color.White.copy(alpha = 0.8f),
                            modifier = Modifier.padding(bottom = 4.dp),
                        )

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(bottom = 4.dp),
                        ) {
                            if (platform.supportsPictureInPicture) {
                                IconButton(
                                    onClick = { platform.enterPictureInPicture(isPlaying = isPlaying, hasVideo = showVideoSurface) },
                                    modifier = Modifier.size(40.dp, 32.dp),
                                    shapes = IconButtonDefaults.shapes(),
                                ) {
                                    Icon(
                                        imageVector = MetrolistIcons.Filled.PictureInPicture,
                                        contentDescription = stringResource(Res.string.enter_pip),
                                        tint = Color.White,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }

                            IconButton(
                                onClick = onToggleFullscreen,
                                modifier = Modifier.size(40.dp, 32.dp),
                                shapes = IconButtonDefaults.shapes(),
                            ) {
                                Icon(
                                    imageVector = MetrolistIcons.Default.FullscreenExit,
                                    contentDescription = stringResource(Res.string.exit_fullscreen),
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }

                    PlayerProgressSlider(
                        viewModel = viewModel,
                        duration = duration,
                        isPlaying = isPlaying,
                        textBackgroundColor = Color.White,
                        canSeek = canControlPlayback,
                        isCasting = isCasting,
                        songId = currentSong?.id,
                        showTimers = false,
                        isLive = viewModel.uiState.value.isLive,
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            PlayerLikeShareButtons(
                                isLiked = isLiked,
                                onLikeClick = onToggleLike,
                                onShareClick = onShareSong,
                                onAltShareClick = onAltShareSong,
                                groupHeight = 40.dp,
                                iconSize = 20.dp,
                            )

                            PlayerConnectedButtonGroup(
                                modifier = Modifier.weight(1f, fill = false),
                                onQueueClick = onToggleQueue,
                                onLyricsClick = onToggleLyrics,
                                isLyricsActive = uiState.isLyricsVisible,
                                isQueueActive = uiState.isQueueVisible,
                                isShuffled = uiState.isShuffleOn,
                                onShuffleClick = onToggleShuffle.takeIf { canControlPlayback },
                                onRepeatClick = onToggleRepeat.takeIf { canControlPlayback },
                                repeatMode =
                                    when (uiState.repeatMode) {
                                        1 -> RepeatMode.ONE
                                        2 -> RepeatMode.ALL
                                        else -> RepeatMode.OFF
                                    },
                                onSleepTimerClick = onShowSleepTimer.takeIf { canControlPlayback },
                                isSleepTimerActive = sleepTimerState.isActive,
                                sleepTimerDisplay =
                                    sleepTimerState.remainingMs
                                        ?.takeIf { sleepTimerState.isActive }
                                        ?.let(::formatSleepTimerRemaining),
                                sleepTimerContentDescription = sleepTimerContentDescription(sleepTimerState),
                                onSubtitleClick = onToggleSubtitle,
                                isSubtitleActive = uiState.isSubtitleActive,
                                onCastClick = onOpenCast.takeIf { canControlPlayback },
                                isCasting = isCasting,
                                touchTarget = 40.dp,
                            )
                        }

                        IconButton(
                            onClick = onShowPlayerMenu,
                            modifier = Modifier.size(40.dp).navigationPopupSource(),
                            shapes = IconButtonDefaults.shapes(),
                        ) {
                            Icon(
                                MetrolistIcons.Default.MoreVert,
                                contentDescription = stringResource(Res.string.more_options),
                                tint = Color.White,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SubtitleOverlay(
    uiState: PlayerViewModel.PlayerUiState,
    currentPositionFlow: StateFlow<Long>,
    isPlaying: Boolean,
    isCasting: Boolean,
    offset: IntOffset,
    onOffsetChange: (IntOffset) -> Unit,
) {
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    var subtitleSize by remember { mutableStateOf(IntSize.Zero) }
    val boundedOffset = clampSubtitleOffset(offset, viewportSize, subtitleSize)
    val latestOffset by rememberUpdatedState(boundedOffset)
    val resetLabel = stringResource(Res.string.reset)
    val latestOnOffsetChange by rememberUpdatedState(onOffsetChange)
    val lyrics = uiState.lyrics
    if (!LyricsUtils.hasPlaybackTiming(lyrics)) return
    val lyricsOffsetMs = uiState.lyricsOffsetMs
    val playerSession = LocalPlayerSession.current
    val lyricsPositionState =
        rememberCaptionLyricsPositionState(
            lyrics = lyrics,
            currentPositionFlow = currentPositionFlow,
            positionReader = playerSession?.let { it::currentPositionMs },
            playbackSpeed = playerSession?.playbackSpeedFlow?.collectAsState()?.value ?: Settings.shared.playbackSpeed,
            isPlaying = isPlaying,
            enabled = LocalPlayerPositionActive.current,
            songId = uiState.currentSong?.id,
            timingOffsetMs = lyricsOffsetMs,
            isCasting = isCasting,
            finalLineDurationMs = LyricsUtils.FINAL_DYNAMIC_LINE_DURATION_MS,
        )

    val currentLineIndex by remember(lyrics, lyricsPositionState) {
        derivedStateOf(structuralEqualityPolicy()) { LyricsUtils.findCurrentLineIndex(lyrics, lyricsPositionState.value) }
    }
    val currentLine = lyrics.getOrNull(currentLineIndex)
    val nextLine = lyrics.getOrNull(currentLineIndex + 1)

    val lineEndTime =
        if (currentLine != null) {
            LyricsUtils.getLineEndTime(currentLine, nextLine, LyricsUtils.FINAL_DYNAMIC_LINE_DURATION_MS)
        } else {
            0L
        }

    val isVisible by remember(lyrics, currentLineIndex, currentLine, nextLine, lineEndTime, lyricsPositionState) {
        derivedStateOf(structuralEqualityPolicy()) {
            val lyricsPosition = lyricsPositionState.value
            val inSignificantGap =
                currentLine != null &&
                    nextLine != null &&
                    lyricsPosition > lineEndTime &&
                    (nextLine.time - lineEndTime) > 5000L
            val afterLastLine = currentLine != null && nextLine == null && lyricsPosition > lineEndTime + 2000L
            val isBeforeFirstLine =
                currentLineIndex == -1 &&
                    (lyrics.firstOrNull()?.time ?: 0L) > 5000L &&
                    lyricsPosition < (lyrics.firstOrNull()?.time ?: 0L)
            currentLine != null && !inSignificantGap && !afterLastLine && !isBeforeFirstLine
        }
    }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(bottom = 64.dp, start = 32.dp, end = 32.dp)
                .onSizeChanged { viewportSize = it },
        contentAlignment = Alignment.BottomCenter,
    ) {
        AnimatedVisibility(
            visible = isVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier =
                Modifier
                    .offset { boundedOffset }
                    .onSizeChanged { subtitleSize = it }
                    .semantics {
                        customActions =
                            listOf(
                                CustomAccessibilityAction(resetLabel) {
                                    latestOnOffsetChange(IntOffset.Zero)
                                    true
                                },
                            )
                    }.pointerInput(Unit) {
                        detectTapGestures(onDoubleTap = { latestOnOffsetChange(IntOffset.Zero) })
                    }.pointerInput(viewportSize, subtitleSize) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            latestOnOffsetChange(
                                clampSubtitleOffset(
                                    IntOffset(
                                        (latestOffset.x + dragAmount.x).roundToInt(),
                                        (latestOffset.y + dragAmount.y).roundToInt(),
                                    ),
                                    viewportSize,
                                    subtitleSize,
                                ),
                            )
                        }
                    },
        ) {
            Surface(
                color = lerp(MaterialTheme.colorScheme.secondaryContainer, Color.Black, 0.25f).copy(alpha = 0.88f),
                shape = MaterialTheme.shapes.large,
                modifier =
                    Modifier
                        .wrapContentSize(Alignment.Center)
                        .animateContentSize(animationSpec = tween(200))
                        .widthIn(min = 120.dp, max = 600.dp),
            ) {
                Box(
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    AnimatedContent(
                        targetState = currentLine to lineEndTime,
                        transitionSpec = {
                            (fadeIn(animationSpec = tween(200)) + slideInVertically(initialOffsetY = { it / 4 }))
                                .togetherWith(fadeOut(animationSpec = tween(200)))
                        },
                        label = "subtitleContentSwitch",
                    ) { (line, displayedLineEndTime) ->
                        if (line != null) {
                            val isActive by remember(line, displayedLineEndTime, lyricsPositionState) {
                                derivedStateOf(structuralEqualityPolicy()) { lyricsPositionState.value in line.time..displayedLineEndTime }
                            }
                            val style =
                                MaterialTheme.typography.titleMedium.copy(
                                    fontSize = Settings.shared.subtitleTextSize.sp,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = LyricsUtils.textAlignment(Settings.shared.activeLyricsTextPosition),
                                )
                            ActiveLyricsLineContent(
                                entry = line,
                                isActive = isActive,
                                positionProvider = { lyricsPositionState.value },
                                lineEndTimeMs = displayedLineEndTime,
                                isPlaying = isPlaying,
                                textColor = Color.White,
                                lyricStyle = style,
                                textMeasurer = rememberTextMeasurer(),
                                alignment = LyricsUtils.textAlignment(Settings.shared.activeLyricsTextPosition),
                                fillWidth = false,
                            )
                        }
                    }
                }
            }
        }
    }
}
