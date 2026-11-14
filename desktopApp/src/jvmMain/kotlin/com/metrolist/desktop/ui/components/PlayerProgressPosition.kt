package com.metrolist.desktop.ui.components

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LongState
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import com.metrolist.desktop.state.playback.PlayerState
import com.metrolist.shared.model.isLive
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.ui.components.lyrics.rememberSmoothLyricsPositionState
import com.metrolist.shared.utils.formatDuration
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlin.math.ceil
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

internal fun progressFrameInterval(
    durationMs: Long,
    widthPx: Int,
    playbackSpeed: Float,
): Duration {
    if (durationMs <= 0L || widthPx <= 0) return 250.milliseconds
    val speed = playbackSpeed.takeIf { it.isFinite() }?.coerceAtLeast(0.02f) ?: 1f
    return ceil(durationMs.toDouble() / widthPx / speed).toLong().coerceIn(16L, 250L).milliseconds
}

@Composable
internal fun rememberPlayerProgressPosition(
    durationMs: Long,
    widthPx: Int,
    isLive: Boolean,
): LongState {
    val connection =
        remember {
            PlayerState.castManager.state
                .map { it.isConnected }
                .distinctUntilChanged()
        }
    val isCasting by connection.collectAsState(PlayerState.castManager.state.value.isConnected)
    val speed = Settings.shared.playbackSpeed
    return rememberSmoothLyricsPositionState(
        positionFlow = PlayerState.playbackPosition,
        isPlaying = PlayerState.isPlaying,
        songId = PlayerState.currentTrack?.id,
        frameInterval = progressFrameInterval(durationMs, widthPx, speed),
        playbackSpeed = speed,
        isCasting = isCasting,
        enabled = !isLive,
    )
}

/** Collects milliseconds rounded down to whole seconds, so readers recompose once a second instead of on every 50 ms sample. */
@Composable
internal fun StateFlow<Long>.collectAsSecondsState(): State<Long> {
    val seconds = remember(this) { map { it / 1000L * 1000L }.distinctUntilChanged() }
    return seconds.collectAsState(value / 1000L * 1000L)
}

@Composable
internal fun rememberPlaybackSecondPosition(): State<Long> = PlayerState.playbackPosition.collectAsSecondsState()

@Composable
internal fun playerIsLiveStream(): Boolean {
    val resource by PlayerState.player.currentPlaybackResource.collectAsState()
    val song by PlayerState.player.currentSong.collectAsState()
    return PlayerState.currentTrack?.isLive == true || song?.isLive == true || resource?.isLive == true
}

@Composable
internal fun PlayerProgressTime(
    positionProvider: () -> Long,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    textAlign: TextAlign? = null,
) {
    val latestPosition by rememberUpdatedState(positionProvider)
    val second by remember { derivedStateOf { latestPosition() / 1000L } }
    Text(
        text = formatDuration(second * 1000L),
        style = style,
        color = color,
        modifier = modifier,
        textAlign = textAlign,
    )
}
