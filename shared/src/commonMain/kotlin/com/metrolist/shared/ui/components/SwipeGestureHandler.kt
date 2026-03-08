package com.metrolist.shared.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.add_to_queue
import com.metrolist.shared.generated.resources.play_next
import com.metrolist.shared.generated.resources.remove_from_playlist
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.ui.haptics.HapticEvent
import com.metrolist.shared.ui.haptics.rememberMetrolistHaptics
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.playback.LocalPlaybackController
import com.metrolist.shared.ui.theme.ComponentShapes
import me.saket.swipe.SwipeAction
import me.saket.swipe.SwipeableActionsBox
import org.jetbrains.compose.resources.stringResource

@Composable
fun SwipeGestureHandler(
    onSwipeLeft: () -> Unit,
    onSwipeRight: () -> Unit,
    onFeedback: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    removeOnSwipeLeft: Boolean = false,
    enableSwipeRight: Boolean = true,
    content: @Composable () -> Unit,
) {
    if (!enabled) {
        Box(modifier) { content() }
        return
    }

    val inheritedFeedback = LocalHapticFeedback.current
    val haptics = rememberMetrolistHaptics()
    val thresholdHaptics =
        remember(haptics) {
            object : HapticFeedback {
                override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
                    haptics.perform(HapticEvent.DRAG_SNAP)
                }
            }
        }
    val playNext = stringResource(Res.string.play_next)
    val leftAction = stringResource(if (removeOnSwipeLeft) Res.string.remove_from_playlist else Res.string.add_to_queue)

    val currentOnSwipeLeft by rememberUpdatedState(onSwipeLeft)
    val currentOnSwipeRight by rememberUpdatedState(onSwipeRight)
    val currentOnFeedback by rememberUpdatedState(onFeedback)

    fun commit(
        action: () -> Unit,
        label: String,
    ) {
        action()
        haptics.perform(HapticEvent.CONFIRM)
        currentOnFeedback(label)
    }

    val startBackground = MaterialTheme.colorScheme.primaryContainer
    val endBackground = MaterialTheme.colorScheme.secondaryContainer
    val startAction =
        remember(playNext, startBackground, haptics) {
            SwipeAction(
                onSwipe = { commit(currentOnSwipeRight, playNext) },
                icon = {
                    Icon(
                        MetrolistIcons.AutoMirrored.Filled.PlaylistPlay,
                        contentDescription = playNext,
                        modifier = Modifier.padding(16.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                },
                background = startBackground,
            )
        }
    val endAction =
        remember(leftAction, removeOnSwipeLeft, endBackground, haptics) {
            SwipeAction(
                onSwipe = { commit(currentOnSwipeLeft, leftAction) },
                icon = {
                    Icon(
                        if (removeOnSwipeLeft) MetrolistIcons.Outlined.Delete else MetrolistIcons.AutoMirrored.Filled.QueueMusic,
                        contentDescription = leftAction,
                        modifier = Modifier.padding(16.dp),
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                },
                background = endBackground,
            )
        }

    CompositionLocalProvider(LocalHapticFeedback provides thresholdHaptics) {
        SwipeableActionsBox(
            modifier = modifier.clip(ComponentShapes.CardShape),
            swipeThreshold = 64.dp,
            backgroundUntilSwipeThreshold = MaterialTheme.colorScheme.surfaceContainerHigh,
            startActions = if (enableSwipeRight) listOf(startAction) else emptyList(),
            endActions = listOf(endAction),
        ) {
            CompositionLocalProvider(LocalHapticFeedback provides inheritedFeedback) { content() }
        }
    }
}

@Composable
fun SongSwipeActions(
    song: SongItem,
    onFeedback: (String) -> Unit,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val playback = LocalPlaybackController.current
    SwipeGestureHandler(
        onSwipeLeft = { playback.addToQueue(listOf(song)) },
        onSwipeRight = { playback.playNext(listOf(song)) },
        onFeedback = onFeedback,
        enabled = enabled && Settings.shared.swipeToSong,
        content = content,
    )
}
