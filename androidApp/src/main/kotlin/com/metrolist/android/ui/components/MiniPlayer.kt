/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.android.ui.components

import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import androidx.media3.common.util.UnstableApi
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.metrolist.android.LocalListenTogetherManager
import com.metrolist.android.LocalPlaylistAdder
import com.metrolist.android.playback.LocalPlayerConnection
import com.metrolist.android.ui.player.MusicVideoSurface
import com.metrolist.android.ui.theme.PlayerColorExtractor
import com.metrolist.shared.constants.MiniPlayerHeight
import com.metrolist.shared.constants.ModernMiniPlayerHorizontalPadding
import com.metrolist.shared.constants.modernMiniPlayerHeight
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.error_occurred
import com.metrolist.shared.generated.resources.loading
import com.metrolist.shared.generated.resources.mute
import com.metrolist.shared.generated.resources.next
import com.metrolist.shared.generated.resources.pause
import com.metrolist.shared.generated.resources.play
import com.metrolist.shared.generated.resources.previous
import com.metrolist.shared.generated.resources.subscribed_to_artist
import com.metrolist.shared.generated.resources.unknown
import com.metrolist.shared.generated.resources.unmute
import com.metrolist.shared.listentogether.ListenTogetherRoomRole
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.isLive
import com.metrolist.shared.model.previewThumbnailUrl
import com.metrolist.shared.playback.interpolatePlaybackPosition
import com.metrolist.shared.settings.MiniPlayerType
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.settings.displayName
import com.metrolist.shared.state.GlobalYouTubeRepository
import com.metrolist.shared.state.SongLikeState
import com.metrolist.shared.state.SongMetadataState
import com.metrolist.shared.state.rememberArtistSubscription
import com.metrolist.shared.state.toggleArtistSubscription
import com.metrolist.shared.ui.LocalIsTv
import com.metrolist.shared.ui.components.BottomSheetState
import com.metrolist.shared.ui.components.ContainedLoadingIndicator
import com.metrolist.shared.ui.components.CrossfadeArtwork
import com.metrolist.shared.ui.components.LiveBadge
import com.metrolist.shared.ui.components.MiniPlayerActions
import com.metrolist.shared.ui.components.PlayerSheetSurface
import com.metrolist.shared.ui.components.focusOutline
import com.metrolist.shared.ui.components.images.AsyncImage
import com.metrolist.shared.ui.components.rememberTrackMarquee
import com.metrolist.shared.ui.components.sliders.collectPlaybackDisplayState
import com.metrolist.shared.ui.components.sliders.progressAtPixel
import com.metrolist.shared.ui.design.expressive.components.ExpressiveMiniPlayer
import com.metrolist.shared.ui.design.expressive.components.ExpressiveMiniPlayerHeight
import com.metrolist.shared.ui.haptics.HapticEvent
import com.metrolist.shared.ui.haptics.MetrolistHaptics
import com.metrolist.shared.ui.haptics.rememberMetrolistHaptics
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.player.shouldShowMusicVideoSurface
import com.metrolist.shared.ui.tv.tvFocusHighlight
import com.metrolist.shared.ui.utils.nativeBlurCompat
import com.metrolist.shared.ui.utils.supportsNativeBlur
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import timber.log.Timber
import kotlin.math.PI
import kotlin.math.absoluteValue
import kotlin.math.roundToInt

private val MiniPlayerProgressDiameter = 48.dp

