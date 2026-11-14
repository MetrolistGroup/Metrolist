package com.metrolist.shared.ui.player

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.action_download
import com.metrolist.shared.generated.resources.action_like
import com.metrolist.shared.generated.resources.add_to_playlist
import com.metrolist.shared.generated.resources.clear_selection
import com.metrolist.shared.generated.resources.done
import com.metrolist.shared.generated.resources.more_options
import com.metrolist.shared.generated.resources.queue
import com.metrolist.shared.generated.resources.queue_move
import com.metrolist.shared.generated.resources.queue_multi_select
import com.metrolist.shared.generated.resources.remove_from_queue
import com.metrolist.shared.generated.resources.repeat_mode_all
import com.metrolist.shared.generated.resources.repeat_mode_off
import com.metrolist.shared.generated.resources.repeat_mode_one
import com.metrolist.shared.generated.resources.select_all
import com.metrolist.shared.generated.resources.shuffle
import com.metrolist.shared.generated.resources.sleep_timer
import com.metrolist.shared.generated.resources.unlike
import com.metrolist.shared.ui.components.BasicAlertDialog
import com.metrolist.shared.ui.navigation.NavigationBackHandler
import com.metrolist.shared.ui.tv.RequestTvFocus
import com.metrolist.shared.ui.tv.tvFocusHighlight
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TvQueueMenu(
    state: QueueScreenState,
    track: QueueTrack?,
    selecting: Boolean,
    canControlPlayback: Boolean,
    canMove: Boolean,
    onMove: () -> Unit,
    onMultiSelect: () -> Unit,
    onFinish: () -> Unit,
    onEvent: (QueueEvent) -> Unit,
    onDismiss: () -> Unit,
) {
    BasicAlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.fillMaxSize(),
    ) {
        NavigationBackHandler(onBack = onDismiss)
        Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.CenterEnd) {
            Surface(shape = RoundedCornerShape(24.dp), modifier = Modifier.width(360.dp)) {
                val focusRequester = remember { FocusRequester() }
                Column(
                    modifier =
                        Modifier
                            .focusRequester(focusRequester)
                            .focusGroup()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        if (selecting) stringResource(Res.string.queue_multi_select) else track?.title ?: stringResource(Res.string.queue),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    RequestTvFocus(focusRequester)
                    if (selecting) {
                        TvQueueMenuAction(Res.string.select_all) {
                            onEvent(QueueEvent.SelectAll)
                            onDismiss()
                        }
                        TvQueueMenuAction(Res.string.clear_selection, onClick = onFinish)
                        val hasSelection = state.selectedEntryKeys.isNotEmpty()
                        TvQueueMenuAction(Res.string.add_to_playlist, hasSelection) {
                            onEvent(QueueEvent.AddSelectedToPlaylist)
                            onDismiss()
                        }
                        TvQueueMenuAction(Res.string.remove_from_queue, hasSelection && canControlPlayback) {
                            onEvent(QueueEvent.DeleteSelected)
                            onDismiss()
                        }
                        TvQueueMenuAction(Res.string.action_download, hasSelection) {
                            onEvent(QueueEvent.DownloadSelected)
                            onDismiss()
                        }
                        TvQueueMenuAction(Res.string.action_like, hasSelection) {
                            onEvent(QueueEvent.LikeSelected)
                            onDismiss()
                        }
                    } else if (track != null) {
                        if (canMove) TvQueueMenuAction(Res.string.queue_move, onClick = onMove)
                        if (track.canRemove && !track.isSuggested) TvQueueMenuAction(Res.string.queue_multi_select, onClick = onMultiSelect)
                        TvQueueMenuAction(Res.string.add_to_playlist) {
                            onEvent(QueueEvent.AddTrackToPlaylist(track.entryKey))
                            onDismiss()
                        }
                        if (canControlPlayback && track.canRemove) {
                            TvQueueMenuAction(Res.string.remove_from_queue) {
                                onEvent(QueueEvent.RemoveTrack(track.entryKey))
                                onDismiss()
                            }
                        }
                        TvQueueMenuAction(Res.string.action_download) {
                            onEvent(QueueEvent.DownloadTrack(track.entryKey))
                            onDismiss()
                        }
                        TvQueueMenuAction(if (track.isLiked) Res.string.unlike else Res.string.action_like) {
                            onEvent(QueueEvent.ToggleTrackLike(track.entryKey))
                            onDismiss()
                        }
                        TvQueueMenuAction(Res.string.more_options) {
                            onDismiss()
                            onEvent(QueueEvent.OpenTrackMenu(track.entryKey))
                        }
                    }
                    if (canControlPlayback) {
                        TvQueueMenuAction(Res.string.shuffle, selected = state.isShuffleOn) { onEvent(QueueEvent.ToggleShuffle) }
                        TvQueueMenuAction(
                            when (state.repeatMode) {
                                1 -> Res.string.repeat_mode_one
                                2 -> Res.string.repeat_mode_all
                                else -> Res.string.repeat_mode_off
                            },
                        ) { onEvent(QueueEvent.CycleRepeat) }
                        TvQueueMenuAction(Res.string.sleep_timer) {
                            onDismiss()
                            onEvent(QueueEvent.ToggleSleepTimer)
                        }
                    }
                    TvQueueMenuAction(Res.string.done, onClick = onDismiss)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun TvQueueMenuAction(
    label: StringResource,
    enabled: Boolean = true,
    selected: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        shapes = ButtonDefaults.shapes(),
        colors =
            ButtonDefaults.textButtonColors(
                contentColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            ),
        modifier = modifier.fillMaxWidth().tvFocusHighlight(),
    ) {
        Text(stringResource(label))
    }
}
