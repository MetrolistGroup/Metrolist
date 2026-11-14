/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.shared.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.explicit
import com.metrolist.shared.generated.resources.loading
import com.metrolist.shared.generated.resources.more_options
import com.metrolist.shared.generated.resources.retry
import com.metrolist.shared.generated.resources.saved
import com.metrolist.shared.generated.resources.search_load_more_failed
import com.metrolist.shared.generated.resources.search_online_failed
import com.metrolist.shared.generated.resources.song_type
import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.model.ArtistItem
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.EpisodeItem
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.model.PodcastItem
import com.metrolist.shared.model.SearchFilter
import com.metrolist.shared.model.SearchSuggestions
import com.metrolist.shared.model.SearchSummary
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.YTItem
import com.metrolist.shared.model.displayThumbnailUrl
import com.metrolist.shared.model.filterAvailableSearchFilters
import com.metrolist.shared.model.isVideoShelf
import com.metrolist.shared.model.thumbnailAspectRatio
import com.metrolist.shared.settings.ArtistNameAliases
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.settings.displayName
import com.metrolist.shared.settings.displayTitle
import com.metrolist.shared.ui.components.AlbumPlayButton
import com.metrolist.shared.ui.components.AlertDialog
import com.metrolist.shared.ui.components.ArtistSubtitle
import com.metrolist.shared.ui.components.ChipShelf
import com.metrolist.shared.ui.components.CollectionPreviews
import com.metrolist.shared.ui.components.ContainedLoadingIndicator
import com.metrolist.shared.ui.components.FittingText
import com.metrolist.shared.ui.components.PlaylistCoverImage
import com.metrolist.shared.ui.components.PopupContent
import com.metrolist.shared.ui.components.PopupVisibility
import com.metrolist.shared.ui.components.SongThumbnailPlayingIndicator
import com.metrolist.shared.ui.components.VideoShelf
import com.metrolist.shared.ui.components.YTListItem
import com.metrolist.shared.ui.components.focusOutline
import com.metrolist.shared.ui.components.images.AsyncImage
import com.metrolist.shared.ui.components.squareArtworkSlot
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.icons.SongStatusIcons
import com.metrolist.shared.ui.menu.onSecondaryClick
import com.metrolist.shared.ui.navigation.acceptsInput
import com.metrolist.shared.ui.navigation.blockInput
import com.metrolist.shared.ui.navigation.navigationPopupSource
import com.metrolist.shared.ui.navigation.rememberNavigationMotion
import com.metrolist.shared.ui.theme.ComponentShapes
import com.metrolist.shared.ui.theme.gridCellInset
import com.metrolist.shared.ui.theme.gridCellShape
import com.metrolist.shared.ui.theme.uiEnhanced
import com.metrolist.shared.ui.tv.RequestTvFocus
import com.metrolist.shared.ui.tv.tvFocusHighlight
import com.metrolist.shared.ui.utils.DragScrollableColumn
import com.metrolist.shared.ui.utils.artistArtworkShape
import com.metrolist.shared.ui.utils.carouselScroll
import com.metrolist.shared.ui.utils.desktopArrowScroll
import com.metrolist.shared.utils.formatDuration
import com.metrolist.shared.utils.monthlyAudienceText
import com.metrolist.shared.utils.withoutPlaybackCountLabel
import com.metrolist.shared.viewmodels.SearchPresentation
import com.metrolist.shared.viewmodels.SearchViewModel.SearchUiState
import com.metrolist.shared.viewmodels.searchPresentation
import com.valentinilk.shimmer.shimmer
import kotlinx.coroutines.flow.distinctUntilChanged
import org.jetbrains.compose.resources.stringResource

data class SearchContentText(
    val search: String,
    val suggestions: String,
    val recommended: String,
    val recentSearches: String,
    val recentFromSearches: String,
    val clear: String,
    val clearHistory: String,
    val useSearchText: String,
    val startTypingToSearch: String,
    val noResultsFound: String,
    val filterAll: String,
    val filterSongs: String,
    val filterAlbums: String,
    val filterArtists: String,
    val filterPlaylists: String,
    val filterVideos: String,
    val filterPodcasts: String,
    val filterEpisodes: String,
    val browse: String,
    val verifiedArtists: String,
    val filterFeaturedPlaylists: String,
    val filterCommunityPlaylists: String,
    val filterProfiles: String,
    val topResults: String,
    val moreOptions: String,
    val clearSection: @Composable (String) -> String,
    val removeRecentSearchQuestion: String,
    val removeRecentSearchDescription: @Composable (String) -> String,
    val removeFromRecentQuestion: String,
    val removeRecentItemDescription: @Composable (String) -> String,
    val clearRecentSearchesQuestion: String,
    val deleteAllRecentSearchesQuestion: String,
    val clearRecentFromSearchesQuestion: String,
    val deleteAllRecentFromSearchesQuestion: String,
    val removeRecentSearchAction: String,
    val removeRecentSearchItemAction: String,
    val delete: String,
    val cancel: String,
    val useSuggestion: String,
    val shuffle: String,
    val mix: String,
    val track: String,
    val album: String,
    val artist: String,
    val playlist: String,
    val podcast: String,
    val episode: String,
    val monthlyAudienceShort: String,
    val play: String,
    val save: String,
)

