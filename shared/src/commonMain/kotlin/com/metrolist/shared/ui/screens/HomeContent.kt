@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.shared.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerSnapDistance
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.carousel.CarouselDefaults
import androidx.compose.material3.carousel.CarouselItemDrawInfo
import androidx.compose.material3.carousel.HorizontalMultiBrowseCarousel
import androidx.compose.material3.carousel.rememberCarouselState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.metrolist.shared.constants.CarouselItemCornerRadius
import com.metrolist.shared.constants.CarouselItemSize
import com.metrolist.shared.constants.CarouselItemSpacing
import com.metrolist.shared.constants.GridScreenPadding
import com.metrolist.shared.constants.GridTileSpacing
import com.metrolist.shared.constants.currentGridThumbnailHeight
import com.metrolist.shared.constants.gridCaptionHeight
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.artist_play_all
import com.metrolist.shared.generated.resources.refreshing
import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.model.ArtistItem
import com.metrolist.shared.model.BrowseItem
import com.metrolist.shared.model.Chip
import com.metrolist.shared.model.EpisodeItem
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.model.PodcastItem
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.YTItem
import com.metrolist.shared.model.canonicalHomeItemId
import com.metrolist.shared.model.isVideoShelf
import com.metrolist.shared.settings.LibraryFilterType
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.settings.displayName
import com.metrolist.shared.ui.LocalIsTv
import com.metrolist.shared.ui.components.ContainedLoadingIndicator
import com.metrolist.shared.ui.components.DesktopHorizontalScrollControls
import com.metrolist.shared.ui.components.GenreCard
import com.metrolist.shared.ui.components.SectionTitle
import com.metrolist.shared.ui.components.SpeedDialGrid
import com.metrolist.shared.ui.components.VideoShelf
import com.metrolist.shared.ui.components.YTGridItem
import com.metrolist.shared.ui.components.YTListItem
import com.metrolist.shared.ui.components.carouselArtworkShape
import com.metrolist.shared.ui.components.rememberItemPlayback
import com.metrolist.shared.ui.utils.DragScrollableColumn
import com.metrolist.shared.ui.utils.carouselScroll
import com.metrolist.shared.ui.utils.desktopArrowScroll
import com.metrolist.shared.utils.formatDuration
import com.metrolist.shared.utils.isDesktop
import com.metrolist.shared.viewmodels.HomePaginationTrigger
import com.metrolist.shared.viewmodels.HomeViewModel.HomeUiState
import org.jetbrains.compose.resources.stringResource

enum class HomeShelfStyle {
    CAROUSEL,
    SONG_GRID,
    SPEED_DIAL,
    MOOD_AND_GENRES,
    SONG_LIST,
    ALBUM_CAROUSEL,
    SIMILAR_TO,
}

enum class HomeShelfHeaderAction {
    NONE,
    BROWSE,
    LOCAL,
    LIBRARY,
    SHUFFLE,
    PLAY_ALL,
}

data class HomeShelf(
    val key: String,
    val title: String,
    val items: List<YTItem>,
    val style: HomeShelfStyle,
    val headerAction: HomeShelfHeaderAction = HomeShelfHeaderAction.NONE,
    val rows: Int? = null,
    val browseId: String? = null,
    val params: String? = null,
    val speedDialPinnedIds: List<String>? = null,
    val speedDialSurprisePinned: Boolean? = null,
    val label: String? = null,
    val headerTitle: String? = null,
    val thumbnail: String? = null,
    val headerArtistId: String? = null,
    val visibilityKey: String = key,
    val compactRows: Boolean = false,
    val continuation: String? = null,
    val isPersonal: Boolean = false,
    val feedChip: Chip? = null,
    val chipTitle: String? = null,
)

data class HomeContentText(
    val moodsAndGenres: String,
    val speedDial: String,
    val newReleases: String,
    val similarTo: String,
    val podcastsForYou: String,
    val charts: String,
    val albumCharts: String,
    val editorialPlaylists: String,
    val recapPlaylists: String,
    val shuffle: String,
    val playAll: String,
    val keepListening: String,
    val quickPicks: String,
    val forgottenFavorites: String,
    val loading: String,
    val tryAgain: String,
    val homeUnavailable: String,
)

