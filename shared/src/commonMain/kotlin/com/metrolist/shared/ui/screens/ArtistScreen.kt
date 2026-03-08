@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.shared.ui.screens

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilledTonalToggleButton
import androidx.compose.material3.FilledTonalToggleButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.carousel.CarouselDefaults
import androidx.compose.material3.carousel.HorizontalMultiBrowseCarousel
import androidx.compose.material3.carousel.rememberCarouselState
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metrolist.shared.constants.CarouselItemCornerRadius
import com.metrolist.shared.constants.CarouselItemSize
import com.metrolist.shared.constants.CarouselItemSpacing
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.about
import com.metrolist.shared.generated.resources.action_like
import com.metrolist.shared.generated.resources.album_type
import com.metrolist.shared.generated.resources.artist_actions
import com.metrolist.shared.generated.resources.artist_latest_release
import com.metrolist.shared.generated.resources.artist_play_all
import com.metrolist.shared.generated.resources.artist_refresh
import com.metrolist.shared.generated.resources.artist_subscriber_count
import com.metrolist.shared.generated.resources.back
import com.metrolist.shared.generated.resources.downloaded
import com.metrolist.shared.generated.resources.error_occurred
import com.metrolist.shared.generated.resources.error_unknown
import com.metrolist.shared.generated.resources.loading
import com.metrolist.shared.generated.resources.monthly_audience_short
import com.metrolist.shared.generated.resources.more_options
import com.metrolist.shared.generated.resources.play
import com.metrolist.shared.generated.resources.radio
import com.metrolist.shared.generated.resources.retry
import com.metrolist.shared.generated.resources.see_all
import com.metrolist.shared.generated.resources.show_less
import com.metrolist.shared.generated.resources.show_more
import com.metrolist.shared.generated.resources.shuffle
import com.metrolist.shared.generated.resources.song_count
import com.metrolist.shared.generated.resources.songs
import com.metrolist.shared.generated.resources.subscribe
import com.metrolist.shared.generated.resources.subscribed
import com.metrolist.shared.generated.resources.top_tracks
import com.metrolist.shared.generated.resources.unblock
import com.metrolist.shared.generated.resources.unlike
import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.model.ArtistItem
import com.metrolist.shared.model.ArtistPageResult
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.YTItem
import com.metrolist.shared.model.isVideoShelf
import com.metrolist.shared.model.previewAspectRatio
import com.metrolist.shared.model.previewThumbnailUrl
import com.metrolist.shared.settings.displayName
import com.metrolist.shared.settings.displayTitle
import com.metrolist.shared.ui.components.BlurredArtworkBackground
import com.metrolist.shared.ui.components.CollectionPreviews
import com.metrolist.shared.ui.components.ExpandableText
import com.metrolist.shared.ui.components.ExplicitBadge
import com.metrolist.shared.ui.components.FittingText
import com.metrolist.shared.ui.components.ListItemTitle
import com.metrolist.shared.ui.components.LocalDownloadedSongIds
import com.metrolist.shared.ui.components.RefreshIndicator
import com.metrolist.shared.ui.components.SectionTitle
import com.metrolist.shared.ui.components.SubscribeConfetti
import com.metrolist.shared.ui.components.VideoShelf
import com.metrolist.shared.ui.components.YTGridItem
import com.metrolist.shared.ui.components.YTListItem
import com.metrolist.shared.ui.components.animateCurrentSongColors
import com.metrolist.shared.ui.components.animatedCurrentSongArtworkShape
import com.metrolist.shared.ui.components.carouselArtworkShape
import com.metrolist.shared.ui.components.currentSongSemantics
import com.metrolist.shared.ui.components.images.AsyncImage
import com.metrolist.shared.ui.components.loadingShimmer
import com.metrolist.shared.ui.components.rememberCurrentSongArtworkRotation
import com.metrolist.shared.ui.components.squareArtworkSlot
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.icons.SongStatusIcons
import com.metrolist.shared.ui.menu.artworkMenuActions
import com.metrolist.shared.ui.menu.onSecondaryClick
import com.metrolist.shared.ui.navigation.AlbumArtworkSource
import com.metrolist.shared.ui.navigation.LocalNavSharedTransitionScope
import com.metrolist.shared.ui.navigation.navigationPageSource
import com.metrolist.shared.ui.navigation.navigationPopupSource
import com.metrolist.shared.ui.theme.uiEnhanced
import com.metrolist.shared.ui.utils.DragScrollableColumn
import com.metrolist.shared.ui.utils.carouselScroll
import com.metrolist.shared.ui.utils.desktopArrowScroll
import com.metrolist.shared.utils.formatDuration
import com.metrolist.shared.utils.highResolutionArtworkUrl
import com.metrolist.shared.utils.isPullToRefreshSupported
import com.metrolist.shared.utils.monthlyAudienceText
import com.metrolist.shared.utils.withoutPlaybackCountLabel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