@UnstableApi
@Composable
fun MiniPlayer(
    useDesktopLayout: Boolean,
    modifier: Modifier = Modifier,
    onExpand: () -> Unit = {},
    onDpadExpand: () -> Unit = onExpand,
    bottomSheetState: BottomSheetState? = null,
    cardFocusRequester: FocusRequester? = null,
) {
    val playerConnection = LocalPlayerConnection.current ?: return

    val currentSong by playerConnection.currentSong.collectAsState(initial = null)
    val isPlaying by playerConnection.isPlaying.collectAsState(initial = false)
    val volume by playerConnection.volume.collectAsState(initial = 1f)
    val listenTogetherManager = LocalListenTogetherManager.current
    val listenTogetherRole by listenTogetherManager?.role?.collectAsState()
        ?: remember { mutableStateOf(ListenTogetherRoomRole.NONE) }
    val isInListenTogetherRoom by listenTogetherManager?.isInRoom?.collectAsState()
        ?: remember { mutableStateOf(false) }
    val isListenTogetherGuest = isInListenTogetherRoom && listenTogetherRole == ListenTogetherRoomRole.GUEST
    val isMuted = volume <= 0.01f
    val isPlaybackLoading by playerConnection.isPlaybackLoading.collectAsState(initial = false)
    val playbackResource by playerConnection.currentPlaybackResource.collectAsState(initial = null)
    val isLive = currentSong?.isLive == true || playbackResource?.isLive == true
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val miniPlayerActive = bottomSheetState?.isCollapsed != false && lifecycleState == Lifecycle.State.RESUMED
    val isExpressiveMiniPlayer = Settings.shared.miniPlayerType == MiniPlayerType.EXPRESSIVE && !useDesktopLayout
    val isModern = Settings.shared.miniPlayerType == MiniPlayerType.MODERN && !useDesktopLayout
    val expressiveProgressEnabled = isExpressiveMiniPlayer && Settings.shared.showExpressiveMiniPlayerProgress
    val progressActive = miniPlayerActive && (!isExpressiveMiniPlayer || expressiveProgressEnabled) && !useDesktopLayout && !isLive
    var progressWidthPx by remember { mutableIntStateOf(0) }
    val progressLengthPx =
        if (isModern || expressiveProgressEnabled) {
            (with(LocalDensity.current) { MiniPlayerProgressDiameter.toPx() } * PI).roundToInt()
        } else {
            progressWidthPx
        }
    val duration by playerConnection.duration.collectAsState(initial = 0L)
    val playbackSpeed by playerConnection.playbackSpeedFlow.collectPlaybackDisplayState(progressActive) { it }
    val isCasting by playerConnection.castState.collectPlaybackDisplayState(progressActive) { it.isConnected }
    val interpolationSpeed = if (isCasting) 1f else playbackSpeed
    val isProgressAnimating = progressActive && duration > 0L && isPlaying && !isPlaybackLoading
    val progress =
        key(
            playerConnection,
            currentSong?.id,
            progressActive,
            isLive,
            duration,
            interpolationSpeed,
            isProgressAnimating,
            progressLengthPx,
        ) {
            fun positionProgress(position: Long): Float =
                if (isLive) {
                    1f
                } else if (duration > 0L) {
                    progressAtPixel(position.toFloat() / duration, progressLengthPx)
                } else {
                    0f
                }

            produceState(remember { positionProgress(playerConnection.currentPosition.value) }) {
                if (!progressActive || duration <= 0L) return@produceState
                if (isProgressAnimating) {
                    var sampledPosition = playerConnection.currentPosition.value
                    var sampleFrameNanos = 0L
                    while (true) {
                        withFrameNanos { frameNanos ->
                            val newSample = playerConnection.currentPosition.value
                            if (newSample != sampledPosition || sampleFrameNanos == 0L) {
                                sampledPosition = newSample
                                sampleFrameNanos = frameNanos
                            }
                            value =
                                positionProgress(
                                    interpolatePlaybackPosition(
                                        sampledPositionMs = sampledPosition,
                                        elapsedRealtimeMs = (frameNanos - sampleFrameNanos) / 1_000_000L,
                                        playbackSpeed = interpolationSpeed,
                                        durationMs = duration,
                                    ),
                                )
                        }
                    }
                } else {
                    playerConnection.currentPosition.collect { value = positionProgress(it) }
                }
            }
        }
    val bufferedProgress =
        playerConnection.bufferedProgress.collectPlaybackDisplayState(progressActive && !isModern && !isExpressiveMiniPlayer) {
            if (isLive) 1f else progressAtPixel(it, progressLengthPx)
        }
    val canSkipNext by playerConnection.canSkipNext.collectAsState(initial = false)
    val canSkipPrevious by playerConnection.canSkipPrevious.collectAsState(initial = false)
    val crossfadeState = if (miniPlayerActive) playerConnection.crossfadeState.collectAsState() else remember { mutableStateOf(null) }
    val isVideoModeActive by playerConnection.isVideoModeActive.collectAsState()
    val isVideoViewerVisible by playerConnection.isVideoViewerVisible.collectAsState()

    if (currentSong == null) return
    val metadataRevision by SongMetadataState.revision.collectAsState()
    val song = remember(currentSong, metadataRevision) { SongMetadataState.apply(currentSong!!) }
    val nextArtworkUrl by remember(crossfadeState, song.id) {
        derivedStateOf {
            val transition = crossfadeState.value ?: return@derivedStateOf null
            if (transition.progress >= 1f) {
                null
            } else if (transition.nextSong.id == song.id) {
                transition.outgoingSong?.previewThumbnailUrl
            } else {
                transition.nextSong.previewThumbnailUrl
            }
        }
    }
    val artworkCrossfadeProgress =
        remember(crossfadeState, song.id) {
            {
                val transition = crossfadeState.value
                if (transition?.nextSong?.id == song.id) 1f - transition.progress else transition?.progress ?: 0f
            }
        }

    val coroutineScope = rememberCoroutineScope()
    val hapticManager = rememberMetrolistHaptics()
    val context = LocalContext.current

    val layoutDirection = LocalLayoutDirection.current
    val dragOffset = remember { mutableStateOf(Offset.Zero) }
    var offsetResetJob by remember { mutableStateOf<Job?>(null) }
    val animationSpec = remember { spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessLow) }

    fun resetDragOffset() {
        offsetResetJob?.cancel()
        val startOffset = dragOffset.value
        offsetResetJob =
            coroutineScope.launch {
                Animatable(0f).animateTo(1f, animationSpec) {
                    dragOffset.value = startOffset * (1f - value)
                }
                dragOffset.value = Offset.Zero
            }
    }

    // At 73 percent sensitivity the multiplier is 1; it ranges from 1.73 at 0 to 0.73 at 100.
    val sensitivityFactor = (173f - Settings.shared.swipeSensitivity) / 100f
    val swipeThreshold = with(LocalDensity.current) { 100.dp.toPx() } * sensitivityFactor

    val marqueeModifier = rememberTrackMarquee(song.id, miniPlayerActive, 10_000L)
    val arcAlpha =
        animateFloatAsState(
            targetValue = if (isPlaying) 1f else 0.5f,
            label = "arcAlpha",
        )

    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(
                    if (useDesktopLayout) {
                        DesktopMiniPlayerHeight
                    } else if (isExpressiveMiniPlayer) {
                        ExpressiveMiniPlayerHeight
                    } else if (isModern) {
                        modernMiniPlayerHeight(LocalDensity.current)
                    } else {
                        MiniPlayerHeight
                    },
                ).then(
                    if (isModern || isExpressiveMiniPlayer) {
                        Modifier
                            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                            .padding(horizontal = ModernMiniPlayerHorizontalPadding)
                    } else {
                        Modifier
                    },
                ).miniPlayerGestures(
                    layoutDirection = layoutDirection,
                    hapticManager = hapticManager,
                    dragOffset = dragOffset,
                    onDragStart = {
                        offsetResetJob?.cancel()
                        dragOffset.value = Offset.Zero
                    },
                    onResetDragOffset = ::resetDragOffset,
                    swipeThreshold = swipeThreshold,
                    canSkipNext = canSkipNext && !isListenTogetherGuest,
                    canSkipPrevious = canSkipPrevious && !isListenTogetherGuest,
                    onSkipNext = { playerConnection.skipToNext() },
                    onSkipPrevious = { playerConnection.skipToPrevious() },
                ),
    ) {
        if (isExpressiveMiniPlayer) {
            val database: MusicDatabase = koinInject()
            val artist = song.artists.firstOrNull { !it.id.isNullOrBlank() }
            val artistId = artist?.id
            val isArtistSubscribed =
                if (miniPlayerActive && Settings.shared.showExpressiveMiniPlayerSubscribeButton) {
                    rememberArtistSubscription(database, artistId)
                } else {
                    false
                }
            val subscribedMessage = stringResource(Res.string.subscribed_to_artist, artist?.displayName.orEmpty())
            val subscriptionError = stringResource(Res.string.error_occurred)
            val addToPlaylist = LocalPlaylistAdder.current
            ExpressiveMiniPlayer(
                song = song,
                isPlaying = isPlaying,
                isLoading = isPlaybackLoading && miniPlayerActive,
                active = miniPlayerActive,
                onTogglePlay = {
                    hapticManager.perform(HapticEvent.PRESS)
                    if (isListenTogetherGuest) {
                        playerConnection.setVolume(if (volume > 0.01f) 0f else 1f)
                    } else {
                        playerConnection.togglePlayPause()
                    }
                },
                onSkipNext = {
                    hapticManager.perform(HapticEvent.PRESS)
                    playerConnection.skipToNext()
                },
                onSkipPrevious = { playerConnection.skipToPrevious() },
                onExpand = {
                    hapticManager.perform(HapticEvent.PRESS)
                    onExpand()
                },
                canSkipNext = canSkipNext && !isListenTogetherGuest,
                canSkipPrevious = canSkipPrevious && !isListenTogetherGuest,
                playbackEnabled = true,
                swipeThresholdPx = swipeThreshold,
                isVisible = true,
                modifier =
                    Modifier
                        .then(if (cardFocusRequester != null) Modifier.focusRequester(cardFocusRequester) else Modifier)
                        .tvFocusHighlight(scale = 1f)
                        .graphicsLayer { translationX = dragOffset.value.x },
                onDpadExpand = {
                    hapticManager.perform(HapticEvent.PRESS)
                    onDpadExpand()
                },
                playbackActionLabel =
                    if (isListenTogetherGuest) {
                        stringResource(
                            if (isMuted) Res.string.unmute else Res.string.mute,
                        )
                    } else {
                        null
                    },
                playbackActionIcon =
                    if (isListenTogetherGuest) {
                        if (isMuted) MetrolistIcons.AutoMirrored.Filled.VolumeUp else MetrolistIcons.AutoMirrored.Filled.VolumeOff
                    } else {
                        null
                    },
                swipeEnabled = false,
                progress = { progress.value },
                progressAvailable = !isLive && duration > 0L,
                isLiked = SongLikeState.isLiked(song.id),
                isArtistSubscribed = isArtistSubscribed,
                onLike = { SongLikeState.toggleLike(song) },
                onSubscribeToggle =
                    if (artistId != null) {
                        {
                            toggleArtistSubscription(
                                coroutineScope,
                                database,
                                GlobalYouTubeRepository.instance,
                                artistId,
                                artist.displayName,
                                onSubscriptionChanged = { subscribed ->
                                    if (subscribed) Toast.makeText(context, subscribedMessage, Toast.LENGTH_SHORT).show()
                                },
                                onError = { Toast.makeText(context, subscriptionError, Toast.LENGTH_SHORT).show() },
                            )
                        }
                    } else {
                        null
                    },
                onAddToPlaylist = { addToPlaylist(listOf(song)) },
            )
            return
        }
        MiniPlayerCard(
            useDesktopLayout = useDesktopLayout,
            currentSong = song,
            isLive = isLive,
            modifier = Modifier.onSizeChanged { progressWidthPx = it.width },
            marqueeModifier = marqueeModifier,
            nextArtworkUrl = nextArtworkUrl,
            active = miniPlayerActive,
            artworkCrossfadeProgress = artworkCrossfadeProgress,
            isPlaying = isPlaying,
            isPlaybackLoading = isPlaybackLoading && miniPlayerActive,
            progress = { progress.value },
            bufferedProgress = { bufferedProgress.value },
            arcAlpha = arcAlpha,
            dragOffset = dragOffset,
            onExpand = {
                hapticManager.perform(HapticEvent.PRESS)
                onExpand()
            },
            onDpadExpand = {
                hapticManager.perform(HapticEvent.PRESS)
                onDpadExpand()
            },
            onTogglePlayPause = {
                hapticManager.toggle(if (isListenTogetherGuest) isMuted else !isPlaying)
                if (isListenTogetherGuest) {
                    playerConnection.setVolume(if (volume > 0.01f) 0f else 1f)
                } else {
                    playerConnection.togglePlayPause()
                }
            },
            canSkipPrevious = canSkipPrevious && !isListenTogetherGuest,
            onSkipPrevious = { playerConnection.skipToPrevious() },
            canSkipNext = canSkipNext && !isListenTogetherGuest,
            onSkipNext = {
                hapticManager.perform(HapticEvent.PRESS)
                playerConnection.skipToNext()
            },
            isListenTogetherGuest = isListenTogetherGuest,
            isMuted = isMuted,
            onLike = { songId ->
                hapticManager.toggle(!SongLikeState.isLiked(songId))
                SongLikeState.toggleLike(song)
            },
            videoPlayer = playerConnection.videoPlayer,
            showVideo =
                shouldShowMusicVideoSurface(
                    isVideoModeActive = isVideoModeActive,
                    isVideoViewerVisible = isVideoViewerVisible,
                    hasVideo = playbackResource?.hasVideo == true,
                    presentationAllowed =
                        Settings.shared.playVideosInMiniPlayer &&
                            miniPlayerActive,
                ),
            focusRequester = cardFocusRequester,
        )
    }
}

