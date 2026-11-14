/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.shared.ui.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalToggleButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.metrolist.shared.api.lyrics.LyricsUtils
import com.metrolist.shared.cast.CastState
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.action_like
import com.metrolist.shared.generated.resources.album_art
import com.metrolist.shared.generated.resources.cast
import com.metrolist.shared.generated.resources.close
import com.metrolist.shared.generated.resources.collapse
import com.metrolist.shared.generated.resources.expand
import com.metrolist.shared.generated.resources.local
import com.metrolist.shared.generated.resources.lyrics
import com.metrolist.shared.generated.resources.lyrics_auto_scroll_btn
import com.metrolist.shared.generated.resources.lyrics_not_found
import com.metrolist.shared.generated.resources.lyrics_provided_by
import com.metrolist.shared.generated.resources.more_lyrics_options
import com.metrolist.shared.generated.resources.more_options
import com.metrolist.shared.generated.resources.next
import com.metrolist.shared.generated.resources.now_playing
import com.metrolist.shared.generated.resources.pause
import com.metrolist.shared.generated.resources.play
import com.metrolist.shared.generated.resources.previous
import com.metrolist.shared.generated.resources.queue
import com.metrolist.shared.generated.resources.repeat
import com.metrolist.shared.generated.resources.share
import com.metrolist.shared.generated.resources.sleep_timer
import com.metrolist.shared.generated.resources.small_album_art
import com.metrolist.shared.generated.resources.song
import com.metrolist.shared.generated.resources.unknown
import com.metrolist.shared.generated.resources.unlike
import com.metrolist.shared.generated.resources.video
import com.metrolist.shared.model.AgentInfo
import com.metrolist.shared.model.LyricsEntry
import com.metrolist.shared.model.RepeatMode
import com.metrolist.shared.model.lyricsCuratorUrl
import com.metrolist.shared.model.lyricsProviderUrl
import com.metrolist.shared.playback.LocalPlayerSession
import com.metrolist.shared.playback.PlaybackSeekSource
import com.metrolist.shared.playback.SleepTimerState
import com.metrolist.shared.repository.isLocalLyricsProvider
import com.metrolist.shared.settings.PlayerBackgroundStyle
import com.metrolist.shared.settings.PlayerType
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.ui.LocalIsTv
import com.metrolist.shared.ui.components.CrossfadeArtwork
import com.metrolist.shared.ui.components.PlayerConnectedButtonGroup
import com.metrolist.shared.ui.components.PlayerLikeShareButtons
import com.metrolist.shared.ui.components.PlayerPrimaryControls
import com.metrolist.shared.ui.components.VintageAudioVideoModeSwitcher
import com.metrolist.shared.ui.components.VintageClassicActionButtons
import com.metrolist.shared.ui.components.VintageClassicBottomBar
import com.metrolist.shared.ui.components.VintageClassicControls
import com.metrolist.shared.ui.components.VintageClassicControlsText
import com.metrolist.shared.ui.components.VintagePlayerControls
import com.metrolist.shared.ui.components.VintagePlayerControlsText
import com.metrolist.shared.ui.components.formatSleepTimerRemaining
import com.metrolist.shared.ui.components.lyrics.ActiveLyricsLineContent
import com.metrolist.shared.ui.components.lyrics.LyricsOffsetEditor
import com.metrolist.shared.ui.components.lyrics.LyricsView
import com.metrolist.shared.ui.components.lyrics.rememberCaptionLyricsPositionState
import com.metrolist.shared.ui.components.lyrics.resolveLyricsTextColor
import com.metrolist.shared.ui.components.playerControlBlend
import com.metrolist.shared.ui.components.rememberTrackMarquee
import com.metrolist.shared.ui.components.vintageOnButtonColor
import com.metrolist.shared.ui.haptics.HapticEvent
import com.metrolist.shared.ui.haptics.rememberMetrolistHaptics
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.navigation.navigationPopupSource
import com.metrolist.shared.ui.theme.ComponentShapes
import com.metrolist.shared.ui.tv.tvFocusHighlight
import com.metrolist.shared.ui.utils.verticalScrollWithDrag
import com.metrolist.shared.utils.Logger
import com.metrolist.shared.viewmodels.PlayerViewModel
import org.jetbrains.compose.resources.stringResource
import kotlin.math.max

// Header, metadata, actions, playback controls, fixed bottom row and outer padding.
private val TabletPlayerChromeHeight = 574.dp
private val TabletPlayerCompactChromeHeight = 460.dp