@Composable
fun ArtistScreen(
    artist: ArtistItem?,
    artistId: String,
    initialArtistName: String? = null,
    currentSongId: String? = null,
    isPlaying: Boolean = false,
    onItemsVisible: ((List<YTItem>) -> Unit)? = null,
    sections: Map<String, List<YTItem>>,
    sectionBrowseIds: Map<String, Pair<String?, String?>> = emptyMap(),
    loadableSections: Set<String> = emptySet(),
    sectionContinuation: (@Composable (String) -> Unit)? = null,
    isLoading: Boolean,
    errorMessage: String?,
    onRefresh: () -> Unit,
    onItemClick: (YTItem) -> Unit,
    onVideoItemClick: (YTItem) -> Unit = onItemClick,
    onAlbumArtworkClick: ((AlbumItem, String) -> Unit)? = null,
    onItemLongClick: (YTItem) -> Unit,
    songContainer: @Composable (SongItem, @Composable () -> Unit) -> Unit = { _, content -> content() },
    onSectionClick: ((String, String?, String) -> Unit)? = null,
    onVideoSectionClick: ((String, String?, String) -> Unit)? = onSectionClick,
    onPlayItem: ((YTItem) -> Unit)? = null,
    isSongLiked: ((String) -> Boolean)? = null,
    onLikeSong: ((SongItem) -> Unit)? = null,
    onBackClick: (() -> Unit)?,
    onMenuClick: (() -> Unit)? = null,
    onPlayAllClick: () -> Unit,
    onShuffleClick: () -> Unit,
    onMixClick: () -> Unit,
    onRefreshClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    listState: LazyListState? = null,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    headerWindowInsets: WindowInsets = WindowInsets.statusBars,
    isSubscribed: Boolean = false,
    subscribeSuccessCount: Int = 0,
    showSubscribe: Boolean = false,
    onSubscribeClick: () -> Unit = {},
    onUnblockClick: () -> Unit = {},
    description: String? = artist?.description,
    aboutImageUrl: String? = artist?.banner ?: artist?.thumbnail,
    onAboutClick: (() -> Unit)? = null,
    headerVideoLayer: (@Composable (Modifier) -> Unit)? = null,
    onAppBarVisibleChange: (Boolean) -> Unit = {},
    overlay: @Composable () -> Unit = {},
) {
    val initialArtist =
        remember(artistId, initialArtistName) {
            CollectionPreviews.artist(artistId) ?: ArtistItem(artistId, initialArtistName.orEmpty(), null)
        }
    val state = listState ?: rememberLazyListState()
    Box(modifier.fillMaxSize()) {
        ArtistScreenLayout(
            isLoading = isLoading,
            hasContent = artist != null,
            errorMessage = errorMessage,
            onRefresh = onRefresh,
            onBackClick = onBackClick,
        ) {
            ArtistScreenContent(
                artist = artist ?: initialArtist,
                isPlaceholder = artist == null,
                currentSongId = currentSongId,
                isPlaying = isPlaying,
                onItemsVisible = onItemsVisible,
                sections = sections,
                sectionBrowseIds = sectionBrowseIds,
                loadableSections = loadableSections,
                sectionContinuation = sectionContinuation,
                onItemClick = onItemClick,
                onVideoItemClick = onVideoItemClick,
                onAlbumArtworkClick = onAlbumArtworkClick,
                onItemLongClick = onItemLongClick,
                songContainer = songContainer,
                onSectionClick = onSectionClick,
                onVideoSectionClick = onVideoSectionClick,
                onPlayItem = onPlayItem,
                isSongLiked = isSongLiked,
                onLikeSong = onLikeSong,
                onBackClick = onBackClick,
                onMenuClick = onMenuClick,
                onPlayAllClick = onPlayAllClick,
                onShuffleClick = onShuffleClick,
                onMixClick = onMixClick,
                onRefreshClick = onRefreshClick,
                listState = state,
                contentPadding = contentPadding,
                headerWindowInsets = headerWindowInsets,
                isSubscribed = isSubscribed,
                subscribeSuccessCount = subscribeSuccessCount,
                showSubscribe = showSubscribe,
                onSubscribeClick = onSubscribeClick,
                onUnblockClick = onUnblockClick,
                description = description,
                aboutImageUrl = aboutImageUrl,
                onAboutClick = onAboutClick,
                headerVideoLayer = headerVideoLayer,
                onAppBarVisibleChange = onAppBarVisibleChange,
            )
        }
        overlay()
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ArtistScreenLayout(
    isLoading: Boolean,
    hasContent: Boolean,
    errorMessage: String?,
    onRefresh: () -> Unit,
    onBackClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier.fillMaxSize()) {
        if (hasContent && errorMessage != null) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp).windowInsetsPadding(WindowInsets.statusBars),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(errorMessage, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                TextButton(onClick = onRefresh, enabled = !isLoading, shapes = ButtonDefaults.shapes()) {
                    Text(stringResource(Res.string.retry))
                }
            }
        }
        if (!hasContent && !isLoading && onBackClick != null) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBackClick, shapes = IconButtonDefaults.shapes()) {
                    Icon(MetrolistIcons.AutoMirrored.Filled.ArrowBack, stringResource(Res.string.back))
                }
            }
        }
        val layoutBody = @Composable {
            when {
                !hasContent && !isLoading -> {
                    Column(
                        Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(stringResource(Res.string.error_occurred), color = MaterialTheme.colorScheme.error)
                        Text(errorMessage?.takeIf(String::isNotBlank) ?: stringResource(Res.string.error_unknown))
                        Button(onClick = onRefresh, shapes = ButtonDefaults.shapes()) { Text(stringResource(Res.string.retry)) }
                    }
                }

                else -> {
                    content()
                }
            }
        }

        if (isPullToRefreshSupported) {
            var userRefreshing by remember { mutableStateOf(false) }
            val isLoadingNow by rememberUpdatedState(isLoading && hasContent)
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
                    Box(Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.statusBars)) {
                        RefreshIndicator(
                            state = refreshState,
                            isRefreshing = userRefreshing,
                        )
                    }
                },
                modifier = Modifier.weight(1f),
            ) {
                layoutBody()
            }
        } else {
            Box(Modifier.weight(1f)) {
                layoutBody()
            }
        }
    }
}

