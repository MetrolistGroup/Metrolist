/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class, ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.desktop.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.EaseInBack
import androidx.compose.animation.core.EaseOutBack
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.rememberSliderState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.metrolist.desktop.constants.BottomPlayerHeight
import com.metrolist.desktop.deviceconnect.DesktopDeviceConnect
import com.metrolist.desktop.state.app.AppState
import com.metrolist.desktop.state.app.UiState
import com.metrolist.desktop.state.content.ArtistState
import com.metrolist.desktop.state.content.PlaylistAlbumState
import com.metrolist.desktop.state.playback.ListenTogetherState
import com.metrolist.desktop.state.playback.LyricsState
import com.metrolist.desktop.state.playback.PlayerState
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.cancel
import com.metrolist.shared.generated.resources.close
import com.metrolist.shared.generated.resources.expand
import com.metrolist.shared.generated.resources.loading
import com.metrolist.shared.generated.resources.media_details
import com.metrolist.shared.generated.resources.more
import com.metrolist.shared.generated.resources.next
import com.metrolist.shared.generated.resources.not_playing
import com.metrolist.shared.generated.resources.pause
import com.metrolist.shared.generated.resources.play
import com.metrolist.shared.generated.resources.play_pause
import com.metrolist.shared.generated.resources.playback_reconnecting
import com.metrolist.shared.generated.resources.previous
import com.metrolist.shared.generated.resources.sleep_timer
import com.metrolist.shared.generated.resources.start
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.isLive
import com.metrolist.shared.playback.SleepTimerState
import com.metrolist.shared.settings.PlayerType
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.settings.SliderStyle
import com.metrolist.shared.settings.displayName
import com.metrolist.shared.ui.components.AlertDialog
import com.metrolist.shared.ui.components.LiveBadge
import com.metrolist.shared.ui.components.PlayerConnectedButtonGroup
import com.metrolist.shared.ui.components.PlayerLikeShareButtons
import com.metrolist.shared.ui.components.PlayerPrimaryControls
import com.metrolist.shared.ui.components.PopupVisibility
import com.metrolist.shared.ui.components.VintagePlayerControls
import com.metrolist.shared.ui.components.VintagePlayerControlsText
import com.metrolist.shared.ui.components.rememberTrackMarquee
import com.metrolist.shared.ui.components.sliders.PlaybackProgressSlider
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.menu.SongDetailsContent
import com.metrolist.shared.ui.menu.cursorContextMenu
import com.metrolist.shared.ui.menu.rememberCursorContextMenuState
import com.metrolist.shared.ui.navigation.blockInput
import com.metrolist.shared.ui.navigation.navigationPopupSource
import com.metrolist.shared.ui.playback.retainPlaybackKeyFocus
import com.metrolist.shared.ui.theme.uiEnhanced
import com.metrolist.shared.utils.ShareUrls
import com.metrolist.shared.utils.formatDuration
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import org.koin.core.context.GlobalContext
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds
import com.metrolist.shared.ui.components.SleepTimerDialog as SharedSleepTimerDialog

@Composable
fun PersistentPlayerDrawer(
    colorScheme: ColorScheme,
    availableWidth: Dp,
    modifier: Modifier = Modifier,
) {
    val widthClass = currentWindowAdaptiveInfoV2().windowSizeClass.minWidthDp
    val preferredWidth =
        if (widthClass < 600) {
            300.dp
        } else if (widthClass < 960) {
            320.dp
        } else {
            400.dp
        }
    val drawerOpen =
        UiState.showPersistentPlayerDrawer &&
            PlayerState.currentTrack != null &&
            availableWidth >= 280.dp
    val targetDrawerWidth = if (drawerOpen) minOf(preferredWidth, availableWidth) else 0.dp
    val animatedDrawerWidth by animateDpAsState(
        targetValue = targetDrawerWidth,
        animationSpec = tween(durationMillis = 280, easing = EaseOutCubic),
        label = "queueDrawerWidth",
    )
    AnimatedVisibility(
        visible = drawerOpen,
        modifier = modifier.fillMaxHeight(),
        enter = fadeIn(animationSpec = tween(200)),
        exit = fadeOut(animationSpec = tween(150)),
    ) {
        DesktopQueueContent(
            onDismiss = { UiState.showPersistentPlayerDrawer = false },
            colorScheme = colorScheme,
            modifier = Modifier.width(animatedDrawerWidth).blockInput(!drawerOpen || animatedDrawerWidth != targetDrawerWidth),
        )
    }
}