@Composable
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
internal fun TabletPlayerContent(
    viewModel: PlayerViewModel,
    artworkOverlay: @Composable (Modifier) -> Unit,
    onToggleFullscreen: (() -> Unit)?,
    showLyricsOffsetEditor: Boolean,
    onLyricsOffsetChange: (Int) -> Unit,
    onDoneLyricsOffset: () -> Unit,
    currentSong: PlayerViewModel.SongUiModel?,
    crossfadeNextArtworkUrl: String?,
    artworkCrossfadeProgress: () -> Float,
    isPlaying: Boolean,
    duration: Long,
    isLiked: Boolean,
    uiState: PlayerViewModel.PlayerUiState,
    textBackgroundColor: Color,
    sideButtonContainerColor: Color,
    sideButtonContentColor: Color,
    onNavigateToArtist: (String) -> Unit,
    onNavigateToAlbum: (String) -> Unit,
    sleepTimerState: SleepTimerState,
    onShowSleepTimer: () -> Unit,
    onShowLyricsMenu: () -> Unit,
    onOpenQueue: () -> Unit,
    onShowPlayerMenu: () -> Unit,
    onIsLyricsVisibleChange: (Boolean) -> Unit,
    onShareSong: () -> Unit,
    onAltShareSong: (() -> Unit)? = null,
    onToggleLike: () -> Unit,
    isListenTogetherGuest: Boolean,
    artworkSwipeEnabled: Boolean,
    artworkSwipeSensitivity: Float,
    onArtworkSeek: (Long) -> Unit,
    seekFeedback: SeekFeedback?,
    seekFeedbackVisible: Boolean,
    playerType: PlayerType = Settings.shared.playerType,
    playPauseFocusRequester: FocusRequester? = null,
    showVideoSurface: Boolean = false,
    onOpenCast: () -> Unit,
    onClose: () -> Unit,
    castState: CastState,
    showVideoButton: Boolean,
    isVideoModeActive: Boolean,
    isVideoModeResolving: Boolean,
    usesFullscreenThumbnail: Boolean = false,
    usesCanvasThumbnail: Boolean = false,
) {
    val repeatMode = uiState.repeatMode
    val isShuffleOn = uiState.isShuffleOn
    val isTv = LocalIsTv.current
    val hapticManager = rememberMetrolistHaptics()
    val platform = LocalPlayerPlatform.current
    val playerConnection = LocalPlayerSession.current
    val isPlaybackLoading by viewModel.isPlaybackLoading.collectWhilePlayerActive()
    val artworkSeekStepMs = Settings.shared.seekDurationSeconds * 1000L

    BoxWithConstraints(
        modifier =
            Modifier
                .fillMaxSize()
                .landscapePlayerInsets(),
    ) {
        val compactHeight = maxHeight < 720.dp
        val compactWidth = maxWidth < 600.dp
        val primaryControlSize = if (compactHeight) 64.dp else 104.dp
        val horizontalPadding =
            if (compactWidth) {
                8.dp
            } else if (compactHeight) {
                16.dp
            } else {
                LandscapePlayerHorizontalPadding
            }
        val verticalPadding = if (compactHeight) 8.dp else LandscapePlayerVerticalPadding
        val sectionGap = if (compactHeight) 12.dp else 28.dp
        val artworkSize =
            minOf(
                LandscapePlayerArtworkSize,
                (maxHeight - if (compactHeight) TabletPlayerCompactChromeHeight else TabletPlayerChromeHeight)
                    .coerceAtLeast(160.dp),
                (maxWidth - horizontalPadding * 2).coerceAtLeast(0.dp),
            )
        val topButtonSize = 48.dp
        val lyricsFraction by animateFloatAsState(
            targetValue = if (uiState.isLyricsVisible && !compactWidth) 1f else 0f,
            animationSpec =
                tween(
                    durationMillis = 300,
                    easing = FastOutSlowInEasing,
                ),
            label = "tabletLyricsDrawer",
        )

        val drawerWidth = (maxWidth - horizontalPadding * 2f) / 2f
        val drawerWidthPx = with(LocalDensity.current) { drawerWidth.toPx() }
        val drawerDirection = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1f else 1f
        val contentHorizontalPadding = if (compactWidth) 0.dp else 32.dp
        val contentMaxWidth = if (uiState.isLyricsVisible && !compactWidth) drawerWidth else 760.dp

        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = horizontalPadding, vertical = verticalPadding)
                    .focusGroup(),
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            translationX = -lyricsFraction * drawerWidthPx * 0.5f * drawerDirection
                        },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier =
                        Modifier
                            .weight(1f)
                            .verticalScrollWithDrag(rememberScrollState()),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.widthIn(max = contentMaxWidth).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Row(
                                modifier =
                                    Modifier
                                        .then(if (compactWidth) Modifier.fillMaxWidth() else Modifier)
                                        .padding(bottom = sectionGap)
                                        .zIndex(1f)
                                        .focusProperties { if (isTv && playPauseFocusRequester != null) down = playPauseFocusRequester },
                                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                LandscapePlayerCollapseButton(
                                    textColor = textBackgroundColor,
                                    buttonSize = topButtonSize,
                                    onClick = {
                                        hapticManager.perform(HapticEvent.PRESS)
                                        onClose()
                                    },
                                )
                                // Beside the queue the row spans the player, so close stays at the start and cast
                                // at the end like the portrait player instead of pairing up in the middle.
                                if (compactWidth) Spacer(Modifier.weight(1f))
                                if (showVideoButton) {
                                    if (playerType == PlayerType.CLASSIC) {
                                        VintageAudioVideoModeSwitcher(
                                            songDescription = stringResource(Res.string.song),
                                            videoDescription = stringResource(Res.string.video),
                                            isVideoActive = isVideoModeActive,
                                            isResolving = isVideoModeResolving,
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
                                    } else {
                                        LandscapePlayerModeSwitcher(
                                            isVideoActive = isVideoModeActive,
                                            isResolving = isVideoModeResolving,
                                            textColor = textBackgroundColor,
                                            buttonHeight = topButtonSize,
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
                                }
                                if (compactWidth) Spacer(Modifier.weight(1f))
                                if (!isTv && !isListenTogetherGuest && platform.supportsCasting &&
                                    Settings.shared.isPlayerCastButtonVisible
                                ) {
                                    LandscapePlayerCastButton(
                                        isCasting = castState.isConnected,
                                        textColor = textBackgroundColor,
                                        buttonSize = topButtonSize,
                                        onClick = {
                                            hapticManager.perform(HapticEvent.PRESS)
                                            onOpenCast()
                                        },
                                    )
                                }
                            }

                            if (compactWidth && uiState.isLyricsVisible) {
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
                                    modifier = Modifier.fillMaxWidth().height(artworkSize),
                                )
                                if (showLyricsOffsetEditor) {
                                    LyricsOffsetEditor(
                                        offsetMs = uiState.lyricsOffsetMs,
                                        onOffsetChange = onLyricsOffsetChange,
                                        onDone = onDoneLyricsOffset,
                                        contentColor = textBackgroundColor,
                                    )
                                }
                            } else if (currentSong?.displayThumbnail != null || showVideoSurface) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier =
                                        Modifier
                                            .requiredSize(artworkSize)
                                            .artworkSwipe(
                                                enabled = artworkSwipeEnabled,
                                                sensitivity = artworkSwipeSensitivity,
                                                onPrevious = viewModel::previous,
                                                onNext = viewModel::next,
                                            ).artworkSeekGestures(
                                                seekEnabled = !isListenTogetherGuest,
                                                stepMs = artworkSeekStepMs,
                                                onTap = null,
                                                tapLabel = null,
                                                progressive = Settings.shared.seekExtraSeconds,
                                                onSeek = onArtworkSeek,
                                            ),
                                ) {
                                    if (showVideoSurface) {
                                        platform.MusicVideoSurface(
                                            modifier = Modifier.requiredSize(artworkSize),
                                            cornerRadius = PlayerArtworkCornerRadius,
                                            fillContainer = true,
                                            showControls = true,
                                            useSurfaceView = false,
                                            onFullscreenClick = onToggleFullscreen,
                                            onPiPClick = { platform.enterPictureInPicture(isPlaying = isPlaying, hasVideo = true) },
                                        )
                                    } else {
                                        CrossfadeArtwork(
                                            animate = LocalPlayerContentActive.current,
                                            currentUrl = requireNotNull(currentSong?.displayThumbnail),
                                            nextUrl = crossfadeNextArtworkUrl,
                                            progress = artworkCrossfadeProgress,
                                            contentDescription = stringResource(Res.string.album_art),
                                            modifier =
                                                Modifier
                                                    .requiredSize(artworkSize)
                                                    .clip(RoundedCornerShape(PlayerArtworkCornerRadius)),
                                            contentScale = ContentScale.Crop,
                                        )
                                        artworkOverlay(
                                            Modifier.requiredSize(artworkSize).clip(RoundedCornerShape(PlayerArtworkCornerRadius)),
                                        )
                                    }
                                    SeekFeedbackOverlay(
                                        feedback = seekFeedback,
                                        visible = seekFeedbackVisible,
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(24.dp))

                            Text(
                                text = currentSong?.title ?: stringResource(Res.string.unknown),
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold,
                                color = textBackgroundColor,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .clipToBounds()
                                        .clickable(enabled = currentSong?.albumId != null) {
                                            currentSong?.albumId?.let(onNavigateToAlbum)
                                        }.then(
                                            currentSong?.let { song ->
                                                rememberTrackMarquee(
                                                    trackId = song.id,
                                                    enabled = LocalPlayerContentActive.current,
                                                    initialDelayMillis = 3000L,
                                                )
                                            } ?: Modifier,
                                        ),
                            )

                            Spacer(modifier = Modifier.height(6.dp))

                            PlayerArtistLine(
                                artists = currentSong?.artists,
                                onNavigateToArtist = onNavigateToArtist,
                                iterations = if (LocalPlayerContentActive.current) 1 else 0,
                                style = MaterialTheme.typography.titleMedium,
                                color = textBackgroundColor.copy(alpha = 0.72f),
                                centered = true,
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .padding(vertical = 4.dp),
                            )

                            Spacer(modifier = Modifier.height(12.dp))
                            PlayerLikeShareButtons(
                                isLiked = isLiked,
                                onLikeClick = onToggleLike,
                                onShareClick = onShareSong,
                                onAltShareClick = onAltShareSong,
                                groupHeight = if (compactWidth) 40.dp else 48.dp,
                                buttonWidth = if (compactWidth) 48.dp else 56.dp,
                                iconSize = if (compactWidth) 20.dp else 24.dp,
                            )
                            Spacer(modifier = Modifier.height(sectionGap))

                            Column(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = contentHorizontalPadding),
                            ) {
                                PlayerProgressSlider(
                                    viewModel = viewModel,
                                    duration = duration,
                                    isPlaying = isPlaying,
                                    textBackgroundColor = textBackgroundColor,
                                    canSeek = !isListenTogetherGuest,
                                    isCasting = castState.isConnected,
                                    songId = currentSong?.id,
                                    progressColor = textBackgroundColor.takeIf { playerType == PlayerType.CLASSIC },
                                    timerTextStyle = MaterialTheme.typography.labelLarge,
                                    isLive = uiState.isLive,
                                )

                                Spacer(modifier = Modifier.height(if (compactHeight) 4.dp else 8.dp))

                                if (!isListenTogetherGuest) {
                                    if (playerType == PlayerType.VINTAGE || playerType == PlayerType.CLASSIC) {
                                        VintagePlayerControls(
                                            playPauseFocusRequester = playPauseFocusRequester,
                                            text = vintagePlayerControlsText(),
                                            buttonSize = if (compactHeight || compactWidth) 56.dp else 88.dp,
                                            iconSize = if (compactHeight || compactWidth) 42.dp else 68.dp,
                                            playButtonSize = primaryControlSize,
                                            playIconSize = if (compactHeight) 48.dp else 84.dp,
                                            spacing = if (compactWidth) 8.dp else 40.dp,
                                            isPlaying = isPlaying,
                                            isLoading = isPlaybackLoading && LocalPlayerContentActive.current,
                                            showLoadingIndicator = !(showVideoButton && isVideoModeResolving),
                                            onSkipPrevious = {
                                                Logger.d("Haptics", "Trigger PlayerScreen previous click")
                                                hapticManager.perform(HapticEvent.PRESS)
                                                viewModel.previous()
                                            },
                                            onTogglePlay = {
                                                Logger.d("Haptics", "Trigger PlayerScreen play pause click isPlaying=$isPlaying")
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
                                    } else {
                                        PlayerPrimaryControls(
                                            isPlaying = isPlaying,
                                            isLoading = isPlaybackLoading && LocalPlayerContentActive.current,
                                            showLoadingIndicator = !(showVideoButton && isVideoModeResolving),
                                            playText = stringResource(Res.string.play),
                                            pauseText = stringResource(Res.string.pause),
                                            onSkipPrevious = {
                                                Logger.d("Haptics", "Trigger PlayerScreen previous click")
                                                hapticManager.perform(HapticEvent.PRESS)
                                                viewModel.previous()
                                            },
                                            onTogglePlay = {
                                                Logger.d("Haptics", "Trigger PlayerScreen play pause click isPlaying=$isPlaying")
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
                }
                Spacer(modifier = Modifier.height(sectionGap))

                Column(
                    modifier = Modifier.widthIn(max = minOf(360.dp, contentMaxWidth)).fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    PlayerBottomControls(
                        sleepTimerState = sleepTimerState,
                        onShowSleepTimer = onShowSleepTimer,
                        isShuffleOn = isShuffleOn,
                        onToggleShuffle = viewModel::toggleShuffle.takeUnless { playerType == PlayerType.CLASSIC },
                        repeatMode = repeatMode,
                        onToggleRepeat = viewModel::toggleRepeat,
                        showQueueHandle = false,
                        navigationBarBottomPadding = 0.dp,
                        onOpenQueue = onOpenQueue,
                        onShowPlayerMenu = onShowPlayerMenu,
                        isLyricsVisible = uiState.isLyricsVisible,
                        onIsLyricsVisibleChange = onIsLyricsVisibleChange,
                        isVideoAvailable = uiState.isVideoAvailable,
                        modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth(),
                        isListenTogetherGuest = isListenTogetherGuest,
                        controlHeight = if (compactHeight) 48.dp else 56.dp,
                        touchTarget = if (compactWidth) 40.dp else 48.dp,
                    )
                }
            }

            Box(
                modifier =
                    Modifier
                        .align(Alignment.CenterEnd)
                        .width(drawerWidth)
                        .fillMaxHeight()
                        .graphicsLayer {
                            translationX = (1f - lyricsFraction) * drawerWidthPx * drawerDirection
                            alpha = lyricsFraction
                        },
            ) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(top = 72.dp, bottom = 24.dp, end = 32.dp, start = 8.dp),
                ) {
                    if ((!compactWidth && uiState.isLyricsVisible) || lyricsFraction > 0f) {
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
                            modifier = Modifier.weight(1f),
                        )
                    }
                    AnimatedVisibility(visible = showLyricsOffsetEditor) {
                        LyricsOffsetEditor(
                            offsetMs = uiState.lyricsOffsetMs,
                            onOffsetChange = onLyricsOffsetChange,
                            onDone = onDoneLyricsOffset,
                            contentColor = textBackgroundColor,
                        )
                    }
                }
            }
        }
    }
}

