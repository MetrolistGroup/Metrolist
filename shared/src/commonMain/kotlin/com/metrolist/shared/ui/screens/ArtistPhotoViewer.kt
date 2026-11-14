@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.shared.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.Crossfade
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import coil3.compose.rememberAsyncImagePainter
import coil3.memory.MemoryCache
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.crossfade
import coil3.size.Precision
import coil3.size.Size
import com.metrolist.shared.api.lastfm.LastFmPhotoCache
import com.metrolist.shared.api.lastfm.LastFmPhotoDetails
import com.metrolist.shared.api.lastfm.parsePhotoUploadDate
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.action_download
import com.metrolist.shared.generated.resources.artist_photo_content_description
import com.metrolist.shared.generated.resources.artist_photo_details_unavailable
import com.metrolist.shared.generated.resources.artist_photo_open_original
import com.metrolist.shared.generated.resources.artist_photo_position
import com.metrolist.shared.generated.resources.artist_photo_uploaded
import com.metrolist.shared.generated.resources.artist_photo_view_author
import com.metrolist.shared.generated.resources.artist_photo_view_on_lastfm
import com.metrolist.shared.generated.resources.brand_lastfm
import com.metrolist.shared.generated.resources.close
import com.metrolist.shared.generated.resources.error_occurred
import com.metrolist.shared.generated.resources.next
import com.metrolist.shared.generated.resources.previous
import com.metrolist.shared.generated.resources.saved
import com.metrolist.shared.generated.resources.share
import com.metrolist.shared.ui.components.BlurredArtworkBackground
import com.metrolist.shared.ui.components.ContainedLoadingIndicator
import com.metrolist.shared.ui.components.images.ImageLoadingPlaceholder
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.navigation.LocalNavSharedTransitionScope
import com.metrolist.shared.ui.navigation.LocalNavVisibilityScope
import com.metrolist.shared.ui.navigation.LocalNavigationContainerState
import com.metrolist.shared.ui.navigation.NavigationBackHandler
import com.metrolist.shared.ui.navigation.NavigationPattern
import com.metrolist.shared.ui.navigation.acceptsInput
import com.metrolist.shared.ui.navigation.blockInput
import com.metrolist.shared.ui.navigation.expressiveNavigationElement
import com.metrolist.shared.ui.navigation.rememberNavigationMotion
import com.metrolist.shared.ui.statistics.StatisticsFormatter
import com.metrolist.shared.ui.utils.LocalMouseScrollFactors
import com.metrolist.shared.utils.Logger
import com.metrolist.shared.viewmodels.ArtistPhoto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

private const val TAG = "ArtistPhotoViewer"
private const val MAX_ZOOM = 5f
private const val DOUBLE_TAP_ZOOM = 2.5f
internal const val GALLERY_RATIO = 3f / 4f
internal const val ARTIST_PHOTO_REQUEST_PX = 1080

private val OnScrim = Color.White

fun artistPhotoSharedKey(url: String) = "artist-photo-$url"

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun rememberArtistPhotoBoundsTransform(): BoundsTransform {
    val spec = MaterialTheme.motionScheme.defaultSpatialSpec<Rect>()
    return remember(spec) { BoundsTransform { _, _ -> spec } }
}

@Composable
private fun <T> viewerSpec() = MaterialTheme.motionScheme.defaultSpatialSpec<T>()

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun Modifier.artistPhotoNavTransition(
    url: String,
    clip: Shape,
): Modifier = expressiveNavigationElement(artistPhotoSharedKey(url), clip)

