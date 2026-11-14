/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.shared.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxDefaults
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.action_download
import com.metrolist.shared.generated.resources.action_like
import com.metrolist.shared.generated.resources.add_to_playlist
import com.metrolist.shared.generated.resources.clear_selection
import com.metrolist.shared.generated.resources.collapse
import com.metrolist.shared.generated.resources.delete
import com.metrolist.shared.generated.resources.deselect_all
import com.metrolist.shared.generated.resources.deselect_track_by_artist
import com.metrolist.shared.generated.resources.explicit
import com.metrolist.shared.generated.resources.filter_downloaded
import com.metrolist.shared.generated.resources.liked
import com.metrolist.shared.generated.resources.lock_queue
import com.metrolist.shared.generated.resources.more_options
import com.metrolist.shared.generated.resources.n_selected
import com.metrolist.shared.generated.resources.pause_track_by_artist
import com.metrolist.shared.generated.resources.play
import com.metrolist.shared.generated.resources.play_track_by_artist
import com.metrolist.shared.generated.resources.queue
import com.metrolist.shared.generated.resources.queue_loading
import com.metrolist.shared.generated.resources.queue_move_hint
import com.metrolist.shared.generated.resources.queue_select_hint
import com.metrolist.shared.generated.resources.queue_track_count_duration
import com.metrolist.shared.generated.resources.removed_from_queue
import com.metrolist.shared.generated.resources.repeat_mode_all
import com.metrolist.shared.generated.resources.repeat_mode_off
import com.metrolist.shared.generated.resources.repeat_mode_one
import com.metrolist.shared.generated.resources.saved_to_library
import com.metrolist.shared.generated.resources.select_all
import com.metrolist.shared.generated.resources.select_track_by_artist
import com.metrolist.shared.generated.resources.shuffle
import com.metrolist.shared.generated.resources.suggested_tracks
import com.metrolist.shared.generated.resources.undo
import com.metrolist.shared.generated.resources.unlock_queue
import com.metrolist.shared.generated.resources.up_next
import com.metrolist.shared.model.RadioQueueChip
import com.metrolist.shared.model.moveSelection
import com.metrolist.shared.playback.LocalPlayerSession
import com.metrolist.shared.playback.QueueRemoval
import com.metrolist.shared.playback.SleepTimerState
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.ui.LocalIsTv
import com.metrolist.shared.ui.components.ChipShelf
import com.metrolist.shared.ui.components.ContainedLoadingIndicator
import com.metrolist.shared.ui.components.PlayerMenuAction
import com.metrolist.shared.ui.components.ReorderMenu
import com.metrolist.shared.ui.components.animateCurrentSongColors
import com.metrolist.shared.ui.components.animatedCurrentSongArtworkShape
import com.metrolist.shared.ui.components.currentSongSemantics
import com.metrolist.shared.ui.components.images.AsyncImage
import com.metrolist.shared.ui.components.rememberArtworkShapeRotation
import com.metrolist.shared.ui.components.squareArtworkSlot
import com.metrolist.shared.ui.haptics.HapticEvent
import com.metrolist.shared.ui.haptics.rememberMetrolistHaptics
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.icons.SongStatusIcons
import com.metrolist.shared.ui.menu.ExpressiveSelectionMenu
import com.metrolist.shared.ui.menu.cursorContextMenu
import com.metrolist.shared.ui.menu.rememberCursorContextMenuState
import com.metrolist.shared.ui.navigation.NavigationBackHandler
import com.metrolist.shared.ui.navigation.navigationPopupSource
import com.metrolist.shared.ui.theme.rememberReducedMotion
import com.metrolist.shared.ui.tv.TvReorderScrollTarget
import com.metrolist.shared.ui.tv.tvFocusHighlight
import com.metrolist.shared.ui.utils.DragScrollableColumn
import com.metrolist.shared.ui.utils.queueVerticalBlur
import com.metrolist.shared.utils.Logger
import com.metrolist.shared.utils.formatQueueDuration
import com.metrolist.shared.viewmodels.PlayerViewModel
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

private val queueHorizontalInset = 12.dp
private val queueItemGap = 4.dp
private val queueItemShape = RoundedCornerShape(12.dp)

sealed class QueueEvent {
    data class PlayTrack(
        val entryKey: Long,
    ) : QueueEvent()

    data class OpenTrackMenu(
        val entryKey: Long,
        val offset: DpOffset = DpOffset.Zero,
    ) : QueueEvent()

    data class AddTrackToPlaylist(
        val entryKey: Long,
    ) : QueueEvent()

    data class DownloadTrack(
        val entryKey: Long,
    ) : QueueEvent()

    data class ToggleTrackLike(
        val entryKey: Long,
    ) : QueueEvent()

    data class SelectChip(
        val id: String,
    ) : QueueEvent()

    data class SelectTrack(
        val entryKey: Long,
    ) : QueueEvent()

    data class DeselectTrack(
        val entryKey: Long,
    ) : QueueEvent()

    data class ReorderTrack(
        val fromEntryKey: Long,
        val toEntryKey: Long,
    ) : QueueEvent()

    data class RemoveTrack(
        val entryKey: Long,
    ) : QueueEvent()

    data object SelectAll : QueueEvent()

    data object AddSelectedToPlaylist : QueueEvent()

    data object ClearSelection : QueueEvent()

    data object DeleteSelected : QueueEvent()

    data object DownloadSelected : QueueEvent()

    data object LikeSelected : QueueEvent()

    data object ToggleShuffle : QueueEvent()

    data object CycleRepeat : QueueEvent()

    data object ToggleSleepTimer : QueueEvent()

    data object TogglePlayPause : QueueEvent()

    data object Previous : QueueEvent()

    data object Next : QueueEvent()

    data object CommitReorder : QueueEvent()

    data object CollapseToolbar : QueueEvent()

    data object Dismiss : QueueEvent()
}

data class QueueTrack(
    val id: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String?,
    val duration: Long?,
    val isExplicit: Boolean,
    val isLiked: Boolean,
    val isSaved: Boolean,
    val isDownloaded: Boolean = false,
    val isSuggested: Boolean = false,
    val canRemove: Boolean = true,
    val queueIndex: Int = -1,
    val entryKey: Long = -1L,
    val uniqueKey: String = id,
    val thumbnailAspectRatio: Float = 1f,
)

internal fun prepareQueueTracks(
    items: List<PlayerViewModel.QueueItemUiModel>,
    entryKeys: List<Long>,
    downloadedSongIds: Set<String>,
): List<QueueTrack> =
    items.mapIndexed { index, item ->
        val entryKey = entryKeys[index]
        QueueTrack(
            id = item.id,
            title = item.title,
            artist = item.artists,
            thumbnailUrl = item.thumbnail,
            duration = item.duration,
            isExplicit = item.isExplicit,
            isLiked = item.isLiked,
            isSaved = item.isSaved,
            isDownloaded = item.id in downloadedSongIds,
            isSuggested = item.isSuggested,
            canRemove = entryKey >= 0,
            queueIndex = index,
            entryKey = entryKey,
            uniqueKey = entryKey.toString(),
            thumbnailAspectRatio = item.thumbnailAspectRatio,
        )
    }

internal fun QueueTrack.durationText(
    isActive: Boolean,
    currentDuration: Long?,
): String = formatQueueDuration(duration ?: currentDuration?.takeIf { isActive && it > 0L })

internal fun QueueTrack.isReorderTarget(
    draggingEntryKey: Long?,
    selectedEntryKeys: Set<Long>,
): Boolean =
    canRemove && entryKey != draggingEntryKey &&
        (draggingEntryKey !in selectedEntryKeys || entryKey !in selectedEntryKeys)

