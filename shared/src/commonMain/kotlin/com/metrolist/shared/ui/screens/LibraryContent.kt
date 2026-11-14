@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.shared.ui.screens

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SplitButtonDefaults
import androidx.compose.material3.SplitButtonLayout
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metrolist.shared.constants.GridRowSpacing
import com.metrolist.shared.constants.GridScreenPadding
import com.metrolist.shared.constants.GridTileSpacing
import com.metrolist.shared.constants.currentGridCells
import com.metrolist.shared.constants.gridTileColumns
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.display_density
import com.metrolist.shared.generated.resources.filter_downloaded
import com.metrolist.shared.generated.resources.library_load_failed
import com.metrolist.shared.generated.resources.playlist_direction_ascending
import com.metrolist.shared.generated.resources.playlist_direction_descending
import com.metrolist.shared.generated.resources.song_count
import com.metrolist.shared.generated.resources.try_again
import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.model.ArtistItem
import com.metrolist.shared.model.EpisodeItem
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.model.PodcastItem
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.SortDirection
import com.metrolist.shared.model.YTItem
import com.metrolist.shared.model.displayThumbnailUrl
import com.metrolist.shared.model.filterForLibrarySearch
import com.metrolist.shared.model.isPodcast
import com.metrolist.shared.model.isSystemPlaylist
import com.metrolist.shared.model.parseDisplayCount
import com.metrolist.shared.model.thumbnailAspectRatio
import com.metrolist.shared.settings.ArtistNameAliases
import com.metrolist.shared.settings.BlockedContent
import com.metrolist.shared.settings.HiddenLibraryItem
import com.metrolist.shared.settings.HiddenLibraryItemKey
import com.metrolist.shared.settings.HiddenLibraryItemType
import com.metrolist.shared.settings.LibraryContentSort
import com.metrolist.shared.settings.LibraryFilterType
import com.metrolist.shared.settings.LibraryGridColumnsRange
import com.metrolist.shared.settings.LibraryGridPinch
import com.metrolist.shared.settings.LibrarySorting
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.settings.displayName
import com.metrolist.shared.settings.displayTitle
import com.metrolist.shared.settings.moveLibraryItems
import com.metrolist.shared.settings.orderLibraryItems
import com.metrolist.shared.ui.components.ChipShelf
import com.metrolist.shared.ui.components.CollectionPreviews
import com.metrolist.shared.ui.components.ContainedLoadingIndicator
import com.metrolist.shared.ui.components.ExpandableSearchBar
import com.metrolist.shared.ui.components.GridCard
import com.metrolist.shared.ui.components.ListCard
import com.metrolist.shared.ui.components.ReorderMenu
import com.metrolist.shared.ui.components.albumPlayOverlay
import com.metrolist.shared.ui.components.libraryArtwork
import com.metrolist.shared.ui.components.libraryCover
import com.metrolist.shared.ui.components.menuAnchorSemantics
import com.metrolist.shared.ui.components.rememberItemPlayback
import com.metrolist.shared.ui.haptics.HapticEvent
import com.metrolist.shared.ui.haptics.rememberMetrolistHaptics
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.icons.SongStatusIcons
import com.metrolist.shared.ui.menu.ExpressiveSelectionMenu
import com.metrolist.shared.ui.menu.trackPressPosition
import com.metrolist.shared.ui.navigation.LocalRootContentActive
import com.metrolist.shared.ui.navigation.LocalUseDesktopUi
import com.metrolist.shared.ui.navigation.blockInput
import com.metrolist.shared.ui.player.staticConnectedLeadingButtonShapes
import com.metrolist.shared.ui.player.staticConnectedTrailingButtonShapes
import com.metrolist.shared.ui.tv.tvFocusHighlight
import com.metrolist.shared.ui.utils.DragScrollableGrid
import com.metrolist.shared.ui.utils.desktopArrowScroll
import com.metrolist.shared.utils.isDesktop
import com.metrolist.shared.viewmodels.LibraryPresentation
import com.metrolist.shared.viewmodels.LibraryPresentationInput
import kotlinx.coroutines.flow.distinctUntilChanged
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import sh.calvin.reorderable.DragGestureDetector
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.ScrollMoveMode
import sh.calvin.reorderable.rememberReorderableLazyGridState

enum class LibraryFilter {
    TRACKS,
    PLAYLISTS,
    ARTISTS,
    ALBUMS,
    PODCASTS,
}

enum class LibrarySortType {
    RECENTLY_ADDED,
    NAME,
    LAST_UPDATED,
    ARTIST,
    YEAR,
    SONG_COUNT,
    LENGTH,
    PLAYTIME,
    MANUAL,
}

@Immutable
data class LibraryContentText(
    val filterLabels: Map<LibraryFilter, String>,
    val sortLabels: Map<LibrarySortType, String>,
    val empty: String,
    val sortOptions: String,
    val grid: String,
    val list: String,
    val artist: String,
    val loading: String,
    val search: String,
    val searchPlaceholder: String,
    val searchNoResults: String,
)

