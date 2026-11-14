@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.shared.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TwoRowsTopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.metrolist.shared.constants.GridRowSpacing
import com.metrolist.shared.constants.GridTileSpacing
import com.metrolist.shared.constants.currentGridCells
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.back
import com.metrolist.shared.generated.resources.browse
import com.metrolist.shared.generated.resources.loading
import com.metrolist.shared.generated.resources.nothing_to_show
import com.metrolist.shared.generated.resources.retry
import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.model.BrowseItem
import com.metrolist.shared.model.BrowsePageData
import com.metrolist.shared.model.EpisodeItem
import com.metrolist.shared.model.HomeSectionLayout
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.YTItem
import com.metrolist.shared.model.canonicalHomeItemId
import com.metrolist.shared.model.homeSectionLayout
import com.metrolist.shared.model.toSongItem
import com.metrolist.shared.ui.components.ContainedLoadingIndicator
import com.metrolist.shared.ui.components.GenreCard
import com.metrolist.shared.ui.components.SectionTitle
import com.metrolist.shared.ui.components.YTGridItem
import com.metrolist.shared.ui.components.YTListItem
import com.metrolist.shared.ui.components.images.ImageLoadingPlaceholder
import com.metrolist.shared.ui.components.rememberItemPlayback
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.tv.tvFocusHighlight
import com.metrolist.shared.ui.utils.DragScrollableGrid
import com.metrolist.shared.ui.utils.desktopArrowScroll
import com.metrolist.shared.utils.isDesktop
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun BrowseContent(
    page: BrowsePageData,
    isLoading: Boolean,
    isLoadingMore: Boolean,
    onBackClick: (() -> Unit)?,
    onItemClick: (YTItem, List<SongItem>) -> Unit,
    onItemLongClick: ((YTItem) -> Unit)?,
    onLoadMore: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = page.title,
    hasMore: Boolean = page.continuation != null || page.sections.any { it.continuation != null },
    contentPadding: PaddingValues = PaddingValues(0.dp),
    error: String? = null,
    onRetry: () -> Unit = {},
    loadMoreError: String? = null,
    onLoadMoreRetry: () -> Unit = onLoadMore,
    browseId: String? = null,
    sectionPage: Boolean = false,
    currentSongId: State<String?> = remember { mutableStateOf(null) },
    isPlaying: State<Boolean> = remember { mutableStateOf(false) },
    onArtistClick: ((String) -> Unit)? = null,
    onAlbumClick: ((String) -> Unit)? = null,
    onAlbumPlay: ((AlbumItem) -> Unit)? = null,
    isItemEnabled: (YTItem) -> Boolean = { true },
    headerContent: (@Composable () -> Unit)? = null,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val layoutDirection = LocalLayoutDirection.current
    val categoriesOnly =
        page.sections.any { it.items.isNotEmpty() } && page.sections.all { section -> section.items.all { it is BrowseItem } }
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TwoRowsTopAppBar(
                expandedHeight = 128.dp,
                title = { expanded ->
                    Text(
                        title.orEmpty().ifBlank { stringResource(Res.string.browse) },
                        style =
                            if (expanded) {
                                MaterialTheme.typography.headlineMediumEmphasized
                            } else {
                                MaterialTheme.typography.titleLargeEmphasized
                            },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier =
                            if (expanded) {
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = if (categoriesOnly) 6.dp else 0.dp, vertical = 16.dp)
                            } else {
                                Modifier
                            },
                    )
                },
                navigationIcon = {
                    if (onBackClick != null) {
                        IconButton(onClick = onBackClick, shapes = IconButtonDefaults.shapes()) {
                            Icon(MetrolistIcons.AutoMirrored.Filled.ArrowBack, stringResource(Res.string.back))
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { scaffoldPadding ->
        val padding =
            PaddingValues(
                start = contentPadding.calculateLeftPadding(layoutDirection),
                top = scaffoldPadding.calculateTopPadding() + contentPadding.calculateTopPadding(),
                end = contentPadding.calculateRightPadding(layoutDirection),
                bottom = scaffoldPadding.calculateBottomPadding() + contentPadding.calculateBottomPadding() + 16.dp,
            )
        when {
            error != null && page.sections.isEmpty() -> {
                Column(
                    Modifier.fillMaxSize().padding(padding),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(error, color = MaterialTheme.colorScheme.error)
                    Button(onClick = onRetry, shapes = ButtonDefaults.shapes()) { Text(stringResource(Res.string.retry)) }
                }
            }

            isLoading && page.sections.isEmpty() -> {
                val loadingLabel = stringResource(Res.string.loading)
                val isSongList = browseId?.startsWith("VLOL") == true
                DragScrollableGrid(
                    columns = if (sectionPage) GridCells.Fixed(2) else currentGridCells(),
                    modifier = Modifier.fillMaxSize().semantics { contentDescription = loadingLabel },
                    contentPadding =
                        PaddingValues(
                            start = padding.calculateLeftPadding(layoutDirection) + 16.dp,
                            top = padding.calculateTopPadding(),
                            end = padding.calculateRightPadding(layoutDirection) + 16.dp,
                            bottom = padding.calculateBottomPadding(),
                        ),
                    horizontalArrangement = Arrangement.spacedBy(GridTileSpacing),
                    verticalArrangement = Arrangement.spacedBy(GridRowSpacing),
                    userScrollEnabled = false,
                ) {
                    if (isSongList) {
                        items(12, span = { GridItemSpan(maxLineSpan) }) {
                            Row(
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                ImageLoadingPlaceholder(Modifier.size(48.dp).clip(RoundedCornerShape(4.dp)))
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    val density = LocalDensity.current
                                    val titleHeight =
                                        with(density) {
                                            MaterialTheme.typography.bodyMedium.lineHeight
                                                .toDp()
                                        }
                                    val subtitleHeight =
                                        with(density) {
                                            MaterialTheme.typography.labelSmall.lineHeight
                                                .toDp()
                                        }
                                    ImageLoadingPlaceholder(Modifier.fillMaxWidth(0.8f).height(titleHeight))
                                    ImageLoadingPlaceholder(Modifier.fillMaxWidth(0.5f).height(subtitleHeight))
                                }
                            }
                        }
                    } else {
                        repeat(3) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                ImageLoadingPlaceholder(Modifier.padding(vertical = 8.dp).fillMaxWidth(0.55f).height(28.dp))
                            }
                            items(12) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    ImageLoadingPlaceholder(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(4.dp)))
                                    ImageLoadingPlaceholder(Modifier.fillMaxWidth(0.8f).height(16.dp))
                                    ImageLoadingPlaceholder(Modifier.fillMaxWidth(0.5f).height(12.dp))
                                }
                            }
                        }
                    }
                }
            }

            page.sections.all { it.items.isEmpty() } && !hasMore && !isLoadingMore -> {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    Text(stringResource(Res.string.nothing_to_show), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            else -> {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val categoryInset = if (categoriesOnly) 22.dp else 38.dp
                    val categoryWidth =
                        maxWidth - padding.calculateLeftPadding(layoutDirection) - padding.calculateRightPadding(layoutDirection) -
                            categoryInset * 2
                    val categoryColumns =
                        if (isDesktop) ((categoryWidth + GridTileSpacing) / (240.dp + GridTileSpacing)).toInt().coerceAtLeast(1) else 2
                    val gridState = rememberLazyGridState()
                    DragScrollableGrid(
                        state = gridState,
                        columns = if (sectionPage) GridCells.Fixed(2) else currentGridCells(),
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .padding(horizontal = if (categoriesOnly) 16.dp else 0.dp)
                                .desktopArrowScroll(gridState),
                        contentPadding =
                            PaddingValues(
                                start = padding.calculateLeftPadding(layoutDirection) + if (categoriesOnly) 0.dp else 16.dp,
                                top = padding.calculateTopPadding(),
                                end = padding.calculateRightPadding(layoutDirection) + if (categoriesOnly) 0.dp else 16.dp,
                                bottom = padding.calculateBottomPadding(),
                            ),
                        horizontalArrangement = Arrangement.spacedBy(GridTileSpacing),
                        verticalArrangement = Arrangement.spacedBy(if (isDesktop && categoriesOnly) GridTileSpacing else GridRowSpacing),
                    ) {
                        if (headerContent != null) {
                            item(key = "section_actions", span = { GridItemSpan(maxLineSpan) }) { headerContent() }
                        }
                        page.sections.forEachIndexed { sectionIndex, section ->
                            if (section.items.isEmpty()) return@forEachIndexed
                            val songs = section.items.toPlayableSongs()
                            if (section.items.all { it is BrowseItem }) {
                                item(key = "section_${sectionIndex}_title", span = { GridItemSpan(maxLineSpan) }) {
                                    Text(
                                        section.title,
                                        style = MaterialTheme.typography.headlineSmallEmphasized,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier =
                                            Modifier
                                                .padding(
                                                    horizontal = if (categoriesOnly) 6.dp else 22.dp,
                                                ).padding(top = 15.dp, bottom = 10.dp),
                                    )
                                }
                                val categoryRows = section.items.chunked(categoryColumns)
                                itemsIndexed(
                                    items = categoryRows,
                                    key = { _, items -> "section_${sectionIndex}_categories_${canonicalHomeItemId(items.first())}" },
                                    span = { _, _ -> GridItemSpan(maxLineSpan) },
                                    contentType = { _, _ -> "category_row" },
                                ) { row, items ->
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(
                                                horizontal = if (categoriesOnly) 6.dp else 22.dp,
                                            ).padding(bottom = if (isDesktop) 0.dp else 3.dp),
                                        horizontalArrangement = Arrangement.spacedBy(if (isDesktop) GridTileSpacing else 3.dp),
                                    ) {
                                        items.forEachIndexed { column, item ->
                                            val lastRow = row == categoryRows.lastIndex
                                            GenreCard(
                                                title = item.title,
                                                onClick = { onItemClick(item, songs) },
                                                shape =
                                                    RoundedCornerShape(
                                                        topStart = if (row == 0 && column == 0) 12.dp else 5.dp,
                                                        topEnd = if (row == 0 && column == items.lastIndex) 12.dp else 5.dp,
                                                        bottomStart = if (lastRow && column == 0) 12.dp else 5.dp,
                                                        bottomEnd = if (lastRow && column == items.lastIndex) 12.dp else 5.dp,
                                                    ),
                                                modifier = Modifier.weight(1f),
                                            )
                                        }
                                        if (!isDesktop && items.size == 1) Spacer(Modifier.weight(1f))
                                    }
                                }
                            } else {
                                if (section.title.isNotBlank() && (page.sections.size > 1 || page.title.isNullOrBlank())) {
                                    item(key = "section_${sectionIndex}_title", span = { GridItemSpan(maxLineSpan) }) {
                                        SectionTitle(
                                            title = section.title,
                                            modifier = Modifier.padding(vertical = 8.dp),
                                        )
                                    }
                                }
                                val layout = section.items.homeSectionLayout()
                                val isList =
                                    if (sectionPage) {
                                        layout == HomeSectionLayout.LIST
                                    } else {
                                        section.isListStyle ||
                                            section.items.all { it is SongItem || it is EpisodeItem }
                                    }
                                val isVideo = sectionPage && layout == HomeSectionLayout.VIDEOS
                                itemsIndexed(
                                    items = section.items,
                                    key = { index, item -> "section_${sectionIndex}_${item.id}_$index" },
                                    span = { _, _ -> GridItemSpan(if (isList || isVideo) maxLineSpan else 1) },
                                    contentType = { _, item -> item::class },
                                ) { _, item ->
                                    val playback by rememberItemPlayback(item.id, currentSongId, isPlaying)
                                    val enabled = !item.isUnavailable && isItemEnabled(item)
                                    val menu = onItemLongClick?.let { callback -> { callback(item) } }
                                    if (isList) {
                                        YTListItem(
                                            item = item,
                                            onClick = { onItemClick(item, songs) },
                                            onLongClick = menu,
                                            onShowMenu = menu,
                                            onArtistClick = onArtistClick.takeIf { enabled },
                                            onAlbumClick = onAlbumClick.takeIf { enabled },
                                            onAlbumPlay = onAlbumPlay?.let { play -> (item as? AlbumItem)?.let { { play(it) } } },
                                            isCurrent = playback.isCurrent,
                                            isPlaying = playback.isPlaying,
                                            enabled = enabled,
                                            clickableMetadata = !sectionPage,
                                            modifier = if (sectionPage) Modifier.tvFocusHighlight() else Modifier,
                                        )
                                    } else {
                                        YTGridItem(
                                            item = item,
                                            onClick = { onItemClick(item, songs) },
                                            onLongClick = menu,
                                            onShowMenu = onItemLongClick,
                                            onArtistClick = onArtistClick.takeIf { enabled },
                                            onAlbumClick = onAlbumClick.takeIf { enabled },
                                            onAlbumPlay = onAlbumPlay?.let { play -> (item as? AlbumItem)?.let { { play(it) } } },
                                            isCurrent = playback.isCurrent,
                                            isPlaying = playback.isPlaying,
                                            enabled = enabled,
                                            clickableMetadata = !sectionPage,
                                            modifier =
                                                Modifier.fillMaxWidth().then(
                                                    if (sectionPage) Modifier.tvFocusHighlight() else Modifier,
                                                ),
                                            fillWidth = true,
                                            artworkAspectRatio = if (isVideo) 16f / 9f else null,
                                            squareArtwork = sectionPage && !isVideo,
                                            subtitleOverride = if (sectionPage) homeCardSubtitle(item) else null,
                                            showDurationBadge = sectionPage && item is EpisodeItem,
                                            showAlbumYear = true,
                                        )
                                    }
                                }
                            }
                        }
                        if (error != null) {
                            item(key = "browse_error", span = { GridItemSpan(maxLineSpan) }) {
                                Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(error, color = MaterialTheme.colorScheme.error)
                                    Button(onClick = onRetry, shapes = ButtonDefaults.shapes()) { Text(stringResource(Res.string.retry)) }
                                }
                            }
                        }
                        if (isLoadingMore || hasMore) {
                            item(key = "browse_more", span = {
                                GridItemSpan(maxLineSpan)
                            }) { LoadMore(isLoadingMore, hasMore, loadMoreError, onLoadMore, onLoadMoreRetry) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LoadMore(
    isLoadingMore: Boolean,
    hasMore: Boolean,
    error: String?,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
) {
    if (hasMore && !isLoadingMore && error == null) LaunchedEffect(Unit) { onLoadMore() }
    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        when {
            isLoadingMore -> ContainedLoadingIndicator()
            error != null -> Button(onClick = onRetry, shapes = ButtonDefaults.shapes()) { Text(stringResource(Res.string.retry)) }
        }
    }
}

internal fun List<YTItem>.toPlayableSongs() =
    mapNotNull { item ->
        when (item) {
            is SongItem -> item
            is EpisodeItem -> item.toSongItem()
            else -> null
        }
    }
