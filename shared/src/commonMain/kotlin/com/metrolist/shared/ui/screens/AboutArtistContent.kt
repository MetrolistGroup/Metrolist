@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class, ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.shared.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.carousel.CarouselDefaults
import androidx.compose.material3.carousel.CarouselState
import androidx.compose.material3.carousel.HorizontalCenteredHeroCarousel
import androidx.compose.material3.carousel.rememberCarouselState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import coil3.request.SuccessResult
import com.metrolist.shared.api.lastfm.LastFM
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.about
import com.metrolist.shared.generated.resources.artist_lastfm_biography
import com.metrolist.shared.generated.resources.artist_lastfm_listeners
import com.metrolist.shared.generated.resources.artist_lastfm_read_more
import com.metrolist.shared.generated.resources.artist_lastfm_scrobbles
import com.metrolist.shared.generated.resources.artist_monthly_audience
import com.metrolist.shared.generated.resources.artist_photo_content_description
import com.metrolist.shared.generated.resources.artist_similar_artists
import com.metrolist.shared.generated.resources.back
import com.metrolist.shared.generated.resources.could_not_load_info
import com.metrolist.shared.generated.resources.genres
import com.metrolist.shared.generated.resources.lastfm_link_text
import com.metrolist.shared.generated.resources.lastfm_profile_hint
import com.metrolist.shared.generated.resources.loading
import com.metrolist.shared.generated.resources.no_bio_hint
import com.metrolist.shared.generated.resources.no_bio_title
import com.metrolist.shared.generated.resources.show_less
import com.metrolist.shared.generated.resources.show_more
import com.metrolist.shared.generated.resources.subscribers
import com.metrolist.shared.generated.resources.total_views
import com.metrolist.shared.model.ArtistItem
import com.metrolist.shared.ui.components.ExpandableText
import com.metrolist.shared.ui.components.images.AsyncImage
import com.metrolist.shared.ui.components.loadingShimmer
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.utils.carouselScroll
import com.metrolist.shared.utils.Logger
import com.metrolist.shared.utils.monthlyListenersCount
import com.metrolist.shared.viewmodels.AboutArtistViewModel
import com.metrolist.shared.viewmodels.ArtistPhoto
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