internal fun reconcileReorderedTracks(
    current: List<QueueTrack>,
    incoming: List<QueueTrack>,
    hasPendingReorder: Boolean,
): List<QueueTrack> {
    if (!hasPendingReorder || current.size != incoming.size) return incoming
    val incomingByKey = incoming.associateBy(QueueTrack::uniqueKey)
    if (incomingByKey.keys != current.mapTo(mutableSetOf(), QueueTrack::uniqueKey)) return incoming
    return current.mapIndexed { index, track -> incomingByKey.getValue(track.uniqueKey).copy(queueIndex = index) }
}

data class PlaylistInfo(
    val name: String,
    val trackCount: Int,
    val totalDuration: String,
)

data class QueueScreenState(
    val playlist: PlaylistInfo? = null,
    val chips: List<RadioQueueChip> = emptyList(),
    val tracks: List<QueueTrack> = emptyList(),
    val suggestedTracks: List<QueueTrack> = emptyList(),
    val isShuffleOn: Boolean = false,
    val repeatMode: Int = 0,
    val toolbarExpanded: Boolean = false,
    val isQueueLoading: Boolean = false,
    val selectedEntryKeys: Set<Long> = emptySet(),
    val canControlPlayback: Boolean = true,
    val currentEntryKey: Long? = null,
    val isPlaying: Boolean = false,
    val currentDuration: Long? = null,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun QueueScreen(
    state: QueueScreenState,
    onEvent: (QueueEvent) -> Unit,
    onDismiss: () -> Unit,
    isListenTogetherGuest: Boolean = false,
    modifier: Modifier = Modifier,
    nestedScrollConnection: NestedScrollConnection? = null,
    backHandlerEnabled: Boolean = true,
    handleDismissalBack: Boolean = true,
    reorderEnabled: Boolean = true,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    session: QueueSession? = null,
    isDesktopPanel: Boolean = false,
    headerActions: @Composable RowScope.() -> Unit = {},
) {
    val lazyListState = session?.listState ?: rememberLazyListState()
    val hapticManager = rememberMetrolistHaptics()
    val hasSelection = state.selectedEntryKeys.isNotEmpty()
    var draggingEntryKey by remember { mutableStateOf<Long?>(null) }
    val isUnlocked = !Settings.shared.isQueueLocked
    val isTv = LocalIsTv.current
    val queueFocusRequester = remember { FocusRequester() }
    var tvMovement by remember { mutableStateOf(TvQueueMovement()) }
    var tvSelecting by remember { mutableStateOf(hasSelection) }
    var tvMenuOpen by remember { mutableStateOf(false) }
    var tvMenuEntryKey by remember { mutableStateOf<Long?>(null) }
    var focusedEntryKey by remember { mutableStateOf<Long?>(null) }
    var restoreFocusKey by remember { mutableStateOf<Long?>(null) }
    val tvEntryKeys = remember(state.tracks) { state.tracks.filter { it.canRemove }.map(QueueTrack::entryKey) }
    LaunchedEffect(tvEntryKeys, state.canControlPlayback, isListenTogetherGuest) {
        tvMovement = if (state.canControlPlayback && !isListenTogetherGuest) tvMovement.reconcile(tvEntryKeys) else TvQueueMovement()
    }
    val trackEvent: (QueueEvent) -> Unit = { event ->
        if (isTv && event is QueueEvent.OpenTrackMenu) {
            tvMovement = tvMovement.place()
            tvMenuEntryKey = event.entryKey
            tvMenuOpen = true
        } else {
            onEvent(event)
        }
    }
    val dismissTvMenu = {
        tvMenuOpen = false
        restoreFocusKey = focusedEntryKey
    }
    val canControlPlayback = state.canControlPlayback && !isListenTogetherGuest
    val showReorderHandleSpace = !isTv && isUnlocked && canControlPlayback
    val navigationBarBottomPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val allTracks = remember(state.tracks, state.suggestedTracks) { state.tracks + state.suggestedTracks }
    val queueSelectionActions =
        if (hasSelection) {
            listOfNotNull(
                state.tracks
                    .firstOrNull { it.entryKey in state.selectedEntryKeys }
                    ?.takeIf { canControlPlayback }
                    ?.let { track ->
                        PlayerMenuAction(MetrolistIcons.Rounded.PlayArrow, stringResource(Res.string.play)) {
                            onEvent(QueueEvent.PlayTrack(track.entryKey))
                        }
                    },
                PlayerMenuAction(MetrolistIcons.AutoMirrored.Outlined.PlaylistAdd, stringResource(Res.string.add_to_playlist)) {
                    onEvent(QueueEvent.AddSelectedToPlaylist)
                },
                PlayerMenuAction(MetrolistIcons.Outlined.Download, stringResource(Res.string.action_download)) {
                    onEvent(QueueEvent.DownloadSelected)
                },
                PlayerMenuAction(
                    MetrolistIcons.Outlined.Delete,
                    stringResource(Res.string.delete),
                    enabled = canControlPlayback && allTracks.any { it.canRemove && it.entryKey in state.selectedEntryKeys },
                ) { onEvent(QueueEvent.DeleteSelected) },
                PlayerMenuAction(MetrolistIcons.Rounded.FavoriteBorder, stringResource(Res.string.action_like)) {
                    onEvent(QueueEvent.LikeSelected)
                },
            )
        } else {
            emptyList()
        }
    var hasScrolledToCurrent by remember { mutableStateOf(false) }
    val queueHeadingCount = if (!isDesktopPanel && state.tracks.isNotEmpty()) 1 else 0
    var tvScrollTarget by remember { mutableStateOf<TvReorderScrollTarget?>(null) }
    val trackKeys = remember(state.tracks) { state.tracks.map(QueueTrack::uniqueKey) }
    LaunchedEffect(tvScrollTarget, trackKeys, queueHeadingCount) {
        val target = tvScrollTarget ?: return@LaunchedEffect
        val index = target.listIndex(trackKeys, queueHeadingCount) ?: return@LaunchedEffect
        withFrameNanos { }
        val layout = lazyListState.layoutInfo
        val visible =
            layout.visibleItemsInfo.any {
                it.key == target.key && it.index == index && it.offset >= layout.viewportStartOffset &&
                    it.offset + it.size <= layout.viewportEndOffset
            }
        if (!visible) lazyListState.animateScrollToItem(index)
        tvScrollTarget = null
    }
    val currentListIndex =
        remember(state.tracks, state.suggestedTracks, state.currentEntryKey, queueHeadingCount) {
            state.tracks
                .indexOfFirst { it.entryKey == state.currentEntryKey }
                .takeIf { it >= 0 }
                ?.plus(queueHeadingCount)
                ?: state.suggestedTracks
                    .indexOfFirst { it.entryKey == state.currentEntryKey }
                    .takeIf { it >= 0 }
                    ?.let { state.tracks.size + queueHeadingCount + 2 + it }
                ?: -1
        }

    LaunchedEffect(currentListIndex) {
        if (!hasScrolledToCurrent && currentListIndex >= 0) {
            lazyListState.scrollToItem(currentListIndex)
            hasScrolledToCurrent = true
        }
    }

    val handlesBack = handleDismissalBack || hasSelection || state.toolbarExpanded || (isTv && (tvMovement.enabled || tvSelecting))
    NavigationBackHandler(enabled = backHandlerEnabled && !tvMenuOpen && handlesBack) {
        if (isTv && tvMovement.grabbedEntryKey != null) {
            tvMovement = tvMovement.place()
        } else if (isTv && tvMovement.enabled) {
            tvMovement = TvQueueMovement()
        } else if (isTv && tvSelecting) {
            tvSelecting = false
            onEvent(QueueEvent.ClearSelection)
        } else if (hasSelection) {
            onEvent(QueueEvent.ClearSelection)
        } else if (state.toolbarExpanded) {
            onEvent(QueueEvent.CollapseToolbar)
        } else {
            onDismiss()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Scaffold(
            containerColor = Color.Transparent,
            snackbarHost = {
                SnackbarHost(
                    hostState = snackbarHostState,
                    modifier = Modifier.padding(bottom = navigationBarBottomPadding + 84.dp),
                )
            },
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            modifier =
                Modifier
                    .fillMaxSize(),
        ) { padding ->
            Column(
                modifier =
                    Modifier
                        .padding(padding)
                        .fillMaxSize(),
            ) {
                Spacer(modifier = Modifier.height(4.dp))

                PlaylistHeader(
                    playlist = state.playlist,
                    shape =
                        if (isDesktopPanel) {
                            RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 12.dp, bottomEnd = 12.dp)
                        } else {
                            queueItemShape
                        },
                    isQueueLoading = state.isQueueLoading,
                    selectedCount = state.selectedEntryKeys.size,
                    allSelected =
                        state.tracks.map { it.entryKey }.filter { it >= 0 }.let { keys ->
                            keys.isNotEmpty() && state.selectedEntryKeys.containsAll(keys)
                        },
                    onClearSelection = {
                        tvSelecting = false
                        onEvent(QueueEvent.ClearSelection)
                    },
                    onSelectAll = { onEvent(QueueEvent.SelectAll) },
                    isUnlocked = isUnlocked,
                    onUnlockedChange = { unlocked ->
                        if (!unlocked) onEvent(QueueEvent.ClearSelection)
                        Settings.shared.isQueueLocked = !unlocked
                    },
                    actions = {
                        headerActions()
                        if (isTv) {
                            IconButton(
                                onClick = {
                                    tvMenuEntryKey = null
                                    tvMenuOpen = true
                                },
                                modifier = Modifier.tvFocusHighlight(CircleShape).navigationPopupSource(),
                                shapes = IconButtonDefaults.shapes(),
                            ) {
                                Icon(MetrolistIcons.Rounded.MoreVert, stringResource(Res.string.more_options))
                            }
                        }
                    },
                )

                if (isTv && (tvMovement.enabled || tvSelecting)) {
                    Text(
                        stringResource(if (tvMovement.enabled) Res.string.queue_move_hint else Res.string.queue_select_hint),
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                if (state.chips.isNotEmpty()) {
                    ChipShelf(
                        options = state.chips,
                        selectedOption = state.chips.firstOrNull(RadioQueueChip::isSelected),
                        onSelectionChange = { chip -> chip?.let { onEvent(QueueEvent.SelectChip(it.id)) } },
                        label = { chip -> chip.title },
                        selectionRequired = true,
                    )
                }

                Spacer(modifier = Modifier.height(queueItemGap))

                val reorderableState =
                    rememberReorderableLazyListState(lazyListState) { from, to ->
                        val fromTrack = allTracks.firstOrNull { it.uniqueKey == from.key }
                        val toTrack = state.tracks.firstOrNull { it.uniqueKey == to.key }
                        if (fromTrack != null && toTrack != null) {
                            // Keep the viewport at its current index and offset while row keys move.
                            lazyListState.requestScrollToItem(
                                lazyListState.firstVisibleItemIndex,
                                lazyListState.firstVisibleItemScrollOffset,
                            )
                            hapticManager.perform(HapticEvent.DRAG_SNAP)
                            onEvent(QueueEvent.ReorderTrack(fromTrack.entryKey, toTrack.entryKey))
                        }
                    }

                val isDragging = reorderableState.isAnyItemDragging
                LaunchedEffect(isDragging) {
                    if (!isDragging) {
                        onEvent(QueueEvent.CommitReorder)
                    }
                }

                val onMoveTrack: (Int, Int) -> Unit = { from, to ->
                    if (isTv) {
                        tvScrollTarget = TvReorderScrollTarget(state.tracks[from].uniqueKey, from)
                    } else {
                        val firstIndex = lazyListState.firstVisibleItemIndex
                        val firstOffset = lazyListState.firstVisibleItemScrollOffset
                        lazyListState.requestScrollToItem(firstIndex, firstOffset)
                    }
                    onEvent(QueueEvent.ReorderTrack(state.tracks[from].entryKey, state.tracks[to].entryKey))
                    onEvent(QueueEvent.CommitReorder)
                }

                Box(
                    modifier =
                        Modifier
                            .padding(horizontal = queueHorizontalInset)
                            .weight(1f)
                            .fillMaxWidth()
                            .clip(queueItemShape),
                ) {
                    val baseListModifier =
                        if (nestedScrollConnection != null) {
                            Modifier.fillMaxSize().nestedScroll(nestedScrollConnection)
                        } else {
                            Modifier.fillMaxSize()
                        }
                    val listModifier =
                        baseListModifier.queueVerticalBlur(
                            blurHeight = 95.dp + navigationBarBottomPadding,
                            enabled = !isTv && !hasSelection,
                        )

                    DragScrollableColumn(
                        state = lazyListState,
                        modifier =
                            listModifier
                                .focusRequester(queueFocusRequester)
                                .focusRestorer()
                                .onPreviewKeyEvent { event ->
                                    if (isTv && tvMovement.grabbedEntryKey == null && event.key == Key.DirectionRight) {
                                        if (event.type == KeyEventType.KeyDown) {
                                            tvMenuEntryKey = focusedEntryKey
                                            tvMenuOpen = true
                                        }
                                        true
                                    } else {
                                        false
                                    }
                                }.focusGroup(),
                        verticalArrangement = Arrangement.spacedBy(queueItemGap),
                        contentPadding =
                            PaddingValues(
                                bottom = if (isTv) 8.dp else 90.dp + navigationBarBottomPadding,
                            ),
                    ) {
                        if (queueHeadingCount > 0) {
                            item(key = "queue-heading") {
                                QueueSectionHeading(stringResource(Res.string.queue))
                            }
                        }
                        itemsIndexed(
                            items = state.tracks,
                            key = { _, item -> item.uniqueKey },
                        ) { index, track ->
                            val queueIndex = track.queueIndex
                            val isSelected = track.entryKey in state.selectedEntryKeys
                            val isActive = track.entryKey == state.currentEntryKey
                            val rowFocusRequester = remember(track.entryKey) { FocusRequester() }
                            val grabbed = isTv && tvMovement.grabbedEntryKey == track.entryKey
                            LaunchedEffect(grabbed, index, restoreFocusKey, tvMenuOpen) {
                                if (isTv && !tvMenuOpen && (grabbed || restoreFocusKey == track.entryKey)) {
                                    rowFocusRequester.requestFocus()
                                    restoreFocusKey = null
                                }
                            }

                            ReorderableItem(
                                state = reorderableState,
                                key = track.uniqueKey,
                                // A no-op drop inside the selection leaves the drag library waiting for a move.
                                enabled = track.isReorderTarget(draggingEntryKey, state.selectedEntryKeys),
                            ) {
                                // Keep the handle composed while the committed move awaits the player so the row does not shift.
                                val dragModifier =
                                    if (isUnlocked && canControlPlayback && track.canRemove) {
                                        Modifier
                                            .draggableHandle(
                                                enabled = reorderEnabled,
                                                onDragStarted = {
                                                    draggingEntryKey = track.entryKey
                                                    hapticManager.perform(HapticEvent.DRAG_START)
                                                },
                                                onDragStopped = { draggingEntryKey = null },
                                            )
                                    } else {
                                        Modifier
                                    }
                                QueueTrackSwipeToDismissBox(
                                    trackKey = track.uniqueKey,
                                    enabled = isUnlocked && track.canRemove && !hasSelection && canControlPlayback && !isDragging,
                                    onDismiss = {
                                        if (canControlPlayback) {
                                            onEvent(QueueEvent.RemoveTrack(track.entryKey))
                                        }
                                    },
                                ) {
                                    TrackItem(
                                        track = track,
                                        isActive = isActive,
                                        isPlaying = isActive && state.isPlaying,
                                        currentDuration = state.currentDuration.takeIf { isActive },
                                        trackIndex = queueIndex,
                                        isSelected = isSelected,
                                        showSelectionCheckbox = if (isTv) tvSelecting else isUnlocked,
                                        isReorderable = showReorderHandleSpace && track.canRemove,
                                        showReorderHandleSpace = showReorderHandleSpace,
                                        isMoving = grabbed,
                                        movementMode = isTv && tvMovement.enabled,
                                        modifier =
                                            Modifier
                                                .focusRequester(rowFocusRequester)
                                                .onFocusChanged { if (it.isFocused) focusedEntryKey = track.entryKey }
                                                .onPreviewKeyEvent { event ->
                                                    if (!grabbed ||
                                                        event.key !in
                                                        listOf(
                                                            Key.DirectionUp,
                                                            Key.DirectionDown,
                                                            Key.DirectionLeft,
                                                            Key.DirectionRight,
                                                        )
                                                    ) {
                                                        return@onPreviewKeyEvent false
                                                    }
                                                    if (event.type == KeyEventType.KeyDown && reorderEnabled) {
                                                        val direction =
                                                            when (event.key) {
                                                                Key.DirectionUp -> -1
                                                                Key.DirectionDown -> 1
                                                                else -> 0
                                                            }
                                                        tvMovement.target(tvEntryKeys, direction)?.let { target ->
                                                            tvScrollTarget = TvReorderScrollTarget(track.uniqueKey, index)
                                                            onEvent(QueueEvent.ReorderTrack(track.entryKey, target))
                                                            onEvent(QueueEvent.CommitReorder)
                                                        }
                                                    }
                                                    true
                                                },
                                        onMoveUp =
                                            if (isUnlocked && reorderEnabled && index > 0) {
                                                { onMoveTrack(index, index - 1) }
                                            } else {
                                                null
                                            },
                                        onMoveDown =
                                            if (isUnlocked && reorderEnabled && index < state.tracks.lastIndex) {
                                                { onMoveTrack(index, index + 1) }
                                            } else {
                                                null
                                            },
                                        onMoveToTop =
                                            if (isUnlocked && reorderEnabled && index > 0) {
                                                { onMoveTrack(index, 0) }
                                            } else {
                                                null
                                            },
                                        onMoveToBottom =
                                            if (isUnlocked && reorderEnabled && index < state.tracks.lastIndex) {
                                                { onMoveTrack(index, state.tracks.lastIndex) }
                                            } else {
                                                null
                                            },
                                        onEvent = trackEvent,
                                        onClick = {
                                            if (isTv && tvMovement.enabled) {
                                                tvMovement =
                                                    if (grabbed) {
                                                        tvMovement.place()
                                                    } else if (track.canRemove) {
                                                        tvMovement.grab(track.entryKey)
                                                    } else {
                                                        tvMovement
                                                    }
                                            } else if (if (isTv) tvSelecting else isUnlocked) {
                                                onEvent(
                                                    if (isSelected) {
                                                        QueueEvent.DeselectTrack(track.entryKey)
                                                    } else {
                                                        QueueEvent.SelectTrack(track.entryKey)
                                                    },
                                                )
                                            } else {
                                                onEvent(
                                                    if (isActive) {
                                                        QueueEvent.TogglePlayPause
                                                    } else {
                                                        QueueEvent.PlayTrack(track.entryKey)
                                                    },
                                                )
                                            }
                                        },
                                        dragHandleModifier = dragModifier,
                                    )
                                }
                            }
                        }

                        if (state.suggestedTracks.isNotEmpty()) {
                            item(key = "suggested-gap") {
                                Spacer(modifier = Modifier.height(3.dp))
                            }
                            item(key = "suggested-heading") {
                                QueueSectionHeading(stringResource(Res.string.suggested_tracks))
                            }
                            items(
                                items = state.suggestedTracks,
                                key = { item -> item.uniqueKey },
                            ) { track ->
                                val isActive = track.entryKey == state.currentEntryKey
                                Box {
                                    QueueTrackSwipeToDismissBox(
                                        trackKey = track.uniqueKey,
                                        enabled = isUnlocked && track.canRemove && !hasSelection && canControlPlayback,
                                        onDismiss = {
                                            if (canControlPlayback) {
                                                onEvent(QueueEvent.RemoveTrack(track.entryKey))
                                            }
                                        },
                                    ) {
                                        TrackItem(
                                            track = track,
                                            isActive = isActive,
                                            isPlaying = isActive && state.isPlaying,
                                            currentDuration = state.currentDuration.takeIf { isActive },
                                            trackIndex = track.queueIndex,
                                            isSelected = false,
                                            isReorderable = false,
                                            showReorderHandleSpace = showReorderHandleSpace,
                                            onEvent = trackEvent,
                                            modifier = Modifier.onFocusChanged { if (it.isFocused) focusedEntryKey = track.entryKey },
                                            onClick = {
                                                onEvent(
                                                    if (isActive) {
                                                        QueueEvent.TogglePlayPause
                                                    } else {
                                                        QueueEvent.PlayTrack(track.entryKey)
                                                    },
                                                )
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (isTv && tvMenuOpen) {
            val menuTrack = allTracks.firstOrNull { it.entryKey == tvMenuEntryKey }
            TvQueueMenu(
                state = state,
                track = menuTrack,
                selecting = tvSelecting,
                canControlPlayback = canControlPlayback,
                canMove = canControlPlayback && reorderEnabled && menuTrack?.entryKey in tvEntryKeys,
                onMove = {
                    menuTrack?.let { tvMovement = tvMovement.grab(it.entryKey) }
                    tvSelecting = false
                    onEvent(QueueEvent.ClearSelection)
                    dismissTvMenu()
                },
                onMultiSelect = {
                    tvMovement = TvQueueMovement()
                    tvSelecting = true
                    menuTrack?.let { onEvent(QueueEvent.SelectTrack(it.entryKey)) }
                    dismissTvMenu()
                },
                onFinish = {
                    tvSelecting = false
                    onEvent(QueueEvent.ClearSelection)
                    dismissTvMenu()
                },
                onEvent = onEvent,
                onDismiss = dismissTvMenu,
            )
        }

        if (!isTv && (hasSelection || canControlPlayback)) {
            QueueBottomToolbar(
                isShuffleOn = state.isShuffleOn,
                repeatMode = state.repeatMode,
                collapseIcon =
                    if (isDesktopPanel) MetrolistIcons.AutoMirrored.Filled.KeyboardArrowRight else MetrolistIcons.Rounded.KeyboardArrowDown,
                selectionActions = queueSelectionActions.takeIf { hasSelection },
                onEvent = onEvent,
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = navigationBarBottomPadding + 6.dp)
                        .focusGroup(),
            )
        }
    }
}

@Composable
private fun QueueSectionHeading(text: String) {
    Text(
        text = text,
        style =
            MaterialTheme.typography.titleLarge.copy(
                fontSize = 22.sp,
                fontWeight = FontWeight.Medium,
                lineHeight = 28.sp,
                letterSpacing = 0.sp,
            ),
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun QueueSheetDragHandle(modifier: Modifier = Modifier) {
    BottomSheetDefaults.DragHandle(modifier = modifier)
}

@Composable
private fun QueueBottomToolbar(
    isShuffleOn: Boolean,
    repeatMode: Int,
    collapseIcon: ImageVector,
    selectionActions: List<PlayerMenuAction>? = null,
    onEvent: (QueueEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sleepTimerState by LocalPlayerSession.current?.sleepTimerState?.collectWhilePlayerActive()
        ?: remember { mutableStateOf(SleepTimerState()) }
    val hapticManager = rememberMetrolistHaptics()
    Row(
        modifier = modifier.width(240.dp).height(72.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.width(176.dp).height(72.dp),
            shape = RoundedCornerShape(36.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.85f),
        ) {
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (selectionActions != null) {
                    selectionActions.take(3).forEach { action ->
                        IconButton(
                            onClick = {
                                hapticManager.perform(HapticEvent.PRESS)
                                action.onClick()
                            },
                            enabled = action.enabled,
                            modifier = Modifier.size(48.dp).tvFocusHighlight(CircleShape, scale = 1f).navigationPopupSource(),
                            shapes = IconButtonDefaults.shapes(),
                        ) {
                            Icon(action.icon, contentDescription = action.title, tint = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                } else {
                    IconButton(
                        onClick = {
                            hapticManager.perform(HapticEvent.PRESS)
                            onEvent(QueueEvent.ToggleSleepTimer)
                        },
                        modifier = Modifier.size(48.dp).tvFocusHighlight(CircleShape, scale = 1f),
                        shapes = IconButtonDefaults.shapes(),
                    ) {
                        Icon(
                            imageVector = MetrolistIcons.Outlined.Bedtime,
                            contentDescription = sleepTimerContentDescription(sleepTimerState),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    IconButton(
                        onClick = {
                            hapticManager.perform(HapticEvent.PRESS)
                            onEvent(QueueEvent.ToggleShuffle)
                        },
                        modifier = Modifier.size(48.dp).tvFocusHighlight(CircleShape, scale = 1f),
                        shapes = IconButtonDefaults.shapes(),
                    ) {
                        Icon(
                            imageVector = MetrolistIcons.Rounded.Shuffle,
                            contentDescription = stringResource(Res.string.shuffle),
                            tint = if (isShuffleOn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    IconButton(
                        onClick = {
                            hapticManager.perform(HapticEvent.PRESS)
                            onEvent(QueueEvent.CycleRepeat)
                        },
                        modifier = Modifier.size(48.dp).tvFocusHighlight(CircleShape, scale = 1f),
                        shapes = IconButtonDefaults.shapes(),
                    ) {
                        Icon(
                            imageVector = if (repeatMode == 1) MetrolistIcons.Rounded.RepeatOne else MetrolistIcons.Rounded.Repeat,
                            contentDescription =
                                stringResource(
                                    when (repeatMode) {
                                        1 -> Res.string.repeat_mode_one
                                        2 -> Res.string.repeat_mode_all
                                        else -> Res.string.repeat_mode_off
                                    },
                                ),
                            tint = if (repeatMode == 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }

        if (selectionActions != null) {
            var selectionMenuOpen by remember { mutableStateOf(false) }
            Box {
                Surface(
                    onClick = { selectionMenuOpen = true },
                    modifier = Modifier.size(56.dp).tvFocusHighlight(RoundedCornerShape(16.dp), scale = 1f).navigationPopupSource(),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.tertiaryFixedDim.copy(alpha = 0.85f),
                    contentColor = MaterialTheme.colorScheme.onTertiaryFixedVariant,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = MetrolistIcons.Default.MoreVert,
                            contentDescription = stringResource(Res.string.more_options),
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
                ExpressiveSelectionMenu(
                    expanded = selectionMenuOpen,
                    onDismissRequest = { selectionMenuOpen = false },
                    options = selectionActions,
                    selectedOption = null,
                    onOptionSelected = { it.onClick() },
                    optionLabel = { it.title },
                    optionIcon = { Icon(it.icon, contentDescription = null) },
                    optionEnabled = { it.enabled },
                )
            }
        } else {
            Surface(
                onClick = { onEvent(QueueEvent.Dismiss) },
                modifier = Modifier.size(56.dp).tvFocusHighlight(RoundedCornerShape(16.dp), scale = 1f),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.tertiaryFixedDim.copy(alpha = 0.85f),
                contentColor = MaterialTheme.colorScheme.onTertiaryFixedVariant,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = collapseIcon,
                        contentDescription = stringResource(Res.string.collapse),
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun PlaylistHeader(
    playlist: PlaylistInfo?,
    shape: Shape,
    isQueueLoading: Boolean,
    selectedCount: Int,
    allSelected: Boolean,
    onClearSelection: () -> Unit,
    onSelectAll: () -> Unit,
    isUnlocked: Boolean,
    onUnlockedChange: (Boolean) -> Unit,
    actions: @Composable RowScope.() -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = shape,
        modifier =
            modifier
                .padding(horizontal = queueHorizontalInset)
                .fillMaxWidth()
                .heightIn(min = 64.dp),
    ) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f).padding(horizontal = 6.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text =
                            if (selectedCount > 0) {
                                pluralStringResource(Res.plurals.n_selected, selectedCount, selectedCount)
                            } else {
                                playlist?.name ?: stringResource(Res.string.up_next)
                            },
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        style =
                            MaterialTheme.typography.headlineSmall.copy(
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Medium,
                                lineHeight = 28.sp,
                                letterSpacing = 0.sp,
                            ),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (selectedCount == 0 && isQueueLoading) {
                        ContainedLoadingIndicator(Modifier.size(24.dp), label = stringResource(Res.string.queue_loading))
                    } else if (selectedCount == 0) {
                        Text(
                            text =
                                playlist?.let {
                                    pluralStringResource(
                                        Res.plurals.queue_track_count_duration,
                                        it.trackCount,
                                        it.trackCount,
                                        it.totalDuration,
                                    )
                                } ?: "",
                            style =
                                MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    lineHeight = 16.sp,
                                    letterSpacing = 0.5.sp,
                                ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                        )
                    }
                }

                if (selectedCount > 0) {
                    val selectAllLabel = stringResource(if (allSelected) Res.string.deselect_all else Res.string.select_all)
                    Checkbox(
                        modifier =
                            Modifier.semantics(mergeDescendants = true) {
                                contentDescription = selectAllLabel
                            },
                        checked = allSelected,
                        onCheckedChange = { checked -> if (checked) onSelectAll() else onClearSelection() },
                    )
                }
                if (selectedCount > 0) {
                    Surface(
                        shape = RoundedCornerShape(100.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                        IconButton(
                            onClick = onClearSelection,
                            modifier = Modifier.size(48.dp),
                            shapes = IconButtonDefaults.shapes(),
                        ) {
                            Icon(
                                imageVector = MetrolistIcons.Filled.Close,
                                contentDescription = stringResource(Res.string.clear_selection),
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                    }
                } else {
                    actions()
                }
                if (selectedCount == 0 && !LocalIsTv.current) {
                    FilledTonalIconToggleButton(
                        checked = isUnlocked,
                        onCheckedChange = onUnlockedChange,
                        modifier = Modifier.size(48.dp),
                        shapes = IconButtonDefaults.toggleableShapes(),
                    ) {
                        Icon(
                            imageVector = if (isUnlocked) MetrolistIcons.Rounded.LockOpen else MetrolistIcons.Rounded.Lock,
                            contentDescription = stringResource(if (isUnlocked) Res.string.lock_queue else Res.string.unlock_queue),
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun QueueTrackSwipeToDismissBox(
    trackKey: String,
    enabled: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val hapticManager = rememberMetrolistHaptics()
    val positionalThreshold = SwipeToDismissBoxDefaults.positionalThreshold
    val dismissState =
        remember(trackKey) {
            SwipeToDismissBoxState(
                initialValue = SwipeToDismissBoxValue.Settled,
                positionalThreshold = positionalThreshold,
            )
        }

    val isThresholdReached = dismissState.targetValue != SwipeToDismissBoxValue.Settled
    LaunchedEffect(isThresholdReached) {
        if (isThresholdReached) {
            hapticManager.perform(HapticEvent.DRAG_SNAP)
        }
    }

    val backgroundColor by animateColorAsState(
        targetValue =
            if (isThresholdReached) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
            },
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "swipeBackgroundColor",
    )
    val iconColor by animateColorAsState(
        targetValue =
            if (isThresholdReached) {
                MaterialTheme.colorScheme.onErrorContainer
            } else {
                MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.7f)
            },
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "swipeIconColor",
    )
    val iconScale by animateFloatAsState(
        targetValue = if (isThresholdReached) 1.2f else 0.9f,
        animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
        label = "swipeIconScale",
    )

    SwipeToDismissBox(
        state = dismissState,
        modifier = modifier.clip(queueItemShape),
        backgroundContent = {
            val direction = dismissState.dismissDirection
            val alignment =
                when (direction) {
                    SwipeToDismissBoxValue.StartToEnd -> Alignment.CenterStart
                    SwipeToDismissBoxValue.EndToStart -> Alignment.CenterEnd
                    SwipeToDismissBoxValue.Settled -> Alignment.Center
                }
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(if (direction == SwipeToDismissBoxValue.Settled) Color.Transparent else backgroundColor)
                        .padding(horizontal = 20.dp),
                contentAlignment = alignment,
            ) {
                if (direction != SwipeToDismissBoxValue.Settled) {
                    Icon(
                        imageVector = MetrolistIcons.Outlined.Delete,
                        contentDescription = stringResource(Res.string.delete),
                        tint = iconColor,
                        modifier =
                            Modifier.graphicsLayer {
                                scaleX = iconScale
                                scaleY = iconScale
                            },
                    )
                }
            }
        },
        enableDismissFromStartToEnd = enabled,
        enableDismissFromEndToStart = enabled,
        gesturesEnabled = enabled,
        onDismiss = { _ ->
            onDismiss()
        },
        content = content,
    )
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun TrackItem(
    track: QueueTrack,
    isActive: Boolean,
    isPlaying: Boolean,
    currentDuration: Long?,
    trackIndex: Int,
    isSelected: Boolean,
    isReorderable: Boolean,
    showReorderHandleSpace: Boolean,
    onEvent: (QueueEvent) -> Unit,
    onClick: () -> Unit,
    showSelectionCheckbox: Boolean = false,
    isMoving: Boolean = false,
    movementMode: Boolean = false,
    modifier: Modifier = Modifier,
    dragHandleModifier: Modifier = Modifier,
    onMoveUp: (() -> Unit)? = null,
    onMoveDown: (() -> Unit)? = null,
    onMoveToTop: (() -> Unit)? = null,
    onMoveToBottom: (() -> Unit)? = null,
) {
    val hapticManager = rememberMetrolistHaptics()
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val isHovered by interactionSource.collectIsHoveredAsState()
    val highlightProgress =
        animateFloatAsState(
            targetValue = if (isActive) 1f else 0f,
            animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
            label = "queueCurrentShape",
        ).value.coerceIn(0f, 1f)
    val shape =
        RoundedCornerShape(
            object : CornerSize {
                override fun toPx(
                    shapeSize: Size,
                    density: Density,
                ): Float =
                    lerp(
                        queueItemShape.topStart.toPx(shapeSize, density),
                        CircleShape.topStart.toPx(shapeSize, density),
                        highlightProgress,
                    )
            },
        )
    val artworkShape = animatedCurrentSongArtworkShape(isActive, RoundedCornerShape(6.dp))
    val motionScale = rememberCoroutineScope().coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f
    val motionEnabled = isActive && motionScale > 0f && !rememberReducedMotion()
    val artworkRotation by rememberArtworkShapeRotation(spinning = isPlaying, motionEnabled = motionEnabled)
    val contextMenu = rememberCursorContextMenuState()
    LaunchedEffect(contextMenu.expanded) {
        if (contextMenu.expanded) {
            onEvent(QueueEvent.OpenTrackMenu(track.entryKey, contextMenu.offset))
            contextMenu.dismiss()
        }
    }

    val trackActionDescription =
        stringResource(
            when {
                (showSelectionCheckbox && isSelected) || isMoving -> Res.string.deselect_track_by_artist
                showSelectionCheckbox || movementMode -> Res.string.select_track_by_artist
                isPlaying -> Res.string.pause_track_by_artist
                else -> Res.string.play_track_by_artist
            },
            track.title,
            track.artist,
        )
    val colors =
        animateCurrentSongColors(
            isCurrent = isActive,
            container =
                if (isSelected ||
                    isMoving
                ) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainer
                },
            content = if (isSelected || isMoving) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
            subtitle =
                if (isSelected || isMoving) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.secondary,
        )
    val backgroundColor = colors.container
    val contentColor = colors.content
    val secondaryContentColor = colors.subtitle
    val durationText = remember(track.duration, isActive, currentDuration) { track.durationText(isActive, currentDuration) }

    Surface(
        color = if (isHovered || isPressed) contentColor.copy(alpha = 0.08f).compositeOver(backgroundColor) else backgroundColor,
        contentColor = contentColor,
        shape = shape,
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = 60.dp)
                .tvFocusHighlight(shape)
                .currentSongSemantics(isActive, isPlaying)
                .cursorContextMenu(contextMenu)
                .combinedClickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick,
                    onLongClickLabel = stringResource(Res.string.more_options),
                    onLongClick = { onEvent(QueueEvent.OpenTrackMenu(track.entryKey, contextMenu.offset)) },
                ).semantics {
                    contentDescription = trackActionDescription
                    selected = isSelected || isMoving
                },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AnimatedVisibility(
                visible = showReorderHandleSpace,
                enter =
                    fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()) +
                        expandHorizontally(MaterialTheme.motionScheme.defaultSpatialSpec()),
                exit =
                    fadeOut(
                        MaterialTheme.motionScheme.fastEffectsSpec(),
                    ) + shrinkHorizontally(MaterialTheme.motionScheme.fastSpatialSpec()),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isReorderable) {
                        ReorderMenu(
                            onMoveUp = onMoveUp,
                            onMoveDown = onMoveDown,
                            onMoveToTop = onMoveToTop,
                            onMoveToBottom = onMoveToBottom,
                            modifier = Modifier.size(36.dp),
                            dragHandleModifier = if (LocalIsTv.current) Modifier else dragHandleModifier,
                        )
                    } else {
                        Spacer(modifier = Modifier.size(36.dp))
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                }
            }

            Box(
                modifier =
                    Modifier
                        .squareArtworkSlot(44.dp, if (isActive) 1f else track.thumbnailAspectRatio)
                        .graphicsLayer { rotationZ = artworkRotation }
                        .clip(artworkShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer { rotationZ = -artworkRotation },
                    contentAlignment = Alignment.Center,
                ) {
                    if (track.thumbnailUrl != null) {
                        AsyncImage(
                            url = track.thumbnailUrl,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                    } else {
                        Icon(
                            imageVector = MetrolistIcons.Rounded.MusicNote,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = track.title,
                    style =
                        MaterialTheme.typography.titleMedium.copy(
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            lineHeight = 20.sp,
                            letterSpacing = 0.15.sp,
                        ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = contentColor,
                )
                Row(
                    modifier = Modifier.height(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MetadataIcons(track = track)
                    if (
                        track.isLiked ||
                        track.isExplicit ||
                        track.isSaved ||
                        track.isDownloaded
                    ) {
                        Spacer(modifier = Modifier.width(3.dp))
                    }
                    Text(
                        text =
                            listOf(track.artist, durationText)
                                .filter { it.isNotBlank() }
                                .joinToString(" \u00b7 "),
                        style =
                            MaterialTheme.typography.bodyMedium.copy(
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                lineHeight = 18.sp,
                                letterSpacing = 0.1.sp,
                            ),
                        color = secondaryContentColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (showSelectionCheckbox || isSelected) {
                Box(
                    modifier = Modifier.size(40.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Checkbox(checked = isSelected, onCheckedChange = null)
                }
            } else {
                IconButton(
                    onClick = {
                        Logger.d("Haptics", "Trigger QueueScreen track menu click index=$trackIndex")
                        hapticManager.perform(HapticEvent.PRESS)
                        onEvent(QueueEvent.OpenTrackMenu(track.entryKey, contextMenu.offset))
                    },
                    modifier = Modifier.size(40.dp).navigationPopupSource(),
                    shapes = IconButtonDefaults.shapes(),
                ) {
                    Icon(
                        imageVector = MetrolistIcons.Rounded.MoreVert,
                        contentDescription = stringResource(Res.string.more_options),
                        tint = if (isActive || isSelected || isMoving) contentColor else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun MetadataIcons(
    track: QueueTrack,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(1.3.dp),
    ) {
        if (track.isLiked) {
            Icon(
                imageVector = SongStatusIcons.Liked,
                contentDescription = stringResource(Res.string.liked),
                tint = Color.Unspecified,
                modifier = Modifier.size(13.dp),
            )
        }
        if (track.isExplicit) {
            ExplicitTag(size = 13.dp)
        }
        if (track.isSaved) {
            Icon(
                imageVector = SongStatusIcons.Saved,
                contentDescription = stringResource(Res.string.saved_to_library),
                tint = Color.Unspecified,
                modifier = Modifier.size(13.dp),
            )
        }
        if (track.isDownloaded) {
            Icon(
                imageVector = SongStatusIcons.Downloaded,
                contentDescription = stringResource(Res.string.filter_downloaded),
                tint = Color.Unspecified,
                modifier = Modifier.size(13.dp),
            )
        }
    }
}

@Composable
private fun ExplicitTag(
    modifier: Modifier = Modifier,
    size: Dp = 10.dp,
) {
    Icon(
        imageVector = SongStatusIcons.Explicit,
        contentDescription = stringResource(Res.string.explicit),
        tint = Color.Unspecified,
        modifier = modifier.size(size),
    )
}

@Stable
class QueueSession internal constructor(
    val listState: LazyListState,
) {
    internal var selectedEntryKeys by mutableStateOf(setOf<Long>())
    internal var reorderableTracks by mutableStateOf(listOf<QueueTrack>())
    internal var trackMenuEntryKey by mutableStateOf<Long?>(null)
    internal var pendingReorder by mutableStateOf<List<Long>?>(null)
    internal var awaitingReorder by mutableStateOf(false)
    internal var pendingRemovals by mutableStateOf(emptyList<QueueRemoval>())
}

@Composable
fun rememberQueueSession(viewModel: PlayerViewModel): QueueSession {
    val listState = rememberLazyListState()
    val session = remember(viewModel) { QueueSession(listState) }
    DisposableEffect(viewModel, session) {
        onDispose { viewModel.discardQueueUndo(session.pendingRemovals) }
    }
    return session
}

@Composable
fun QueueScreen(
    onClose: () -> Unit,
    onShowSleepTimer: () -> Unit = {},
    onNavigateToAlbum: (String) -> Unit = {},
    onNavigateToArtist: (String) -> Unit = {},
    onNavigateToListenTogether: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: PlayerViewModel,
    session: QueueSession = rememberQueueSession(viewModel),
    nestedScrollConnection: NestedScrollConnection? = null,
    backHandlerEnabled: Boolean = true,
    handleDismissalBack: Boolean = true,
) {
    val uiState by viewModel.uiState.collectAsState()
    var selectedEntryKeys by session::selectedEntryKeys
    var reorderableTracks by session::reorderableTracks
    var trackMenuEntryKey by session::trackMenuEntryKey
    var pendingReorder by session::pendingReorder
    var awaitingReorder by session::awaitingReorder
    var pendingRemovals by session::pendingRemovals
    val platform = LocalPlayerPlatform.current
    val isListenTogetherGuest = platform.isListenTogetherGuest()
    val canControlPlayback = !isListenTogetherGuest
    val downloadedSongIds = platform.downloadedSongIds()
    val downloadSongs = platform.rememberSongDownloader()
    val addToPlaylist = platform.rememberPlaylistAdder()
    val queueTitleFallback = stringResource(Res.string.queue)
    val snackbarHostState = remember { SnackbarHostState() }
    val currentEntryKey = uiState.queueEntryKeys.getOrNull(uiState.currentQueueIndex)

    val tracks =
        remember(
            uiState.queueItems,
            uiState.queueEntryKeys,
            downloadedSongIds,
        ) {
            prepareQueueTracks(uiState.queueItems, uiState.queueEntryKeys, downloadedSongIds)
        }

    LaunchedEffect(uiState.queueEntryKeys, tracks) {
        selectedEntryKeys =
            selectedEntryKeys.intersect(tracks.filter { !it.isSuggested && it.canRemove }.mapTo(mutableSetOf(), QueueTrack::entryKey))
        trackMenuEntryKey = trackMenuEntryKey?.takeIf { it in uiState.queueEntryKeys }
        reorderableTracks = reconcileReorderedTracks(reorderableTracks, tracks, pendingReorder != null)
        if (pendingReorder != null && reorderableTracks.map(QueueTrack::uniqueKey) == tracks.map(QueueTrack::uniqueKey)) {
            pendingReorder = null
            awaitingReorder = false
        }
    }

    val latestTracks by rememberUpdatedState(tracks)
    LaunchedEffect(awaitingReorder) {
        if (awaitingReorder) {
            // shortcut: Remote moves have no acknowledgement; roll back after 3 seconds without a matching queue.
            delay(3_000)
            reorderableTracks = latestTracks
            pendingReorder = null
            awaitingReorder = false
        }
    }

    val removals = pendingRemovals
    if (removals.isNotEmpty()) {
        val count = removals.sumOf { it.items.size }
        LaunchedEffect(removals) {
            val result =
                snackbarHostState.showSnackbar(
                    message = getPluralString(Res.plurals.removed_from_queue, count, count),
                    actionLabel = getString(Res.string.undo),
                    duration = SnackbarDuration.Short,
                )
            if (result == SnackbarResult.ActionPerformed) {
                viewModel.restoreQueueItems(removals)
            } else {
                viewModel.discardQueueUndo(removals)
            }
            pendingRemovals = pendingRemovals.drop(removals.size)
        }
    }

    fun downloadQueueSongs(entryKeys: Set<Long>) {
        downloadSongs(viewModel.getQueueSongs(entryKeys))
    }

    fun showRemovalUndo(removed: QueueRemoval?) {
        if (removed != null) pendingRemovals = viewModel.pendingQueueRemovals()
    }

    fun removeQueueSongs(entryKeys: Set<Long>) {
        val removed = viewModel.removeQueueEntries(entryKeys)
        selectedEntryKeys = emptySet()
        showRemovalUndo(removed)
    }

    val totalDuration =
        remember(uiState.queueItems, uiState.queueEntryKeys, uiState.currentQueueIndex, uiState.duration) {
            val durations =
                uiState.queueItems.mapIndexed { index, item ->
                    if (uiState.queueEntryKeys[index] < 0) {
                        0L
                    } else {
                        item.duration ?: uiState.duration.takeIf { index == uiState.currentQueueIndex && it > 0L }
                    }
                }
            if (durations.any { it == null }) null else durations.filterNotNull().sumOf { it.coerceAtLeast(0L) }
        }
    val (queuedTracks, suggestedTracks) =
        remember(reorderableTracks, tracks) {
            reorderableTracks.ifEmpty { tracks }.partition { !it.isSuggested }
        }

    val state =
        remember(
            uiState.currentSong,
            currentEntryKey,
            uiState.isPlaying,
            uiState.duration,
            uiState.queueTitle,
            uiState.queueChips,
            uiState.queueItems.size,
            uiState.isShuffleOn,
            uiState.repeatMode,
            uiState.isQueueLoading,
            selectedEntryKeys,
            tracks,
            totalDuration,
            reorderableTracks,
            canControlPlayback,
            queueTitleFallback,
        ) {
            QueueScreenState(
                playlist =
                    PlaylistInfo(
                        name =
                            uiState.queueTitle?.takeIf { it.isNotBlank() }
                                ?: uiState.currentSong?.title?.takeIf { it.isNotBlank() }
                                ?: queueTitleFallback,
                        trackCount = uiState.queueEntryKeys.count { it >= 0 },
                        totalDuration = formatQueueDuration(totalDuration),
                    ),
                chips = uiState.queueChips,
                tracks = queuedTracks,
                suggestedTracks = suggestedTracks,
                isShuffleOn = uiState.isShuffleOn,
                repeatMode = uiState.repeatMode,
                toolbarExpanded = false,
                isQueueLoading = uiState.isQueueLoading,
                selectedEntryKeys = selectedEntryKeys,
                canControlPlayback = canControlPlayback,
                currentEntryKey = currentEntryKey,
                isPlaying = uiState.isPlaying,
                currentDuration = uiState.duration,
            )
        }

    QueueScreen(
        state = state,
        session = session,
        isListenTogetherGuest = isListenTogetherGuest,
        onEvent = { event ->
            if (!canControlPlayback &&
                (
                    event is QueueEvent.PlayTrack || event is QueueEvent.SelectChip || event is QueueEvent.ReorderTrack ||
                        event is QueueEvent.RemoveTrack ||
                        event == QueueEvent.CommitReorder ||
                        event == QueueEvent.DeleteSelected ||
                        event == QueueEvent.ToggleShuffle ||
                        event == QueueEvent.CycleRepeat ||
                        event == QueueEvent.TogglePlayPause ||
                        event == QueueEvent.Previous ||
                        event == QueueEvent.Next
                )
            ) {
                return@QueueScreen
            }
            when (event) {
                is QueueEvent.PlayTrack -> {
                    if (pendingReorder == null) viewModel.playQueueEntry(event.entryKey)
                }

                is QueueEvent.OpenTrackMenu -> {
                    trackMenuEntryKey = event.entryKey
                }

                is QueueEvent.AddTrackToPlaylist -> {
                    addToPlaylist(viewModel.getQueueSongs(setOf(event.entryKey)))
                }

                is QueueEvent.DownloadTrack -> {
                    downloadQueueSongs(setOf(event.entryKey))
                }

                is QueueEvent.ToggleTrackLike -> {
                    viewModel.toggleLikeQueueEntry(event.entryKey)
                }

                is QueueEvent.SelectChip -> {
                    viewModel.selectQueueChip(event.id)
                }

                is QueueEvent.SelectTrack -> {
                    selectedEntryKeys = selectedEntryKeys + event.entryKey
                }

                is QueueEvent.DeselectTrack -> {
                    selectedEntryKeys = selectedEntryKeys - event.entryKey
                }

                is QueueEvent.ReorderTrack -> {
                    if (awaitingReorder || event.fromEntryKey < 0 || event.toEntryKey < 0) return@QueueScreen
                    val from = reorderableTracks.indexOfFirst { it.entryKey == event.fromEntryKey }
                    val to = reorderableTracks.indexOfFirst { it.entryKey == event.toEntryKey }
                    if (from in reorderableTracks.indices && to in reorderableTracks.indices) {
                        val selected =
                            reorderableTracks.indices.filterTo(mutableSetOf()) {
                                reorderableTracks[it].entryKey in
                                    selectedEntryKeys
                            }
                        reorderableTracks =
                            reorderableTracks
                                .moveSelection(from, to, selected)
                                .mapIndexed { index, track -> track.copy(queueIndex = index) }
                        pendingReorder =
                            reorderableTracks
                                .map(QueueTrack::entryKey)
                                .takeUnless { it == tracks.map(QueueTrack::entryKey) }
                    }
                }

                QueueEvent.CommitReorder -> {
                    pendingReorder?.let { entryKeys ->
                        if (viewModel.reorderQueueEntries(entryKeys.filter { it >= 0 })) {
                            awaitingReorder = true
                        } else {
                            reorderableTracks = tracks
                            pendingReorder = null
                        }
                    }
                }

                is QueueEvent.RemoveTrack -> {
                    showRemovalUndo(viewModel.removeQueueEntry(event.entryKey))
                }

                QueueEvent.SelectAll -> {
                    selectedEntryKeys = tracks.filter { !it.isSuggested && it.canRemove }.mapTo(mutableSetOf(), QueueTrack::entryKey)
                }

                QueueEvent.AddSelectedToPlaylist -> {
                    addToPlaylist(viewModel.getQueueSongs(selectedEntryKeys))
                }

                QueueEvent.ClearSelection -> {
                    selectedEntryKeys = emptySet()
                }

                QueueEvent.DeleteSelected -> {
                    removeQueueSongs(selectedEntryKeys)
                }

                QueueEvent.DownloadSelected -> {
                    downloadQueueSongs(selectedEntryKeys)
                    selectedEntryKeys = emptySet()
                }

                QueueEvent.LikeSelected -> {
                    viewModel.likeQueueEntries(selectedEntryKeys)
                    selectedEntryKeys = emptySet()
                }

                QueueEvent.ToggleShuffle -> {
                    viewModel.toggleShuffle()
                }

                QueueEvent.CycleRepeat -> {
                    viewModel.toggleRepeat()
                }

                QueueEvent.ToggleSleepTimer -> {
                    onShowSleepTimer()
                }

                QueueEvent.TogglePlayPause -> {
                    viewModel.togglePlayPause()
                }

                QueueEvent.Previous -> {
                    viewModel.previous()
                }

                QueueEvent.Next -> {
                    viewModel.next()
                }

                QueueEvent.CollapseToolbar -> { }

                QueueEvent.Dismiss -> {
                    onClose()
                }
            }
        },
        onDismiss = onClose,
        modifier = modifier,
        nestedScrollConnection = nestedScrollConnection,
        backHandlerEnabled = backHandlerEnabled,
        handleDismissalBack = handleDismissalBack,
        reorderEnabled = !awaitingReorder,
        snackbarHostState = snackbarHostState,
    )

    val menuEntryKey = trackMenuEntryKey
    if (menuEntryKey != null) {
        val track = tracks.firstOrNull { it.entryKey == menuEntryKey }
        val song = viewModel.getQueueSong(menuEntryKey)
        if (track != null && song != null) {
            platform.QueueMenu(
                song = song.copy(thumbnail = track.thumbnailUrl ?: song.thumbnail),
                durationText = track.durationText(track.entryKey == currentEntryKey, uiState.duration),
                isLiked = track.isLiked,
                onDismiss = { trackMenuEntryKey = null },
                onRemoveFromQueue =
                    if (track.canRemove && canControlPlayback) {
                        { showRemovalUndo(viewModel.removeQueueEntry(menuEntryKey)) }
                    } else {
                        null
                    },
                onPlayNext = {
                    viewModel.playNextQueueEntry(menuEntryKey)
                },
                onStartRadio = {
                    viewModel.startRadio(song)
                },
                onAddToQueue = {
                    viewModel.addQueueEntry(menuEntryKey)
                },
                onLike = {
                    viewModel.toggleLikeQueueEntry(menuEntryKey)
                },
                onGoToAlbum = song.album?.id?.let { albumId -> { onNavigateToAlbum(albumId) } },
                onGoToArtist = onNavigateToArtist,
                onShare = {
                    viewModel.shareSong(song)
                },
                onAltShare = {
                    viewModel.copySongLink(song)
                },
                onDownload = {
                    downloadQueueSongs(setOf(menuEntryKey))
                },
                onNavigateToListenTogether = {
                    platform.prepareListenTogetherQueueImport(song.title, listOf(song))
                    onNavigateToListenTogether()
                },
            )
        } else {
            trackMenuEntryKey = null
        }
    }
}