@Composable
internal fun isNavTransitionRunning(): Boolean {
    val transition = LocalNavVisibilityScope.current?.transition ?: return false
    return !transition.acceptsInput(EnterExitState.Visible)
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun Modifier.artistPhotoNavOverlayContent(): Modifier {
    val motion = rememberNavigationMotion()
    if (!motion.enabled || LocalNavigationContainerState.current?.change?.pattern != NavigationPattern.Expressive) return this
    val transitions = LocalNavSharedTransitionScope.current ?: return this
    val visibility = LocalNavVisibilityScope.current ?: return this
    return with(transitions) {
        with(visibility) {
            renderInSharedTransitionScopeOverlay(zIndexInOverlay = 1f)
                .animateEnterExit(
                    enter =
                        fadeIn(motion.effectsSpec),
                    exit = fadeOut(motion.effectsSpec),
                )
        }
    }
}

internal fun artistPhotoRequest(
    context: PlatformContext,
    url: String,
): ImageRequest =
    ImageRequest
        .Builder(context)
        .data(url)
        .size(ARTIST_PHOTO_REQUEST_PX, ARTIST_PHOTO_REQUEST_PX)
        .memoryCacheKeyExtra("artist_photo_preview", ARTIST_PHOTO_REQUEST_PX.toString())
        .precision(Precision.INEXACT)
        .crossfade(true)
        .build()

@Composable
internal fun ArtistPhotoImage(
    url: String,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    alignment: Alignment = Alignment.Center,
) {
    val context = LocalPlatformContext.current
    val request = remember(url, context) { artistPhotoRequest(context, url) }
    val painter = rememberAsyncImagePainter(request, contentScale = ContentScale.Crop)
    val state by painter.state.collectAsState()
    Box(modifier) {
        if (state !is AsyncImagePainter.State.Success) {
            ImageLoadingPlaceholder(Modifier.fillMaxSize(), animated = state !is AsyncImagePainter.State.Error)
        }
        Image(
            painter = painter,
            contentDescription = contentDescription,
            modifier = Modifier.fillMaxSize(),
            alignment = alignment,
            contentScale = ContentScale.Crop,
        )
    }
}

@Composable
internal fun navGradientStrength(settled: Float): State<Float> {
    val transition =
        LocalNavVisibilityScope.current?.transition ?: return remember(settled) { mutableFloatStateOf(settled) }
    val spec = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    return transition.animateFloat({ spec }, label = "artistPhotoGradient") { state ->
        if (state == EnterExitState.Visible) settled else 1f - settled
    }
}

@Composable
internal fun ArtistAboutPhoto(
    url: String,
    gradient: () -> Float,
    modifier: Modifier = Modifier,
    alignment: Alignment = Alignment.Center,
) {
    val surface = MaterialTheme.colorScheme.surface
    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainer)) {
        BlurredArtworkBackground(
            url = url,
            modifier =
                Modifier.matchParentSize().graphicsLayer {
                    alpha = 0.55f
                    colorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(1.3f) })
                },
            blurRadiusPx = with(LocalDensity.current) { 48.dp.toPx() },
        )
        ArtistPhotoImage(
            url = url,
            modifier =
                Modifier
                    .fillMaxSize()
                    .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                    .drawWithContent {
                        drawContent()
                        val strength = gradient()
                        drawRect(
                            brush =
                                Brush.verticalGradient(
                                    0.35f to Color.Black,
                                    0.7f to Color.Black.copy(alpha = lerp(1f, 0.35f, strength)),
                                    1f to Color.Black.copy(alpha = 1f - strength),
                                ),
                            blendMode = BlendMode.DstIn,
                        )
                    },
            alignment = alignment,
        )
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.62f)
                .graphicsLayer { alpha = gradient() }
                .background(
                    Brush.verticalGradient(
                        0f to surface.copy(alpha = 0f),
                        0.45f to surface.copy(alpha = 0.72f),
                        1f to surface.copy(alpha = 0.92f),
                    ),
                ),
        )
    }
}

class ArtistPhotoActions(
    val share: (String) -> Unit,
    val save: suspend (ArtistPhoto) -> Boolean,
    val showMessage: (String) -> Unit,
    val onViewerVisibilityChange: (Boolean) -> Unit = {},
)

data class ArtistPhotoPreview(
    val aspectRatio: Float,
    val memoryCacheKey: MemoryCache.Key?,
)