private fun Modifier.miniPlayerGestures(
    layoutDirection: LayoutDirection,
    hapticManager: MetrolistHaptics,
    dragOffset: MutableState<Offset>,
    onDragStart: () -> Unit,
    onResetDragOffset: () -> Unit,
    swipeThreshold: Float,
    canSkipNext: Boolean,
    canSkipPrevious: Boolean,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
) = pointerInput(layoutDirection, swipeThreshold, canSkipNext, canSkipPrevious) {
    var accumulatedX = 0f

    detectHorizontalDragGestures(
        onDragStart = {
            accumulatedX = 0f
            onDragStart()
        },
        onDragCancel = {
            onResetDragOffset()
        },
        onHorizontalDrag = { change, dragAmount ->
            val adjustedDragAmount =
                if (layoutDirection == LayoutDirection.Rtl) -dragAmount else dragAmount
            val tryingToSwipeRight = adjustedDragAmount > 0
            val tryingToSwipeLeft = adjustedDragAmount < 0
            val allowLeft = tryingToSwipeLeft && canSkipNext
            val allowRight = tryingToSwipeRight && canSkipPrevious

            val canReturnToCenter =
                (tryingToSwipeRight && !canSkipPrevious && dragOffset.value.x < 0) ||
                    (tryingToSwipeLeft && !canSkipNext && dragOffset.value.x > 0)

            if (allowLeft || allowRight || canReturnToCenter) {
                accumulatedX += adjustedDragAmount
                dragOffset.value = Offset(accumulatedX, 0f)
                change.consume()
            }
        },
        onDragEnd = {
            onHorizontalDragEnd(
                currentOffset = dragOffset.value.x,
                swipeThreshold = swipeThreshold,
                hapticManager = hapticManager,
                canSkipNext = canSkipNext,
                canSkipPrevious = canSkipPrevious,
                onSkipNext = onSkipNext,
                onSkipPrevious = onSkipPrevious,
            )
            onResetDragOffset()
        },
    )
}

