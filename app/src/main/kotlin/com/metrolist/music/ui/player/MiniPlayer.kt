/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 * 
 * Performance optimized MiniPlayer - prevents unnecessary recomposition
 */

package com.metrolist.music.ui.player

import android.content.res.Configuration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import coil3.compose.AsyncImage
import com.metrolist.music.LocalDatabase
import com.metrolist.music.LocalListenTogetherManager
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.constants.CropAlbumArtKey
import com.metrolist.music.constants.DarkModeKey
import com.metrolist.music.constants.MiniPlayerHeight
import com.metrolist.music.constants.PureBlackMiniPlayerKey
import com.metrolist.music.constants.SwipeSensitivityKey
import com.metrolist.music.constants.SwipeThumbnailKey
import com.metrolist.music.constants.ThumbnailCornerRadius
import com.metrolist.music.constants.UseNewMiniPlayerDesignKey
import com.metrolist.music.db.entities.ArtistEntity
import com.metrolist.music.listentogether.ListenTogetherManager
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.playback.CastConnectionHandler
import com.metrolist.music.playback.PlayerConnection
import com.metrolist.music.ui.screens.settings.DarkMode
import com.metrolist.music.ui.utils.resize
import com.metrolist.music.utils.rememberEnumPreference
import com.metrolist.music.utils.rememberPreference
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue
import kotlin.math.roundToInt
import com.metrolist.music.ui.component.Icon as MIcon

/**
 * Stable wrapper for progress state - reads values only during draw phase
 * This prevents recomposition when position/duration change
 */
@Stable
class ProgressState(
    private val positionState: MutableLongState,
    private val durationState: MutableLongState
) {
    val progress: Float
        get() {
            val duration = durationState.longValue
            return if (duration > 0) (positionState.longValue.toFloat() / duration).coerceIn(0f, 1f) else 0f
        }
}

@Composable
fun MiniPlayer(
    positionState: MutableLongState,
    durationState: MutableLongState,
    modifier: Modifier = Modifier
) {
    val useNewMiniPlayerDesign by rememberPreference(UseNewMiniPlayerDesignKey, true)
    
    // Create stable progress state - doesn't cause recomposition on position changes
    val progressState = remember { ProgressState(positionState, durationState) }

    val configuration = LocalConfiguration.current
    val isTabletLandscape = remember(configuration.screenWidthDp, configuration.orientation) {
        configuration.screenWidthDp >= 600 && configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    }

    if (useNewMiniPlayerDesign) {
        NewMiniPlayer(
            progressState = progressState,
            modifier = modifier
        )
    } else {
        Box(modifier = modifier.fillMaxWidth()) {
            LegacyMiniPlayer(
                progressState = progressState,
                modifier = Modifier.align(Alignment.Center)
            )
        }
    }
}

// ============================================================================
// NEW MINI PLAYER DESIGN
// ============================================================================