suspend fun artistPhotoPreview(
    imageLoader: ImageLoader,
    context: PlatformContext,
    url: String,
): ArtistPhotoPreview? {
    val result = imageLoader.execute(artistPhotoRequest(context, url)) as? SuccessResult ?: return null
    val image = result.image.takeIf { it.height > 0 } ?: return null
    return ArtistPhotoPreview(image.width.toFloat() / image.height, result.memoryCacheKey)
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun SharedTransitionScope.ArtistPhotoViewer(
    photos: List<ArtistPhoto>,
    initialIndex: Int,
    initialPreview: ArtistPhotoPreview?,
    artistName: String,
    actions: ArtistPhotoActions,
    visibilityScope: AnimatedVisibilityScope,
    onPageChange: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    NavigationBackHandler(onBack = onDismiss)
    DisposableEffect(actions) {
        actions.onViewerVisibilityChange(true)
        onDispose { actions.onViewerVisibilityChange(false) }
    }
    val pagerState = key(photos) { rememberPagerState(initialIndex.coerceIn(0, photos.lastIndex.coerceAtLeast(0))) { photos.size } }
    val previewPhotoUrl = remember { photos.getOrNull(initialIndex)?.url }
    var zoomed by remember(photos) { mutableStateOf(false) }
    val scrimAlpha by visibilityScope.transition.animateFloat(transitionSpec = { viewerSpec() }, label = "photoScrim") {
        if (it == EnterExitState.Visible) 1f else 0f
    }
    val photo = photos.getOrNull(pagerState.currentPage) ?: return
    var detailsVersion by remember { mutableIntStateOf(0) }
    var loadedPage by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(photo.pageUrl, pagerState.isScrollInProgress) {
        if (pagerState.isScrollInProgress) return@LaunchedEffect
        val pageUrl = photo.pageUrl ?: return@LaunchedEffect
        loadedPage = null
        LastFmPhotoCache.shared.get(pageUrl)
        loadedPage = pageUrl
        detailsVersion++
        delay(750)
        for (index in listOf(pagerState.currentPage + 1, pagerState.currentPage - 1)) {
            val neighborUrl = photos.getOrNull(index)?.pageUrl ?: continue
            LastFmPhotoCache.shared.get(neighborUrl, prefetch = true)
            detailsVersion++
        }
    }
    val settled = visibilityScope.transition.acceptsInput(EnterExitState.Visible)
    LaunchedEffect(photos, pagerState.currentPage) { onPageChange(pagerState.currentPage) }
    val scope = rememberCoroutineScope()
    val savingPhotos = remember { mutableStateMapOf<String, Boolean>() }
    val savedText = stringResource(Res.string.saved)
    val errorText = stringResource(Res.string.error_occurred)

    fun save(photoToSave: ArtistPhoto) {
        if (savingPhotos[photoToSave.url] == true) return
        savingPhotos[photoToSave.url] = true
        scope.launch {
            val message =
                try {
                    savedText.takeIf { actions.save(photoToSave) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Logger.w(TAG, "save failed", e)
                    errorText
                } finally {
                    savingPhotos.remove(photoToSave.url)
                }
            message?.let(actions.showMessage)
        }
    }

    val dragging by pagerState.interactionSource.collectIsDraggedAsState()
    val pageButtons = LocalMouseScrollFactors.current != null && settled && !zoomed && !dragging

    BoxWithConstraints(Modifier.fillMaxSize().blockInput(!settled)) {
        val detailsMaxHeight = maxHeight * 0.45f
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = scrimAlpha }
                .background(MaterialTheme.colorScheme.scrim),
        )
        Column(Modifier.fillMaxSize()) {
            ViewerTopBar(
                photo = photo,
                position = pagerState.currentPage + 1,
                count = photos.size,
                onDismiss = onDismiss,
                modifier =
                    with(visibilityScope) {
                        Modifier.animateEnterExit(
                            enter = fadeIn(MaterialTheme.motionScheme.fastEffectsSpec()),
                            exit = fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()),
                        )
                    },
            )
            Box(Modifier.weight(1f).fillMaxWidth()) {
                key(photos) {
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize(),
                        userScrollEnabled = !zoomed && settled,
                        pageSpacing = 16.dp,
                        key = { photos.getOrNull(it)?.url ?: it },
                    ) { page ->
                        val pagePhoto = photos.getOrNull(page) ?: return@HorizontalPager
                        ViewerPhoto(
                            photo = pagePhoto,
                            initialPreview = if (pagePhoto.url == previewPhotoUrl) initialPreview else null,
                            contentDescription = stringResource(Res.string.artist_photo_content_description, artistName),
                            isCurrent = page == pagerState.currentPage,
                            sharedModifier =
                                if (page == pagerState.currentPage) {
                                    Modifier.sharedElement(
                                        rememberSharedContentState(artistPhotoSharedKey(pagePhoto.url)),
                                        animatedVisibilityScope = visibilityScope,
                                        boundsTransform = rememberArtistPhotoBoundsTransform(),
                                    )
                                } else {
                                    Modifier
                                },
                            onZoomChange = { if (page == pagerState.currentPage) zoomed = it },
                        )
                    }
                }
                ViewerPageButton(
                    visible = pageButtons && pagerState.currentPage > 0,
                    icon = MetrolistIcons.Filled.ChevronLeft,
                    label = stringResource(Res.string.previous),
                    modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 12.dp),
                    onClick = { scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) } },
                )
                ViewerPageButton(
                    visible = pageButtons && pagerState.currentPage < photos.lastIndex,
                    icon = MetrolistIcons.Filled.ChevronRight,
                    label = stringResource(Res.string.next),
                    modifier = Modifier.align(Alignment.CenterEnd).padding(horizontal = 12.dp),
                    onClick = { scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) } },
                )
            }
            Crossfade(
                targetState = photo,
                animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
                label = "photoDetails",
                modifier =
                    with(visibilityScope) {
                        Modifier.heightIn(max = detailsMaxHeight).animateContentSize(viewerSpec()).animateEnterExit(
                            enter = slideInVertically(viewerSpec()) { it } + fadeIn(MaterialTheme.motionScheme.fastEffectsSpec()),
                            exit = slideOutVertically(viewerSpec()) { it } + fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()),
                        )
                    },
            ) { displayedPhoto ->
                val details = remember(displayedPhoto, detailsVersion) { displayedPhoto.pageUrl?.let(LastFmPhotoCache.shared::peek) }
                PhotoDetailsSheet(
                    photo = displayedPhoto,
                    saving = savingPhotos[displayedPhoto.url] == true,
                    onSave = { save(displayedPhoto) },
                    details = details,
                    loading = displayedPhoto.pageUrl != null && details == null && loadedPage != displayedPhoto.pageUrl,
                    artistName = artistName,
                    actions = actions,
                )
            }
        }
    }
}