@Composable
fun HomeContent(
    uiState: HomeUiState,
    text: HomeContentText,
    onItemClick: (YTItem) -> Unit,
    onVideoItemClick: (YTItem) -> Unit = onItemClick,
    onItemLongClick: ((YTItem) -> Unit)?,
    onShuffleSongs: ((List<SongItem>) -> Unit)? = null,
    onLibraryClick: ((LibraryFilterType?) -> Unit)? = null,
    onLocalShelfClick: ((HomeShelf) -> Unit)? = null,
    onSpeedDialItemLongClick: ((YTItem) -> Unit)? = onItemLongClick,
    onSpeedDialItemSecondaryClick: ((YTItem) -> Unit)? = null,
    onArtistClick: ((String) -> Unit)? = null,
    onAlbumClick: ((String) -> Unit)? = null,
    onPlayAlbum: ((AlbumItem) -> Unit)? = null,
    currentSongId: State<String?>,
    isPlaying: State<Boolean>,
    isItemEnabled: (YTItem) -> Boolean = { true },
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    shelves: List<HomeShelf>? = null,
    onShowSurpriseMenu: (() -> Unit)? = null,
    onShowSurpriseSecondaryMenu: (() -> Unit)? = null,
    onLoadMore: (() -> Unit)? = null,
    isLandscape: Boolean = false,
    speedDialSurpriseAtEndOfFirstPage: Boolean = false,
    header: (LazyListScope.() -> Unit)? = null,
    shelfCache: HomeShelfCache? = null,
    onPlaySongs: ((List<SongItem>) -> Unit)? = null,
) {
    val activeSongId =
        remember(currentSongId) {
            derivedStateOf { currentSongId.value.takeUnless { Settings.shared.uiEnhancements } }
        }
    val localShelfCache = remember { HomeShelfCache() }
    val paginationScrollConnection = rememberHomePagination(uiState, listState, onLoadMore)
    BoxWithConstraints(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        val isWide = maxWidth > 840.dp
        val effectiveShelves =
            shelves ?: (shelfCache ?: localShelfCache).build(uiState, text, maxWidth)

        DragScrollableColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().nestedScroll(paginationScrollConnection).desktopArrowScroll(listState),
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(if (isWide) 16.dp else 8.dp),
        ) {
            header?.invoke(this)

            renderHomeShelves(
                shelves = effectiveShelves,
                shuffleLabel = text.shuffle,
                onItemClick = onItemClick,
                onVideoItemClick = onVideoItemClick,
                onItemLongClick = onItemLongClick,
                onShuffleSongs = onShuffleSongs,
                onPlaySongs = onPlaySongs,
                onLibraryClick = onLibraryClick,
                onLocalShelfClick = onLocalShelfClick,
                onSpeedDialItemLongClick = onSpeedDialItemLongClick,
                onSpeedDialItemSecondaryClick = onSpeedDialItemSecondaryClick,
                onArtistClick = onArtistClick,
                onAlbumClick = onAlbumClick,
                onPlayAlbum = onPlayAlbum,
                currentSongId = activeSongId,
                isPlaying = isPlaying,
                isItemEnabled = isItemEnabled,
                onShowSurpriseMenu = onShowSurpriseMenu,
                onShowSurpriseSecondaryMenu = onShowSurpriseSecondaryMenu,
                isLandscape = isLandscape,
                speedDialSurpriseAtEndOfFirstPage = speedDialSurpriseAtEndOfFirstPage,
                isWide = isWide,
            )

            if (uiState.isContentLoading || uiState.isLoadingMore) {
                item(key = "home_content_loading") {
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        ContainedLoadingIndicator(Modifier.semantics { contentDescription = text.loading })
                    }
                }
            }

            if (!uiState.isLoading && uiState.error.isNullOrBlank() && uiState.loadMoreError && onLoadMore != null) {
                item(key = "home_load_more_retry") {
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        TextButton(onClick = onLoadMore, shapes = ButtonDefaults.shapes()) { Text(text.tryAgain) }
                    }
                }
            }
        }

        HomeRevalidationIndicator(
            visible = uiState.isRevalidating,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = contentPadding.calculateTopPadding()),
        )
    }
}

@Composable
private fun rememberHomePagination(
    uiState: HomeUiState,
    listState: LazyListState,
    onLoadMore: (() -> Unit)?,
): NestedScrollConnection {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentState by rememberUpdatedState(uiState)
    val loadMore by rememberUpdatedState(onLoadMore)
    var scrollRevision by remember(listState) { mutableLongStateOf(0L) }
    val connection =
        remember(listState) {
            object : NestedScrollConnection {
                override fun onPreScroll(
                    available: Offset,
                    source: NestedScrollSource,
                ): Offset {
                    if (available.y < 0f) scrollRevision++
                    return Offset.Zero
                }
            }
        }

    // Desktop mouse dragging and arrow keys scroll the list directly, without nested scroll events.
    LaunchedEffect(listState) {
        if (!isDesktop) return@LaunchedEffect
        var previous = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }.collect { position ->
            if (position.first > previous.first || (position.first == previous.first && position.second > previous.second)) {
                scrollRevision++
            }
            previous = position
        }
    }

    LaunchedEffect(lifecycle, listState, uiState.selectedChip, uiState.isRefreshing) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val trigger = HomePaginationTrigger()
            snapshotFlow {
                val layout = listState.layoutInfo
                val nearEnd =
                    layout.visibleItemsInfo
                        .lastOrNull()
                        ?.index
                        ?.let { it >= layout.totalItemsCount - 3 } == true
                Triple(currentState, nearEnd, scrollRevision)
            }.collect { (state, nearEnd, revision) ->
                if (trigger.shouldLoad(state, nearEnd, revision)) loadMore?.invoke()
            }
        }
    }
    return connection
}

