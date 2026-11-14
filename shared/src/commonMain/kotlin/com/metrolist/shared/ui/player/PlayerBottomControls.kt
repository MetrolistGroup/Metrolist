/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.shared.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.rememberSliderState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.metrolist.shared.constants.QueuePeekHeight
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.hours
import com.metrolist.shared.generated.resources.hours_and_minutes
import com.metrolist.shared.generated.resources.less_than_one_minute
import com.metrolist.shared.generated.resources.minutes
import com.metrolist.shared.generated.resources.more_options
import com.metrolist.shared.generated.resources.mute
import com.metrolist.shared.generated.resources.sleep_timer
import com.metrolist.shared.generated.resources.sleep_timer_end_current_song_description
import com.metrolist.shared.generated.resources.sleep_timer_remaining
import com.metrolist.shared.generated.resources.unmute
import com.metrolist.shared.generated.resources.volume
import com.metrolist.shared.model.RepeatMode
import com.metrolist.shared.model.isVideoSong
import com.metrolist.shared.model.isVideoType
import com.metrolist.shared.playback.LocalPlayerSession
import com.metrolist.shared.playback.SleepTimerState
import com.metrolist.shared.ui.LocalIsTv
import com.metrolist.shared.ui.components.PlayerConnectedButtonGroup
import com.metrolist.shared.ui.components.focusOutline
import com.metrolist.shared.ui.components.formatSleepTimerRemaining
import com.metrolist.shared.ui.components.playerControlBlend
import com.metrolist.shared.ui.components.playerInactiveControlContainerColor
import com.metrolist.shared.ui.haptics.HapticEvent
import com.metrolist.shared.ui.haptics.rememberMetrolistHaptics
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.navigation.navigationPopupSource
import com.metrolist.shared.ui.playback.retainPlaybackKeyFocus
import com.metrolist.shared.utils.Logger
import com.metrolist.shared.youtube.playback.PlaybackResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun Modifier.focusedPlayerBorder(
    shape: Shape,
    color: Color = MaterialTheme.colorScheme.primary,
    borderWidth: Dp = 3.dp,
): Modifier = focusOutline(shape, color, borderWidth)

