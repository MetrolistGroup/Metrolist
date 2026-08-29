/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.tv

import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.media3.common.Player
import androidx.navigation.NavController
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.constants.ShowLyricsKey
import com.metrolist.music.extensions.togglePlayPause
import com.metrolist.music.extensions.toggleRepeatMode
import com.metrolist.music.ui.component.Lyrics
import com.metrolist.music.ui.component.PlayingIndicator
import com.metrolist.music.ui.theme.PlayerColorExtractor
import com.metrolist.music.utils.rememberPreference
import com.metrolist.music.viewmodels.LyricsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Spotify TV Inspired Playback UI
 *
 * Left side: Large Album Art with rounded corners, subtle elevation, currently playing badge
 * Right side: Clean track typography, dynamic backdrop extracted from album cover,
 * Spotify style progress bar, transport controls with Spotify green accents,
 * lyrics overlay support, and direct D-pad remote shortcuts.
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

    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(1L) }

    LaunchedEffect(playerConnection) {
        while (isActive) {
            positionMs = playerConnection.player.currentPosition
            durationMs = playerConnection.player.duration.coerceAtLeast(1L)
            delay(500L)
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

    // Lyrics visibility toggle
    var showLyrics by remember { mutableStateOf(false) }

    // D-Pad focus & controls
    val focusRequester = remember { FocusRequester() }
    val playButtonFocusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(TvColors.BackgroundPureBlack)
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent { keyEvent ->
                val action = keyEvent.nativeKeyEvent.action
                val code = keyEvent.nativeKeyEvent.keyCode
                val isDown = action == KeyEvent.ACTION_DOWN
                val isFirst = keyEvent.nativeKeyEvent.repeatCount == 0

                when {
                    // ── Play/Pause ────────────────────────────────────────────
                    isDown && isFirst && (code == KeyEvent.KEYCODE_DPAD_CENTER ||
                        code == KeyEvent.KEYCODE_ENTER ||
                        code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) -> {
                        playerConnection.player.togglePlayPause()
                        true
                    }

                    // ── Seek Left/Right ─────────────────────────────────────────
                    isDown && (code == KeyEvent.KEYCODE_DPAD_LEFT ||
                        code == KeyEvent.KEYCODE_MEDIA_REWIND) -> {
                        playerConnection.player.seekTo(
                            (playerConnection.player.currentPosition - 10_000L).coerceAtLeast(0L)
                        )
                        true
                    }
                    isDown && (code == KeyEvent.KEYCODE_DPAD_RIGHT ||
                        code == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) -> {
                        playerConnection.player.seekTo(
                            (playerConnection.player.currentPosition + 10_000L).coerceAtMost(durationMs)
                        )
                        true
                    }

                    // ── Up: Queue screen ───────────────────────────────────────
                    isDown && isFirst && code == KeyEvent.KEYCODE_DPAD_UP -> {
                        navController.navigate("tv_queue") { launchSingleTop = true }
                        true
                    }

                    // ── Down: Toggle Lyrics / Shuffle ──────────────────────────
                    isDown && isFirst && code == KeyEvent.KEYCODE_DPAD_DOWN -> {
                        showLyrics = !showLyrics
                        true
                    }

                    // ── Media keys ─────────────────────────────────────────────
                    isDown && isFirst && code == KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                        playerConnection.player.seekToPreviousMediaItem()
                        true
                    }
                    isDown && isFirst && code == KeyEvent.KEYCODE_MEDIA_NEXT -> {
                        playerConnection.player.seekToNextMediaItem()
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
                .padding(horizontal = 64.dp, vertical = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // ── Left Column: Album Art & Track Status ────────────────────────
            Column(
                modifier = Modifier
                    .weight(0.42f)
                    .fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box(
                    contentAlignment = Alignment.BottomStart,
                    modifier = Modifier
                        .fillMaxHeight(0.78f)
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

            Spacer(Modifier.width(56.dp))

            // ── Right Column: Track Details, Progress Bar & Actions ───────────
            Column(
                modifier = Modifier
                    .weight(0.58f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.Center,
            ) {
                // If lyrics mode is on, show synchronized lyrics block
                if (showLyrics) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color.Black.copy(alpha = 0.5f))
                            .padding(24.dp)
                    ) {
                        Lyrics(
                            sliderPositionProvider = { positionMs },
                            showLyrics = true,
                            lyricsViewModel = lyricsViewModel,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                } else {
                    // Album badge / tag
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

                    Spacer(Modifier.height(36.dp))
                }

                // ── Modern Spotify TV Progress Bar ────────────────────────────
                Column(modifier = Modifier.fillMaxWidth()) {
                    Slider(
                        value = progress,
                        onValueChange = { targetProgress ->
                            playerConnection.player.seekTo((targetProgress * durationMs).toLong())
                        },
                        colors = SliderDefaults.colors(
                            thumbColor = Color.White,
                            activeTrackColor = TvColors.SpotifyGreenBright,
                            inactiveTrackColor = Color.White.copy(alpha = 0.2f)
                        ),
                        modifier = Modifier.fillMaxWidth()
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

                Spacer(Modifier.height(28.dp))

                // ── Controls Row (Spotify-styled Buttons) ─────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
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

                    // Lyrics Toggle
                    SpotifyTvIconButton(
                        iconRes = R.drawable.lyrics,
                        contentDescription = "Lyrics",
                        isActive = showLyrics,
                        onClick = { showLyrics = !showLyrics }
                    )

                    // Queue Screen
                    SpotifyTvIconButton(
                        iconRes = R.drawable.queue_music,
                        contentDescription = "Queue",
                        onClick = { navController.navigate("tv_queue") { launchSingleTop = true } }
                    )
                }

                Spacer(Modifier.height(24.dp))

                // Remote D-pad Navigation Hints Pill
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black.copy(alpha = 0.4f))
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "OK Play/Pause",
                        style = MaterialTheme.typography.labelSmall,
                        color = TvColors.TextMuted,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text("•", color = TvColors.TextMuted)
                    Text(
                        text = "◄► Seek ±10s",
                        style = MaterialTheme.typography.labelSmall,
                        color = TvColors.TextMuted,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text("•", color = TvColors.TextMuted)
                    Text(
                        text = "▲ Queue",
                        style = MaterialTheme.typography.labelSmall,
                        color = TvColors.TextMuted,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text("•", color = TvColors.TextMuted)
                    Text(
                        text = "▼ Lyrics",
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
 * Spotify TV style round Play/Pause Action Button
 */
@Composable
private fun SpotifyTvPlayPauseButton(
    isPlaying: Boolean,
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
            .focusable(interactionSource = interactionSource)
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
        isActive -> TvColors.SpotifyGreenBright
        isFocused -> Color.White
        else -> tint
    }

    val background = when {
        isFocused -> TvColors.OverlayFocused
        isActive -> TvColors.SpotifyGreen.copy(alpha = 0.15f)
        else -> Color.Transparent
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(background)
            .border(
                width = if (isFocused) 2.dp else 0.dp,
                color = if (isFocused) Color.White.copy(alpha = 0.6f) else Color.Transparent,
                shape = CircleShape
            )
            .focusable(enabled = enabled, interactionSource = interactionSource)
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