@Composable
fun AboutArtistContent(
    artistName: String,
    info: ArtistItem?,
    uiState: AboutArtistViewModel.AboutArtistUiState,
    photoActions: ArtistPhotoActions? = null,
    bottomContentPadding: Dp = 0.dp,
    onBackClick: () -> Unit,
) {
    val availablePhotos =
        remember(artistName, info?.thumbnail, uiState.lastFmData?.photos) {
            artistGalleryPhotos(artistName, info?.thumbnail, uiState.lastFmData?.photos.orEmpty())
        }
    val navigating = isNavTransitionRunning()
    var galleryPhotos by remember(info?.id) { mutableStateOf(availablePhotos) }
    LaunchedEffect(availablePhotos, navigating) {
        if (!navigating) galleryPhotos = availablePhotos
    }
    val data = uiState.lastFmData
    val loading = uiState.isLoading && uiState.lastFmData == null
    val galleryState = rememberCarouselState { galleryPhotos.size }
    val context = LocalPlatformContext.current
    val imageLoader = SingletonImageLoader.get(context)
    val scope = rememberCoroutineScope()
    var viewerOpen by rememberSaveable { mutableStateOf(false) }
    var viewerPreview by remember { mutableStateOf<ArtistPhotoPreview?>(null) }

    val viewerVisibility = remember { MutableTransitionState(false) }
    viewerVisibility.targetState = viewerOpen && galleryPhotos.isNotEmpty()
    val galleryInteractive = viewerVisibility.isIdle && !viewerVisibility.currentState
    var viewerPhotoUrl by rememberSaveable { mutableStateOf<String?>(null) }
    var viewerLastPage by rememberSaveable { mutableIntStateOf(0) }
    val viewerPage =
        remember(galleryPhotos, viewerPhotoUrl, viewerLastPage) {
            artistPhotoIndex(galleryPhotos, viewerPhotoUrl, viewerLastPage)
        }
    var hadGalleryPhotos by remember { mutableStateOf(galleryPhotos.isNotEmpty()) }
    LaunchedEffect(galleryPhotos) {
        if (galleryPhotos.isEmpty() && hadGalleryPhotos) viewerOpen = false
        hadGalleryPhotos = galleryPhotos.isNotEmpty()
    }
    var photoJob by remember { mutableStateOf<Job?>(null) }

    fun openPhoto(index: Int) {
        if (photoActions == null || !galleryInteractive || photoJob?.isActive == true) return
        photoJob =
            scope.launch {
                if (galleryPhotos.size > 1 && galleryState.currentItem != index) galleryState.animateScrollToItem(index)
                viewerPreview = artistPhotoPreview(imageLoader, context, galleryPhotos[index].url)
                viewerLastPage = index
                viewerPhotoUrl = galleryPhotos[index].url
                viewerOpen = true
            }
    }

    fun closePhoto() {
        if (!viewerOpen || photoJob?.isActive == true) return
        photoJob =
            scope.launch {
                if (galleryPhotos.size > 1) {
                    galleryState.scrollToItem(viewerPage ?: 0)
                    withFrameNanos { }
                    withFrameNanos { }
                }
                viewerOpen = false
            }
    }

    val prefetched = remember(galleryPhotos) { mutableSetOf<String>() }
    LaunchedEffect(galleryPhotos, imageLoader, context, galleryInteractive, navigating) {
        if (!galleryInteractive || navigating) return@LaunchedEffect
        snapshotFlow { galleryPhotos.drop(galleryState.currentItem + 1).take(GALLERY_PREFETCH_COUNT).map(ArtistPhoto::url) }
            .distinctUntilChanged()
            .collectLatest { urls ->
                urls.filterNot(prefetched::contains).forEach { url ->
                    if (imageLoader.execute(artistPhotoRequest(context, url)) is SuccessResult) prefetched += url
                }
            }
    }
    val uriHandler = LocalUriHandler.current
    val loadingText = stringResource(Res.string.loading)

    fun openLastFm(name: String) {
        runCatching { uriHandler.openUri(LastFM.artistPageUrl(name)) }
            .onFailure { Logger.w(TAG, "unable to open Last.fm profile", it) }
    }
    SharedTransitionLayout {
        @Composable
        fun Gallery(heightLimit: Dp) {
            val navigating = isNavTransitionRunning()
            val cardGradient by navGradientStrength(settled = 0f)

            @Composable
            fun GalleryPhoto(
                index: Int,
                sharedPhoto: Boolean = false,
            ) {
                val photo = galleryPhotos.getOrNull(index) ?: return
                val photoDescription = stringResource(Res.string.artist_photo_content_description, artistName)
                AnimatedVisibility(visible = !viewerOpen, enter = fadeIn(), exit = fadeOut()) {
                    val sharedModifier =
                        if (sharedPhoto) {
                            Modifier.sharedElement(
                                rememberSharedContentState(artistPhotoSharedKey(photo.url)),
                                animatedVisibilityScope = this,
                                boundsTransform = rememberArtistPhotoBoundsTransform(),
                            )
                        } else {
                            Modifier
                        }
                    ArtistPhotoImage(
                        url = photo.url,
                        contentDescription = photoDescription,
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .then(sharedModifier)
                                .clip(MaterialTheme.shapes.large)
                                .clickable(enabled = galleryInteractive && photoActions != null) { openPhoto(index) },
                    )
                }
            }
            if (galleryPhotos.isEmpty()) {
                Spacer(
                    Modifier
                        .padding(horizontal = 16.dp)
                        .fillMaxWidth()
                        .height(300.dp)
                        .loadingShimmer()
                        .clip(MaterialTheme.shapes.large)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                )
            } else if (galleryPhotos.size == 1) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier
                            .padding(horizontal = 16.dp)
                            .widthIn(max = minOf(GALLERY_MAX_WIDTH, heightLimit * GALLERY_RATIO))
                            .fillMaxWidth()
                            .aspectRatio(GALLERY_RATIO),
                    ) {
                        Box(
                            Modifier
                                .artistPhotoNavTransition(galleryPhotos[0].url, MaterialTheme.shapes.large)
                                .fillMaxSize(),
                        ) {
                            GalleryPhoto(0, sharedPhoto = true)
                            if (navigating) {
                                ArtistAboutPhoto(
                                    url = galleryPhotos[0].url,
                                    gradient = { cardGradient },
                                    modifier = Modifier.fillMaxSize().clip(MaterialTheme.shapes.large),
                                )
                            }
                        }
                    }
                }
            } else {
                // Carousel masks are invisible to shared elements; an invisible hero stand-in supplies the resting bounds.
                var galleryCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
                val heroBounds = remember { mutableStateOf<Rect?>(null) }
                BoxWithConstraints(
                    modifier =
                        Modifier
                            .padding(top = 8.dp)
                            .fillMaxWidth()
                            .onPlaced { galleryCoordinates = it },
                    contentAlignment = Alignment.Center,
                ) {
                    // Cap the carousel width rather than item width to preserve currentItem tracking.
                    val peekWidth = 32.dp + (CAROUSEL_SMALL_ITEM + CAROUSEL_SPACING) * 2
                    val focusedWidth = minOf(minOf(maxWidth, GALLERY_MAX_WIDTH) - peekWidth, heightLimit * GALLERY_RATIO)
                    val carouselWidth = focusedWidth + peekWidth
                    val flingBehavior = CarouselDefaults.singleAdvanceFlingBehavior(galleryState)
                    HorizontalCenteredHeroCarousel(
                        state = galleryState,
                        flingBehavior = flingBehavior,
                        minSmallItemWidth = CAROUSEL_SMALL_ITEM,
                        maxSmallItemWidth = CAROUSEL_SMALL_ITEM,
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        itemSpacing = CAROUSEL_SPACING,
                        userScrollEnabled = galleryInteractive,
                        modifier =
                            Modifier
                                .width(carouselWidth)
                                .height(focusedWidth / GALLERY_RATIO)
                                .carouselScroll(galleryState, flingBehavior, enabled = galleryInteractive),
                    ) { index ->
                        val isHero by remember(index, galleryState) { derivedStateOf { index == galleryState.currentItem } }
                        Box(
                            Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    alpha =
                                        if (isHero && (navigating || !galleryInteractive) &&
                                            heroBounds.value != null
                                        ) {
                                            0f
                                        } else {
                                            1f
                                        }
                                }.maskClip(MaterialTheme.shapes.large)
                                .onGloballyPositioned { item ->
                                    val gallery = galleryCoordinates?.takeIf { it.isAttached }
                                    if (isHero && gallery != null) {
                                        val itemBounds = gallery.localBoundingBoxOf(item, clipBounds = false)
                                        heroBounds.value = carouselItemDrawInfo.maskRect.translate(itemBounds.topLeft)
                                    }
                                },
                        ) {
                            GalleryPhoto(index)
                        }
                    }
                    ArtistGalleryHero(
                        bounds = heroBounds,
                        photos = galleryPhotos,
                        galleryState = galleryState,
                        viewerOpen = viewerOpen,
                        showPhoto = navigating || !galleryInteractive,
                        gradient = { cardGradient },
                        modifier = Modifier.align(Alignment.TopStart),
                    )
                }
            }
        }

        fun LazyListScope.details() {
            if (loading) {
                item(key = "loading", contentType = "loading") {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .padding(top = 24.dp)
                            .clearAndSetSemantics {
                                contentDescription = loadingText
                                liveRegion = LiveRegionMode.Polite
                            }.loadingShimmer(),
                        verticalArrangement = Arrangement.spacedBy(24.dp),
                    ) {
                        val color = MaterialTheme.colorScheme.surfaceContainerHigh
                        Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                            repeat(2) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Spacer(Modifier.fillMaxWidth(0.8f).height(32.dp).background(color, MaterialTheme.shapes.small))
                                    Spacer(Modifier.fillMaxWidth(0.6f).height(20.dp).background(color, MaterialTheme.shapes.small))
                                }
                            }
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Spacer(Modifier.width(100.dp).height(28.dp).background(color, MaterialTheme.shapes.small))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                repeat(3) { Spacer(Modifier.weight(1f).height(36.dp).background(color, RoundedCornerShape(100.dp))) }
                            }
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Spacer(Modifier.fillMaxWidth(0.5f).height(28.dp).background(color, MaterialTheme.shapes.small))
                            repeat(6) { index ->
                                val lineWidth = if (index == 5) 0.6f else 1f
                                Spacer(Modifier.fillMaxWidth(lineWidth).height(18.dp).background(color, MaterialTheme.shapes.small))
                            }
                        }
                    }
                }
                return
            }
            val stats =
                listOf(
                    Res.string.artist_lastfm_listeners to data?.listeners,
                    Res.string.artist_lastfm_scrobbles to data?.scrobbles,
                    Res.string.artist_monthly_audience to monthlyListenersCount(info?.monthlyListeners),
                    Res.string.subscribers to info?.subscribers,
                ).filter { !it.second.isNullOrBlank() }
            if (stats.isNotEmpty()) {
                item(key = "stats", contentType = "stats") {
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        stats.chunked(2).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                                row.forEach { (label, count) ->
                                    Column(Modifier.weight(1f)) {
                                        Text(count.orEmpty(), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                                        Text(
                                            stringResource(label),
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (!data?.tags.isNullOrEmpty()) {
                item(key = "tags", contentType = "tags") {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 24.dp)) {
                        Text(stringResource(Res.string.genres), style = MaterialTheme.typography.titleLargeEmphasized)
                        Spacer(Modifier.height(12.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(data.tags) { tag ->
                                Surface(shape = RoundedCornerShape(100.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                                    Text(
                                        tag,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                        style = MaterialTheme.typography.labelLarge,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (!data?.summary.isNullOrBlank()) {
                item(key = "lastfm_bio", contentType = "lastfm_bio") {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 24.dp)) {
                        Text(
                            stringResource(Res.string.artist_lastfm_biography),
                            style = MaterialTheme.typography.titleLargeEmphasized,
                        )
                        Spacer(Modifier.height(12.dp))
                        ExpandableText(
                            text = data.summary,
                            collapsedMaxLines = 6,
                            showMoreText = stringResource(Res.string.show_more),
                            showLessText = stringResource(Res.string.show_less),
                        )
                        TextButton(onClick = {
                            openLastFm(artistName)
                        }, shapes = ButtonDefaults.shapes()) { Text(stringResource(Res.string.artist_lastfm_read_more)) }
                    }
                }
            }
            if (!info?.description.isNullOrBlank()) {
                item(key = "description", contentType = "description") {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 24.dp)) {
                        Text(stringResource(Res.string.about), style = MaterialTheme.typography.titleLargeEmphasized)
                        Spacer(Modifier.height(12.dp))
                        ExpandableText(
                            text = info.description,
                            collapsedMaxLines = 6,
                            showMoreText = stringResource(Res.string.show_more),
                            showLessText = stringResource(Res.string.show_less),
                        )
                    }
                }
            }
            if (!data?.similarArtists.isNullOrEmpty()) {
                item(key = "similar", contentType = "similar") {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 24.dp)) {
                        Text(
                            stringResource(Res.string.artist_similar_artists),
                            style = MaterialTheme.typography.titleLargeEmphasized,
                        )
                        Spacer(Modifier.height(12.dp))
                        data.similarArtists.forEach { name ->
                            TextButton(onClick = { openLastFm(name) }, shapes = ButtonDefaults.shapes()) { Text(name) }
                        }
                    }
                }
            }
            if (!info?.viewCount.isNullOrBlank()) {
                item(key = "views", contentType = "views") {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 24.dp)) {
                        Text(
                            stringResource(Res.string.total_views),
                            style = MaterialTheme.typography.titleLargeEmphasized,
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(info.viewCount, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (info?.description.isNullOrBlank() && data?.summary.isNullOrBlank()) {
                item(key = "no_bio_hint", contentType = "no_bio_hint") {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 24.dp)) {
                        LastfmHintCard(
                            title = stringResource(if (uiState.hasError) Res.string.could_not_load_info else Res.string.no_bio_title),
                            bodyText = stringResource(if (uiState.hasError) Res.string.lastfm_profile_hint else Res.string.no_bio_hint),
                            artistName = artistName,
                        )
                    }
                }
            }
        }
        Box(Modifier.fillMaxSize()) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text(artistName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        navigationIcon = {
                            IconButton(onClick = onBackClick, shapes = IconButtonDefaults.shapes()) {
                                Icon(MetrolistIcons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.back))
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                    )
                },
            ) { padding ->
                BoxWithConstraints(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
                    val showGallery = loading || galleryPhotos.isNotEmpty()
                    if (maxWidth >= WIDE_LAYOUT_WIDTH && showGallery) {
                        val galleryHeight = maxHeight - 32.dp - bottomContentPadding
                        Row(Modifier.widthIn(max = 1400.dp).fillMaxSize()) {
                            Column(Modifier.width(GALLERY_MAX_WIDTH).padding(vertical = 16.dp)) { Gallery(galleryHeight) }
                            LazyColumn(
                                Modifier.weight(1f).fillMaxHeight(),
                                contentPadding = PaddingValues(bottom = 32.dp + bottomContentPadding),
                                userScrollEnabled = galleryInteractive,
                            ) {
                                details()
                            }
                        }
                    } else {
                        LazyColumn(
                            Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = 32.dp + bottomContentPadding),
                            userScrollEnabled = galleryInteractive,
                        ) {
                            if (showGallery) item(key = "gallery", contentType = "gallery") { Gallery(Dp.Infinity) }
                            details()
                        }
                    }
                }
            }
            AnimatedVisibility(visibleState = viewerVisibility, enter = EnterTransition.None, exit = ExitTransition.None) {
                photoActions?.let { actions ->
                    ArtistPhotoViewer(
                        photos = galleryPhotos,
                        initialIndex = viewerPage ?: 0,
                        initialPreview = viewerPreview,
                        artistName = artistName,
                        actions = actions,
                        visibilityScope = this,
                        onPageChange = { index ->
                            viewerLastPage = index
                            viewerPhotoUrl = galleryPhotos.getOrNull(index)?.url
                        },
                        onDismiss = ::closePhoto,
                    )
                }
            }
        }
    }
}

@Composable
private fun SharedTransitionScope.ArtistGalleryHero(
    bounds: State<Rect?>,
    photos: List<ArtistPhoto>,
    galleryState: CarouselState,
    viewerOpen: Boolean,
    showPhoto: Boolean,
    gradient: () -> Float,
    modifier: Modifier,
) {
    val hero = bounds.value?.takeUnless(Rect::isEmpty) ?: return
    val url = photos.getOrNull(galleryState.currentItem)?.url ?: return
    AnimatedVisibility(
        visible = !viewerOpen,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier.absoluteOffset { hero.topLeft.round() }.size(with(LocalDensity.current) { hero.size.toDpSize() }),
    ) {
        Box(
            Modifier
                .artistPhotoNavTransition(url, MaterialTheme.shapes.large)
                .sharedElement(
                    rememberSharedContentState(artistPhotoSharedKey(url)),
                    animatedVisibilityScope = this,
                    boundsTransform = rememberArtistPhotoBoundsTransform(),
                ).fillMaxSize()
                .graphicsLayer { alpha = if (showPhoto) 1f else 0f }
                .clip(MaterialTheme.shapes.large),
        ) {
            if (showPhoto) ArtistAboutPhoto(url = url, gradient = gradient, modifier = Modifier.fillMaxSize())
        }
    }
}

private const val TAG = "AboutArtist"
private const val GALLERY_PREFETCH_COUNT = 2
private val GALLERY_MAX_WIDTH = 520.dp
private val WIDE_LAYOUT_WIDTH = 960.dp
private val CAROUSEL_SMALL_ITEM = 32.dp
private val CAROUSEL_SPACING = 12.dp

@Composable
private fun LastfmHintCard(
    title: String,
    bodyText: String,
    artistName: String,
) {
    val uriHandler = LocalUriHandler.current
    val lastfmUrl = LastFM.artistPageUrl(artistName)

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = bodyText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(
                onClick = {
                    runCatching { uriHandler.openUri(lastfmUrl) }
                        .onFailure { Logger.w(TAG, "unable to open Last.fm profile", it) }
                },
                modifier = Modifier.align(Alignment.End),
                shapes = ButtonDefaults.shapes(),
            ) {
                Text(stringResource(Res.string.lastfm_link_text))
            }
        }
    }
}