@Composable
private fun ViewerPageButton(
    visible: Boolean,
    icon: ImageVector,
    label: String,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    AnimatedVisibility(
        visible,
        modifier,
        enter = fadeIn(MaterialTheme.motionScheme.fastEffectsSpec()),
        exit = fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()),
    ) {
        FilledTonalIconButton(onClick = onClick, shapes = IconButtonDefaults.shapes()) { Icon(icon, label) }
    }
}

@Composable
private fun ViewerTopBar(
    photo: ArtistPhoto,
    position: Int,
    count: Int,
    onDismiss: () -> Unit,
    modifier: Modifier,
) {
    val uriHandler = LocalUriHandler.current
    Box(modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
        FilledTonalIconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.CenterStart), shapes = IconButtonDefaults.shapes()) {
            Icon(MetrolistIcons.Outlined.Close, stringResource(Res.string.close))
        }
        if (count > 1) {
            Text(
                stringResource(Res.string.artist_photo_position, position, count),
                style = MaterialTheme.typography.titleMedium,
                color = OnScrim,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        FilledTonalIconButton(
            onClick = { runCatching { uriHandler.openUri(photo.url) } },
            modifier = Modifier.align(Alignment.CenterEnd),
            shapes = IconButtonDefaults.shapes(),
        ) {
            Icon(MetrolistIcons.Outlined.Image, stringResource(Res.string.artist_photo_open_original))
        }
    }
}

@Composable
private fun ViewerPhoto(
    photo: ArtistPhoto,
    initialPreview: ArtistPhotoPreview?,
    contentDescription: String,
    isCurrent: Boolean,
    sharedModifier: Modifier,
    onZoomChange: (Boolean) -> Unit,
) {
    val context = LocalPlatformContext.current
    val imageLoader = SingletonImageLoader.get(context)
    val preview by produceState(initialPreview, photo.url) {
        if (value == null) value = artistPhotoPreview(imageLoader, context, photo.url)
    }
    val request =
        remember(photo.url, preview?.memoryCacheKey) {
            ImageRequest
                .Builder(context)
                .data(photo.url)
                .size(Size.ORIGINAL)
                .placeholderMemoryCacheKey(preview?.memoryCacheKey)
                .crossfade(false)
                .build()
        }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    fun applyScale(value: Float) {
        scale = value
        if (value == 1f) offset = Offset.Zero
        onZoomChange(value > 1f)
    }
    LaunchedEffect(isCurrent) { if (!isCurrent) applyScale(1f) }

    // shortcut: zoomed panning is unbounded, clamp offsets if photos can be moved out of view.
    val transformState =
        rememberTransformableState { _, zoomChange, panChange, _ ->
            applyScale((scale * zoomChange).coerceIn(1f, MAX_ZOOM))
            if (scale > 1f) offset += panChange
        }
    BoxWithConstraints(
        modifier =
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(onDoubleTap = { applyScale(if (scale > 1f) 1f else DOUBLE_TAP_ZOOM) })
                }.transformable(transformState, canPan = { scale > 1f }),
        contentAlignment = Alignment.Center,
    ) {
        val ratio = preview?.aspectRatio ?: GALLERY_RATIO
        val width = minOf(maxWidth, maxHeight * ratio)
        AsyncImage(
            model = request,
            contentDescription = contentDescription,
            imageLoader = imageLoader,
            contentScale = ContentScale.Crop,
            modifier =
                Modifier
                    .size(width, width / ratio)
                    .then(sharedModifier)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
        )
    }
}

