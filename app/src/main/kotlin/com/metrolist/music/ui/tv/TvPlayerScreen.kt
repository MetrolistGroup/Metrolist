/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.tv

import android.view.KeyEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.navigation.NavController
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.db.entities.LyricsEntity.Companion.LYRICS_NOT_FOUND
import com.metrolist.music.extensions.metadata
import com.metrolist.music.extensions.togglePlayPause
import com.metrolist.music.extensions.toggleRepeatMode
import com.metrolist.music.lyrics.LyricsEntry
import com.metrolist.music.lyrics.LyricsUtils
import com.metrolist.music.ui.component.PlayingIndicator
import com.metrolist.music.ui.theme.PlayerColorExtractor
import com.metrolist.music.viewmodels.LyricsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * Spotify TV Inspired Playback UI
 *
 * Left side: Large Album Art with rounded corners, subtle elevation, currently playing badge
 * Right side: Clean track typography, dynamic backdrop extracted from album cover,
 * Spotify style progress bar, transport controls with Spotify green accents,
 * synchronized TV lyrics mode, in-player TV Queue Viewer panel, and D-pad remote support.
 */
@Composable
fun TvPlayerScreen(
    navController: NavController,
    lyricsViewModel: LyricsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()
    val isPlaying by playerConnection.isPlaying.collectAsState()
    val repeatMode by playerConnection.repeatMode.collectAsState()
    val shuffleModeEnabled by playerConnection.shuffleModeEnabled.collectAsState()
    val canSkipPrevious by playerConnection.canSkipPrevious.collectAsState()
    val canSkipNext by playerConnection.canSkipNext.collectAsState()
    val currentSong by playerConnection.currentSong.collectAsState(initial = null)
    val currentLyricsEntity by playerConnection.currentLyrics.collectAsState(initial = null)
    val queueWindows by playerConnection.queueWindows.collectAsState()

    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(1L) }

    LaunchedEffect(playerConnection) {
        while (isActive) {
            positionMs = playerConnection.player.currentPosition
            durationMs = playerConnection.player.duration.coerceAtLeast(1L)
            delay(250L)
        }
    }

    val progress by animateFloatAsState(
        targetValue = (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f),
        label = "progress",
    )

    // Dynamic gradient colors from artwork
    var dynamicColors by remember {
        mutableStateOf(listOf(Color(0xFF1E3264), Color(0xFF121212), Color.Black))
    }

    LaunchedEffect(mediaMetadata?.thumbnailUrl) {
        val url = mediaMetadata?.thumbnailUrl
        if (!url.isNullOrEmpty()) {
            withContext(Dispatchers.IO) {
                try {
                    val loader = ImageLoader(context)
                    val req = ImageRequest.Builder(context)
                        .data(url)
                        .allowHardware(false)
                        .build()
                    val result = loader.execute(req)
                    val bitmap = result.image?.toBitmap()
                    if (bitmap != null) {
                        val palette = androidx.palette.graphics.Palette.from(bitmap).generate()
                        val colors = PlayerColorExtractor.extractGradientColors(
                            palette = palette,
                            fallbackColor = android.graphics.Color.parseColor("#1DB954")
                        )
                        withContext(Dispatchers.Main) {
                            dynamicColors = colors
                        }
                    }
                } catch (_: Exception) {
                    // Fallback remains
                }
            }
        }
    }

    // View modes: 0 = Standard player, 1 = Synced TV Lyrics, 2 = In-player Queue
    var playerViewMode by remember { mutableIntStateOf(0) }

    val playButtonFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        delay(100)
        try {
            playButtonFocusRequester.requestFocus()
        } catch (_: Exception) {}
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(TvColors.BackgroundPureBlack)
            .onKeyEvent { keyEvent ->
                val action = keyEvent.nativeKeyEvent.action
                val code = keyEvent.nativeKeyEvent.keyCode
                val isDown = action == KeyEvent.ACTION_DOWN
                val isFirst = keyEvent.nativeKeyEvent.repeatCount == 0

                when {
                    // Media hardware buttons
                    isDown && isFirst && code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                        playerConnection.player.togglePlayPause()
                        true
                    }
                    isDown && isFirst && code == KeyEvent.KEYCODE_MEDIA_PLAY -> {
                        playerConnection.player.play()
                        true
                    }
                    isDown && isFirst && code == KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                        playerConnection.player.pause()
                        true
                    }
                    isDown && isFirst && code == KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                        playerConnection.player.seekToPreviousMediaItem()
                        true
                    }
                    isDown && isFirst && code == KeyEvent.KEYCODE_MEDIA_NEXT -> {
                        playerConnection.player.seekToNextMediaItem()
                        true
                    }
                    isDown && code == KeyEvent.KEYCODE_MEDIA_REWIND -> {
                        playerConnection.player.seekTo((playerConnection.player.currentPosition - 10_000L).coerceAtLeast(0L))
                        true
                    }
                    isDown && code == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                        playerConnection.player.seekTo((playerConnection.player.currentPosition + 10_000L).coerceAtMost(durationMs))
                        true
                    }
                    else -> false
                }
            },
    ) {
        // Dynamic blurred gradient background (Spotify TV style ambient canvas)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                Brush.verticalGradient(
                    colors = listOf(
                        dynamicColors.firstOrNull()?.copy(alpha = 0.75f) ?: Color(0xFF1E3264),
                        dynamicColors.getOrNull(1)?.copy(alpha = 0.5f) ?: Color(0xFF121212),
                        Color.Black
                    )
                )
            )
        )

        // Large subtle blurred backdrop from cover art
        AsyncImage(
            model = mediaMetadata?.thumbnailUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .blur(80.dp)
                .background(Color.Black.copy(alpha = 0.45f)),
        )

        // Main 2-column layout: Left Art, Right Controls & Info
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 64.dp, vertical = 40.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // ── Left Column: Album Art & Track Status ────────────────────────
            Column(
                modifier = Modifier
                    .weight(0.38f)
                    .fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box(
                    contentAlignment = Alignment.BottomStart,
                    modifier = Modifier
                        .fillMaxHeight(0.72f)
                        .aspectRatio(1f)
                        .shadow(24.dp, RoundedCornerShape(16.dp))
                        .clip(RoundedCornerShape(16.dp))
                        .background(TvColors.CardBackground)
                ) {
                    AsyncImage(
                        model = mediaMetadata?.thumbnailUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )

                    // Spotify style live waveform badge when playing
                    if (isPlaying) {
                        Box(
                            modifier = Modifier
                                .padding(16.dp)
                                .background(Color.Black.copy(alpha = 0.7f), CircleShape)
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                PlayingIndicator(
                                    color = TvColors.SpotifyGreenBright,
                                    modifier = Modifier.height(14.dp),
                                    bars = 3,
                                    barWidth = 3.dp,
                                    cornerRadius = 2.dp
                                )
                                Text(
                                    text = "PLAYING",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = TvColors.SpotifyGreenBright,
                                    letterSpacing = 1.sp
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.width(48.dp))

            // ── Right Column: Track Details, Progress Bar & Actions ───────────
            Column(
                modifier = Modifier
                    .weight(0.62f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.Center,
            ) {
                when (playerViewMode) {
                    1 -> {
                        // ── 10-Foot Synced TV Lyrics Mode ──────────────────────
                        TvSyncedLyricsView(
                            lyricsRaw = currentLyricsEntity?.lyrics,
                            currentPositionMs = positionMs,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                        )
                        Spacer(Modifier.height(12.dp))
                    }
                    2 -> {
                        // ── In-Player TV Queue Viewer Panel ───────────────────
                        TvInPlayerQueueView(
                            queueWindows = queueWindows,
                            currentIndex = playerConnection.player.currentMediaItemIndex,
                            onItemClick = { index ->
                                playerConnection.player.seekToDefaultPosition(index)
                                playerConnection.player.play()
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                        )
                        Spacer(Modifier.height(12.dp))
                    }
                    else -> {
                        // ── Standard Track Info Display ──────────────────────
                        mediaMetadata?.album?.title?.let { albumTitle ->
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(20.dp))
                                    .background(TvColors.OverlayLight)
                                    .padding(horizontal = 14.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = albumTitle,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = TvColors.TextSecondary,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Spacer(Modifier.height(16.dp))
                        }

                        // Track Title
                        Text(
                            text = mediaMetadata?.title ?: "",
                            style = MaterialTheme.typography.headlineLarge.copy(fontSize = 38.sp),
                            fontWeight = FontWeight.Black,
                            color = TvColors.TextPrimary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.basicMarquee()
                        )

                        Spacer(Modifier.height(8.dp))

                        // Artist Name(s)
                        Text(
                            text = mediaMetadata?.artists?.joinToString { it.name } ?: "",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Medium,
                            color = TvColors.TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        Spacer(Modifier.height(28.dp))
                    }
                }

                // ── Modern Spotify TV Progress Bar ────────────────────────────
                Column(modifier = Modifier.fillMaxWidth()) {
                    val progressSliderInteractionSource = remember { MutableInteractionSource() }
                    val isSliderFocused by progressSliderInteractionSource.collectIsFocusedAsState()

                    Slider(
                        value = progress,
                        onValueChange = { targetProgress ->
                            playerConnection.player.seekTo((targetProgress * durationMs).toLong())
                        },
                        colors = SliderDefaults.colors(
                            thumbColor = if (isSliderFocused) TvColors.SpotifyGreenBright else Color.White,
                            activeTrackColor = TvColors.SpotifyGreenBright,
                            inactiveTrackColor = Color.White.copy(alpha = 0.2f)
                        ),
                        interactionSource = progressSliderInteractionSource,
                        modifier = Modifier
                            .fillMaxWidth()
                            .onKeyEvent {
                                if (it.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                                    when (it.nativeKeyEvent.keyCode) {
                                        KeyEvent.KEYCODE_DPAD_LEFT -> {
                                            playerConnection.player.seekTo((playerConnection.player.currentPosition - 10_000L).coerceAtLeast(0L))
                                            true
                                        }
                                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                            playerConnection.player.seekTo((playerConnection.player.currentPosition + 10_000L).coerceAtMost(durationMs))
                                            true
                                        }
                                        else -> false
                                    }
                                } else false
                            }
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = formatMs(positionMs),
                            style = MaterialTheme.typography.labelMedium,
                            color = TvColors.TextSecondary,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = formatMs(durationMs),
                            style = MaterialTheme.typography.labelMedium,
                            color = TvColors.TextSecondary,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                Spacer(Modifier.height(20.dp))

                // ── Controls Row (Spotify-styled Buttons) ─────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // Shuffle
                    SpotifyTvIconButton(
                        iconRes = if (shuffleModeEnabled) R.drawable.shuffle_on else R.drawable.shuffle,
                        contentDescription = "Shuffle",
                        isActive = shuffleModeEnabled,
                        onClick = { playerConnection.player.shuffleModeEnabled = !shuffleModeEnabled }
                    )

                    // Previous
                    SpotifyTvIconButton(
                        iconRes = R.drawable.skip_previous,
                        contentDescription = "Previous",
                        enabled = canSkipPrevious,
                        onClick = { playerConnection.player.seekToPreviousMediaItem() }
                    )

                    // Play / Pause (Large Spotify Green Circle)
                    SpotifyTvPlayPauseButton(
                        isPlaying = isPlaying,
                        focusRequester = playButtonFocusRequester,
                        onClick = { playerConnection.player.togglePlayPause() }
                    )

                    // Next
                    SpotifyTvIconButton(
                        iconRes = R.drawable.skip_next,
                        contentDescription = "Next",
                        enabled = canSkipNext,
                        onClick = { playerConnection.player.seekToNextMediaItem() }
                    )

                    // Repeat
                    SpotifyTvIconButton(
                        iconRes = when (repeatMode) {
                            Player.REPEAT_MODE_ONE -> R.drawable.repeat_one_on
                            Player.REPEAT_MODE_ALL -> R.drawable.repeat_on
                            else -> R.drawable.repeat
                        },
                        contentDescription = "Repeat",
                        isActive = repeatMode != Player.REPEAT_MODE_OFF,
                        onClick = { playerConnection.player.toggleRepeatMode() }
                    )

                    // Like / Favorite
                    val isLiked = currentSong?.song?.liked == true
                    SpotifyTvIconButton(
                        iconRes = if (isLiked) R.drawable.favorite else R.drawable.favorite_border,
                        contentDescription = "Favorite",
                        tint = if (isLiked) Color(0xFFE91E63) else TvColors.TextPrimary,
                        onClick = { playerConnection.toggleLike() }
                    )

                    // Lyrics Toggle (In-Player TV Mode)
                    SpotifyTvIconButton(
                        iconRes = R.drawable.lyrics,
                        contentDescription = "Lyrics",
                        isActive = playerViewMode == 1,
                        onClick = { playerViewMode = if (playerViewMode == 1) 0 else 1 }
                    )

                    // Queue Toggle (In-Player TV Queue)
                    SpotifyTvIconButton(
                        iconRes = R.drawable.queue_music,
                        contentDescription = "Queue",
                        isActive = playerViewMode == 2,
                        onClick = { playerViewMode = if (playerViewMode == 2) 0 else 2 }
                    )
                }

                Spacer(Modifier.height(18.dp))

                // Remote D-pad Navigation Hints Pill
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black.copy(alpha = 0.4f))
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "◄► Controls",
                        style = MaterialTheme.typography.labelSmall,
                        color = TvColors.TextMuted,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text("•", color = TvColors.TextMuted)
                    Text(
                        text = "Center: Action",
                        style = MaterialTheme.typography.labelSmall,
                        color = TvColors.TextMuted,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text("•", color = TvColors.TextMuted)
                    Text(
                        text = "Back: Return",
                        style = MaterialTheme.typography.labelSmall,
                        color = TvColors.TextMuted,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

/**
 * 10-Foot Synced TV Lyrics Screen with Smooth Auto-Scroll & High Contrast Typography
 */
@Composable
private fun TvSyncedLyricsView(
    lyricsRaw: String?,
    currentPositionMs: Long,
    modifier: Modifier = Modifier
) {
    val lines = remember(lyricsRaw) {
        if (lyricsRaw.isNullOrBlank() || lyricsRaw == LYRICS_NOT_FOUND) {
            emptyList()
        } else {
            val parsed = LyricsUtils.parseLyrics(lyricsRaw)
            if (parsed.isNotEmpty()) parsed
            else {
                lyricsRaw.lines().filter { it.isNotBlank() }.mapIndexed { i, line ->
                    LyricsEntry(time = i * 4000L, text = line)
                }
            }
        }
    }

    val activeIndex = remember(lines, currentPositionMs) {
        if (lines.isEmpty()) -1
        else {
            val idx = lines.indexOfLast { it.time <= currentPositionMs }
            if (idx >= 0) idx else 0
        }
    }

    val listState = rememberLazyListState()

    LaunchedEffect(activeIndex) {
        if (activeIndex >= 0 && lines.isNotEmpty()) {
            listState.animateScrollToItem(maxOf(0, activeIndex - 2))
        }
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black.copy(alpha = 0.65f))
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        if (lines.isEmpty()) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxSize()
            ) {
                Text(
                    text = "No lyrics available for this song",
                    style = MaterialTheme.typography.titleMedium,
                    color = TvColors.TextMuted,
                    fontWeight = FontWeight.Medium
                )
            }
        } else {
            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(20.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                itemsIndexed(lines) { index, line ->
                    val isActive = index == activeIndex
                    val isPast = index < activeIndex

                    val textColor = when {
                        isActive -> TvColors.SpotifyGreenBright
                        isPast -> TvColors.TextMuted.copy(alpha = 0.5f)
                        else -> TvColors.TextSecondary
                    }

                    Text(
                        text = line.text,
                        style = if (isActive) MaterialTheme.typography.headlineSmall.copy(fontSize = 28.sp)
                               else MaterialTheme.typography.titleMedium.copy(fontSize = 20.sp),
                        fontWeight = if (isActive) FontWeight.ExtraBold else FontWeight.Medium,
                        color = textColor,
                        lineHeight = if (isActive) 36.sp else 28.sp
                    )
                }
            }
        }
    }
}

/**
 * In-Player TV Queue Viewer Panel for picking upcoming tracks without leaving the player
 */
@Composable
private fun TvInPlayerQueueView(
    queueWindows: List<androidx.media3.common.Timeline.Window>,
    currentIndex: Int,
    onItemClick: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()

    LaunchedEffect(currentIndex) {
        listState.animateScrollToItem(maxOf(0, currentIndex - 2))
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black.copy(alpha = 0.65f))
            .padding(horizontal = 20.dp, vertical = 14.dp)
    ) {
        if (queueWindows.isEmpty()) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxSize()
            ) {
                Text(
                    text = "Queue is empty",
                    style = MaterialTheme.typography.titleMedium,
                    color = TvColors.TextMuted
                )
            }
        } else {
            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                itemsIndexed(queueWindows) { index, window ->
                    val isPlayingItem = index == currentIndex
                    val mediaItem = window.mediaItem
                    val interactionSource = remember { MutableInteractionSource() }
                    val isFocused by interactionSource.collectIsFocusedAsState()

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                when {
                                    isFocused -> TvColors.CardBackgroundElevated
                                    isPlayingItem -> TvColors.OverlayLight
                                    else -> Color.Transparent
                                }
                            )
                            .border(
                                width = if (isFocused) 2.dp else 0.dp,
                                color = if (isFocused) Color.White else Color.Transparent,
                                shape = RoundedCornerShape(8.dp)
                            )
                            .padding(horizontal = 12.dp)
                            .focusable(interactionSource = interactionSource)
                            .onKeyEvent {
                                if (it.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                                    (it.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || it.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_ENTER)) {
                                    onItemClick(index)
                                    true
                                } else false
                            }
                            .clickable { onItemClick(index) }
                    ) {
                        // Track index / Playing wave indicator
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.size(32.dp)
                        ) {
                            if (isPlayingItem) {
                                PlayingIndicator(
                                    color = TvColors.SpotifyGreenBright,
                                    modifier = Modifier.height(14.dp),
                                    bars = 3,
                                    barWidth = 3.dp,
                                    cornerRadius = 2.dp
                                )
                            } else {
                                Text(
                                    text = "${index + 1}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = TvColors.TextMuted,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }

                        Spacer(Modifier.width(12.dp))

                        // Artwork
                        AsyncImage(
                            model = mediaItem.mediaMetadata.artworkUri,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(TvColors.CardBackground)
                        )

                        Spacer(Modifier.width(14.dp))

                        // Title & Artist
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = mediaItem.mediaMetadata.title?.toString() ?: "Unknown Track",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (isPlayingItem) FontWeight.Bold else FontWeight.Medium,
                                color = if (isPlayingItem || isFocused) TvColors.SpotifyGreenBright else TvColors.TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = mediaItem.mediaMetadata.artist?.toString() ?: "Unknown Artist",
                                style = MaterialTheme.typography.bodySmall,
                                color = TvColors.TextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Spotify TV style round Play/Pause Action Button
 */
@Composable
private fun SpotifyTvPlayPauseButton(
    isPlaying: Boolean,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val bgColor = if (isFocused) Color.White else TvColors.SpotifyGreenBright
    val iconColor = Color.Black

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(64.dp)
            .shadow(if (isFocused) 16.dp else 4.dp, CircleShape)
            .clip(CircleShape)
            .background(bgColor)
            .then(
                if (focusRequester != null) Modifier.focusRequester(focusRequester)
                else Modifier
            )
            .focusable(interactionSource = interactionSource)
            .onKeyEvent {
                if (it.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                    (it.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || it.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_ENTER)) {
                    onClick()
                    true
                } else false
            }
            .clickable(onClick = onClick)
    ) {
        Icon(
            painter = painterResource(if (isPlaying) R.drawable.pause else R.drawable.play),
            contentDescription = if (isPlaying) "Pause" else "Play",
            tint = iconColor,
            modifier = Modifier.size(32.dp)
        )
    }
}

/**
 * Focus-aware Spotify TV button with smooth hover/focus state
 */
@Composable
private fun SpotifyTvIconButton(
    iconRes: Int,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    isActive: Boolean = false,
    enabled: Boolean = true,
    tint: Color = TvColors.TextPrimary
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val effectiveTint = when {
        !enabled -> TvColors.TextTertiary
        isFocused -> Color.Black
        isActive -> TvColors.SpotifyGreenBright
        else -> tint
    }

    val background = when {
        isFocused -> Color.White
        isActive -> TvColors.SpotifyGreen.copy(alpha = 0.2f)
        else -> TvColors.OverlayLight
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(background)
            .border(
                width = if (isFocused) 2.dp else 0.dp,
                color = if (isFocused) Color.White else Color.Transparent,
                shape = CircleShape
            )
            .focusable(enabled = enabled, interactionSource = interactionSource)
            .onKeyEvent {
                if (enabled && it.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                    (it.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || it.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_ENTER)) {
                    onClick()
                    true
                } else false
            }
            .clickable(enabled = enabled, onClick = onClick)
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            tint = effectiveTint,
            modifier = Modifier.size(size * 0.52f)
        )
    }
}

private fun formatMs(ms: Long): String {
    val totalSecs = ms / 1000
    val minutes = totalSecs / 60
    val seconds = totalSecs % 60
    return "%d:%02d".format(minutes, seconds)
}