/** Mandatory gesture insets persist with hidden system bars and would shift centered content upward. */
@Composable
private fun Modifier.landscapePlayerInsets(): Modifier = windowInsetsPadding(WindowInsets.safeDrawing)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LandscapePlayerModeSwitcher(
    isVideoActive: Boolean,
    isResolving: Boolean,
    textColor: Color,
    buttonHeight: Dp,
    onSongClick: () -> Unit,
    onVideoClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val songText =
        stringResource(Res.string.song)
    val videoText =
        stringResource(Res.string.video)
    val colors =
        ToggleButtonDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = textColor,
            checkedContainerColor = MaterialTheme.colorScheme.primary,
            checkedContentColor = MaterialTheme.colorScheme.onPrimary,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            disabledContentColor = textColor,
        )

    Row(
        modifier = modifier.height(buttonHeight),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        val songSelected = !isVideoActive
        ToggleButton(
            checked = songSelected,
            enabled = !isResolving,
            onCheckedChange = { onSongClick() },
            shapes = staticConnectedLeadingButtonShapes(),
            contentPadding = PaddingValues(0.dp),
            colors = colors,
            modifier =
                Modifier.size(width = 46.dp, height = buttonHeight).tvFocusHighlight(
                    staticConnectedLeadingButtonShapes().shape,
                    color = if (songSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
                ),
        ) {
            if (isResolving && isVideoActive && LocalPlayerContentActive.current) {
                LoadingIndicator(
                    modifier = Modifier.size(18.dp),
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                Icon(
                    imageVector = if (songSelected) MetrolistIcons.Filled.Headphones else MetrolistIcons.Outlined.Headphones,
                    contentDescription = songText,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        ToggleButton(
            checked = isVideoActive,
            enabled = !isResolving,
            onCheckedChange = { onVideoClick() },
            shapes = staticConnectedTrailingButtonShapes(),
            contentPadding = PaddingValues(0.dp),
            colors = colors,
            modifier =
                Modifier.size(width = 46.dp, height = buttonHeight).tvFocusHighlight(
                    staticConnectedTrailingButtonShapes().shape,
                    color = if (isVideoActive) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
                ),
        ) {
            if (isResolving && !isVideoActive && LocalPlayerContentActive.current) {
                LoadingIndicator(
                    modifier = Modifier.size(18.dp),
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                Icon(
                    imageVector =
                        if (isVideoActive) MetrolistIcons.Filled.SmartDisplay else MetrolistIcons.Outlined.SmartDisplay,
                    contentDescription = videoText,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun LandscapePlayerCollapseButton(
    textColor: Color,
    buttonSize: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FilledIconButton(
        onClick = onClick,
        shapes = IconButtonDefaults.shapes(shape = CircleShape),
        colors =
            IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                contentColor = textColor.copy(alpha = 0.7f),
            ),
        modifier = modifier.size(buttonSize).tvFocusHighlight(CircleShape).playerControlBlend(),
    ) {
        Icon(
            imageVector = MetrolistIcons.Default.Close,
            contentDescription = stringResource(Res.string.close),
            modifier = Modifier.size(24.dp),
        )
    }
}

@Composable
private fun LandscapePlayerCastButton(
    isCasting: Boolean,
    textColor: Color,
    buttonSize: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FilledIconButton(
        onClick = onClick,
        shapes = IconButtonDefaults.shapes(shape = CircleShape),
        colors =
            IconButtonDefaults.filledIconButtonColors(
                containerColor =
                    if (isCasting) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                contentColor =
                    if (isCasting) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        textColor
                    },
            ),
        modifier =
            modifier
                .size(buttonSize)
                .tvFocusHighlight(
                    CircleShape,
                    color = if (isCasting) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
                ).playerControlBlend(),
    ) {
        Icon(
            imageVector = if (isCasting) MetrolistIcons.Filled.Cast else MetrolistIcons.Outlined.Cast,
            contentDescription = stringResource(Res.string.cast),
            modifier = Modifier.size(20.dp),
        )
    }
}

private val LandscapeRightPaneStartGap = 24.dp
private val LandscapeRightPaneEndGap = 32.dp

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun LandscapePlayerContent(
    viewModel: PlayerViewModel,
    artworkOverlay: @Composable (Modifier) -> Unit,
    onToggleFullscreen: (() -> Unit)?,
    showLyricsOffsetEditor: Boolean,
    onLyricsOffsetChange: (Int) -> Unit,
    onDoneLyricsOffset: () -> Unit,
    currentSong: PlayerViewModel.SongUiModel?,
    crossfadeNextArtworkUrl: String?,
    artworkCrossfadeProgress: () -> Float,
    isPlaying: Boolean,
    duration: Long,
    isLiked: Boolean,
    uiState: PlayerViewModel.PlayerUiState,
    textBackgroundColor: Color,
    sideButtonContainerColor: Color,
    sideButtonContentColor: Color,
    onNavigateToArtist: (String) -> Unit,
    onNavigateToAlbum: (String) -> Unit,
    sleepTimerState: SleepTimerState,
    onShowSleepTimer: () -> Unit,
    onShowLyricsMenu: () -> Unit,
    onOpenQueue: () -> Unit,
    onShowPlayerMenu: () -> Unit,
    onIsLyricsVisibleChange: (Boolean) -> Unit,
    onShareSong: () -> Unit,
    onAltShareSong: (() -> Unit)? = null,
    onToggleLike: () -> Unit,
    isListenTogetherGuest: Boolean,
    artworkSwipeEnabled: Boolean,
    artworkSwipeSensitivity: Float,
    onArtworkSeek: (Long) -> Unit,
    seekFeedback: SeekFeedback?,
    seekFeedbackVisible: Boolean,
    playerType: PlayerType = Settings.shared.playerType,
    playPauseFocusRequester: FocusRequester? = null,
    showVideoSurface: Boolean = false,
    onOpenCast: () -> Unit = {},
    castState: CastState = CastState(),
    showVideoButton: Boolean = false,
    isVideoModeActive: Boolean = false,
    isVideoModeResolving: Boolean = false,
    usesFullscreenThumbnail: Boolean = false,
    usesCanvasThumbnail: Boolean = false,
) {
    val repeatMode = uiState.repeatMode
    val isShuffleOn = uiState.isShuffleOn
    val isTv = LocalIsTv.current
    val artworkBehindControls =
        Settings.shared.playerBackgroundStyle != PlayerBackgroundStyle.SOLID && currentSong?.displayThumbnail != null
    val controlsColor =
        if (isTv) {
            val color by animateColorAsState(
                if (artworkBehindControls) textBackgroundColor else MaterialTheme.colorScheme.onSurface,
                MaterialTheme.motionScheme.defaultEffectsSpec(),
            )
            color
        } else {
            textBackgroundColor
        }
    val controlsSurface by animateColorAsState(
        if (artworkBehindControls) Color.Black.copy(alpha = 0.6f) else MaterialTheme.colorScheme.surfaceContainer,
        MaterialTheme.motionScheme.defaultEffectsSpec(),
    )
    val hapticManager = rememberMetrolistHaptics()
    val platform = LocalPlayerPlatform.current
    val controlsVisible = !uiState.isLyricsExpanded || !Settings.shared.lyricsHideStatusBarInFullscreen
    val playerConnection = LocalPlayerSession.current
    val artworkSeekStepMs = Settings.shared.seekDurationSeconds * 1000L
    val isPlaybackLoading by viewModel.isPlaybackLoading.collectWhilePlayerActive()
    val volume by playerConnection?.volume?.collectWhilePlayerActive() ?: remember { mutableFloatStateOf(1f) }

    BoxWithConstraints(
        modifier =
            Modifier
                .fillMaxSize()
                .landscapePlayerInsets(),
    ) {
        val compactHeight = maxHeight < 520.dp
        val primaryControlSize = if (compactHeight) 64.dp else 104.dp
        val horizontalPadding = if (compactHeight) 16.dp else LandscapePlayerHorizontalPadding
        val verticalPadding = if (compactHeight) 8.dp else LandscapePlayerVerticalPadding
        val topButtonSize = if (compactHeight) 36.dp else 40.dp
        // Only the audio/video switcher and the classic header sit above the controls.
        val controlsTopPadding =
            if (showVideoButton || playerType == PlayerType.CLASSIC) {
                topButtonSize + if (compactHeight) 4.dp else 16.dp
            } else {
                0.dp
            }
        val rightPaneWidth = maxWidth / 2 - LandscapeRightPaneStartGap - LandscapeRightPaneEndGap
        val isClassic = playerType == PlayerType.CLASSIC
        val isVintage = playerType == PlayerType.VINTAGE

        val overflowButton: @Composable () -> Unit = {
            FilledIconButton(
                onClick = {
                    hapticManager.perform(HapticEvent.PRESS)
                    onShowPlayerMenu()
                },
                shapes = IconButtonDefaults.shapes(shape = CircleShape),
                colors =
                    IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ),
                modifier =
                    Modifier
                        .size(if (compactHeight) 36.dp else 40.dp)
                        .tvFocusHighlight(CircleShape, bringIntoView = false)
                        .navigationPopupSource()
                        .playerControlBlend(),
            ) {
                Icon(
                    MetrolistIcons.Default.MoreVert,
                    contentDescription = stringResource(Res.string.more_options),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        val bottomControls: @Composable () -> Unit = {
            AnimatedVisibility(
                visible = controlsVisible,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (isClassic) {
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
                                    onShowSleepTimer()
                                }
                            } else {
                                null
                            },
                        lyricsText = stringResource(Res.string.lyrics),
                        onQueueClick = {
                            hapticManager.perform(HapticEvent.PRESS)
                            onOpenQueue()
                        },
                        onLyricsClick = {
                            hapticManager.perform(HapticEvent.PRESS)
                            onIsLyricsVisibleChange(!uiState.isLyricsVisible)
                        },
                        contentColor = controlsColor,
                        horizontalPadding = 0.dp,
                        verticalPadding = if (compactHeight) 0.dp else 12.dp,
                    )
                } else {
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = if (compactHeight) 6.dp else 12.dp)
                                .height(if (compactHeight) 44.dp else 48.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PlayerConnectedButtonGroup(
                            onQueueClick = onOpenQueue,
                            onLyricsClick = {
                                hapticManager.perform(HapticEvent.PRESS)
                                onIsLyricsVisibleChange(!uiState.isLyricsVisible)
                            },
                            isLyricsActive = uiState.isLyricsVisible,
                            isQueueActive = false,
                            modifier = Modifier.weight(1f, fill = false),
                            compact = compactHeight,
                            volume = volume,
                            onVolumeChange = { newVolume -> playerConnection?.setVolume(newVolume) },
                            onShuffleClick =
                                if (!isListenTogetherGuest) {
                                    {
                                        hapticManager.toggle(!isShuffleOn)
                                        viewModel.toggleShuffle()
                                    }
                                } else {
                                    null
                                },
                            isShuffled = isShuffleOn,
                            onRepeatClick =
                                {
                                    hapticManager.perform(HapticEvent.PRESS)
                                    viewModel.toggleRepeat()
                                }.takeUnless { isListenTogetherGuest },
                            repeatMode =
                                when (repeatMode) {
                                    1 -> RepeatMode.ONE
                                    2 -> RepeatMode.ALL
                                    else -> RepeatMode.OFF
                                },
                            onSleepTimerClick =
                                if (!isListenTogetherGuest) {
                                    {
                                        hapticManager.perform(HapticEvent.PRESS)
                                        onShowSleepTimer()
                                    }
                                } else {
                                    null
                                },
                            isSleepTimerActive = sleepTimerState.isActive,
                            sleepTimerDisplay =
                                sleepTimerState.remainingMs
                                    ?.takeIf { sleepTimerState.isActive }
                                    ?.let(::formatSleepTimerRemaining),
                            sleepTimerContentDescription = sleepTimerContentDescription(sleepTimerState),
                            touchTarget = if (compactHeight) 36.dp else 40.dp,
                        )

                        overflowButton()
                    }
                }
            }
        }

        if (isTv) {
            Box(
                Modifier.fillMaxSize().drawBehind {
                    val start = if (layoutDirection == LayoutDirection.Ltr) 0f else size.width
                    val controlsStart = (size.width / 2 + LandscapeRightPaneStartGap.toPx()) / size.width
                    drawRect(
                        // A wide eased ramp blends the artwork into the controls instead of a hard edge.
                        Brush.horizontalGradient(
                            0f to Color.Transparent,
                            0.25f to Color.Transparent,
                            0.36f to controlsSurface.copy(alpha = 0.3f),
                            0.46f to controlsSurface.copy(alpha = 0.72f),
                            controlsStart to controlsSurface,
                            1f to controlsSurface,
                            startX = start,
                            endX = size.width - start,
                        ),
                    )
                },
            )
        }

        if (!showVideoButton && isClassic) {
            Box(
                modifier =
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = verticalPadding, end = LandscapeRightPaneEndGap)
                        .width(rightPaneWidth)
                        .heightIn(min = topButtonSize)
                        .padding(horizontal = topButtonSize + 12.dp)
                        .zIndex(1f)
                        .focusProperties { if (isTv && playPauseFocusRequester != null) down = playPauseFocusRequester },
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(Res.string.now_playing),
                        style = MaterialTheme.typography.titleMedium,
                        color = controlsColor,
                        maxLines = 1,
                    )
                    uiState.queueTitle?.takeIf { it.isNotBlank() }?.let { playingFrom ->
                        Text(
                            text = playingFrom,
                            style = MaterialTheme.typography.titleMedium,
                            color = controlsColor.copy(alpha = 0.8f),
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
        }

        if (showVideoButton) {
            Box(
                modifier =
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = verticalPadding, end = LandscapeRightPaneEndGap)
                        .width(rightPaneWidth)
                        .zIndex(1f)
                        .focusProperties { if (isTv && playPauseFocusRequester != null) down = playPauseFocusRequester },
                contentAlignment = Alignment.TopCenter,
            ) {
                if (isClassic) {
                    VintageAudioVideoModeSwitcher(
                        songDescription = stringResource(Res.string.song),
                        videoDescription = stringResource(Res.string.video),
                        isVideoActive = isVideoModeActive,
                        isResolving = isVideoModeResolving,
                        contentColor = controlsColor,
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
                } else {
                    LandscapePlayerModeSwitcher(
                        isVideoActive = isVideoModeActive,
                        isResolving = isVideoModeResolving,
                        textColor = controlsColor,
                        buttonHeight = topButtonSize,
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
            }
        }

        if (!isTv && !isListenTogetherGuest && platform.supportsCasting && Settings.shared.isPlayerCastButtonVisible) {
            LandscapePlayerCastButton(
                isCasting = castState.isConnected,
                textColor = controlsColor,
                buttonSize = topButtonSize,
                onClick = {
                    hapticManager.perform(HapticEvent.PRESS)
                    onOpenCast()
                },
                modifier =
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = verticalPadding, end = LandscapeRightPaneEndGap)
                        .zIndex(1f)
                        .focusProperties { if (isTv && playPauseFocusRequester != null) down = playPauseFocusRequester },
            )
        }

        Row(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(vertical = verticalPadding)
                    .focusGroup(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BoxWithConstraints(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(horizontal = horizontalPadding),
                contentAlignment = Alignment.Center,
            ) {
                val tvArtworkSize = minOf(LandscapePlayerArtworkSize, maxHeight, maxWidth)
                Column(modifier = Modifier.fillMaxSize()) {
                    BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                        val artworkSize = if (isTv) tvArtworkSize else minOf(maxHeight, maxWidth)
                        AnimatedContent(
                            targetState = uiState.isLyricsVisible,
                            transitionSpec = { fadeIn() togetherWith fadeOut() },
                            label = "landscapeLyricsToggle",
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
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
                            } else if (showVideoSurface || currentSong?.displayThumbnail != null) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier =
                                        if (usesFullscreenThumbnail || usesCanvasThumbnail) {
                                            Modifier
                                                .fillMaxSize()
                                                .artworkSwipe(
                                                    enabled = artworkSwipeEnabled,
                                                    sensitivity = artworkSwipeSensitivity,
                                                    onPrevious = viewModel::previous,
                                                    onNext = viewModel::next,
                                                ).artworkSeekGestures(
                                                    seekEnabled = !isListenTogetherGuest,
                                                    stepMs = artworkSeekStepMs,
                                                    onTap = null,
                                                    tapLabel = null,
                                                    progressive = Settings.shared.seekExtraSeconds,
                                                    onSeek = onArtworkSeek,
                                                )
                                        } else {
                                            Modifier
                                                .requiredSize(artworkSize)
                                                .artworkSwipe(
                                                    enabled = artworkSwipeEnabled,
                                                    sensitivity = artworkSwipeSensitivity,
                                                    onPrevious = viewModel::previous,
                                                    onNext = viewModel::next,
                                                ).artworkSeekGestures(
                                                    seekEnabled = !isListenTogetherGuest,
                                                    stepMs = artworkSeekStepMs,
                                                    onTap = null,
                                                    tapLabel = null,
                                                    progressive = Settings.shared.seekExtraSeconds,
                                                    onSeek = onArtworkSeek,
                                                )
                                        },
                                ) {
                                    if (showVideoSurface) {
                                        platform.MusicVideoSurface(
                                            modifier = Modifier.requiredSize(artworkSize),
                                            cornerRadius = PlayerArtworkCornerRadius,
                                            fillContainer = true,
                                            showControls = true,
                                            useSurfaceView = false,
                                            onFullscreenClick = onToggleFullscreen,
                                            onPiPClick = { platform.enterPictureInPicture(isPlaying = isPlaying, hasVideo = true) },
                                        )
                                    } else if (!usesFullscreenThumbnail && !usesCanvasThumbnail) {
                                        CrossfadeArtwork(
                                            animate = LocalPlayerContentActive.current,
                                            currentUrl = requireNotNull(currentSong?.displayThumbnail),
                                            nextUrl = crossfadeNextArtworkUrl,
                                            progress = artworkCrossfadeProgress,
                                            contentDescription = stringResource(Res.string.album_art),
                                            modifier =
                                                Modifier
                                                    .requiredSize(artworkSize)
                                                    .clip(RoundedCornerShape(PlayerArtworkCornerRadius)),
                                            contentScale = ContentScale.Crop,
                                        )
                                        artworkOverlay(
                                            Modifier.requiredSize(artworkSize).clip(RoundedCornerShape(PlayerArtworkCornerRadius)),
                                        )
                                    }
                                    SeekFeedbackOverlay(
                                        feedback = seekFeedback,
                                        visible = seekFeedbackVisible,
                                    )
                                }
                            }
                        }
                    }
                    AnimatedVisibility(visible = uiState.isLyricsVisible && showLyricsOffsetEditor) {
                        LyricsOffsetEditor(
                            offsetMs = uiState.lyricsOffsetMs,
                            onOffsetChange = onLyricsOffsetChange,
                            onDone = onDoneLyricsOffset,
                            contentColor = textBackgroundColor,
                        )
                    }
                }
            }

            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(
                            start = LandscapeRightPaneStartGap,
                            end = LandscapeRightPaneEndGap,
                            top = controlsTopPadding,
                        ).focusGroup(),
            ) {
                BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    val contentHeight = maxHeight
                    Column(
                        modifier = Modifier.fillMaxWidth().verticalScrollWithDrag(rememberScrollState()).heightIn(min = contentHeight),
                        verticalArrangement =
                            if (isTv) {
                                Arrangement.spacedBy(if (compactHeight) 4.dp else 16.dp, Alignment.CenterVertically)
                            } else {
                                Arrangement.Top
                            },
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                AnimatedVisibility(
                                    visible = uiState.isLyricsVisible && currentSong?.displayThumbnail != null,
                                    enter = expandHorizontally(clip = false) + fadeIn(),
                                    exit = shrinkHorizontally(clip = false) + fadeOut(),
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        CrossfadeArtwork(
                                            animate = LocalPlayerContentActive.current,
                                            currentUrl = currentSong?.displayThumbnail,
                                            nextUrl = crossfadeNextArtworkUrl,
                                            progress = artworkCrossfadeProgress,
                                            contentDescription = stringResource(Res.string.small_album_art),
                                            modifier =
                                                Modifier
                                                    .size(48.dp)
                                                    .clip(if (isTv) RoundedCornerShape(8.dp) else ComponentShapes.SmallThumbnailShape),
                                            contentScale = ContentScale.Crop,
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))
                                    }
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = currentSong?.title ?: stringResource(Res.string.unknown),
                                        style =
                                            if (compactHeight) {
                                                MaterialTheme.typography.titleLarge
                                            } else {
                                                MaterialTheme.typography.headlineSmall
                                            },
                                        color = controlsColor,
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier =
                                            Modifier
                                                .fillMaxWidth()
                                                .clipToBounds()
                                                .clickable(enabled = currentSong?.albumId != null) {
                                                    currentSong?.albumId?.let(onNavigateToAlbum)
                                                }.then(
                                                    currentSong?.let { song ->
                                                        rememberTrackMarquee(
                                                            trackId = song.id,
                                                            enabled = LocalPlayerContentActive.current,
                                                            initialDelayMillis = 3000L,
                                                        )
                                                    } ?: Modifier,
                                                ),
                                    )
                                    PlayerArtistLine(
                                        artists = currentSong?.artists,
                                        onNavigateToArtist = onNavigateToArtist,
                                        iterations = if (LocalPlayerContentActive.current) 1 else 0,
                                        style =
                                            if (compactHeight) {
                                                MaterialTheme.typography.bodyMedium
                                            } else {
                                                MaterialTheme.typography.bodyLarge.copy(
                                                    fontSize = 18.sp,
                                                )
                                            },
                                        color = controlsColor.copy(alpha = 0.9f),
                                        modifier =
                                            Modifier
                                                .clip(RoundedCornerShape(10.dp))
                                                .padding(vertical = 4.dp),
                                    )
                                }

                                Spacer(modifier = Modifier.width(12.dp))
                                if (isClassic) {
                                    VintageClassicActionButtons(
                                        isLyricsVisible = uiState.isLyricsVisible,
                                        isLyricsExpanded = false,
                                        shareDescription = stringResource(Res.string.share),
                                        expandDescription = stringResource(Res.string.expand),
                                        collapseDescription = stringResource(Res.string.collapse),
                                        moreDescription = stringResource(Res.string.more_options),
                                        moreLyricsDescription = stringResource(Res.string.more_lyrics_options),
                                        onToggleLyricsExpanded = {},
                                        onShowLyricsMenu = {
                                            hapticManager.perform(HapticEvent.PRESS)
                                            onShowLyricsMenu()
                                        },
                                        onShareClick = {
                                            hapticManager.perform(HapticEvent.PRESS)
                                            onShareSong()
                                        },
                                        onShowPlayerMenu = {
                                            hapticManager.perform(HapticEvent.PRESS)
                                            onShowPlayerMenu()
                                        },
                                        buttonColor = controlsColor,
                                        onButtonColor = vintageOnButtonColor(controlsColor),
                                        showExpandButton = false,
                                    )
                                } else {
                                    PlayerActionButtons(
                                        uiState = uiState,
                                        isLiked = isLiked,
                                        onShareSong = onShareSong,
                                        onAltShareSong = onAltShareSong,
                                        onToggleLike = onToggleLike,
                                        onShowLyricsMenu = onShowLyricsMenu,
                                    )
                                }
                            }
                        }

                        val progressAndControls: @Composable ColumnScope.() -> Unit = {
                            Spacer(modifier = Modifier.height(if (compactHeight) 8.dp else 16.dp))

                            PlayerProgressSlider(
                                viewModel = viewModel,
                                duration = duration,
                                isPlaying = isPlaying,
                                textBackgroundColor = controlsColor,
                                canSeek = !isListenTogetherGuest,
                                isCasting = castState.isConnected,
                                songId = currentSong?.id,
                                progressColor = controlsColor.takeIf { isClassic },
                                timerTextStyle = MaterialTheme.typography.labelLarge,
                                isLive = uiState.isLive,
                            )

                            if (!isTv) Spacer(Modifier.weight(1f))
                            if (isTv) Spacer(modifier = Modifier.height(2.dp))

                            AnimatedVisibility(
                                visible = controlsVisible,
                                enter = expandVertically() + fadeIn(),
                                exit = shrinkVertically() + fadeOut(),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                if (!isListenTogetherGuest) {
                                    when {
                                        isClassic -> {
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
                                                isLoading = isPlaybackLoading && LocalPlayerContentActive.current,
                                                showLoadingIndicator = !(showVideoButton && isVideoModeResolving),
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
                                                    Logger.d("Haptics", "Trigger PlayerScreen play pause click isPlaying=$isPlaying")
                                                    viewModel.togglePlayPause()
                                                },
                                                onSkipNext = {
                                                    Logger.d("Haptics", "Trigger PlayerScreen next click")
                                                    hapticManager.perform(HapticEvent.PRESS)
                                                    viewModel.next()
                                                },
                                                onToggleLike = {
                                                    Logger.d("Haptics", "Trigger PlayerScreen like click liked=$isLiked")
                                                    hapticManager.perform(HapticEvent.PRESS)
                                                    onToggleLike()
                                                },
                                                contentColor = controlsColor,
                                                buttonColor = controlsColor,
                                                onButtonColor = vintageOnButtonColor(controlsColor),
                                                modifier = Modifier.fillMaxWidth(),
                                                buttonSize = primaryControlSize,
                                            )
                                        }

                                        isVintage -> {
                                            VintagePlayerControls(
                                                playPauseFocusRequester = playPauseFocusRequester,
                                                text = vintagePlayerControlsText(),
                                                buttonSize = if (compactHeight) 56.dp else 88.dp,
                                                iconSize = if (compactHeight) 42.dp else 68.dp,
                                                playButtonSize = primaryControlSize,
                                                playIconSize = if (compactHeight) 48.dp else 84.dp,
                                                spacing = if (compactHeight) 40.dp else 52.dp,
                                                isPlaying = isPlaying,
                                                isLoading = isPlaybackLoading && LocalPlayerContentActive.current,
                                                showLoadingIndicator = !(showVideoButton && isVideoModeResolving),
                                                onSkipPrevious = {
                                                    Logger.d("Haptics", "Trigger PlayerScreen previous click")
                                                    hapticManager.perform(HapticEvent.PRESS)
                                                    viewModel.previous()
                                                },
                                                onTogglePlay = {
                                                    Logger.d("Haptics", "Trigger PlayerScreen play pause click isPlaying=$isPlaying")
                                                    viewModel.togglePlayPause()
                                                },
                                                onSkipNext = {
                                                    Logger.d("Haptics", "Trigger PlayerScreen next click")
                                                    hapticManager.perform(HapticEvent.PRESS)
                                                    viewModel.next()
                                                },
                                                sideButtonColor = controlsColor,
                                                modifier = Modifier.fillMaxWidth(),
                                            )
                                        }

                                        else -> {
                                            PlayerPrimaryControls(
                                                isPlaying = isPlaying,
                                                isLoading = isPlaybackLoading && LocalPlayerContentActive.current,
                                                showLoadingIndicator = !(showVideoButton && isVideoModeResolving),
                                                playText = stringResource(Res.string.play),
                                                pauseText = stringResource(Res.string.pause),
                                                onSkipPrevious = {
                                                    Logger.d("Haptics", "Trigger PlayerScreen previous click")
                                                    hapticManager.perform(HapticEvent.PRESS)
                                                    viewModel.previous()
                                                },
                                                onTogglePlay = {
                                                    Logger.d("Haptics", "Trigger PlayerScreen play pause click isPlaying=$isPlaying")
                                                    viewModel.togglePlayPause()
                                                },
                                                onSkipNext = {
                                                    Logger.d("Haptics", "Trigger PlayerScreen next click")
                                                    hapticManager.perform(HapticEvent.PRESS)
                                                    viewModel.next()
                                                },
                                                showLabels = true,
                                                sideButtonContainerColor =
                                                    if (isTv) MaterialTheme.colorScheme.secondaryContainer else sideButtonContainerColor,
                                                sideButtonContentColor =
                                                    if (isTv) MaterialTheme.colorScheme.onSecondaryContainer else sideButtonContentColor,
                                                useRoundedRect = Settings.shared.useRoundedRectPlayButton,
                                                modifier = Modifier.fillMaxWidth(),
                                                buttonHeight = primaryControlSize,
                                                playPauseFocusRequester = playPauseFocusRequester,
                                            )
                                        }
                                    }
                                }
                            }
                            if (!isTv) Spacer(Modifier.weight(1f))
                        }
                        if (isTv) {
                            Column(Modifier.fillMaxWidth()) { progressAndControls() }
                        } else {
                            progressAndControls()
                        }
                    }
                }

                bottomControls()
            }
        }
    }
}