data class LibraryAutoPlaylist(
    val type: String,
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val badge: String? = null,
)

@Composable
fun rememberLibrarySearchScrollState(
    query: String,
    unfilteredState: LazyGridState,
): LazyGridState {
    val filteredState = rememberLazyGridState()
    return if (query.isBlank()) unfilteredState else filteredState
}

@Composable
fun LibraryContent(
    text: LibraryContentText,
    items: List<YTItem>,
    autoPlaylists: List<LibraryAutoPlaylist>,
    hiddenItemKeys: Set<HiddenLibraryItemKey>,
    isLoading: Boolean,
    error: Boolean = false,
    onRetry: (() -> Unit)? = null,
    isGridView: Boolean,
    selectedFilter: LibraryFilter?,
    manualOrder: String,
    topPadding: Dp,
    compactGrid: Boolean,
    useGridItemSize: Boolean = true,
    onFilterChange: (LibraryFilter?) -> Unit,
    onGridViewChange: (Boolean) -> Unit,
    onManualOrderChange: (String) -> Unit,
    onItemClick: (YTItem) -> Unit,
    onPlaySongs: (List<SongItem>, Int) -> Unit,
    currentSongId: String?,
    isPlaying: Boolean,
    onAutoPlaylistClick: (String) -> Unit,
    itemContainer: @Composable (HiddenLibraryItem, YTItem?, Boolean, () -> Unit, @Composable () -> Unit) -> Unit,
    bottomPadding: Dp = 0.dp,
    extraBottomPadding: Dp = 96.dp,
    scrollState: LazyGridState,
    modifier: Modifier = Modifier,
    searchQuery: String = "",
    isSearchOpen: Boolean = false,
    onSearchQueryChange: ((String) -> Unit)? = null,
    onSearchToggle: () -> Unit = {},
    contentMatchedSongs: List<SongItem> = emptyList(),
    contentSearchHasMore: Boolean = false,
    onLoadMoreContentMatches: () -> Unit = {},
    onSearchDescendingChange: (Boolean) -> Unit = {},
    onPlayAlbum: ((AlbumItem) -> Unit)? = null,
    presentation: LibraryPresentation = remember { LibraryPresentation() },
) {
    val currentSongIdState = rememberUpdatedState(currentSongId)
    val isPlayingState = rememberUpdatedState(isPlaying)
    val rootActive = LocalRootContentActive.current

    val sortingFilter = selectedFilter?.let { LibraryFilterType.valueOf(it.name) } ?: LibraryFilterType.ALL
    val sorting = Settings.shared.librarySorting(sortingFilter)
    val sortType = LibrarySortType.valueOf(sorting.sort.name)
    val sortDirection = sorting.direction
    val gridColumns = Settings.shared.libraryGridColumns
    val useDesktopUi = LocalUseDesktopUi.current
    val pinchColumns = if (useGridItemSize && !useDesktopUi) Settings.shared.libraryPinchColumns else 0
    val contentFilterVersion by BlockedContent.version.collectAsStateWithLifecycle()
    val effectiveFilter = selectedFilter.takeUnless { it == LibraryFilter.PODCASTS && Settings.shared.hidePodcasts }
    val effectiveSortType = sortType.takeIf { it.isAvailableFor(effectiveFilter) } ?: LibrarySortType.RECENTLY_ADDED
    val orderableItems =
        remember(items, effectiveSortType) {
            if (effectiveSortType == LibrarySortType.MANUAL) {
                items.filterNot { it is EpisodeItem || (it is SongItem && it.isPodcast) }
            } else {
                emptyList()
            }
        }
    var reorderOrder by remember(manualOrder) { mutableStateOf(manualOrder) }
    val reorderEnabled = effectiveSortType == LibrarySortType.MANUAL && searchQuery.isBlank()
    val presentationInput =
        remember(
            items,
            effectiveFilter,
            hiddenItemKeys,
            effectiveSortType,
            sortDirection,
            manualOrder,
            contentFilterVersion,
            searchQuery,
            contentMatchedSongs,
        ) {
            LibraryPresentationInput(
                items,
                effectiveFilter,
                hiddenItemKeys,
                effectiveSortType,
                sortDirection,
                manualOrder,
                contentFilterVersion,
                searchQuery,
                contentMatchedSongs,
            )
        }
    val cachedItems = remember(presentation, presentationInput) { presentation.cached(presentationInput) }
    val preparedItems by produceState(cachedItems, presentation, presentationInput, rootActive, isLoading) {
        if (!rootActive || (isLoading && items.isEmpty())) return@produceState

        value = presentation.prepare(presentationInput)
    }
    LaunchedEffect(effectiveSortType, sortDirection) {
        onSearchDescendingChange(effectiveSortType == LibrarySortType.NAME && sortDirection == SortDirection.DESCENDING)
    }
    LaunchedEffect(
        scrollState,
        preparedItems,
        contentMatchedSongs.size,
        contentSearchHasMore,
        rootActive,
        searchQuery,
        effectiveFilter,
    ) {
        val searchesSongs = effectiveFilter == null || effectiveFilter == LibraryFilter.TRACKS
        if (!rootActive || !searchesSongs || searchQuery.trim().length < 2 || !contentSearchHasMore ||
            preparedItems == null
        ) {
            return@LaunchedEffect
        }
        snapshotFlow {
            val layout = scrollState.layoutInfo
            (layout.visibleItemsInfo.lastOrNull()?.index ?: 0) >= layout.totalItemsCount - 12
        }.distinctUntilChanged().collect { nearEnd ->
            if (nearEnd) onLoadMoreContentMatches()
        }
    }
    val visibleAutoPlaylists =
        remember(autoPlaylists, hiddenItemKeys, effectiveFilter, contentFilterVersion) {
            if (effectiveFilter in setOf(LibraryFilter.TRACKS, LibraryFilter.ARTISTS, LibraryFilter.ALBUMS, LibraryFilter.PODCASTS)) {
                emptyList()
            } else {
                autoPlaylists.filterNot {
                    (Settings.shared.hidePodcasts && it.type in setOf("podcasts", "SE")) ||
                        HiddenLibraryItemKey(HiddenLibraryItemType.AUTO, it.type) in hiddenItemKeys
                }
            }
        }
    val searchedAutoPlaylists =
        remember(visibleAutoPlaylists, searchQuery) {
            if (searchQuery.isBlank()) {
                visibleAutoPlaylists
            } else {
                val term = searchQuery.trim()
                visibleAutoPlaylists.filter { it.title.contains(term, ignoreCase = true) }
            }
        }

    val toggleSearch = {
        if (isSearchOpen) onSearchQueryChange?.invoke("")
        onSearchToggle()
    }
    Box(modifier.fillMaxSize()) {
        val prepared = preparedItems
        if (prepared == null) {
            ContainedLoadingIndicator(Modifier.align(Alignment.Center))
            return@Box
        }
        val searchedItems =
            remember(prepared, reorderEnabled, reorderOrder) {
                if (reorderEnabled) orderLibraryItems(prepared, reorderOrder) else prepared
            }

        val haptics = rememberMetrolistHaptics()

        fun moveItem(
            from: YTItem,
            to: YTItem,
        ) {
            reorderOrder =
                moveLibraryItems(
                    orderableItems,
                    searchedItems,
                    reorderOrder,
                    HiddenLibraryItem.from(from).key,
                    HiddenLibraryItem.from(to).key,
                )
            haptics.perform(HapticEvent.DRAG_SNAP)
            onManualOrderChange(reorderOrder)
        }
        val currentGridView = isGridView
        val retainedScrollState = scrollState
        Crossfade(
            targetState = isGridView,
            animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
            label = "libraryLayout",
        ) { isGridView ->
            val scrollState =
                if (isGridView == currentGridView) {
                    retainedScrollState
                } else {
                    remember(currentGridView, retainedScrollState) {
                        LazyGridState(retainedScrollState.firstVisibleItemIndex, retainedScrollState.firstVisibleItemScrollOffset)
                    }
                }
            val sizedGrid = isGridView && useGridItemSize && pinchColumns == 0
            val gridGap = if (isGridView) (14 - (if (pinchColumns > 0) pinchColumns else gridColumns) * 2).dp else 8.dp
            val horizontalGap = if (sizedGrid) GridTileSpacing else gridGap
            val verticalGap = if (sizedGrid) GridRowSpacing else gridGap
            key(isGridView) {
                val reorderState =
                    rememberReorderableLazyGridState(
                        scrollState,
                        scrollThresholdPadding = PaddingValues(top = topPadding, bottom = bottomPadding + extraBottomPadding),
                        scrollMoveMode = ScrollMoveMode.INSERT,
                    ) { from, to ->
                        if (reorderEnabled) {
                            val fromItem = searchedItems.firstOrNull { it.libraryReorderKey == from.key }
                            val toItem = searchedItems.firstOrNull { it.libraryReorderKey == to.key }
                            if (fromItem != null && toItem != null) moveItem(fromItem, toItem)
                        }
                    }
                DragScrollableGrid(
                    columns =
                        if (!isGridView) {
                            GridCells.Fixed(1)
                        } else if (useGridItemSize) {
                            pinchColumns.takeIf { it > 0 }?.let { GridCells.Fixed(it) } ?: currentGridCells()
                        } else if (compactGrid) {
                            GridCells.Fixed(gridColumns)
                        } else {
                            GridCells.Adaptive((360f / gridColumns).dp)
                        },
                    state = scrollState,
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .blockInput(isGridView != currentGridView)
                            .desktopArrowScroll(scrollState)
                            .libraryGridPinch(isGridView && !useDesktopUi, useGridItemSize),
                    contentPadding =
                        PaddingValues(
                            top = topPadding,
                            start = 16.dp,
                            end = 16.dp,
                            bottom = bottomPadding + extraBottomPadding,
                        ),
                    horizontalArrangement = Arrangement.spacedBy(horizontalGap),
                    verticalArrangement = Arrangement.spacedBy(verticalGap),
                ) {
                    item(key = "library_header", contentType = "header", span = { GridItemSpan(maxLineSpan) }) {
                        LibraryHeader(
                            text = text,
                            selectedFilter = effectiveFilter,
                            isGridView = isGridView,
                            sortType = effectiveSortType,
                            isSearchOpen = isSearchOpen,
                            onSearchToggle = toggleSearch,
                            onFilterChange = onFilterChange,
                            onGridViewChange = onGridViewChange,
                        )
                    }
                    if (onSearchQueryChange != null) {
                        item(key = "library_search", contentType = "search", span = { GridItemSpan(maxLineSpan) }) {
                            ExpandableSearchBar(
                                visible = isSearchOpen,
                                query = searchQuery,
                                onQueryChange = onSearchQueryChange,
                                onClose = toggleSearch,
                                placeholder = text.searchPlaceholder,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                    }
                    if (!isLoading && error) {
                        item(key = "library_error", contentType = "status", span = { GridItemSpan(maxLineSpan) }) {
                            Column(Modifier.fillMaxWidth().padding(24.dp)) {
                                Text(stringResource(Res.string.library_load_failed), color = MaterialTheme.colorScheme.error)
                                onRetry?.let { retry ->
                                    TextButton(onClick = retry, shapes = ButtonDefaults.shapes()) {
                                        Text(stringResource(Res.string.try_again))
                                    }
                                }
                            }
                        }
                    }
                    items(
                        count = searchedAutoPlaylists.size,
                        key = { "auto_${searchedAutoPlaylists[it].type}" },
                        contentType = { "auto_playlist" },
                        span = { GridItemSpan(if (isGridView) 1 else maxLineSpan) },
                    ) { index ->
                        val playlist = searchedAutoPlaylists[index]
                        Box(Modifier.animateItem()) {
                            itemContainer(
                                HiddenLibraryItem(
                                    HiddenLibraryItemType.AUTO,
                                    playlist.type,
                                    playlist.title,
                                    subtitle = playlist.subtitle,
                                ),
                                null,
                                isGridView,
                                { onAutoPlaylistClick(playlist.type) },
                            ) {
                                AutoPlaylistItem(playlist, isGridView)
                            }
                        }
                    }
                    if (searchedItems.isEmpty() && searchedAutoPlaylists.isEmpty() && !error) {
                        item(key = "library_empty", contentType = "status", span = { GridItemSpan(maxLineSpan) }) {
                            Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                                if (isLoading) {
                                    Column(
                                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(12.dp),
                                    ) {
                                        ContainedLoadingIndicator(label = text.loading)
                                    }
                                } else {
                                    Text(
                                        if (searchQuery.isNotBlank()) text.searchNoResults else text.empty,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                                    )
                                }
                            }
                        }
                    } else {
                        items(
                            count = searchedItems.size,
                            key = { index -> searchedItems[index].libraryReorderKey },
                            contentType = { index -> searchedItems[index]::class },
                            span = { GridItemSpan(if (isGridView) 1 else maxLineSpan) },
                        ) { index ->
                            val item = searchedItems[index]
                            val playback by rememberItemPlayback(item.id, currentSongIdState, isPlayingState)

                            fun moveTo(targetIndex: Int): (() -> Unit)? =
                                searchedItems
                                    .getOrNull(targetIndex)
                                    ?.takeIf { targetIndex != index }
                                    ?.let { { moveItem(item, it) } }
                            ReorderableItem(reorderState, key = item.libraryReorderKey, enabled = reorderEnabled) {
                                val dragHandleModifier =
                                    Modifier.draggableHandle(
                                        enabled = reorderEnabled,
                                        onDragStarted = { haptics.perform(HapticEvent.DRAG_START) },
                                        dragGestureDetector = if (isDesktop) DragGestureDetector.Press else DragGestureDetector.LongPress,
                                    )
                                itemContainer(
                                    HiddenLibraryItem.from(item),
                                    item,
                                    isGridView,
                                    {
                                        dispatchLibraryItemClick(searchedItems, index, onPlaySongs, onItemClick)
                                    },
                                ) {
                                    LibraryItem(
                                        artistLabel = text.artist,
                                        item = item,
                                        isGridView = isGridView,
                                        isCurrent = item is SongItem && playback.isCurrent,
                                        isPlaying = playback.isPlaying,
                                        onAlbumPlay =
                                            (item as? AlbumItem)?.let { album ->
                                                onPlayAlbum?.let { play -> { play(album) } }
                                            },
                                        reorderHandle =
                                            if (reorderEnabled && searchedItems.size > 1) {
                                                { modifier ->
                                                    ReorderMenu(
                                                        onMoveUp = moveTo(index - 1),
                                                        onMoveDown = moveTo(index + 1),
                                                        onMoveToTop = moveTo(0),
                                                        onMoveToBottom = moveTo(searchedItems.lastIndex),
                                                        modifier = modifier,
                                                        dragHandleModifier = dragHandleModifier,
                                                        onArtwork = true,
                                                    )
                                                }
                                            } else {
                                                null
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
}

private fun Modifier.libraryGridPinch(
    enabled: Boolean,
    useGridItemSize: Boolean,
): Modifier =
    pointerInput(enabled, useGridItemSize) {
        if (!enabled) return@pointerInput
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var pinch: LibraryGridPinch? = null
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.count { it.pressed } >= 2) {
                    pinch =
                        pinch ?: LibraryGridPinch(
                            if (useGridItemSize) {
                                Settings.shared.libraryPinchColumns.takeIf { it > 0 }
                                    ?: gridTileColumns(size.width.toDp() - GridScreenPadding * 2, Settings.shared.gridItemSize)
                            } else {
                                Settings.shared.libraryGridColumns
                            },
                        )
                    val previousColumns = pinch.columns
                    val columns = pinch.zoomBy(event.calculateZoom())
                    if (columns != previousColumns && useGridItemSize) {
                        Settings.shared.libraryPinchColumns = columns
                    } else if (columns != previousColumns) {
                        Settings.shared.libraryGridColumns = columns
                    }
                }
                if (pinch != null) event.changes.forEach { it.consume() }
            } while (event.changes.any { it.pressed })
        }
    }

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LibraryHeader(
    text: LibraryContentText,
    selectedFilter: LibraryFilter?,
    isGridView: Boolean,
    sortType: LibrarySortType,
    isSearchOpen: Boolean,
    onSearchToggle: () -> Unit,
    onFilterChange: (LibraryFilter?) -> Unit,
    onGridViewChange: (Boolean) -> Unit,
) {
    var sortExpanded by remember { mutableStateOf(false) }
    var sortMenuOffset by remember { mutableStateOf(IntOffset.Zero) }
    var gridSizeExpanded by remember { mutableStateOf(false) }
    var gridSizeMenuOffset by remember { mutableStateOf(IntOffset.Zero) }
    val sortingFilter = selectedFilter?.let { LibraryFilterType.valueOf(it.name) } ?: LibraryFilterType.ALL
    val sorting = Settings.shared.librarySorting(sortingFilter)
    val ascending = sorting.direction == SortDirection.ASCENDING
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ChipShelf(
            options = LibraryFilter.entries.filterNot { it == LibraryFilter.PODCASTS && Settings.shared.hidePodcasts },
            selectedOption = selectedFilter,
            onSelectionChange = onFilterChange,
            label = { text.filterLabels.getValue(it) },
            modifier = Modifier.padding(top = 1.dp),
            contentPadding = PaddingValues(horizontal = 2.dp),
        )
        Row(
            // LazyVerticalGrid supplies unbounded height, which can inflate expressive buttons under release optimizations.
            Modifier.fillMaxWidth().height(SplitButtonDefaults.MediumContainerHeight),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f, fill = false).trackPressPosition { sortMenuOffset = it.round() }) {
                    SplitButtonLayout(
                        leadingButton = {
                            SplitButtonDefaults.TonalLeadingButton(
                                onClick = { sortExpanded = true },
                                modifier =
                                    Modifier
                                        .tvFocusHighlight(
                                            RoundedCornerShape(
                                                topStart = 24.dp,
                                                bottomStart = 24.dp,
                                                topEnd = 4.dp,
                                                bottomEnd = 4.dp,
                                            ),
                                        ).menuAnchorSemantics(sortExpanded),
                            ) {
                                Text(
                                    text = text.sortLabels.getValue(sortType),
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        },
                        trailingButton = {
                            SplitButtonDefaults.TrailingButton(
                                colors = ButtonDefaults.filledTonalButtonColors(),
                                elevation = ButtonDefaults.filledTonalButtonElevation(),
                                onClick = {
                                    Settings.shared.setLibrarySorting(
                                        sortingFilter,
                                        sorting.copy(direction = if (ascending) SortDirection.DESCENDING else SortDirection.ASCENDING),
                                    )
                                },
                                enabled = sortType != LibrarySortType.MANUAL,
                                modifier =
                                    Modifier
                                        .tvFocusHighlight(
                                            RoundedCornerShape(
                                                topStart = 4.dp,
                                                bottomStart = 4.dp,
                                                topEnd = 24.dp,
                                                bottomEnd = 24.dp,
                                            ),
                                        ).clip(
                                            RoundedCornerShape(
                                                topStart = 4.dp,
                                                bottomStart = 4.dp,
                                                topEnd = 24.dp,
                                                bottomEnd = 24.dp,
                                            ),
                                        ),
                                shapes = SplitButtonDefaults.trailingButtonShapesFor(SplitButtonDefaults.MediumContainerHeight),
                            ) {
                                val rotation by animateFloatAsState(
                                    if (ascending) {
                                        180f
                                    } else {
                                        0f
                                    },
                                )
                                Icon(
                                    MetrolistIcons.Outlined.KeyboardArrowDown,
                                    contentDescription =
                                        stringResource(
                                            if (ascending) {
                                                Res.string.playlist_direction_descending
                                            } else {
                                                Res.string.playlist_direction_ascending
                                            },
                                        ),
                                    modifier = Modifier.size(SplitButtonDefaults.TrailingIconSize).graphicsLayer { rotationZ = rotation },
                                )
                            }
                        },
                    )
                    ExpressiveSelectionMenu(
                        expanded = sortExpanded,
                        onDismissRequest = { sortExpanded = false },
                        anchorOffset = sortMenuOffset,
                        options = LibrarySortType.entries.filter { it.isAvailableFor(selectedFilter) },
                        selectedOption = sortType,
                        optionLabel = { text.sortLabels.getValue(it) },
                        onOptionSelected = { option ->
                            Settings.shared.setLibrarySorting(
                                sortingFilter,
                                LibrarySorting(LibraryContentSort.valueOf(option.name), option.defaultDirection),
                            )
                        },
                    )
                }
                FilledTonalIconButton(
                    onClick = onSearchToggle,
                    modifier = Modifier.size(40.dp).tvFocusHighlight(CircleShape),
                    colors =
                        IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor =
                                if (isSearchOpen) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.secondaryContainer
                                },
                            contentColor =
                                if (isSearchOpen) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSecondaryContainer
                                },
                        ),
                    shapes = IconButtonDefaults.shapes(),
                ) {
                    Icon(
                        if (isSearchOpen) MetrolistIcons.Default.Close else MetrolistIcons.Default.Search,
                        contentDescription = text.search,
                    )
                }
            }
            if (isDesktop && isGridView) {
                Box(Modifier.trackPressPosition { gridSizeMenuOffset = it.round() }) {
                    FilledTonalIconButton(
                        onClick = { gridSizeExpanded = true },
                        modifier = Modifier.size(40.dp).tvFocusHighlight(CircleShape).menuAnchorSemantics(gridSizeExpanded),
                        shapes = IconButtonDefaults.shapes(),
                    ) {
                        Icon(MetrolistIcons.Outlined.Tune, contentDescription = stringResource(Res.string.display_density))
                    }
                    ExpressiveSelectionMenu(
                        expanded = gridSizeExpanded,
                        onDismissRequest = { gridSizeExpanded = false },
                        anchorOffset = gridSizeMenuOffset,
                        options = LibraryGridColumnsRange.toList(),
                        selectedOption = Settings.shared.libraryGridColumns,
                        optionLabel = { it.toString() },
                        onOptionSelected = { Settings.shared.libraryGridColumns = it },
                    )
                }
            }
            ButtonGroup(
                overflowIndicator = { menuState -> ButtonGroupDefaults.OverflowIndicator(menuState) },
                horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
            ) {
                listOf(false, true).forEachIndexed { index, grid ->
                    val icon = if (grid) MetrolistIcons.Default.GridView else MetrolistIcons.AutoMirrored.Outlined.List
                    val label = if (grid) text.grid else text.list
                    customItem(
                        buttonGroupContent = {
                            val interactionSource = remember { MutableInteractionSource() }
                            val shapes = if (index == 0) staticConnectedLeadingButtonShapes() else staticConnectedTrailingButtonShapes()
                            ToggleButton(
                                checked = isGridView == grid,
                                interactionSource = interactionSource,
                                onCheckedChange = { onGridViewChange(grid) },
                                modifier = Modifier.size(40.dp).animateWidth(interactionSource, 12.dp).tvFocusHighlight(shapes.shape),
                                shapes = shapes,
                                contentPadding = PaddingValues(0.dp),
                            ) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Icon(icon, contentDescription = label, modifier = Modifier.size(20.dp))
                                }
                            }
                        },
                        menuContent = {
                            DropdownMenuItem(
                                leadingIcon = { Icon(icon, contentDescription = null) },
                                text = { Text(label) },
                                onClick = { onGridViewChange(grid) },
                            )
                        },
                    )
                }
            }
        }
    }
}

private fun LibrarySortType.isAvailableFor(filter: LibraryFilter?): Boolean =
    when (this) {
        LibrarySortType.ARTIST, LibrarySortType.YEAR, LibrarySortType.LENGTH -> {
            filter == LibraryFilter.ALBUMS
        }

        LibrarySortType.SONG_COUNT -> {
            filter in
                setOf(LibraryFilter.ARTISTS, LibraryFilter.ALBUMS, LibraryFilter.PLAYLISTS, null)
        }

        LibrarySortType.PLAYTIME -> {
            filter in
                setOf(LibraryFilter.ARTISTS, LibraryFilter.ALBUMS, LibraryFilter.PLAYLISTS, null)
        }

        else -> {
            true
        }
    }

@Composable
private fun AutoPlaylistItem(
    playlist: LibraryAutoPlaylist,
    isGridView: Boolean,
) {
    if (isGridView) {
        Box(Modifier.fillMaxWidth()) {
            GridCard(
                cover = libraryCover(playlist.type).takeIf { Settings.shared.collectionCovers[playlist.type] == null },
                title = playlist.title,
                subtitle = playlist.subtitle,
                thumbnail = Settings.shared.collectionCovers[playlist.type],
                icon = playlist.icon,
            )
            playlist.badge?.let { AutoPlaylistBadge(it, Modifier.align(Alignment.TopEnd).padding(8.dp)) }
        }
    } else {
        Box(Modifier.fillMaxWidth()) {
            ListCard(
                cover = libraryCover(playlist.type).takeIf { Settings.shared.collectionCovers[playlist.type] == null },
                title = playlist.title,
                subtitle = playlist.subtitle,
                thumbnail = Settings.shared.collectionCovers[playlist.type],
                icon = playlist.icon,
            )
            playlist.badge?.let { AutoPlaylistBadge(it, Modifier.align(Alignment.CenterEnd).padding(end = 8.dp)) }
        }
    }
}

@Composable
private fun AutoPlaylistBadge(
    label: String,
    modifier: Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

@Composable
private fun LibraryItem(
    artistLabel: String,
    item: YTItem,
    isGridView: Boolean,
    reorderHandle: (@Composable (Modifier) -> Unit)? = null,
    onAlbumPlay: (() -> Unit)? = null,
    isCurrent: Boolean,
    isPlaying: Boolean,
) {
    val artworkOverlay: (@Composable BoxScope.() -> Unit)? =
        if (item is SongItem) {
            null
        } else {
            albumPlayOverlay(onAlbumPlay)
        }
    val subtitle =
        when (item) {
            is SongItem -> {
                item.artists.joinToString { it.displayName }
            }

            is AlbumItem -> {
                item.artists.joinToString { it.displayName }
            }

            is ArtistItem -> {
                artistLabel
            }

            is PlaylistItem -> {
                listOfNotNull(
                    parseDisplayCount(item.songCount)?.let { pluralStringResource(Res.plurals.song_count, it, it) },
                    item.author?.let { ArtistNameAliases.resolve(null, it) }?.takeIf(String::isNotBlank),
                ).joinToString(" • ")
            }

            is PodcastItem -> {
                item.author?.let { ArtistNameAliases.resolve(null, it) }.orEmpty()
            }

            is EpisodeItem -> {
                item.author?.displayName.orEmpty()
            }

            else -> {
                ""
            }
        }
    val isDownloaded = (item as? PlaylistItem)?.isDownloaded == true || (item as? AlbumItem)?.isDownloaded == true
    val artistId = (item as? ArtistItem)?.id
    val localArtwork =
        (item as? PlaylistItem)
            ?.takeIf { it.isLocal && it.customThumbnail.isNullOrBlank() }
            ?.artworkThumbnails
    if (isGridView) {
        Box {
            GridCard(
                cover = (item as? PlaylistItem)?.let(::libraryCover),
                title = item.displayTitle,
                subtitle = subtitle,
                thumbnail =
                    (item as? PlaylistItem)?.let { libraryArtwork(it.id, it.thumbnail, it.customThumbnail) } ?: item.displayThumbnailUrl,
                localArtwork = localArtwork,
                isPlaylistArtwork = item is PlaylistItem,
                icon = null,
                artistId = artistId,
                artworkOverlay = artworkOverlay,
                artworkAspectRatio = item.thumbnailAspectRatio,
                isCurrent = isCurrent,
                isPlaying = isPlaying,
            )
            if (isDownloaded) DownloadedCollectionIcon(Modifier.align(Alignment.TopEnd).padding(8.dp))
            reorderHandle?.invoke(Modifier.align(Alignment.TopStart))
        }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                ListCard(
                    cover = (item as? PlaylistItem)?.let(::libraryCover),
                    title = item.displayTitle,
                    subtitle = subtitle,
                    thumbnail =
                        (item as? PlaylistItem)?.let {
                            libraryArtwork(
                                it.id,
                                it.thumbnail,
                                it.customThumbnail,
                            )
                        } ?: item.displayThumbnailUrl,
                    localArtwork = localArtwork,
                    isPlaylistArtwork = item is PlaylistItem,
                    icon = null,
                    artistId = artistId,
                    artworkOverlay = artworkOverlay,
                    artworkAspectRatio = item.thumbnailAspectRatio,
                    isCurrent = isCurrent,
                    isPlaying = isPlaying,
                )
            }
            if (isDownloaded) DownloadedCollectionIcon(Modifier.padding(horizontal = 8.dp))
            reorderHandle?.invoke(Modifier)
        }
    }
}

