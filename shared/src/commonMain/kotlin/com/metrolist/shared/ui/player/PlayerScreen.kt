/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.shared.ui.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.mandatorySystemGestures
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidthIn
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.metrolist.shared.api.artwork.CanvasContentSource
import com.metrolist.shared.cast.CastState
import com.metrolist.shared.constants.QueuePeekHeight
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.action_like
import com.metrolist.shared.generated.resources.album_art
import com.metrolist.shared.generated.resources.cast
import com.metrolist.shared.generated.resources.collapse
import com.metrolist.shared.generated.resources.expand
import com.metrolist.shared.generated.resources.lyrics
import com.metrolist.shared.generated.resources.menu_seek_backward
import com.metrolist.shared.generated.resources.menu_seek_forward
import com.metrolist.shared.generated.resources.more_lyrics_options
import com.metrolist.shared.generated.resources.more_options
import com.metrolist.shared.generated.resources.next
import com.metrolist.shared.generated.resources.now_playing
import com.metrolist.shared.generated.resources.pause
import com.metrolist.shared.generated.resources.play
import com.metrolist.shared.generated.resources.playback_reconnecting
import com.metrolist.shared.generated.resources.previous
import com.metrolist.shared.generated.resources.queue
import com.metrolist.shared.generated.resources.repeat
import com.metrolist.shared.generated.resources.seek_feedback_description
import com.metrolist.shared.generated.resources.seek_feedback_seconds
import com.metrolist.shared.generated.resources.share
import com.metrolist.shared.generated.resources.sleep_timer
import com.metrolist.shared.generated.resources.small_album_art
import com.metrolist.shared.generated.resources.song
import com.metrolist.shared.generated.resources.unknown
import com.metrolist.shared.generated.resources.unlike
import com.metrolist.shared.generated.resources.video
import com.metrolist.shared.playback.LocalPlayerSession
import com.metrolist.shared.playback.SleepTimerState
import com.metrolist.shared.settings.PlayerBackgroundStyle
import com.metrolist.shared.settings.PlayerThumbnailStyle
import com.metrolist.shared.settings.PlayerType
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.ui.LocalIsInPipMode
import com.metrolist.shared.ui.LocalIsTv
import com.metrolist.shared.ui.LocalPlayerImmersiveMode
import com.metrolist.shared.ui.components.BlurredArtworkBackground
import com.metrolist.shared.ui.components.BottomSheet
import com.metrolist.shared.ui.components.BottomSheetState
import com.metrolist.shared.ui.components.COLLAPSED_ANCHOR
import com.metrolist.shared.ui.components.CrossfadeArtwork
import com.metrolist.shared.ui.components.EXPANDED_ANCHOR
import com.metrolist.shared.ui.components.LiquidBackground
import com.metrolist.shared.ui.components.LoadingScreen
import com.metrolist.shared.ui.components.LocalPlayerArtworkPrefetchUrls
import com.metrolist.shared.ui.components.PlayerPrimaryControls
import com.metrolist.shared.ui.components.PopupVisibility
import com.metrolist.shared.ui.components.SleepTimerDialog
import com.metrolist.shared.ui.components.VintageAudioVideoModeSwitcher
import com.metrolist.shared.ui.components.VintageClassicActionButtons
import com.metrolist.shared.ui.components.VintageClassicBottomBar
import com.metrolist.shared.ui.components.VintageClassicControls
import com.metrolist.shared.ui.components.VintageClassicControlsText
import com.metrolist.shared.ui.components.VintagePlayerControls
import com.metrolist.shared.ui.components.adjacentArtworkUrls
import com.metrolist.shared.ui.components.focusOutline
import com.metrolist.shared.ui.components.formatSleepTimerRemaining
import com.metrolist.shared.ui.components.images.AsyncImage
import com.metrolist.shared.ui.components.lyrics.LyricsContrastScrim
import com.metrolist.shared.ui.components.lyrics.LyricsOffsetEditor
import com.metrolist.shared.ui.components.playerControlBlend
import com.metrolist.shared.ui.components.rememberBottomSheetState
import com.metrolist.shared.ui.components.rememberTrackMarquee
import com.metrolist.shared.ui.components.sheetBackContent
import com.metrolist.shared.ui.components.vintageOnButtonColor
import com.metrolist.shared.ui.haptics.HapticEvent
import com.metrolist.shared.ui.haptics.rememberMetrolistHaptics
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.navigation.NavigationBackHandler
import com.metrolist.shared.ui.navigation.blockInput
import com.metrolist.shared.ui.theme.ComponentShapes
import com.metrolist.shared.ui.utils.supportsNativeBlur
import com.metrolist.shared.utils.Logger
import com.metrolist.shared.viewmodels.PlayerViewModel
import com.metrolist.shared.youtube.playback.PlaybackEventKind
import com.metrolist.shared.youtube.playback.PlaybackEventLog
import com.metrolist.shared.youtube.playback.PlaybackNetworkStateStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

@Composable
internal fun Modifier.artworkSwipe(
    enabled: Boolean,
    sensitivity: Float,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
): Modifier {
    if (!enabled) return this
    val currentOnPrevious by rememberUpdatedState(onPrevious)
    val currentOnNext by rememberUpdatedState(onNext)
    var totalDrag by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    val offset =
        animateFloatAsState(
            targetValue = totalDrag,
            animationSpec =
                if (dragging) {
                    snap()
                } else {
                    spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMedium,
                    )
                },
            label = "artworkSwipe",
        )
    return graphicsLayer { translationX = offset.value }
        .pointerInput(sensitivity) {
            detectHorizontalDragGestures(
                onDragStart = { dragging = true },
                onHorizontalDrag = { change, dragAmount ->
                    change.consume()
                    totalDrag += dragAmount
                },
                onDragEnd = {
                    val threshold = artworkSwipeThresholdPx(size.width.toFloat(), sensitivity)
                    when {
                        totalDrag >= threshold -> currentOnPrevious()
                        totalDrag <= -threshold -> currentOnNext()
                    }
                    dragging = false
                    totalDrag = 0f
                },
                onDragCancel = {
                    dragging = false
                    totalDrag = 0f
                },
            )
        }
}

@Composable
internal fun Modifier.artworkSeekGestures(
    seekEnabled: Boolean,
    stepMs: Long,
    onTap: (() -> Unit)?,
    tapLabel: String?,
    progressive: Boolean = false,
    onSeek: (Long) -> Unit,
): Modifier {
    if (!seekEnabled && onTap != null) {
        return focusOutline(RoundedCornerShape(PlayerArtworkCornerRadius)).clickable(
            interactionSource = null,
            indication = null,
            onClickLabel = tapLabel,
            onClick = onTap,
        )
    }
    if (!seekEnabled) return this
    val currentOnTap by rememberUpdatedState(onTap)
    val currentOnSeek by rememberUpdatedState(onSeek)
    val currentProgressive by rememberUpdatedState(progressive)
    var skipMultiplier by remember { mutableStateOf(1) }
    var lastTapMark by remember { mutableStateOf<TimeMark?>(null) }
    return this
        .focusOutline(RoundedCornerShape(PlayerArtworkCornerRadius))
        .semantics {
            if (onTap != null) {
                role = Role.Button
                onClick(label = tapLabel) {
                    onTap()
                    true
                }
            }
        }.onKeyEvent { event ->
            if (onTap != null && (event.key == Key.Enter || event.key == Key.DirectionCenter || event.key == Key.Spacebar)) {
                if (event.type == KeyEventType.KeyUp) onTap()
                true
            } else {
                false
            }
        }.focusable(enabled = onTap != null)
        .pointerInput(stepMs) {
            detectTapGestures(
                onTap = { currentOnTap?.invoke() },
                onDoubleTap = { offset ->
                    val elapsedMs = lastTapMark?.elapsedNow()?.inWholeMilliseconds ?: Long.MAX_VALUE
                    skipMultiplier =
                        if (currentProgressive) nextSeekMultiplier(skipMultiplier, elapsedMs) else 1
                    lastTapMark = TimeSource.Monotonic.markNow()
                    val amount = stepMs * skipMultiplier
                    currentOnSeek(if (offset.x < size.width / 2f) -amount else amount)
                },
            )
        }
}

internal fun nextSeekMultiplier(
    previousMultiplier: Int,
    elapsedMs: Long,
    thresholdMs: Long = 1_000L,
): Int = if (elapsedMs in 0 until thresholdMs) previousMultiplier + 1 else 1

internal data class SeekFeedback(
    val deltaMs: Long,
    val nonce: Int,
)