@Composable
fun LyricsDrawer(
    colorScheme: ColorScheme,
    availableWidth: Dp,
    modifier: Modifier = Modifier,
) {
    val widthClass = currentWindowAdaptiveInfoV2().windowSizeClass.minWidthDp
    val preferredWidth =
        if (widthClass < 600) {
            300.dp
        } else if (widthClass < 960) {
            320.dp
        } else {
            420.dp
        }
    val drawerOpen =
        UiState.showLyricsDrawer &&
            PlayerState.currentTrack != null &&
            availableWidth >= 280.dp
    val targetDrawerWidth = if (drawerOpen) minOf(preferredWidth, availableWidth) else 0.dp
    val animatedDrawerWidth by animateDpAsState(
        targetValue = targetDrawerWidth,
        animationSpec = tween(durationMillis = 280, easing = EaseOutCubic),
        label = "lyricsDrawerWidth",
    )
    LyricsDrawer(
        drawerOpen = drawerOpen,
        animatedDrawerWidth = animatedDrawerWidth,
        colorScheme = colorScheme,
        modifier = modifier,
        onClose = { UiState.showLyricsDrawer = false },
        isVisible = !PlayerState.isExpanded && !UiState.videoFullscreenObscuresPlayer,
    )
}

private fun openCurrentTrackCollection() {
    val song = PlayerState.currentTrack ?: return
    PlaylistAlbumState.openSongAlbum(song)
}

