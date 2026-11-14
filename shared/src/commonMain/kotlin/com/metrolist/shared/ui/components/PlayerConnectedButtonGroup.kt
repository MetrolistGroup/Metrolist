package com.metrolist.shared.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.metrolist.shared.model.RepeatMode

@Composable
expect fun PlayerConnectedButtonGroup(
    onQueueClick: () -> Unit,
    onLyricsClick: (() -> Unit)?,
    isLyricsActive: Boolean = false,
    isQueueActive: Boolean = false,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    volume: Float = 1f,
    onVolumeChange: (Float) -> Unit = {},
    onMiniplayerClick: (() -> Unit)? = null,
    showMiniplayer: Boolean = false,
    onFullscreenClick: (() -> Unit)? = null,
    isFullscreen: Boolean = false,
    onShuffleClick: (() -> Unit)? = null,
    isShuffled: Boolean = false,
    onRepeatClick: (() -> Unit)? = null,
    repeatMode: RepeatMode = RepeatMode.OFF,
    onSleepTimerClick: (() -> Unit)? = null,
    isSleepTimerActive: Boolean = false,
    sleepTimerDisplay: String? = null,
    sleepTimerContentDescription: String? = null,
    onVideoClick: (() -> Unit)? = null,
    isVideoActive: Boolean = false,
    onSubtitleClick: (() -> Unit)? = null,
    isSubtitleActive: Boolean = false,
    onCastClick: (() -> Unit)? = null,
    isCasting: Boolean = false,
    onDeviceConnectClick: (() -> Unit)? = null,
    isDeviceConnectActive: Boolean = false,
    touchTarget: Dp = 40.dp,
    sectioned: Boolean = false,
)