@Composable
internal fun SeekFeedbackOverlay(
    feedback: SeekFeedback?,
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    val spatialSpec =
        MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    val effectsSpec =
        MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val deltaMs = feedback?.deltaMs ?: 0L
    val forward = deltaMs >= 0L
    val label = stringResource(Res.string.seek_feedback_seconds, (abs(deltaMs) / 1000L).toInt())
    val directionLabel = stringResource(if (forward) Res.string.menu_seek_forward else Res.string.menu_seek_backward)
    val seekDescription = stringResource(Res.string.seek_feedback_description, directionLabel, label)
    val density = LocalDensity.current
    val nudgeDistance = with(density) { 12.dp.toPx() }
    val supportsBlur = supportsNativeBlur()
    val labelShadow =
        remember(density) {
            Shadow(
                color = Color.Black.copy(alpha = 0.55f),
                offset = Offset(0f, with(density) { 1.dp.toPx() }),
                blurRadius = with(density) { 6.dp.toPx() },
            )
        }

    val arrowNudge = remember { Animatable(0f) }
    LaunchedEffect(feedback?.nonce) {
        if (feedback == null) return@LaunchedEffect
        arrowNudge.snapTo(if (forward) nudgeDistance else -nudgeDistance)
        arrowNudge.animateTo(0f, animationSpec = spatialSpec)
    }

    AnimatedVisibility(
        visible = visible,
        enter = scaleIn(initialScale = 0.7f, animationSpec = spatialSpec) + fadeIn(animationSpec = effectsSpec),
        exit = scaleOut(targetScale = 0.9f, animationSpec = spatialSpec) + fadeOut(animationSpec = effectsSpec),
        modifier = modifier,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier =
                Modifier
                    .size(96.dp)
                    .semantics(mergeDescendants = true) {
                        contentDescription = seekDescription
                        liveRegion = LiveRegionMode.Polite
                    },
        ) {
            Box(modifier = Modifier.size(32.dp)) {
                if (supportsBlur) {
                    Icon(
                        imageVector = MetrolistIcons.Outlined.FastForward,
                        contentDescription = null,
                        tint = Color.Black.copy(alpha = 0.45f),
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .offset(y = 1.dp)
                                .blur(6.dp, BlurredEdgeTreatment.Unbounded)
                                .graphicsLayer {
                                    if (!forward) scaleX = -1f
                                    translationX = arrowNudge.value
                                },
                    )
                }
                Icon(
                    imageVector = MetrolistIcons.Outlined.FastForward,
                    contentDescription = null,
                    tint = Color.White,
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                if (!forward) scaleX = -1f
                                translationX = arrowNudge.value
                            },
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = label,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, shadow = labelShadow),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalSharedTransitionApi::class)
@Composable
fun PlayerScreen(
    viewModel: PlayerViewModel,
    bottomSheetState: BottomSheetState,
    onClose: () -> Unit = {},
    onNavigateToAlbum: (String) -> Unit = {},
    onNavigateToArtist: (String) -> Unit = {},
    onNavigateToListenTogether: () -> Unit = {},
    onNavigateToIntegrations: () -> Unit = {},
    modifier: Modifier = Modifier,
    requestPlayPauseFocus: Boolean = false,
    onPlayPauseFocusRequested: () -> Unit = {},
    onLyricsVisibleChange: (Boolean) -> Unit = {},
    isScreenVisible: Boolean = true,
) {
    val isPlayerContentVisible by remember(bottomSheetState) {
        derivedStateOf { bottomSheetState.value > bottomSheetState.collapsedBound && !bottomSheetState.isCollapsed }
    }
    val playerContentState =
        resolvePlayerContentState(
            isContentVisible = isPlayerContentVisible,
            isSettledExpanded = bottomSheetState.isSettledExpanded,
            isScreenVisible = isScreenVisible,
            isInPipMode = LocalIsInPipMode.current,
        )
    val uiState by viewModel.uiState.collectWhilePlayerActive(isPlayerContentVisible && isScreenVisible)
    val windowSize = LocalWindowInfo.current.containerSize
    val isLandscape = windowSize.width > windowSize.height
    val isImmersive = Settings.shared.immersivePlayer
    val allowArtworkExpansion = !isImmersive || !isLandscape || uiState.isVideoModeActive
    val isFullscreen = uiState.isFullscreen && allowArtworkExpansion
    val playerThumbnailStyle = Settings.shared.playerThumbnailStyle
    val artworkRequestSize = playerArtworkRequestSize(windowSize, playerThumbnailStyle, isFullscreen)
    val onToggleFullscreen = viewModel::toggleFullscreen.takeIf { allowArtworkExpansion }
    LaunchedEffect(allowArtworkExpansion, uiState.isFullscreen) {
        if (!allowArtworkExpansion && uiState.isFullscreen) viewModel.setFullscreen(false)
    }
    val currentSongs = remember(viewModel) { viewModel.uiState.map { it.currentSong }.distinctUntilChanged() }
    val artworkPrefetchUrls by remember(viewModel) {
        viewModel.uiState.map { it.adjacentArtworkUrls() }.distinctUntilChanged()
    }.collectAsState(viewModel.uiState.value.adjacentArtworkUrls())
    val trackPresentation = rememberPlayerTrackPresentation(currentSongs, artworkRequestSize, artworkPrefetchUrls, isScreenVisible)
    val currentSong = trackPresentation.song
    val repeatMode = uiState.repeatMode
    val isShuffleOn = uiState.isShuffleOn
    val duration = if (currentSong?.id == uiState.currentSong?.id) uiState.duration else currentSong?.duration ?: 0L
    val queueItems = uiState.queueItems
    val currentQueueIndex = uiState.currentQueueIndex
    val isLiked = currentSong?.isLiked == true
    // While the previous track stays on screen during an artwork load, song actions would target the next track.
    val displaysPlayingSong = currentSong?.id == uiState.currentSong?.id
    val toggleDisplayedLike = { if (displaysPlayingSong) viewModel.toggleLike() }
    val shareDisplayedSong = { if (displaysPlayingSong) viewModel.shareSong() }
    val copyDisplayedSongLink = { if (displaysPlayingSong) viewModel.copySongLink() }
    SideEffect { onLyricsVisibleChange(uiState.isLyricsVisible) }

    val platform = LocalPlayerPlatform.current
    val playerConnection = LocalPlayerSession.current
    val isReconnecting by playerConnection?.isReconnecting?.collectWhilePlayerActive(isPlayerContentVisible && isScreenVisible)
        ?: remember { mutableStateOf(false) }
    val isPlaybackLoading by playerConnection?.isPlaybackLoading?.collectWhilePlayerActive(isPlayerContentVisible && isScreenVisible)
        ?: remember { mutableStateOf(false) }
    val wantsPlayback by viewModel.wantsPlaybackState.collectWhilePlayerActive(isPlayerContentVisible && isScreenVisible)
    val volume by playerConnection?.volume?.collectWhilePlayerActive(isPlayerContentVisible && isScreenVisible)
        ?: remember { mutableFloatStateOf(1f) }
    val sleepTimerState by playerConnection?.sleepTimerState?.collectWhilePlayerActive(isPlayerContentVisible && isScreenVisible)
        ?: remember { mutableStateOf(SleepTimerState()) }
    val isPlayerConnected by playerConnection?.isConnected?.collectWhilePlayerActive(isPlayerContentVisible && isScreenVisible)
        ?: remember { mutableStateOf(false) }
    val playbackResource by playerConnection?.currentPlaybackResource?.collectWhilePlayerActive(isPlayerContentVisible && isScreenVisible)
        ?: remember { mutableStateOf(null) }

    LaunchedEffect(isPlayerContentVisible, isScreenVisible, playbackResource?.videoId, currentSong?.id) {
        val mediaId = playerConnection?.currentPlaybackResource?.value?.videoId
        val metadataId = currentSong?.id
        if (isPlayerContentVisible && isScreenVisible && mediaId != null && metadataId != null && mediaId != metadataId) {
            PlaybackEventLog.info(
                PlaybackEventKind.PLAYER,
                "Full player media and displayed metadata IDs differ.",
                videoId = mediaId,
                details =
                    mapOf(
                        "mediaId" to mediaId,
                        "metadataId" to metadataId,
                        "viewModelMetadataId" to
                            viewModel.uiState.value.currentSong
                                ?.id
                                .orEmpty(),
                        "isPlaybackLoading" to isPlaybackLoading.toString(),
                    ),
            )
        }
    }

    val isVideoViewerVisible by playerConnection?.isVideoViewerVisible?.collectWhilePlayerActive(isPlayerContentVisible && isScreenVisible)
        ?: remember { mutableStateOf(true) }
    val isListenTogetherGuest = platform.isListenTogetherGuest()
    val castState by playerConnection?.castState?.collectWhilePlayerActive(isPlayerContentVisible && isScreenVisible) ?: remember {
        mutableStateOf(
            CastState(),
        )
    }
    val isPlaying =
        uiState.isPlaying ||
            (wantsPlayback && !castState.isConnected && (isPlaybackLoading || playbackResource == null))
    var showDeviceConnectSheet by remember { mutableStateOf(false) }
    val openCastDialog = platform.rememberCastDialogLauncher { showDeviceConnectSheet = true }
    val isVideoModeActive by playerConnection?.isVideoModeActive?.collectWhilePlayerActive(isPlayerContentVisible && isScreenVisible)
        ?: remember { mutableStateOf(false) }
    val isVideoModeResolving by playerConnection?.isVideoModeResolving?.collectWhilePlayerActive(isPlayerContentVisible && isScreenVisible)
        ?: remember { mutableStateOf(false) }
    val showVideoButton =
        Settings.shared.showPlayerVideoButton && platform.supportsVideoPlayback &&
            shouldShowVideoButton(
                isListenTogetherGuest = isListenTogetherGuest,
                hideVideoSongs = Settings.shared.hideVideoSongs,
                videoPlaybackEnabled = Settings.shared.musicVideoPlaybackEnabled,
                songId = currentSong?.id,
                musicVideoType = currentSong?.musicVideoType,
            )

    var showPlayerMenu by remember { mutableStateOf(false) }
    var showSongDetails by remember { mutableStateOf(false) }
    var showSleepTimerDialog by remember { mutableStateOf(false) }
    var showLyricsMenu by remember { mutableStateOf(false) }
    var showLyricsOffsetEditor by rememberSaveable(currentSong?.id) { mutableStateOf(false) }
    val hapticManager = rememberMetrolistHaptics()
    var seekFeedback by remember { mutableStateOf<SeekFeedback?>(null) }
    var seekFeedbackVisible by remember { mutableStateOf(false) }
    val artworkSeekStepMs = Settings.shared.seekDurationSeconds * 1000L
    val onArtworkSeek: (Long) -> Unit = { deltaMs ->
        viewModel.seekBy(deltaMs)
        hapticManager.perform(HapticEvent.PRESS)
        seekFeedback = SeekFeedback(deltaMs = deltaMs, nonce = (seekFeedback?.nonce ?: 0) + 1)
    }
    LaunchedEffect(seekFeedback) {
        if (seekFeedback != null) {
            seekFeedbackVisible = true
            delay(650)
            seekFeedbackVisible = false
        }
    }
    val isPlayerBackHandlerEnabled by remember(bottomSheetState) {
        derivedStateOf { bottomSheetState.progress > 0.2f && !bottomSheetState.isDismissed }
    }

    NavigationBackHandler(enabled = isPlayerBackHandlerEnabled) {
        when {
            showLyricsOffsetEditor -> showLyricsOffsetEditor = false
            uiState.isQueueVisible -> viewModel.setQueueVisible(false)
            isFullscreen && !uiState.isLyricsVisible -> viewModel.toggleFullscreen()
            else -> onClose()
        }
    }

    val isTv = LocalIsTv.current
    val isInPipMode = LocalIsInPipMode.current

    LaunchedEffect(bottomSheetState.isCollapsed, bottomSheetState.isDismissed, isInPipMode) {
        if ((bottomSheetState.isCollapsed || bottomSheetState.isDismissed || isInPipMode) && uiState.isFullscreen) {
            viewModel.setFullscreen(false)
        }
    }

    val windowWidth = with(LocalDensity.current) { windowSize.width.toDp() }
    val playerBackgroundBlurRadiusPx = with(LocalDensity.current) { 150.dp.toPx() }

    val artworkSwipeEnabled =
        shouldEnableArtworkSwipe(
            enabled = Settings.shared.swipeThumbnail,
            isTv = isTv,
            touchExplorationEnabled = platform.isTouchExplorationEnabled(),
        )
    val shouldHideSystemBars =
        !isInPipMode &&
            (
                (isFullscreen && isPlayerContentVisible) ||
                    (bottomSheetState.isExpanded && (isImmersive || uiState.isLyricsExpanded))
            )
    platform.PlayerWindowEffects(
        hideSystemBars = shouldHideSystemBars,
        lockLandscape =
            !isTv && isFullscreen && !bottomSheetState.isCollapsed && !bottomSheetState.isDismissed && !isInPipMode,
    )

    val playerType = Settings.shared.playerType
    val usesFullscreenThumbnail = playerThumbnailStyle == PlayerThumbnailStyle.FULLSCREEN && allowArtworkExpansion
    val prefersTallCover = usesFullscreenThumbnail && !isLandscape
    val playbackNetwork by PlaybackNetworkStateStore.state.collectAsState()
    val artworkAllowedOnNetwork = Settings.shared.isAnimatedArtworkAllowedOnNetwork(playbackNetwork)
    val canvasLayers = remember(artworkAllowedOnNetwork) { CanvasLayers() }
    LaunchedEffect(
        playerThumbnailStyle,
        Settings.shared.enableAnimatedCovers,
        Settings.shared.isCanvasEnabled,
        Settings.shared.canvasEnabledSources,
        Settings.shared.canvasContentOrder,
        Settings.shared.youtubeCanvasMode,
        Settings.shared.youtubeCanvasQuality,
        Settings.shared.animatedCoverResolution,
    ) { canvasLayers.failed = emptySet() }
    LaunchedEffect(currentSong?.musicVideoCanvas) { canvasLayers.failed = emptySet() }
    val currentCanvasLayer =
        remember(
            currentSong,
            platform,
            canvasLayers.failed,
            artworkAllowedOnNetwork,
            Settings.shared.isCanvasEnabled,
            Settings.shared.enableAnimatedCovers,
            Settings.shared.canvasContentOrder,
            Settings.shared.canvasEnabledSources,
            Settings.shared.musicVideoPlaybackEnabled,
            Settings.shared.animatedCoverResolution,
            prefersTallCover,
        ) { if (artworkAllowedOnNetwork) canvasLayers.select(currentSong, platform, preferTall = prefersTallCover) else null }
    val canRenderCanvasThumbnail =
        Settings.shared.isCanvasEnabled && currentCanvasLayer != null && currentCanvasLayer.source != CanvasContentSource.APPLE_MUSIC
    val isArtworkCover = currentCanvasLayer?.source == CanvasContentSource.APPLE_MUSIC
    val renderAnimatedCover = artworkAllowedOnNetwork && canvasLayers.shouldRender(currentCanvasLayer, playerContentState)
    val artworkOverlayContent by rememberUpdatedState<@Composable (Modifier) -> Unit> { artworkModifier ->
        if (renderAnimatedCover && isArtworkCover && (!isTv || !uiState.isLyricsVisible)) {
            canvasLayers.Render(
                current = currentCanvasLayer,
                playing = isPlaying && !uiState.isLyricsVisible,
                onError = { layer -> viewModel.retryMusicVideoCanvas(layer.songId, layer.url) },
                modifier = artworkModifier,
            )
        }
    }
    // Move the same video renderer with the shared artwork so lyrics toggles keep its frame.
    val artworkOverlay =
        remember {
            movableContentOf<Modifier> { artworkModifier -> artworkOverlayContent(artworkModifier) }
        }
    val animatedCoverReady = renderAnimatedCover && (currentCanvasLayer?.key in canvasLayers.ready || canvasLayers.isVisible)
    val usesCanvasThumbnail = canRenderCanvasThumbnail && (animatedCoverReady || canvasLayers.hasPresented(currentCanvasLayer))
    LaunchedEffect(currentSong?.id, uiState.nextCanvasSongId, uiState.previousCanvasSongId) {
        canvasLayers.failed =
            canvasLayers.failed
                .filter {
                    it.first == currentSong?.id || it.first == uiState.nextCanvasSongId ||
                        it.first == uiState.previousCanvasSongId || it.first == CanvasContentSource.APPLE_MUSIC.name
                }.toSet()
    }
    LaunchedEffect(currentSong?.id) {
        canvasLayers.failed = canvasLayers.failed.filterNot { it.first == currentSong?.id }.toSet()
    }
    LaunchedEffect(currentSong?.appleMusicAnimatedCoverUrl) {
        canvasLayers.failed = canvasLayers.failed.filterNot { it.first == CanvasContentSource.APPLE_MUSIC.name }.toSet()
    }
    val playerBackground = Settings.shared.playerBackgroundStyle
    val useRoundedRectPlayButton = Settings.shared.useRoundedRectPlayButton
    val playPauseFocusRequester = remember { FocusRequester() }
    val showControlsInFullscreen = !Settings.shared.lyricsHideStatusBarInFullscreen
    val controlsVisible = !uiState.isLyricsExpanded || showControlsInFullscreen
    var wasLyricsOffsetEditorOpen by remember { mutableStateOf(false) }
    LaunchedEffect(showLyricsOffsetEditor) {
        if (wasLyricsOffsetEditorOpen && !showLyricsOffsetEditor && isTv && bottomSheetState.isExpanded && controlsVisible) {
            withFrameNanos { }
            playPauseFocusRequester.requestFocus()
        }
        wasLyricsOffsetEditorOpen = showLyricsOffsetEditor
    }

    LaunchedEffect(bottomSheetState.isExpanded, requestPlayPauseFocus, currentSong?.id, controlsVisible) {
        if (bottomSheetState.isExpanded && requestPlayPauseFocus && controlsVisible) {
            runCatching { playPauseFocusRequester.requestFocus() }
            onPlayPauseFocusRequested()
        }
    }

    val gradientColors = if (currentSong?.displayThumbnail != null) uiState.gradientColors else emptyList()

    // Show video throughout visible sheet motion so reopening does not briefly expose static artwork.
    val shouldPresentMusicVideo =
        shouldShowMusicVideoSurface(
            isVideoModeActive = isVideoModeActive,
            isVideoViewerVisible = isVideoViewerVisible,
            hasVideo = playbackResource?.hasVideo == true,
            presentationAllowed = playerContentState.isVisible,
        )
    val showVideoSurface =
        shouldPresentMusicVideo && !uiState.isLyricsVisible &&
            currentSong?.id == uiState.currentSong?.id

    val isCanvasBackdropVisible =
        usesCanvasThumbnail &&
            !bottomSheetState.isCollapsed &&
            !showVideoSurface &&
            !uiState.isLyricsVisible
    val textBackgroundColor by animateColorAsState(
        targetValue =
            if (isCanvasBackdropVisible) {
                Color.White
            } else {
                when (playerBackground) {
                    PlayerBackgroundStyle.BLURRED_ARTWORK,
                    PlayerBackgroundStyle.LIQUID,
                    -> if (currentSong?.displayThumbnail != null) Color.White else MaterialTheme.colorScheme.onSurface

                    PlayerBackgroundStyle.GRADIENT,
                    PlayerBackgroundStyle.ANIMATED_GRADIENT,
                    -> Color.White

                    PlayerBackgroundStyle.SOLID -> MaterialTheme.colorScheme.onSurface
                }
            },
        animationSpec = if (!playerContentState.isActive) snap() else spring(),
        label = "TextBackgroundColor",
    )

    var canvasMinimalUi by rememberSaveable { mutableStateOf(false) }
    if (!isCanvasBackdropVisible || !allowArtworkExpansion) canvasMinimalUi = false
    val shouldFetchLyrics = playerContentState.isActive && (uiState.isLyricsVisible || uiState.isSubtitleActive)
    val shouldLoadLyrics = shouldFetchLyrics || (playerContentState.isActive && (Settings.shared.showLyricsOnPlayer || canvasMinimalUi))

    LaunchedEffect(
        currentSong?.id,
        shouldLoadLyrics,
        shouldFetchLyrics,
        Settings.shared.lyricsProviderOrder,
        Settings.shared.lyricsEnabledProviders,
        Settings.shared.preferWordSyncedLyrics,
    ) {
        if (shouldLoadLyrics && currentSong != null) {
            viewModel.loadLyrics(currentSong)
        }
    }

    val nextSong = queueItems.getOrNull(currentQueueIndex + 1)
    LaunchedEffect(currentSong?.id, nextSong?.id, shouldFetchLyrics) {
        if (shouldFetchLyrics && nextSong != null) {
            viewModel.currentPosition
                .map { it >= 30_000L }
                .distinctUntilChanged()
                .collectLatest { shouldPrefetch ->
                    if (shouldPrefetch) {
                        delay(1_000L.milliseconds)
                        viewModel.prefetchLyrics(nextSong)
                    }
                }
        }
    }

    val backgroundColor =
        when (playerBackground) {
            PlayerBackgroundStyle.BLURRED_ARTWORK,
            PlayerBackgroundStyle.GRADIENT,
            PlayerBackgroundStyle.ANIMATED_GRADIENT,
            PlayerBackgroundStyle.SOLID,
            PlayerBackgroundStyle.LIQUID,
            -> MaterialTheme.colorScheme.surfaceContainer
        }

    val (sideButtonContainerColor, sideButtonContentColor) =
        if (isCanvasBackdropVisible) {
            Pair(Color.Black.copy(alpha = 0.2f), Color.White)
        } else {
            Pair(MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
        }

    val palette = MaterialTheme.colorScheme

    val gradientColorStops =
        remember(gradientColors, palette, playerBackground) {
            val stops = playerGradientStops(gradientColors)
            if (stops.isNotEmpty()) {
                stops
            } else if (playerBackground == PlayerBackgroundStyle.GRADIENT || playerBackground == PlayerBackgroundStyle.ANIMATED_GRADIENT) {
                arrayOf(
                    0.0f to palette.primaryContainer,
                    0.5f to palette.tertiaryContainer,
                    1.0f to palette.surfaceContainer,
                )
            } else {
                emptyArray()
            }
        }

    val albumArtShape = RoundedCornerShape(PlayerArtworkCornerRadius)
    val smallAlbumArtShape = if (isTv) albumArtShape else ComponentShapes.SmallThumbnailShape
    val albumArtBoundsTransform =
        remember(allowArtworkExpansion) {
            BoundsTransform { _, _ ->
                if (!allowArtworkExpansion) {
                    snap()
                } else {
                    spring(
                        stiffness = Spring.StiffnessMediumLow,
                        dampingRatio = Spring.DampingRatioNoBouncy,
                    )
                }
            }
        }

    CompositionLocalProvider(LocalPlayerImmersiveMode provides shouldHideSystemBars) {
        BoxWithConstraints(
            modifier =
                modifier
                    .fillMaxSize()
                    .focusGroup(),
        ) {
            val screenWidth = this.maxWidth
            val maxHeight = this.maxHeight
            val useLandscapeLayout =
                if (isTv) {
                    (isLandscape || screenWidth > maxHeight) && screenWidth >= 600.dp && maxHeight >= 320.dp
                } else {
                    playerLayoutMode(screenWidth, maxHeight) == PlayerLayoutMode.LANDSCAPE
                }
            val useTabletLayout = isTv && useLandscapeLayout && minOf(screenWidth, maxHeight) >= 600.dp
            val compactPortraitHeight = !useLandscapeLayout && maxHeight < 560.dp
            val primaryControlSize = if (compactPortraitHeight) 64.dp else 72.dp
            val showFullscreenThumbnailBackground = usesFullscreenThumbnail
            val navigationBarBottomPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            val statusBarTopPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
            val displayCutoutTopPadding = WindowInsets.displayCutout.asPaddingValues().calculateTopPadding()
            val queueSheetTopPadding =
                maxOf(
                    statusBarTopPadding,
                    if (displayCutoutTopPadding > 0.dp) displayCutoutTopPadding + 4.dp else 0.dp,
                    if (isImmersive) 16.dp else 0.dp,
                )
            val queuePeekHeight = if (useLandscapeLayout) 0.dp else QueuePeekHeight + 16.dp + navigationBarBottomPadding
            val queueSheetState =
                rememberBottomSheetState(
                    dismissedBound = 0.dp,
                    collapsedBound = queuePeekHeight,
                    expandedBound = maxHeight,
                    initialAnchor = COLLAPSED_ANCHOR,
                    isDismissible = false,
                )

            LaunchedEffect(useLandscapeLayout, uiState.isLyricsExpanded) {
                if (useLandscapeLayout && uiState.isLyricsExpanded) {
                    viewModel.setLyricsExpanded(false)
                }
            }

            val queueSession = rememberQueueSession(viewModel)
            val queueFocusRequester = remember { FocusRequester() }
            val queueFocusModifier = if (isTv) Modifier.focusRequester(queueFocusRequester).focusGroup() else Modifier
            var restorePlayerFocusAfterQueue by remember { mutableStateOf(false) }
            LaunchedEffect(isTv, queueSheetState.isSettledExpanded, queueSheetState.isCollapsed) {
                if (!isTv) return@LaunchedEffect
                if (queueSheetState.isSettledExpanded) {
                    withFrameNanos { }
                    queueFocusRequester.requestFocus()
                    restorePlayerFocusAfterQueue = true
                } else if (queueSheetState.isCollapsed && restorePlayerFocusAfterQueue) {
                    withFrameNanos { }
                    if (bottomSheetState.isExpanded && controlsVisible) playPauseFocusRequester.requestFocus()
                    restorePlayerFocusAfterQueue = false
                }
            }
            val queueOpen by remember(queueSheetState, useLandscapeLayout) {
                derivedStateOf { !useLandscapeLayout && queueSheetState.progress > 0f }
            }
            val queueHandlesBack by remember(queueSheetState, useLandscapeLayout) {
                derivedStateOf { !useLandscapeLayout && queueSheetState.progress > 0.2f }
            }
            val landscapeQueueVisible = useLandscapeLayout && !queueSheetState.isCollapsed
            val queuePanelWidth = minOf(480.dp, screenWidth / 2)
            val queuePanelSlidePx = with(LocalDensity.current) { queuePanelWidth.toPx() } / 2f
            val queueSlideDirection = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1f else 1f
            val queueCoversPlayer = !useLandscapeLayout && queueSheetState.isSettledExpanded
            val contentActive = playerContentState.isActive && !queueCoversPlayer
            val hasVisibleVideoTransitionIndicator =
                isVideoModeResolving &&
                    showVideoButton &&
                    !isFullscreen &&
                    (useLandscapeLayout || !uiState.isLyricsVisible) &&
                    !canvasMinimalUi
            val artworkCrossfade = if (contentActive) platform.artworkCrossfade(currentSong?.id) else PlayerArtworkCrossfade()
            val crossfadeNextArtworkUrl = artworkCrossfade.nextArtworkUrl
            val artworkCrossfadeProgress = artworkCrossfade.progressProvider

            val canvasVisible = artworkAllowedOnNetwork && contentActive && !showVideoSurface && !uiState.isLyricsVisible
            DisposableEffect(viewModel, canvasVisible) {
                viewModel.setCanvasVisible(canvasVisible)
                onDispose { viewModel.setCanvasVisible(false) }
            }

            val canvasSpatialSpec = MaterialTheme.motionScheme.defaultSpatialSpec<IntSize>()
            val canvasSlideSpec = MaterialTheme.motionScheme.defaultSpatialSpec<IntOffset>()
            val canvasEffectsSpec = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
            val canvasControlsEnter =
                expandVertically(animationSpec = canvasSpatialSpec) +
                    fadeIn(animationSpec = canvasEffectsSpec)
            val canvasControlsExit =
                shrinkVertically(animationSpec = canvasSpatialSpec) +
                    fadeOut(animationSpec = canvasEffectsSpec)
            val playerControlsTransition =
                updateTransition(uiState.isLyricsExpanded to canvasMinimalUi, label = "playerControls")

            var playerRootCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
            var playerRootHeightPx by remember { mutableFloatStateOf(0f) }
            var songTitleTopPx by remember { mutableFloatStateOf(Float.NaN) }
            var artistBottomPx by remember { mutableFloatStateOf(Float.NaN) }
            val fullscreenTransitionBounds =
                remember {
                    derivedStateOf {
                        if (
                            playerRootHeightPx > 0f &&
                            !songTitleTopPx.isNaN() &&
                            !artistBottomPx.isNaN()
                        ) {
                            val start =
                                (
                                    (songTitleTopPx - playerRootHeightPx * FULLSCREEN_TITLE_TRANSITION_OFFSET_FRACTION) /
                                        playerRootHeightPx
                                ).coerceIn(0f, 0.85f)
                            val end =
                                (artistBottomPx / playerRootHeightPx)
                                    .coerceAtLeast(start + FULLSCREEN_MIN_FADE_SPAN_FRACTION)
                                    .coerceIn(start, 1f)
                            FullscreenTransitionBounds(
                                bottomTransitionStartFraction = start,
                                bottomTransitionEndFraction = end,
                            )
                        } else {
                            null
                        }
                    }
                }

            CompositionLocalProvider(
                LocalPlayerTrackArtwork provides trackPresentation.artwork,
                LocalPlayerArtworkPrefetchUrls provides if (isScreenVisible) artworkPrefetchUrls else emptyList(),
                LocalPlayerContentActive provides contentActive,
                LocalPlayerPositionActive provides (playerContentState.isPositionActive && !queueCoversPlayer),
                LocalPlayerBackgroundAnimating provides (contentActive && isPlaying),
            ) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .onPlaced { playerRootCoordinates = it }
                            .onSizeChanged { playerRootHeightPx = it.height.toFloat() },
                ) {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .background(backgroundColor),
                    )

                    if (isPlayerContentVisible && !isInPipMode && !isFullscreen) {
                        Box(Modifier.fillMaxSize().graphicsLayer { alpha = if (queueCoversPlayer) 0f else 1f }) {
                            if (playerBackground == PlayerBackgroundStyle.BLURRED_ARTWORK && currentSong?.displayThumbnail != null) {
                                PlayerBackgroundArtwork(currentSong.displayThumbnail!!, artworkCrossfade) { backgroundUrl ->
                                    PlayerBlurredArtworkBackground(
                                        thumbnailUrl = backgroundUrl,
                                        alpha = 1f,
                                        isLyricsVisible = uiState.isLyricsVisible,
                                    )
                                }
                            } else if (playerBackground == PlayerBackgroundStyle.LIQUID && currentSong?.displayThumbnail != null &&
                                !(showFullscreenThumbnailBackground && !uiState.isLyricsVisible && !showVideoSurface)
                            ) {
                                LiquidBackground(
                                    artworkUrl = currentSong.displayThumbnail,
                                    modifier = Modifier.fillMaxSize(),
                                    animate = LocalPlayerBackgroundAnimating.current,
                                )
                            } else if (uiState.isLyricsVisible) {
                                LyricsPlayerBackground(
                                    playerBackground = playerBackground,
                                    thumbnailUrl = currentSong?.displayThumbnail,
                                    backgroundColor = backgroundColor,
                                    gradientColorStops = gradientColorStops,
                                    alpha = 1f,
                                    artworkCrossfade = artworkCrossfade,
                                )
                            }
                            if (playerBackground != PlayerBackgroundStyle.SOLID) {
                                val hasArtwork =
                                    playerBackground == PlayerBackgroundStyle.BLURRED_ARTWORK ||
                                        playerBackground == PlayerBackgroundStyle.LIQUID
                                val isLiquid = playerBackground == PlayerBackgroundStyle.LIQUID
                                if (!hasArtwork || currentSong?.displayThumbnail != null) {
                                    LyricsContrastScrim(
                                        backgroundColors =
                                            if (!hasArtwork) {
                                                gradientColorStops
                                                    .map { (_, color) ->
                                                        Color.Black.copy(alpha = 0.2f).compositeOver(color.compositeOver(backgroundColor))
                                                    }.ifEmpty { listOf(backgroundColor) }
                                            } else {
                                                listOf(backgroundColor)
                                            },
                                        artworkUrl = currentSong?.displayThumbnail.takeIf { hasArtwork },
                                        artworkScrimAlpha = 0.3f,
                                        artworkWhiteAlpha = 0.12f,
                                        isLiquid = isLiquid,
                                        visible = uiState.isLyricsVisible,
                                    )
                                }
                            }
                            if (!uiState.isLyricsVisible) {
                                if (showFullscreenThumbnailBackground && currentSong?.displayThumbnail != null && !showVideoSurface) {
                                    FullscreenBackgroundBounds(fullscreenTransitionBounds) { transitionBounds ->
                                        FullscreenThumbnailBackground(
                                            thumbnailUrl = currentSong.displayThumbnail!!,
                                            playerBackground = playerBackground,
                                            backgroundColor = backgroundColor,
                                            gradientColorStops = gradientColorStops,
                                            alpha = 1f,
                                            transitionBounds = transitionBounds,
                                            isLandscape = useLandscapeLayout,
                                            canvasReady = animatedCoverReady && isArtworkCover,
                                            canvasKey = currentCanvasLayer?.key,
                                            canvasTall = currentCanvasLayer?.tall == true,
                                            artworkCrossfade = artworkCrossfade,
                                            canvasContent =
                                                if (renderAnimatedCover && !uiState.isLyricsVisible && isArtworkCover) {
                                                    { modifier, onFrame ->
                                                        canvasLayers.Render(
                                                            current = currentCanvasLayer,
                                                            playing = true,
                                                            onError = { layer -> viewModel.retryMusicVideoCanvas(layer.songId, layer.url) },
                                                            modifier = modifier,
                                                            onFrame = onFrame,
                                                        )
                                                    }
                                                } else {
                                                    null
                                                },
                                        )
                                    }
                                } else if (
                                    playerBackground != PlayerBackgroundStyle.BLURRED_ARTWORK &&
                                    usesFullscreenThumbnail && showVideoSurface && currentSong?.displayThumbnail != null
                                ) {
                                    PlayerBackgroundArtwork(currentSong.displayThumbnail!!, artworkCrossfade) { backgroundUrl ->
                                        BlurredArtworkBackground(
                                            url = backgroundUrl,
                                            modifier = Modifier.fillMaxSize(),
                                            blurRadiusPx = playerBackgroundBlurRadiusPx,
                                            pendingScrimAlpha = 0.5f,
                                        )
                                    }
                                    Box(
                                        modifier =
                                            Modifier
                                                .fillMaxSize()
                                                .background(Color.Black.copy(alpha = 0.3f)),
                                    )
                                }

                                if (
                                    !showFullscreenThumbnailBackground &&
                                    gradientColorStops.isNotEmpty() &&
                                    (
                                        playerBackground == PlayerBackgroundStyle.GRADIENT ||
                                            playerBackground == PlayerBackgroundStyle.ANIMATED_GRADIENT
                                    )
                                ) {
                                    val stops =
                                        rememberAnimatedGradientStops(
                                            gradientColorStops = gradientColorStops,
                                            animate = playerBackground == PlayerBackgroundStyle.ANIMATED_GRADIENT,
                                        )
                                    Box(
                                        Modifier
                                            .fillMaxSize()
                                            .drawBehind { drawRect(Brush.verticalGradient(colorStops = stops())) }
                                            .background(Color.Black.copy(alpha = 0.2f)),
                                    )
                                }

                                if (canRenderCanvasThumbnail && renderAnimatedCover && !showVideoSurface) {
                                    Box(Modifier.fillMaxSize()) {
                                        canvasLayers.Render(
                                            current = currentCanvasLayer,
                                            playing = true,
                                            onError = { layer -> viewModel.retryMusicVideoCanvas(layer.songId, layer.url) },
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                        if (usesCanvasThumbnail) {
                                            Box(
                                                Modifier
                                                    .fillMaxSize()
                                                    .background(
                                                        Brush.verticalGradient(
                                                            0f to Color.Black.copy(alpha = 0.65f),
                                                            0.12f to Color.Black.copy(alpha = 0.45f),
                                                            0.3f to Color.Black.copy(alpha = 0.2f),
                                                            0.48f to Color.Black.copy(alpha = 0.2f),
                                                            0.64f to Color.Black.copy(alpha = 0.65f),
                                                            1f to Color.Black.copy(alpha = 0.8f),
                                                        ),
                                                    ),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    SharedTransitionLayout(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .padding(end = if (landscapeQueueVisible) queuePanelWidth else 0.dp)
                                .then(
                                    if (landscapeQueueVisible) {
                                        Modifier.consumeWindowInsets(WindowInsets.safeDrawing.only(WindowInsetsSides.End))
                                    } else {
                                        Modifier
                                    },
                                ).graphicsLayer {
                                    alpha = if (queueCoversPlayer) 0f else 1f
                                    // The player is measured for its final narrow slot, so slide it in from the
                                    // screen centre as the queue panel rises instead of snapping it aside.
                                    if (landscapeQueueVisible) {
                                        translationX =
                                            (1f - queueSheetState.progress.coerceIn(0f, 1f)) *
                                            queuePanelSlidePx *
                                            queueSlideDirection
                                    }
                                },
                    ) {
                        val sharedTransitionScope = this
                        if (isFullscreen) {
                            FullscreenPlayerContent(
                                canControlPlayback = !isListenTogetherGuest,
                                backHandlerEnabled = isPlayerBackHandlerEnabled,
                                viewModel = viewModel,
                                currentSong = currentSong,
                                isPlaying = isPlaying,
                                duration = duration,
                                isLiked = isLiked,
                                uiState = uiState,
                                textBackgroundColor = textBackgroundColor,
                                showVideoSurface = shouldPresentMusicVideo,
                                onNavigateToArtist = onNavigateToArtist,
                                onToggleLike = toggleDisplayedLike,
                                onShareSong = shareDisplayedSong,
                                onAltShareSong = { copyDisplayedSongLink() },
                                onToggleFullscreen = viewModel::toggleFullscreen,
                                onShowPlayerMenu = { showPlayerMenu = true },
                                onShowSleepTimer = { showSleepTimerDialog = true },
                                onOpenCast = openCastDialog,
                                isCasting = castState.isConnected,
                            )
                        } else if (useLandscapeLayout) {
                            if (useTabletLayout || landscapeQueueVisible) {
                                TabletPlayerContent(
                                    viewModel = viewModel,
                                    artworkOverlay = artworkOverlay,
                                    onToggleFullscreen = onToggleFullscreen,
                                    showLyricsOffsetEditor = showLyricsOffsetEditor,
                                    onLyricsOffsetChange = { offset -> currentSong?.let { viewModel.setLyricsOffset(it.id, offset) } },
                                    onDoneLyricsOffset = { showLyricsOffsetEditor = false },
                                    currentSong = currentSong,
                                    crossfadeNextArtworkUrl = crossfadeNextArtworkUrl,
                                    artworkCrossfadeProgress = artworkCrossfadeProgress,
                                    isPlaying = isPlaying,
                                    duration = duration,
                                    isLiked = isLiked,
                                    uiState = uiState,
                                    textBackgroundColor = textBackgroundColor,
                                    sideButtonContainerColor = sideButtonContainerColor,
                                    sideButtonContentColor = sideButtonContentColor,
                                    onNavigateToArtist = onNavigateToArtist,
                                    onNavigateToAlbum = onNavigateToAlbum,
                                    sleepTimerState = sleepTimerState,
                                    onShowSleepTimer = { showSleepTimerDialog = true },
                                    onShowLyricsMenu = { showLyricsMenu = true },
                                    onOpenQueue = { queueSheetState.expandSoft() },
                                    onShowPlayerMenu = { showPlayerMenu = true },
                                    onIsLyricsVisibleChange = { visible -> viewModel.setLyricsVisible(visible) },
                                    onShareSong = { shareDisplayedSong() },
                                    onAltShareSong = { copyDisplayedSongLink() },
                                    onToggleLike = toggleDisplayedLike,
                                    isListenTogetherGuest = isListenTogetherGuest,
                                    artworkSwipeEnabled = artworkSwipeEnabled,
                                    artworkSwipeSensitivity = Settings.shared.swipeSensitivity,
                                    onArtworkSeek = onArtworkSeek,
                                    seekFeedback = seekFeedback,
                                    seekFeedbackVisible = seekFeedbackVisible,
                                    playerType = playerType,
                                    playPauseFocusRequester = playPauseFocusRequester,
                                    showVideoSurface = showVideoSurface,
                                    onOpenCast = openCastDialog,
                                    onClose = onClose,
                                    castState = castState,
                                    showVideoButton = showVideoButton,
                                    isVideoModeActive = isVideoModeActive,
                                    isVideoModeResolving = isVideoModeResolving,
                                    usesFullscreenThumbnail = showFullscreenThumbnailBackground,
                                    usesCanvasThumbnail = usesCanvasThumbnail,
                                )
                            } else {
                                LandscapePlayerContent(
                                    viewModel = viewModel,
                                    artworkOverlay = artworkOverlay,
                                    onToggleFullscreen = onToggleFullscreen,
                                    showLyricsOffsetEditor = showLyricsOffsetEditor,
                                    onLyricsOffsetChange = { offset -> currentSong?.let { viewModel.setLyricsOffset(it.id, offset) } },
                                    onDoneLyricsOffset = { showLyricsOffsetEditor = false },
                                    currentSong = currentSong,
                                    crossfadeNextArtworkUrl = crossfadeNextArtworkUrl,
                                    artworkCrossfadeProgress = artworkCrossfadeProgress,
                                    isPlaying = isPlaying,
                                    duration = duration,
                                    isLiked = isLiked,
                                    uiState = uiState,
                                    textBackgroundColor = textBackgroundColor,
                                    sideButtonContainerColor = sideButtonContainerColor,
                                    sideButtonContentColor = sideButtonContentColor,
                                    onNavigateToArtist = onNavigateToArtist,
                                    onNavigateToAlbum = onNavigateToAlbum,
                                    sleepTimerState = sleepTimerState,
                                    onShowSleepTimer = { showSleepTimerDialog = true },
                                    onShowLyricsMenu = { showLyricsMenu = true },
                                    onOpenQueue = { queueSheetState.expandSoft() },
                                    onShowPlayerMenu = { showPlayerMenu = true },
                                    onIsLyricsVisibleChange = { visible -> viewModel.setLyricsVisible(visible) },
                                    onShareSong = { shareDisplayedSong() },
                                    onAltShareSong = { copyDisplayedSongLink() },
                                    onToggleLike = toggleDisplayedLike,
                                    isListenTogetherGuest = isListenTogetherGuest,
                                    artworkSwipeEnabled = artworkSwipeEnabled,
                                    artworkSwipeSensitivity = Settings.shared.swipeSensitivity,
                                    onArtworkSeek = onArtworkSeek,
                                    seekFeedback = seekFeedback,
                                    seekFeedbackVisible = seekFeedbackVisible,
                                    playerType = playerType,
                                    playPauseFocusRequester = playPauseFocusRequester,
                                    showVideoSurface = showVideoSurface,
                                    onOpenCast = openCastDialog,
                                    castState = castState,
                                    showVideoButton = showVideoButton,
                                    isVideoModeActive = isVideoModeActive,
                                    isVideoModeResolving = isVideoModeResolving,
                                    usesFullscreenThumbnail = showFullscreenThumbnailBackground,
                                    usesCanvasThumbnail = usesCanvasThumbnail,
                                )
                            }
                        } else {
                            // Split-screen windows can be shorter than the controls; scroll instead of overlapping them.
                            val portraitScroll = rememberScrollState()
                            val scrollsPortrait = maxHeight < MinPortraitPlayerHeight
                            // Open on the playback controls; the bottom bar would otherwise cover them.
                            LaunchedEffect(scrollsPortrait) {
                                if (scrollsPortrait) {
                                    portraitScroll.scrollTo(snapshotFlow { portraitScroll.maxValue }.first { it > 0 })
                                }
                            }
                            Column(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .then(
                                            if (scrollsPortrait) {
                                                // Pulling down past the top still collapses the player.
                                                Modifier
                                                    .nestedScroll(bottomSheetState.preUpPostDownNestedScrollConnection)
                                                    .verticalScroll(portraitScroll)
                                                    .height(MinPortraitPlayerHeight)
                                            } else {
                                                Modifier.fillMaxHeight()
                                            },
                                        ).windowInsetsPadding(WindowInsets.safeDrawing)
                                        .windowInsetsPadding(
                                            WindowInsets.mandatorySystemGestures.only(WindowInsetsSides.Bottom),
                                        ).padding(horizontal = 24.dp)
                                        .padding(top = 16.dp),
                            ) {
                                AnimatedContent(
                                    targetState = uiState.isLyricsVisible,
                                    transitionSpec = {
                                        fadeIn(animationSpec = tween(400, delayMillis = 90))
                                            .togetherWith(fadeOut(animationSpec = tween(400)))
                                    },
                                    label = "lyricsToggle",
                                    modifier = Modifier.weight(1f),
                                ) { showLyrics ->
                                    if (showLyrics) {
                                        LyricsViewWithPosition(
                                            viewModel = viewModel,
                                            lyrics = uiState.lyrics,
                                            songwriters = uiState.lyricsSongwriters,
                                            agents = uiState.lyricsAgents,
                                            provider = uiState.lyricsProvider,
                                            timingOffsetMs = uiState.lyricsOffsetMs,
                                            onSeek = viewModel::seekToPosition,
                                            isLoading = uiState.isLyricsLoading,
                                            textColor = textBackgroundColor,
                                            isCasting = castState.isConnected,
                                        )
                                    } else {
                                        Column(
                                            modifier = Modifier.fillMaxSize(),
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                        ) {
                                            AnimatedVisibility(
                                                visible = !canvasMinimalUi,
                                                enter = canvasControlsEnter,
                                                exit = canvasControlsExit,
                                            ) {
                                                Box(
                                                    modifier =
                                                        Modifier
                                                            .fillMaxWidth()
                                                            .padding(
                                                                bottom = if (compactPortraitHeight) 0.dp else 12.dp,
                                                            ).height(48.dp),
                                                    contentAlignment = Alignment.Center,
                                                ) {
                                                    val songText =
                                                        stringResource(Res.string.song)
                                                    val videoText =
                                                        stringResource(Res.string.video)
                                                    val isVideoActive = isVideoModeActive
                                                    val isVideoResolving = isVideoModeResolving
                                                    val isResolvingToVideo = isVideoResolving && !isVideoActive
                                                    val isResolvingToSong = isVideoResolving && isVideoActive

                                                    if (showVideoButton && playerType == PlayerType.CLASSIC) {
                                                        VintageAudioVideoModeSwitcher(
                                                            songDescription = songText,
                                                            videoDescription = videoText,
                                                            isVideoActive = isVideoActive,
                                                            isResolving = isVideoResolving,
                                                            contentColor = textBackgroundColor,
                                                            onSongClick = {
                                                                hapticManager.perform(HapticEvent.PRESS)
                                                                playerConnection?.setVideoMode(false)
                                                            },
                                                            onVideoClick = {
                                                                hapticManager.perform(HapticEvent.PRESS)
                                                                playerConnection?.setVideoMode(true)
                                                            },
                                                            modifier = Modifier.playerControlBlend(),
                                                        )
                                                    }

                                                    if (showVideoButton && playerType != PlayerType.CLASSIC) {
                                                        ButtonGroup(
                                                            overflowIndicator = { menuState ->
                                                                ButtonGroupDefaults.OverflowIndicator(menuState)
                                                            },
                                                            horizontalArrangement =
                                                                Arrangement.spacedBy(
                                                                    ButtonGroupDefaults.ConnectedSpaceBetween,
                                                                ),
                                                            modifier = Modifier.height(40.dp),
                                                        ) {
                                                            val songSelected = !isVideoActive
                                                            customItem(
                                                                buttonGroupContent = {
                                                                    val interactionSource = remember { MutableInteractionSource() }
                                                                    ToggleButton(
                                                                        interactionSource = interactionSource,
                                                                        checked = songSelected,
                                                                        enabled = !isVideoResolving,
                                                                        onCheckedChange = {
                                                                            hapticManager.perform(HapticEvent.PRESS)
                                                                            playerConnection?.setVideoMode(false)
                                                                        },
                                                                        shapes = staticConnectedLeadingButtonShapes(),
                                                                        contentPadding = PaddingValues(0.dp),
                                                                        colors =
                                                                            ToggleButtonDefaults.colors(
                                                                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                                                                contentColor = textBackgroundColor,
                                                                                checkedContainerColor = MaterialTheme.colorScheme.primary,
                                                                                checkedContentColor = MaterialTheme.colorScheme.onPrimary,
                                                                                disabledContainerColor =
                                                                                    MaterialTheme.colorScheme.surfaceVariant,
                                                                                disabledContentColor = textBackgroundColor,
                                                                            ),
                                                                        modifier =
                                                                            Modifier
                                                                                .size(
                                                                                    width = 46.dp,
                                                                                    height = 40.dp,
                                                                                ).animateWidth(interactionSource, 12.dp)
                                                                                .playerControlBlend(),
                                                                    ) {
                                                                        if (isResolvingToSong && LocalPlayerContentActive.current) {
                                                                            LoadingIndicator(
                                                                                modifier = Modifier.size(18.dp),
                                                                                color = MaterialTheme.colorScheme.primary,
                                                                            )
                                                                        } else {
                                                                            Icon(
                                                                                imageVector =
                                                                                    if (songSelected) {
                                                                                        MetrolistIcons.Filled.Headphones
                                                                                    } else {
                                                                                        MetrolistIcons.Outlined.Headphones
                                                                                    },
                                                                                contentDescription = songText,
                                                                                modifier = Modifier.size(20.dp),
                                                                            )
                                                                        }
                                                                    }
                                                                },
                                                                menuContent = {
                                                                    DropdownMenuItem(
                                                                        leadingIcon = {
                                                                            if (isResolvingToSong && LocalPlayerContentActive.current) {
                                                                                LoadingIndicator(
                                                                                    modifier = Modifier.size(20.dp),
                                                                                    color = MaterialTheme.colorScheme.primary,
                                                                                )
                                                                            } else {
                                                                                Icon(
                                                                                    imageVector =
                                                                                        if (songSelected) {
                                                                                            MetrolistIcons.Filled.Headphones
                                                                                        } else {
                                                                                            MetrolistIcons.Outlined.Headphones
                                                                                        },
                                                                                    contentDescription = null,
                                                                                )
                                                                            }
                                                                        },
                                                                        text = { Text(songText) },
                                                                        enabled = !isVideoResolving,
                                                                        onClick = {
                                                                            hapticManager.perform(HapticEvent.PRESS)
                                                                            playerConnection?.setVideoMode(false)
                                                                        },
                                                                    )
                                                                },
                                                            )

                                                            customItem(
                                                                buttonGroupContent = {
                                                                    val interactionSource = remember { MutableInteractionSource() }
                                                                    ToggleButton(
                                                                        interactionSource = interactionSource,
                                                                        checked = isVideoActive,
                                                                        enabled = showVideoButton && !isVideoResolving,
                                                                        onCheckedChange = {
                                                                            hapticManager.perform(HapticEvent.PRESS)
                                                                            playerConnection?.setVideoMode(true)
                                                                        },
                                                                        shapes = staticConnectedTrailingButtonShapes(),
                                                                        contentPadding = PaddingValues(0.dp),
                                                                        colors =
                                                                            ToggleButtonDefaults.colors(
                                                                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                                                                contentColor = textBackgroundColor,
                                                                                checkedContainerColor = MaterialTheme.colorScheme.primary,
                                                                                checkedContentColor = MaterialTheme.colorScheme.onPrimary,
                                                                                disabledContainerColor =
                                                                                    MaterialTheme.colorScheme.surfaceVariant,
                                                                                disabledContentColor = textBackgroundColor,
                                                                            ),
                                                                        modifier =
                                                                            Modifier
                                                                                .size(
                                                                                    width = 46.dp,
                                                                                    height = 40.dp,
                                                                                ).animateWidth(interactionSource, 12.dp)
                                                                                .playerControlBlend(),
                                                                    ) {
                                                                        if (isResolvingToVideo && LocalPlayerContentActive.current) {
                                                                            LoadingIndicator(
                                                                                modifier = Modifier.size(18.dp),
                                                                                color = MaterialTheme.colorScheme.primary,
                                                                            )
                                                                        } else {
                                                                            Icon(
                                                                                imageVector =
                                                                                    if (isVideoActive) {
                                                                                        MetrolistIcons.Filled.SmartDisplay
                                                                                    } else {
                                                                                        MetrolistIcons.Outlined.SmartDisplay
                                                                                    },
                                                                                contentDescription = videoText,
                                                                                modifier = Modifier.size(20.dp),
                                                                            )
                                                                        }
                                                                    }
                                                                },
                                                                menuContent = {
                                                                    DropdownMenuItem(
                                                                        leadingIcon = {
                                                                            if (isResolvingToVideo && LocalPlayerContentActive.current) {
                                                                                LoadingIndicator(
                                                                                    modifier = Modifier.size(20.dp),
                                                                                    color = MaterialTheme.colorScheme.primary,
                                                                                )
                                                                            } else {
                                                                                Icon(
                                                                                    imageVector =
                                                                                        if (isVideoActive) {
                                                                                            MetrolistIcons.Filled.SmartDisplay
                                                                                        } else {
                                                                                            MetrolistIcons.Outlined.SmartDisplay
                                                                                        },
                                                                                    contentDescription = null,
                                                                                )
                                                                            }
                                                                        },
                                                                        text = { Text(videoText) },
                                                                        enabled = showVideoButton && !isVideoResolving,
                                                                        onClick = {
                                                                            hapticManager.perform(HapticEvent.PRESS)
                                                                            playerConnection?.setVideoMode(true)
                                                                        },
                                                                    )
                                                                },
                                                            )
                                                        }
                                                    }

                                                    if (!showVideoButton && playerType == PlayerType.CLASSIC) {
                                                        Column(
                                                            horizontalAlignment = Alignment.CenterHorizontally,
                                                            modifier = Modifier.align(Alignment.Center).padding(horizontal = 52.dp),
                                                        ) {
                                                            Text(
                                                                text = stringResource(Res.string.now_playing),
                                                                style = MaterialTheme.typography.titleMedium,
                                                                color = textBackgroundColor,
                                                                maxLines = 1,
                                                            )
                                                            uiState.queueTitle?.takeIf { it.isNotBlank() }?.let { playingFrom ->
                                                                Text(
                                                                    text = playingFrom,
                                                                    style = MaterialTheme.typography.titleMedium,
                                                                    color = textBackgroundColor.copy(alpha = 0.8f),
                                                                    maxLines = 1,
                                                                    modifier =
                                                                        Modifier.basicMarquee(
                                                                            iterations = if (LocalPlayerContentActive.current) 1 else 0,
                                                                            initialDelayMillis = 3000,
                                                                            velocity = 30.dp,
                                                                        ),
                                                                )
                                                            }
                                                        }
                                                    }

                                                    FilledIconButton(
                                                        onClick = {
                                                            hapticManager.perform(HapticEvent.PRESS)
                                                            onClose()
                                                        },
                                                        shapes = IconButtonDefaults.shapes(shape = CircleShape),
                                                        colors =
                                                            IconButtonDefaults.filledIconButtonColors(
                                                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                                                contentColor = textBackgroundColor,
                                                            ),
                                                        modifier =
                                                            Modifier
                                                                .align(Alignment.CenterStart)
                                                                .size(36.dp)
                                                                .playerControlBlend(),
                                                    ) {
                                                        Icon(
                                                            imageVector = MetrolistIcons.Outlined.KeyboardArrowDown,
                                                            contentDescription = stringResource(Res.string.collapse),
                                                            modifier = Modifier.size(24.dp),
                                                        )
                                                    }

                                                    Row(
                                                        modifier = Modifier.align(Alignment.CenterEnd),
                                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                        verticalAlignment = Alignment.CenterVertically,
                                                    ) {
                                                        if (!isTv && !isListenTogetherGuest && platform.supportsCasting &&
                                                            Settings.shared.isPlayerCastButtonVisible
                                                        ) {
                                                            FilledIconButton(
                                                                onClick = {
                                                                    hapticManager.perform(HapticEvent.PRESS)
                                                                    openCastDialog()
                                                                },
                                                                shapes = IconButtonDefaults.shapes(shape = CircleShape),
                                                                colors =
                                                                    IconButtonDefaults.filledIconButtonColors(
                                                                        containerColor =
                                                                            if (castState.isConnected) {
                                                                                MaterialTheme.colorScheme.primary
                                                                            } else {
                                                                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                                                            },
                                                                        contentColor =
                                                                            if (castState.isConnected) {
                                                                                MaterialTheme.colorScheme.onPrimary
                                                                            } else {
                                                                                textBackgroundColor
                                                                            },
                                                                    ),
                                                                modifier = Modifier.size(36.dp).playerControlBlend(),
                                                            ) {
                                                                Icon(
                                                                    imageVector =
                                                                        if (castState.isConnected) {
                                                                            MetrolistIcons.Filled.Cast
                                                                        } else {
                                                                            MetrolistIcons.Outlined.Cast
                                                                        },
                                                                    contentDescription = stringResource(Res.string.cast),
                                                                    modifier = Modifier.size(20.dp),
                                                                )
                                                            }
                                                        }
                                                    }
                                                }
                                            }

                                            Column(
                                                modifier = Modifier.weight(1f),
                                                horizontalAlignment = Alignment.CenterHorizontally,
                                                verticalArrangement =
                                                    if (usesFullscreenThumbnail) {
                                                        Arrangement.Bottom
                                                    } else {
                                                        Arrangement.Center
                                                    },
                                            ) {
                                                Box(
                                                    contentAlignment = Alignment.Center,
                                                    modifier =
                                                        Modifier
                                                            .fillMaxWidth()
                                                            .weight(1f, fill = usesCanvasThumbnail || usesFullscreenThumbnail)
                                                            .artworkSwipe(
                                                                enabled = artworkSwipeEnabled,
                                                                sensitivity = Settings.shared.swipeSensitivity,
                                                                onPrevious = viewModel::previous,
                                                                onNext = viewModel::next,
                                                            ).artworkSeekGestures(
                                                                seekEnabled = !isListenTogetherGuest,
                                                                stepMs = artworkSeekStepMs,
                                                                onTap =
                                                                    if (isCanvasBackdropVisible && allowArtworkExpansion) {
                                                                        { canvasMinimalUi = !canvasMinimalUi }
                                                                    } else {
                                                                        null
                                                                    },
                                                                tapLabel =
                                                                    if (isCanvasBackdropVisible && allowArtworkExpansion) {
                                                                        stringResource(
                                                                            if (canvasMinimalUi) Res.string.expand else Res.string.collapse,
                                                                        )
                                                                    } else {
                                                                        null
                                                                    },
                                                                progressive = Settings.shared.seekExtraSeconds,
                                                                onSeek = onArtworkSeek,
                                                            ),
                                                ) {
                                                    if (showVideoSurface) {
                                                        platform.MusicVideoSurface(
                                                            cornerRadius = 0.dp,
                                                            fillContainer = true,
                                                            showControls = true,
                                                            useSurfaceView = false,
                                                            onFullscreenClick = onToggleFullscreen,
                                                            onPiPClick = {
                                                                platform.enterPictureInPicture(
                                                                    isPlaying = isPlaying,
                                                                    hasVideo = true,
                                                                )
                                                            },
                                                            modifier =
                                                                Modifier.requiredWidthIn(max = windowWidth),
                                                        )
                                                    } else if (
                                                        !usesFullscreenThumbnail &&
                                                        !usesCanvasThumbnail &&
                                                        currentSong?.displayThumbnail != null &&
                                                        !(
                                                            isVideoModeActive &&
                                                                playbackResource?.hasVideo == true &&
                                                                bottomSheetState.isCollapsed
                                                        )
                                                    ) {
                                                        with(sharedTransitionScope) {
                                                            Box(
                                                                modifier =
                                                                    Modifier
                                                                        .sharedElement(
                                                                            sharedContentState =
                                                                                rememberSharedContentState(
                                                                                    ALBUM_ART_SHARED_KEY,
                                                                                ),
                                                                            animatedVisibilityScope = this@AnimatedContent,
                                                                            boundsTransform = albumArtBoundsTransform,
                                                                            clipInOverlayDuringTransition = OverlayClip(albumArtShape),
                                                                        ).aspectRatio(1f)
                                                                        .clip(albumArtShape),
                                                            ) {
                                                                CrossfadeArtwork(
                                                                    animate = LocalPlayerContentActive.current,
                                                                    currentUrl = currentSong.displayThumbnail,
                                                                    nextUrl = crossfadeNextArtworkUrl,
                                                                    progress = artworkCrossfadeProgress,
                                                                    contentDescription = stringResource(Res.string.album_art),
                                                                    modifier = Modifier.fillMaxSize(),
                                                                    contentScale = ContentScale.Crop,
                                                                )
                                                                if (!uiState.isLyricsVisible) artworkOverlay(Modifier.fillMaxSize())
                                                            }
                                                        }
                                                    }
                                                    SeekFeedbackOverlay(
                                                        feedback = seekFeedback,
                                                        visible = seekFeedbackVisible,
                                                    )
                                                }

                                                ActiveLyricsLine(
                                                    viewModel = viewModel,
                                                    lyrics = uiState.lyrics,
                                                    songId = currentSong?.id,
                                                    timingOffsetMs = uiState.lyricsOffsetMs,
                                                    isPlaying = isPlaying,
                                                    showLyrics = Settings.shared.showLyricsOnPlayer || canvasMinimalUi,
                                                    textColor =
                                                        if (usesFullscreenThumbnail || canvasMinimalUi) {
                                                            Color.White
                                                        } else {
                                                            textBackgroundColor
                                                        },
                                                    isCasting = castState.isConnected,
                                                    hasBackground = usesFullscreenThumbnail || canvasMinimalUi,
                                                    modifier = Modifier.fillMaxWidth(),
                                                )
                                            }
                                        }
                                    }
                                }

                                AnimatedVisibility(
                                    visible = uiState.isLyricsVisible && showLyricsOffsetEditor,
                                    enter = expandVertically() + fadeIn(),
                                    exit = shrinkVertically() + fadeOut(),
                                ) {
                                    LyricsOffsetEditor(
                                        offsetMs = uiState.lyricsOffsetMs,
                                        onOffsetChange = { offset -> currentSong?.let { viewModel.setLyricsOffset(it.id, offset) } },
                                        onDone = { showLyricsOffsetEditor = false },
                                        contentColor = textBackgroundColor,
                                    )
                                }

                                Spacer(modifier = Modifier.height(if (showLyricsOffsetEditor || compactPortraitHeight) 8.dp else 24.dp))

                                Row(
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Row(
                                        modifier = Modifier.weight(1f),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        AnimatedVisibility(
                                            visible =
                                                (usesCanvasThumbnail || uiState.isLyricsVisible) &&
                                                    currentSong?.displayThumbnail != null,
                                            enter = expandHorizontally(clip = false) + fadeIn(),
                                            exit = shrinkHorizontally(clip = false) + fadeOut(),
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                if (usesFullscreenThumbnail) {
                                                    CrossfadeArtwork(
                                                        animate = LocalPlayerContentActive.current,
                                                        currentUrl = currentSong?.displayThumbnail,
                                                        nextUrl = crossfadeNextArtworkUrl,
                                                        progress = artworkCrossfadeProgress,
                                                        contentDescription = stringResource(Res.string.small_album_art),
                                                        modifier =
                                                            Modifier
                                                                .size(48.dp)
                                                                .clip(smallAlbumArtShape),
                                                        contentScale = ContentScale.Crop,
                                                    )
                                                } else {
                                                    with(sharedTransitionScope) {
                                                        Box(
                                                            modifier =
                                                                Modifier
                                                                    .sharedElement(
                                                                        sharedContentState =
                                                                            rememberSharedContentState(
                                                                                ALBUM_ART_SHARED_KEY,
                                                                            ),
                                                                        animatedVisibilityScope = this@AnimatedVisibility,
                                                                        boundsTransform = albumArtBoundsTransform,
                                                                        clipInOverlayDuringTransition =
                                                                            OverlayClip(
                                                                                smallAlbumArtShape,
                                                                            ),
                                                                    ).size(48.dp)
                                                                    .clip(smallAlbumArtShape),
                                                        ) {
                                                            CrossfadeArtwork(
                                                                animate = LocalPlayerContentActive.current,
                                                                currentUrl = currentSong?.displayThumbnail,
                                                                nextUrl = crossfadeNextArtworkUrl,
                                                                progress = artworkCrossfadeProgress,
                                                                contentDescription = stringResource(Res.string.small_album_art),
                                                                modifier = Modifier.fillMaxSize(),
                                                                contentScale = ContentScale.Crop,
                                                            )
                                                            if (uiState.isLyricsVisible) artworkOverlay(Modifier.fillMaxSize())
                                                        }
                                                    }
                                                }
                                                Spacer(modifier = Modifier.width(12.dp))
                                            }
                                        }

                                        Column(
                                            modifier = Modifier.weight(1f),
                                            verticalArrangement = Arrangement.spacedBy(4.dp),
                                        ) {
                                            val titleEffectsSpec = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
                                            AnimatedContent(
                                                targetState = currentSong,
                                                contentKey = { it?.id },
                                                transitionSpec = {
                                                    (fadeIn(titleEffectsSpec) togetherWith fadeOut(titleEffectsSpec))
                                                        .using(SizeTransform(clip = false))
                                                },
                                                contentAlignment = Alignment.CenterStart,
                                                label = "titleAnimation",
                                                modifier =
                                                    if (usesFullscreenThumbnail) {
                                                        Modifier.onGloballyPositioned { coordinates ->
                                                            playerRootCoordinates?.let { root ->
                                                                // Exclude the sheet translation from the artwork crop bounds.
                                                                songTitleTopPx = root.localPositionOf(coordinates, Offset.Zero).y
                                                            }
                                                        }
                                                    } else {
                                                        Modifier
                                                    },
                                            ) { displayedSong ->
                                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                    Text(
                                                        text = displayedSong?.title ?: stringResource(Res.string.unknown),
                                                        style =
                                                            if (usesCanvasThumbnail) {
                                                                MaterialTheme.typography.titleMedium
                                                            } else {
                                                                MaterialTheme.typography.titleLarge
                                                            },
                                                        fontWeight = FontWeight.Bold,
                                                        color = textBackgroundColor,
                                                        maxLines = 1,
                                                        softWrap = false,
                                                        overflow = TextOverflow.Ellipsis,
                                                        modifier =
                                                            Modifier
                                                                .clickable(enabled = displayedSong?.albumId != null) {
                                                                    displayedSong?.albumId?.let(onNavigateToAlbum)
                                                                }.then(
                                                                    displayedSong?.let { song ->
                                                                        rememberTrackMarquee(
                                                                            trackId = song.id,
                                                                            enabled = LocalPlayerContentActive.current,
                                                                            initialDelayMillis = 3000L,
                                                                        )
                                                                    } ?: Modifier,
                                                                ),
                                                    )
                                                    PlayerArtistLine(
                                                        artists = displayedSong?.artists,
                                                        onNavigateToArtist = onNavigateToArtist,
                                                        iterations = if (LocalPlayerContentActive.current) 1 else 0,
                                                        style =
                                                            if (usesCanvasThumbnail) {
                                                                MaterialTheme.typography.bodyMedium
                                                            } else {
                                                                MaterialTheme.typography.titleMedium
                                                            },
                                                        color = textBackgroundColor.copy(alpha = 0.7f),
                                                        modifier =
                                                            Modifier
                                                                .then(
                                                                    if (usesFullscreenThumbnail) {
                                                                        Modifier.onGloballyPositioned { coordinates ->
                                                                            playerRootCoordinates?.let { root ->
                                                                                artistBottomPx =
                                                                                    root.localPositionOf(coordinates, Offset.Zero).y +
                                                                                    coordinates.size.height
                                                                            }
                                                                        }
                                                                    } else {
                                                                        Modifier
                                                                    },
                                                                ),
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    Spacer(modifier = Modifier.width(12.dp))

                                    AnimatedVisibility(
                                        visible = !canvasMinimalUi,
                                        enter =
                                            expandHorizontally(animationSpec = canvasSpatialSpec) +
                                                fadeIn(animationSpec = canvasEffectsSpec),
                                        exit =
                                            shrinkHorizontally(animationSpec = canvasSpatialSpec) +
                                                fadeOut(animationSpec = canvasEffectsSpec),
                                    ) {
                                        if (playerType == PlayerType.CLASSIC) {
                                            VintageClassicActionButtons(
                                                isLyricsVisible = uiState.isLyricsVisible,
                                                isLyricsExpanded = uiState.isLyricsExpanded,
                                                shareDescription = stringResource(Res.string.share),
                                                expandDescription = stringResource(Res.string.expand),
                                                collapseDescription = stringResource(Res.string.collapse),
                                                moreDescription = stringResource(Res.string.more_options),
                                                moreLyricsDescription = stringResource(Res.string.more_lyrics_options),
                                                onToggleLyricsExpanded = {
                                                    Logger.d(
                                                        "Haptics",
                                                        "Trigger PlayerScreen lyrics expand click expanded=${!uiState.isLyricsExpanded}",
                                                    )
                                                    hapticManager.perform(HapticEvent.PRESS)
                                                    viewModel.setLyricsExpanded(!uiState.isLyricsExpanded)
                                                },
                                                onShowLyricsMenu = {
                                                    hapticManager.perform(HapticEvent.PRESS)
                                                    showLyricsMenu = true
                                                },
                                                onShareClick = {
                                                    Logger.d("Haptics", "Trigger PlayerScreen share click")
                                                    hapticManager.perform(HapticEvent.PRESS)
                                                    shareDisplayedSong()
                                                },
                                                onShowPlayerMenu = {
                                                    hapticManager.perform(HapticEvent.PRESS)
                                                    showPlayerMenu = true
                                                },
                                                buttonColor = textBackgroundColor,
                                                onButtonColor = vintageOnButtonColor(textBackgroundColor),
                                            )
                                        } else {
                                            PlayerActionRow(
                                                contentColor = textBackgroundColor,
                                                isLyricsVisible = uiState.isLyricsVisible,
                                                isLyricsExpanded = uiState.isLyricsExpanded,
                                                onToggleLyricsExpanded = {
                                                    Logger.d(
                                                        "Haptics",
                                                        "Trigger PlayerScreen lyrics expand click expanded=${!uiState.isLyricsExpanded}",
                                                    )
                                                    hapticManager.perform(HapticEvent.PRESS)
                                                    viewModel.setLyricsExpanded(!uiState.isLyricsExpanded)
                                                },
                                                onShowLyricsMenu = { showLyricsMenu = true },
                                                isLiked = isLiked,
                                                onLikeClick = {
                                                    Logger.d("Haptics", "Trigger PlayerScreen like click liked=$isLiked")
                                                    hapticManager.toggle(!isLiked)
                                                    toggleDisplayedLike()
                                                },
                                                onShareClick = {
                                                    Logger.d("Haptics", "Trigger PlayerScreen share click")
                                                    hapticManager.perform(HapticEvent.PRESS)
                                                    shareDisplayedSong()
                                                },
                                                onAltShareClick = {
                                                    Logger.d("Haptics", "Trigger PlayerScreen alt share click")
                                                    hapticManager.perform(HapticEvent.PRESS)
                                                    copyDisplayedSongLink()
                                                },
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(12.dp))

                                PlayerProgressSlider(
                                    viewModel = viewModel,
                                    duration = duration,
                                    isPlaying = isPlaying,
                                    textBackgroundColor = textBackgroundColor,
                                    canSeek = !isListenTogetherGuest,
                                    isCasting = castState.isConnected,
                                    songId = currentSong?.id,
                                    progressColor = textBackgroundColor.takeIf { playerType == PlayerType.CLASSIC },
                                    isLive = uiState.isLive || playbackResource?.isLive == true,
                                )

                                playerControlsTransition.AnimatedVisibility(
                                    visible = { (expanded, minimalUi) ->
                                        (!expanded || showControlsInFullscreen) &&
                                            !minimalUi
                                    },
                                    enter = canvasControlsEnter,
                                    exit = canvasControlsExit,
                                ) {
                                    Column {
                                        if (!isListenTogetherGuest) {
                                            Spacer(modifier = Modifier.height(8.dp))

                                            when (playerType) {
                                                PlayerType.CLASSIC -> {
                                                    VintageClassicControls(
                                                        playPauseFocusRequester = playPauseFocusRequester,
                                                        text =
                                                            VintageClassicControlsText(
                                                                repeat = stringResource(Res.string.repeat),
                                                                previous = stringResource(Res.string.previous),
                                                                play = stringResource(Res.string.play),
                                                                pause = stringResource(Res.string.pause),
                                                                next = stringResource(Res.string.next),
                                                                like = stringResource(Res.string.action_like),
                                                                unlike = stringResource(Res.string.unlike),
                                                            ),
                                                        isPlaying = isPlaying,
                                                        isLoading = isPlaybackLoading && contentActive,
                                                        showLoadingIndicator = !hasVisibleVideoTransitionIndicator,
                                                        repeatMode = repeatMode,
                                                        isLiked = isLiked,
                                                        onToggleRepeat = {
                                                            hapticManager.perform(HapticEvent.PRESS)
                                                            viewModel.toggleRepeat()
                                                        },
                                                        onSkipPrevious = {
                                                            Logger.d("Haptics", "Trigger PlayerScreen previous click")
                                                            hapticManager.perform(HapticEvent.PRESS)
                                                            viewModel.previous()
                                                        },
                                                        onTogglePlay = {
                                                            Logger.d(
                                                                "Haptics",
                                                                "Trigger PlayerScreen play pause click isPlaying=$isPlaying",
                                                            )
                                                            viewModel.togglePlayPause()
                                                        },
                                                        onSkipNext = {
                                                            Logger.d("Haptics", "Trigger PlayerScreen next click")
                                                            hapticManager.perform(HapticEvent.PRESS)
                                                            viewModel.next()
                                                        },
                                                        onToggleLike = {
                                                            Logger.d("Haptics", "Trigger PlayerScreen like click liked=$isLiked")
                                                            hapticManager.toggle(!isLiked)
                                                            toggleDisplayedLike()
                                                        },
                                                        contentColor = textBackgroundColor,
                                                        buttonColor = textBackgroundColor,
                                                        onButtonColor = vintageOnButtonColor(textBackgroundColor),
                                                        modifier = Modifier.fillMaxWidth(),
                                                        buttonSize = primaryControlSize,
                                                    )
                                                }

                                                PlayerType.VINTAGE -> {
                                                    VintagePlayerControls(
                                                        playPauseFocusRequester = playPauseFocusRequester,
                                                        text = vintagePlayerControlsText(),
                                                        buttonSize = primaryControlSize,
                                                        iconSize = 54.dp,
                                                        isPlaying = isPlaying,
                                                        isLoading = isPlaybackLoading && contentActive,
                                                        showLoadingIndicator = !hasVisibleVideoTransitionIndicator,
                                                        onSkipPrevious = {
                                                            Logger.d("Haptics", "Trigger PlayerScreen previous click")
                                                            hapticManager.perform(HapticEvent.PRESS)
                                                            viewModel.previous()
                                                        },
                                                        onTogglePlay = {
                                                            Logger.d(
                                                                "Haptics",
                                                                "Trigger PlayerScreen play pause click isPlaying=$isPlaying",
                                                            )
                                                            viewModel.togglePlayPause()
                                                        },
                                                        onSkipNext = {
                                                            Logger.d("Haptics", "Trigger PlayerScreen next click")
                                                            hapticManager.perform(HapticEvent.PRESS)
                                                            viewModel.next()
                                                        },
                                                        sideButtonColor = textBackgroundColor,
                                                        modifier = Modifier.fillMaxWidth(),
                                                    )
                                                }

                                                PlayerType.MODERN -> {
                                                    PlayerPrimaryControls(
                                                        isPlaying = isPlaying,
                                                        isLoading = isPlaybackLoading && contentActive,
                                                        showLoadingIndicator = !hasVisibleVideoTransitionIndicator,
                                                        playText = stringResource(Res.string.play),
                                                        pauseText = stringResource(Res.string.pause),
                                                        onSkipPrevious = {
                                                            Logger.d("Haptics", "Trigger PlayerScreen previous click")
                                                            hapticManager.perform(HapticEvent.PRESS)
                                                            viewModel.previous()
                                                        },
                                                        onTogglePlay = {
                                                            Logger.d(
                                                                "Haptics",
                                                                "Trigger PlayerScreen play pause click isPlaying=$isPlaying",
                                                            )
                                                            viewModel.togglePlayPause()
                                                        },
                                                        onSkipNext = {
                                                            Logger.d("Haptics", "Trigger PlayerScreen next click")
                                                            hapticManager.perform(HapticEvent.PRESS)
                                                            viewModel.next()
                                                        },
                                                        showLabels = true,
                                                        sideButtonContainerColor = sideButtonContainerColor,
                                                        sideButtonContentColor = sideButtonContentColor,
                                                        useRoundedRect = Settings.shared.useRoundedRectPlayButton,
                                                        modifier = Modifier.fillMaxWidth(),
                                                        buttonHeight = primaryControlSize,
                                                        playPauseFocusRequester = playPauseFocusRequester,
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }

                                playerControlsTransition.AnimatedVisibility(
                                    visible = { (expanded, minimalUi) -> !expanded && !minimalUi },
                                    enter = canvasControlsEnter,
                                    exit = canvasControlsExit,
                                ) {
                                    Spacer(modifier = Modifier.height(QueuePeekHeight + 32.dp))
                                }
                                playerControlsTransition.AnimatedVisibility(
                                    visible = { (expanded, minimalUi) ->
                                        expanded && playerType == PlayerType.CLASSIC && controlsVisible &&
                                            !minimalUi
                                    },
                                    enter = canvasControlsEnter,
                                    exit = canvasControlsExit,
                                ) {
                                    Spacer(modifier = Modifier.height(24.dp))
                                }
                            }
                        }
                    }

                    if (!useLandscapeLayout) {
                        playerControlsTransition.AnimatedVisibility(
                            visible = { (expanded, minimalUi) -> !expanded && !minimalUi },
                            enter =
                                fadeIn(canvasEffectsSpec) +
                                    slideInVertically(canvasSlideSpec) { it },
                            exit =
                                fadeOut(canvasEffectsSpec) +
                                    slideOutVertically(canvasSlideSpec) { it },
                        ) {
                            BottomSheet(
                                state = queueSheetState,
                                backHandlerEnabled = isPlayerBackHandlerEnabled,
                                predictiveBack = true,
                                modifier = Modifier.fillMaxSize(),
                                isDraggableWhenCollapsed = true,
                                collapsedContentAlpha = { progress -> (1f - progress * 5f).coerceIn(0f, 1f) },
                                contentAlpha = { progress -> ((progress - 0.2f) * 4f).coerceIn(0f, 1f) },
                                preserveContentWhenCollapsed = false,
                                background = {
                                    if (queueOpen) {
                                        Box(
                                            modifier =
                                                Modifier
                                                    .fillMaxSize()
                                                    .drawBehind {
                                                        drawRect(
                                                            Color.Black.copy(alpha = 0.5f * queueSheetState.progress.coerceIn(0f, 1f)),
                                                        )
                                                    }.focusProperties { canFocus = false }
                                                    .clickable(
                                                        interactionSource = remember { MutableInteractionSource() },
                                                        indication = null,
                                                        onClick = { queueSheetState.collapseSoft() },
                                                    ),
                                        )
                                    }
                                },
                                collapsedContent = {
                                    if (playerType == PlayerType.CLASSIC) {
                                        VintageClassicBottomBar(
                                            queueText = stringResource(Res.string.queue),
                                            sleepTimerText = stringResource(Res.string.sleep_timer),
                                            sleepTimerRemaining =
                                                sleepTimerState.remainingMs
                                                    ?.takeIf { sleepTimerState.isActive }
                                                    ?.let(::formatSleepTimerRemaining),
                                            isSleepTimerEnabled = sleepTimerState.isActive,
                                            sleepTimerDescription = sleepTimerContentDescription(sleepTimerState),
                                            onSleepTimerClick =
                                                if (!isListenTogetherGuest) {
                                                    {
                                                        hapticManager.perform(HapticEvent.PRESS)
                                                        showSleepTimerDialog = true
                                                    }
                                                } else {
                                                    null
                                                },
                                            lyricsText = stringResource(Res.string.lyrics),
                                            onQueueClick = {
                                                hapticManager.perform(HapticEvent.PRESS)
                                                queueSheetState.expandSoft()
                                            },
                                            onLyricsClick = {
                                                hapticManager.toggle(!uiState.isLyricsVisible)
                                                viewModel.setLyricsVisible(!uiState.isLyricsVisible)
                                            },
                                            contentColor = textBackgroundColor,
                                            modifier = Modifier.padding(bottom = navigationBarBottomPadding),
                                        )
                                    } else {
                                        PlayerBottomControls(
                                            sleepTimerState = sleepTimerState,
                                            onShowSleepTimer = { showSleepTimerDialog = true },
                                            isShuffleOn = isShuffleOn,
                                            onToggleShuffle = viewModel::toggleShuffle,
                                            repeatMode = repeatMode,
                                            onToggleRepeat = viewModel::toggleRepeat,
                                            navigationBarBottomPadding = navigationBarBottomPadding,
                                            onOpenQueue = { queueSheetState.expandSoft() },
                                            onShowPlayerMenu = { showPlayerMenu = true },
                                            isLyricsVisible = uiState.isLyricsVisible,
                                            onIsLyricsVisibleChange = { visible -> viewModel.setLyricsVisible(visible) },
                                            isVideoAvailable = uiState.isVideoAvailable,
                                            isListenTogetherGuest = isListenTogetherGuest,
                                        )
                                    }
                                },
                            ) {
                                Surface(
                                    modifier =
                                        Modifier.fillMaxSize().blockInput(!queueSheetState.acceptsInput),
                                    shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                                ) {
                                    Column(modifier = Modifier.fillMaxSize().sheetBackContent()) {
                                        Spacer(
                                            modifier =
                                                Modifier.layout { _, constraints ->
                                                    val height =
                                                        (
                                                            queueSheetTopPadding.toPx() *
                                                                queueSheetState.progress.coerceIn(
                                                                    0f,
                                                                    1f,
                                                                )
                                                        ).toInt()
                                                    layout(constraints.minWidth, height) {}
                                                },
                                        )
                                        QueueSheetDragHandle(
                                            modifier =
                                                Modifier
                                                    .align(Alignment.CenterHorizontally)
                                                    .graphicsLayer {
                                                        alpha = ((queueSheetState.progress - 0.4f) * 2.5f).coerceIn(0f, 1f)
                                                    },
                                        )
                                        if (playerContentState.isVisible) {
                                            CompositionLocalProvider(
                                                LocalPlayerContentActive provides playerContentState.isActive,
                                                LocalPlayerPositionActive provides playerContentState.isPositionActive,
                                            ) {
                                                QueueScreen(
                                                    session = queueSession,
                                                    viewModel = viewModel,
                                                    onClose = { queueSheetState.collapseSoft() },
                                                    onShowSleepTimer = { showSleepTimerDialog = true },
                                                    onNavigateToAlbum = { albumId ->
                                                        queueSheetState.collapseSoft()
                                                        onNavigateToAlbum(albumId)
                                                    },
                                                    onNavigateToArtist = { artistId ->
                                                        queueSheetState.collapseSoft()
                                                        onNavigateToArtist(artistId)
                                                    },
                                                    onNavigateToListenTogether = {
                                                        queueSheetState.collapseSoft()
                                                        onNavigateToListenTogether()
                                                    },
                                                    modifier = Modifier.fillMaxWidth().weight(1f).then(queueFocusModifier),
                                                    nestedScrollConnection = queueSheetState.preUpPostDownNestedScrollConnection,
                                                    backHandlerEnabled = isPlayerBackHandlerEnabled && queueHandlesBack,
                                                    handleDismissalBack = false,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (isReconnecting) {
                        Text(
                            text = stringResource(Res.string.playback_reconnecting),
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(8.dp),
                        )
                    }

                    val hasVisibleControlsLoadingIndicator = controlsVisible && !canvasMinimalUi && !isListenTogetherGuest
                    if (
                        contentActive &&
                        shouldShowPlayerLoadingScreen(
                            isPlaybackLoading && !isReconnecting,
                            hasVisibleVideoTransitionIndicator || hasVisibleControlsLoadingIndicator,
                        )
                    ) {
                        LoadingScreen()
                    }

                    if (showDeviceConnectSheet) {
                        platform.DeviceConnectSheet(
                            onDismiss = { showDeviceConnectSheet = false },
                            onNavigateToIntegrations = {
                                showDeviceConnectSheet = false
                                onNavigateToIntegrations()
                            },
                        )
                    }

                    if (useLandscapeLayout) {
                        Box(
                            Modifier
                                .align(Alignment.CenterEnd)
                                .width(queuePanelWidth)
                                .fillMaxHeight()
                                .windowInsetsPadding(
                                    WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.End),
                                ),
                        ) {
                            BottomSheet(
                                state = queueSheetState,
                                backHandlerEnabled = isPlayerBackHandlerEnabled,
                                predictiveBack = true,
                                isDraggableWhenCollapsed = false,
                                contentAlpha = { 1f },
                                collapsedContent = {},
                            ) {
                                Surface(
                                    modifier = Modifier.fillMaxSize().blockInput(!queueSheetState.acceptsInput),
                                    shape = MaterialTheme.shapes.extraLarge,
                                ) {
                                    Column(Modifier.fillMaxSize().sheetBackContent()) {
                                        QueueSheetDragHandle(Modifier.align(Alignment.CenterHorizontally))
                                        QueueScreen(
                                            session = queueSession,
                                            viewModel = viewModel,
                                            onClose = { queueSheetState.collapseSoft() },
                                            onShowSleepTimer = { showSleepTimerDialog = true },
                                            onNavigateToAlbum = { albumId ->
                                                queueSheetState.collapseSoft()
                                                onNavigateToAlbum(albumId)
                                            },
                                            onNavigateToArtist = { artistId ->
                                                queueSheetState.collapseSoft()
                                                onNavigateToArtist(artistId)
                                            },
                                            onNavigateToListenTogether = {
                                                queueSheetState.collapseSoft()
                                                onNavigateToListenTogether()
                                            },
                                            modifier = Modifier.fillMaxWidth().weight(1f).then(queueFocusModifier),
                                            nestedScrollConnection = queueSheetState.preUpPostDownNestedScrollConnection,
                                            backHandlerEnabled = isPlayerBackHandlerEnabled,
                                            handleDismissalBack = false,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    PopupVisibility(visible = showPlayerMenu) {
                        platform.PlayerMenu(
                            viewModel = viewModel,
                            onDismiss = { showPlayerMenu = false },
                            onNavigateToAlbum = onNavigateToAlbum,
                            onNavigateToArtist = onNavigateToArtist,
                            onNavigateToListenTogether = onNavigateToListenTogether,
                            onShowSongDetails = {
                                showPlayerMenu = false
                                showSongDetails = true
                            },
                        )
                    }

                    PopupVisibility(visible = showSongDetails) {
                        platform.SongDetailsSheet(viewModel = viewModel, onDismiss = { showSongDetails = false })
                    }

                    PopupVisibility(visible = showSleepTimerDialog) {
                        SleepTimerDialog(
                            state = sleepTimerState,
                            onDismiss = { showSleepTimerDialog = false },
                            onStart = { durationMs, fadeOut, finishCurrentSong ->
                                playerConnection?.startSleepTimer(durationMs, fadeOut, finishCurrentSong)
                            },
                            onStopAfterCurrentSong = { fadeOut -> playerConnection?.stopAfterCurrentSong(fadeOut) },
                            onCancelTimer = { playerConnection?.cancelSleepTimer() },
                        )
                    }

                    PopupVisibility(visible = showLyricsMenu) {
                        platform.LyricsMenu(
                            viewModel = viewModel,
                            onDismiss = { showLyricsMenu = false },
                            onShowLyricsOffset = {
                                showLyricsMenu = false
                                showLyricsOffsetEditor = true
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FullscreenBackgroundBounds(
    bounds: State<FullscreenTransitionBounds?>,
    content: @Composable (FullscreenTransitionBounds?) -> Unit,
) {
    content(bounds.value)
}

private val MinPortraitPlayerHeight = 420.dp