@Composable
private fun HomeRevalidationIndicator(
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    val refreshing = stringResource(Res.string.refreshing)
    val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val spatial = MaterialTheme.motionScheme.fastSpatialSpec<IntSize>()
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(effects) + expandVertically(spatial, expandFrom = Alignment.Top),
        exit = fadeOut(effects) + shrinkVertically(spatial, shrinkTowards = Alignment.Top),
    ) {
        ContainedLoadingIndicator(
            modifier =
                Modifier
                    .padding(8.dp)
                    .semantics {
                        contentDescription = refreshing
                        liveRegion = LiveRegionMode.Polite
                    },
        )
    }
}

@Composable
private fun HomeShelfTransition(
    shelf: HomeShelf,
    content: @Composable (HomeShelf) -> Unit,
) {
    val spatial = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    val sizeSpec = MaterialTheme.motionScheme.defaultSpatialSpec<IntSize>()
    val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val fastEffects = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    AnimatedContent(
        targetState = shelf,
        contentKey = { current -> current.items.map(YTItem::id) },
        transitionSpec = {
            (fadeIn(effects) + scaleIn(spatial, initialScale = 0.96f))
                .togetherWith(fadeOut(fastEffects))
                .using(SizeTransform(clip = false) { _, _ -> sizeSpec })
        },
        label = "homeShelf",
    ) { current ->
        content(current)
    }
}

