/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.android.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSliderState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.window.core.layout.WindowSizeClass
import com.metrolist.android.playback.LocalPlayerConnection
import com.metrolist.android.playback.PlayerConnection
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.mute
import com.metrolist.shared.generated.resources.pause
import com.metrolist.shared.generated.resources.play
import com.metrolist.shared.generated.resources.repeat_mode_all
import com.metrolist.shared.generated.resources.repeat_mode_off
import com.metrolist.shared.generated.resources.repeat_mode_one
import com.metrolist.shared.generated.resources.shuffle
import com.metrolist.shared.generated.resources.unmute
import com.metrolist.shared.generated.resources.volume
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.isLive
import com.metrolist.shared.playback.PendingSeekPosition
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.settings.SliderStyle
import com.metrolist.shared.settings.displayName
import com.metrolist.shared.ui.components.LiveBadge
import com.metrolist.shared.ui.components.PlayerPrimaryControls
import com.metrolist.shared.ui.components.sliders.PlaybackProgressSlider
import com.metrolist.shared.ui.components.sliders.rememberPendingSeekPosition
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.utils.formatDuration
import org.jetbrains.compose.resources.stringResource

internal val DesktopMiniPlayerHeight = 112.dp

internal fun shouldUseDesktopMiniPlayer(
    useDesktopUi: Boolean,
    smallestScreenWidthDp: Int,
): Boolean = useDesktopUi && smallestScreenWidthDp >= WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@UnstableApi
@Composable
internal fun DesktopMiniPlayerContent(
    song: SongItem,
    isPlaying: Boolean,
    isLoading: Boolean,
    isGuest: Boolean,
    isMuted: Boolean,
    active: Boolean,
    onTogglePlayPause: () -> Unit,
    marqueeModifier: Modifier,
    contentColor: Color,
    secondaryContentColor: Color,
    artwork: @Composable () -> Unit,
    actions: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val player = LocalPlayerConnection.current ?: return
    val canSkipPrevious by player.canSkipPrevious.collectAsState()
    val canSkipNext by player.canSkipNext.collectAsState()
    val shuffled by player.shuffleModeEnabled.collectAsState()
    val repeatMode by player.repeatMode.collectAsState()
    val volume by player.volume.collectAsState()
    val volumeLabel = stringResource(Res.string.volume)
    val playbackResource by player.currentPlaybackResource.collectAsState()
    val isLive = song.isLive || playbackResource?.isLive == true
    Column(modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        DesktopMiniPlayerProgress(player, song.id, isPlaying, active, enabled = !isGuest, contentColor)
        Row(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.weight(1.4f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                artwork()
                Column(Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (isLive) {
                            LiveBadge(compact = true)
                        }
                        Text(song.title, modifier = marqueeModifier, color = contentColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(
                        song.artists.joinToString { it.displayName },
                        modifier = marqueeModifier,
                        color = secondaryContentColor,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                actions()
            }
            PlayerPrimaryControls(
                isPlaying = if (isGuest) !isMuted else isPlaying,
                isLoading = isLoading,
                playText = stringResource(if (isGuest) Res.string.unmute else Res.string.play),
                pauseText = stringResource(if (isGuest) Res.string.mute else Res.string.pause),
                onSkipPrevious = player::skipToPrevious.takeIf { canSkipPrevious && !isGuest },
                onTogglePlay = onTogglePlayPause,
                onSkipNext = player::skipToNext.takeIf { canSkipNext && !isGuest },
                modifier = Modifier.width(240.dp),
                buttonHeight = 48.dp,
                sideButtonHeight = 40.dp,
            )
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val volumeSliderState = rememberSliderState(value = volume)
                volumeSliderState.value = volume
                IconButton(onClick = player::toggleShuffle, enabled = !isGuest, shapes = IconButtonDefaults.shapes()) {
                    Icon(
                        MetrolistIcons.Rounded.Shuffle,
                        stringResource(Res.string.shuffle),
                        tint = if (shuffled) MaterialTheme.colorScheme.primary else secondaryContentColor,
                    )
                }
                IconButton(onClick = player::cycleRepeatMode, enabled = !isGuest, shapes = IconButtonDefaults.shapes()) {
                    Icon(
                        if (repeatMode == Player.REPEAT_MODE_ONE) MetrolistIcons.Rounded.RepeatOne else MetrolistIcons.Rounded.Repeat,
                        stringResource(
                            when (repeatMode) {
                                Player.REPEAT_MODE_ONE -> Res.string.repeat_mode_one
                                Player.REPEAT_MODE_ALL -> Res.string.repeat_mode_all
                                else -> Res.string.repeat_mode_off
                            },
                        ),
                        tint = if (repeatMode != Player.REPEAT_MODE_OFF) MaterialTheme.colorScheme.primary else secondaryContentColor,
                    )
                }
                Slider(
                    state = volumeSliderState,
                    onValueChange = {
                        volumeSliderState.value = it
                        player.setVolume(it)
                    },
                    modifier = Modifier.weight(1f, fill = false).widthIn(max = 112.dp).semantics { contentDescription = volumeLabel },
                )
            }
        }
    }
}

@UnstableApi
@Composable
private fun DesktopMiniPlayerProgress(
    player: PlayerConnection,
    songId: String,
    isPlaying: Boolean,
    active: Boolean,
    enabled: Boolean,
    contentColor: Color,
) {
    val currentSong by player.currentSong.collectAsState()
    val playbackResource by player.currentPlaybackResource.collectAsState()
    val isLive = currentSong?.isLive == true || playbackResource?.isLive == true
    val position = if (active) player.currentPosition.collectAsState() else remember { mutableStateOf(player.currentPosition.value) }
    val duration by player.duration.collectAsState()
    val buffered by player.bufferedProgress.collectAsState()
    var draggedFraction by remember(songId) { mutableStateOf<Float?>(null) }
    var pendingSeek by rememberPendingSeekPosition(
        songId = songId,
        durationMs = duration,
        enabled = enabled && !isLive && duration > 0L,
        position = { position.value },
    )
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(formatDuration(pendingSeek?.targetMs ?: position.value), color = contentColor, style = MaterialTheme.typography.labelSmall)
        PlaybackProgressSlider(
            value = 0f,
            valueProvider = {
                if (isLive) {
                    1f
                } else {
                    draggedFraction
                        ?: if (duration > 0) (pendingSeek?.targetMs ?: position.value).toFloat() / duration else 0f
                }
            },
            onValueChange = {
                if (!isLive) {
                    pendingSeek = null
                    draggedFraction = it
                }
            },
            onValueChangeFinished = {
                if (!isLive) {
                    draggedFraction?.let {
                        val targetMs = (it * duration).toLong()
                        pendingSeek = PendingSeekPosition(targetMs, player.currentPosition.value)
                        player.seekTo(targetMs)
                    }
                    draggedFraction = null
                }
            },
            isPlaying = isPlaying,
            enabled = enabled && !isLive && duration > 0,
            showThumb = !isLive,
            animate = active,
            bufferedFraction = if (isLive) 1f else buffered,
            modifier =
                Modifier.weight(1f).then(
                    if (Settings.shared.sliderStyle == SliderStyle.DEFAULT) Modifier else Modifier.height(40.dp),
                ),
        )
        if (isLive) {
            LiveBadge()
        } else {
            Text(formatDuration(duration), color = contentColor, style = MaterialTheme.typography.labelSmall)
        }
    }
}
