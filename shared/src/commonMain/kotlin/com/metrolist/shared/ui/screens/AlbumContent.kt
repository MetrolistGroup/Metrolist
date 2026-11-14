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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.album_order_original
import com.metrolist.shared.generated.resources.search_no_results
import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.model.PlaylistSongSort
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.SortDirection
import com.metrolist.shared.model.filterForPlaylistSearch
import com.metrolist.shared.model.indexOfPlaylistOccurrence
import com.metrolist.shared.model.sortedForPlaylist
import com.metrolist.shared.ui.components.AlbumHeader
import com.metrolist.shared.ui.components.CollectionHeaderHorizontalPadding
import com.metrolist.shared.ui.components.CollectionHeaderNavigation
import com.metrolist.shared.ui.components.GroupedListItem
import com.metrolist.shared.ui.components.InlineSearchRow
import com.metrolist.shared.ui.components.LazyListScrollbar
import com.metrolist.shared.ui.components.OtherVersionItem
import com.metrolist.shared.ui.components.PlaylistHeaderActions
import com.metrolist.shared.ui.components.PlaylistOrderControls
import com.metrolist.shared.ui.components.SongListItem
import com.metrolist.shared.ui.components.mediumCollectionArtworkSize
import com.metrolist.shared.ui.components.rememberItemPlayback
import com.metrolist.shared.ui.menu.onSecondaryClick
import com.metrolist.shared.ui.navigation.NavigationBackHandler
import com.metrolist.shared.ui.utils.DragScrollableColumn
import com.metrolist.shared.ui.utils.carouselScroll
import com.metrolist.shared.ui.utils.verticalScrollWithDrag
import com.metrolist.shared.utils.isDesktop
import org.jetbrains.compose.resources.stringResource

private val AlbumSortOptions =
    setOf(PlaylistSongSort.ORIGINAL, PlaylistSongSort.TITLE, PlaylistSongSort.ARTIST, PlaylistSongSort.DURATION)

data class AlbumContentText(
    val play: String,
    val shuffle: String,
    val songs: String,
    val otherVersions: String,
    val showMore: String,
    val showLess: String,
)