@Composable
private fun NewMiniPlayer(
    progressState: ProgressState,
    modifier: Modifier = Modifier
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    
    // Theme settings - these rarely change
    val pureBlack by rememberPreference(PureBlackMiniPlayerKey, defaultValue = false)
    val isSystemInDarkTheme = isSystemInDarkTheme()
    val darkTheme by rememberEnumPreference(DarkModeKey, defaultValue = DarkMode.AUTO)
    val useDarkTheme = remember(darkTheme, isSystemInDarkTheme) {
        if (darkTheme == DarkMode.AUTO) isSystemInDarkTheme else darkTheme == DarkMode.ON
    }
    
    // Player states - only collect what's needed at this level
    val playbackState by playerConnection.playbackState.collectAsState()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()
    val canSkipNext by playerConnection.canSkipNext.collectAsState()
    val canSkipPrevious by playerConnection.canSkipPrevious.collectAsState()
    
    // Cast state - safely access castConnectionHandler to prevent crashes during service lifecycle changes
    val castHandler = remember(playerConnection) {
        try {
            playerConnection.service.castConnectionHandler
        } catch (e: Exception) {
            null
        }
    }
    val isCasting by castHandler?.isCasting?.collectAsState() ?: remember { mutableStateOf(false) }

    // Swipe settings
    val swipeSensitivity by rememberPreference(SwipeSensitivityKey, 0.73f)
    val swipeThumbnailPref by rememberPreference(SwipeThumbnailKey, true)
    
    // Disable swipe for Listen Together guests
    val listenTogetherManager = LocalListenTogetherManager.current
    val isListenTogetherGuest = listenTogetherManager?.let { it.isInRoom && !it.isHost } ?: false
    val swipeThumbnail = swipeThumbnailPref && !isListenTogetherGuest
    
    val layoutDirection = LocalLayoutDirection.current
    val coroutineScope = rememberCoroutineScope()
    
    val configuration = LocalConfiguration.current
    val isTabletLandscape = remember(configuration.screenWidthDp, configuration.orientation) {
        configuration.screenWidthDp >= 600 && configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    }

    // Swipe animation state
    val offsetXAnimatable = remember { Animatable(0f) }
    var dragStartTime by remember { mutableLongStateOf(0L) }
    var totalDragDistance by remember { mutableFloatStateOf(0f) }

    val animationSpec = remember {
        spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessLow)
    }

    val autoSwipeThreshold = remember(swipeSensitivity) {
        (600 / (1f + kotlin.math.exp(-(-11.44748 * swipeSensitivity + 9.04945)))).roundToInt()
    }
    
    // Memoize colors
    val backgroundColor = if (pureBlack && useDarkTheme) Color.Black else MaterialTheme.colorScheme.surfaceContainer
    val primaryColor = MaterialTheme.colorScheme.primary
    val outlineColor = MaterialTheme.colorScheme.outline
    val onSurfaceColor = MaterialTheme.colorScheme.onSurface
    val errorColor = MaterialTheme.colorScheme.error

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(MiniPlayerHeight)
            .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Horizontal))
            .padding(horizontal = 12.dp)
            .let { baseModifier ->
                if (swipeThumbnail) {
                    baseModifier.pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onDragStart = {
                                dragStartTime = System.currentTimeMillis()
                                totalDragDistance = 0f
                            },
                            onDragCancel = {
                                coroutineScope.launch {
                                    offsetXAnimatable.animateTo(0f, animationSpec)
                                }
                            },
                            onHorizontalDrag = { _, dragAmount ->
                                val adjustedDragAmount =
                                    if (layoutDirection == LayoutDirection.Rtl) -dragAmount else dragAmount
                                val canSkipPrevious = playerConnection.player.previousMediaItemIndex != -1
                                val canSkipNext = playerConnection.player.nextMediaItemIndex != -1
                                val tryingToSwipeRight = adjustedDragAmount > 0
                                val tryingToSwipeLeft = adjustedDragAmount < 0
                                val allowLeft = tryingToSwipeLeft && canSkipNext
                                val allowRight = tryingToSwipeRight && canSkipPrevious

                                val canReturnToCenter =
                                    (tryingToSwipeRight && !canSkipPrevious && offsetXAnimatable.value < 0) ||
                                            (tryingToSwipeLeft && !canSkipNext && offsetXAnimatable.value > 0)

                                if (allowLeft || allowRight || canReturnToCenter) {
                                    totalDragDistance += kotlin.math.abs(adjustedDragAmount)
                                    coroutineScope.launch {
                                        offsetXAnimatable.snapTo(offsetXAnimatable.value + adjustedDragAmount)
                                    }
                                }
                            },
                            onDragEnd = {
                                val dragDuration = System.currentTimeMillis() - dragStartTime
                                val velocity = if (dragDuration > 0) totalDragDistance / dragDuration else 0f
                                val currentOffset = offsetXAnimatable.value
                                val minDistanceThreshold = 50f
                                val velocityThreshold = (swipeSensitivity * -8.25f) + 8.5f

                                val shouldChangeSong = (kotlin.math.abs(currentOffset) > minDistanceThreshold && velocity > velocityThreshold) ||
                                    (kotlin.math.abs(currentOffset) > autoSwipeThreshold)

                                if (shouldChangeSong) {
                                    if (currentOffset > 0 && canSkipPrevious) {
                                        playerConnection.player.seekToPreviousMediaItem()
                                    } else if (currentOffset <= 0 && canSkipNext) {
                                        playerConnection.player.seekToNext()
                                    }
                                }
                                coroutineScope.launch {
                                    offsetXAnimatable.animateTo(0f, animationSpec)
                                }
                            }
                        )
                    }
                } else baseModifier
            }
    ) {
        Box(
            modifier = Modifier
                .then(if (isTabletLandscape) Modifier.width(500.dp).align(Alignment.Center) else Modifier.fillMaxWidth())
                .height(64.dp)
                .offset { IntOffset(offsetXAnimatable.value.roundToInt(), 0) }
                .clip(RoundedCornerShape(32.dp))
                .background(color = backgroundColor)
                .border(1.dp, outlineColor.copy(alpha = 0.3f), RoundedCornerShape(32.dp))
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 8.dp),
            ) {
                // Play button with progress - isolated composable
                NewMiniPlayerPlayButton(
                    progressState = progressState,
                    playbackState = playbackState,
                    isCasting = isCasting,
                    castHandler = castHandler,
                    playerConnection = playerConnection,
                    mediaMetadata = mediaMetadata,
                    primaryColor = primaryColor,
                    outlineColor = outlineColor,
                    listenTogetherManager = listenTogetherManager
                )

                Spacer(modifier = Modifier.width(16.dp))

                // Song info - isolated composable
                NewMiniPlayerSongInfo(
                    mediaMetadata = mediaMetadata,
                    onSurfaceColor = onSurfaceColor,
                    errorColor = errorColor,
                    modifier = Modifier.weight(1f)
                )

                Spacer(modifier = Modifier.width(12.dp))
                
                // Cast indicator
                if (isCasting) {
                    Icon(
                        painter = painterResource(R.drawable.cast_connected),
                        contentDescription = "Casting",
                        tint = primaryColor,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                }

                // Subscribe button - isolated composable
                mediaMetadata?.artists?.firstOrNull()?.id?.let { artistId ->
                    SubscribeButton(artistId = artistId, metadata = mediaMetadata!!)
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Favorite button - isolated composable
                mediaMetadata?.let { FavoriteButton(songId = it.id) }
            }
        }
    }
}