private val YTItem.libraryReorderKey: String
    get() = "${this::class.simpleName}_$id"

internal fun List<YTItem>.filterForLibrary(
    filter: LibraryFilter?,
    hiddenItemKeys: Set<HiddenLibraryItemKey>,
): List<YTItem> =
    filter { item -> (item !is PlaylistItem || !item.isSystemPlaylist()) && HiddenLibraryItem.from(item).key !in hiddenItemKeys }
        .let { visible ->
            when (filter) {
                LibraryFilter.TRACKS -> {
                    visible.filterIsInstance<SongItem>().filterNot { it.isPodcast }
                }

                LibraryFilter.PLAYLISTS -> {
                    visible.filterIsInstance<PlaylistItem>()
                }

                LibraryFilter.ARTISTS -> {
                    visible.filterIsInstance<ArtistItem>()
                }

                LibraryFilter.ALBUMS -> {
                    visible.filterIsInstance<AlbumItem>()
                }

                LibraryFilter.PODCASTS -> {
                    visible.filterIsInstance<PodcastItem>()
                }

                null -> {
                    visible
                }
            }
        }

internal fun sortLibraryItems(
    items: List<YTItem>,
    sort: LibrarySortType,
    manualOrder: String,
    direction: SortDirection = sort.defaultDirection,
): List<YTItem> =
    when (sort) {
        LibrarySortType.RECENTLY_ADDED -> {
            items.sortedByLibraryMetadata(direction, YTItem::libraryAddedAt)
        }

        LibrarySortType.NAME -> {
            items.sortedByLibraryMetadata(direction) { it.title.lowercase() }
        }

        LibrarySortType.LAST_UPDATED -> {
            items.sortedByLibraryMetadata(direction, YTItem::libraryUpdatedAt)
        }

        LibrarySortType.ARTIST -> {
            items.sortedByLibraryMetadata(direction) {
                (it as? AlbumItem)
                    ?.artists
                    ?.firstOrNull()
                    ?.name
                    ?.lowercase()
            }
        }

        LibrarySortType.YEAR -> {
            items.sortedByLibraryMetadata(direction) { (it as? AlbumItem)?.year?.toIntOrNull() }
        }

        LibrarySortType.SONG_COUNT -> {
            items.sortedByLibraryMetadata(direction, ::librarySongCount)
        }

        LibrarySortType.LENGTH -> {
            items.sortedByLibraryMetadata(direction) { (it as? AlbumItem)?.duration }
        }

        LibrarySortType.PLAYTIME -> {
            items.sortedByLibraryMetadata(direction, ::libraryPlaytime)
        }

        LibrarySortType.MANUAL -> {
            orderLibraryItems(items, manualOrder)
        }
    }