fun LazyListScope.renderHomeShelves(
    shelves: List<HomeShelf>,
    shuffleLabel: String,
    onItemClick: (YTItem) -> Unit,
    onVideoItemClick: (YTItem) -> Unit = onItemClick,
    onItemLongClick: ((YTItem) -> Unit)?,
    onShuffleSongs: ((List<SongItem>) -> Unit)?,
    onLibraryClick: ((LibraryFilterType?) -> Unit)?,
    onLocalShelfClick: ((HomeShelf) -> Unit)? = null,
    onSpeedDialItemLongClick: ((YTItem) -> Unit)?,
    onSpeedDialItemSecondaryClick: ((YTItem) -> Unit)?,
    onArtistClick: ((String) -> Unit)? = null,
    onAlbumClick: ((String) -> Unit)? = null,
    onPlayAlbum: ((AlbumItem) -> Unit)? = null,
    currentSongId: State<String?>,
    isPlaying: State<Boolean>,
    isItemEnabled: (YTItem) -> Boolean = { true },
    onShowSurpriseMenu: (() -> Unit)? = null,
    onShowSurpriseSecondaryMenu: (() -> Unit)? = null,
    isLandscape: Boolean = false,
    speedDialSurpriseAtEndOfFirstPage: Boolean = false,
    isWide: Boolean = false,
    onPlaySongs: ((List<SongItem>) -> Unit)? = null,
) {
    shelves.forEach { shelf ->
        shelf.chipTitle?.let { title ->
            item(key = "${shelf.key}_chip_header", contentType = "home_chip_header") {
                Box(
                    Modifier.fillMaxWidth().animateItem(
                        fadeInSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
                        placementSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
                        fadeOutSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
                    ),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    Column(
                        Modifier
                            .widthIn(max = 1400.dp)
                            .fillMaxWidth()
                            .padding(horizontal = if (isWide) 24.dp else 16.dp)
                            .padding(top = 16.dp, bottom = 8.dp),
                    ) {
                        HorizontalDivider()
                        Spacer(Modifier.height(16.dp))
                        Text(
                            title,
                            style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
        if (shelf.style == HomeShelfStyle.SONG_LIST && !shelf.items.isVideoShelf()) {
            item(key = shelf.key, contentType = "home_list_header") {
                Box(
                    Modifier.fillMaxWidth().animateItem(
                        fadeInSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
                        placementSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
                        fadeOutSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
                    ),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    Box(Modifier.widthIn(max = 1400.dp).fillMaxWidth()) {
                        HomeShelfHeader(
                            shelf,
                            shuffleLabel,
                            onItemClick,
                            onShuffleSongs,
                            onLibraryClick,
                            onLocalShelfClick,
                            isItemEnabled,
                            isWide,
                            onPlaySongs,
                        )
                    }
                }
            }
            itemsIndexed(
                items = shelf.items,
                key = { _, item -> "${shelf.key}_${canonicalHomeItemId(item)}" },
                contentType = { _, _ -> HomeShelfStyle.SONG_LIST },
            ) { index, item ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .animateItem(
                            fadeInSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
                            placementSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
                            fadeOutSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
                        ).padding(bottom = if (index == shelf.items.lastIndex) 16.dp else 0.dp),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    Box(Modifier.widthIn(max = 1400.dp).fillMaxWidth().padding(horizontal = if (isWide) 24.dp else 16.dp)) {
                        HomeListItem(
                            item,
                            onItemClick,
                            onItemLongClick,
                            onArtistClick,
                            onAlbumClick,
                            onPlayAlbum,
                            currentSongId,
                            isPlaying,
                            isItemEnabled,
                            isWide,
                        )
                    }
                }
            }
            return@forEach
        }
        item(key = shelf.key, contentType = shelf.style) {
            Box(
                modifier =
                    Modifier
                        .animateItem(
                            fadeInSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
                            placementSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
                            fadeOutSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
                        ).fillMaxWidth()
                        .padding(bottom = if (isWide) 24.dp else 16.dp),
                contentAlignment = Alignment.TopCenter,
            ) {
                Box(modifier = if (isWide) Modifier.widthIn(max = 1400.dp).fillMaxWidth() else Modifier.fillMaxWidth()) {
                    HomeShelfTransition(shelf) { current ->
                        DefaultHomeShelf(
                            shelf = current,
                            shuffleLabel = shuffleLabel,
                            onItemClick = onItemClick,
                            onVideoItemClick = onVideoItemClick,
                            onItemLongClick = onItemLongClick,
                            onShuffleSongs = onShuffleSongs,
                            onPlaySongs = onPlaySongs,
                            onLibraryClick = onLibraryClick,
                            onLocalShelfClick = onLocalShelfClick,
                            onSpeedDialItemLongClick = onSpeedDialItemLongClick,
                            onSpeedDialItemSecondaryClick = onSpeedDialItemSecondaryClick,
                            onArtistClick = onArtistClick,
                            onAlbumClick = onAlbumClick,
                            onPlayAlbum = onPlayAlbum,
                            currentSongId = currentSongId,
                            isPlaying = isPlaying,
                            isItemEnabled = isItemEnabled,
                            onShowSurpriseMenu = onShowSurpriseMenu,
                            onShowSurpriseSecondaryMenu = onShowSurpriseSecondaryMenu,
                            isLandscape = isLandscape,
                            speedDialSurpriseAtEndOfFirstPage = speedDialSurpriseAtEndOfFirstPage,
                            isWide = isWide,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun DefaultHomeShelf(
    shelf: HomeShelf,
    shuffleLabel: String,
    onItemClick: (YTItem) -> Unit,
    onVideoItemClick: (YTItem) -> Unit = onItemClick,
    onItemLongClick: ((YTItem) -> Unit)?,
    onShuffleSongs: ((List<SongItem>) -> Unit)?,
    onLibraryClick: ((LibraryFilterType?) -> Unit)?,
    onLocalShelfClick: ((HomeShelf) -> Unit)? = null,
    onSpeedDialItemLongClick: ((YTItem) -> Unit)?,
    onSpeedDialItemSecondaryClick: ((YTItem) -> Unit)?,
    onArtistClick: ((String) -> Unit)? = null,
    onAlbumClick: ((String) -> Unit)? = null,
    onPlayAlbum: ((AlbumItem) -> Unit)? = null,
    currentSongId: State<String?>,
    isPlaying: State<Boolean>,
    isItemEnabled: (YTItem) -> Boolean = { true },
    onShowSurpriseMenu: (() -> Unit)? = null,
    onShowSurpriseSecondaryMenu: (() -> Unit)? = null,
    isLandscape: Boolean = false,
    speedDialSurpriseAtEndOfFirstPage: Boolean = false,
    isWide: Boolean = false,
    onPlaySongs: ((List<SongItem>) -> Unit)? = null,
) {
    val isVideoShelf = shelf.style != HomeShelfStyle.SPEED_DIAL && !shelf.compactRows && shelf.items.isVideoShelf()
    Column(Modifier.fillMaxWidth()) {
        HomeShelfHeader(
            shelf,
            shuffleLabel,
            if (isVideoShelf) onVideoItemClick else onItemClick,
            onShuffleSongs,
            onLibraryClick,
            onLocalShelfClick,
            isItemEnabled,
            isWide,
            onPlaySongs,
        )

        if (isVideoShelf) {
            val state = rememberCarouselState { shelf.items.size }
            DesktopHorizontalScrollControls(state) {
                VideoShelf(
                    items = shelf.items,
                    showDurationBadge = true,
                    onItemClick = onVideoItemClick,
                    onItemLongClick = onItemLongClick,
                    isWide = isWide,
                    currentSongId = currentSongId,
                    isPlaying = isPlaying,
                    isItemEnabled = isItemEnabled,
                    onArtistClick = onArtistClick,
                    onAlbumClick = onAlbumClick,
                    state = state,
                )
            }
            return@Column
        }
        when (shelf.style) {
            HomeShelfStyle.CAROUSEL, HomeShelfStyle.ALBUM_CAROUSEL, HomeShelfStyle.SIMILAR_TO -> {
                HomeCarouselShelf(
                    shelf,
                    onItemClick,
                    onItemLongClick,
                    onArtistClick,
                    onAlbumClick,
                    onPlayAlbum,
                    currentSongId,
                    isPlaying,
                    isItemEnabled,
                    isWide,
                )
            }

            HomeShelfStyle.SPEED_DIAL -> {
                HomeSpeedDialShelf(
                    shelf,
                    onItemClick,
                    onSpeedDialItemLongClick,
                    onSpeedDialItemSecondaryClick,
                    onShowSurpriseMenu,
                    onShowSurpriseSecondaryMenu,
                    currentSongId,
                    isPlaying,
                    isItemEnabled,
                    isLandscape,
                    speedDialSurpriseAtEndOfFirstPage,
                    isWide,
                )
            }

            HomeShelfStyle.SONG_GRID -> {
                HomeGridShelf(
                    shelf,
                    onItemClick,
                    onItemLongClick,
                    onArtistClick,
                    onAlbumClick,
                    onPlayAlbum,
                    currentSongId,
                    isPlaying,
                    isItemEnabled,
                    isWide,
                )
            }

            HomeShelfStyle.MOOD_AND_GENRES -> {
                HomeMoodsShelf(shelf, onItemClick, isItemEnabled, isWide)
            }

            HomeShelfStyle.SONG_LIST -> {
                HomeListShelf(
                    shelf,
                    onItemClick,
                    onItemLongClick,
                    onArtistClick,
                    onAlbumClick,
                    onPlayAlbum,
                    currentSongId,
                    isPlaying,
                    isItemEnabled,
                    isWide,
                )
            }
        }
    }
}

@Composable
private fun HomeShelfHeader(
    shelf: HomeShelf,
    shuffleLabel: String,
    onItemClick: (YTItem) -> Unit,
    onShuffleSongs: ((List<SongItem>) -> Unit)?,
    onLibraryClick: ((LibraryFilterType?) -> Unit)?,
    onLocalShelfClick: ((HomeShelf) -> Unit)?,
    isItemEnabled: (YTItem) -> Boolean,
    isWide: Boolean,
    onPlaySongs: ((List<SongItem>) -> Unit)?,
) {
    val songs = shelf.items.filterIsInstance<SongItem>().filter(isItemEnabled)
    val isLibraryAlbums = shelf.headerAction == HomeShelfHeaderAction.LIBRARY && shelf.browseId?.startsWith("FEmusic_library") == true
    SectionTitle(
        title = shelf.headerTitle ?: shelf.title,
        label = shelf.label,
        thumbnailUrl = shelf.thumbnail,
        isArtistEndpoint = shelf.headerArtistId != null,
        artworkSeed = shelf.headerArtistId.orEmpty(),
        modifier = Modifier.padding(horizontal = if (isWide) 24.dp else 16.dp),
        onClick =
            if (shelf.items.isNotEmpty() || shelf.headerAction == HomeShelfHeaderAction.BROWSE || isLibraryAlbums) {
                when (shelf.headerNavigationAction(onLocalShelfClick != null)) {
                    HomeShelfHeaderAction.BROWSE -> {
                        shelf.browseId?.takeIf(String::isNotBlank)?.let { id ->
                            { onItemClick(BrowseItem(id, shelf.title, params = shelf.params)) }
                        }
                    }

                    HomeShelfHeaderAction.LIBRARY -> {
                        onLibraryClick?.let { click -> { click(LibraryFilterType.ALBUMS.takeIf { isLibraryAlbums }) } }
                    }

                    HomeShelfHeaderAction.LOCAL -> {
                        onLocalShelfClick?.let { click -> { click(shelf) } }
                    }

                    else -> {
                        null
                    }
                }
            } else {
                null
            },
        playAllLabel = stringResource(Res.string.artist_play_all),
        onPlayAllClick =
            onPlaySongs?.takeIf { shelf.headerAction == HomeShelfHeaderAction.PLAY_ALL && songs.isNotEmpty() }?.let { play ->
                { play(songs) }
            },
        shuffleLabel = shuffleLabel,
        onShuffleClick =
            onShuffleSongs?.takeIf { shelf.headerAction == HomeShelfHeaderAction.SHUFFLE && songs.isNotEmpty() }?.let { shuffle ->
                { shuffle(songs.shuffled()) }
            },
        isWide = isWide,
    )
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun HomeCarouselShelf(
    shelf: HomeShelf,
    onItemClick: (YTItem) -> Unit,
    onItemLongClick: ((YTItem) -> Unit)?,
    onArtistClick: ((String) -> Unit)? = null,
    onAlbumClick: ((String) -> Unit)? = null,
    onPlayAlbum: ((AlbumItem) -> Unit)? = null,
    currentSongId: State<String?>,
    isPlaying: State<Boolean>,
    isItemEnabled: (YTItem) -> Boolean,
    isWide: Boolean = false,
) {
    val imageSize = CarouselItemSize
    val state = rememberCarouselState { shelf.items.size }
    DesktopHorizontalScrollControls(state) {
        // Material 3 1.13 defaults to one item per fling; keep momentum with snapping.
        val carouselFling = CarouselDefaults.multiBrowseFlingBehavior(state)
        HorizontalMultiBrowseCarousel(
            state = state,
            flingBehavior = carouselFling,
            preferredItemWidth = imageSize,
            itemSpacing = CarouselItemSpacing,
            contentPadding = PaddingValues(0.dp),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = if (isWide) 24.dp else 16.dp)
                    .clip(RoundedCornerShape(CarouselItemCornerRadius))
                    .carouselScroll(state, carouselFling),
        ) { index ->
            val item = shelf.items[index]
            CarouselItem(
                item,
                imageSize,
                imageSize,
                isWide,
                isItemEnabled,
                onItemClick,
                onItemLongClick,
                onArtistClick,
                onAlbumClick,
                onPlayAlbum,
                currentSongId,
                isPlaying,
                carouselItemDrawInfo,
                Modifier.maskClip(carouselArtworkShape(item)),
            )
        }
    }
}

@Composable
private fun CarouselItem(
    item: YTItem,
    itemWidth: Dp,
    imageSize: Dp,
    isWide: Boolean,
    isItemEnabled: (YTItem) -> Boolean,
    onItemClick: (YTItem) -> Unit,
    onItemLongClick: ((YTItem) -> Unit)?,
    onArtistClick: ((String) -> Unit)?,
    onAlbumClick: ((String) -> Unit)?,
    onPlayAlbum: ((AlbumItem) -> Unit)?,
    currentSongId: State<String?>,
    isPlaying: State<Boolean>,
    carouselItemDrawInfo: CarouselItemDrawInfo? = null,
    artworkModifier: Modifier = Modifier,
) {
    val playback by rememberItemPlayback(item.id, currentSongId, isPlaying)
    Box((if (carouselItemDrawInfo == null) Modifier.width(itemWidth) else Modifier.fillMaxWidth()).clipToBounds()) {
        val enabled = !item.isUnavailable && isItemEnabled(item)
        YTGridItem(
            item = item,
            subtitleOverride = homeCardSubtitle(item),
            showDurationBadge = item is EpisodeItem,
            onClick = { onItemClick(item) },
            onLongClick = onItemLongClick?.let { handler -> { handler(item) } },
            onShowMenu = onItemLongClick,
            onArtistClick = onArtistClick.takeIf { enabled },
            onAlbumClick = onAlbumClick.takeIf { enabled },
            onAlbumPlay = onPlayAlbum?.let { play -> { (item as? AlbumItem)?.let(play) } },
            isCurrent = playback.isCurrent,
            isPlaying = playback.isPlaying,
            enabled = enabled,
            imageSize = imageSize,
            fillWidth = carouselItemDrawInfo != null,
            squareArtwork = carouselItemDrawInfo != null,
            artworkModifier = artworkModifier,
            carouselItemDrawInfo = carouselItemDrawInfo,
            contentPadding = 0.dp,
            largeText = isWide,
            clickableMetadata = isWide && !LocalIsTv.current,
        )
    }
}

@Composable
private fun HomeGridShelf(
    shelf: HomeShelf,
    onItemClick: (YTItem) -> Unit,
    onItemLongClick: ((YTItem) -> Unit)?,
    onArtistClick: ((String) -> Unit)? = null,
    onAlbumClick: ((String) -> Unit)? = null,
    onPlayAlbum: ((AlbumItem) -> Unit)? = null,
    currentSongId: State<String?>,
    isPlaying: State<Boolean>,
    isItemEnabled: (YTItem) -> Boolean,
    isWide: Boolean = false,
) {
    val rows = shelf.rows ?: 2
    val useCards = rows == 2 && !shelf.compactRows
    if (useCards && shelf.items.any { it is AlbumItem || it is PlaylistItem || it is ArtistItem }) {
        HomeCarouselShelf(
            shelf,
            onItemClick,
            onItemLongClick,
            onArtistClick,
            onAlbumClick,
            onPlayAlbum,
            currentSongId,
            isPlaying,
            isItemEnabled,
            isWide,
        )
        return
    }
    val imageSize = if (isWide) 180.dp else currentGridThumbnailHeight()
    val rowHeight =
        if (useCards) {
            imageSize + gridCaptionHeight(isWide)
        } else {
            maxOf(48.dp, with(LocalDensity.current) { 36.sp.toDp() })
        }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val itemWidth =
            if (useCards) {
                imageSize
            } else if (isWide) {
                420.dp
            } else {
                (maxWidth - 66.dp).coerceIn(240.dp, 346.dp)
            }
        HomeGallery(
            shelf,
            rows,
            itemWidth,
            rowHeight,
            if (useCards) (if (isWide) 10.dp else GridTileSpacing) else 18.dp,
            isWide = isWide,
        ) { _, item ->
            if (useCards) {
                CarouselItem(
                    item,
                    itemWidth,
                    imageSize,
                    isWide,
                    isItemEnabled,
                    onItemClick,
                    onItemLongClick,
                    onArtistClick,
                    onAlbumClick,
                    onPlayAlbum,
                    currentSongId,
                    isPlaying,
                )
            } else {
                GridShelfItem(
                    item,
                    itemWidth,
                    isWide,
                    isItemEnabled,
                    onItemClick,
                    onItemLongClick,
                    onArtistClick,
                    onAlbumClick,
                    onPlayAlbum,
                    currentSongId,
                    isPlaying,
                )
            }
        }
    }
}

@Composable
private fun GridShelfItem(
    item: YTItem,
    itemWidth: Dp,
    isWide: Boolean,
    isItemEnabled: (YTItem) -> Boolean,
    onItemClick: (YTItem) -> Unit,
    onItemLongClick: ((YTItem) -> Unit)?,
    onArtistClick: ((String) -> Unit)?,
    onAlbumClick: ((String) -> Unit)?,
    onPlayAlbum: ((AlbumItem) -> Unit)? = null,
    currentSongId: State<String?>,
    isPlaying: State<Boolean>,
) {
    val playback by rememberItemPlayback(item.id, currentSongId, isPlaying)
    Box(Modifier.width(itemWidth).clipToBounds()) {
        val enabled = !item.isUnavailable && isItemEnabled(item)
        YTListItem(
            item = item,
            onClick = { onItemClick(item) },
            onLongClick = onItemLongClick?.let { handler -> { handler(item) } },
            onShowMenu = onItemLongClick?.let { handler -> { handler(item) } },
            contentPadding = PaddingValues(0.dp),
            onArtistClick = onArtistClick.takeIf { enabled },
            onAlbumClick = onAlbumClick.takeIf { enabled },
            onAlbumPlay = onPlayAlbum?.let { play -> { (item as? AlbumItem)?.let(play) } },
            isCurrent = playback.isCurrent,
            isPlaying = playback.isPlaying,
            enabled = enabled,
            clickableMetadata = isWide && !LocalIsTv.current,
        )
    }
}

@Composable
private fun HomeSpeedDialShelf(
    shelf: HomeShelf,
    onItemClick: (YTItem) -> Unit,
    onShowSpeedDialMenu: ((YTItem) -> Unit)?,
    onShowSpeedDialSecondaryMenu: ((YTItem) -> Unit)?,
    onShowSurpriseMenu: (() -> Unit)? = null,
    onShowSurpriseSecondaryMenu: (() -> Unit)? = null,
    currentSongId: State<String?>,
    isPlaying: State<Boolean>,
    isItemEnabled: (YTItem) -> Boolean,
    isLandscape: Boolean = false,
    speedDialSurpriseAtEndOfFirstPage: Boolean = false,
    isWide: Boolean = false,
) {
    val horizontalPadding = if (isWide) 24.dp else 16.dp
    val gridState = rememberLazyGridState()
    DesktopHorizontalScrollControls(gridState) {
        SpeedDialGrid(
            items = shelf.items,
            pinnedIds = shelf.speedDialPinnedIds ?: emptyList(),
            surprisePinned = shelf.speedDialSurprisePinned ?: false,
            onItemClick = onItemClick,
            onShowSpeedDialMenu = onShowSpeedDialMenu,
            onShowSpeedDialSecondaryMenu = onShowSpeedDialSecondaryMenu,
            onShowSurpriseMenu = onShowSurpriseMenu,
            onShowSurpriseSecondaryMenu = onShowSurpriseSecondaryMenu,
            currentSongId = currentSongId,
            isPlaying = isPlaying,
            isItemEnabled = isItemEnabled,
            isLandscape = isLandscape,
            surpriseAtEndOfFirstPage = speedDialSurpriseAtEndOfFirstPage,
            modifier = if (isLandscape) Modifier else Modifier.padding(horizontal = horizontalPadding),
            contentPadding = if (isLandscape) PaddingValues(horizontal = horizontalPadding) else PaddingValues(0.dp),
            landscapeMaxItemSize = if (isWide) 170.dp else 136.dp,
            gridState = gridState,
        )
    }
}

@Composable
private fun HomeMoodsShelf(
    shelf: HomeShelf,
    onItemClick: (YTItem) -> Unit,
    isItemEnabled: (YTItem) -> Boolean,
    isWide: Boolean = false,
) {
    val cardWidth = if (isWide) 240.dp else 185.dp
    HomeGallery(shelf, 4, cardWidth, 52.dp, 5.dp, rowSpacing = 5.dp, isWide = isWide) { index, item ->
        MoodsItem(item, index, shelf.items.size, cardWidth, isItemEnabled, onItemClick)
    }
}

@Composable
private fun HomeGallery(
    shelf: HomeShelf,
    rows: Int,
    itemWidth: Dp,
    rowHeight: Dp,
    pageSpacing: Dp,
    rowSpacing: Dp = 8.dp,
    isWide: Boolean,
    content: @Composable (Int, YTItem) -> Unit,
) {
    val pageCount = (shelf.items.size + rows - 1) / rows
    val galleryModifier =
        Modifier.fillMaxWidth().height(rowHeight * rows + rowSpacing * (rows - 1)).clipToBounds()
    val contentPadding = PaddingValues(horizontal = if (isWide) 24.dp else GridScreenPadding)
    val pageContent: @Composable (Int) -> Unit = { page ->
        Column(Modifier.width(itemWidth), verticalArrangement = Arrangement.spacedBy(rowSpacing)) {
            repeat(rows) { row ->
                val index = page * rows + row
                shelf.items.getOrNull(index)?.let { item ->
                    Box(Modifier.fillMaxWidth().height(rowHeight).clipToBounds()) {
                        content(index, item)
                    }
                }
            }
        }
    }
    if (isDesktop) {
        val state = rememberLazyListState()
        DesktopHorizontalScrollControls(state) {
            LazyRow(
                state = state,
                horizontalArrangement = Arrangement.spacedBy(pageSpacing),
                contentPadding = contentPadding,
                modifier = galleryModifier.carouselScroll(state),
            ) {
                items(pageCount, key = { page -> shelf.items[page * rows].homeKey(shelf.key, page * rows) }) { page ->
                    pageContent(page)
                }
            }
        }
        return
    }
    val state = rememberPagerState { pageCount }
    val flingBehavior = PagerDefaults.flingBehavior(state, pagerSnapDistance = PagerSnapDistance.atMost(pageCount))
    HorizontalPager(
        state = state,
        pageSize = PageSize.Fixed(itemWidth),
        pageSpacing = pageSpacing,
        flingBehavior = flingBehavior,
        key = { page -> shelf.items[page * rows].homeKey(shelf.key, page * rows) },
        verticalAlignment = Alignment.Top,
        contentPadding = contentPadding,
        modifier = galleryModifier.carouselScroll(state, flingBehavior),
    ) { page ->
        pageContent(page)
    }
}

@Composable
private fun MoodsItem(
    item: YTItem,
    index: Int,
    count: Int,
    cardWidth: Dp,
    isItemEnabled: (YTItem) -> Boolean,
    onItemClick: (YTItem) -> Unit,
) {
    GenreCard(
        title = item.title,
        onClick = { if (isItemEnabled(item)) onItemClick(item) },
        shape =
            RoundedCornerShape(
                topStart = if (index == 0) 12.dp else 5.dp,
                topEnd = if (index == (count - 1) / 4 * 4) 12.dp else 5.dp,
                bottomStart = if (index == minOf(3, count - 1)) 12.dp else 5.dp,
                bottomEnd = if (index == count - 1) 12.dp else 5.dp,
            ),
        modifier = Modifier.width(cardWidth),
    )
}

private fun YTItem.homeKey(
    shelfKey: String,
    index: Int,
): String =
    when (this) {
        is SongItem -> "song_${shelfKey}_${id}_$index"
        is BrowseItem -> "browse_${shelfKey}_${id}_${params.orEmpty()}_$index"
        else -> "item_${shelfKey}_${id}_$index"
    }

@Composable
private fun HomeListShelf(
    shelf: HomeShelf,
    onItemClick: (YTItem) -> Unit,
    onItemLongClick: ((YTItem) -> Unit)?,
    onArtistClick: ((String) -> Unit)? = null,
    onAlbumClick: ((String) -> Unit)? = null,
    onPlayAlbum: ((AlbumItem) -> Unit)? = null,
    currentSongId: State<String?>,
    isPlaying: State<Boolean>,
    isItemEnabled: (YTItem) -> Boolean,
    isWide: Boolean = false,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = if (isWide) 24.dp else 16.dp),
        verticalArrangement = Arrangement.spacedBy(if (isWide) 8.dp else 4.dp),
    ) {
        shelf.items.forEach { item ->
            HomeListItem(
                item,
                onItemClick,
                onItemLongClick,
                onArtistClick,
                onAlbumClick,
                onPlayAlbum,
                currentSongId,
                isPlaying,
                isItemEnabled,
                isWide,
            )
        }
    }
}

@Composable
private fun HomeListItem(
    item: YTItem,
    onItemClick: (YTItem) -> Unit,
    onItemLongClick: ((YTItem) -> Unit)?,
    onArtistClick: ((String) -> Unit)?,
    onAlbumClick: ((String) -> Unit)?,
    onPlayAlbum: ((AlbumItem) -> Unit)?,
    currentSongId: State<String?>,
    isPlaying: State<Boolean>,
    isItemEnabled: (YTItem) -> Boolean,
    isWide: Boolean,
) {
    val playback by rememberItemPlayback(item.id, currentSongId, isPlaying)
    val enabled = !item.isUnavailable && isItemEnabled(item)
    YTListItem(
        item = item,
        onClick = { onItemClick(item) },
        onLongClick = onItemLongClick?.let { handler -> { handler(item) } },
        onShowMenu = onItemLongClick?.let { handler -> { handler(item) } },
        onArtistClick = onArtistClick.takeIf { enabled },
        onAlbumClick = onAlbumClick.takeIf { enabled },
        onAlbumPlay = onPlayAlbum?.let { play -> { (item as? AlbumItem)?.let(play) } },
        isCurrent = playback.isCurrent,
        isPlaying = playback.isPlaying,
        enabled = enabled,
        clickableMetadata = isWide && !LocalIsTv.current,
    )
}

internal fun homeCardSubtitle(item: YTItem): String? =
    when (item) {
        is PlaylistItem -> {
            item.subtitle
        }

        is PodcastItem -> {
            listOfNotNull(item.author, item.episodeCount).joinToString(" • ")
        }

        is EpisodeItem -> {
            listOfNotNull(
                item.author?.displayName,
                item.subtitle ?: listOfNotNull(item.publishDateText, item.viewCountText).joinToString(" • ").takeIf(String::isNotBlank),
                item.durationText ?: item.duration?.let { formatDuration(it * 1000) },
            ).distinct().joinToString(" • ")
        }

        else -> {
            null
        }
    }?.takeIf(String::isNotBlank)