@Composable
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
internal fun PlayerBottomControls(
    sleepTimerState: SleepTimerState,
    onShowSleepTimer: () -> Unit,
    isShuffleOn: Boolean,
    onToggleShuffle: (() -> Unit)?,
    repeatMode: Int,
    onToggleRepeat: () -> Unit,
    navigationBarBottomPadding: Dp,
    onOpenQueue: () -> Unit,
    onShowPlayerMenu: () -> Unit,
    isLyricsVisible: Boolean,
    onIsLyricsVisibleChange: (Boolean) -> Unit,
    isVideoAvailable: Boolean = false,
    onFullscreenClick: (() -> Unit)? = null,
    isFullscreen: Boolean = false,
    onDeviceConnectClick: (() -> Unit)? = null,
    isDeviceConnectActive: Boolean = false,
    modifier: Modifier = Modifier,
    isListenTogetherGuest: Boolean = false,
    controlHeight: Dp = QueuePeekHeight,
    touchTarget: Dp = 40.dp,
    showQueueHandle: Boolean = true,
) {
    val hapticManager = rememberMetrolistHaptics()
    val playerConnection = LocalPlayerSession.current
    val volume by playerConnection?.volume?.collectAsState() ?: remember { mutableFloatStateOf(1f) }
    var lastAudibleVolume by remember { mutableFloatStateOf(1f) }

    LaunchedEffect(volume) {
        if (volume > 0.01f) lastAudibleVolume = volume
    }

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .focusProperties { canFocus = false }
                .clickable(
                    enabled = showQueueHandle,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {
                        hapticManager.perform(HapticEvent.PRESS)
                        onOpenQueue()
                    },
                ).padding(bottom = navigationBarBottomPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (showQueueHandle) {
            Spacer(modifier = Modifier.height(8.dp))
            if (LocalIsTv.current) {
                Box(
                    modifier =
                        Modifier
                            .width(36.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)),
                )
            } else {
                Spacer(modifier = Modifier.height(4.dp))
            }
            Spacer(modifier = Modifier.height(12.dp))
        }
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            PlayerConnectedButtonGroup(
                onQueueClick = onOpenQueue,
                onLyricsClick = {
                    Logger.d("Haptics", "Trigger PlayerScreen lyrics button click")
                    hapticManager.toggle(!isLyricsVisible)
                    onIsLyricsVisibleChange(!isLyricsVisible)
                },
                isLyricsActive = isLyricsVisible,
                isQueueActive = false,
                modifier = Modifier.weight(1f, fill = false),
                compact = false,
                volume = volume,
                onVolumeChange = { newVolume -> playerConnection?.setVolume(newVolume) },
                onShuffleClick =
                    onToggleShuffle?.takeUnless { isListenTogetherGuest }?.let { toggleShuffle ->
                        {
                            Logger.d("Haptics", "Trigger PlayerScreen shuffle button click")
                            hapticManager.toggle(!isShuffleOn)
                            toggleShuffle()
                        }
                    },
                isShuffled = isShuffleOn,
                onRepeatClick =
                    if (!isListenTogetherGuest) {
                        {
                            Logger.d("Haptics", "Trigger PlayerScreen repeat button click")
                            hapticManager.perform(HapticEvent.PRESS)
                            onToggleRepeat()
                        }
                    } else {
                        null
                    },
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
                onDeviceConnectClick = onDeviceConnectClick,
                isDeviceConnectActive = isDeviceConnectActive,
                touchTarget = touchTarget,
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (isListenTogetherGuest) {
                    IconButton(
                        onClick = {
                            hapticManager.perform(HapticEvent.PRESS)
                            if (volume > 0.01f) {
                                playerConnection?.setVolume(0f)
                            } else {
                                playerConnection?.setVolume(lastAudibleVolume.coerceAtLeast(0.25f))
                            }
                        },
                        modifier = Modifier.size(touchTarget),
                        shapes = IconButtonDefaults.shapes(),
                    ) {
                        Icon(
                            imageVector =
                                if (volume > 0.01f) {
                                    MetrolistIcons.AutoMirrored.Filled.VolumeOff
                                } else {
                                    MetrolistIcons.AutoMirrored.Filled.VolumeUp
                                },
                            contentDescription = stringResource(if (volume > 0.01f) Res.string.mute else Res.string.unmute),
                            modifier = Modifier.size(22.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                FilledTonalIconButton(
                    onClick = {
                        hapticManager.perform(HapticEvent.PRESS)
                        onShowPlayerMenu()
                    },
                    shapes = IconButtonDefaults.shapes(shape = CircleShape),
                    modifier =
                        Modifier
                            .size(touchTarget)
                            .focusedPlayerBorder(CircleShape)
                            .navigationPopupSource()
                            .playerControlBlend(),
                    colors =
                        IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = playerInactiveControlContainerColor(),
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        ),
                ) {
                    Icon(
                        MetrolistIcons.Default.MoreVert,
                        contentDescription = stringResource(Res.string.more_options),
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlayerVolumeControl() {
    val playerConnection = LocalPlayerSession.current ?: return
    val volume by playerConnection.volume.collectWhilePlayerActive()
    val sliderState = rememberSliderState(value = volume)
    sliderState.value = volume
    val volumeDescription = stringResource(Res.string.volume)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(MetrolistIcons.AutoMirrored.Filled.VolumeUp, contentDescription = null)
        Slider(
            state = sliderState,
            onValueChange = playerConnection::setVolume,
            modifier = Modifier.retainPlaybackKeyFocus().weight(1f).semantics { contentDescription = volumeDescription },
        )
    }
}

internal fun shouldShowVideoButton(
    isListenTogetherGuest: Boolean,
    hideVideoSongs: Boolean,
    videoPlaybackEnabled: Boolean,
    songId: String?,
    musicVideoType: String?,
): Boolean {
    if (isListenTogetherGuest) return false
    if (hideVideoSongs || !videoPlaybackEnabled) return false
    if (songId == null) return false
    if (songId.startsWith("local_")) return false
    return true
}

@Composable
internal fun sleepTimerContentDescription(state: SleepTimerState): String {
    if (state.isActive && state.stopAfterCurrentSong) return stringResource(Res.string.sleep_timer_end_current_song_description)
    val remainingMs = state.remainingMs?.takeIf { state.isActive } ?: return stringResource(Res.string.sleep_timer)
    val totalMinutes = (remainingMs / 60_000L).coerceAtLeast(0L)
    val hours = totalMinutes / 60L
    val minutes = totalMinutes % 60L
    val duration =
        when {
            hours > 0L && minutes > 0L -> {
                stringResource(
                    Res.string.hours_and_minutes,
                    pluralStringResource(Res.plurals.hours, hours.toInt(), hours),
                    pluralStringResource(Res.plurals.minutes, minutes.toInt(), minutes),
                )
            }

            hours > 0L -> {
                pluralStringResource(Res.plurals.hours, hours.toInt(), hours)
            }

            totalMinutes > 0L -> {
                pluralStringResource(Res.plurals.minutes, totalMinutes.toInt(), totalMinutes)
            }

            else -> {
                stringResource(Res.string.less_than_one_minute)
            }
        }
    return stringResource(Res.string.sleep_timer_remaining, duration)
}