@Composable
private fun ArtistScreenContent(
    currentSongId: String?,
    isPlaying: Boolean,
    onItemsVisible: ((List<YTItem>) -> Unit)?,
    artist: ArtistItem,
    isPlaceholder: Boolean,
    sections: Map<String, List<YTItem>>,
    sectionBrowseIds: Map<String, Pair<String?, String?>>,
    loadableSections: Set<String>,
    sectionContinuation: (@Composable (String) -> Unit)?,
    onItemClick: (YTItem) -> Unit,
    onVideoItemClick: (YTItem) -> Unit = onItemClick,
    onAlbumArtworkClick: ((AlbumItem, String) -> Unit)? = null,
    onItemLongClick: (YTItem) -> Unit,
    songContainer: @Composable (SongItem, @Composable () -> Unit) -> Unit,
    onSectionClick: ((String, String?, String) -> Unit)?,
    onVideoSectionClick: ((String, String?, String) -> Unit)? = onSectionClick,
    onPlayItem: ((YTItem) -> Unit)? = null,
    isSongLiked: ((String) -> Boolean)? = null,
    onLikeSong: ((SongItem) -> Unit)? = null,
    onBackClick: (() -> Unit)?,
    onMenuClick: (() -> Unit)?,
    onPlayAllClick: () -> Unit,
    onShuffleClick: () -> Unit,
    onMixClick: () -> Unit,
    onRefreshClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    headerWindowInsets: WindowInsets = WindowInsets.statusBars,
    isSubscribed: Boolean = false,
    subscribeSuccessCount: Int = 0,
    showSubscribe: Boolean = false,
    onSubscribeClick: () -> Unit = {},
    onUnblockClick: () -> Unit = {},
    description: String? = artist.description,
    aboutImageUrl: String? = artist.banner ?: artist.thumbnail,
    onAboutClick: (() -> Unit)? = null,
    headerVideoLayer: (@Composable (Modifier) -> Unit)? = null,
    onAppBarVisibleChange: (Boolean) -> Unit = {},
) {
    val page = remember(sections, sectionBrowseIds) { ArtistPageResult(sections, sectionBrowseIds) }
    val loadingText = stringResource(Res.string.loading)
    val topTracksSection = remember(page) { page.topTracksSection }
    val videoSections = remember(page) { page.videoSections }
    val librarySection = remember(page) { page.librarySection }
    BoxWithConstraints(
        modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.TopCenter,
    ) {
        val isWide = maxWidth >= 960.dp
        val isMedium = maxWidth >= 600.dp && !isWide
        // Phones show the whole square profile picture; wider layouts keep the banner strip.
        val squareHeader = !isWide && !isMedium
        val headerImageHeight =
            if (squareHeader) {
                minOf(maxWidth, maxHeight * 0.6f)
            } else {
                minOf(maxHeight / 2, if (isWide) 260.dp else 360.dp)
            }
        val actionSize =
            when {
                maxWidth >= 840.dp -> ArtistActionSize.Expanded
                maxWidth >= 600.dp -> ArtistActionSize.Medium
                else -> ArtistActionSize.Compact
            }

        val titleBottom = remember { mutableFloatStateOf(Float.POSITIVE_INFINITY) }
        val surface = MaterialTheme.colorScheme.surface
        val headerTop = contentPadding.calculateTopPadding()
        // The picture stays put while the list scrolls; the page content slides up over it.
        ArtistHeaderImage(
            imageUrl = if (squareHeader) artist.thumbnail ?: artist.banner else artist.banner ?: artist.thumbnail,
            square = squareHeader,
            headerVideoLayer = headerVideoLayer,
            modifier =
                Modifier.padding(top = headerTop).fillMaxWidth().height(headerImageHeight).clipToBounds().then(
                    if (isPlaceholder && artist.banner == null && artist.thumbnail == null) {
                        Modifier.loadingShimmer().background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    } else {
                        Modifier
                    },
                ),
        )
        DragScrollableColumn(
            state = listState,
            modifier =
                Modifier
                    .fillMaxSize()
                    .drawBehind {
                        // Items below the header gap are transparent, so paint the page behind them.
                        val coverTop =
                            if (listState.firstVisibleItemIndex == 0) {
                                (headerTop + headerImageHeight).toPx() - listState.firstVisibleItemScrollOffset
                            } else {
                                0f
                            }
                        val top = coverTop.coerceAtLeast(0f)
                        // Rounded like a sheet over the picture, flattening as it reaches the top edge.
                        val radius = minOf(28.dp.toPx(), top)
                        drawRoundRect(
                            color = surface,
                            topLeft = Offset(0f, top),
                            size = Size(size.width, size.height - top + radius),
                            cornerRadius = CornerRadius(radius),
                        )
                    }.desktopArrowScroll(listState)
                    .then(
                        if (isPlaceholder) {
                            Modifier.semantics {
                                contentDescription = loadingText
                                liveRegion = LiveRegionMode.Polite
                            }
                        } else {
                            Modifier
                        },
                    ),
            contentPadding = contentPadding,
        ) {
            item(key = "artist_header", contentType = "header") {
                ArtistHeader(
                    artist = artist,
                    isPlaceholder = isPlaceholder,
                    imageHeight = headerImageHeight,
                    isWide = isWide,
                    isMedium = isMedium,
                    actionSize = actionSize,
                    isSubscribed = isSubscribed,
                    subscribeSuccessCount = subscribeSuccessCount,
                    showSubscribe = showSubscribe,
                    onMenuClick = onMenuClick,
                    onTitleBottom = { titleBottom.floatValue = it },
                    onPlayAllClick = onPlayAllClick,
                    onShuffleClick = onShuffleClick,
                    onMixClick = onMixClick,
                    onSubscribeClick = onSubscribeClick,
                    onUnblockClick = onUnblockClick,
                    description = description.takeIf { onAboutClick == null },
                )
            }

            if (isPlaceholder) {
                item(key = "artist_loading_sections", contentType = "loading") {
                    ArtistLoadingSections(isWide, isMedium)
                }
            } else {
                renderArtistSections(
                    artistId = artist.id,
                    topTracksSection = topTracksSection,
                    videoSections = videoSections,
                    librarySection = librarySection,
                    currentSongId = currentSongId,
                    isPlaying = isPlaying,
                    onItemsVisible = onItemsVisible,
                    sections = sections,
                    sectionBrowseIds = sectionBrowseIds,
                    loadableSections = loadableSections,
                    sectionContinuation = sectionContinuation,
                    onItemClick = onItemClick,
                    onVideoItemClick = onVideoItemClick,
                    onAlbumArtworkClick = onAlbumArtworkClick,
                    onItemLongClick = onItemLongClick,
                    songContainer = songContainer,
                    onSectionClick = onSectionClick,
                    onVideoSectionClick = onVideoSectionClick,
                    onPlayItem = onPlayItem,
                    isSongLiked = isSongLiked,
                    onLikeSong = onLikeSong,
                    isWide = isWide,
                    isMedium = isMedium,
                    about =
                        onAboutClick?.let { onClick ->
                            {
                                item(key = "artist_about", contentType = "about") {
                                    ArtistAboutCard(
                                        artist = artist,
                                        description = description,
                                        imageUrl = aboutImageUrl,
                                        isWide = isWide,
                                        descriptionLines = if (isWide || isMedium) 2 else 4,
                                        onClick = onClick,
                                    )
                                }
                            }
                        },
                )
            }

            item(key = "artist_footer_space", contentType = "spacer") { Spacer(Modifier.height(24.dp)) }
        }
        val barMenuClick = onMenuClick.takeIf { actionSize == ArtistActionSize.Compact }
        if (onBackClick != null || barMenuClick != null || onRefreshClick != null) {
            val appBarBottom = remember { mutableFloatStateOf(0f) }
            val showAppBar by remember(listState) {
                derivedStateOf { listState.firstVisibleItemIndex > 0 || titleBottom.floatValue <= appBarBottom.floatValue }
            }
            LaunchedEffect(showAppBar) { onAppBarVisibleChange(showAppBar) }
            val progress by animateFloatAsState(if (showAppBar) 1f else 0f, MaterialTheme.motionScheme.fastEffectsSpec())
            val surface = MaterialTheme.colorScheme.surface
            val iconColor = lerp(Color.White, MaterialTheme.colorScheme.onSurface, progress)
            val buttonModifier = Modifier.drawBehind { drawCircle(Color.Black, alpha = 0.4f * (1f - progress)) }
            TopAppBar(
                title = {
                    Text(
                        artist.displayTitle,
                        modifier =
                            Modifier
                                .graphicsLayer { alpha = progress }
                                .then(if (isPlaceholder) Modifier.clearAndSetSemantics {} else Modifier),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    if (onBackClick != null) {
                        IconButton(onClick = onBackClick, modifier = buttonModifier, shapes = IconButtonDefaults.shapes()) {
                            Icon(MetrolistIcons.AutoMirrored.Filled.ArrowBack, stringResource(Res.string.back), tint = iconColor)
                        }
                    }
                },
                actions = {
                    if (onRefreshClick != null) {
                        IconButton(onClick = onRefreshClick, modifier = buttonModifier, shapes = IconButtonDefaults.shapes()) {
                            Icon(MetrolistIcons.Outlined.Refresh, stringResource(Res.string.artist_refresh), tint = iconColor)
                        }
                    }
                    if (barMenuClick != null) {
                        IconButton(
                            onClick = barMenuClick,
                            modifier = buttonModifier.navigationPopupSource(),
                            shapes = IconButtonDefaults.shapes(),
                        ) {
                            Icon(MetrolistIcons.Default.MoreVert, stringResource(Res.string.artist_actions), tint = iconColor)
                        }
                    }
                },
                modifier =
                    Modifier
                        .onGloballyPositioned { appBarBottom.floatValue = it.boundsInWindow().bottom }
                        .drawBehind { drawRect(surface, alpha = progress) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                windowInsets = headerWindowInsets,
            )
        }
    }
}

@Composable
private fun ArtistPlaceholderBlock(
    modifier: Modifier,
    shape: Shape = RoundedCornerShape(4.dp),
) {
    Spacer(modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHigh))
}

@Composable
private fun ArtistPlaceholderText(
    style: TextStyle,
    modifier: Modifier = Modifier,
    fraction: Float = 0.7f,
) {
    val color = MaterialTheme.colorScheme.surfaceContainerHigh
    Text(
        text = "",
        style = style,
        modifier =
            modifier.fillMaxWidth().clearAndSetSemantics {}.drawBehind {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(if (layoutDirection == LayoutDirection.Rtl) size.width * (1f - fraction) else 0f, size.height * 0.2f),
                    size = Size(size.width * fraction, size.height * 0.6f),
                    cornerRadius = CornerRadius(4.dp.toPx()),
                )
            },
    )
}