@Composable
private fun PlayerActionButtons(
    uiState: PlayerViewModel.PlayerUiState,
    isLiked: Boolean,
    onShareSong: () -> Unit,
    onToggleLike: () -> Unit,
    onShowLyricsMenu: () -> Unit,
    onAltShareSong: (() -> Unit)? = null,
) {
    val hapticManager = rememberMetrolistHaptics()

    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (uiState.isLyricsVisible) {
            FocusedPlayerActionIconButton(
                onClick = {
                    hapticManager.perform(HapticEvent.PRESS)
                    onShowLyricsMenu()
                },
                shape = CircleShape,
            ) {
                Icon(
                    MetrolistIcons.Default.MoreHoriz,
                    contentDescription = stringResource(Res.string.more_lyrics_options),
                    modifier = Modifier.size(24.dp),
                )
            }
        } else {
            PlayerLikeShareButtons(
                isLiked = isLiked,
                onLikeClick = {
                    hapticManager.perform(HapticEvent.PRESS)
                    onToggleLike()
                },
                onShareClick = {
                    hapticManager.perform(HapticEvent.PRESS)
                    onShareSong()
                },
                onAltShareClick = {
                    hapticManager.perform(HapticEvent.PRESS)
                    (onAltShareSong ?: onShareSong).invoke()
                },
                groupHeight = 40.dp,
                buttonWidth = 48.dp,
                iconSize = 20.dp,
            )
        }
    }
}