private fun onHorizontalDragEnd(
    currentOffset: Float,
    swipeThreshold: Float,
    hapticManager: MetrolistHaptics,
    canSkipNext: Boolean,
    canSkipPrevious: Boolean,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
) {
    if (currentOffset.absoluteValue > swipeThreshold) {
        if (currentOffset > 0 && canSkipPrevious) {
            Timber.tag("Haptics").d("Trigger MiniPlayer swipe previous")
            hapticManager.perform(HapticEvent.CONFIRM)
            onSkipPrevious()
        } else if (currentOffset < 0 && canSkipNext) {
            Timber.tag("Haptics").d("Trigger MiniPlayer swipe next")
            hapticManager.perform(HapticEvent.CONFIRM)
            onSkipNext()
        }
    }
}

@UnstableApi
@Composable
internal fun MiniPlayerCard(
    useDesktopLayout: Boolean,
    currentSong: SongItem,
    isLive: Boolean = false,
    modifier: Modifier = Modifier,
    marqueeModifier: Modifier,
    nextArtworkUrl: String? = null,
    artworkCrossfadeProgress: () -> Float = { 0f },
    active: Boolean = true,
    isPlaying: Boolean,
    isPlaybackLoading: Boolean,
    progress: () -> Float,
    bufferedProgress: () -> Float = { 0f },
    arcAlpha: State<Float>,
    dragOffset: State<Offset>,
    onExpand: () -> Unit,
    onDpadExpand: () -> Unit,
    canSkipPrevious: Boolean,
    onSkipPrevious: () -> Unit,
    onTogglePlayPause: () -> Unit,
    canSkipNext: Boolean,
    onSkipNext: () -> Unit,
    isListenTogetherGuest: Boolean,
    isMuted: Boolean,
    onLike: (songId: String) -> Unit,
    videoPlayer: androidx.media3.common.Player?,
    showVideo: Boolean,
    focusRequester: FocusRequester?,
) {
    val miniPlayerBlurRadiusPx = with(LocalDensity.current) { 60.dp.toPx() }
    val previousLabel = stringResource(Res.string.previous)
    val nextLabel = stringResource(Res.string.next)
    val isTv = LocalIsTv.current
    val artworkUrl = currentSong.previewThumbnailUrl
    val useBlurMiniPlayer =
        Settings.shared.miniPlayerBackgroundStyle == "BLURRED_ARTWORK" && artworkUrl != null
    val useGradientMiniPlayer =
        Settings.shared.miniPlayerBackgroundStyle == "GRADIENT" && artworkUrl != null
    val useSolidBlackMiniPlayer = Settings.shared.miniPlayerBackgroundStyle == "SOLID_BLACK"
    val context = LocalContext.current
    var gradientColors by remember(artworkUrl) { mutableStateOf<List<Color>>(emptyList()) }
    LaunchedEffect(artworkUrl, useGradientMiniPlayer, active) {
        if (!useGradientMiniPlayer) {
            gradientColors = emptyList()
        } else if (active) {
            gradientColors =
                try {
                    PlayerColorExtractor.extractColors(context, context.imageLoader, artworkUrl, Color.Black)?.gradient.orEmpty()
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    emptyList()
                }
        }
    }
    val backgroundColor = if (useSolidBlackMiniPlayer) Color.Black else MaterialTheme.colorScheme.surfaceContainer
    val contentColor =
        if (useSolidBlackMiniPlayer || useGradientMiniPlayer || useBlurMiniPlayer) Color.White else MaterialTheme.colorScheme.onSurface
    val secondaryContentColor = contentColor.copy(alpha = 0.7f)
    val buttonBorderColor = contentColor.copy(alpha = 0.3f)
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val isPressed by interactionSource.collectIsPressedAsState()

    val miniPlayerType = Settings.shared.miniPlayerType
    val isModern = miniPlayerType == MiniPlayerType.MODERN && !useDesktopLayout

    val animatedScale =
        animateFloatAsState(
            targetValue = if (isPressed) 0.98f else 1f,
            animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
            label = "miniPlayerScale",
        )

    val cornerRadius =
        if (useDesktopLayout) {
            0.dp
        } else if (isModern) {
            modernMiniPlayerHeight(LocalDensity.current) / 2
        } else {
            16.dp
        }

    PlayerSheetSurface(
        modifier =
            modifier
                .fillMaxWidth()
                .height(
                    if (useDesktopLayout) {
                        DesktopMiniPlayerHeight
                    } else if (isModern) {
                        modernMiniPlayerHeight(LocalDensity.current)
                    } else {
                        MiniPlayerHeight
                    },
                ).graphicsLayer {
                    translationX = dragOffset.value.x
                    scaleX = animatedScale.value
                    scaleY = animatedScale.value
                }.clip(RoundedCornerShape(cornerRadius))
                .then(if (!isTv && focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .focusProperties { canFocus = !isTv }
                .testTag("mini_player_card")
                .focusOutline(RoundedCornerShape(cornerRadius), borderWidth = 4.dp)
                .semantics { selected = isFocused }
                .onPreviewKeyEvent { event ->
                    if (isFocused && event.type == KeyEventType.KeyUp && (event.key == Key.Enter || event.key == Key.DirectionCenter)) {
                        onDpadExpand()
                        true
                    } else {
                        false
                    }
                }.clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onExpand,
                ).semantics {
                    customActions =
                        buildList {
                            if (canSkipPrevious) {
                                add(
                                    CustomAccessibilityAction(previousLabel) {
                                        onSkipPrevious()
                                        true
                                    },
                                )
                            }
                            if (canSkipNext) {
                                add(
                                    CustomAccessibilityAction(nextLabel) {
                                        onSkipNext()
                                        true
                                    },
                                )
                            }
                        }
                },
        cornerRadius = cornerRadius,
        elevation = 8.dp,
        color = backgroundColor,
        border =
            if (isModern) {
                BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
            } else {
                null
            },
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (useBlurMiniPlayer) {
                AsyncImage(
                    url = artworkUrl,
                    contentDescription = null,
                    modifier =
                        Modifier
                            .matchParentSize()
                            .nativeBlurCompat(miniPlayerBlurRadiusPx),
                    contentScale = ContentScale.Crop,
                )
                Box(
                    modifier =
                        Modifier
                            .matchParentSize()
                            .background(
                                Color.Black.copy(alpha = 0.45f),
                            ),
                )
            }

            if (useGradientMiniPlayer) {
                val colors =
                    if (gradientColors.isNotEmpty()) {
                        gradientColors
                    } else {
                        listOf(
                            MaterialTheme.colorScheme.surfaceContainer,
                            MaterialTheme.colorScheme.surfaceContainer,
                        )
                    }
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.horizontalGradient(colors),
                        ).background(Color.Black.copy(alpha = 0.15f)),
                )
            }

            val subscribeDatabase: MusicDatabase = koinInject()
            val subscribeArtist = currentSong.artists.firstOrNull { !it.id.isNullOrBlank() }
            val subscribeArtistId = subscribeArtist?.id
            val isArtistSubscribed =
                if (active && (isModern || useDesktopLayout) &&
                    Settings.shared.showMiniPlayerSubscribeButton
                ) {
                    rememberArtistSubscription(subscribeDatabase, subscribeArtistId)
                } else {
                    false
                }
            val subscribeScope = rememberCoroutineScope()
            val subscribeHaptics = rememberMetrolistHaptics()
            val subscribedMessage = stringResource(Res.string.subscribed_to_artist, subscribeArtist?.displayName.orEmpty())
            val subscriptionError = stringResource(Res.string.error_occurred)
            val addToPlaylist = LocalPlaylistAdder.current

            MiniPlayerContent(
                useDesktopLayout = useDesktopLayout,
                active = active,
                isLive = isLive,
                metadataModifier =
                    if (isTv) {
                        Modifier
                            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                            .tvFocusHighlight(scale = 1f)
                            .clickable(onClick = onDpadExpand)
                    } else {
                        Modifier
                    },
                currentSong = currentSong,
                marqueeModifier = marqueeModifier,
                artworkUrl = artworkUrl,
                nextArtworkUrl = nextArtworkUrl,
                artworkCrossfadeProgress = artworkCrossfadeProgress,
                isPlaying = isPlaying,
                isPlaybackLoading = isPlaybackLoading,
                progress = progress,
                arcAlpha = arcAlpha,
                contentColor = contentColor,
                secondaryContentColor = secondaryContentColor,
                buttonBorderColor = buttonBorderColor,
                miniPlayerType = miniPlayerType,
                onTogglePlayPause = onTogglePlayPause,
                canSkipNext = canSkipNext,
                onSkipNext = onSkipNext,
                isListenTogetherGuest = isListenTogetherGuest,
                isMuted = isMuted,
                onLike = onLike,
                onAddToPlaylist = { addToPlaylist(listOf(currentSong)) },
                artistId = subscribeArtistId,
                isArtistSubscribed = isArtistSubscribed,
                onSubscribeToggle =
                    if (subscribeArtistId != null) {
                        {
                            subscribeHaptics.toggle(!isArtistSubscribed)
                            toggleArtistSubscription(
                                subscribeScope,
                                subscribeDatabase,
                                GlobalYouTubeRepository.instance,
                                subscribeArtistId,
                                subscribeArtist.displayName,
                                onSubscriptionChanged = { subscribed ->
                                    if (subscribed) Toast.makeText(context, subscribedMessage, Toast.LENGTH_SHORT).show()
                                },
                                onError = { Toast.makeText(context, subscriptionError, Toast.LENGTH_SHORT).show() },
                            )
                        }
                    } else {
                        null
                    },
                videoPlayer = videoPlayer,
                showVideo = showVideo,
            )

            if (!isModern && !useDesktopLayout) {
                val progressColor = MaterialTheme.colorScheme.primary
                Box(
                    modifier =
                        Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .height(3.dp)
                            .graphicsLayer()
                            .background(contentColor.copy(alpha = 0.08f))
                            .drawBehind {
                                fun drawProgress(
                                    fraction: Float,
                                    color: Color,
                                ) {
                                    val width = size.width * fraction.coerceIn(0f, 1f)
                                    drawRect(
                                        color = color,
                                        topLeft = Offset(if (layoutDirection == LayoutDirection.Rtl) size.width - width else 0f, 0f),
                                        size = Size(width, size.height),
                                    )
                                }
                                drawProgress(bufferedProgress(), progressColor.copy(alpha = 0.38f))
                                drawProgress(progress(), progressColor)
                            },
                )
            }
        }
    }
}

