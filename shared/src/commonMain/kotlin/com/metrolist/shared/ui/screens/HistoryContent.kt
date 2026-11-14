@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.shared.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.action_cannot_be_undone
import com.metrolist.shared.generated.resources.album
import com.metrolist.shared.generated.resources.artist
import com.metrolist.shared.generated.resources.artists
import com.metrolist.shared.generated.resources.back
import com.metrolist.shared.generated.resources.cancel
import com.metrolist.shared.generated.resources.collection_sort_newest
import com.metrolist.shared.generated.resources.collection_sort_oldest
import com.metrolist.shared.generated.resources.delete
import com.metrolist.shared.generated.resources.delete_selected_count
import com.metrolist.shared.generated.resources.edit
import com.metrolist.shared.generated.resources.history
import com.metrolist.shared.generated.resources.history_also_delete_from_youtube
import com.metrolist.shared.generated.resources.history_days_ago
import com.metrolist.shared.generated.resources.history_delete_item_title
import com.metrolist.shared.generated.resources.history_delete_items_title
import com.metrolist.shared.generated.resources.history_deleting_message
import com.metrolist.shared.generated.resources.history_deleting_title
import com.metrolist.shared.generated.resources.history_empty
import com.metrolist.shared.generated.resources.history_hours_ago
import com.metrolist.shared.generated.resources.history_just_now
import com.metrolist.shared.generated.resources.history_minutes_ago
import com.metrolist.shared.generated.resources.history_no_search_results
import com.metrolist.shared.generated.resources.history_refresh
import com.metrolist.shared.generated.resources.history_remote_delete_unavailable
import com.metrolist.shared.generated.resources.history_remove_item_message
import com.metrolist.shared.generated.resources.history_sort_listening_time
import com.metrolist.shared.generated.resources.last_week
import com.metrolist.shared.generated.resources.menu
import com.metrolist.shared.generated.resources.n_selected
import com.metrolist.shared.generated.resources.required
import com.metrolist.shared.generated.resources.retry
import com.metrolist.shared.generated.resources.save
import com.metrolist.shared.generated.resources.search_history
import com.metrolist.shared.generated.resources.sort_options
import com.metrolist.shared.generated.resources.this_week
import com.metrolist.shared.generated.resources.title
import com.metrolist.shared.generated.resources.today
import com.metrolist.shared.generated.resources.yesterday
import com.metrolist.shared.model.HistoryDateBucket
import com.metrolist.shared.model.HistoryEntryUi
import com.metrolist.shared.model.HistorySortMode
import com.metrolist.shared.model.SortDirection
import com.metrolist.shared.model.groupHistory
import com.metrolist.shared.model.historyEntryTimeText
import com.metrolist.shared.model.toggleDisplayedHistorySelection
import com.metrolist.shared.ui.components.AlertDialog
import com.metrolist.shared.ui.components.ContainedLoadingIndicator
import com.metrolist.shared.ui.components.HistoryEntryGroupPosition
import com.metrolist.shared.ui.components.HistoryEntryRow
import com.metrolist.shared.ui.components.HistoryFilterBar
import com.metrolist.shared.ui.components.HistoryFilterBarText
import com.metrolist.shared.ui.components.PopupContent
import com.metrolist.shared.ui.components.PopupVisibility
import com.metrolist.shared.ui.components.RefreshIndicator
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.menu.SongSelectionMenuSheet
import com.metrolist.shared.ui.menu.historySelectionMenuModel
import com.metrolist.shared.ui.navigation.NavigationBackHandler
import com.metrolist.shared.ui.selection.SelectionTopBar
import com.metrolist.shared.ui.theme.MdSpacing
import com.metrolist.shared.ui.tv.tvFocusHighlight
import com.metrolist.shared.ui.utils.DragScrollableColumn
import com.metrolist.shared.ui.utils.desktopArrowScroll
import com.metrolist.shared.utils.isPullToRefreshSupported
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun HistoryContent(
    entries: List<HistoryEntryUi>,
    displayedEntries: List<HistoryEntryUi>,
    query: String,
    sortMode: HistorySortMode,
    sortDirection: SortDirection,
    onSortDirectionChange: (SortDirection) -> Unit,
    selectedIds: Set<String>,
    isLoading: Boolean,
    isDeleting: Boolean,
    error: String?,
    canDeleteRemote: Boolean,
    onQueryChange: (String) -> Unit,
    onSortChange: (HistorySortMode) -> Unit,
    onRefresh: () -> Unit,
    onPlay: (HistoryEntryUi) -> Unit,
    onToggleSelection: (String) -> Unit,
    onSelectAll: (Set<String>) -> Unit,
    onUpdate: (HistoryEntryUi, String, String, String?) -> Unit,
    onDelete: (HistoryEntryUi, Boolean) -> Unit,
    onDeleteSelected: (Set<String>, Boolean) -> Unit,
    formatMonth: (Long) -> String,
    onNavigateBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    selectionBottomPadding: Dp = contentPadding.calculateBottomPadding(),
    canRefresh: Boolean = true,
    onSongMenu: ((HistoryEntryUi) -> Unit)? = null,
    showInlineEntryMenu: Boolean = true,
    onLoadMore: (() -> Unit)? = null,
    headerActions: @Composable () -> Unit = {},
    selectedHasRemoteEntries: Boolean = entries.any { it.id in selectedIds && !it.remoteFeedbackToken.isNullOrBlank() },
) {
    var editingEntry by remember { mutableStateOf<HistoryEntryUi?>(null) }
    var deletingEntry by remember { mutableStateOf<HistoryEntryUi?>(null) }
    var showBulkDelete by remember { mutableStateOf(false) }
    var showSelectionMenu by remember { mutableStateOf(false) }
    var isSelectMode by remember { mutableStateOf(false) }
    var bulkDeletionRequested by remember { mutableStateOf(false) }
    var nowMs by remember { mutableStateOf(currentTimeMillis()) }
    val displayedIds = remember(displayedEntries) { displayedEntries.mapTo(mutableSetOf()) { it.id } }
    val showFullScreenState = entries.isEmpty() && query.isEmpty()

    LaunchedEffect(bulkDeletionRequested, isDeleting, selectedIds, error) {
        if (bulkDeletionRequested && !isDeleting) {
            if (selectedIds.isEmpty()) {
                isSelectMode = false
                bulkDeletionRequested = false
            } else if (error != null) {
                bulkDeletionRequested = false
            }
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000.milliseconds)
            nowMs = currentTimeMillis()
        }
    }

    val bucketLabels =
        mapOf(
            HistoryDateBucket.TODAY to stringResource(Res.string.today),
            HistoryDateBucket.YESTERDAY to stringResource(Res.string.yesterday),
            HistoryDateBucket.THIS_WEEK to stringResource(Res.string.this_week),
            HistoryDateBucket.LAST_WEEK to stringResource(Res.string.last_week),
        )
    val timeZone = TimeZone.currentSystemDefault()
    val day = Instant.fromEpochMilliseconds(nowMs).toLocalDateTime(timeZone).date
    val grouped =
        remember(displayedEntries, sortMode, day, timeZone, bucketLabels, formatMonth, LocalLocale.current) {
            if (sortMode == HistorySortMode.NEWEST || sortMode == HistorySortMode.OLDEST) {
                groupHistory(displayedEntries, nowMs, bucketLabels, formatMonth, timeZone)
            } else {
                linkedMapOf("" to displayedEntries)
            }
        }

    NavigationBackHandler(enabled = isSelectMode) {
        isSelectMode = false
        if (selectedIds.isNotEmpty()) onSelectAll(emptySet())
    }

    Column(modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
        if (isSelectMode) {
            SelectionTopBar(
                count = selectedIds.size,
                allSelected = displayedIds.isNotEmpty() && displayedIds.all(selectedIds::contains),
                onClose = {
                    isSelectMode = false
                    showSelectionMenu = false
                    if (selectedIds.isNotEmpty()) onSelectAll(emptySet())
                },
                onSelectAll = { onSelectAll(toggleDisplayedHistorySelection(selectedIds, displayedIds)) },
                onMore = { showSelectionMenu = true },
                moreEnabled = true,
            )
        } else if (onNavigateBack != null) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = onNavigateBack,
                    modifier = Modifier.tvFocusHighlight(CircleShape),
                    shapes = IconButtonDefaults.shapes(),
                ) {
                    Icon(
                        MetrolistIcons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(Res.string.back),
                    )
                }
                Text(
                    stringResource(Res.string.history),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                headerActions()
            }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { headerActions() }
        }
        if (error != null && !showFullScreenState) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                TextButton(onClick = onRefresh, shapes = ButtonDefaults.shapes()) { Text(stringResource(Res.string.retry)) }
            }
        }
        val historyBodyContent = @Composable {
            when {
                error != null && showFullScreenState -> {
                    Column(
                        Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(error, color = MaterialTheme.colorScheme.error)
                        Button(onClick = onRefresh, shapes = ButtonDefaults.shapes()) { Text(stringResource(Res.string.retry)) }
                    }
                }

                isLoading && showFullScreenState -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        ContainedLoadingIndicator()
                    }
                }

                showFullScreenState -> {
                    EmptyHistory(if (canRefresh) onRefresh else null)
                }

                else -> {
                    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                        val isWide = maxWidth >= 600.dp
                        val listState = rememberLazyListState()
                        val listModifier = if (isWide) Modifier.widthIn(max = 840.dp) else Modifier.fillMaxWidth()
                        DragScrollableColumn(
                            state = listState,
                            modifier = listModifier.desktopArrowScroll(listState),
                            contentPadding =
                                PaddingValues(
                                    start = if (isWide) 0.dp else MdSpacing.sm,
                                    top = MdSpacing.xs,
                                    end = if (isWide) 0.dp else MdSpacing.sm,
                                    bottom = contentPadding.calculateBottomPadding() + MdSpacing.sm,
                                ),
                        ) {
                            item(key = "history_controls") {
                                HistoryFilterBar(
                                    text =
                                        HistoryFilterBarText(
                                            searchLabel = stringResource(Res.string.search_history),
                                            sortLabels =
                                                HistorySortMode.entries.associateWith {
                                                    stringResource(
                                                        when (it) {
                                                            HistorySortMode.NEWEST -> Res.string.collection_sort_newest
                                                            HistorySortMode.OLDEST -> Res.string.collection_sort_oldest
                                                            HistorySortMode.TITLE -> Res.string.title
                                                            HistorySortMode.ARTIST -> Res.string.artist
                                                            HistorySortMode.PLAY_TIME -> Res.string.history_sort_listening_time
                                                        },
                                                    )
                                                },
                                            sortOptions = stringResource(Res.string.sort_options),
                                        ),
                                    query = query,
                                    onQueryChange = onQueryChange,
                                    sort = sortMode,
                                    sortDirection = sortDirection,
                                    onSortDirectionChange = onSortDirectionChange,
                                    onSortChange = onSortChange,
                                )
                                if (isLoading && entries.isEmpty()) {
                                    Box(Modifier.fillMaxWidth().padding(MdSpacing.sm), contentAlignment = Alignment.Center) {
                                        ContainedLoadingIndicator()
                                    }
                                } else if (displayedEntries.isEmpty() && error == null) {
                                    Text(
                                        stringResource(Res.string.history_no_search_results),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(MdSpacing.xs),
                                    )
                                }
                            }
                            grouped.forEach { (header, sectionEntries) ->
                                if (header.isNotEmpty()) {
                                    item(key = "header_$header") {
                                        Text(
                                            header,
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.padding(top = MdSpacing.sm, bottom = MdSpacing.xxs),
                                        )
                                    }
                                }
                                itemsIndexed(sectionEntries, key = { _, entry -> entry.id }) { index, entry ->
                                    val groupPosition =
                                        when {
                                            sectionEntries.size == 1 -> HistoryEntryGroupPosition.SINGLE
                                            index == 0 -> HistoryEntryGroupPosition.FIRST
                                            index == sectionEntries.lastIndex -> HistoryEntryGroupPosition.LAST
                                            else -> HistoryEntryGroupPosition.MIDDLE
                                        }
                                    HistoryEntryRow(
                                        title = entry.title,
                                        artists = entry.artists,
                                        album = entry.album,
                                        thumbnailUrl = entry.thumbnailUrl,
                                        relativeTimeText =
                                            historyEntryTimeText(
                                                entry = entry,
                                                sortMode = sortMode,
                                                formatRelativeTime = { historyRelativeTime(entry.playedAt, nowMs) },
                                            ),
                                        isSelected = entry.id in selectedIds,
                                        isSelectMode = isSelectMode,
                                        groupPosition = groupPosition,
                                        onPlay = { onPlay(entry) },
                                        onSongMenu = onSongMenu?.let { { it(entry) } },
                                        onToggleSelection = { onToggleSelection(entry.id) },
                                        onLongClick = {
                                            if (!isSelectMode) {
                                                isSelectMode = true
                                                if (entry.id !in selectedIds) onToggleSelection(entry.id)
                                            }
                                        },
                                        onEdit = { editingEntry = entry },
                                        onDelete = { deletingEntry = entry },
                                        showInlineMenu = showInlineEntryMenu,
                                        editText = stringResource(Res.string.edit),
                                        deleteText = stringResource(Res.string.delete),
                                    )
                                }
                            }
                            if (onLoadMore != null) {
                                item(key = "history_load_more") {
                                    LaunchedEffect(displayedEntries.size) { onLoadMore() }
                                    Box(Modifier.fillMaxWidth().padding(MdSpacing.sm), contentAlignment = Alignment.Center) {
                                        ContainedLoadingIndicator()
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (isPullToRefreshSupported && canRefresh) {
            var userRefreshing by remember { mutableStateOf(false) }
            val isLoadingNow by rememberUpdatedState(isLoading)
            LaunchedEffect(userRefreshing) {
                if (!userRefreshing) return@LaunchedEffect
                withTimeoutOrNull(1_500L) { snapshotFlow { isLoadingNow }.first { it } }
                snapshotFlow { isLoadingNow }.first { !it }
                userRefreshing = false
            }
            val refreshState = rememberPullToRefreshState()
            PullToRefreshBox(
                isRefreshing = userRefreshing,
                onRefresh = {
                    userRefreshing = true
                    if (!isLoadingNow) onRefresh()
                },
                state = refreshState,
                indicator = {
                    RefreshIndicator(
                        state = refreshState,
                        isRefreshing = userRefreshing,
                        modifier = Modifier.align(Alignment.TopCenter),
                    )
                },
                modifier = Modifier.weight(1f),
            ) {
                historyBodyContent()
            }
        } else {
            Box(Modifier.weight(1f)) {
                historyBodyContent()
            }
        }

        if (showSelectionMenu && isSelectMode) {
            val deleteLabel = stringResource(Res.string.delete_selected_count, selectedIds.size)
            SongSelectionMenuSheet(
                model =
                    historySelectionMenuModel(
                        count = selectedIds.size,
                        deleteLabel = deleteLabel,
                        allDisplayedSelected = displayedIds.isNotEmpty() && displayedIds.all(selectedIds::contains),
                        onDismiss = { showSelectionMenu = false },
                        onToggleDisplayedSelection = { onSelectAll(toggleDisplayedHistorySelection(selectedIds, displayedIds)) },
                        onCancel = {
                            showSelectionMenu = false
                            isSelectMode = false
                            onSelectAll(emptySet())
                        },
                        onDelete = { showBulkDelete = true },
                    ),
                onDismiss = { showSelectionMenu = false },
            )
        }

        PopupContent(editingEntry) { entry ->
            EditHistoryDialog(
                entry = entry,
                onDismiss = { editingEntry = null },
                onSave = { title, artists, album ->
                    onUpdate(entry, title, artists, album)
                    editingEntry = null
                },
            )
        }
        PopupContent(deletingEntry) { entry ->
            DeleteHistoryDialog(
                entry = entry,
                canDeleteRemote = canDeleteRemote && !entry.remoteFeedbackToken.isNullOrBlank(),
                onDismiss = { deletingEntry = null },
                onDelete = {
                    deletingEntry = null
                    onDelete(entry, it)
                },
            )
        }
        PopupVisibility(visible = showBulkDelete) {
            BulkDeleteHistoryDialog(
                count = selectedIds.size,
                canDeleteRemote = canDeleteRemote && selectedHasRemoteEntries,
                onDismiss = { showBulkDelete = false },
                onDelete = {
                    showBulkDelete = false
                    bulkDeletionRequested = true
                    onDeleteSelected(selectedIds, it)
                },
            )
        }
        PopupVisibility(visible = isDeleting) {
            AlertDialog(
                onDismissRequest = {},
                title = { Text(stringResource(Res.string.history_deleting_title)) },
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ContainedLoadingIndicator(Modifier.size(MdSpacing.lg))
                        Spacer(Modifier.width(MdSpacing.sm))
                        Text(stringResource(Res.string.history_deleting_message))
                    }
                },
                confirmButton = {},
            )
        }
    }
}

@Composable
private fun EmptyHistory(onRefresh: (() -> Unit)?) {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            MetrolistIcons.Outlined.History,
            null,
            modifier = Modifier.size(MdSpacing.xl),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
        )
        Spacer(Modifier.height(MdSpacing.sm))
        Text(
            stringResource(Res.string.history_empty),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.titleMedium,
        )
        if (onRefresh != null) {
            Spacer(Modifier.height(MdSpacing.xs))
            FilledTonalIconButton(onClick = onRefresh, shapes = IconButtonDefaults.shapes()) {
                Icon(MetrolistIcons.Outlined.Refresh, stringResource(Res.string.history_refresh))
            }
        }
    }
}

