@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.desktop.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metrolist.desktop.deviceconnect.DesktopDeviceConnect
import com.metrolist.desktop.state.app.AppState
import com.metrolist.desktop.state.app.UiState
import com.metrolist.desktop.state.content.ArtistState
import com.metrolist.desktop.state.content.PlaylistAlbumState
import com.metrolist.desktop.state.playback.ListenTogetherState
import com.metrolist.desktop.state.playback.LyricsState
import com.metrolist.desktop.state.playback.PlayerState
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.cd_playback_position
import com.metrolist.shared.generated.resources.more
import com.metrolist.shared.generated.resources.next
import com.metrolist.shared.generated.resources.not_playing
import com.metrolist.shared.generated.resources.pause
import com.metrolist.shared.generated.resources.play
import com.metrolist.shared.generated.resources.previous
import com.metrolist.shared.generated.resources.view_album
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.isLive
import com.metrolist.shared.model.previewAspectRatio
import com.metrolist.shared.settings.PlayerBackgroundStyle
import com.metrolist.shared.settings.PlayerType
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.ui.components.LiveBadge
import com.metrolist.shared.ui.components.PlayerConnectedButtonGroup
import com.metrolist.shared.ui.components.PlayerLikeShareButtons
import com.metrolist.shared.ui.components.PlayerPrimaryControls
import com.metrolist.shared.ui.components.PopupVisibility
import com.metrolist.shared.ui.components.VintagePlayerControls
import com.metrolist.shared.ui.components.VintagePlayerControlsText
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.menu.cursorContextMenu
import com.metrolist.shared.ui.menu.rememberCursorContextMenuState
import com.metrolist.shared.ui.navigation.navigationPopupSource
import com.metrolist.shared.ui.player.PlayerArtistLine
import com.metrolist.shared.ui.utils.verticalScrollWithDrag
import com.metrolist.shared.utils.ShareUrls
import com.metrolist.shared.utils.formatDuration
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds

@Composable
fun PlayerContentColumn(
    currentTrack: SongItem?,
    isPlaying: Boolean,
    availableHeight: Dp,
    scale: Float,
    modifier: Modifier = Modifier,
    toggleWindowFullscreen: () -> Unit,
) {
    // verticalScroll measures unbounded, so stretch to the viewport to keep Arrangement.Center working.
    Column(
        modifier =
            modifier
                .verticalScrollWithDrag(rememberScrollState())
                .heightIn(min = availableHeight)
                .padding(top = 78.dp * scale, bottom = 62.dp * scale),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        PlayerCoreContent(
            currentTrack = currentTrack,
            isPlaying = isPlaying,
            availableHeight = (availableHeight - 140.dp * scale).coerceAtLeast(0.dp),
            scale = scale,
            toggleWindowFullscreen = toggleWindowFullscreen,
        )
    }
}