/**
 * Play button with circular progress indicator
 * Uses drawWithContent to update progress without recomposition
 */
@Composable
private fun NewMiniPlayerPlayButton(
    progressState: ProgressState,
    playbackState: Int,
    isCasting: Boolean,
    castHandler: CastConnectionHandler?,
    playerConnection: PlayerConnection,
    mediaMetadata: MediaMetadata?,
    primaryColor: Color,
    outlineColor: Color,
    listenTogetherManager: ListenTogetherManager?
) {
    val isPlaying by playerConnection.isPlaying.collectAsState()
    val castIsPlaying by castHandler?.castIsPlaying?.collectAsState() ?: remember { mutableStateOf(false) }
    val effectiveIsPlaying = if (isCasting) castIsPlaying else isPlaying
    val isListenTogetherGuest = listenTogetherManager?.let { it.isInRoom && !it.isHost } ?: false
    val isMuted by playerConnection.isMuted.collectAsState()

    
    val trackColor = outlineColor.copy(alpha = 0.2f)
    val strokeWidth = 3.dp

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(48.dp)
            .drawWithContent {
                drawContent()
                // Draw progress arc - this reads progressState.progress during draw phase only
                val progress = progressState.progress
                val stroke = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Round)
                val startAngle = -90f
                val sweepAngle = 360f * progress
                val diameter = size.minDimension
                val topLeft = Offset((size.width - diameter) / 2, (size.height - diameter) / 2)
                
                // Draw track
                drawArc(
                    color = trackColor,
                    startAngle = 0f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = Size(diameter, diameter),
                    style = stroke
                )
                // Draw progress
                drawArc(
                    color = primaryColor,
                    startAngle = startAngle,
                    sweepAngle = sweepAngle,
                    useCenter = false,
                    topLeft = topLeft,
                    size = Size(diameter, diameter),
                    style = stroke
                )
            }
    ) {
        // Thumbnail with play/pause overlay
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .border(1.dp, outlineColor.copy(alpha = 0.3f), CircleShape)
                .clickable {
                    if (isListenTogetherGuest) {
                        playerConnection.toggleMute()
                        return@clickable
                    }
                    if (isCasting) {
                        if (castIsPlaying) castHandler?.pause() else castHandler?.play()
                    } else if (playbackState == Player.STATE_ENDED) {
                        playerConnection.player.seekTo(0, 0)
                        playerConnection.player.playWhenReady = true
                    } else {
                        playerConnection.togglePlayPause()
                    }
                }
        ) {
            mediaMetadata?.let { metadata ->
                val thumbnailUrl = remember(metadata.thumbnailUrl) {
                    metadata.thumbnailUrl?.resize(120, 120)
                }
                AsyncImage(
                    model = thumbnailUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clip(CircleShape)
                )
            }

            // Overlay for paused state or muted (guest)
            if (isListenTogetherGuest && isMuted || (!isListenTogetherGuest && (!effectiveIsPlaying || playbackState == Player.STATE_ENDED))) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.4f), CircleShape)
                )
                Icon(
                    painter = painterResource(
                        if (isListenTogetherGuest) {
                            if (isMuted) R.drawable.volume_off else R.drawable.volume_up
                        } else if (playbackState == Player.STATE_ENDED) R.drawable.replay else R.drawable.play
                    ),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

/**
 * Song info display - title and artist
 */
@Composable
private fun NewMiniPlayerSongInfo(
    mediaMetadata: MediaMetadata?,
    onSurfaceColor: Color,
    errorColor: Color,
    modifier: Modifier = Modifier
) {
    val error by LocalPlayerConnection.current?.error?.collectAsState() ?: remember { mutableStateOf(null) }
    
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.Center
    ) {
        mediaMetadata?.let { metadata ->
            Text(
                text = metadata.title,
                color = onSurfaceColor,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.basicMarquee(iterations = 1, initialDelayMillis = 3000, velocity = 30.dp),
            )
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (metadata.explicit) MIcon.Explicit()
                if (metadata.artists.any { it.name.isNotBlank() }) {
                    Text(
                        text = metadata.artists.joinToString { it.name },
                        color = onSurfaceColor.copy(alpha = 0.7f),
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.basicMarquee(iterations = 1, initialDelayMillis = 3000, velocity = 30.dp),
                    )
                }
            }

            AnimatedVisibility(visible = error != null, enter = fadeIn(), exit = fadeOut()) {
                Text(
                    text = stringResource(R.string.error_playing),
                    color = errorColor,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// ============================================================================
// LEGACY MINI PLAYER DESIGN
// ============================================================================

@Composable
private fun LegacyMiniPlayer(
    progressState: ProgressState,
    modifier: Modifier = Modifier
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val pureBlack by rememberPreference(PureBlackMiniPlayerKey, defaultValue = false)
    
    val playbackState by playerConnection.playbackState.collectAsState()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()
    val canSkipNext by playerConnection.canSkipNext.collectAsState()
    val canSkipPrevious by playerConnection.canSkipPrevious.collectAsState()
    
    val castHandler = remember(playerConnection) {
        try {
            playerConnection.service.castConnectionHandler
        } catch (e: Exception) {
            null
        }
    }
    val isCasting by castHandler?.isCasting?.collectAsState() ?: remember { mutableStateOf(false) }

    val swipeSensitivity by rememberPreference(SwipeSensitivityKey, 0.73f)
    val swipeThumbnailPref by rememberPreference(SwipeThumbnailKey, true)
    
    // Disable swipe for Listen Together guests
    val listenTogetherManager = LocalListenTogetherManager.current
    val isListenTogetherGuest = listenTogetherManager?.let { it.isInRoom && !it.isHost } ?: false
    val swipeThumbnail = swipeThumbnailPref && !isListenTogetherGuest

    val layoutDirection = LocalLayoutDirection.current
    val coroutineScope = rememberCoroutineScope()
    
    val configuration = LocalConfiguration.current
    val isTabletLandscape = remember(configuration.screenWidthDp, configuration.orientation) {
        configuration.screenWidthDp >= 600 && configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    }

    val offsetXAnimatable = remember { Animatable(0f) }
    var dragStartTime by remember { mutableLongStateOf(0L) }
    var totalDragDistance by remember { mutableFloatStateOf(0f) }

    val animationSpec = remember {
        spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessLow)
    }

    val autoSwipeThreshold = remember(swipeSensitivity) {
        (600 / (1f + kotlin.math.exp(-(-11.44748 * swipeSensitivity + 9.04945)))).roundToInt()
    }
    
    val primaryColor = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.surfaceVariant

    Box(
        modifier = modifier
            .then(if (isTabletLandscape) Modifier.width(500.dp) else Modifier.fillMaxWidth())
            .height(MiniPlayerHeight)
            .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Horizontal))
            .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            .background(
                if (pureBlack && isSystemInDarkTheme()) Color.Black
                else MaterialTheme.colorScheme.surfaceContainer
            )
            .let { baseModifier ->
                if (swipeThumbnail) {
                    baseModifier.pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onDragStart = {
                                dragStartTime = System.currentTimeMillis()
                                totalDragDistance = 0f
                            },
                            onDragCancel = {
                                coroutineScope.launch { offsetXAnimatable.animateTo(0f, animationSpec) }
                            },
                            onHorizontalDrag = { _, dragAmount ->
                                val adjustedDragAmount =
                                    if (layoutDirection == LayoutDirection.Rtl) -dragAmount else dragAmount
                                val canSkipPrevious = playerConnection.player.previousMediaItemIndex != -1
                                val canSkipNext = playerConnection.player.nextMediaItemIndex != -1
                                val tryingToSwipeRight = adjustedDragAmount > 0
                                val tryingToSwipeLeft = adjustedDragAmount < 0
                                val allowLeft = tryingToSwipeLeft && canSkipNext
                                val allowRight = tryingToSwipeRight && canSkipPrevious

                                val canReturnToCenter =
                                    (tryingToSwipeRight && !canSkipPrevious && offsetXAnimatable.value < 0) ||
                                            (tryingToSwipeLeft && !canSkipNext && offsetXAnimatable.value > 0)

                                if (allowLeft || allowRight || canReturnToCenter) {
                                    totalDragDistance += kotlin.math.abs(adjustedDragAmount)
                                    coroutineScope.launch {
                                        offsetXAnimatable.snapTo(offsetXAnimatable.value + adjustedDragAmount)
                                    }
                                }
                            },
                            onDragEnd = {
                                val dragDuration = System.currentTimeMillis() - dragStartTime
                                val velocity = if (dragDuration > 0) totalDragDistance / dragDuration else 0f
                                val currentOffset = offsetXAnimatable.value
                                val minDistanceThreshold = 50f
                                val velocityThreshold = (swipeSensitivity * -8.25f) + 8.5f

                                val shouldChangeSong = (kotlin.math.abs(currentOffset) > minDistanceThreshold && velocity > velocityThreshold) ||
                                    (kotlin.math.abs(currentOffset) > autoSwipeThreshold)

                                if (shouldChangeSong) {
                                    if (currentOffset > 0 && canSkipPrevious) {
                                        playerConnection.player.seekToPreviousMediaItem()
                                    } else if (currentOffset <= 0 && canSkipNext) {
                                        playerConnection.player.seekToNext()
                                    }
                                }
                                coroutineScope.launch { offsetXAnimatable.animateTo(0f, animationSpec) }
                            }
                        )
                    }
                } else baseModifier
            }
    ) {
        // Progress bar - uses drawWithContent to avoid recomposition
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .align(Alignment.BottomCenter)
                .drawWithContent {
                    val progress = progressState.progress
                    drawRect(trackColor)
                    drawRect(primaryColor, size = Size(size.width * progress, size.height))
                }
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxSize()
                .offset { IntOffset(offsetXAnimatable.value.roundToInt(), 0) }
                .padding(end = 12.dp),
        ) {
            Box(Modifier.weight(1f)) {
                mediaMetadata?.let {
                    LegacyMiniMediaInfo(
                        mediaMetadata = it,
                        pureBlack = pureBlack,
                        modifier = Modifier.padding(horizontal = 6.dp),
                    )
                }
            }

            LegacyPlayPauseButton(
                playbackState = playbackState,
                isCasting = isCasting,
                castHandler = castHandler,
                playerConnection = playerConnection,
                listenTogetherManager = listenTogetherManager
            )

            IconButton(
                    enabled = canSkipNext && !isListenTogetherGuest,
                    onClick = if (isListenTogetherGuest) ({}) else ({ playerConnection.seekToNext() }),
            ) {
                Icon(painter = painterResource(R.drawable.skip_next), contentDescription = null)
            }
        }

        // Swipe indicator
        if (offsetXAnimatable.value.absoluteValue > 50f) {
            Box(
                modifier = Modifier
                    .align(if (offsetXAnimatable.value > 0) Alignment.CenterStart else Alignment.CenterEnd)
                    .padding(horizontal = 16.dp)
            ) {
                Icon(
                    painter = painterResource(
                        if (offsetXAnimatable.value > 0) R.drawable.skip_previous else R.drawable.skip_next
                    ),
                    contentDescription = null,
                    tint = primaryColor.copy(
                        alpha = (offsetXAnimatable.value.absoluteValue / autoSwipeThreshold).coerceIn(0f, 1f)
                    ),
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

@Composable
private fun LegacyPlayPauseButton(
    playbackState: Int,
    isCasting: Boolean,
    castHandler: CastConnectionHandler?,
    playerConnection: PlayerConnection,
    listenTogetherManager: ListenTogetherManager?
) {
    val isPlaying by playerConnection.isPlaying.collectAsState()
    val castIsPlaying by castHandler?.castIsPlaying?.collectAsState() ?: remember { mutableStateOf(false) }
    val effectiveIsPlaying = if (isCasting) castIsPlaying else isPlaying
    val isListenTogetherGuest = listenTogetherManager?.let { it.isInRoom && !it.isHost } ?: false
    val isMuted by playerConnection.isMuted.collectAsState()


    IconButton(
        onClick = {
            if (isListenTogetherGuest) {
                playerConnection.toggleMute()
                return@IconButton
            }
            if (isCasting) {
                if (castIsPlaying) castHandler?.pause() else castHandler?.play()
            } else if (playbackState == Player.STATE_ENDED) {
                playerConnection.player.seekTo(0, 0)
                playerConnection.player.playWhenReady = true
            } else {
                playerConnection.togglePlayPause()
            }
        },
    ) {
        Icon(
            painter = painterResource(
                when {
                    isListenTogetherGuest -> if (isMuted) R.drawable.volume_off else R.drawable.volume_up
                    playbackState == Player.STATE_ENDED -> R.drawable.replay
                    effectiveIsPlaying -> R.drawable.pause
                    else -> R.drawable.play
                }
            ),
            contentDescription = null,
        )
    }
}

@Composable
private fun LegacyMiniMediaInfo(
    mediaMetadata: MediaMetadata,
    pureBlack: Boolean,
    modifier: Modifier = Modifier,
) {
    val error by LocalPlayerConnection.current?.error?.collectAsState() ?: remember { mutableStateOf(null) }
    val cropAlbumArt by rememberPreference(CropAlbumArtKey, false)
    
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier
                .padding(6.dp)
                .size(48.dp)
                .clip(RoundedCornerShape(ThumbnailCornerRadius))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )

            val thumbnailUrl = remember(mediaMetadata.thumbnailUrl) {
                mediaMetadata.thumbnailUrl?.resize(144, 144)
            }
            AsyncImage(
                model = thumbnailUrl,
                contentDescription = null,
                contentScale = if (cropAlbumArt) ContentScale.Crop else ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(ThumbnailCornerRadius)),
            )

            androidx.compose.animation.AnimatedVisibility(visible = error != null, enter = fadeIn(), exit = fadeOut()) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            color = if (pureBlack) Color.Black else Color.Black.copy(alpha = 0.6f),
                            shape = RoundedCornerShape(ThumbnailCornerRadius),
                        ),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.info),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 6.dp),
        ) {
            Text(
                text = mediaMetadata.title,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.basicMarquee(),
            )

            if (mediaMetadata.artists.any { it.name.isNotBlank() }) {
                Text(
                    text = mediaMetadata.artists.joinToString { it.name },
                    color = MaterialTheme.colorScheme.secondary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// ============================================================================
// ISOLATED BUTTON COMPOSABLES - Prevent parent recomposition
// ============================================================================

@Composable
private fun SubscribeButton(
    artistId: String,
    metadata: MediaMetadata
) {
    val database = LocalDatabase.current
    val libraryArtist by database.artist(artistId).collectAsState(initial = null)
    val isSubscribed = libraryArtist?.artist?.bookmarkedAt != null
    
    val primaryColor = MaterialTheme.colorScheme.primary
    val outlineColor = MaterialTheme.colorScheme.outline
    val onSurfaceColor = MaterialTheme.colorScheme.onSurface

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .border(
                width = 1.dp,
                color = if (isSubscribed) primaryColor.copy(alpha = 0.5f) else outlineColor.copy(alpha = 0.3f),
                shape = CircleShape
            )
            .background(
                color = if (isSubscribed) primaryColor.copy(alpha = 0.1f) else Color.Transparent,
                shape = CircleShape
            )
            .clickable {
                database.transaction {
                    val artist = libraryArtist?.artist
                    if (artist != null) {
                        update(artist.toggleLike())
                    } else {
                        metadata.artists.firstOrNull()?.let { artistInfo ->
                            insert(
                                ArtistEntity(
                                    id = artistInfo.id ?: "",
                                    name = artistInfo.name,
                                    channelId = null,
                                    thumbnailUrl = null,
                                ).toggleLike()
                            )
                        }
                    }
                }
            }
    ) {
        Icon(
            painter = painterResource(if (isSubscribed) R.drawable.subscribed else R.drawable.subscribe),
            contentDescription = null,
            tint = if (isSubscribed) primaryColor else onSurfaceColor.copy(alpha = 0.7f),
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun FavoriteButton(songId: String) {
    val database = LocalDatabase.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val librarySong by database.song(songId).collectAsState(initial = null)
    val isLiked = librarySong?.song?.liked == true
    
    val errorColor = MaterialTheme.colorScheme.error
    val outlineColor = MaterialTheme.colorScheme.outline
    val onSurfaceColor = MaterialTheme.colorScheme.onSurface

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .border(
                width = 1.dp,
                color = if (isLiked) errorColor.copy(alpha = 0.5f) else outlineColor.copy(alpha = 0.3f),
                shape = CircleShape
            )
            .background(
                color = if (isLiked) errorColor.copy(alpha = 0.1f) else Color.Transparent,
                shape = CircleShape
            )
            .clickable { playerConnection.service.toggleLike() }
    ) {
        Icon(
            painter = painterResource(if (isLiked) R.drawable.favorite else R.drawable.favorite_border),
            contentDescription = null,
            tint = if (isLiked) errorColor else onSurfaceColor.copy(alpha = 0.7f),
            modifier = Modifier.size(20.dp)
        )
    }
}