@Composable
fun EditHistoryDialog(
    entry: HistoryEntryUi,
    onDismiss: () -> Unit,
    onSave: (String, String, String?) -> Unit,
) {
    var title by remember { mutableStateOf(entry.title) }
    var artists by remember { mutableStateOf(entry.artists) }
    var album by remember { mutableStateOf(entry.album.orEmpty()) }
    var showValidationErrors by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.edit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(MdSpacing.xs)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(Res.string.title)) },
                    isError = showValidationErrors && title.isBlank(),
                    supportingText = if (showValidationErrors && title.isBlank()) ({ Text(stringResource(Res.string.required)) }) else null,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = artists,
                    onValueChange = { artists = it },
                    label = { Text(stringResource(Res.string.artists)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = album,
                    onValueChange = { album = it },
                    label = { Text(stringResource(Res.string.album)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (title.isBlank()) {
                        showValidationErrors = true
                    } else {
                        onSave(title.trim(), artists.trim(), album.trim().takeIf(String::isNotBlank))
                    }
                },
                shapes = ButtonDefaults.shapes(),
            ) { Text(stringResource(Res.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) { Text(stringResource(Res.string.cancel)) } },
    )
}

@Composable
fun DeleteHistoryDialog(
    entry: HistoryEntryUi,
    canDeleteRemote: Boolean,
    onDismiss: () -> Unit,
    onDelete: (Boolean) -> Unit,
) {
    var alsoRemote by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.history_delete_item_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(MdSpacing.xs)) {
                Text(stringResource(Res.string.history_remove_item_message, entry.title))
                RemoteDeleteOption(canDeleteRemote, alsoRemote) { alsoRemote = it }
            }
        },
        confirmButton = {
            Button(
                onClick = { onDelete(alsoRemote) },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                shapes = ButtonDefaults.shapes(),
            ) { Text(stringResource(Res.string.delete)) }
        },
        dismissButton = { TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) { Text(stringResource(Res.string.cancel)) } },
    )
}

