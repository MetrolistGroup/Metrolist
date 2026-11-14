@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.shared.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.add_a_song
import com.metrolist.shared.generated.resources.minutes_short
import com.metrolist.shared.generated.resources.n_hidden_song
import com.metrolist.shared.generated.resources.partial_song_count
import com.metrolist.shared.generated.resources.search_no_results
import com.metrolist.shared.generated.resources.seconds_short
import com.metrolist.shared.generated.resources.song_count_with_hidden
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.canEdit
import com.metrolist.shared.model.playlistLazyItemKeys
import com.metrolist.shared.model.playlistSearchIndices
import com.metrolist.shared.model.playlistTotalDuration
import com.metrolist.shared.model.withLocalArtwork
import com.metrolist.shared.ui.LocalIsTv
import com.metrolist.shared.ui.components.CollectionHeaderHorizontalPadding
import com.metrolist.shared.ui.components.CollectionHeaderNavigation
import com.metrolist.shared.ui.components.ContainedLoadingIndicator
import com.metrolist.shared.ui.components.GroupedListItem
import com.metrolist.shared.ui.components.InlineSearchRow
import com.metrolist.shared.ui.components.LazyListScrollbar
import com.metrolist.shared.ui.components.PlaylistHeader
import com.metrolist.shared.ui.components.PlaylistHeaderActions
import com.metrolist.shared.ui.components.ReorderMenu
import com.metrolist.shared.ui.components.ScrollingAddButton
import com.metrolist.shared.ui.components.SongListItem
import com.metrolist.shared.ui.components.mediumCollectionArtworkSize
import com.metrolist.shared.ui.components.rememberItemPlayback
import com.metrolist.shared.ui.components.rememberScrollingFabState
import com.metrolist.shared.ui.haptics.HapticEvent
import com.metrolist.shared.ui.haptics.rememberMetrolistHaptics
import com.metrolist.shared.ui.menu.artworkMenuActions
import com.metrolist.shared.ui.menu.onSecondaryClick
import com.metrolist.shared.ui.navigation.NavigationBackHandler
import com.metrolist.shared.ui.tv.TvReorderScrollTarget
import com.metrolist.shared.ui.utils.DragScrollableColumn
import com.metrolist.shared.ui.utils.desktopArrowScroll
import com.metrolist.shared.ui.utils.verticalScrollWithDrag
import com.metrolist.shared.utils.isDesktop
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.ReorderableLazyListState
import sh.calvin.reorderable.rememberReorderableLazyListState

data class PlaylistContentText(
    val play: String,
    val shuffle: String,
    val songCount: @Composable (Int) -> String,
    val suggestions: String,
)