@Composable
fun SearchContent(
    uiState: SearchUiState,
    text: SearchContentText,
    onItemClick: (YTItem) -> Unit,
    onVideoItemClick: (YTItem) -> Unit = onItemClick,
    onItemLongClick: (YTItem) -> Unit,
    onSongSave: (SongItem) -> Unit,
    onHistoryItemClick: (String) -> Unit,
    onHistoryItemInsert: (String) -> Unit,
    onClearHistory: () -> Unit,
    onFilterSelected: (SearchFilter?) -> Unit,
    onLoadMore: () -> Unit,
    onRetryLoadMore: () -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    onRemoveHistoryItem: (String) -> Unit = {},
    onRemoveRecentItem: (YTItem) -> Unit = {},
    onClearRecentItems: () -> Unit = {},
    currentSongId: String? = null,
    isPlaying: Boolean = false,
    onArtistClick: ((String) -> Unit)? = null,
    onAlbumClick: ((String) -> Unit)? = null,
    onShuffleClick: (YTItem) -> Unit = {},
    onMixClick: (YTItem) -> Unit = {},
    itemContainer: @Composable (YTItem, @Composable () -> Unit) -> Unit = { _, content -> content() },
    onAlbumPlay: ((AlbumItem) -> Unit)? = null,
) {
    val query = uiState.query
    val isQuerySubmitted = uiState.isQuerySubmitted
    val searchSuggestions = uiState.searchSuggestions

    val motion = rememberNavigationMotion()
    val contentState = searchPresentation(query, isQuerySubmitted)

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val isWide = maxWidth >= 960.dp

        AnimatedContent(
            targetState = contentState,
            modifier = Modifier.fillMaxSize(),
            transitionSpec = { motion.transition() },
            label = "Search content transition",
        ) { state ->
            Box(modifier = Modifier.fillMaxSize().blockInput(!transition.acceptsInput(EnterExitState.Visible))) {
                when (state) {
                    SearchPresentation.SUGGESTIONS -> {
                        SearchSuggestionsList(
                            query = query,
                            suggestions = searchSuggestions,
                            onSuggestionClick = onHistoryItemClick,
                            onSuggestionInsert = onHistoryItemInsert,
                            onItemClick = onItemClick,
                            text = text,
                            bottomContentPadding = contentPadding.calculateBottomPadding(),
                            isWide = isWide,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }

                    SearchPresentation.IDLE -> {
                        SearchEmptyState(
                            recentSearchItems = if (Settings.shared.hideRecentSearches) emptyList() else uiState.recentSearchItems,
                            searchHistory = uiState.searchHistory,
                            onRecentItemClick = onItemClick,
                            onVideoItemClick = onVideoItemClick,
                            onHistoryItemClick = onHistoryItemClick,
                            onHistoryItemInsert = onHistoryItemInsert,
                            onRemoveHistoryItem = onRemoveHistoryItem,
                            onClearHistory = onClearHistory,
                            onRemoveRecentItem = onRemoveRecentItem,
                            onClearRecentItems = onClearRecentItems,
                            text = text,
                            bottomContentPadding = contentPadding.calculateBottomPadding(),
                            isWide = isWide,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }

                    SearchPresentation.RESULTS -> {
                        SearchResultsList(
                            uiState = uiState,
                            onFilterSelected = onFilterSelected,
                            onItemClick = if (uiState.selectedFilter == SearchFilter.VIDEO) onVideoItemClick else onItemClick,
                            onVideoItemClick = onVideoItemClick,
                            onItemLongClick = onItemLongClick,
                            onSongSave = onSongSave,
                            onLoadMore = onLoadMore,
                            onRetryLoadMore = onRetryLoadMore,
                            currentSongId = currentSongId,
                            isPlaying = isPlaying,
                            text = text,
                            listState = listState,
                            bottomContentPadding = contentPadding.calculateBottomPadding(),
                            isWide = isWide,
                            onArtistClick = onArtistClick,
                            onAlbumClick = onAlbumClick,
                            onShuffleClick = onShuffleClick,
                            onMixClick = onMixClick,
                            itemContainer = itemContainer,
                            onAlbumPlay = onAlbumPlay,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchEnterMotion(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    AnimatedVisibility(
        visible = true,
        enter = fadeIn(spring(stiffness = Spring.StiffnessMediumLow)),
        exit = fadeOut(spring(stiffness = Spring.StiffnessMedium)),
        modifier = modifier,
    ) {
        content()
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SearchEmptyState(
    recentSearchItems: List<YTItem>,
    searchHistory: List<String>,
    onRecentItemClick: (YTItem) -> Unit,
    onVideoItemClick: (YTItem) -> Unit,
    onHistoryItemClick: (String) -> Unit,
    onHistoryItemInsert: (String) -> Unit,
    onRemoveHistoryItem: (String) -> Unit,
    onClearHistory: () -> Unit,
    onRemoveRecentItem: (YTItem) -> Unit,
    onClearRecentItems: () -> Unit,
    text: SearchContentText,
    bottomContentPadding: Dp = 0.dp,
    isWide: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var historyItemToRemove by remember { mutableStateOf<String?>(null) }
    var recentItemToRemove by remember { mutableStateOf<YTItem?>(null) }
    var confirmClearHistory by remember { mutableStateOf(false) }
    var confirmClearRecent by remember { mutableStateOf(false) }

    PopupContent(historyItemToRemove) { item ->
        ConfirmDeleteDialog(
            title = text.removeRecentSearchQuestion,
            text = text.removeRecentSearchDescription(item),
            textData = text,
            onConfirm = {
                onRemoveHistoryItem(item)
                historyItemToRemove = null
            },
            onDismiss = { historyItemToRemove = null },
        )
    }

    PopupContent(recentItemToRemove) { item ->
        ConfirmDeleteDialog(
            title = text.removeFromRecentQuestion,
            text = text.removeRecentItemDescription(item.displayTitle),
            textData = text,
            onConfirm = {
                onRemoveRecentItem(item)
                recentItemToRemove = null
            },
            onDismiss = { recentItemToRemove = null },
        )
    }

    PopupVisibility(visible = confirmClearHistory) {
        ConfirmDeleteDialog(
            title = text.clearRecentSearchesQuestion,
            text = text.deleteAllRecentSearchesQuestion,
            textData = text,
            onConfirm = {
                onClearHistory()
                confirmClearHistory = false
            },
            onDismiss = { confirmClearHistory = false },
        )
    }

    PopupVisibility(visible = confirmClearRecent) {
        ConfirmDeleteDialog(
            title = text.clearRecentFromSearchesQuestion,
            text = text.deleteAllRecentFromSearchesQuestion,
            textData = text,
            onConfirm = {
                onClearRecentItems()
                confirmClearRecent = false
            },
            onDismiss = { confirmClearRecent = false },
        )
    }

    val listState = rememberLazyListState()
    DragScrollableColumn(
        state = listState,
        modifier = modifier.fillMaxWidth().desktopArrowScroll(listState),
        contentPadding = PaddingValues(top = 8.dp, bottom = 8.dp + bottomContentPadding),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        if (recentSearchItems.isNotEmpty()) {
            item {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                    Column(Modifier.widthIn(max = 1400.dp).fillMaxWidth()) {
                        RecentSectionTitle(
                            title = text.recentFromSearches,
                            onClearClick = { confirmClearRecent = true },
                            text = text,
                        )
                        Spacer(Modifier.height(12.dp))
                        if (recentSearchItems.isVideoShelf()) {
                            VideoShelf(
                                items = recentSearchItems.take(10),
                                onItemClick = onVideoItemClick,
                                onItemLongClick = { recentItemToRemove = it },
                                menuActionLabel = text.removeRecentSearchItemAction,
                                isWide = isWide,
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                modifier = Modifier.focusGroup().focusRestorer(),
                            )
                        } else {
                            val recentSearchState = rememberLazyListState()
                            LazyRow(
                                state = recentSearchState,
                                modifier = Modifier.focusGroup().focusRestorer().carouselScroll(recentSearchState),
                                horizontalArrangement = Arrangement.spacedBy(if (isWide) 16.dp else 12.dp),
                                contentPadding = PaddingValues(horizontal = 16.dp),
                            ) {
                                items(recentSearchItems.take(10), key = { it.id }) { item ->
                                    RecentSearchItemCard(
                                        item = item,
                                        text = text,
                                        onClick = { onRecentItemClick(item) },
                                        onLongClickLabel = text.removeRecentSearchItemAction,
                                        onLongClick = { recentItemToRemove = item },
                                        isWide = isWide,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        if (searchHistory.isNotEmpty()) {
            item {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                    Column(Modifier.widthIn(max = 1400.dp).fillMaxWidth()) {
                        RecentSectionTitle(
                            title = text.recentSearches,
                            onClearClick = { confirmClearHistory = true },
                            text = text,
                        )
                        Spacer(Modifier.height(12.dp))
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            searchHistory.forEach { historyItem ->
                                Row(
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .heightIn(min = 60.dp)
                                            .clip(RoundedCornerShape(ComponentShapes.SearchItemCornerRadius))
                                            .combinedClickable(
                                                onClick = { onHistoryItemClick(historyItem) },
                                                onLongClickLabel = text.removeRecentSearchAction,
                                                onLongClick = { historyItemToRemove = historyItem },
                                            ).padding(horizontal = 16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                                ) {
                                    Icon(
                                        MetrolistIcons.Outlined.History,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(24.dp),
                                    )
                                    Text(
                                        text = historyItem,
                                        style = MaterialTheme.typography.titleMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    IconButton(
                                        onClick = { onHistoryItemInsert(historyItem) },
                                        shapes = IconButtonDefaults.shapes(),
                                    ) {
                                        Icon(
                                            imageVector = MetrolistIcons.AutoMirrored.Filled.ArrowBack,
                                            contentDescription = text.useSearchText,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(22.dp).rotate(45f),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } else if (recentSearchItems.isEmpty()) {
            item {
                Box(
                    modifier = Modifier.fillParentMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = text.startTypingToSearch,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun RecentSectionTitle(
    title: String,
    onClearClick: () -> Unit,
    text: SearchContentText,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMediumEmphasized,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onClearClick, shapes = IconButtonDefaults.shapes()) {
            Icon(
                imageVector = MetrolistIcons.Default.Close,
                contentDescription = text.clearSection(title),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ConfirmDeleteDialog(
    title: String,
    text: String,
    textData: SearchContentText,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.tvFocusHighlight(CircleShape), shapes = ButtonDefaults.shapes()) {
                Text(textData.delete)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.tvFocusHighlight(CircleShape), shapes = ButtonDefaults.shapes()) {
                Text(textData.cancel)
            }
        },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecentSearchItemCard(
    item: YTItem,
    text: SearchContentText,
    onClick: () -> Unit,
    onLongClickLabel: String,
    onLongClick: () -> Unit,
    isWide: Boolean = false,
) {
    val cardSize = if (isWide) 180.dp else 130.dp
    val inset = gridCellInset()
    Column(
        modifier =
            Modifier
                .width(cardSize)
                .clip(gridCellShape())
                .combinedClickable(
                    onClick = onClick,
                    onLongClickLabel = onLongClickLabel,
                    onLongClick = onLongClick,
                ).padding(inset),
    ) {
        Box(
            modifier =
                Modifier
                    .squareArtworkSlot(cardSize - inset * 2, item.thumbnailAspectRatio)
                    .clip(ComponentShapes.ThumbnailShape)
                    .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            if (item is PlaylistItem) {
                PlaylistCoverImage(item, Modifier.fillMaxSize())
            } else if (item.thumbnail != null) {
                AsyncImage(
                    url = item.displayThumbnailUrl,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = item.displayTitle,
            style = if (isWide) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurface,
        )
        val subtitle = searchItemSubtitle(item, text)
        if (subtitle.isNotEmpty()) {
            FittingText(
                text = subtitle,
                compactText = if (item is SongItem) subtitle.withoutPlaybackCountLabel() else subtitle,
                style = if (isWide) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SearchSuggestionsList(
    query: String,
    suggestions: SearchSuggestions?,
    onSuggestionClick: (String) -> Unit,
    onSuggestionInsert: (String) -> Unit,
    onItemClick: (YTItem) -> Unit,
    text: SearchContentText,
    bottomContentPadding: Dp = 0.dp,
    isWide: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val querySuggestions =
        suggestions
            ?.queries
            .orEmpty()
            .ifEmpty { listOf(query) }
            .take(5)
    val recommendedItems = suggestions?.recommendedItems.orEmpty().take(6)

    val listState = rememberLazyListState()
    DragScrollableColumn(
        state = listState,
        modifier =
            modifier
                .fillMaxWidth()
                .desktopArrowScroll(listState)
                .focusGroup()
                .focusRestorer(),
        contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp + bottomContentPadding),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (querySuggestions.isNotEmpty()) {
            item {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                    Text(
                        text = text.suggestions,
                        style = MaterialTheme.typography.headlineMediumEmphasized,
                        color = MaterialTheme.colorScheme.primary,
                        modifier =
                            Modifier
                                .widthIn(
                                    max = 1400.dp,
                                ).fillMaxWidth()
                                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 10.dp),
                    )
                }
            }
            itemsIndexed(querySuggestions, key = { index, suggestion -> "${suggestion}_$index" }) { index, suggestion ->
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                    Box(Modifier.widthIn(max = 1400.dp).fillMaxWidth()) {
                        SearchSuggestionRow(
                            suggestion = suggestion,
                            text = text,
                            shape = searchListItemShape(index, querySuggestions.size),
                            onClick = { onSuggestionClick(suggestion) },
                            onInsertClick = { onSuggestionInsert(suggestion) },
                        )
                    }
                }
            }
        }

        if (recommendedItems.isNotEmpty()) {
            item {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                    Text(
                        text = text.recommended,
                        style = MaterialTheme.typography.headlineMediumEmphasized,
                        color = MaterialTheme.colorScheme.primary,
                        modifier =
                            Modifier
                                .widthIn(
                                    max = 1400.dp,
                                ).fillMaxWidth()
                                .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 10.dp),
                    )
                }
            }
            itemsIndexed(recommendedItems, key = { index, item -> "${item.id}_$index" }) { index, item ->
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                    Box(Modifier.widthIn(max = 1400.dp).fillMaxWidth()) {
                        SearchRecommendationRow(
                            item = item,
                            index = index,
                            text = text,
                            shape = searchListItemShape(index, recommendedItems.size),
                            onClick = { onItemClick(item) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchSuggestionRow(
    suggestion: String,
    text: SearchContentText,
    shape: RoundedCornerShape,
    onClick: () -> Unit,
    onInsertClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .focusOutline(shape)
                    .onPreviewKeyEvent { event ->
                        if (isFocused &&
                            event.type == KeyEventType.KeyUp &&
                            (event.key == Key.Enter || event.key == Key.DirectionCenter)
                        ) {
                            onClick()
                            true
                        } else {
                            false
                        }
                    }.clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = onClick,
                    ).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(100.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.size(32.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        MetrolistIcons.Outlined.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Text(
                text = suggestion,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = onInsertClick,
                shapes = IconButtonDefaults.shapes(),
            ) {
                Icon(
                    imageVector = MetrolistIcons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = text.useSuggestion,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp).rotate(45f),
                )
            }
        }
    }
}

@Composable
private fun SearchRecommendationRow(
    item: YTItem,
    index: Int,
    text: SearchContentText,
    shape: RoundedCornerShape,
    onClick: () -> Unit,
) {
    val artworkShape =
        when (item) {
            is ArtistItem -> artistArtworkShape(item.id)
            is PodcastItem -> RoundedCornerShape(100.dp)
            else -> ComponentShapes.ThumbnailShape
        }

    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 72.dp)
                    .clickable(onClick = onClick)
                    .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier =
                    Modifier
                        .squareArtworkSlot(52.dp, item.thumbnailAspectRatio)
                        .clipToBounds()
                        .clip(artworkShape)
                        .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                if (item is PlaylistItem) {
                    PlaylistCoverImage(item, Modifier.fillMaxSize())
                } else if (item.thumbnail != null) {
                    AsyncImage(
                        url = item.displayThumbnailUrl,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = item.displayTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val subtitle = quickSearchItemSubtitle(item, text)
                FittingText(
                    text = subtitle,
                    compactText = if (item is SongItem) subtitle.withoutPlaybackCountLabel() else subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun searchListItemShape(
    index: Int,
    itemCount: Int,
): RoundedCornerShape =
    when {
        itemCount == 1 -> {
            RoundedCornerShape(ComponentShapes.SearchExteriorCornerRadius)
        }

        index == 0 -> {
            RoundedCornerShape(
                topStart = ComponentShapes.SearchExteriorCornerRadius,
                topEnd = ComponentShapes.SearchExteriorCornerRadius,
                bottomStart = ComponentShapes.SearchItemCornerRadius,
                bottomEnd = ComponentShapes.SearchItemCornerRadius,
            )
        }

        index == itemCount - 1 -> {
            RoundedCornerShape(
                topStart = ComponentShapes.SearchItemCornerRadius,
                topEnd = ComponentShapes.SearchItemCornerRadius,
                bottomStart = ComponentShapes.SearchExteriorCornerRadius,
                bottomEnd = ComponentShapes.SearchExteriorCornerRadius,
            )
        }

        else -> {
            RoundedCornerShape(ComponentShapes.SearchItemCornerRadius)
        }
    }

@Composable
private fun SearchResultsList(
    uiState: SearchUiState,
    onFilterSelected: (SearchFilter?) -> Unit,
    onItemClick: (YTItem) -> Unit,
    onVideoItemClick: (YTItem) -> Unit = onItemClick,
    onItemLongClick: (YTItem) -> Unit,
    onSongSave: (SongItem) -> Unit,
    onLoadMore: () -> Unit,
    onRetryLoadMore: () -> Unit,
    currentSongId: String?,
    isPlaying: Boolean,
    text: SearchContentText,
    listState: LazyListState,
    modifier: Modifier = Modifier,
    bottomContentPadding: Dp = 0.dp,
    isWide: Boolean = false,
    onArtistClick: ((String) -> Unit)? = null,
    onAlbumClick: ((String) -> Unit)? = null,
    onShuffleClick: (YTItem) -> Unit = {},
    onMixClick: (YTItem) -> Unit = {},
    itemContainer: @Composable (YTItem, @Composable () -> Unit) -> Unit = { _, content -> content() },
    onAlbumPlay: ((AlbumItem) -> Unit)? = null,
) {
    val selectedFilter = uiState.selectedFilter
    val isSearching = uiState.isSearching
    val isLoadingMore = uiState.isLoadingMore
    val searchSummary = uiState.searchSummary
    val filteredResults = uiState.filteredResults
    val hasSearched = uiState.hasSearched
    val resultsFocusRequester = remember(uiState.query) { FocusRequester() }
    val hasVisibleItems by remember(listState) { derivedStateOf { listState.layoutInfo.visibleItemsInfo.isNotEmpty() } }
    RequestTvFocus(
        resultsFocusRequester,
        enabled = hasSearched && !isSearching && hasVisibleItems,
    )

    val summaries = searchSummary?.summaries.orEmpty()
    val filteredItems = filteredResults?.items.orEmpty()
    val showingFilteredResults = selectedFilter != null && filteredResults != null
    val allResultsEmpty = summaries.all { it.items.isEmpty() }
    val showNoResults =
        hasSearched && !isSearching && !uiState.searchFailed &&
            if (showingFilteredResults) filteredItems.isEmpty() && filteredResults.continuation == null else allResultsEmpty

    LaunchedEffect(selectedFilter, filteredResults?.continuation, filteredItems.size, isLoadingMore, uiState.loadMoreFailed) {
        snapshotFlow {
            listState.layoutInfo.totalItemsCount > 0 && !listState.canScrollForward
        }.distinctUntilChanged().collect { atEnd ->
            if (atEnd && showingFilteredResults && filteredResults.continuation != null && !isLoadingMore && !uiState.loadMoreFailed) {
                onLoadMore()
            }
        }
    }

    DragScrollableColumn(
        state = listState,
        modifier =
            modifier
                .fillMaxWidth()
                .focusRequester(resultsFocusRequester)
                .focusGroup()
                .focusRestorer()
                .desktopArrowScroll(listState),
        contentPadding = PaddingValues(bottom = 24.dp + bottomContentPadding),
    ) {
        item(key = "search_filter_top_safe_space") {
            Spacer(Modifier.height(12.dp))
        }

        item(key = "search_filter_chips") {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                Box(Modifier.widthIn(max = 1400.dp).fillMaxWidth()) {
                    ChipShelf(
                        options =
                            SearchFilter.entries.toList().filterAvailableSearchFilters(
                                hideVideoSongs = Settings.shared.hideVideoSongs,
                                hidePodcasts = Settings.shared.hidePodcasts,
                            ),
                        selectedOption = selectedFilter,
                        onSelectionChange = onFilterSelected,
                        label = { filter ->
                            when (filter) {
                                SearchFilter.SONG -> text.filterSongs
                                SearchFilter.ALBUM -> text.filterAlbums
                                SearchFilter.ARTIST -> text.filterArtists
                                SearchFilter.FEATURED_PLAYLIST -> text.filterFeaturedPlaylists
                                SearchFilter.COMMUNITY_PLAYLIST -> text.filterCommunityPlaylists
                                SearchFilter.VIDEO -> text.filterVideos
                                SearchFilter.PODCAST -> text.filterPodcasts
                                SearchFilter.EPISODE -> text.filterEpisodes
                                SearchFilter.PROFILE -> text.filterProfiles
                            }
                        },
                        allLabel = text.filterAll,
                    )
                }
            }
        }

        if (isSearching) {
            item(key = "search_shimmer") {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                    Box(Modifier.widthIn(max = 1400.dp).fillMaxWidth()) {
                        SearchEnterMotion {
                            SearchResultsShimmer(sectioned = selectedFilter == null)
                        }
                    }
                }
            }
            return@DragScrollableColumn
        }

        if (uiState.searchFailed) {
            item(key = "search_failed") {
                Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(Res.string.search_online_failed))
                    TextButton(onClick = { onFilterSelected(selectedFilter) }, shapes = ButtonDefaults.shapes()) {
                        Text(stringResource(Res.string.retry))
                    }
                }
            }
        }

        if (showNoResults) {
            item(key = "no_results") {
                SearchEnterMotion(modifier = Modifier.fillParentMaxSize()) {
                    NoResultsFound(text = text, modifier = Modifier.fillParentMaxSize())
                }
            }
            return@DragScrollableColumn
        }

        if (showingFilteredResults) {
            itemsIndexed(filteredItems, key = {
                index,
                item,
                ->
                "filtered_${item::class.simpleName}_${item.id}_$index"
            }, contentType = {
                _,
                item,
                ->
                item::class.simpleName
            }) { _, item ->
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                    Box(Modifier.widthIn(max = 1400.dp).fillMaxWidth()) {
                        SearchEnterMotion {
                            SearchResultGroup(
                                items = listOf(item),
                                onItemClick = onItemClick,
                                onVideoItemClick = onVideoItemClick,
                                onItemLongClick = onItemLongClick,
                                onSongSave = onSongSave,
                                currentSongId = currentSongId,
                                isPlaying = isPlaying,
                                text = text,
                                onArtistClick = onArtistClick,
                                onAlbumClick = onAlbumClick,
                                onShuffleClick = onShuffleClick,
                                onMixClick = onMixClick,
                                itemContainer = itemContainer,
                                onAlbumPlay = onAlbumPlay,
                                isWide = isWide,
                            )
                        }
                    }
                }
            }
            if (uiState.loadMoreFailed) {
                item(key = "filtered_results_retry") {
                    Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(Res.string.search_load_more_failed))
                        TextButton(onClick = onRetryLoadMore, shapes = ButtonDefaults.shapes()) {
                            Text(stringResource(Res.string.retry))
                        }
                    }
                }
            }
            if (isLoadingMore) {
                item(key = "filtered_results_loading") {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(80.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        ContainedLoadingIndicator()
                    }
                }
            }
        } else {
            summaries.forEachIndexed { index, summary ->
                if (summary.items.isNotEmpty()) {
                    val sectionTitle = searchSectionTitle(summary, text)
                    item(key = "section_${index}_${summary.title}") {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                            Box(Modifier.widthIn(max = 1400.dp).fillMaxWidth()) {
                                SearchEnterMotion {
                                    SearchResultGroup(
                                        title = sectionTitle,
                                        items = summary.items,
                                        onItemClick = onItemClick,
                                        onVideoItemClick = onVideoItemClick,
                                        onItemLongClick = onItemLongClick,
                                        onSongSave = onSongSave,
                                        currentSongId = currentSongId,
                                        isPlaying = isPlaying,
                                        text = text,
                                        onArtistClick = onArtistClick,
                                        onAlbumClick = onAlbumClick,
                                        onShuffleClick = onShuffleClick,
                                        onMixClick = onMixClick,
                                        itemContainer = itemContainer,
                                        onAlbumPlay = onAlbumPlay,
                                        isTopResults = summary.isTopResult,
                                        isWide = isWide,
                                    )
                                }
                            }
                        }
                    }
                    item(key = "section_gap_$index") {
                        Spacer(Modifier.height(24.dp))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun TopResultCard(
    mainItem: YTItem,
    relatedItems: List<YTItem>,
    text: SearchContentText,
    onItemClick: (YTItem) -> Unit,
    onItemLongClick: (YTItem) -> Unit,
    onSongSave: (SongItem) -> Unit,
    currentSongId: String?,
    isPlaying: Boolean,
    onArtistClick: ((String) -> Unit)? = null,
    onAlbumClick: ((String) -> Unit)? = null,
    onShuffleClick: (YTItem) -> Unit = {},
    onMixClick: (YTItem) -> Unit = {},
    itemContainer: @Composable (YTItem, @Composable () -> Unit) -> Unit,
    isWide: Boolean = false,
    onAlbumPlay: (() -> Unit)? = null,
) {
    val podcastActions = if (mainItem is PodcastItem) rememberPodcastSearchActions(mainItem) else null
    val onPlay = podcastActions?.play ?: { onItemClick(mainItem) }
    val onSave =
        podcastActions?.save ?: {
            if (mainItem is SongItem) {
                onSongSave(mainItem)
            } else {
                onItemLongClick(mainItem)
            }
        }
    val saveLabel = if (podcastActions?.isSaved == true) stringResource(Res.string.saved) else text.save
    val canSave = podcastActions?.let { !it.isSaving && !it.isSaved } ?: true

    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceBright,
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            if (mainItem.thumbnail != null) {
                AsyncImage(
                    url = mainItem.thumbnail,
                    modifier =
                        Modifier
                            .matchParentSize()
                            .blur(56.dp)
                            .graphicsLayer(alpha = 0.38f),
                    contentScale = ContentScale.Crop,
                )
                Box(
                    modifier =
                        Modifier
                            .matchParentSize()
                            .background(
                                MaterialTheme.colorScheme.surfaceBright.copy(alpha = 0.66f),
                            ),
                )
            }

            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val isWideCard = maxWidth > 600.dp

                if (isWideCard && mainItem is ArtistItem) {
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(IntrinsicSize.Min)
                                .padding(8.dp),
                    ) {
                        Row(
                            modifier =
                                Modifier
                                    .weight(0.45f)
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(uiEnhanced(6.dp, 12.dp)))
                                    .clickable {
                                        CollectionPreviews.record(mainItem)
                                        onItemClick(mainItem)
                                    }.padding(24.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier =
                                    Modifier
                                        .size(if (isWide) 140.dp else 100.dp)
                                        .clip(artistArtworkShape(mainItem.id))
                                        .background(MaterialTheme.colorScheme.secondaryContainer),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (mainItem.thumbnail != null) {
                                    AsyncImage(
                                        url = mainItem.thumbnail,
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop,
                                    )
                                }
                            }
                            Spacer(Modifier.width(if (isWide) 24.dp else 16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = mainItem.displayTitle,
                                    style = if (isWide) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text =
                                        listOfNotNull(
                                            text.artist,
                                            mainItem.monthlyListeners?.let {
                                                monthlyAudienceText(it, text.monthlyAudienceShort)
                                            } ?: mainItem.subscribers,
                                        ).joinToString(" • "),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.height(24.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Surface(
                                        onClick = { onShuffleClick(mainItem) },
                                        modifier =
                                            Modifier
                                                .weight(1f)
                                                .height(48.dp),
                                        shape = CircleShape,
                                        color = MaterialTheme.colorScheme.primary,
                                        contentColor = MaterialTheme.colorScheme.onPrimary,
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxSize(),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.Center,
                                        ) {
                                            Icon(
                                                MetrolistIcons.Rounded.Shuffle,
                                                contentDescription = null,
                                                modifier = Modifier.size(20.dp),
                                            )
                                            Spacer(Modifier.width(8.dp))
                                            Text(
                                                text.shuffle,
                                                style = MaterialTheme.typography.labelLarge,
                                                fontWeight = FontWeight.Bold,
                                            )
                                        }
                                    }
                                    Surface(
                                        onClick = { onMixClick(mainItem) },
                                        modifier =
                                            Modifier
                                                .weight(1f)
                                                .height(48.dp),
                                        shape = CircleShape,
                                        color = MaterialTheme.colorScheme.surfaceVariant,
                                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        border =
                                            androidx.compose.foundation.BorderStroke(
                                                1.dp,
                                                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                            ),
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxSize(),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.Center,
                                        ) {
                                            Icon(
                                                MetrolistIcons.Outlined.Sensors,
                                                contentDescription = null,
                                                modifier = Modifier.size(20.dp),
                                            )
                                            Spacer(Modifier.width(8.dp))
                                            Text(
                                                text.mix,
                                                style = MaterialTheme.typography.labelLarge,
                                                fontWeight = FontWeight.Bold,
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)))
                        Spacer(Modifier.width(8.dp))

                        Column(
                            modifier =
                                Modifier
                                    .weight(0.55f)
                                    .fillMaxHeight()
                                    .padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            relatedItems.forEach { song ->
                                itemContainer(song) {
                                    TopResultItem(
                                        item = song,
                                        mainArtist = mainItem,
                                        textData = text,
                                        currentSongId = currentSongId,
                                        isPlaying = isPlaying,
                                        onClick = { onItemClick(song) },
                                        onMenuClick = { onItemLongClick(song) },
                                        onArtistClick = onArtistClick,
                                        clickableMetadata = isWide,
                                    )
                                }
                            }
                        }
                    }
                } else {
                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(8.dp),
                    ) {
                        if (mainItem is ArtistItem) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 72.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable {
                                            CollectionPreviews.record(mainItem)
                                            onItemClick(mainItem)
                                        }.onSecondaryClick { onItemLongClick(mainItem) }
                                        .padding(horizontal = 8.dp, vertical = 8.dp),
                            ) {
                                Box(
                                    modifier =
                                        Modifier
                                            .size(56.dp)
                                            .clip(artistArtworkShape(mainItem.id))
                                            .background(MaterialTheme.colorScheme.secondaryContainer),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (mainItem.thumbnail != null) {
                                        AsyncImage(
                                            url = mainItem.thumbnail,
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = ContentScale.Crop,
                                        )
                                    }
                                }
                                Spacer(Modifier.width(16.dp))
                                Column {
                                    Text(
                                        text = mainItem.displayTitle,
                                        style = if (isWide) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    Text(
                                        text =
                                            listOfNotNull(
                                                text.artist,
                                                mainItem.monthlyListeners?.let {
                                                    monthlyAudienceText(it, text.monthlyAudienceShort)
                                                } ?: mainItem.subscribers,
                                            ).joinToString(" • "),
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Spacer(Modifier.weight(1f))
                                IconButton(
                                    modifier = Modifier.navigationPopupSource(),
                                    onClick = { onItemLongClick(mainItem) },
                                    shapes = IconButtonDefaults.shapes(),
                                ) {
                                    Icon(MetrolistIcons.Default.MoreVert, contentDescription = stringResource(Res.string.more_options))
                                }
                            }
                            if (relatedItems.isNotEmpty()) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                                )
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    relatedItems.forEach { song ->
                                        itemContainer(song) {
                                            TopResultItem(
                                                item = song,
                                                mainArtist = mainItem,
                                                textData = text,
                                                currentSongId = currentSongId,
                                                isPlaying = isPlaying,
                                                onClick = { onItemClick(song) },
                                                onMenuClick = { onItemLongClick(song) },
                                                onArtistClick = onArtistClick,
                                                clickableMetadata = isWide,
                                            )
                                        }
                                    }
                                }
                            }
                        } else {
                            itemContainer(mainItem) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                            .clickable { onItemClick(mainItem) }
                                            .onSecondaryClick { onItemLongClick(mainItem) }
                                            .padding(horizontal = 8.dp, vertical = 16.dp),
                                ) {
                                    Box(
                                        modifier =
                                            Modifier
                                                .size(if (isWide) 120.dp else 80.dp)
                                                .clip(RoundedCornerShape(uiEnhanced(6.dp, 12.dp)))
                                                .background(MaterialTheme.colorScheme.secondaryContainer),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        if (mainItem is PlaylistItem) {
                                            PlaylistCoverImage(mainItem, Modifier.fillMaxSize())
                                        } else if (mainItem.thumbnail != null) {
                                            AsyncImage(
                                                url = mainItem.displayThumbnailUrl,
                                                modifier = Modifier.fillMaxSize(),
                                                contentScale = ContentScale.Crop,
                                            )
                                        }
                                        if (mainItem is SongItem) {
                                            SongThumbnailPlayingIndicator(
                                                isActive = mainItem.id == currentSongId && !Settings.shared.uiEnhancements,
                                                isPlaying = isPlaying,
                                                shape = RoundedCornerShape(6.dp),
                                            )
                                        }
                                        if (mainItem is AlbumItem && onAlbumPlay != null) {
                                            AlbumPlayButton(visible = true, onClick = onAlbumPlay)
                                        }
                                    }
                                    Spacer(Modifier.width(if (isWide) 24.dp else 16.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = mainItem.displayTitle,
                                            style =
                                                if (isWide) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            val subtitleStyle =
                                                if (isWide) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium
                                            val subtitleColor = MaterialTheme.colorScheme.onSurfaceVariant

                                            if (mainItem is SongItem) {
                                                if (mainItem.isExplicit) {
                                                    Icon(
                                                        imageVector = SongStatusIcons.Explicit,
                                                        contentDescription = stringResource(Res.string.explicit),
                                                        tint = subtitleColor,
                                                        modifier = Modifier.size(14.dp).padding(end = 4.dp),
                                                    )
                                                }

                                                Text(
                                                    text = stringResource(Res.string.song_type),
                                                    style = subtitleStyle,
                                                    color = subtitleColor,
                                                )
                                                Text(
                                                    text = " • ",
                                                    style = subtitleStyle,
                                                    color = subtitleColor,
                                                )

                                                ArtistSubtitle(
                                                    artists = mainItem.artists,
                                                    onArtistClick = onArtistClick,
                                                    style = subtitleStyle,
                                                    color = subtitleColor,
                                                    modifier = Modifier.weight(1f, fill = false),
                                                    clickable = isWide,
                                                )

                                                val suffix =
                                                    listOfNotNull(
                                                        mainItem.duration?.let { formatDuration(it * 1000) },
                                                        mainItem.playCountText,
                                                    ).joinToString(" • ")
                                                if (suffix.isNotEmpty()) {
                                                    FittingText(
                                                        text = " • $suffix",
                                                        compactText = " • ${suffix.withoutPlaybackCountLabel()}",
                                                        style = subtitleStyle,
                                                        color = subtitleColor,
                                                    )
                                                }
                                            } else {
                                                val subtitle = quickSearchItemSubtitle(mainItem, text)
                                                if (subtitle.isNotEmpty()) {
                                                    Text(
                                                        text = subtitle,
                                                        style = subtitleStyle,
                                                        color = subtitleColor,
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    if (isWide) {
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.padding(horizontal = 16.dp),
                                        ) {
                                            Surface(
                                                onClick = onPlay,
                                                enabled = podcastActions?.isLoading != true,
                                                modifier = Modifier.height(40.dp),
                                                shape = RoundedCornerShape(100.dp),
                                                color = MaterialTheme.colorScheme.primary,
                                                contentColor = MaterialTheme.colorScheme.onPrimary,
                                            ) {
                                                Row(
                                                    modifier = Modifier.padding(horizontal = 20.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.Center,
                                                ) {
                                                    Icon(MetrolistIcons.Default.PlayArrow, null, modifier = Modifier.size(20.dp))
                                                    Spacer(Modifier.width(8.dp))
                                                    Text(
                                                        text.play,
                                                        style = MaterialTheme.typography.labelLarge,
                                                        fontWeight = FontWeight.Bold,
                                                    )
                                                }
                                            }

                                            Surface(
                                                onClick = onSave,
                                                enabled = canSave,
                                                modifier = Modifier.height(40.dp),
                                                shape = RoundedCornerShape(100.dp),
                                                color = MaterialTheme.colorScheme.surfaceVariant,
                                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                                border =
                                                    androidx.compose.foundation.BorderStroke(
                                                        1.dp,
                                                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                                    ),
                                            ) {
                                                Row(
                                                    modifier = Modifier.padding(horizontal = 20.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.Center,
                                                ) {
                                                    Icon(
                                                        MetrolistIcons.AutoMirrored.Outlined.PlaylistAdd,
                                                        null,
                                                        modifier = Modifier.size(20.dp),
                                                    )
                                                    Spacer(Modifier.width(8.dp))
                                                    Text(
                                                        saveLabel,
                                                        style = MaterialTheme.typography.labelLarge,
                                                        fontWeight = FontWeight.Bold,
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    IconButton(
                                        modifier = Modifier.navigationPopupSource(),
                                        onClick = { onItemLongClick(mainItem) },
                                        shapes = IconButtonDefaults.shapes(),
                                    ) {
                                        Icon(MetrolistIcons.Default.MoreVert, contentDescription = stringResource(Res.string.more_options))
                                    }
                                }
                            }

                            if (!isWide) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                ) {
                                    Surface(
                                        onClick = onPlay,
                                        enabled = podcastActions?.isLoading != true,
                                        modifier = Modifier.height(36.dp).weight(1f),
                                        shape = RoundedCornerShape(100.dp),
                                        color = MaterialTheme.colorScheme.primary,
                                        contentColor = MaterialTheme.colorScheme.onPrimary,
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxSize(),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.Center,
                                        ) {
                                            Icon(MetrolistIcons.Default.PlayArrow, null, modifier = Modifier.size(18.dp))
                                            Spacer(Modifier.width(8.dp))
                                            Text(text.play, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                    Surface(
                                        onClick = onSave,
                                        enabled = canSave,
                                        modifier = Modifier.height(36.dp).weight(1f),
                                        shape = RoundedCornerShape(100.dp),
                                        color = MaterialTheme.colorScheme.surfaceVariant,
                                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        border =
                                            androidx.compose.foundation.BorderStroke(
                                                1.dp,
                                                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                            ),
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxSize(),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.Center,
                                        ) {
                                            Icon(MetrolistIcons.AutoMirrored.Outlined.PlaylistAdd, null, modifier = Modifier.size(18.dp))
                                            Spacer(Modifier.width(8.dp))
                                            Text(saveLabel, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            podcastActions?.let { SnackbarHost(it.snackbar, Modifier.align(Alignment.BottomCenter)) }
        }
    }
}

@Composable
private fun TopResultItem(
    item: YTItem,
    mainArtist: ArtistItem,
    textData: SearchContentText,
    currentSongId: String?,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onMenuClick: () -> Unit,
    modifier: Modifier = Modifier,
    onArtistClick: ((String) -> Unit)? = null,
    clickableMetadata: Boolean = true,
) {
    val isCurrentSong = item is SongItem && item.id == currentSongId

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp)
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onClick)
                .onSecondaryClick(onMenuClick)
                .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .squareArtworkSlot(56.dp, item.thumbnailAspectRatio)
                    .clip(RoundedCornerShape(uiEnhanced(6.dp, 12.dp)))
                    .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            if (item is PlaylistItem) {
                PlaylistCoverImage(item, Modifier.fillMaxSize())
            } else if (item.thumbnail != null) {
                AsyncImage(
                    url = item.displayThumbnailUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
            if (item is SongItem) {
                SongThumbnailPlayingIndicator(
                    isActive = isCurrentSong,
                    isPlaying = isPlaying,
                    shape = RoundedCornerShape(6.dp),
                )
            }
        }

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.displayTitle,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (isCurrentSong) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (item is SongItem && item.isExplicit) {
                    Icon(
                        imageVector = SongStatusIcons.Explicit,
                        contentDescription = stringResource(Res.string.explicit),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp).padding(end = 4.dp),
                    )
                }

                if (item is SongItem) {
                    val artists = item.topResultArtists(mainArtist)
                    if (artists.isNotEmpty()) {
                        ArtistSubtitle(
                            artists = artists,
                            onArtistClick = onArtistClick,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f, fill = false),
                            clickable = clickableMetadata,
                        )
                    }

                    val metadata =
                        listOfNotNull(
                            item.duration?.let { formatDuration(it * 1000) },
                            item.playCountText,
                        ).joinToString(" • ")

                    if (metadata.isNotEmpty()) {
                        val separator = if (artists.isEmpty()) "" else " • "
                        FittingText(
                            text = "$separator$metadata",
                            compactText = "$separator${metadata.withoutPlaybackCountLabel()}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    val subtitle = searchItemSubtitle(item, textData)
                    if (subtitle.isNotEmpty()) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        IconButton(modifier = Modifier.navigationPopupSource(), onClick = onMenuClick, shapes = IconButtonDefaults.shapes()) {
            Icon(
                imageVector = MetrolistIcons.Default.MoreVert,
                contentDescription = stringResource(Res.string.more_options),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

internal fun SongItem.topResultArtists(mainArtist: ArtistItem): List<ArtistTiny> =
    artists.filterNot { artist ->
        if (artist.id.isNullOrBlank()) {
            artist.name.trim().equals(mainArtist.title.trim(), ignoreCase = true)
        } else {
            artist.id == mainArtist.id || artist.id == mainArtist.channelId
        }
    }

@Composable
private fun SearchResultGroup(
    items: List<YTItem>,
    onItemClick: (YTItem) -> Unit,
    onVideoItemClick: (YTItem) -> Unit = onItemClick,
    onItemLongClick: (YTItem) -> Unit,
    onSongSave: (SongItem) -> Unit,
    currentSongId: String?,
    isPlaying: Boolean,
    text: SearchContentText,
    title: String? = null,
    onArtistClick: ((String) -> Unit)? = null,
    onAlbumClick: ((String) -> Unit)? = null,
    onShuffleClick: (YTItem) -> Unit = {},
    onMixClick: (YTItem) -> Unit = {},
    itemContainer: @Composable (YTItem, @Composable () -> Unit) -> Unit = { _, content -> content() },
    isTopResults: Boolean = false,
    isWide: Boolean = false,
    onAlbumPlay: ((AlbumItem) -> Unit)? = null,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (!title.isNullOrBlank()) {
            SectionHeader(title = title, isWide = isWide)
        }

        if (!isTopResults && title != null && items.isVideoShelf()) {
            VideoShelf(
                items = items,
                onItemClick = onVideoItemClick,
                onItemLongClick = onItemLongClick,
                isWide = isWide,
                contentPadding = PaddingValues(horizontal = 16.dp),
                currentSongId = rememberUpdatedState(currentSongId),
                isPlaying = rememberUpdatedState(isPlaying),
                onArtistClick = onArtistClick,
                onAlbumClick = onAlbumClick,
            )
        } else if (isTopResults && items.isNotEmpty()) {
            val mainItem = items.first()
            val relatedItems = items.drop(1).take(3)

            TopResultCard(
                mainItem = mainItem,
                relatedItems = relatedItems,
                text = text,
                onItemClick = onItemClick,
                onItemLongClick = onItemLongClick,
                onSongSave = onSongSave,
                currentSongId = currentSongId,
                isPlaying = isPlaying,
                onArtistClick = onArtistClick,
                onAlbumClick = onAlbumClick,
                onShuffleClick = onShuffleClick,
                onMixClick = onMixClick,
                itemContainer = itemContainer,
                isWide = isWide,
                onAlbumPlay = onAlbumPlay?.let { play -> { (mainItem as? AlbumItem)?.let(play) } },
            )

            if (items.size > 4) {
                items.drop(4).forEach { item ->
                    itemContainer(item) {
                        SearchResultItem(
                            item = item,
                            textData = text,
                            currentSongId = currentSongId,
                            isPlaying = isPlaying,
                            onClick = { onItemClick(item) },
                            onMenuClick = { onItemLongClick(item) },
                            onArtistClick = onArtistClick,
                            onAlbumClick = onAlbumClick,
                            modifier = Modifier.padding(horizontal = 8.dp),
                            isWide = isWide,
                            onAlbumPlay = onAlbumPlay?.let { play -> { (item as? AlbumItem)?.let(play) } },
                        )
                    }
                }
            }
        } else {
            items.forEach { item ->
                itemContainer(item) {
                    SearchResultItem(
                        item = item,
                        textData = text,
                        currentSongId = currentSongId,
                        isPlaying = isPlaying,
                        onClick = { onItemClick(item) },
                        onMenuClick = { onItemLongClick(item) },
                        onArtistClick = onArtistClick,
                        onAlbumClick = onAlbumClick,
                        modifier = Modifier.padding(horizontal = 8.dp),
                        isWide = isWide,
                        onAlbumPlay = onAlbumPlay?.let { play -> { (item as? AlbumItem)?.let(play) } },
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchResultsShimmer(sectioned: Boolean) {
    val loadingText = stringResource(Res.string.loading)
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val animate = lifecycleState == Lifecycle.State.RESUMED

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clearAndSetSemantics {
                    contentDescription = loadingText
                    liveRegion = LiveRegionMode.Polite
                }.then(if (animate) Modifier.shimmer() else Modifier),
        verticalArrangement = Arrangement.spacedBy(if (sectioned) 24.dp else 8.dp),
    ) {
        if (!sectioned) {
            repeat(10) { SearchResultItemPlaceholder() }
            return@Column
        }
        repeat(3) { sectionIndex ->
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Spacer(
                    modifier =
                        Modifier
                            .padding(start = 16.dp, top = 16.dp, bottom = 4.dp)
                            .width(if (sectionIndex == 0) 160.dp else 112.dp)
                            .height(30.dp)
                            .clip(ComponentShapes.SmallThumbnailShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                )
                repeat(if (sectionIndex == 0) 2 else 4) {
                    SearchResultItemPlaceholder()
                }
            }
        }
    }
}

@Composable
private fun SearchResultItemPlaceholder() {
    Row(
        modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(
            modifier =
                Modifier
                    .size(48.dp)
                    .clip(ComponentShapes.MenuItemShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Spacer(
                modifier =
                    Modifier
                        .fillMaxWidth(0.78f)
                        .height(14.dp)
                        .clip(ComponentShapes.MenuItemShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
            )
            Spacer(
                modifier =
                    Modifier
                        .fillMaxWidth(0.48f)
                        .height(12.dp)
                        .clip(ComponentShapes.MenuItemShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
            )
        }
        Spacer(
            modifier =
                Modifier
                    .size(width = 4.dp, height = 28.dp)
                    .clip(RoundedCornerShape(100.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
        )
    }
}

@Composable
private fun SearchResultItem(
    item: YTItem,
    textData: SearchContentText,
    currentSongId: String?,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onMenuClick: () -> Unit,
    onArtistClick: ((String) -> Unit)? = null,
    onAlbumClick: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier,
    isWide: Boolean = false,
    onAlbumPlay: (() -> Unit)? = null,
) {
    val isCurrentSong = item is SongItem && item.id == currentSongId

    YTListItem(
        item = item,
        onClick = onClick,
        onShowMenu = { onMenuClick() },
        onArtistClick = onArtistClick,
        onAlbumClick = onAlbumClick,
        isCurrent = isCurrentSong,
        isPlaying = isPlaying,
        modifier = modifier,
        clickableMetadata = isWide,
        onAlbumPlay = onAlbumPlay,
    )
}

@Composable
private fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    isWide: Boolean = false,
) {
    val titleStyle = MaterialTheme.typography.headlineMediumEmphasized
    val rowHeight = 64.dp

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = rowHeight)
                .padding(start = 16.dp, end = 16.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = titleStyle,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
        )
    }
}

private fun searchSectionTitle(
    summary: SearchSummary,
    text: SearchContentText,
): String =
    when {
        summary.isTopResult -> text.topResults
        summary.title.equals("Songs", ignoreCase = true) -> text.filterSongs
        summary.title.equals("Videos", ignoreCase = true) -> text.filterVideos
        summary.title.equals("Albums", ignoreCase = true) -> text.filterAlbums
        summary.title.equals("Artists", ignoreCase = true) -> text.filterArtists
        summary.title.equals("Playlists", ignoreCase = true) -> text.filterPlaylists
        summary.title.equals("Podcasts", ignoreCase = true) -> text.filterPodcasts
        summary.title.equals("Episodes", ignoreCase = true) -> text.filterEpisodes
        summary.title.equals("Browse", ignoreCase = true) -> text.browse
        summary.title.equals("Verified Artists", ignoreCase = true) -> text.verifiedArtists
        else -> summary.title
    }

private fun searchItemSubtitle(
    item: YTItem,
    text: SearchContentText,
): String =
    when (item) {
        is SongItem -> {
            listOfNotNull(
                item.artists.joinToString(", ") { it.displayName }.takeIf { it.isNotBlank() },
                item.duration?.let { formatDuration(it * 1000) },
                item.playCountText,
            ).joinToString(" ·\u00a0")
        }

        is AlbumItem -> {
            listOfNotNull(
                item.artists.joinToString(", ") { it.displayName }.takeIf { it.isNotBlank() },
                item.year,
            ).joinToString(" ·\u00a0")
        }

        is ArtistItem -> {
            monthlyAudienceText(item.monthlyListeners, text.monthlyAudienceShort) ?: item.subscribers.orEmpty()
        }

        is PlaylistItem -> {
            listOfNotNull(item.author?.let { ArtistNameAliases.resolve(null, it) }, item.songCount).joinToString(" ·\u00a0")
        }

        is PodcastItem -> {
            listOfNotNull(item.author?.let { ArtistNameAliases.resolve(null, it) }, item.episodeCount).joinToString(" ·\u00a0")
        }

        is EpisodeItem -> {
            listOfNotNull(
                item.author?.displayName,
                item.publishDateText,
                item.duration?.let { formatDuration(it * 1000) },
            ).joinToString(" ·\u00a0")
        }

        else -> {
            ""
        }
    }

private fun quickSearchItemSubtitle(
    item: YTItem,
    text: SearchContentText,
): String {
    val type =
        when (item) {
            is SongItem -> text.track
            is AlbumItem -> text.album
            is ArtistItem -> text.artist
            is PlaylistItem -> text.playlist
            is PodcastItem -> text.podcast
            is EpisodeItem -> text.episode
            else -> null
        }
    return listOfNotNull(type, searchItemSubtitle(item, text).takeIf { it.isNotBlank() }).joinToString(" ·\u00a0")
}

@Composable
private fun NoResultsFound(
    text: SearchContentText,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxSize().padding(top = 72.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Surface(
                shape = ComponentShapes.AlbumArtShape,
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.size(width = 136.dp, height = 112.dp).rotate(-45f),
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        MetrolistIcons.Outlined.Search,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp).rotate(45f),
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
            Text(
                text = text.noResultsFound,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