@Composable
private fun BulkDeleteHistoryDialog(
    count: Int,
    canDeleteRemote: Boolean,
    onDismiss: () -> Unit,
    onDelete: (Boolean) -> Unit,
) {
    var alsoRemote by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(pluralStringResource(Res.plurals.history_delete_items_title, count, count)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(MdSpacing.xs)) {
                Text(stringResource(Res.string.action_cannot_be_undone))
                RemoteDeleteOption(canDeleteRemote, alsoRemote) { alsoRemote = it }
            }
        },
        confirmButton = {
            Button(
                onClick = { onDelete(alsoRemote) },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                shapes = ButtonDefaults.shapes(),
            ) { Text(stringResource(Res.string.delete)) }
        },
        dismissButton = { TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) { Text(stringResource(Res.string.cancel)) } },
    )
}

@Composable
private fun RemoteDeleteOption(
    canDeleteRemote: Boolean,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    if (canDeleteRemote) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked, onCheckedChange)
            Spacer(Modifier.width(MdSpacing.xxs))
            Text(stringResource(Res.string.history_also_delete_from_youtube))
        }
    } else {
        Text(
            stringResource(Res.string.history_remote_delete_unavailable),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun historyRelativeTime(
    playedAt: Long,
    nowMs: Long,
): String {
    val diff = (nowMs - playedAt).coerceAtLeast(0)
    return when {
        diff < 60_000 -> {
            stringResource(Res.string.history_just_now)
        }

        diff < 3_600_000 -> {
            val minutes = (diff / 60_000).toInt()
            pluralStringResource(Res.plurals.history_minutes_ago, minutes, minutes)
        }

        diff < 86_400_000 -> {
            val hours = (diff / 3_600_000).toInt()
            pluralStringResource(Res.plurals.history_hours_ago, hours, hours)
        }

        else -> {
            val days = (diff / 86_400_000).toInt()
            pluralStringResource(Res.plurals.history_days_ago, days, days)
        }
    }
}

private fun currentTimeMillis(): Long =
    kotlin.time.Clock.System
        .now()
        .toEpochMilliseconds()