@Composable
private fun FocusedPlayerActionIconButton(
    onClick: () -> Unit,
    shape: Shape,
    content: @Composable () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }

    FilledIconButton(
        onClick = onClick,
        shapes = IconButtonDefaults.shapes(shape = shape),
        interactionSource = interactionSource,
        colors =
            IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
        modifier =
            Modifier
                .size(48.dp)
                .focusedPlayerBorder(shape, color = MaterialTheme.colorScheme.onPrimary, borderWidth = 3.dp),
        content = content,
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun PlayerActionRow(
    contentColor: Color,
    isLyricsVisible: Boolean,
    isLyricsExpanded: Boolean,
    onToggleLyricsExpanded: () -> Unit,
    onShowLyricsMenu: () -> Unit,
    isLiked: Boolean,
    onLikeClick: () -> Unit,
    onShareClick: () -> Unit,
    modifier: Modifier = Modifier,
    onAltShareClick: (() -> Unit)? = null,
) {
    if (isLyricsVisible) {
        val colors =
            if (LocalIsTv.current) {
                FilledTonalToggleButtonDefaults.colors()
            } else {
                FilledTonalToggleButtonDefaults.colors(
                    containerColor = Color.Transparent,
                    checkedContainerColor = Color.Transparent,
                    contentColor = contentColor,
                    checkedContentColor = contentColor,
                )
            }
        val expandDescription = stringResource(Res.string.expand)
        val collapseDescription = stringResource(Res.string.collapse)
        val moreLyricsOptionsDescription = stringResource(Res.string.more_lyrics_options)
        val expandInteractionSource = remember { MutableInteractionSource() }
        val menuInteractionSource = remember { MutableInteractionSource() }

        ButtonGroup(
            overflowIndicator = { menuState -> ButtonGroupDefaults.OverflowIndicator(menuState) },
            expandedRatio = 1f,
            horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
            verticalAlignment = Alignment.CenterVertically,
            modifier = modifier.height(40.dp),
        ) {
            customItem(
                buttonGroupContent = {
                    ToggleButton(
                        checked = isLyricsExpanded,
                        onCheckedChange = { onToggleLyricsExpanded() },
                        shapes = staticConnectedLeadingButtonShapes(),
                        contentPadding = PaddingValues(0.dp),
                        colors = colors,
                        interactionSource = expandInteractionSource,
                        modifier =
                            Modifier
                                .animateWidth(expandInteractionSource, 12.dp)
                                .size(width = 48.dp, height = 40.dp)
                                .semantics {
                                    contentDescription = if (isLyricsExpanded) collapseDescription else expandDescription
                                },
                    ) {
                        Icon(
                            if (isLyricsExpanded) MetrolistIcons.Default.FullscreenExit else MetrolistIcons.Default.Fullscreen,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                },
                menuContent = { menuState ->
                    DropdownMenuItem(
                        leadingIcon = {
                            Icon(
                                if (isLyricsExpanded) MetrolistIcons.Default.FullscreenExit else MetrolistIcons.Default.Fullscreen,
                                contentDescription = null,
                            )
                        },
                        text = { Text(stringResource(if (isLyricsExpanded) Res.string.collapse else Res.string.expand)) },
                        onClick = {
                            onToggleLyricsExpanded()
                            menuState.dismiss()
                        },
                    )
                },
            )
            customItem(
                buttonGroupContent = {
                    ToggleButton(
                        checked = false,
                        onCheckedChange = { onShowLyricsMenu() },
                        shapes = staticConnectedTrailingButtonShapes(),
                        contentPadding = PaddingValues(0.dp),
                        colors = colors,
                        interactionSource = menuInteractionSource,
                        modifier =
                            Modifier
                                .animateWidth(menuInteractionSource, 12.dp)
                                .size(width = 48.dp, height = 40.dp)
                                .semantics { contentDescription = moreLyricsOptionsDescription }
                                .navigationPopupSource(),
                    ) {
                        Icon(
                            MetrolistIcons.Default.MoreHoriz,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                },
                menuContent = { menuState ->
                    DropdownMenuItem(
                        modifier = Modifier.navigationPopupSource(),
                        leadingIcon = { Icon(MetrolistIcons.Default.MoreHoriz, contentDescription = null) },
                        text = { Text(moreLyricsOptionsDescription) },
                        onClick = {
                            onShowLyricsMenu()
                            menuState.dismiss()
                        },
                    )
                },
            )
        }
    } else {
        PlayerLikeShareButtons(
            isLiked = isLiked,
            onLikeClick = onLikeClick,
            onShareClick = onShareClick,
            onAltShareClick = onAltShareClick,
            groupHeight = 40.dp,
            buttonWidth = 48.dp,
            iconSize = 20.dp,
            modifier = modifier,
        )
    }
}

@Composable
internal fun LyricsViewWithPosition(
    viewModel: PlayerViewModel,
    lyrics: List<LyricsEntry>,
    songwriters: List<String> = emptyList(),
    agents: Map<String, AgentInfo> = emptyMap(),
    provider: String?,
    timingOffsetMs: Int,
    onSeek: (Long, PlaybackSeekSource) -> Unit,
    isLoading: Boolean,
    textColor: Color,
    isCasting: Boolean,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectWhilePlayerActive(LocalPlayerPositionActive.current)
    val shareLyrics = LocalPlayerPlatform.current.lyricsShareAction(viewModel)
    val displayedProvider = if (isLocalLyricsProvider(provider)) stringResource(Res.string.local) else provider
    val providedByText = displayedProvider?.let { stringResource(Res.string.lyrics_provided_by, it) }

    LyricsView(
        noLyricsText = stringResource(Res.string.lyrics_not_found),
        translationProgress = uiState.lyricsTranslationProgress,
        autoScrollText = stringResource(Res.string.lyrics_auto_scroll_btn),
        onShareSelection = shareLyrics,
        lyricsProvidedBy = { providerName -> providedByText?.takeIf { providerName == displayedProvider } ?: providerName },
        lyrics = lyrics,
        songwriters = songwriters,
        agents = agents,
        provider = displayedProvider,
        providerUrl = lyricsProviderUrl(provider, uiState.lyricsMeta?.sourceId),
        lyricsMeta = uiState.lyricsMeta,
        lyricsCuratorUrl = lyricsCuratorUrl(provider, uiState.lyricsMeta?.lyricsCuratorId),
        currentPositionFlow = viewModel.currentPosition,
        positionReader = viewModel::currentPositionMs,
        playbackSpeed = viewModel.playbackSpeed.collectAsState().value,
        timingOffsetMs = timingOffsetMs,
        lyricsRetimed = uiState.mvLyricsRetimed,
        isPlaying = uiState.isPlaying,
        isVisible = LocalPlayerPositionActive.current,
        onSeek = onSeek,
        isLoading = isLoading,
        textColor = textColor,
        songId = uiState.currentSong?.id,
        isCasting = isCasting,
        modifier = modifier,
    )
}

@Composable
internal fun ActiveLyricsLine(
    viewModel: PlayerViewModel,
    lyrics: List<LyricsEntry>,
    songId: String?,
    timingOffsetMs: Int,
    isPlaying: Boolean,
    showLyrics: Boolean,
    textColor: Color,
    isCasting: Boolean,
    hasBackground: Boolean = false,
    modifier: Modifier = Modifier,
) {
    if (!showLyrics || !LyricsUtils.hasPlaybackTiming(lyrics)) return
    val lyricsPositionState =
        rememberCaptionLyricsPositionState(
            lyrics = lyrics,
            currentPositionFlow = viewModel.currentPosition,
            positionReader = viewModel::currentPositionMs,
            playbackSpeed = viewModel.playbackSpeed.collectAsState().value,
            isPlaying = isPlaying,
            enabled = LocalPlayerPositionActive.current,
            songId = songId,
            timingOffsetMs = timingOffsetMs,
            isCasting = isCasting,
            finalLineDurationMs = LyricsUtils.FINAL_DYNAMIC_LINE_DURATION_MS,
        )

    val currentLineIndex by remember(lyrics, lyricsPositionState) {
        derivedStateOf(structuralEqualityPolicy()) { LyricsUtils.findCurrentLineIndex(lyrics, lyricsPositionState.value) }
    }
    val currentLine = lyrics.getOrNull(currentLineIndex)
    val nextLine = lyrics.getOrNull(currentLineIndex + 1)

    val lineEndTime =
        currentLine?.let {
            LyricsUtils
                .getLineEndTime(it, nextLine, LyricsUtils.FINAL_DYNAMIC_LINE_DURATION_MS)
        } ?: 0L

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

    AnimatedVisibility(
        visible = isVisible,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier.padding(top = 16.dp).fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            val style =
                MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    textAlign = LyricsUtils.textAlignment(Settings.shared.activeLyricsTextPosition),
                )
            val reservedLineHeight = with(LocalDensity.current) { style.lineHeight.toDp() * 2 }
            val lyricsContent = @Composable {
                AnimatedContent(
                    targetState = currentLine to lineEndTime,
                    transitionSpec = {
                        (fadeIn(animationSpec = tween(400)) togetherWith fadeOut(animationSpec = tween(400)))
                            .using(null)
                    },
                    contentAlignment = Alignment.Center,
                    label = "activeLyricLineSwitch",
                    modifier = Modifier.sizeIn(minHeight = reservedLineHeight).wrapContentHeight(Alignment.CenterVertically),
                ) { (line, displayedLineEndTime) ->
                    if (line != null) {
                        val isActive by remember(line, displayedLineEndTime, lyricsPositionState) {
                            derivedStateOf(structuralEqualityPolicy()) { lyricsPositionState.value in line.time..displayedLineEndTime }
                        }
                        val baseTextColor = if (hasBackground) Color.White else textColor
                        ActiveLyricsLineContent(
                            entry = line,
                            isActive = isActive,
                            positionProvider = { lyricsPositionState.value },
                            lineEndTimeMs = displayedLineEndTime,
                            isPlaying = isPlaying,
                            textColor = resolveLyricsTextColor(baseTextColor),
                            useFixedTone = false,
                            lyricStyle = style,
                            textMeasurer = rememberTextMeasurer(),
                            maxLines = 2,
                            alignment = LyricsUtils.textAlignment(Settings.shared.activeLyricsTextPosition),
                            fillWidth = !hasBackground,
                        )
                    }
                }
            }

            if (hasBackground) {
                Surface(
                    color = lerp(MaterialTheme.colorScheme.secondaryContainer, Color.Black, 0.25f).copy(alpha = 0.88f),
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.padding(horizontal = 24.dp),
                ) {
                    Box(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        lyricsContent()
                    }
                }
            } else {
                lyricsContent()
            }
        }
    }
}

@Composable
internal fun vintagePlayerControlsText() =
    VintagePlayerControlsText(
        previous = stringResource(Res.string.previous),
        play = stringResource(Res.string.play),
        pause = stringResource(Res.string.pause),
        next = stringResource(Res.string.next),
    )