@Composable
private fun ArtistLoadingSections(
    isWide: Boolean,
    isMedium: Boolean,
) {
    val isSpacious = isWide || isMedium
    val horizontalPadding = if (isWide) 24.dp else 16.dp
    val imageSize = if (isSpacious) 180.dp else 135.dp
    Box(Modifier.fillMaxWidth().clearAndSetSemantics {}, contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 1400.dp).fillMaxWidth().loadingShimmer()) {
            Column(Modifier.padding(top = 15.dp)) {
                SectionTitle(
                    title = stringResource(Res.string.artist_latest_release),
                    modifier = Modifier.padding(horizontal = horizontalPadding),
                    isWide = isSpacious,
                )
                Spacer(Modifier.height(10.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = horizontalPadding),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainer,
                ) {
                    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        ArtistPlaceholderBlock(Modifier.size(imageSize), RoundedCornerShape(uiEnhanced(6.dp, 12.dp)))
                        Column(Modifier.height(imageSize).weight(1f)) {
                            ArtistPlaceholderText(
                                if (isSpacious) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge,
                            )
                            ArtistPlaceholderText(MaterialTheme.typography.bodyLarge, fraction = 0.5f)
                            Spacer(Modifier.weight(1f))
                            ArtistPlaceholderBlock(
                                Modifier.align(if (isWide) Alignment.Start else Alignment.End).size(width = 88.dp, height = 40.dp),
                                CircleShape,
                            )
                        }
                    }
                }
            }
            Column(Modifier.padding(top = 15.dp)) {
                SectionTitle(
                    title = stringResource(Res.string.top_tracks),
                    modifier = Modifier.padding(horizontal = horizontalPadding),
                    isWide = isSpacious,
                )
                Spacer(Modifier.height(10.dp))
                Column(Modifier.padding(horizontal = horizontalPadding)) {
                    repeat(5) {
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ArtistPlaceholderBlock(Modifier.size(if (isWide) 40.dp else 48.dp))
                            Spacer(Modifier.width(if (isWide) 16.dp else 12.dp))
                            Column(Modifier.weight(1f)) {
                                ArtistPlaceholderText(MaterialTheme.typography.bodyMedium)
                                ArtistPlaceholderText(MaterialTheme.typography.bodySmall, fraction = 0.45f)
                            }
                            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                                ArtistPlaceholderBlock(Modifier.size(24.dp), CircleShape)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun LazyListScope.renderArtistSections(
    artistId: String,
    topTracksSection: String?,
    videoSections: Set<String>,
    librarySection: String?,
    currentSongId: String?,
    isPlaying: Boolean,
    onItemsVisible: ((List<YTItem>) -> Unit)?,
    sections: Map<String, List<YTItem>>,
    sectionBrowseIds: Map<String, Pair<String?, String?>>,
    loadableSections: Set<String>,
    sectionContinuation: (@Composable (String) -> Unit)?,
    onItemClick: (YTItem) -> Unit,
    onVideoItemClick: (YTItem) -> Unit = onItemClick,
    onAlbumArtworkClick: ((AlbumItem, String) -> Unit)? = null,
    onItemLongClick: (YTItem) -> Unit,
    songContainer: @Composable (SongItem, @Composable () -> Unit) -> Unit,
    onSectionClick: ((String, String?, String) -> Unit)?,
    onVideoSectionClick: ((String, String?, String) -> Unit)? = onSectionClick,
    onPlayItem: ((YTItem) -> Unit)?,
    isSongLiked: ((String) -> Boolean)? = null,
    onLikeSong: ((SongItem) -> Unit)? = null,
    isWide: Boolean = false,
    isMedium: Boolean = false,
    about: (LazyListScope.() -> Unit)? = null,
) {
    val aboutBefore = librarySection
    sections.forEach { (title, items) ->
        if (title == aboutBefore) about?.invoke(this)
        if (items.isNotEmpty() && title != "header") {
            val browseTarget = sectionBrowseIds[title]
            val browseId = browseTarget?.first
            val onBrowse = if (items.isVideoShelf(allowUntypedSongs = title in videoSections)) onVideoSectionClick else onSectionClick
            item(key = "section_$title", contentType = "section") {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(modifier = Modifier.widthIn(max = 1400.dp).fillMaxWidth().padding(top = 15.dp)) {
                        val sectionTitle =
                            when {
                                title == "latest_release" -> stringResource(Res.string.artist_latest_release)
                                title == topTracksSection -> stringResource(Res.string.top_tracks)
                                else -> title
                            }
                        SectionTitle(
                            title = sectionTitle,
                            modifier = Modifier.padding(horizontal = if (isWide) 24.dp else 16.dp),
                            seeAllLabel = stringResource(Res.string.see_all),
                            showSeeAllLabel = false,
                            onClick =
                                if (browseId != null && onBrowse != null) {
                                    { onBrowse(browseId, browseTarget.second, sectionTitle) }
                                } else {
                                    null
                                },
                            isWide = isWide || isMedium,
                        )
                        Spacer(Modifier.height(10.dp))

                        ArtistSectionContent(
                            sectionKey = "$artistId:$title",
                            items = items,
                            currentSongId = currentSongId,
                            isPlaying = isPlaying,
                            isLatestRelease = title == "latest_release",
                            songRows = if (title == topTracksSection) 5 else 4,
                            isTopTracks = title == topTracksSection,
                            isLibrary = title == librarySection,
                            isVideo = title in videoSections,
                            onItemsVisible = onItemsVisible,
                            continuationContent =
                                if (title in loadableSections && sectionContinuation != null) {
                                    { sectionContinuation(title) }
                                } else {
                                    null
                                },
                            onItemClick = onItemClick,
                            onVideoItemClick = onVideoItemClick,
                            onAlbumArtworkClick = onAlbumArtworkClick,
                            onItemLongClick = onItemLongClick,
                            songContainer = songContainer,
                            onPlayItem = onPlayItem,
                            isSongLiked = isSongLiked,
                            onLikeSong = onLikeSong,
                            isWide = isWide,
                            isMedium = isMedium,
                        )
                    }
                }
            }
        }
    }
    if (aboutBefore == null) about?.invoke(this)
}

@Composable
private fun ArtistHeader(
    artist: ArtistItem,
    isPlaceholder: Boolean,
    imageHeight: Dp,
    isWide: Boolean,
    isMedium: Boolean,
    actionSize: ArtistActionSize,
    isSubscribed: Boolean,
    subscribeSuccessCount: Int,
    showSubscribe: Boolean,
    onMenuClick: (() -> Unit)?,
    onTitleBottom: (Float) -> Unit,
    onPlayAllClick: () -> Unit,
    onShuffleClick: () -> Unit,
    onMixClick: () -> Unit,
    onSubscribeClick: () -> Unit,
    onUnblockClick: () -> Unit,
    description: String?,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Leaves the pinned picture behind the list visible until the content scrolls over it.
        Spacer(Modifier.fillMaxWidth().height(imageHeight))
        Column(Modifier.widthIn(max = 1400.dp).fillMaxWidth()) {
            ArtistHeaderDetails(
                artist = artist,
                isPlaceholder = isPlaceholder,
                isWide = isWide,
                isMedium = isMedium,
                actionSize = actionSize,
                isSubscribed = isSubscribed,
                subscribeSuccessCount = subscribeSuccessCount,
                showSubscribe = showSubscribe,
                onPlayAllClick = onPlayAllClick,
                onShuffleClick = onShuffleClick,
                onMixClick = onMixClick,
                onSubscribeClick = onSubscribeClick,
                onUnblockClick = onUnblockClick,
                onMenuClick = onMenuClick.takeUnless { actionSize == ArtistActionSize.Compact },
                onTitleBottom = onTitleBottom,
                modifier = Modifier.padding(horizontal = if (isWide) 32.dp else 20.dp, vertical = if (isWide) 24.dp else 16.dp),
            )
        }
        if (!isPlaceholder && !description.isNullOrBlank()) {
            ArtistAbout(
                description = description,
                modifier =
                    Modifier
                        .widthIn(max = 1400.dp)
                        .fillMaxWidth()
                        .padding(horizontal = if (isWide) 32.dp else 20.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun ArtistHeaderImage(
    imageUrl: String?,
    square: Boolean,
    headerVideoLayer: (@Composable (Modifier) -> Unit)?,
    modifier: Modifier,
) {
    val resolvedImageUrl = remember(imageUrl) { imageUrl?.let(::highResolutionArtworkUrl) }
    var currentUrl by remember { mutableStateOf(resolvedImageUrl) }
    var previousUrl by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(resolvedImageUrl) {
        if (resolvedImageUrl != currentUrl) {
            previousUrl = currentUrl
            currentUrl = resolvedImageUrl
        }
    }

    val imageAlignment = if (square) Alignment.Center else BiasAlignment(0f, -0.65f)
    val surface = MaterialTheme.colorScheme.surface
    Box(modifier) {
        BlurredArtworkBackground(
            url = currentUrl,
            modifier = Modifier.fillMaxSize(),
            blurRadiusPx = with(LocalDensity.current) { 48.dp.toPx() },
            verticalBias = -0.65f,
        )
        Box(
            modifier =
                Modifier
                    .align(Alignment.Center)
                    .widthIn(max = 1400.dp)
                    .fillMaxHeight()
                    .then(if (square) Modifier else Modifier.bannerEdgeMask()),
        ) {
            AsyncImage(
                url = currentUrl,
                previewUrl = previousUrl,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                alignment = imageAlignment,
            )
        }
        headerVideoLayer?.invoke(Modifier.matchParentSize())
        if (!square) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            0f to Color.Black.copy(alpha = 0.6f),
                            0.15f to Color.Transparent,
                            0.85f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.6f),
                        ),
                    ),
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    if (square) {
                        // Only the lower edge blends into the page so the whole picture stays visible.
                        Brush.verticalGradient(
                            0f to Color.Black.copy(alpha = 0.25f),
                            0.18f to Color.Transparent,
                            0.78f to Color.Transparent,
                            0.92f to surface.copy(alpha = 0.7f),
                            1f to surface,
                        )
                    } else {
                        Brush.verticalGradient(
                            0f to Color.Black.copy(alpha = 0.25f),
                            0.3f to Color.Transparent,
                            0.6f to surface.copy(alpha = 0.5f),
                            0.85f to surface.copy(alpha = 0.88f),
                            1f to surface,
                        )
                    },
                ),
        )
    }
}

private fun Modifier.bannerEdgeMask(): Modifier =
    graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
        .drawWithCache {
            val edgeMask =
                Brush.horizontalGradient(
                    0f to Color.Black,
                    0.12f to Color.Transparent,
                    0.88f to Color.Transparent,
                    1f to Color.Black,
                )
            onDrawWithContent {
                drawContent()
                drawRect(brush = edgeMask, blendMode = BlendMode.DstOut)
            }
        }

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ArtistHeaderDetails(
    artist: ArtistItem,
    isPlaceholder: Boolean,
    isWide: Boolean,
    isMedium: Boolean,
    actionSize: ArtistActionSize,
    isSubscribed: Boolean,
    subscribeSuccessCount: Int,
    showSubscribe: Boolean,
    onPlayAllClick: () -> Unit,
    onShuffleClick: () -> Unit,
    onMixClick: () -> Unit,
    onSubscribeClick: () -> Unit,
    onUnblockClick: () -> Unit,
    onMenuClick: (() -> Unit)?,
    onTitleBottom: (Float) -> Unit,
    modifier: Modifier,
) {
    Column(modifier) {
        val titleStyle =
            MaterialTheme.typography.headlineLargeEmphasized.copy(
                fontSize =
                    if (isWide) {
                        44.sp
                    } else if (isMedium) {
                        32.sp
                    } else {
                        26.sp
                    },
            )
        val titleModifier = Modifier.onGloballyPositioned { onTitleBottom(it.boundsInWindow().bottom) }
        if (isPlaceholder && artist.title.isBlank()) {
            ArtistPlaceholderText(titleStyle, titleModifier.loadingShimmer(), 0.6f)
        } else {
            Text(
                text = artist.displayTitle,
                modifier = titleModifier.then(if (isPlaceholder) Modifier.clearAndSetSemantics {} else Modifier),
                style = titleStyle,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        if (isPlaceholder) {
            Column(Modifier.loadingShimmer().clearAndSetSemantics {}) {
                ArtistPlaceholderText(MaterialTheme.typography.bodyLarge, fraction = 0.4f)
                ArtistPlaceholderText(MaterialTheme.typography.bodyLarge, fraction = 0.55f)
            }
        } else {
            artist.subscribers?.takeIf { it.isNotBlank() }?.let { count ->
                Text(
                    stringResource(Res.string.artist_subscriber_count, count),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val monthlyAudience =
                artist.monthlyListeners
                    ?.let { monthlyAudienceText(it, stringResource(Res.string.monthly_audience_short)) }
            if (monthlyAudience != null) {
                Text(monthlyAudience, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(16.dp))
        ArtistActionRow(
            isPlaceholder = isPlaceholder,
            size = actionSize,
            artist = artist,
            isSubscribed = isSubscribed,
            subscribeSuccessCount = subscribeSuccessCount,
            showSubscribe = showSubscribe,
            unblockPadding = if (isWide) 20.dp else 14.dp,
            onPlayAllClick = onPlayAllClick,
            onShuffleClick = onShuffleClick,
            onMixClick = onMixClick,
            onSubscribeClick = onSubscribeClick,
            onUnblockClick = onUnblockClick,
            onMenuClick = onMenuClick,
        )
    }
}

private enum class ArtistActionSize { Compact, Medium, Expanded }

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalSharedTransitionApi::class)
@Composable
private fun ArtistActionRow(
    isPlaceholder: Boolean,
    size: ArtistActionSize,
    artist: ArtistItem,
    isSubscribed: Boolean,
    subscribeSuccessCount: Int,
    showSubscribe: Boolean,
    unblockPadding: Dp,
    onPlayAllClick: () -> Unit,
    onShuffleClick: () -> Unit,
    onMixClick: () -> Unit,
    onSubscribeClick: () -> Unit,
    onUnblockClick: () -> Unit,
    onMenuClick: (() -> Unit)?,
) {
    val compact = size == ArtistActionSize.Compact
    val rowHeight = 48.dp
    val subscribeLabel = stringResource(if (isSubscribed) Res.string.subscribed else Res.string.subscribe)
    val radioLabel = stringResource(Res.string.radio)
    val shuffleLabel = stringResource(Res.string.shuffle)
    val textStyle = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val tonalColors = FilledTonalToggleButtonDefaults.colors()
    val subscribeColor by animateColorAsState(
        if (isSubscribed) MaterialTheme.colorScheme.primary else tonalColors.containerColor,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
    )
    val subscribeContentColor by animateColorAsState(
        if (isSubscribed) MaterialTheme.colorScheme.onPrimary else tonalColors.contentColor,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
    )
    // BoxWithConstraints composes at the lookahead width; keep ButtonGroup and its menu at that width in both passes.
    val actionSizing = LocalNavSharedTransitionScope.current?.run { Modifier.skipToLookaheadSize { true } } ?: Modifier
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().then(actionSizing),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BoxWithConstraints(Modifier.weight(1f)) {
                val widths =
                    with(density) {
                        artistActionWidths(
                            availableWidth = constraints.maxWidth,
                            subscribeLabelWidth = textMeasurer.measure(subscribeLabel, textStyle).size.width,
                            radioLabelWidth = textMeasurer.measure(radioLabel, textStyle).size.width,
                            showSubscribe = showSubscribe,
                        )
                    }
                if (isPlaceholder) {
                    Row(
                        Modifier.loadingShimmer().clearAndSetSemantics {},
                        horizontalArrangement = Arrangement.spacedBy(widths.spacing),
                    ) {
                        if (showSubscribe) {
                            ArtistPlaceholderBlock(Modifier.width(widths.subscribeWidth).height(rowHeight), CircleShape)
                            Spacer(Modifier.width(widths.separation))
                        }
                        ArtistPlaceholderBlock(Modifier.width(widths.radioWidth).height(rowHeight), MaterialTheme.shapes.small)
                        ArtistPlaceholderBlock(Modifier.width(widths.shuffleWidth).height(rowHeight), MaterialTheme.shapes.small)
                        ArtistPlaceholderBlock(Modifier.width(widths.playWidth).height(rowHeight), MaterialTheme.shapes.small)
                    }
                } else {
                    ButtonGroup(
                        overflowIndicator = { ButtonGroupDefaults.OverflowIndicator(it) },
                        expandedRatio = 1f,
                        horizontalArrangement = Arrangement.spacedBy(widths.spacing),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (showSubscribe) {
                            customItem(
                                buttonGroupContent = {
                                    val interactionSource = remember { MutableInteractionSource() }
                                    Box(Modifier.animateWidth(interactionSource, 12.dp).width(widths.subscribeWidth).height(rowHeight)) {
                                        FilledTonalToggleButton(
                                            checked = isSubscribed,
                                            onCheckedChange = { onSubscribeClick() },
                                            interactionSource = interactionSource,
                                            modifier = Modifier.fillMaxSize(),
                                            shapes =
                                                FilledTonalToggleButtonDefaults.shapesFor(rowHeight).copy(
                                                    shape = MaterialTheme.shapes.small,
                                                    checkedShape = MaterialTheme.shapes.medium,
                                                ),
                                            contentPadding = PaddingValues(horizontal = 12.dp),
                                            colors =
                                                tonalColors.copy(
                                                    containerColor = subscribeColor,
                                                    contentColor = subscribeContentColor,
                                                    checkedContainerColor = subscribeColor,
                                                    checkedContentColor = subscribeContentColor,
                                                ),
                                        ) {
                                            Text(subscribeLabel, style = textStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                        SubscribeConfetti(subscribeSuccessCount, Modifier.matchParentSize())
                                    }
                                },
                                menuContent = { DropdownMenuItem(text = { Text(subscribeLabel) }, onClick = onSubscribeClick) },
                            )
                        }
                        if (showSubscribe) {
                            customItem(
                                buttonGroupContent = { Spacer(Modifier.width(widths.separation)) },
                                menuContent = {},
                            )
                        }
                        listOf(radioLabel, shuffleLabel).forEachIndexed { index, label ->
                            val onClick = if (index == 0) onMixClick else onShuffleClick
                            val showLabel = index == 0 && widths.labelRadio
                            customItem(
                                buttonGroupContent = {
                                    val interactionSource = remember { MutableInteractionSource() }
                                    val shapes =
                                        if (index == 0) {
                                            ButtonGroupDefaults.connectedLeadingButtonShapes()
                                        } else {
                                            ButtonGroupDefaults.connectedMiddleButtonShapes()
                                        }
                                    FilledTonalButton(
                                        onClick = onClick,
                                        interactionSource = interactionSource,
                                        modifier =
                                            Modifier
                                                .animateWidth(interactionSource, 12.dp)
                                                .width(if (index == 0) widths.radioWidth else widths.shuffleWidth)
                                                .height(rowHeight),
                                        shapes = ButtonDefaults.shapes(shape = shapes.shape, pressedShape = shapes.pressedShape),
                                        contentPadding = PaddingValues(horizontal = if (showLabel) 12.dp else 0.dp),
                                    ) {
                                        Icon(
                                            if (index == 0) MetrolistIcons.Outlined.Sensors else MetrolistIcons.Rounded.Shuffle,
                                            if (showLabel) null else label,
                                            Modifier.size(24.dp),
                                        )
                                        if (showLabel) {
                                            Spacer(Modifier.width(8.dp))
                                            Text(label, style = textStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                },
                                menuContent = { DropdownMenuItem(text = { Text(label) }, onClick = onClick) },
                            )
                        }
                        customItem(
                            buttonGroupContent = {
                                val interactionSource = remember { MutableInteractionSource() }
                                val shapes = ButtonGroupDefaults.connectedTrailingButtonShapes()
                                Button(
                                    onClick = onPlayAllClick,
                                    interactionSource = interactionSource,
                                    modifier = Modifier.animateWidth(interactionSource, 12.dp).width(widths.playWidth).height(rowHeight),
                                    shapes = ButtonDefaults.shapes(shape = shapes.shape, pressedShape = shapes.pressedShape),
                                    contentPadding = PaddingValues(0.dp),
                                ) {
                                    Icon(MetrolistIcons.Default.PlayArrow, stringResource(Res.string.artist_play_all), Modifier.size(32.dp))
                                }
                            },
                            menuContent = {
                                DropdownMenuItem(text = { Text(stringResource(Res.string.artist_play_all)) }, onClick = onPlayAllClick)
                            },
                        )
                    }
                }
            }
            if (!compact && onMenuClick != null) {
                FilledTonalIconButton(
                    onClick = onMenuClick,
                    modifier = Modifier.size(rowHeight).navigationPopupSource(),
                    shapes = IconButtonDefaults.shapes(),
                ) {
                    Icon(MetrolistIcons.Default.MoreVert, stringResource(Res.string.artist_actions), modifier = Modifier.size(24.dp))
                }
            }
        }
        if (!isPlaceholder && artist.isBlocked) {
            ArtistUnblockButton(onUnblockClick, unblockPadding)
        }
    }
}

@Composable
private fun ArtistUnblockButton(
    onClick: () -> Unit,
    horizontalPadding: Dp,
) {
    FilledTonalButton(
        onClick = onClick,
        modifier = Modifier.height(48.dp),
        contentPadding = PaddingValues(horizontal = horizontalPadding),
        shapes = ButtonDefaults.shapes(),
    ) {
        Icon(MetrolistIcons.Filled.Remove, null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(Res.string.unblock), fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun ArtistSectionContent(
    sectionKey: String,
    items: List<YTItem>,
    currentSongId: String?,
    isPlaying: Boolean,
    isLatestRelease: Boolean,
    songRows: Int,
    isTopTracks: Boolean,
    isLibrary: Boolean,
    isVideo: Boolean,
    onItemsVisible: ((List<YTItem>) -> Unit)?,
    continuationContent: (@Composable () -> Unit)?,
    onItemClick: (YTItem) -> Unit,
    onVideoItemClick: (YTItem) -> Unit = onItemClick,
    onAlbumArtworkClick: ((AlbumItem, String) -> Unit)? = null,
    onItemLongClick: (YTItem) -> Unit,
    songContainer: @Composable (SongItem, @Composable () -> Unit) -> Unit,
    onPlayItem: ((YTItem) -> Unit)?,
    isSongLiked: ((String) -> Boolean)?,
    onLikeSong: ((SongItem) -> Unit)?,
    isWide: Boolean,
    isMedium: Boolean,
) {
    val isSpacious = isWide || isMedium
    val horizontalPadding = if (isWide) 24.dp else 16.dp
    val showReleaseType =
        remember(items) {
            items
                .filterIsInstance<AlbumItem>()
                .mapNotNull { it.type }
                .distinct()
                .size > 1
        }
    val isVideoShelf = remember(items, isVideo) { items.isVideoShelf(allowUntypedSongs = isVideo) }
    val isSongShelf = remember(items) { items.all { it is SongItem } }
    val release = (items.singleOrNull() as? AlbumItem)?.takeIf { isLatestRelease }
    if (release != null) {
        val menuLabel = stringResource(Res.string.more_options)
        val imageSize = if (isSpacious) 180.dp else 135.dp
        val token = "artist:$sectionKey:0"
        val artworkSource =
            AlbumArtworkSource(release, token) {
                onAlbumArtworkClick?.invoke(release, token) ?: onItemClick(release)
            }
        Surface(
            modifier = if (isWide) Modifier.padding(horizontal = 24.dp).fillMaxWidth() else artistCardModifier(false),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .navigationPageSource(token)
                        .artworkMenuActions({ onItemLongClick(release) }, menuLabel)
                        .combinedClickable(
                            onClick = {
                                artworkSource.select()
                            },
                            onLongClick = { onItemLongClick(release) },
                            onLongClickLabel = menuLabel,
                        ).padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                AsyncImage(
                    release.thumbnail.orEmpty(),
                    modifier = artworkSource.modifier(uiEnhanced(6.dp, 12.dp)).size(imageSize),
                    contentScale = ContentScale.Crop,
                )
                Column(Modifier.height(imageSize).weight(1f)) {
                    Text(
                        release.title,
                        style = if (isSpacious) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (release.isExplicit) ExplicitBadge()
                        listOfNotNull(release.type, release.year).joinToString(" • ").takeIf(String::isNotBlank)?.let {
                            Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = if (isWide) Arrangement.Start else Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (onPlayItem != null) {
                            Button(onClick = { onPlayItem(release) }, shapes = ButtonDefaults.shapes()) {
                                Icon(MetrolistIcons.Filled.PlayArrow, null, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(Res.string.play))
                            }
                        }
                    }
                }
            }
        }
    } else if (isVideoShelf) {
        VideoShelf(
            items = items,
            onItemClick = onVideoItemClick,
            onItemLongClick = onItemLongClick,
            isWide = isSpacious,
            contentPadding = PaddingValues(horizontal = horizontalPadding),
            currentSongId = rememberUpdatedState(currentSongId),
            isPlaying = rememberUpdatedState(isPlaying),
            onItemsVisible = onItemsVisible,
            continuationContent = continuationContent,
        )
    } else if (isLibrary) {
        val carouselState = rememberCarouselState { items.size + if (continuationContent != null) 1 else 0 }
        // Material 3 1.13 defaults to one item per fling; keep momentum with snapping.
        val carouselFling = CarouselDefaults.multiBrowseFlingBehavior(carouselState)
        HorizontalMultiBrowseCarousel(
            state = carouselState,
            flingBehavior = carouselFling,
            preferredItemWidth = CarouselItemSize,
            itemSpacing = CarouselItemSpacing,
            contentPadding = PaddingValues(0.dp),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = horizontalPadding)
                    .clip(RoundedCornerShape(CarouselItemCornerRadius))
                    .carouselScroll(carouselState, carouselFling),
        ) { index ->
            if (index == items.size) {
                Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                    continuationContent?.invoke()
                }
            } else {
                val item = items[index]
                if (item is SongItem) {
                    LaunchedEffect(item.id, item.duration) { onItemsVisible?.invoke(listOf(item)) }
                }
                YTGridItem(
                    item = item,
                    onClick = { onItemClick(item) },
                    onLongClick = { onItemLongClick(item) },
                    fillWidth = true,
                    squareArtwork = true,
                    artworkModifier = Modifier.maskClip(carouselArtworkShape(item)),
                    carouselItemDrawInfo = carouselItemDrawInfo,
                    isCurrent = item.id == currentSongId,
                    isPlaying = isPlaying && item.id == currentSongId,
                    clickableMetadata = isSpacious,
                )
            }
        }
    } else if (!isVideo && isSongShelf) {
        if (isTopTracks) {
            Column(Modifier.fillMaxWidth().padding(horizontal = horizontalPadding)) {
                ArtistSongRows(
                    songs = remember(items) { items.take(5) },
                    isWide = isWide,
                    isSpacious = isSpacious,
                    currentSongId = currentSongId,
                    isPlaying = isPlaying,
                    onItemsVisible = onItemsVisible,
                    onItemClick = onItemClick,
                    onItemLongClick = onItemLongClick,
                    songContainer = songContainer,
                    isSongLiked = isSongLiked,
                    onLikeSong = onLikeSong,
                )
            }
        } else {
            val pages = remember(items, songRows) { items.chunked(songRows) }
            val pagerState = rememberPagerState { pages.size + if (continuationContent != null) 1 else 0 }
            val flingBehavior = PagerDefaults.flingBehavior(pagerState)
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.heightIn(min = 56.dp * songRows).carouselScroll(pagerState, flingBehavior),
                contentPadding = PaddingValues(horizontal = horizontalPadding),
                pageSpacing = if (isWide) horizontalPadding else 8.dp,
                flingBehavior = flingBehavior,
                verticalAlignment = Alignment.Top,
            ) { page ->
                if (page == pages.size) {
                    Box(Modifier.fillMaxWidth().height(56.dp * songRows), contentAlignment = Alignment.Center) {
                        continuationContent?.invoke()
                    }
                } else {
                    ArtistSongRows(
                        songs = pages[page],
                        isWide = isWide,
                        isSpacious = isSpacious,
                        currentSongId = currentSongId,
                        isPlaying = isPlaying,
                        onItemsVisible = onItemsVisible,
                        onItemClick = onItemClick,
                        onItemLongClick = onItemLongClick,
                        songContainer = songContainer,
                        isSongLiked = isSongLiked,
                        onLikeSong = onLikeSong,
                    )
                }
            }
        }
    } else {
        val imageSize =
            if (isVideo) {
                if (isSpacious) 320.dp else 280.dp
            } else {
                CarouselItemSize
            }
        val carouselState = rememberCarouselState { items.size + if (continuationContent != null) 1 else 0 }
        // Material 3 1.13 defaults to one item per fling; keep momentum with snapping.
        val carouselFling = CarouselDefaults.multiBrowseFlingBehavior(carouselState)
        HorizontalMultiBrowseCarousel(
            state = carouselState,
            flingBehavior = carouselFling,
            preferredItemWidth = imageSize,
            itemSpacing = if (isVideo) 12.dp else CarouselItemSpacing,
            contentPadding = PaddingValues(0.dp),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = horizontalPadding)
                    .clip(RoundedCornerShape(CarouselItemCornerRadius))
                    .carouselScroll(carouselState, carouselFling),
        ) { index ->
            if (index == items.size) {
                Box(Modifier.fillMaxWidth().height(imageSize), contentAlignment = Alignment.Center) {
                    continuationContent?.invoke()
                }
            } else {
                val item = items[index]
                if (item is SongItem) {
                    LaunchedEffect(item.id, item.duration) { onItemsVisible?.invoke(listOf(item)) }
                }
                val token = "artist:$sectionKey:$index"
                val artworkSource =
                    (item as? AlbumItem)?.let { album ->
                        AlbumArtworkSource(album, token) { onAlbumArtworkClick?.invoke(album, token) ?: onItemClick(album) }
                    }
                Box(Modifier.fillMaxWidth()) {
                    YTGridItem(
                        item = item,
                        onClick = {
                            if (artworkSource != null) artworkSource.select() else onItemClick(item)
                        },
                        artwork = artworkSource,
                        onLongClick = { onItemLongClick(item) },
                        onShowMenu = onItemLongClick.takeIf { item is AlbumItem },
                        imageSize = imageSize,
                        fillWidth = true,
                        squareArtwork = true,
                        artworkModifier = Modifier.maskClip(carouselArtworkShape(item)),
                        carouselItemDrawInfo = carouselItemDrawInfo,
                        onAlbumPlay = onPlayItem?.let { play -> { play(item) } }.takeIf { item is AlbumItem },
                        contentPadding = 0.dp,
                        isCurrent = item is SongItem && item.id == currentSongId,
                        isPlaying = isPlaying && item.id == currentSongId,
                        subtitleOverride =
                            when (item) {
                                is AlbumItem -> {
                                    listOfNotNull(
                                        item.type.takeIf { showReleaseType },
                                        item.year,
                                        item.songCount?.takeIf { it > 1 }?.let { pluralStringResource(Res.plurals.song_count, it, it) },
                                    ).joinToString(" • ")
                                }

                                is PlaylistItem -> {
                                    val count = item.songCount?.substringBefore(' ')?.toIntOrNull()
                                    when {
                                        count == null -> item.songCount.orEmpty()
                                        count > 1 -> pluralStringResource(Res.plurals.song_count, count, count)
                                        else -> ""
                                    }
                                }

                                else -> {
                                    null
                                }
                            },
                        clickableMetadata = isSpacious,
                    )
                }
            }
        }
    }
}

@Composable
private fun ArtistSongRows(
    songs: List<YTItem>,
    isWide: Boolean,
    isSpacious: Boolean,
    currentSongId: String?,
    isPlaying: Boolean,
    onItemsVisible: ((List<YTItem>) -> Unit)?,
    onItemClick: (YTItem) -> Unit,
    onItemLongClick: (YTItem) -> Unit,
    songContainer: @Composable (SongItem, @Composable () -> Unit) -> Unit,
    isSongLiked: ((String) -> Boolean)?,
    onLikeSong: ((SongItem) -> Unit)?,
) {
    LaunchedEffect(songs) { onItemsVisible?.invoke(songs) }
    Column {
        songs.forEachIndexed { index, item ->
            key(item.id, index) {
                val song = item as? SongItem
                val row: @Composable () -> Unit = {
                    if (isWide && song != null) {
                        ArtistSongListItem(
                            song = song,
                            isCurrent = song.id == currentSongId,
                            isPlaying = isPlaying && song.id == currentSongId,
                            isLiked = isSongLiked?.invoke(song.id) == true,
                            onClick = { onItemClick(song) },
                            onLikeClick = onLikeSong?.let { onLike -> { onLike(song) } },
                            onMenuClick = { onItemLongClick(song) },
                        )
                    } else {
                        YTListItem(
                            item = item,
                            onClick = { onItemClick(item) },
                            onShowMenu = { onItemLongClick(item) },
                            clickableMetadata = isSpacious,
                            compactSongSubtitle = true,
                            isCurrent = item.id == currentSongId,
                            isPlaying = isPlaying && item.id == currentSongId,
                        )
                    }
                }
                if (song != null) songContainer(song, row) else row()
            }
        }
    }
}

@Composable
private fun ArtistSongListItem(
    song: SongItem,
    isCurrent: Boolean,
    isPlaying: Boolean,
    isLiked: Boolean,
    onClick: () -> Unit,
    onLikeClick: (() -> Unit)?,
    onMenuClick: () -> Unit,
) {
    val colors = animateCurrentSongColors(isCurrent)
    val contentColor = colors.content
    val subtitleColor = colors.subtitle
    val artworkShape = animatedCurrentSongArtworkShape(isCurrent, RoundedCornerShape(4.dp))
    val artworkRotation by rememberCurrentSongArtworkRotation(isCurrent, isPlaying)
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .clip(if (isCurrent) CircleShape else RoundedCornerShape(8.dp))
                .background(colors.container)
                .currentSongSemantics(isCurrent, isPlaying)
                .clickable(onClick = onClick)
                .onSecondaryClick(onMenuClick)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .squareArtworkSlot(40.dp, if (isCurrent) 1f else song.previewAspectRatio)
                .graphicsLayer { rotationZ = artworkRotation }
                .clip(artworkShape),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer { rotationZ = -artworkRotation },
            ) {
                AsyncImage(url = song.previewThumbnailUrl, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
        }
        Spacer(Modifier.width(16.dp))
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            ListItemTitle(
                text = song.title,
                isExplicit = song.isExplicit,
                modifier = Modifier.weight(1f, fill = false),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = contentColor,
            )
            if (song.id in LocalDownloadedSongIds.current) {
                Icon(
                    imageVector = SongStatusIcons.Downloaded,
                    contentDescription = stringResource(Res.string.downloaded),
                    tint = Color.Unspecified,
                    modifier = Modifier.padding(start = 8.dp).size(11.dp),
                )
            }
        }
        Text(
            text =
                listOfNotNull(
                    song.artists.joinToString { it.displayName },
                    song.duration?.let { formatDuration(it * 1000) },
                ).joinToString(" • "),
            modifier = Modifier.weight(0.7f).padding(horizontal = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = subtitleColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        FittingText(
            text = song.playCountText.orEmpty(),
            compactText = song.playCountText?.withoutPlaybackCountLabel().orEmpty(),
            modifier = Modifier.weight(0.4f).padding(horizontal = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = subtitleColor,
        )
        Text(
            text = song.album?.name.orEmpty(),
            modifier = Modifier.weight(0.8f).padding(horizontal = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = subtitleColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (onLikeClick != null) {
            IconButton(onClick = onLikeClick, modifier = Modifier.size(48.dp), shapes = IconButtonDefaults.shapes()) {
                Icon(
                    imageVector = if (isLiked) MetrolistIcons.Filled.Favorite else MetrolistIcons.Outlined.FavoriteBorder,
                    contentDescription = stringResource(if (isLiked) Res.string.unlike else Res.string.action_like),
                    tint =
                        if (isCurrent) {
                            contentColor
                        } else if (isLiked) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        IconButton(onClick = onMenuClick, modifier = Modifier.size(48.dp).navigationPopupSource(), shapes = IconButtonDefaults.shapes()) {
            Icon(
                MetrolistIcons.Default.MoreVert,
                stringResource(Res.string.artist_actions),
                tint = subtitleColor,
            )
        }
    }
}

private fun artistCardModifier(isWide: Boolean): Modifier =
    if (isWide) {
        Modifier.padding(horizontal = 24.dp).widthIn(max = 720.dp).fillMaxWidth()
    } else {
        Modifier.fillMaxWidth().padding(horizontal = 16.dp)
    }

@Composable
private fun ArtistAboutCard(
    artist: ArtistItem,
    description: String?,
    imageUrl: String?,
    isWide: Boolean,
    descriptionLines: Int,
    onClick: () -> Unit,
) {
    val navigating = isNavTransitionRunning()
    var displayedUrl by remember(artist.id) { mutableStateOf(imageUrl) }
    LaunchedEffect(imageUrl, navigating) {
        if (!navigating) displayedUrl = imageUrl
    }
    val photoUrl = displayedUrl
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(modifier = Modifier.widthIn(max = 1400.dp).fillMaxWidth().padding(top = 15.dp)) {
            SectionTitle(
                title = stringResource(Res.string.about),
                modifier = Modifier.padding(horizontal = if (isWide) 24.dp else 16.dp),
                isWide = isWide,
            )
            Spacer(Modifier.height(10.dp))
            Surface(
                onClick = onClick,
                modifier = artistCardModifier(isWide).navigationPageSource(),
                shape = RoundedCornerShape(28.dp),
                color = if (photoUrl.isNullOrBlank()) MaterialTheme.colorScheme.surfaceContainer else Color.Transparent,
            ) {
                Box(contentAlignment = Alignment.BottomStart) {
                    if (!photoUrl.isNullOrBlank()) {
                        val gradient by navGradientStrength(settled = 1f)
                        ArtistAboutPhoto(
                            url = photoUrl,
                            gradient = { gradient },
                            modifier = Modifier.artistPhotoNavTransition(photoUrl, RoundedCornerShape(28.dp)).fillMaxWidth().height(420.dp),
                            alignment = BiasAlignment(0f, -0.44f),
                        )
                    }
                    Row(
                        modifier = Modifier.artistPhotoNavOverlayContent().fillMaxWidth().padding(horizontal = 28.dp, vertical = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        ArtistAboutCardText(artist, description, descriptionLines, Modifier.weight(1f))
                        Icon(
                            MetrolistIcons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = null,
                            modifier = Modifier.padding(bottom = 2.dp).size(28.dp),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ArtistAboutCardText(
    artist: ArtistItem,
    description: String?,
    descriptionLines: Int,
    modifier: Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            artist.displayTitle,
            style = MaterialTheme.typography.headlineMediumEmphasized,
            fontSize = 30.sp,
            lineHeight = 33.sp,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        val monthlyAudience =
            artist.monthlyListeners
                ?.let { monthlyAudienceText(it, stringResource(Res.string.monthly_audience_short)) }
        if (monthlyAudience != null) {
            Text(monthlyAudience, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        }
        if (!description.isNullOrBlank()) {
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                fontSize = 15.sp,
                lineHeight = 22.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = descriptionLines,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ArtistAbout(
    description: String,
    modifier: Modifier,
) {
    val cardColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.55f)
    val contentColor = MaterialTheme.colorScheme.onSurface
    val descriptionBringIntoViewRequester = remember { BringIntoViewRequester() }
    var descriptionExpanded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Surface(
        onClick = { descriptionExpanded = !descriptionExpanded },
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = cardColor,
        contentColor = contentColor,
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
            Text(
                stringResource(Res.string.about),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = contentColor.copy(alpha = 0.85f),
            )
            Spacer(Modifier.height(6.dp))
            ExpandableText(
                text = description,
                modifier =
                    Modifier
                        .bringIntoViewRequester(descriptionBringIntoViewRequester),
                initiallyExpanded = descriptionExpanded,
                collapsedMaxLines = 3,
                showMoreText = stringResource(Res.string.show_more),
                showLessText = stringResource(Res.string.show_less),
                bodyColor = contentColor.copy(alpha = 0.82f),
                onExpandedChange = { descriptionExpanded = it },
                onExpansionFinished = { scope.launch { descriptionBringIntoViewRequester.bringIntoView() } },
            )
        }
    }
}