@Composable
fun PlaylistContent(
    playlist: PlaylistItem?,
    songs: List<SongItem>,
    artworkSongs: List<SongItem> = songs,
    durationSongs: List<SongItem> = artworkSongs,
    hasMoreSongs: Boolean = false,
    hiddenSongCount: Int = 0,
    isLoading: Boolean = false,
    previewThumbnail: String? = null,
    loadingHeader: (@Composable () -> Unit)? = null,
    paginationContent: (@Composable () -> Unit)? = null,
    text: PlaylistContentText,
    onPlayAll: () -> Unit,
    onShuffle: () -> Unit,
    onTrackClick: (SongItem, Int) -> Unit,
    onTrackMenuClick: (SongItem, Int) -> Unit,
    onTrackLongClick: ((SongItem) -> Unit)? = null,
    onBackClick: (() -> Unit)? = null,
    fallbackIcon: ImageVector? = null,
    cover: DrawableResource? = null,
    isFavorite: Boolean = false,
    onFavorite: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    preparedSongKeys: List<String>? = null,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    currentSongId: String? = null,
    isPlaying: Boolean = false,
    suggestions: List<SongItem> = emptyList(),
    downloadedSongIds: Set<String> = emptySet(),
    onArtistClick: ((String) -> Unit)? = null,
    header: @Composable (() -> Unit)? = null,
    orderControls: @Composable (() -> Unit)? = null,
    aboveOrderControls: @Composable (() -> Unit)? = null,
    selectionActive: Boolean = false,
    selectionControls: @Composable (() -> Unit)? = null,
    emptyContent: @Composable (() -> Unit)? = null,
    reorderMode: Boolean = false,
    searchEnabled: Boolean = true,
    trackContainer: @Composable (SongItem, Boolean, @Composable () -> Unit) -> Unit = { _, _, content -> content() },
    isTrackSelected: (SongItem) -> Boolean = { false },
    trackTrailingContent: (@Composable (SongItem, Int) -> Unit)? = null,
    onReorder: (fromIndex: Int, toIndex: Int) -> Unit = { _, _ -> },
    onReorderDragStarted: () -> Unit = {},
    onReorderFinished: () -> Unit = {},
    searchQuery: String = "",
    onSearchQueryChange: ((String) -> Unit)? = null,
    searchOpen: Boolean? = null,
    onSearchOpenChange: (Boolean) -> Unit = {},
    onMoreOptions: (() -> Unit)? = null,
    onEdit: (() -> Unit)? = null,
    onAddSong: (() -> Unit)? = null,
) {
    val currentSongIdState = rememberUpdatedState(currentSongId)
    val isPlayingState = rememberUpdatedState(isPlaying)
    val canEdit = playlist?.canEdit == true
    val onAddSong = onAddSong.takeIf { canEdit }
    val onEdit = onEdit.takeIf { canEdit }
    val headerPlaylist = remember(playlist, artworkSongs) { playlist?.withLocalArtwork(artworkSongs) }
    val reportedSongCount = playlist?.songCount?.takeIf { it.isNotBlank() }
    val loadedSongCount = artworkSongs.size + hiddenSongCount
    val totalSongLabel =
        reportedSongCount?.toIntOrNull()?.let { text.songCount(it) }
            ?: reportedSongCount
            ?: if (hasMoreSongs) {
                stringResource(Res.string.partial_song_count, text.songCount(loadedSongCount))
            } else {
                text.songCount(loadedSongCount)
            }
    val songCountWithHidden =
        if (hiddenSongCount > 0) {
            stringResource(
                Res.string.song_count_with_hidden,
                totalSongLabel,
                pluralStringResource(Res.plurals.n_hidden_song, hiddenSongCount, hiddenSongCount),
            )
        } else {
            totalSongLabel
        }

    val duration =
        remember(durationSongs, hasMoreSongs, loadedSongCount) {
            playlistTotalDuration(durationSongs, hasMoreSongs, loadedSongCount)
        }
    val durationText =
        playlist?.durationText?.takeIf(String::isNotBlank) ?: duration?.let {
            if (it < 60) {
                pluralStringResource(Res.plurals.seconds_short, it.toInt(), it.toInt())
            } else {
                val minutes = (it / 60).toInt()
                pluralStringResource(Res.plurals.minutes_short, minutes, minutes)
            }
        }
    val songCountLabel = listOfNotNull(playlist?.viewCount, songCountWithHidden, durationText).joinToString(" • ")

    var internalSearchOpen by remember(playlist?.id) { mutableStateOf(searchQuery.isNotBlank()) }
    val isSearchOpen = searchOpen ?: internalSearchOpen
    val setSearchOpen: (Boolean) -> Unit = { if (searchOpen != null) onSearchOpenChange(it) else internalSearchOpen = it }
    var internalSearchQuery by remember(playlist?.id) { mutableStateOf("") }
    val effectiveQuery = onSearchQueryChange?.let { searchQuery } ?: internalSearchQuery
    val searchListState = rememberLazyListState()
    val activeListState = if (effectiveQuery.isBlank()) listState else searchListState
    val updateQuery: (String) -> Unit = { newQuery ->
        if (onSearchQueryChange != null) {
            onSearchQueryChange(newQuery)
        } else {
            internalSearchQuery = newQuery
        }
    }
    val toggleSearch: () -> Unit = {
        setSearchOpen(!isSearchOpen)
        if (isSearchOpen) {
            updateQuery("")
        }
    }
    NavigationBackHandler(enabled = isSearchOpen, onBack = toggleSearch)
    val displayedIndices =
        remember(songs, effectiveQuery, onSearchQueryChange == null) {
            if (onSearchQueryChange == null && effectiveQuery.isNotBlank()) {
                songs.playlistSearchIndices(effectiveQuery)
            } else {
                null
            }
        }
    val displayedSongs = remember(songs, displayedIndices) { displayedIndices?.map(songs::get) ?: songs }
    val songKeys = preparedSongKeys ?: remember(displayedSongs) { displayedSongs.playlistLazyItemKeys() }
    val effectiveSuggestions = suggestions.takeIf { effectiveQuery.isBlank() }.orEmpty()

    val isTv = LocalIsTv.current
    var tvScrollTarget by remember(playlist?.id) { mutableStateOf<TvReorderScrollTarget?>(null) }
    val moveAndCommit: (Int, Int) -> Unit = { from, to ->
        if (isTv) {
            tvScrollTarget = TvReorderScrollTarget(songKeys[from], from)
        } else {
            val firstIndex = activeListState.firstVisibleItemIndex
            val firstOffset = activeListState.firstVisibleItemScrollOffset
            activeListState.requestScrollToItem(firstIndex, firstOffset)
        }
        onReorder(from, to)
        onReorderFinished()
    }

    LaunchedEffect(reorderMode, isSearchOpen) {
        if (reorderMode && isSearchOpen) {
            setSearchOpen(false)
            updateQuery("")
        }
    }

    val showPlayback = displayedSongs.isNotEmpty()
    val onAddToLibrary = onFavorite.takeUnless { playlist?.isEditable == true || playlist?.isLocal == true }
    val layoutDirection = LocalLayoutDirection.current
    var titleBottom by remember(playlist?.id) { mutableFloatStateOf(Float.POSITIVE_INFINITY) }
    var listTop by remember { mutableFloatStateOf(0f) }
    val headerIndex = if (header != null) 1 else 0
    val showCollapsedTitle by remember(playlist?.id, activeListState, headerIndex) {
        derivedStateOf { activeListState.firstVisibleItemIndex > headerIndex || titleBottom <= listTop }
    }

    Box(modifier.fillMaxSize()) {
        CollectionHeaderNavigation(
            if (isSearchOpen) toggleSearch else onBackClick,
            onMoreOptions,
            title = {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    AnimatedVisibility(
                        visible = maxWidth < 960.dp && showCollapsedTitle && !isSearchOpen && !selectionActive && loadingHeader == null,
                        enter = fadeIn(MaterialTheme.motionScheme.fastEffectsSpec()),
                        exit = fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()),
                    ) {
                        Text(
                            text = playlist?.title.orEmpty(),
                            modifier = Modifier.padding(start = 64.dp, end = if (onMoreOptions != null) 64.dp else 16.dp),
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            },
            selectionControls = selectionControls.takeIf { selectionActive },
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                val isWide = maxWidth >= 960.dp
                val isMedium = maxWidth >= 600.dp && !isWide
                LaunchedEffect(effectiveQuery.isNotBlank(), isWide) {
                    if (effectiveQuery.isNotBlank() && !isWide) {
                        searchListState.animateScrollToItem(listOfNotNull(header).size + 1)
                    }
                }
                val leadingItems =
                    if (isWide) {
                        0
                    } else {
                        1 + (if (header != null) 1 else 0) +
                            (
                                if (loadingHeader == null &&
                                    (orderControls != null || aboveOrderControls != null || searchEnabled || isSearchOpen)
                                ) {
                                    1
                                } else {
                                    0
                                }
                            )
                    }
                LaunchedEffect(tvScrollTarget, songKeys, leadingItems) {
                    val target = tvScrollTarget ?: return@LaunchedEffect
                    val index = target.listIndex(songKeys, leadingItems) ?: return@LaunchedEffect
                    withFrameNanos { }
                    val layout = activeListState.layoutInfo
                    val visible =
                        layout.visibleItemsInfo.any {
                            it.key == target.key && it.index == index && it.offset >= layout.viewportStartOffset &&
                                it.offset + it.size <= layout.viewportEndOffset
                        }
                    if (!visible) activeListState.animateScrollToItem(index)
                    tvScrollTarget = null
                }
                val mediumArtworkSize =
                    if (isDesktop && isMedium) {
                        mediumCollectionArtworkSize(
                            maxWidth,
                            maxHeight - contentPadding.calculateTopPadding() - contentPadding.calculateBottomPadding(),
                        )
                    } else {
                        160.dp
                    }
                LaunchedEffect(isSearchOpen, isWide, loadingHeader) {
                    if (isSearchOpen && !isWide) {
                        activeListState.animateScrollToItem(listOfNotNull(header).size + 1)
                    }
                }
                val songIndexByKey =
                    remember(songKeys, reorderMode) {
                        if (reorderMode) songKeys.withIndex().associate { (index, key) -> key to index } else emptyMap()
                    }
                val haptics = rememberMetrolistHaptics()
                val reorderableState =
                    if (reorderMode) {
                        rememberReorderableLazyListState(activeListState) { from, to ->
                            val fromIndex = songIndexByKey[from.key]
                            val toIndex = songIndexByKey[to.key]
                            if (fromIndex != null && toIndex != null) {
                                haptics.perform(HapticEvent.DRAG_SNAP)
                                onReorder(fromIndex, toIndex)
                            }
                        }
                    } else {
                        null
                    }
                var dragWasActive by remember { mutableStateOf(false) }
                LaunchedEffect((reorderableState?.isAnyItemDragging == true)) {
                    if ((reorderableState?.isAnyItemDragging == true)) {
                        dragWasActive = true
                    } else if (dragWasActive) {
                        dragWasActive = false
                        onReorderFinished()
                    }
                }

                if (isWide) {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .padding(contentPadding),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxHeight()
                                    .widthIn(max = 1400.dp)
                                    .fillMaxWidth()
                                    .padding(end = 24.dp),
                            horizontalArrangement = Arrangement.Start,
                        ) {
                            Column(
                                modifier =
                                    Modifier
                                        .width(384.dp)
                                        .padding(horizontal = 24.dp)
                                        .verticalScrollWithDrag(rememberScrollState()),
                                horizontalAlignment = Alignment.Start,
                            ) {
                                if (header != null) {
                                    header()
                                }

                                if (loadingHeader != null) {
                                    loadingHeader()
                                } else {
                                    PlaylistHeader(
                                        modifier = Modifier.onSecondaryClick(onMoreOptions),
                                        playlist = headerPlaylist,
                                        onEdit = onEdit,
                                        cover = cover,
                                        previewThumbnail = previewThumbnail,
                                        isWide = true,
                                        songCountLabel = songCountLabel,
                                        onAuthorClick = onArtistClick,
                                        fallbackIcon = fallbackIcon,
                                        actions =
                                            if (showPlayback || onAddToLibrary != null) {
                                                {
                                                    PlaylistHeaderActions(
                                                        onPlayAll = onPlayAll,
                                                        onShuffle = onShuffle,
                                                        playLabel = text.play,
                                                        shuffleLabel = text.shuffle,
                                                        showPlayback = showPlayback,
                                                        isFavorite = isFavorite,
                                                        onFavorite = onAddToLibrary,
                                                        buttonHeight = 48.dp,
                                                    )
                                                }
                                            } else {
                                                null
                                            },
                                    )
                                }
                            }

                            Column(modifier = Modifier.weight(1f)) {
                                if (loadingHeader == null &&
                                    (orderControls != null || aboveOrderControls != null || searchEnabled || isSearchOpen)
                                ) {
                                    InlineSearchRow(
                                        onSearchToggle = toggleSearch.takeIf { searchEnabled && !reorderMode && searchOpen == null },
                                        isSearchOpen = isSearchOpen,
                                        query = effectiveQuery,
                                        onQueryChange = updateQuery,
                                        onClose = toggleSearch,
                                        orderControls = orderControls,
                                        aboveOrderControls = aboveOrderControls,
                                    )
                                }

                                if (displayedSongs.isEmpty() && effectiveQuery.isNotBlank() && paginationContent == null) {
                                    Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                                        Text(
                                            text = stringResource(Res.string.search_no_results),
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                } else if (displayedSongs.isEmpty() && emptyContent != null) {
                                    Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                                        emptyContent()
                                    }
                                } else {
                                    Box(
                                        modifier = Modifier.weight(1f).fillMaxWidth(),
                                    ) {
                                        DragScrollableColumn(
                                            state = activeListState,
                                            // Keep scroll input nodes attached during reorder; the drag handle consumes movement.
                                            modifier =
                                                Modifier
                                                    .fillMaxSize()
                                                    .padding(end = 16.dp)
                                                    .desktopArrowScroll(
                                                        activeListState,
                                                        enabled = reorderableState?.isAnyItemDragging != true,
                                                    ),
                                        ) {
                                            itemsIndexed(displayedSongs, key = {
                                                index,
                                                _,
                                                ->
                                                songKeys[index]
                                            }, contentType = { _, _ -> "song" }) { index, song ->
                                                PlaylistSongItemRow(
                                                    song = song,
                                                    index = index,
                                                    count = displayedSongs.size,
                                                    sourceIndex = displayedIndices?.get(index) ?: index,
                                                    itemKey = songKeys[index],
                                                    currentSongId = currentSongIdState,
                                                    isPlaying = isPlayingState,
                                                    isDownloaded = song.id in downloadedSongIds,
                                                    onTrackClick = onTrackClick,
                                                    onTrackMenuClick = onTrackMenuClick,
                                                    onTrackLongClick = onTrackLongClick,
                                                    onArtistClick = onArtistClick,
                                                    trackContainer = trackContainer,
                                                    trackTrailingContent = trackTrailingContent,
                                                    reorderableState = reorderableState.takeIf { reorderMode },
                                                    onReorderDragStarted = onReorderDragStarted,
                                                    onMove = moveAndCommit,
                                                    isSelected = isTrackSelected(song),
                                                )
                                            }

                                            if (paginationContent != null) {
                                                item(key = "pagination") { paginationContent() }
                                            }
                                            if (effectiveSuggestions.isNotEmpty()) {
                                                item(key = "suggestions_title") {
                                                    Text(
                                                        text = text.suggestions,
                                                        style = MaterialTheme.typography.titleLargeEmphasized,
                                                        modifier = Modifier.padding(16.dp),
                                                    )
                                                }

                                                itemsIndexed(
                                                    effectiveSuggestions,
                                                    key = { index, song -> "suggestion_${song.id}_$index" },
                                                    contentType = { _, _ -> "suggestion" },
                                                ) { index, song ->
                                                    PlaylistSuggestionItemRow(
                                                        song = song,
                                                        index = index,
                                                        count = effectiveSuggestions.size,
                                                        offset = displayedSongs.size,
                                                        currentSongId = currentSongIdState,
                                                        isPlaying = isPlayingState,
                                                        isDownloaded = song.id in downloadedSongIds,
                                                        onTrackClick = onTrackClick,
                                                        onTrackMenuClick = onTrackMenuClick,
                                                        onArtistClick = onArtistClick,
                                                        trackContainer = trackContainer,
                                                    )
                                                }
                                            }
                                        }
                                        LazyListScrollbar(
                                            state = activeListState,
                                            modifier = Modifier.align(Alignment.CenterEnd),
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else {
                    Box(modifier = Modifier.fillMaxSize()) {
                        DragScrollableColumn(
                            state = activeListState,
                            // Detaching a desktop scroll handler also cancels its child reorder gesture.
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .onGloballyPositioned { listTop = it.positionInWindow().y }
                                    .desktopArrowScroll(
                                        activeListState,
                                        enabled = reorderableState?.isAnyItemDragging != true,
                                    ),
                            contentPadding = contentPadding,
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            val headerPadding = CollectionHeaderHorizontalPadding
                            val controlsModifier =
                                Modifier.padding(
                                    start = headerPadding,
                                    end = headerPadding,
                                )
                            val itemModifier =
                                Modifier
                                    .widthIn(max = 1400.dp)
                                    .fillMaxWidth()

                            if (header != null) {
                                item(key = "custom_header") {
                                    Box(itemModifier) {
                                        header()
                                    }
                                }
                            }

                            item(key = "header") {
                                Box(itemModifier) {
                                    if (loadingHeader != null) {
                                        loadingHeader()
                                    } else {
                                        PlaylistHeader(
                                            modifier = Modifier.onSecondaryClick(onMoreOptions),
                                            playlist = headerPlaylist,
                                            titleModifier =
                                                Modifier.onGloballyPositioned {
                                                    titleBottom =
                                                        it.positionInWindow().y + it.size.height
                                                },
                                            onEdit = onEdit,
                                            cover = cover,
                                            previewThumbnail = previewThumbnail,
                                            isWide = false,
                                            songCountLabel = songCountLabel,
                                            onAuthorClick = onArtistClick,
                                            fallbackIcon = fallbackIcon,
                                            isMedium = isMedium,
                                            mediumArtworkSize = mediumArtworkSize,
                                            actions =
                                                if (showPlayback || onAddToLibrary != null) {
                                                    {
                                                        PlaylistHeaderActions(
                                                            onPlayAll = onPlayAll,
                                                            onShuffle = onShuffle,
                                                            playLabel = text.play,
                                                            shuffleLabel = text.shuffle,
                                                            showPlayback = showPlayback,
                                                            isFavorite = isFavorite,
                                                            onFavorite = onAddToLibrary,
                                                            buttonHeight = 40.dp,
                                                        )
                                                    }
                                                } else {
                                                    null
                                                },
                                        )
                                    }
                                }
                            }

                            if (loadingHeader == null &&
                                (orderControls != null || aboveOrderControls != null || searchEnabled || isSearchOpen)
                            ) {
                                item(key = "order_controls") {
                                    Box(itemModifier.then(controlsModifier)) {
                                        InlineSearchRow(
                                            onSearchToggle = toggleSearch.takeIf { searchEnabled && !reorderMode && searchOpen == null },
                                            isSearchOpen = isSearchOpen,
                                            query = effectiveQuery,
                                            onQueryChange = updateQuery,
                                            onClose = toggleSearch,
                                            orderControls = orderControls,
                                            aboveOrderControls = aboveOrderControls,
                                        )
                                    }
                                }
                            }

                            if (displayedSongs.isEmpty() && effectiveQuery.isNotBlank() && paginationContent == null) {
                                item(key = "search_no_results") {
                                    Box(itemModifier.padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
                                        Text(
                                            text = stringResource(Res.string.search_no_results),
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            } else if (displayedSongs.isEmpty() && emptyContent != null) {
                                item(key = "empty") {
                                    Box(itemModifier, contentAlignment = Alignment.Center) {
                                        emptyContent()
                                    }
                                }
                            }

                            if (displayedSongs.isNotEmpty()) {
                                itemsIndexed(displayedSongs, key = {
                                    index,
                                    _,
                                    ->
                                    songKeys[index]
                                }, contentType = { _, _ -> "song" }) { index, song ->
                                    PlaylistSongItemRow(
                                        song = song,
                                        index = index,
                                        count = displayedSongs.size,
                                        sourceIndex = displayedIndices?.get(index) ?: index,
                                        itemKey = songKeys[index],
                                        modifier = itemModifier.padding(horizontal = CollectionHeaderHorizontalPadding),
                                        currentSongId = currentSongIdState,
                                        isPlaying = isPlayingState,
                                        isDownloaded = song.id in downloadedSongIds,
                                        onTrackClick = onTrackClick,
                                        onTrackMenuClick = onTrackMenuClick,
                                        onTrackLongClick = onTrackLongClick,
                                        onArtistClick = onArtistClick,
                                        trackContainer = trackContainer,
                                        trackTrailingContent = trackTrailingContent,
                                        reorderableState = reorderableState.takeIf { reorderMode },
                                        onReorderDragStarted = onReorderDragStarted,
                                        onMove = moveAndCommit,
                                        isSelected = isTrackSelected(song),
                                    )
                                }
                            }

                            if (paginationContent != null) {
                                item(key = "pagination") { paginationContent() }
                            }
                            if (effectiveSuggestions.isNotEmpty()) {
                                item(key = "suggestions_title") {
                                    Box(itemModifier) {
                                        Text(
                                            text = text.suggestions,
                                            style = MaterialTheme.typography.titleLargeEmphasized,
                                            modifier = Modifier.padding(16.dp),
                                        )
                                    }
                                }

                                itemsIndexed(
                                    effectiveSuggestions,
                                    key = { index, song -> "suggestion_${song.id}_$index" },
                                    contentType = { _, _ -> "suggestion" },
                                ) { index, song ->
                                    PlaylistSuggestionItemRow(
                                        song = song,
                                        index = index,
                                        count = effectiveSuggestions.size,
                                        offset = displayedSongs.size,
                                        modifier = itemModifier.padding(horizontal = CollectionHeaderHorizontalPadding),
                                        currentSongId = currentSongIdState,
                                        isPlaying = isPlayingState,
                                        isDownloaded = song.id in downloadedSongIds,
                                        onTrackClick = onTrackClick,
                                        onTrackMenuClick = onTrackMenuClick,
                                        onArtistClick = onArtistClick,
                                        trackContainer = trackContainer,
                                    )
                                }
                            }

                            item {
                                Spacer(Modifier.height(32.dp))
                            }
                        }
                        LazyListScrollbar(
                            state = activeListState,
                            modifier = Modifier.align(Alignment.CenterEnd).padding(contentPadding),
                        )
                    }
                }
            }
        }
        if (onAddSong != null) {
            ScrollingAddButton(
                label = stringResource(Res.string.add_a_song),
                onClick = onAddSong,
                state = rememberScrollingFabState(activeListState),
                visible = !isSearchOpen && !selectionActive,
                modifier =
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(
                            end = contentPadding.calculateEndPadding(layoutDirection) + 16.dp,
                            bottom =
                                contentPadding.calculateBottomPadding() + 16.dp,
                        ),
            )
        }
        if (isLoading) {
            ContainedLoadingIndicator(
                Modifier.align(Alignment.TopCenter).padding(top = contentPadding.calculateTopPadding()),
            )
        }
    }
}

@Composable
private fun LazyItemScope.PlaylistSongItem(
    song: SongItem,
    key: String,
    modifier: Modifier,
    container: @Composable (@Composable () -> Unit) -> Unit,
    currentSongId: State<String?>,
    isPlaying: State<Boolean>,
    isDownloaded: Boolean,
    onClick: () -> Unit,
    onShowMenu: () -> Unit,
    onLongClick: (() -> Unit)?,
    onArtistClick: ((String) -> Unit)?,
    trailingContent: (@Composable () -> Unit)?,
    reorderableState: ReorderableLazyListState?,
    onReorderDragStarted: () -> Unit,
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?,
    onMoveToTop: (() -> Unit)? = null,
    onMoveToBottom: (() -> Unit)? = null,
) {
    val playback by rememberItemPlayback(song.id, currentSongId, isPlaying)
    val haptics = rememberMetrolistHaptics()
    if (reorderableState == null) {
        container {
            SongListItem(
                song = song,
                index = null,
                isCurrent = playback.isCurrent,
                isPlaying = playback.isPlaying,
                isDownloaded = isDownloaded,
                onClick = onClick,
                onShowMenu = onShowMenu,
                onLongClick = onLongClick,
                onArtistClick = onArtistClick,
                trailingContent = trailingContent,
                showThumbnail = true,
                clickableMetadata = isDesktop,
            )
        }
    } else {
        val isTv = LocalIsTv.current
        val moveMenuExpanded = remember { mutableStateOf(false) }
        val openMoveMenu = { moveMenuExpanded.value = true }
        ReorderableItem(
            state = reorderableState,
            key = key,
        ) {
            val dragHandleModifier =
                Modifier
                    .draggableHandle(
                        onDragStarted = { _ ->
                            haptics.perform(HapticEvent.DRAG_START)
                            onReorderDragStarted()
                        },
                    )
            container {
                SongListItem(
                    song = song,
                    index = null,
                    isCurrent = playback.isCurrent,
                    isPlaying = playback.isPlaying,
                    isDownloaded = isDownloaded,
                    onClick = onClick,
                    onShowMenu = onShowMenu,
                    onLongClick = if (isTv) openMoveMenu else onLongClick,
                    onArtistClick = onArtistClick,
                    trailingContent = trailingContent,
                    modifier =
                        modifier.then(if (isTv) Modifier.artworkMenuActions(openMoveMenu) else Modifier).longPressDraggableHandle(
                            enabled = !isTv,
                            onDragStarted = { _ ->
                                haptics.perform(HapticEvent.DRAG_START)
                                onReorderDragStarted()
                            },
                        ),
                    showThumbnail = true,
                    clickableMetadata = isDesktop,
                    longClickEnabled = isTv,
                    leadingContent = {
                        ReorderMenu(
                            onMoveUp = onMoveUp,
                            onMoveDown = onMoveDown,
                            onMoveToTop = onMoveToTop,
                            onMoveToBottom = onMoveToBottom,
                            dragHandleModifier = if (isTv) Modifier else dragHandleModifier,
                            expandedState = moveMenuExpanded,
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun LazyItemScope.PlaylistSongItemRow(
    song: SongItem,
    index: Int,
    count: Int,
    sourceIndex: Int,
    itemKey: String,
    modifier: Modifier = Modifier,
    currentSongId: State<String?>,
    isPlaying: State<Boolean>,
    isDownloaded: Boolean,
    onTrackClick: (SongItem, Int) -> Unit,
    onTrackMenuClick: (SongItem, Int) -> Unit,
    onTrackLongClick: ((SongItem) -> Unit)?,
    onArtistClick: ((String) -> Unit)?,
    trackContainer: @Composable (SongItem, Boolean, @Composable () -> Unit) -> Unit,
    trackTrailingContent: (@Composable (SongItem, Int) -> Unit)?,
    reorderableState: ReorderableLazyListState?,
    onReorderDragStarted: () -> Unit,
    onMove: (Int, Int) -> Unit,
    isSelected: Boolean,
) {
    val playback by rememberItemPlayback(song.id, currentSongId, isPlaying)
    PlaylistSongItem(
        song = song,
        key = itemKey,
        modifier = Modifier.fillMaxWidth(),
        container = { content ->
            trackContainer(song, reorderableState != null) {
                GroupedListItem(
                    index = index,
                    count = count,
                    modifier = modifier,
                    isSelected = isSelected,
                    isCurrent = playback.isCurrent,
                    content = content,
                )
            }
        },
        currentSongId = currentSongId,
        isPlaying = isPlaying,
        isDownloaded = isDownloaded,
        onClick = { onTrackClick(song, sourceIndex) },
        onShowMenu = { onTrackMenuClick(song, sourceIndex) },
        onLongClick = onTrackLongClick?.let { { it(song) } },
        onArtistClick = onArtistClick,
        trailingContent = trackTrailingContent?.let { { it(song, sourceIndex) } },
        reorderableState = reorderableState,
        onReorderDragStarted = onReorderDragStarted,
        onMoveUp = if (index > 0) ({ onMove(index, index - 1) }) else null,
        onMoveDown = if (index < count - 1) ({ onMove(index, index + 1) }) else null,
        onMoveToTop = if (index > 0) ({ onMove(index, 0) }) else null,
        onMoveToBottom = if (index < count - 1) ({ onMove(index, count - 1) }) else null,
    )
}

@Composable
private fun PlaylistSuggestionItemRow(
    song: SongItem,
    index: Int,
    count: Int,
    offset: Int,
    modifier: Modifier = Modifier,
    currentSongId: State<String?>,
    isPlaying: State<Boolean>,
    isDownloaded: Boolean,
    onTrackClick: (SongItem, Int) -> Unit,
    onTrackMenuClick: (SongItem, Int) -> Unit,
    onArtistClick: ((String) -> Unit)?,
    trackContainer: @Composable (SongItem, Boolean, @Composable () -> Unit) -> Unit,
) {
    val playback by rememberItemPlayback(song.id, currentSongId, isPlaying)
    trackContainer(song, true) {
        GroupedListItem(index = index, count = count, modifier = modifier, isCurrent = playback.isCurrent) {
            SongListItem(
                song = song,
                index = null,
                isCurrent = playback.isCurrent,
                isPlaying = playback.isPlaying,
                isDownloaded = isDownloaded,
                onClick = { onTrackClick(song, offset + index) },
                onShowMenu = { onTrackMenuClick(song, offset + index) },
                onArtistClick = onArtistClick,
                showThumbnail = true,
                clickableMetadata = isDesktop,
            )
        }
    }
}