@Composable
private fun MiniPlayerContent(
    useDesktopLayout: Boolean,
    active: Boolean,
    isLive: Boolean = false,
    modifier: Modifier = Modifier,
    metadataModifier: Modifier,
    currentSong: SongItem,
    marqueeModifier: Modifier,
    artworkUrl: String?,
    nextArtworkUrl: String?,
    artworkCrossfadeProgress: () -> Float,
    isPlaying: Boolean,
    isPlaybackLoading: Boolean,
    progress: () -> Float,
    arcAlpha: State<Float>,
    contentColor: Color,
    secondaryContentColor: Color,
    buttonBorderColor: Color,
    miniPlayerType: MiniPlayerType,
    onTogglePlayPause: () -> Unit,
    canSkipNext: Boolean,
    onSkipNext: () -> Unit,
    isListenTogetherGuest: Boolean,
    isMuted: Boolean,
    onLike: (songId: String) -> Unit,
    onAddToPlaylist: () -> Unit,
    artistId: String?,
    isArtistSubscribed: Boolean,
    onSubscribeToggle: (() -> Unit)?,
    videoPlayer: androidx.media3.common.Player?,
    showVideo: Boolean,
) {
    if (useDesktopLayout) {
        DesktopMiniPlayerContent(
            song = currentSong,
            isPlaying = isPlaying,
            isLoading = isPlaybackLoading,
            isGuest = isListenTogetherGuest,
            isMuted = isMuted,
            active = active,
            onTogglePlayPause = onTogglePlayPause,
            marqueeModifier = marqueeModifier,
            contentColor = contentColor,
            secondaryContentColor = secondaryContentColor,
            modifier = modifier,
            artwork = {
                if (showVideo) {
                    MusicVideoSurface(
                        player = videoPlayer,
                        artworkUrl = artworkUrl,
                        fillContainer = true,
                        showControls = false,
                        modifier = Modifier.size(48.dp),
                    )
                } else {
                    CrossfadeArtwork(
                        currentUrl = artworkUrl,
                        nextUrl = nextArtworkUrl,
                        progress = artworkCrossfadeProgress,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)),
                        animate = active,
                    )
                }
            },
            actions = {
                MiniPlayerActions(
                    isLiked = SongLikeState.isLiked(currentSong.id),
                    isArtistSubscribed = isArtistSubscribed,
                    onAddToPlaylist = onAddToPlaylist,
                    onLike = { onLike(currentSong.id) },
                    onSubscribeToggle = onSubscribeToggle.takeIf { artistId != null },
                    borderColor = buttonBorderColor,
                    contentColor = secondaryContentColor,
                )
            },
        )
    } else if (miniPlayerType == MiniPlayerType.MODERN) {
        ModernMiniPlayerContent(
            modifier = modifier,
            metadataModifier = metadataModifier,
            currentSong = currentSong,
            isLive = isLive,
            marqueeModifier = marqueeModifier,
            artworkUrl = artworkUrl,
            nextArtworkUrl = nextArtworkUrl,
            artworkCrossfadeProgress = artworkCrossfadeProgress,
            isPlaying = isPlaying,
            isPlaybackLoading = isPlaybackLoading,
            progress = progress,
            arcAlpha = arcAlpha,
            contentColor = contentColor,
            secondaryContentColor = secondaryContentColor,
            buttonBorderColor = buttonBorderColor,
            onTogglePlayPause = onTogglePlayPause,
            isListenTogetherGuest = isListenTogetherGuest,
            isMuted = isMuted,
            onLike = onLike,
            onAddToPlaylist = onAddToPlaylist,
            artistId = artistId,
            isArtistSubscribed = isArtistSubscribed,
            onSubscribeToggle = onSubscribeToggle,
            videoPlayer = videoPlayer,
            showVideo = showVideo,
        )
    } else {
        VintageMiniPlayerContent(
            modifier = modifier,
            metadataModifier = metadataModifier,
            currentSong = currentSong,
            isLive = isLive,
            marqueeModifier = marqueeModifier,
            artworkUrl = artworkUrl,
            nextArtworkUrl = nextArtworkUrl,
            artworkCrossfadeProgress = artworkCrossfadeProgress,
            isPlaying = isPlaying,
            isPlaybackLoading = isPlaybackLoading,
            contentColor = contentColor,
            secondaryContentColor = secondaryContentColor,
            onTogglePlayPause = onTogglePlayPause,
            canSkipNext = canSkipNext,
            onSkipNext = onSkipNext,
            isListenTogetherGuest = isListenTogetherGuest,
            isMuted = isMuted,
            videoPlayer = videoPlayer,
            showVideo = showVideo,
        )
    }
}