@Composable
fun PlayerCoreContent(
    currentTrack: SongItem?,
    isPlaying: Boolean,
    availableHeight: Dp,
    scale: Float,
    toggleWindowFullscreen: () -> Unit,
) {
    val playerMenu = rememberCursorContextMenuState()
    val castState by PlayerState.castManager.state.collectAsState()
    val isListenTogetherGuest = ListenTogetherState.isListenTogetherGuest()
    var showCastDialog by remember { mutableStateOf(false) }
    var showDetailsDialog by remember { mutableStateOf(false) }
    var showSleepDialog by remember { mutableStateOf(false) }

    val artworkRatio =
        if (PlayerState.animatedCoverUrl != null) 1f else (currentTrack?.previewAspectRatio ?: 1f).coerceAtLeast(1f)

    var targetArtworkHeight by remember { mutableStateOf<Dp?>(null) }
    val animatedArtworkHeight =
        targetArtworkHeight?.let { height ->
            animateDpAsState(
                targetValue = height,
                animationSpec = tween(400, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)),
                label = "animatedArtworkSize",
            )
        }

    val content = @Composable {
        Box(
            modifier = Modifier.desktopPlayerArtwork(currentTrack?.id),
            contentAlignment = Alignment.Center,
        ) {
            var hasAnimatedFrame by remember(PlayerState.animatedCoverUrl) { mutableStateOf(false) }
            AsyncImage(
                url = PlayerState.currentArtworkUrl ?: "",
                artworkPreview = PlayerState.artworkPreview,
                modifier =
                    Modifier
                        .fillMaxSize()
                        .alpha(if (hasAnimatedFrame) 0f else 1f)
                        .clip(RoundedCornerShape(28.dp))
                        .shadow(24.dp, RoundedCornerShape(28.dp)),
                extractColor = true,
                contentScale = if (Settings.shared.cropAlbumArt) ContentScale.Crop else ContentScale.Fit,
            )
            PlayerState.animatedCoverUrl?.let { coverUrl ->
                AnimatedCover(
                    url = coverUrl,
                    active = PlayerState.isPlaying && !UiState.videoFullscreenObscuresPlayer,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(28.dp)),
                    contentScale = ContentScale.Crop,
                    onFrameAvailabilityChanged = { hasAnimatedFrame = it },
                )
            }
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.widthIn(max = 650.dp * scale).fillMaxWidth(),
        ) {
            ActiveLyricsLine(modifier = Modifier.fillMaxWidth().padding(top = 8.dp))

            Spacer(Modifier.height(54.dp * scale))

            val isSolid = Settings.shared.playerBackgroundStyle == PlayerBackgroundStyle.SOLID
            val textBackgroundColor by animateColorAsState(
                targetValue = if (isSolid) MaterialTheme.colorScheme.onSurface else Color.White,
                label = "PlayerContentTextColor",
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        currentTrack?.title ?: stringResource(Res.string.not_playing),
                        style = MaterialTheme.typography.headlineMedium.copy(fontSize = 24.sp, lineHeight = 32.sp),
                        fontWeight = FontWeight.Bold,
                        color = textBackgroundColor,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable(
                                    enabled = currentTrack != null,
                                    onClickLabel = stringResource(Res.string.view_album),
                                ) {
                                    currentTrack?.let { song ->
                                        PlayerState.exitExpanded()
                                        PlaylistAlbumState.openSongAlbum(song)
                                    }
                                }.basicMarquee(
                                    iterations = if (UiState.videoFullscreenObscuresPlayer) 0 else 1,
                                    initialDelayMillis = 2000,
                                    velocity = 30.dp,
                                ),
                    )
                    Spacer(Modifier.height(2.dp))
                    PlayerArtistLine(
                        artists = currentTrack?.artists,
                        onNavigateToArtist = { id -> ArtistState.fetchArtistData(id) },
                        fallbackText = "",
                        iterations = 1,
                        initialDelayMillis = 2000,
                        style = MaterialTheme.typography.titleMedium.copy(fontSize = 12.sp, lineHeight = 16.sp),
                        color = textBackgroundColor.copy(alpha = 0.8f),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Spacer(Modifier.width(16.dp))

                Box {
                    PlayerLikeShareButtons(
                        isLiked = currentTrack?.let { PlayerState.isLiked(it.id) } == true,
                        onLikeClick = { currentTrack?.let { PlayerState.toggleLike(it) } },
                        onShareClick = {
                            currentTrack?.let { track ->
                                AppState.copyToClipboard(ShareUrls.song(track.id))
                            }
                        },
                        groupHeight = 40.dp,
                        buttonWidth = 48.dp,
                        iconSize = 20.dp,
                        onMoreClick = playerMenu::openAnchored,
                        isMoreActive = playerMenu.expanded,
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
                            showDetailsDialog = true
                        },
                        onShowSleepTimerDialog = {
                            playerMenu.dismiss()
                            showSleepDialog = true
                        },
                    )
                }
            }

            Spacer(Modifier.height(8.dp * scale))

            PlayerProgressSection()

            if (!isListenTogetherGuest) {
                Spacer(Modifier.height(14.dp * scale))

                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
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
                            isPlaying = isPlaying,
                            isLoading = PlayerState.isPlaybackLoading,
                            onSkipPrevious =
                                if (PlayerState.canSkipPrevious) {
                                    { PlayerState.skipPrevious() }
                                } else {
                                    null
                                },
                            onTogglePlay = { PlayerState.togglePlay() },
                            onSkipNext = { PlayerState.skipNext() },
                            sideButtonColor = textBackgroundColor,
                            buttonSize = 80.dp,
                            playButtonSize = 88.dp,
                            iconSize = 64.dp,
                            playIconSize = 72.dp,
                            spacing = 28.dp,
                            modifier = Modifier.widthIn(max = 340.dp),
                        )
                    } else {
                        PlayerPrimaryControls(
                            isPlaying = isPlaying,
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
                            buttonHeight = (65.dp * scale).coerceAtLeast(52.dp),
                            showLabels = true,
                            sideButtonContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            sideButtonContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            centerButtonContainerColor = MaterialTheme.colorScheme.primary,
                            centerButtonContentColor = MaterialTheme.colorScheme.onPrimary,
                            useRoundedRect = Settings.shared.useRoundedRectPlayButton,
                            modifier = Modifier.width(298.dp * scale.coerceAtLeast(0.8f)),
                        )
                    }
                }
            }

            Spacer(Modifier.height(42.dp * scale))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PlayerConnectedButtonGroup(
                    modifier = Modifier.weight(1f),
                    sectioned = true,
                    touchTarget = 40.dp,
                    onShuffleClick = if (!isListenTogetherGuest) ({ PlayerState.toggleShuffle() }) else null,
                    isShuffled = PlayerState.shuffleEnabled,
                    onRepeatClick = if (!isListenTogetherGuest) ({ PlayerState.cycleRepeatMode() }) else null,
                    repeatMode = PlayerState.repeatMode,
                    volume = PlayerState.volume,
                    onVolumeChange = { PlayerState.setVolumeLevel(it) },
                    onQueueClick = UiState::togglePersistentPlayerDrawer,
                    onLyricsClick = {
                        LyricsState.toggleLyricsDrawer()
                    },
                    isLyricsActive = UiState.showLyricsDrawer,
                    isQueueActive = UiState.showPersistentPlayerDrawer,
                    onVideoClick =
                        if (shouldShowVideoButton()) {
                            PlayerState::toggleFullscreenVideo
                        } else {
                            null
                        },
                    isVideoActive = false,
                    onSubtitleClick =
                        if (PlayerState.isVideoActive) {
                            {
                                PlayerState.isSubtitleActive = !PlayerState.isSubtitleActive
                            }
                        } else {
                            null
                        },
                    isSubtitleActive = PlayerState.isSubtitleActive,
                    onCastClick = if (!isListenTogetherGuest) ({ showCastDialog = true }) else null,
                    isCasting = castState.isConnected,
                    onFullscreenClick = toggleWindowFullscreen,
                    isFullscreen = PlayerState.isFullscreen || UiState.isNativeFullscreen,
                )
            }
        }
    }
    Layout(
        content = content,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .cursorContextMenu(playerMenu),
    ) { measurables, constraints ->
        val controls = measurables[1].measure(constraints.copy(minWidth = 0, minHeight = 0, maxHeight = Constraints.Infinity))
        val targetArtworkSize =
            fullscreenArtworkSize(
                constraints.maxWidth,
                availableHeight.roundToPx(),
                controls.height,
                artworkRatio,
                (556.dp * scale).roundToPx(),
            )
        targetArtworkHeight = targetArtworkSize.height.takeIf { it > 0 }?.toDp()
        val artworkSize =
            fullscreenArtworkSize(
                constraints.maxWidth,
                availableHeight.roundToPx(),
                controls.height,
                artworkRatio,
                animatedArtworkHeight?.value?.roundToPx() ?: targetArtworkSize.height,
            )
        val artwork = measurables[0].measure(Constraints.fixed(artworkSize.width, artworkSize.height))
        layout(constraints.maxWidth, artwork.height + controls.height) {
            artwork.placeRelative((constraints.maxWidth - artwork.width) / 2, 0)
            controls.placeRelative((constraints.maxWidth - controls.width) / 2, artwork.height)
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

@Composable
fun PlayerProgressSection() {
    val position = rememberPlaybackSecondPosition()
    val bufferedPosition by PlayerState.playbackBufferedPosition.collectAsSecondsState()
    val duration by PlayerState.playbackDuration.collectAsState()
    val isPlaying = PlayerState.isPlaying
    val isListenTogetherGuest = ListenTogetherState.isListenTogetherGuest()
    val playbackPositionDescription = stringResource(Res.string.cd_playback_position)

    val isLive = playerIsLiveStream()
    var sliderWidthPx by remember { mutableIntStateOf(0) }
    val smoothPosition = rememberPlayerProgressPosition(duration, sliderWidthPx, isLive)
    // Read the initial slider value without subscribing this row to position samples.
    val sampledProgress = if (duration > 0L) PlayerState.playbackPosition.value.toFloat() / duration else 0f
    val maxProgress = if (PlayerState.isCrossfading) 0.995f else 1f
    val bufferedProgress = if (duration > 0) (bufferedPosition.toFloat() / duration.toFloat()).coerceIn(0f, 1f) else 0f

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

    val displayPosition = {
        if (isDragging || pendingSeekTargetMs != null) (sliderValue * duration).toLong() else position.value
    }

    val isSolid = Settings.shared.playerBackgroundStyle == PlayerBackgroundStyle.SOLID
    val textSecondaryColor by animateColorAsState(
        targetValue = if (isSolid) MaterialTheme.colorScheme.onSurfaceVariant else Color.White.copy(alpha = 0.7f),
        label = "PlayerProgressDurationColor",
    )

    val canSeek = !isListenTogetherGuest && !isLive

    Column(
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (isLive) {
            LiveProgressBar(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary,
            )
        } else {
            PlayerSlider(
                value = if (isDragging || pendingSeekTargetMs != null) sliderValue else sampledProgress.coerceIn(0f, maxProgress),
                valueProvider = {
                    when {
                        isDragging || pendingSeekTargetMs != null -> sliderValue
                        duration > 0L -> (smoothPosition.longValue.toFloat() / duration.toFloat()).coerceIn(0f, maxProgress)
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
                        val targetMs = (sliderValue * duration).toLong()
                        PlayerState.seekTo(sliderValue)
                        isDragging = false
                        pendingSeekTargetMs = targetMs
                    }
                },
                style = Settings.shared.sliderStyle,
                isPlaying = isPlaying,
                color = MaterialTheme.colorScheme.primary,
                bufferedValue = bufferedProgress,
                enabled = canSeek,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .onSizeChanged { sliderWidthPx = it.width }
                        .semantics {
                            contentDescription = playbackPositionDescription
                        },
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isLive) {
                LiveBadge()
                PlayerProgressTime(
                    positionProvider = displayPosition,
                    style = MaterialTheme.typography.bodySmall,
                    color = textSecondaryColor,
                )
            } else {
                PlayerProgressTime(
                    positionProvider = displayPosition,
                    style = MaterialTheme.typography.bodySmall,
                    color = textSecondaryColor,
                )
                Text(formatDuration(duration), style = MaterialTheme.typography.bodySmall, color = textSecondaryColor)
            }
        }
    }
}