internal fun <T : Comparable<T>> List<YTItem>.sortedByLibraryMetadata(
    direction: SortDirection,
    value: (YTItem) -> T?,
): List<YTItem> =
    map { it to value(it) }
        .sortedWith { first, second ->
            val left = first.second
            val right = second.second
            when {
                left == null && right == null -> 0
                left == null -> 1
                right == null -> -1
                direction == SortDirection.ASCENDING -> left.compareTo(right)
                else -> right.compareTo(left)
            }
        }.map { it.first }

internal val LibrarySortType.defaultDirection: SortDirection
    get() =
        if (this in
            setOf(LibrarySortType.RECENTLY_ADDED, LibrarySortType.LAST_UPDATED)
        ) {
            SortDirection.DESCENDING
        } else {
            SortDirection.ASCENDING
        }

private fun librarySongCount(item: YTItem): Int? =
    when (item) {
        is ArtistItem -> item.songCount
        is AlbumItem -> item.songCount
        is PlaylistItem -> item.songCount?.toIntOrNull()
        else -> null
    }

private fun libraryPlaytime(item: YTItem): Long? =
    when (item) {
        is ArtistItem -> item.playtime
        is AlbumItem -> item.playtime
        is PlaylistItem -> item.playtime
        else -> null
    }

@Composable
private fun DownloadedCollectionIcon(modifier: Modifier = Modifier) {
    Icon(
        imageVector = SongStatusIcons.Downloaded,
        contentDescription = stringResource(Res.string.filter_downloaded),
        tint = MaterialTheme.colorScheme.primary,
        modifier = modifier.size(20.dp),
    )
}