@Composable
fun AlbumContent(
    album: AlbumItem,
    songs: List<SongItem>,
    text: AlbumContentText,
    paginationContent: (@Composable () -> Unit)? = null,
    onPlayAll: (List<SongItem>) -> Unit,
    onShuffle: (List<SongItem>) -> Unit,
    onMoreOptions: (() -> Unit)? = null,
    isFavorite: Boolean = false,
    onFavorite: (() -> Unit)? = null,
    onArtistClick: (String) -> Unit,
    onTrackClick: (SongItem, List<SongItem>, Int) -> Unit,
    onTrackMenuClick: (SongItem) -> Unit,
    onBackClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    selectionBottomPadding: Dp = contentPadding.calculateBottomPadding(),
    currentSongId: String? = null,
    isPlaying: Boolean = false,
    downloadedSongIds: Set<String> = emptySet(),
    description: String? = null,
    otherVersions: List<AlbumItem> = emptyList(),
    onAlbumClick: (String) -> Unit = {},
    onAlbumArtworkClick: ((String, String) -> Unit)? = null,
    header: @Composable (() -> Unit)? = null,
    trackContainer: @Composable (SongItem, @Composable () -> Unit) -> Unit = { _, content -> content() },
    searchQuery: String = "",
    onSearchQueryChange: ((String) -> Unit)? = null,
    searchAvailable: Boolean = true,
    sortAvailable: Boolean = true,
    onTrackLongClick: ((SongItem) -> Unit)? = null,
    selectionActive: Boolean = false,
    selectionControls: @Composable (() -> Unit)? = null,
    trackTrailingContent: @Composable ((SongItem, Int) -> Unit)? = null,
    isTrackSelected: (SongItem) -> Boolean = { false },
) {
    val currentSongIdState = rememberUpdatedState(currentSongId)
    val isPlayingState = rememberUpdatedState(isPlaying)
    var sort by remember(album.id) { mutableStateOf(PlaylistSongSort.ORIGINAL) }
    var sortDirection by remember(album.id) { mutableStateOf(SortDirection.ASCENDING) }
    var isSearchOpen by remember(album.id, searchAvailable) {
        mutableStateOf(searchAvailable && searchQuery.isNotBlank())
    }
    var internalSearchQuery by remember(album.id) { mutableStateOf("") }
    val effectiveQuery =
        if (searchAvailable) {
            onSearchQueryChange?.let { searchQuery } ?: internalSearchQuery
        } else {
            ""
        }
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
        isSearchOpen = !isSearchOpen
        if (!isSearchOpen) {
            updateQuery("")
        }
    }
    NavigationBackHandler(enabled = isSearchOpen, onBack = toggleSearch)
    val sortedSongs =
        remember(songs, sortAvailable, sort, sortDirection) {
            if (sortAvailable) songs.sortedForPlaylist(sort, sortDirection) else songs
        }
    val displayedSongs =
        remember(sortedSongs, effectiveQuery) {
            sortedSongs.filterForPlaylistSearch(effectiveQuery)
        }
    val orderText =
        playlistOrderControlsText().let { base ->
            base.copy(
                sortLabels =
                    base.sortLabels.filterKeys { it in AlbumSortOptions } +
                        (PlaylistSongSort.ORIGINAL to stringResource(Res.string.album_order_original)),
            )
        }

    val showPlayback = displayedSongs.isNotEmpty()
    var titleBottom by remember(album.id) { mutableFloatStateOf(Float.POSITIVE_INFINITY) }
    var listTop by remember { mutableFloatStateOf(0f) }
    val headerIndex = if (header != null) 1 else 0
    val showCollapsedTitle by remember(album.id, activeListState, headerIndex) {
        derivedStateOf { activeListState.firstVisibleItemIndex > headerIndex || titleBottom <= listTop }
    }

    Box(modifier.fillMaxSize()) {
        CollectionHeaderNavigation(
            if (isSearchOpen) toggleSearch else onBackClick,
            onMoreOptions,
            title = {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    AnimatedVisibility(
                        visible = maxWidth < 960.dp && showCollapsedTitle && !isSearchOpen && !selectionActive,
                        enter = fadeIn(MaterialTheme.motionScheme.fastEffectsSpec()),
                        exit = fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()),
                    ) {
                        Text(
                            text = album.title,
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
                LaunchedEffect(isSearchOpen, isWide) {
                    if (isSearchOpen && !isWide) {
                        activeListState.animateScrollToItem(listOfNotNull(header).size + 1)
                    }
                }
                val mediumArtworkSize =
                    if (isDesktop && isMedium) {
                        mediumCollectionArtworkSize(
                            maxWidth,
                            maxHeight - contentPadding.calculateTopPadding() - contentPadding.calculateBottomPadding(),
                        )
                    } else {
                        176.dp
                    }
                val playbackActions: @Composable (() -> Unit)? =
                    if (showPlayback || onFavorite != null) {
                        {
                            PlaylistHeaderActions(
                                onPlayAll = { onPlayAll(displayedSongs) },
                                onShuffle = { onShuffle(displayedSongs) },
                                playLabel = text.play,
                                shuffleLabel = text.shuffle,
                                buttonHeight = if (isWide) 48.dp else 40.dp,
                                showPlayback = showPlayback,
                                isFavorite = isFavorite,
                                onFavorite = onFavorite,
                            )
                        }
                    } else {
                        null
                    }

                if (isWide) {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .padding(top = contentPadding.calculateTopPadding()),
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

                                AlbumHeader(
                                    modifier = Modifier.onSecondaryClick(onMoreOptions),
                                    album = album,
                                    songs = songs,
                                    isWide = true,
                                    onArtistClick = onArtistClick,
                                    description = description,
                                    showMoreLabel = text.showMore,
                                    showLessLabel = text.showLess,
                                    actions = playbackActions,
                                )
                                Spacer(Modifier.height(contentPadding.calculateBottomPadding()))
                            }

                            Column(
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                            ) {
                                if (sortAvailable || searchAvailable) {
                                    InlineSearchRow(
                                        isSearchOpen = isSearchOpen,
                                        query = effectiveQuery,
                                        onQueryChange = updateQuery,
                                        onClose = toggleSearch,
                                        onSearchToggle = toggleSearch.takeIf { searchAvailable },
                                    ) {
                                        if (sortAvailable) {
                                            PlaylistOrderControls(
                                                text = orderText,
                                                sort = sort,
                                                sortDirection = sortDirection,
                                                reorderMode = false,
                                                canReorder = false,
                                                onSortChange = { sort = it },
                                                onSortDirectionChange = { sortDirection = it },
                                                onReorderModeChange = {},
                                            )
                                        }
                                    }
                                }
                                Box(
                                    modifier = Modifier.weight(1f).fillMaxWidth(),
                                ) {
                                    DragScrollableColumn(
                                        state = activeListState,
                                        modifier = Modifier.fillMaxSize().padding(end = 16.dp),
                                        contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding()),
                                    ) {
                                        if (displayedSongs.isEmpty() && effectiveQuery.isNotBlank()) {
                                            item(key = "search_no_results") {
                                                Box(
                                                    modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                                                    contentAlignment = Alignment.Center,
                                                ) {
                                                    Text(
                                                        text = stringResource(Res.string.search_no_results),
                                                        style = MaterialTheme.typography.bodyLarge,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    )
                                                }
                                            }
                                        } else {
                                            itemsIndexed(displayedSongs, key = {
                                                index,
                                                song,
                                                ->
                                                "${song.id}_$index"
                                            }, contentType = { _, _ -> "song" }) { index, song ->
                                                val playback by rememberItemPlayback(song.id, currentSongIdState, isPlayingState)
                                                trackContainer(song) {
                                                    GroupedListItem(
                                                        index = index,
                                                        count = displayedSongs.size,
                                                        isSelected = isTrackSelected(song),
                                                        isCurrent = playback.isCurrent,
                                                    ) {
                                                        SongListItem(
                                                            song = song,
                                                            index = index,
                                                            isCurrent = playback.isCurrent,
                                                            isPlaying = playback.isPlaying,
                                                            isDownloaded = song.id in downloadedSongIds,
                                                            onClick = {
                                                                onTrackClick(
                                                                    song,
                                                                    sortedSongs,
                                                                    sortedSongs.indexOfPlaylistOccurrence(song),
                                                                )
                                                            },
                                                            onShowMenu = { onTrackMenuClick(song) },
                                                            onArtistClick = onArtistClick,
                                                            onLongClick = onTrackLongClick?.let { { it(song) } },
                                                            trailingContent = trackTrailingContent?.let { { it(song, index) } },
                                                            showThumbnail = false,
                                                            showAlbum = false,
                                                            clickableMetadata = isDesktop,
                                                        )
                                                    }
                                                }
                                            }
                                        }

                                        if (paginationContent != null) {
                                            item(key = "pagination") { paginationContent() }
                                        }
                                        if (otherVersions.isNotEmpty() && effectiveQuery.isBlank()) {
                                            item(key = "other_versions_title") {
                                                Text(
                                                    text = text.otherVersions,
                                                    style = MaterialTheme.typography.titleLargeEmphasized,
                                                    modifier = Modifier.padding(16.dp),
                                                )
                                            }

                                            item(key = "other_versions_list") {
                                                val otherRowState = rememberLazyListState()
                                                LazyRow(
                                                    state = otherRowState,
                                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                                                    modifier = Modifier.fillMaxWidth().carouselScroll(otherRowState),
                                                ) {
                                                    items(otherVersions, key = { it.id }, contentType = { "album" }) { other ->
                                                        OtherVersionItem(
                                                            album = other,
                                                            sourceAlbumId = album.id,
                                                            onClick = { token ->
                                                                onAlbumArtworkClick?.invoke(other.id, token) ?: onAlbumClick(other.id)
                                                            },
                                                        )
                                                    }
                                                }
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
                } else {
                    Box(modifier = Modifier.fillMaxSize()) {
                        DragScrollableColumn(
                            state = activeListState,
                            modifier = Modifier.fillMaxSize().onGloballyPositioned { listTop = it.positionInWindow().y },
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
                                    AlbumHeader(
                                        modifier = Modifier.onSecondaryClick(onMoreOptions),
                                        titleModifier =
                                            Modifier.onGloballyPositioned {
                                                titleBottom = it.positionInWindow().y + it.size.height
                                            },
                                        album = album,
                                        songs = songs,
                                        isWide = false,
                                        onArtistClick = onArtistClick,
                                        description = description,
                                        showMoreLabel = text.showMore,
                                        showLessLabel = text.showLess,
                                        isMedium = isMedium,
                                        mediumArtworkSize = mediumArtworkSize,
                                        actions = playbackActions,
                                    )
                                }
                            }

                            if (sortAvailable || searchAvailable) {
                                item(key = "order_controls") {
                                    Box(itemModifier.then(controlsModifier)) {
                                        InlineSearchRow(
                                            isSearchOpen = isSearchOpen,
                                            query = effectiveQuery,
                                            onQueryChange = updateQuery,
                                            onClose = toggleSearch,
                                            onSearchToggle = toggleSearch.takeIf { searchAvailable },
                                        ) {
                                            if (sortAvailable) {
                                                PlaylistOrderControls(
                                                    text = orderText,
                                                    sort = sort,
                                                    sortDirection = sortDirection,
                                                    reorderMode = false,
                                                    canReorder = false,
                                                    onSortChange = { sort = it },
                                                    onSortDirectionChange = { sortDirection = it },
                                                    onReorderModeChange = {},
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            if (displayedSongs.isEmpty() && effectiveQuery.isNotBlank()) {
                                item(key = "search_no_results") {
                                    Box(
                                        modifier = itemModifier.padding(vertical = 32.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            text = stringResource(Res.string.search_no_results),
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            } else {
                                itemsIndexed(displayedSongs, key = {
                                    index,
                                    song,
                                    ->
                                    "${song.id}_$index"
                                }, contentType = { _, _ -> "song" }) { index, song ->
                                    val playback by rememberItemPlayback(song.id, currentSongIdState, isPlayingState)
                                    trackContainer(song) {
                                        GroupedListItem(
                                            index = index,
                                            count = displayedSongs.size,
                                            modifier = itemModifier.padding(horizontal = CollectionHeaderHorizontalPadding),
                                            isCurrent = playback.isCurrent,
                                        ) {
                                            SongListItem(
                                                song = song,
                                                index = index,
                                                isCurrent = playback.isCurrent,
                                                isPlaying = playback.isPlaying,
                                                isDownloaded = song.id in downloadedSongIds,
                                                onClick = { onTrackClick(song, sortedSongs, sortedSongs.indexOfPlaylistOccurrence(song)) },
                                                onShowMenu = { onTrackMenuClick(song) },
                                                onArtistClick = onArtistClick,
                                                onLongClick = onTrackLongClick?.let { { it(song) } },
                                                trailingContent = trackTrailingContent?.let { { it(song, index) } },
                                                showThumbnail = false,
                                                showAlbum = false,
                                                clickableMetadata = isDesktop,
                                            )
                                        }
                                    }
                                }
                            }

                            if (paginationContent != null) {
                                item(key = "pagination") { paginationContent() }
                            }
                            if (otherVersions.isNotEmpty() && effectiveQuery.isBlank()) {
                                item(key = "other_versions_title") {
                                    Box(itemModifier) {
                                        Text(
                                            text = text.otherVersions,
                                            style = MaterialTheme.typography.titleLargeEmphasized,
                                            modifier = Modifier.padding(16.dp),
                                        )
                                    }
                                }

                                item(key = "other_versions_list") {
                                    Box(itemModifier) {
                                        val otherRowState = rememberLazyListState()
                                        LazyRow(
                                            state = otherRowState,
                                            contentPadding = PaddingValues(horizontal = 16.dp),
                                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                                            modifier = Modifier.fillMaxWidth().carouselScroll(otherRowState),
                                        ) {
                                            items(otherVersions, key = { it.id }, contentType = { "album" }) { other ->
                                                OtherVersionItem(
                                                    album = other,
                                                    sourceAlbumId = album.id,
                                                    onClick = { token ->
                                                        onAlbumArtworkClick?.invoke(other.id, token) ?: onAlbumClick(other.id)
                                                    },
                                                )
                                            }
                                        }
                                    }
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
    }
}