@Composable
private fun PhotoDetailsSheet(
    photo: ArtistPhoto,
    details: LastFmPhotoDetails?,
    loading: Boolean,
    saving: Boolean,
    onSave: () -> Unit,
    artistName: String,
    actions: ArtistPhotoActions,
) {
    val uriHandler = LocalUriHandler.current
    val scrollState = remember(photo.url) { ScrollState(0) }

    Surface(
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.verticalScroll(scrollState).navigationBarsPadding().padding(horizontal = 24.dp, vertical = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val profileUrl = details?.authorProfileUrl
                val authorLabel = stringResource(Res.string.artist_photo_view_author)
                Row(
                    modifier =
                        Modifier.weight(1f).heightIn(min = 48.dp).clickable(
                            enabled = profileUrl != null,
                            role = Role.Button,
                            onClickLabel = authorLabel,
                        ) { profileUrl?.let { runCatching { uriHandler.openUri(it) } } },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(40.dp)) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                if (details?.authorName != null) MetrolistIcons.Outlined.Person else MetrolistIcons.Outlined.Image,
                                null,
                                Modifier.size(24.dp),
                            )
                            details?.authorAvatarUrl?.let { avatar ->
                                AsyncImage(
                                    avatar,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                    }
                    Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                        Text(
                            details?.authorName ?: if (photo.pageUrl != null) stringResource(Res.string.brand_lastfm) else artistName,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (details?.authorName != null) {
                            Text(
                                stringResource(Res.string.brand_lastfm),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (loading) ContainedLoadingIndicator(Modifier.size(24.dp))
                photo.pageUrl?.let { pageUrl ->
                    IconButton(onClick = { runCatching { uriHandler.openUri(pageUrl) } }, shapes = IconButtonDefaults.shapes()) {
                        Icon(MetrolistIcons.AutoMirrored.Filled.OpenInNew, stringResource(Res.string.artist_photo_view_on_lastfm))
                    }
                }
            }
            details?.title?.let { title ->
                Spacer(Modifier.height(12.dp))
                Text(title, style = MaterialTheme.typography.titleLarge)
            }
            details?.description?.let { description ->
                Spacer(Modifier.height(8.dp))
                Text(description, style = MaterialTheme.typography.bodyMedium)
            }
            details?.uploadDate?.let(::parsePhotoUploadDate)?.let { date ->
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(Res.string.artist_photo_uploaded, StatisticsFormatter().date(date)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (photo.pageUrl != null && !loading && details == null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(Res.string.artist_photo_details_unavailable),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(20.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalIconButton(
                    onClick = { actions.share(photo.pageUrl ?: photo.url) },
                    modifier = Modifier.size(56.dp),
                    shapes = IconButtonDefaults.shapes(),
                ) { Icon(MetrolistIcons.Outlined.Share, stringResource(Res.string.share)) }
                FilledTonalButton(
                    onClick = onSave,
                    enabled = !saving,
                    colors =
                        ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                    contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                    modifier = Modifier.height(56.dp),
                    shapes = ButtonDefaults.shapes(),
                ) {
                    if (saving) {
                        ContainedLoadingIndicator(Modifier.size(ButtonDefaults.IconSize))
                    } else {
                        Icon(MetrolistIcons.Outlined.Download, null, Modifier.size(ButtonDefaults.IconSize))
                    }
                    Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                    Text(stringResource(Res.string.action_download), style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}