@Composable
internal fun SleepTimerDialog(onDismiss: () -> Unit) {
    val remaining by PlayerState.sleepTimerTimeLeftMs.collectAsState()
    SharedSleepTimerDialog(
        state =
            SleepTimerState(
                isActive = PlayerState.sleepTimerEnabled,
                remainingMs = remaining,
                stopAfterCurrentSong = PlayerState.sleepTimerStopAfterCurrentSong,
                fadeOut = PlayerState.sleepTimerFadeOut,
                finishCurrentSong = PlayerState.sleepTimerFinishCurrentSong,
            ),
        onDismiss = onDismiss,
        onStart = { durationMs, fadeOut, finishCurrentSong ->
            PlayerState.startSleepTimer(durationMs, stopAfterCurrentSong = false, fadeOut = fadeOut, finishCurrentSong = finishCurrentSong)
        },
        onStopAfterCurrentSong = { fadeOut -> PlayerState.startSleepTimer(0L, stopAfterCurrentSong = true, fadeOut = fadeOut) },
        onCancelTimer = PlayerState::clearSleepTimer,
        allowEndOfSong = !PlayerState.isLiveStream,
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MetrolistPlayPauseButton(
    isPlaying: Boolean,
    isLoading: Boolean,
    onClick: () -> Unit,
    colorScheme: ColorScheme,
    modifier: Modifier = Modifier,
    isWide: Boolean = false,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
) {
    val isPressed by interactionSource.collectIsPressedAsState()
    val isHovered by interactionSource.collectIsHoveredAsState()

    val scale by animateFloatAsState(
        targetValue =
            when {
                isPressed -> 0.92f
                isHovered -> 1.08f
                else -> 1f
            },
        animationSpec = spring(dampingRatio = 0.75f),
    )

    val width by animateDpAsState(if (isWide) 120.dp else 56.dp)
    val height = 48.dp
    val shapeRadius = 24.dp
    val loadingDescription = stringResource(Res.string.loading)

    FilledTonalButton(
        modifier =
            modifier
                .size(width = width, height = height)
                .scale(scale)
                .hoverable(interactionSource),
        shapes = ButtonDefaults.shapes(shape = RoundedCornerShape(shapeRadius)),
        colors =
            ButtonDefaults.filledTonalButtonColors(
                containerColor = if (isHovered) colorScheme.primaryContainer.copy(alpha = 0.9f) else colorScheme.primaryContainer,
                contentColor = colorScheme.onPrimaryContainer,
            ),
        contentPadding = PaddingValues(0.dp),
        onClick = onClick,
        interactionSource = interactionSource,
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AnimatedContent(
                targetState = if (isLoading) null else isPlaying,
                transitionSpec = {
                    (scaleIn(animationSpec = tween(250, easing = EaseOutBack)) + fadeIn()).togetherWith(
                        scaleOut(animationSpec = tween(250, easing = EaseInBack)) + fadeOut(),
                    )
                },
            ) { playing ->
                if (playing == null) {
                    LoadingIndicator(
                        modifier = Modifier.size(24.dp).semantics { contentDescription = loadingDescription },
                        color = colorScheme.onPrimaryContainer,
                    )
                } else {
                    Icon(
                        imageVector = if (playing) MetrolistIcons.Rounded.Pause else MetrolistIcons.Rounded.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(if (isWide) 24.dp else 32.dp),
                        tint = colorScheme.onPrimaryContainer,
                    )
                }
            }
            if (isWide && !isLoading) {
                Spacer(Modifier.width(8.dp))
                AnimatedContent(
                    targetState =
                        stringResource(
                            if (isPlaying) {
                                Res.string.pause
                            } else {
                                Res.string.play
                            },
                        ),
                    transitionSpec = {
                        (slideInVertically { it / 2 } + fadeIn()).togetherWith(
                            slideOutVertically { -it / 2 } + fadeOut(),
                        )
                    },
                ) { label ->
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = colorScheme.onPrimaryContainer,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PlayerSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit = {},
    style: SliderStyle,
    isPlaying: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
    bufferedValue: Float = 0f,
    enabled: Boolean = true,
    showThumb: Boolean = true,
    valueProvider: () -> Float = { value },
) {
    PlaybackProgressSlider(
        value = value,
        valueProvider = valueProvider,
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        style = style,
        isPlaying = isPlaying,
        enabled = enabled,
        showThumb = showThumb,
        colors =
            SliderDefaults.colors(
                activeTrackColor = color,
                thumbColor = if (showThumb) color else Color.Transparent,
                disabledActiveTrackColor = color,
                disabledThumbColor = if (showThumb) color else Color.Transparent,
                inactiveTrackColor = color.copy(alpha = 0.24f),
                disabledInactiveTrackColor = color.copy(alpha = 0.24f),
            ),
        bufferedFraction = bufferedValue,
        modifier = modifier,
    )
}

@Composable
fun LiveProgressBar(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    style: SliderStyle = Settings.shared.sliderStyle,
) {
    PlayerSlider(
        value = 1f,
        valueProvider = { 1f },
        onValueChange = {},
        onValueChangeFinished = {},
        style = style,
        isPlaying = true,
        color = color,
        bufferedValue = 1f,
        enabled = false,
        showThumb = false,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun StandardPlayerProgress(colorScheme: ColorScheme) {
    if (UiState.videoFullscreenObscuresPlayer) return
    val playerPosition = rememberPlaybackSecondPosition()
    val playerBufferedPosition by PlayerState.playbackBufferedPosition.collectAsSecondsState()
    val playerDuration by PlayerState.playbackDuration.collectAsState()
    val isPlaying = PlayerState.isPlaying
    val isListenTogetherGuest = ListenTogetherState.isListenTogetherGuest()

    val isLive = playerIsLiveStream()
    var sliderWidthPx by remember { mutableIntStateOf(0) }
    val smoothPosition = rememberPlayerProgressPosition(playerDuration, sliderWidthPx, isLive)
    // Read the initial slider value without subscribing this row to position samples.
    val sampledProgress = if (playerDuration > 0L) PlayerState.playbackPosition.value.toFloat() / playerDuration else 0f
    val maxProgress = if (PlayerState.isCrossfading) 0.995f else 1f
    val bufferedProgress = if (playerDuration > 0L) (playerBufferedPosition.toFloat() / playerDuration.toFloat()).coerceIn(0f, 1f) else 0f

    var isDragging by remember { mutableStateOf(false) }
    var pendingSeekTargetMs by remember { mutableStateOf<Long?>(null) }
    var dragStartPosMs by remember { mutableLongStateOf(-1L) }
    var sliderValue by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(pendingSeekTargetMs) {
        if (pendingSeekTargetMs != null) {
            delay(1200.milliseconds)
            pendingSeekTargetMs = null
        }
    }

    LaunchedEffect(isDragging, pendingSeekTargetMs, dragStartPosMs) {
        if (isDragging) return@LaunchedEffect
        val targetMs = pendingSeekTargetMs ?: return@LaunchedEffect
        PlayerState.playbackPosition.collect { sample ->
            if (sample != dragStartPosMs && abs(sample - targetMs) < 2000L) {
                pendingSeekTargetMs = null
            }
        }
    }

    val canSeek = !isListenTogetherGuest && !isLive

    val displayPosition = {
        if (isDragging || pendingSeekTargetMs != null) (sliderValue * playerDuration).toLong() else playerPosition.value
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (isLive) {
            LiveBadge()
            LiveProgressBar(
                modifier = Modifier.weight(1f),
                color = colorScheme.primary,
            )
            PlayerProgressTime(
                positionProvider = displayPosition,
                style = MaterialTheme.typography.labelMedium,
                color = colorScheme.onSurfaceVariant,
                modifier = Modifier.widthIn(min = 52.dp),
                textAlign = TextAlign.Center,
            )
        } else {
            PlayerProgressTime(
                positionProvider = displayPosition,
                style = MaterialTheme.typography.labelMedium,
                color = colorScheme.onSurfaceVariant,
                modifier = Modifier.widthIn(min = 52.dp),
                textAlign = TextAlign.Center,
            )
            PlayerSlider(
                value = if (isDragging || pendingSeekTargetMs != null) sliderValue else sampledProgress.coerceIn(0f, maxProgress),
                valueProvider = {
                    when {
                        isDragging || pendingSeekTargetMs != null -> sliderValue
                        playerDuration > 0L -> (smoothPosition.longValue.toFloat() / playerDuration.toFloat()).coerceIn(0f, maxProgress)
                        else -> 0f
                    }
                },
                onValueChange = {
                    if (canSeek) {
                        if (!isDragging) {
                            isDragging = true
                            dragStartPosMs = PlayerState.playbackPosition.value
                        }
                        pendingSeekTargetMs = null
                        sliderValue = it
                    }
                },
                onValueChangeFinished = {
                    if (canSeek) {
                        val targetMs = (sliderValue * playerDuration).toLong()
                        PlayerState.seekTo(sliderValue)
                        isDragging = false
                        pendingSeekTargetMs = targetMs
                    }
                },
                style = Settings.shared.sliderStyle,
                isPlaying = isPlaying,
                color = colorScheme.primary,
                modifier = Modifier.weight(1f).onSizeChanged { sliderWidthPx = it.width },
                bufferedValue = bufferedProgress,
                enabled = canSeek,
            )
            Text(
                text = formatDuration(playerDuration),
                style = MaterialTheme.typography.labelMedium,
                color = colorScheme.onSurfaceVariant,
                modifier = Modifier.widthIn(min = 52.dp),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun StandardBottomPlayer(
    colorScheme: ColorScheme,
    toggleWindowFullscreen: () -> Unit,
) {
    val castState by PlayerState.castManager.state.collectAsState()
    val deviceConnectState by DesktopDeviceConnect.manager.remote.state
        .collectAsState()
    val isListenTogetherGuest = ListenTogetherState.isListenTogetherGuest()
    val playerMenu = rememberCursorContextMenuState()
    var showSleepDialog by remember { mutableStateOf(false) }
    var showDetailsDialog by remember { mutableStateOf(false) }
    var showCastDialog by remember { mutableStateOf(false) }

    // Switch during composition so open popups dispose before Compose measures their owners.
    val windowWidth = LocalWindowInfo.current.containerSize.width
    val width = with(LocalDensity.current) { windowWidth.toDp() }

    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .cursorContextMenu(playerMenu)
                .background(colorScheme.surfaceContainer),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            StandardPlayerProgress(colorScheme)
            if (PlayerState.isReconnecting) {
                Text(
                    stringResource(Res.string.playback_reconnecting),
                    style = MaterialTheme.typography.labelMedium,
                    color = colorScheme.onSurface,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            }

            val artists = PlayerState.currentTrack?.artists ?: emptyList()
            val isLiked = PlayerState.currentTrack?.let { PlayerState.isLiked(it.id) } == true

            val isCompact = width < 600.dp
            val isMedium = width >= 600.dp && width < 960.dp
            val isExpanded = width >= 960.dp && width < 1280.dp
            val isLargeOrExtraLarge = width >= 1280.dp
            val isWide = isExpanded || isLargeOrExtraLarge

            val controlsButtonHeight =
                if (isCompact) {
                    40.dp
                } else if (isMedium) {
                    44.dp
                } else {
                    52.dp
                }

            if (isCompact) {
                Row(
                    modifier = Modifier.fillMaxWidth().height(BottomPlayerHeight).padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val compactCoverInteractionSource = remember { MutableInteractionSource() }
                        val isCompactCoverHovered by compactCoverInteractionSource.collectIsHoveredAsState()
                        val compactCoverOverlayAlpha by animateFloatAsState(
                            targetValue = if (isCompactCoverHovered) 1f else 0f,
                            animationSpec = spring(stiffness = Spring.StiffnessHigh),
                        )
                        Box(
                            modifier =
                                Modifier
                                    .songThumbSize(PlayerState.currentTrack, 44.dp)
                                    .clip(RoundedCornerShape(uiEnhanced(4.dp, 8.dp)))
                                    .hoverable(compactCoverInteractionSource)
                                    .clickable(
                                        indication = null,
                                        interactionSource = remember { MutableInteractionSource() },
                                    ) { PlayerState.enterExpanded() },
                        ) {
                            AsyncImage(
                                url = PlayerState.currentArtworkUrl ?: "",
                                artworkPreview = PlayerState.artworkPreview,
                                modifier =
                                    Modifier
                                        .fillMaxSize()
                                        .desktopPlayerArtwork(PlayerState.currentTrack?.id),
                                extractColor = true,
                                contentScale = if (Settings.shared.cropAlbumArt) ContentScale.Crop else ContentScale.Fit,
                            )
                            Box(
                                modifier =
                                    Modifier
                                        .fillMaxSize()
                                        .graphicsLayer { alpha = compactCoverOverlayAlpha }
                                        .background(Color.Black.copy(alpha = 0.6f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    MetrolistIcons.Outlined.KeyboardArrowUp,
                                    contentDescription = stringResource(Res.string.expand),
                                    tint = Color.White,
                                    modifier = Modifier.size(22.dp),
                                )
                            }
                        }
                        Spacer(Modifier.width(8.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            val trackMarquee =
                                rememberTrackMarquee(
                                    PlayerState.currentTrack?.id.orEmpty(),
                                    !UiState.videoFullscreenObscuresPlayer,
                                    10_000L,
                                )
                            Text(
                                PlayerState.currentTrack?.title ?: stringResource(Res.string.not_playing),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                color = colorScheme.onSurface,
                                modifier =
                                    Modifier
                                        .wrapContentWidth(Alignment.Start)
                                        .then(trackMarquee)
                                        .clickable(
                                            enabled = PlayerState.currentTrack != null,
                                            indication = null,
                                            interactionSource = remember { MutableInteractionSource() },
                                        ) { openCurrentTrackCollection() },
                            )

                            Row(
                                modifier =
                                trackMarquee,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                artists.forEachIndexed { idx, artist ->
                                    Text(
                                        text = artist.displayName,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        modifier =
                                            Modifier.clickable(
                                                enabled = artist.id != null,
                                                indication = null,
                                                interactionSource = remember { MutableInteractionSource() },
                                            ) {
                                                artist.id?.let { ArtistState.fetchArtistData(it) }
                                            },
                                    )
                                    if (idx < artists.lastIndex) {
                                        Text(
                                            ", ",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                if (PlayerState.currentTrack?.isExplicit == true) {
                                    Spacer(Modifier.width(4.dp))
                                    ExplicitBadge()
                                }
                            }
                        }
                    }

                    Spacer(Modifier.width(8.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.End,
                    ) {
                        PlayerLikeShareButtons(
                            isLiked = isLiked,
                            onLikeClick = { PlayerState.currentTrack?.let { PlayerState.toggleLike(it) } },
                            onShareClick = {
                                PlayerState.currentTrack?.let { track ->
                                    AppState.shareLink(ShareUrls.song(track.id), track.title)
                                }
                            },
                            onAltShareClick = {
                                PlayerState.currentTrack?.let { track ->
                                    AppState.shareLink(ShareUrls.song(track.id), track.title)
                                }
                            },
                            groupHeight = 36.dp,
                            iconSize = 18.dp,
                        )

                        if (!isListenTogetherGuest) {
                            Spacer(Modifier.width(8.dp))

                            Surface(
                                shape = CircleShape,
                                color = colorScheme.primaryContainer,
                                modifier = Modifier.size(36.dp),
                            ) {
                                IconButton(
                                    onClick = { PlayerState.togglePlay() },
                                    modifier = Modifier.fillMaxSize(),
                                    shapes = IconButtonDefaults.shapes(),
                                ) {
                                    if (PlayerState.isPlaybackLoading) {
                                        LoadingIndicator(
                                            modifier = Modifier.size(20.dp),
                                            color = colorScheme.onPrimaryContainer,
                                        )
                                    } else {
                                        Icon(
                                            if (PlayerState.isPlaying) {
                                                MetrolistIcons.Rounded.Pause
                                            } else {
                                                MetrolistIcons.Rounded.PlayArrow
                                            },
                                            contentDescription =
                                                stringResource(
                                                    if (PlayerState.isPlaying) Res.string.pause else Res.string.play,
                                                ),
                                            tint = colorScheme.onPrimaryContainer,
                                            modifier = Modifier.size(20.dp),
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.width(8.dp))

                        Box {
                            val arrowRotation by animateFloatAsState(
                                targetValue = if (playerMenu.expanded) 180f else 0f,
                                animationSpec = spring(dampingRatio = 0.6f, stiffness = 500f),
                            )
                            val containerColor by animateColorAsState(
                                targetValue = if (playerMenu.expanded) colorScheme.primaryContainer else colorScheme.secondaryContainer,
                                animationSpec = spring(dampingRatio = 0.6f, stiffness = 500f),
                            )
                            val iconColor by animateColorAsState(
                                targetValue = if (playerMenu.expanded) colorScheme.onPrimaryContainer else colorScheme.onSecondaryContainer,
                                animationSpec = spring(dampingRatio = 0.6f, stiffness = 500f),
                            )

                            Surface(
                                shape = CircleShape,
                                color = containerColor,
                                modifier = Modifier.size(36.dp),
                            ) {
                                IconButton(
                                    onClick = playerMenu::openAnchored,
                                    modifier = Modifier.fillMaxSize().navigationPopupSource(),
                                    shapes = IconButtonDefaults.shapes(),
                                ) {
                                    Icon(
                                        MetrolistIcons.Outlined.KeyboardArrowUp,
                                        contentDescription = stringResource(Res.string.more),
                                        tint = iconColor,
                                        modifier =
                                            Modifier
                                                .size(20.dp)
                                                .graphicsLayer { rotationZ = arrowRotation },
                                    )
                                }
                            }

                            PlayerMenuPanel(
                                expanded = playerMenu.expanded,
                                offset = playerMenu.offset,
                                onDismiss = playerMenu::dismiss,
                                onShowAddToPlaylistDialog = {
                                    playerMenu.dismiss()
                                    PlayerState.currentTrack?.let { song ->
                                        PlayerState.showAddToPlaylistSong = song
                                    }
                                },
                                onShowDetailsDialog = {
                                    playerMenu.dismiss()
                                    showDetailsDialog = true
                                },
                                onShowSleepTimerDialog = {
                                    playerMenu.dismiss()
                                    showSleepDialog = true
                                },
                            )
                        }
                    }
                }
            } else {
                val swapControls = PlayerState.swapPlayerControls
                val horizontalPadding = if (isWide) 24.dp else 16.dp
                val sectionGap = if (isWide) 16.dp else 8.dp

                Row(
                    modifier = Modifier.fillMaxWidth().height(BottomPlayerHeight).padding(horizontal = horizontalPadding),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val songInfoSection: @Composable (Modifier, Boolean) -> Unit = { sectionModifier, isCentered ->
                        Row(
                            modifier = sectionModifier.fillMaxHeight(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = if (isCentered) Arrangement.Center else Arrangement.Start,
                        ) {
                            val standardCoverInteractionSource = remember { MutableInteractionSource() }
                            val isStandardCoverHovered by standardCoverInteractionSource.collectIsHoveredAsState()
                            val standardCoverOverlayAlpha by animateFloatAsState(
                                targetValue = if (isStandardCoverHovered) 1f else 0f,
                                animationSpec = spring(stiffness = Spring.StiffnessHigh),
                            )
                            Box(
                                modifier =
                                    Modifier
                                        .songThumbSize(PlayerState.currentTrack, 48.dp)
                                        .clip(RoundedCornerShape(uiEnhanced(4.dp, 8.dp)))
                                        .hoverable(standardCoverInteractionSource)
                                        .clickable(
                                            indication = null,
                                            interactionSource = remember { MutableInteractionSource() },
                                        ) { PlayerState.enterExpanded() },
                            ) {
                                AsyncImage(
                                    url = PlayerState.currentArtworkUrl ?: "",
                                    artworkPreview = PlayerState.artworkPreview,
                                    modifier =
                                        Modifier
                                            .fillMaxSize()
                                            .desktopPlayerArtwork(PlayerState.currentTrack?.id),
                                    extractColor = true,
                                    contentScale = if (Settings.shared.cropAlbumArt) ContentScale.Crop else ContentScale.Fit,
                                )
                                Box(
                                    modifier =
                                        Modifier
                                            .fillMaxSize()
                                            .graphicsLayer { alpha = standardCoverOverlayAlpha }
                                            .background(Color.Black.copy(alpha = 0.6f)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        MetrolistIcons.Outlined.KeyboardArrowUp,
                                        contentDescription = stringResource(Res.string.expand),
                                        tint = Color.White,
                                        modifier = Modifier.size(22.dp),
                                    )
                                }
                            }
                            Spacer(Modifier.width(8.dp))

                            Column(modifier = Modifier.weight(1f, fill = false)) {
                                val trackMarquee =
                                    rememberTrackMarquee(
                                        PlayerState.currentTrack?.id.orEmpty(),
                                        !UiState.videoFullscreenObscuresPlayer,
                                        10_000L,
                                    )
                                Text(
                                    PlayerState.currentTrack?.title ?: stringResource(Res.string.not_playing),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    color = colorScheme.onSurface,
                                    modifier =
                                        Modifier
                                            .wrapContentWidth(Alignment.Start)
                                            .then(trackMarquee)
                                            .clickable(
                                                enabled = PlayerState.currentTrack != null,
                                                indication = null,
                                                interactionSource = remember { MutableInteractionSource() },
                                            ) { openCurrentTrackCollection() },
                                )

                                Row(
                                    modifier =
                                    trackMarquee,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    artists.forEachIndexed { idx, artist ->
                                        Text(
                                            text = artist.displayName,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            modifier =
                                                Modifier.clickable(
                                                    enabled = artist.id != null,
                                                    indication = null,
                                                    interactionSource = remember { MutableInteractionSource() },
                                                ) {
                                                    artist.id?.let { ArtistState.fetchArtistData(it) }
                                                },
                                        )
                                        if (idx < artists.lastIndex) {
                                            Text(
                                                ", ",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                    if (PlayerState.currentTrack?.isExplicit == true) {
                                        Spacer(Modifier.width(4.dp))
                                        ExplicitBadge()
                                    }
                                }
                            }

                            if (isWide) {
                                Spacer(Modifier.width(12.dp))

                                Box {
                                    PlayerLikeShareButtons(
                                        isLiked = isLiked,
                                        onLikeClick = { PlayerState.currentTrack?.let { PlayerState.toggleLike(it) } },
                                        onShareClick = {
                                            PlayerState.currentTrack?.let { track ->
                                                AppState.shareLink(ShareUrls.song(track.id), track.title)
                                            }
                                        },
                                        onAltShareClick = {
                                            PlayerState.currentTrack?.let { track ->
                                                AppState.shareLink(ShareUrls.song(track.id), track.title)
                                            }
                                        },
                                        onMoreClick = { playerMenu.openAnchored() },
                                        isMoreActive = playerMenu.expanded,
                                        groupHeight = 36.dp,
                                        iconSize = 18.dp,
                                    )

                                    PlayerMenuPanel(
                                        expanded = playerMenu.expanded,
                                        offset = playerMenu.offset,
                                        onDismiss = playerMenu::dismiss,
                                        onShowAddToPlaylistDialog = {
                                            playerMenu.dismiss()
                                            PlayerState.currentTrack?.let { song ->
                                                PlayerState.showAddToPlaylistSong = song
                                            }
                                        },
                                        onShowDetailsDialog = {
                                            playerMenu.dismiss()
                                            showDetailsDialog = true
                                        },
                                        onShowSleepTimerDialog = {
                                            playerMenu.dismiss()
                                            showSleepDialog = true
                                        },
                                    )
                                }
                            }
                        }
                    }

                    val controlsSection: @Composable (Modifier) -> Unit = { sectionModifier ->
                        Row(
                            modifier = sectionModifier.fillMaxHeight(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = if (swapControls) Arrangement.Start else Arrangement.Center,
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
                                                { PlayerState.skipPrevious() }
                                            } else {
                                                null
                                            },
                                        onTogglePlay = { PlayerState.togglePlay() },
                                        onSkipNext = { PlayerState.skipNext() },
                                        sideButtonColor = MaterialTheme.colorScheme.onSurface,
                                        buttonSize = if (isCompact) 52.dp else 60.dp,
                                        playButtonSize = if (isCompact) 56.dp else 64.dp,
                                        iconSize = if (isCompact) 40.dp else 48.dp,
                                        playIconSize = if (isCompact) 46.dp else 56.dp,
                                        spacing = if (isCompact) 12.dp else 20.dp,
                                        modifier =
                                            Modifier.widthIn(
                                                max =
                                                    when {
                                                        isMedium -> 260.dp
                                                        else -> 320.dp
                                                    },
                                            ),
                                    )
                                } else {
                                    PlayerPrimaryControls(
                                        isPlaying = PlayerState.isPlaying,
                                        isLoading = PlayerState.isPlaybackLoading,
                                        playText = stringResource(Res.string.play),
                                        pauseText = stringResource(Res.string.pause),
                                        onSkipPrevious =
                                            if (PlayerState.canSkipPrevious) {
                                                { PlayerState.skipPrevious() }
                                            } else {
                                                null
                                            },
                                        onTogglePlay = { PlayerState.togglePlay() },
                                        onSkipNext = { PlayerState.skipNext() },
                                        modifier =
                                            Modifier.widthIn(
                                                max =
                                                    when {
                                                        isMedium -> 220.dp
                                                        else -> 280.dp
                                                    },
                                            ),
                                        buttonHeight = controlsButtonHeight,
                                        showLabels = isWide,
                                        sideButtonContainerColor = colorScheme.surfaceContainerHighest,
                                        sideButtonContentColor = colorScheme.onSurfaceVariant,
                                        centerButtonContainerColor = colorScheme.primaryContainer,
                                        centerButtonContentColor = colorScheme.onPrimaryContainer,
                                        useRoundedRect = Settings.shared.useRoundedRectPlayButton,
                                    )
                                }
                            }
                        }
                    }

                    val songInfoWeight =
                        when {
                            isMedium || isExpanded -> 1.2f
                            else -> 1f
                        }
                    val controlsWeight =
                        when {
                            isMedium -> 1.1f
                            else -> 1f
                        }
                    val actionsWeight = if (swapControls && isMedium) 1.8f else 1f

                    if (swapControls) {
                        controlsSection(Modifier.weight(controlsWeight))
                        Spacer(Modifier.width(sectionGap))
                        songInfoSection(Modifier.weight(songInfoWeight), true)
                        Spacer(Modifier.width(sectionGap))
                    } else {
                        songInfoSection(Modifier.weight(1f), false)
                        Spacer(Modifier.width(sectionGap))
                        controlsSection(Modifier)
                        Spacer(Modifier.width(sectionGap))
                    }

                    Row(
                        modifier = Modifier.weight(actionsWeight).fillMaxHeight(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.End,
                    ) {
                        PlayerConnectedButtonGroup(
                            onQueueClick = UiState::togglePersistentPlayerDrawer,
                            onLyricsClick = {
                                LyricsState.toggleLyricsDrawer()
                            },
                            isLyricsActive = UiState.showLyricsDrawer,
                            isQueueActive = UiState.showPersistentPlayerDrawer,
                            modifier = Modifier.widthIn(max = 300.dp),
                            compact = true,
                            volume = PlayerState.volume,
                            onVolumeChange = { PlayerState.setVolumeLevel(it) },
                            onMiniplayerClick = { PlayerState.showMiniplayer = !PlayerState.showMiniplayer },
                            showMiniplayer = PlayerState.showMiniplayer,
                            onFullscreenClick = toggleWindowFullscreen,
                            isFullscreen = PlayerState.isFullscreen,
                            onShuffleClick = if (!isListenTogetherGuest) ({ PlayerState.toggleShuffle() }) else null,
                            isShuffled = PlayerState.shuffleEnabled,
                            onRepeatClick = if (!isListenTogetherGuest) ({ PlayerState.cycleRepeatMode() }) else null,
                            repeatMode = PlayerState.repeatMode,
                            onVideoClick =
                                if (shouldShowVideoButton()) {
                                    { PlayerState.requestVideoMode() }
                                } else {
                                    null
                                },
                            isVideoActive = UiState.showVideoViewer,
                            onCastClick =
                                if (!isListenTogetherGuest) {
                                    { showCastDialog = true }
                                } else {
                                    null
                                },
                            isCasting = castState.isConnected,
                            onDeviceConnectClick = UiState::toggleDeviceConnectPanel,
                            isDeviceConnectActive =
                                UiState.showDeviceConnectPanel || deviceConnectState.sessionId != null,
                        )
                    }
                }
            }
        }
    }

    PopupVisibility(visible = showCastDialog) {
        DesktopCastDialog(onDismiss = { showCastDialog = false })
    }

    PopupVisibility(visible = showSleepDialog) {
        SleepTimerDialog(onDismiss = { showSleepDialog = false })
    }

    PopupVisibility(visible = showDetailsDialog) {
        MediaDetailsDialog(
            song = PlayerState.currentTrack,
            onDismiss = { showDetailsDialog = false },
        )
    }
}

internal fun shouldShowVideoButton(): Boolean {
    val track = PlayerState.currentTrack
    if (PlayerState.isListenTogetherGuest()) return false
    if (Settings.shared.hideVideoSongs || !Settings.shared.musicVideoPlaybackEnabled) return false
    if (track == null) return false
    if (track.id.startsWith("local_")) return false
    return true
}

@Composable
internal fun MediaDetailsDialog(
    song: SongItem?,
    onDismiss: () -> Unit,
) {
    val playbackResource by PlayerState.player.currentPlaybackResource.collectAsState()
    val stream = playbackResource?.takeIf { it.videoId == song?.id }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(Res.string.media_details),
                style = MaterialTheme.typography.titleLarge,
            )
        },
        text = {
            SongDetailsContent(
                song = song,
                database = remember { GlobalContext.get().get<MusicDatabase>() },
                playbackResource = stream,
                volume = PlayerState.volume,
                modifier = Modifier.heightIn(max = 400.dp),
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) {
                Text(stringResource(Res.string.close))
            }
        },
    )
}