@Composable
private fun ModernMiniPlayerContent(
    modifier: Modifier = Modifier,
    metadataModifier: Modifier,
    currentSong: SongItem,
    isLive: Boolean = false,
    marqueeModifier: Modifier,
    artworkUrl: String?,
    nextArtworkUrl: String?,
    artworkCrossfadeProgress: () -> Float,
    isPlaying: Boolean,
    isPlaybackLoading: Boolean,
    progress: () -> Float,
    arcAlpha: State<Float>,
    contentColor: Color,
    secondaryContentColor: Color,
    buttonBorderColor: Color,
    onTogglePlayPause: () -> Unit,
    isListenTogetherGuest: Boolean,
    isMuted: Boolean,
    onLike: (songId: String) -> Unit,
    onAddToPlaylist: () -> Unit,
    artistId: String?,
    isArtistSubscribed: Boolean,
    onSubscribeToggle: (() -> Unit)?,
    videoPlayer: androidx.media3.common.Player?,
    showVideo: Boolean,
) {
    val primaryColor = MaterialTheme.colorScheme.primary

    val isLiked = SongLikeState.isLiked(currentSong.id)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        PlayButtonWithProgress(
            isPlaying = isPlaying,
            isLoading = isPlaybackLoading,
            progress = progress,
            thumbnailUrl = artworkUrl,
            nextThumbnailUrl = nextArtworkUrl,
            artworkCrossfadeProgress = artworkCrossfadeProgress,
            videoPlayer = videoPlayer,
            showVideo = showVideo,
            isListenTogetherGuest = isListenTogetherGuest,
            isMuted = isMuted,
            primaryColor = primaryColor,
            outlineColor = buttonBorderColor,
            onClick = onTogglePlayPause,
            arcAlpha = arcAlpha,
        )

        Spacer(modifier = Modifier.width(14.dp))

        Column(
            modifier = Modifier.weight(1f).then(metadataModifier),
            verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (isLive || currentSong.isLive) {
                    LiveBadge(compact = true)
                }
                Text(
                    text = currentSong.title,
                    color = contentColor,
                    fontSize = 13.sp,
                    lineHeight = 16.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = marqueeModifier,
                )
            }

            Text(
                text = currentSong.artists.joinToString { it.displayName }.takeIf { it.isNotBlank() } ?: stringResource(Res.string.unknown),
                color = secondaryContentColor,
                fontSize = 11.sp,
                lineHeight = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = marqueeModifier,
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        MiniPlayerActions(
            isLiked = isLiked,
            isArtistSubscribed = isArtistSubscribed,
            onAddToPlaylist = onAddToPlaylist,
            onLike = { onLike(currentSong.id) },
            onSubscribeToggle = onSubscribeToggle.takeIf { artistId != null },
            borderColor = buttonBorderColor,
            contentColor = secondaryContentColor,
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun VintageMiniPlayerContent(
    modifier: Modifier = Modifier,
    metadataModifier: Modifier,
    currentSong: SongItem,
    isLive: Boolean = false,
    marqueeModifier: Modifier,
    artworkUrl: String?,
    nextArtworkUrl: String?,
    artworkCrossfadeProgress: () -> Float,
    isPlaying: Boolean,
    isPlaybackLoading: Boolean,
    contentColor: Color,
    secondaryContentColor: Color,
    onTogglePlayPause: () -> Unit,
    canSkipNext: Boolean,
    onSkipNext: () -> Unit,
    isListenTogetherGuest: Boolean,
    isMuted: Boolean,
    videoPlayer: androidx.media3.common.Player?,
    showVideo: Boolean,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(8.dp)),
        ) {
            if (showVideo) {
                MusicVideoSurface(
                    player = videoPlayer,
                    artworkUrl = artworkUrl,
                    modifier = Modifier.fillMaxSize(),
                    cornerRadius = 8.dp,
                    fillContainer = true,
                )
            } else {
                CrossfadeArtwork(
                    currentUrl = artworkUrl,
                    nextUrl = nextArtworkUrl,
                    progress = artworkCrossfadeProgress,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(
            modifier = Modifier.weight(1f).then(metadataModifier),
            verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (isLive || currentSong.isLive) {
                    LiveBadge(compact = true)
                }
                Text(
                    text = currentSong.title,
                    color = contentColor,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = marqueeModifier,
                )
            }

            Text(
                text = currentSong.artists.joinToString { it.displayName }.takeIf { it.isNotBlank() } ?: stringResource(Res.string.unknown),
                color = secondaryContentColor,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = marqueeModifier,
            )
        }

        IconButton(
            onClick = onTogglePlayPause,
            modifier = Modifier.testTag("mini_player_play").tvFocusHighlight(CircleShape, scale = 1f),
            shapes = IconButtonDefaults.shapes(),
        ) {
            if (isPlaybackLoading) {
                val loadingText = stringResource(Res.string.loading)
                LoadingIndicator(
                    modifier = Modifier.size(32.dp).semantics { contentDescription = loadingText },
                    color = contentColor,
                )
            } else {
                Icon(
                    imageVector =
                        when {
                            isListenTogetherGuest && isMuted -> MetrolistIcons.AutoMirrored.Filled.VolumeUp
                            isListenTogetherGuest -> MetrolistIcons.AutoMirrored.Filled.VolumeOff
                            isPlaying -> MetrolistIcons.Rounded.Pause
                            else -> MetrolistIcons.Rounded.PlayArrow
                        },
                    contentDescription =
                        stringResource(
                            when {
                                isListenTogetherGuest && isMuted -> Res.string.unmute
                                isListenTogetherGuest -> Res.string.mute
                                isPlaying -> Res.string.pause
                                else -> Res.string.play
                            },
                        ),
                    modifier = Modifier.size(28.dp),
                    tint = contentColor,
                )
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        IconButton(
            onClick = onSkipNext,
            enabled = canSkipNext,
            colors =
                IconButtonDefaults.iconButtonColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ),
            modifier = Modifier.testTag("mini_player_skip_next").tvFocusHighlight(CircleShape, scale = 1f),
            shapes = IconButtonDefaults.shapes(),
        ) {
            Icon(
                imageVector = MetrolistIcons.Rounded.SkipNext,
                contentDescription = stringResource(Res.string.next),
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

@Composable
private fun PlayButtonWithProgress(
    isPlaying: Boolean,
    isListenTogetherGuest: Boolean,
    isMuted: Boolean,
    isLoading: Boolean,
    progress: () -> Float,
    thumbnailUrl: String?,
    nextThumbnailUrl: String?,
    artworkCrossfadeProgress: () -> Float,
    primaryColor: Color,
    outlineColor: Color,
    onClick: () -> Unit,
    arcAlpha: State<Float>,
    videoPlayer: androidx.media3.common.Player?,
    showVideo: Boolean,
) {
    val actionLabel =
        stringResource(
            when {
                isListenTogetherGuest && isMuted -> Res.string.unmute
                isListenTogetherGuest -> Res.string.mute
                isPlaying -> Res.string.pause
                else -> Res.string.play
            },
        )
    val trackColor = outlineColor.copy(alpha = 0.2f)
    val strokeWidth = 3.dp

    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier
                .size(MiniPlayerProgressDiameter)
                .graphicsLayer()
                .drawWithContent {
                    drawContent()
                    val stroke = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Round)
                    val diameter = size.minDimension
                    val topLeft = Offset((size.width - diameter) / 2, (size.height - diameter) / 2)

                    drawArc(
                        color = trackColor.copy(alpha = trackColor.alpha * arcAlpha.value),
                        startAngle = 0f,
                        sweepAngle = 360f,
                        useCenter = false,
                        topLeft = topLeft,
                        size = Size(diameter, diameter),
                        style = stroke,
                    )
                    drawArc(
                        color = primaryColor.copy(alpha = primaryColor.alpha * arcAlpha.value),
                        startAngle = -90f,
                        sweepAngle = 360f * progress().coerceIn(0f, 1f),
                        useCenter = false,
                        topLeft = topLeft,
                        size = Size(diameter, diameter),
                        style = stroke,
                    )
                },
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .testTag("mini_player_play")
                    .tvFocusHighlight(CircleShape, scale = 1.08f, borderWidth = 4.dp)
                    .semantics { contentDescription = actionLabel }
                    .clickable(role = Role.Button, onClick = onClick),
        ) {
            if (showVideo) {
                MusicVideoSurface(
                    player = videoPlayer,
                    artworkUrl = thumbnailUrl,
                    modifier = Modifier.fillMaxSize(),
                    cornerRadius = 18.dp,
                    fillContainer = true,
                )
            } else {
                CrossfadeArtwork(
                    currentUrl = thumbnailUrl,
                    nextUrl = nextThumbnailUrl,
                    progress = artworkCrossfadeProgress,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            if (isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f), CircleShape),
                )
                ContainedLoadingIndicator(Modifier.size(32.dp))
            } else if (isListenTogetherGuest || !isPlaying) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.4f), CircleShape),
                )
                Icon(
                    imageVector =
                        if (isListenTogetherGuest) {
                            if (isMuted) {
                                MetrolistIcons.AutoMirrored.Filled.VolumeUp
                            } else {
                                MetrolistIcons.AutoMirrored.Filled.VolumeOff
                            }
                        } else {
                            MetrolistIcons.Outlined.PlayArrow
                        },
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}