internal fun prepareLibraryItems(
    items: List<YTItem>,
    filter: LibraryFilter?,
    hiddenItemKeys: Set<HiddenLibraryItemKey>,
    sort: LibrarySortType,
    manualOrder: String,
    direction: SortDirection,
    query: String,
    contentMatchedSongs: List<SongItem>,
): List<YTItem> {
    val visibleItems =
        items
            .filterNot { it is EpisodeItem || (it is SongItem && filter != LibraryFilter.TRACKS) }
            .filterForLibrary(filter, hiddenItemKeys)
    val matches =
        if (query.isBlank()) {
            visibleItems
        } else {
            visibleItems.filterForLibrarySearch(query) + contentMatchedSongs.filterForLibrary(filter, hiddenItemKeys)
        }
    return sortLibraryItems(matches.distinctBy { it::class to it.id }, sort, manualOrder, direction)
}

internal fun dispatchLibraryItemClick(
    displayedItems: List<YTItem>,
    index: Int,
    onPlaySongs: (List<SongItem>, Int) -> Unit,
    onOpenItem: (YTItem) -> Unit,
) {
    val item = displayedItems.getOrNull(index) ?: return
    if (item is SongItem) {
        val songs = displayedItems.filterIsInstance<SongItem>()
        onPlaySongs(songs, songs.indexOf(item))
    } else {
        CollectionPreviews.record(item)
        onOpenItem(item)
    }
}
