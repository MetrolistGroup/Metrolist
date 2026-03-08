package com.metrolist.shared.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.toPath
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.rectangle
import com.metrolist.shared.constants.GridThumbnailHeight
import com.metrolist.shared.constants.GridTileSpacing
import com.metrolist.shared.constants.ThumbnailCornerRadius
import com.metrolist.shared.constants.speedDialLayout
import com.metrolist.shared.constants.thumbnailHeight
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.loading
import com.metrolist.shared.generated.resources.speed_dial_surprise_action
import com.metrolist.shared.generated.resources.view_artist
import com.metrolist.shared.generated.resources.view_podcast
import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.model.ArtistItem
import com.metrolist.shared.model.EpisodeItem
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.model.PodcastItem
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.YTItem
import com.metrolist.shared.model.canonicalHomeItemId
import com.metrolist.shared.model.displayThumbnailUrl
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.state.GlobalYouTubeRepository
import com.metrolist.shared.state.SongMetadataState
import com.metrolist.shared.ui.components.images.AsyncImage
import com.metrolist.shared.ui.haptics.HapticEvent
import com.metrolist.shared.ui.haptics.rememberMetrolistHaptics
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.menu.onSecondaryClick
import com.metrolist.shared.ui.navigation.LocalRootContentActive
import com.metrolist.shared.ui.navigation.navigationPopupSource
import com.metrolist.shared.ui.playback.LocalPlaybackController
import com.metrolist.shared.ui.theme.uiEnhanced
import com.metrolist.shared.ui.utils.artistArtworkShape
import com.metrolist.shared.ui.utils.carouselScroll
import com.metrolist.shared.utils.isDesktop
import com.metrolist.shared.viewmodels.shouldShowSpeedDialSurprise
import org.jetbrains.compose.resources.stringResource
import kotlin.math.floor

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SpeedDialGrid(
    items: List<YTItem>,
    pinnedIds: List<String>,
    surprisePinned: Boolean,
    onItemClick: (YTItem) -> Unit,
    onShowSpeedDialMenu: ((YTItem) -> Unit)? = null,
    onShowSpeedDialSecondaryMenu: ((YTItem) -> Unit)? = null,
    onShowSurpriseMenu: (() -> Unit)? = null,
    onShowSurpriseSecondaryMenu: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    isLandscape: Boolean = false,
    landscapeMaxItemSize: Dp = 136.dp,
    gridState: LazyGridState,
    showSurprise: Boolean = true,
    surpriseAtEndOfFirstPage: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    currentSongId: State<String?>,
    isPlaying: State<Boolean>,
    isItemEnabled: (YTItem) -> Boolean = { true },
) {
    val haptics = rememberMetrolistHaptics()
    val enabledItems = items.filter(isItemEnabled)
    val showSurprise =
        showSurprise && enabledItems.isNotEmpty() && shouldShowSpeedDialSurprise(items.size, surprisePinned)
    val contentCount = items.size + if (showSurprise) 1 else 0
    val gridItemSize = Settings.shared.gridItemSize
    val itemGap = GridTileSpacing
    val rootActive = LocalRootContentActive.current
    val currentRootActive by rememberUpdatedState(rootActive)
    val currentItems by rememberUpdatedState(items)
    val currentIsItemEnabled by rememberUpdatedState(isItemEnabled)
    val currentOnItemClick by rememberUpdatedState(onItemClick)
    val scheme = MaterialTheme.motionScheme
    val motionScale = rememberCoroutineScope().coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f
    val motion = remember(scheme, motionScale) { SpeedDialRollMotion(scheme, motionScale > 0f) }
    val rollProgress = remember { Animatable(0f) }
    val resultHighlight = remember { Animatable(0f) }
    var pendingRoll by remember { mutableStateOf<SpeedDialRollSelection?>(null) }
    var highlightedItemKey by remember { mutableStateOf<String?>(null) }
    var diceFace by remember { mutableStateOf(5) }
    val playbackController = LocalPlaybackController.current

    LaunchedEffect(rootActive, isLandscape, pendingRoll) {
        val request = pendingRoll ?: return@LaunchedEffect
        try {
            if (!rootActive) return@LaunchedEffect
            rollProgress.snapTo(0f)
            resultHighlight.snapTo(0f)
            rollProgress.animateTo(1f, motion.spatialSpec)
            diceFace = (1..6).filter { it != diceFace }.random()
            if (request.resolve(currentItems, currentIsItemEnabled) == null) return@LaunchedEffect
            highlightedItemKey = request.itemKey
            resultHighlight.animateTo(1f, motion.effectsSpec)
            resultHighlight.animateTo(0f, motion.effectsSpec)
            val currentItem = request.resolve(currentItems, currentIsItemEnabled)
            if (!currentRootActive || currentItem == null) return@LaunchedEffect
            val seed =
                (currentItem as? PlaylistItem)?.let { playlist ->
                    GlobalYouTubeRepository.instance
                        .getPlaylist(playlist.id)
                        .values
                        .flatten()
                        .filterIsInstance<SongItem>()
                        .filterNot { it.isUnavailable }
                        .randomOrNull()
                }
            if (seed != null) playbackController.startRadio(seed) else currentOnItemClick(currentItem)
        } finally {
            pendingRoll = null
            highlightedItemKey = null
        }
    }

    fun contentKey(
        index: Int,
        surpriseIndex: Int,
    ): String {
        if (showSurprise && index == surpriseIndex) return "speed_dial_surprise"
        val itemIndex = if (!showSurprise || index < surpriseIndex) index else index - 1
        return canonicalHomeItemId(items[itemIndex])
    }

    @Composable
    fun Cell(
        contentIndex: Int,
        surpriseIndex: Int,
        itemModifier: Modifier,
    ) {
        if (showSurprise && contentIndex == surpriseIndex) {
            Dice(
                isPinned = surprisePinned,
                onShowMenu = onShowSurpriseMenu,
                onShowSecondaryMenu = onShowSurpriseSecondaryMenu,
                modifier = itemModifier,
                face = diceFace,
                rollFrame = motion.frame(rollProgress.value),
                isRolling = pendingRoll != null,
                motion = motion,
                onRoll = {
                    if (currentRootActive && pendingRoll == null) {
                        enabledItems.randomOrNull()?.let { item ->
                            haptics.perform(HapticEvent.DICE_ROLL)
                            pendingRoll = SpeedDialRollSelection(item)
                        }
                    }
                },
            )
        } else {
            val itemIndex = if (!showSurprise || contentIndex < surpriseIndex) contentIndex else contentIndex - 1
            val item = items[itemIndex]
            val enabled = isItemEnabled(item)
            val playback by rememberItemPlayback(item.id, currentSongId, isPlaying)
            SpeedDialGridItem(
                item = item,
                isPinned = item.id in pinnedIds,
                modifier = itemModifier,
                onClick = {
                    pendingRoll = null
                    onItemClick(item)
                },
                onShowMenu = onShowSpeedDialMenu?.takeIf { enabled }?.let { menu -> { menu(item) } },
                onShowSecondaryMenu =
                    onShowSpeedDialSecondaryMenu?.takeIf { enabled }?.let { menu -> { menu(item) } },
                isCurrent = playback.isCurrent,
                isPlaying = playback.isPlaying,
                enabled = enabled,
                rollHighlight = if (canonicalHomeItemId(item) == highlightedItemKey) resultHighlight.value else 0f,
            )
        }
    }

    if (isLandscape) {
        val rows = 3
        val surpriseIndex = if (surpriseAtEndOfFirstPage) items.size.coerceAtMost(8) else 0
        val landscapeItemSize = landscapeMaxItemSize * (gridItemSize.thumbnailHeight / GridThumbnailHeight)
        val flingBehavior = if (isDesktop) ScrollableDefaults.flingBehavior() else rememberSnapFlingBehavior(gridState, SnapPosition.Start)
        val gridHeight = landscapeItemSize * rows + itemGap * (rows - 1)

        LazyHorizontalGrid(
            rows = GridCells.Fixed(rows),
            state = gridState,
            flingBehavior = flingBehavior,
            modifier =
                modifier
                    .fillMaxWidth()
                    .height(gridHeight)
                    .clipToBounds()
                    .focusGroup()
                    .carouselScroll(gridState, flingBehavior),
            contentPadding = contentPadding,
            horizontalArrangement = Arrangement.spacedBy(itemGap),
            verticalArrangement = Arrangement.spacedBy(itemGap),
        ) {
            items(
                count = contentCount,
                key = { index -> contentKey(index, surpriseIndex) },
                contentType = { index -> if (showSurprise && index == surpriseIndex) "surprise" else "artwork" },
            ) { index ->
                Cell(index, surpriseIndex, Modifier.size(landscapeItemSize))
            }
        }
    } else {
        BoxWithConstraints(modifier) {
            val layout = speedDialLayout(maxWidth, gridItemSize)
            val itemsPerPage = layout.itemsPerPage
            val surpriseIndex = if (surpriseAtEndOfFirstPage) items.size.coerceAtMost(itemsPerPage - 1) else 0
            val tileSize = (maxWidth - itemGap * (layout.columns - 1)) / layout.columns
            val pageHeight = tileSize * layout.rows + itemGap * (layout.rows - 1)
            val pagerState = rememberPagerState(pageCount = { (contentCount + itemsPerPage - 1) / itemsPerPage })
            val flingBehavior = PagerDefaults.flingBehavior(pagerState)
            Column {
                HorizontalPager(
                    state = pagerState,
                    key = { page -> contentKey(page * itemsPerPage, surpriseIndex) },
                    pageSpacing = 16.dp,
                    flingBehavior = flingBehavior,
                    modifier = Modifier.clipToBounds().focusGroup().carouselScroll(pagerState, flingBehavior),
                ) { page ->
                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(pageHeight),
                        verticalArrangement = Arrangement.spacedBy(itemGap),
                    ) {
                        for (row in 0 until layout.rows) {
                            Row(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .weight(1f),
                                horizontalArrangement = Arrangement.spacedBy(itemGap),
                            ) {
                                for (col in 0 until layout.columns) {
                                    val contentIndex = page * itemsPerPage + col * layout.rows + row
                                    if (contentIndex < contentCount) {
                                        key(contentKey(contentIndex, surpriseIndex)) {
                                            Cell(contentIndex, surpriseIndex, Modifier.weight(1f).fillMaxHeight())
                                        }
                                    } else {
                                        Spacer(Modifier.weight(1f).fillMaxHeight())
                                    }
                                }
                            }
                        }
                    }
                }

                if (pagerState.pageCount > 1) {
                    Spacer(Modifier.height(8.dp))
                    SpeedDialPageIndicator(pagerState)
                }
            }
        }
    }
}

@Composable
private fun SpeedDialPageIndicator(pagerState: PagerState) {
    Row(
        modifier = Modifier.height(22.dp).fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val pageCount = pagerState.pageCount
        val inactiveColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        val activeColor = MaterialTheme.colorScheme.primary
        Canvas(Modifier.size(width = 12.dp * pageCount - 6.dp, height = 6.dp)) {
            val dot = size.height
            val pitch = dot * 2
            val radius = CornerRadius(dot / 2)
            repeat(pageCount) {
                drawRoundRect(inactiveColor, Offset(it * pitch, 0f), Size(dot, dot), radius)
            }
            val position =
                (pagerState.currentPage + pagerState.currentPageOffsetFraction)
                    .coerceIn(0f, (pageCount - 1).toFloat())
            val page = floor(position)
            val fraction = position - page
            // Worm: the leading edge reaches the next dot first, then the trailing edge catches up.
            val left = (page + (fraction * 2 - 1).coerceAtLeast(0f)) * pitch
            val right = (page + (fraction * 2).coerceAtMost(1f)) * pitch + dot
            drawRoundRect(activeColor, Offset(left, 0f), Size(right - left, dot), radius)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SpeedDialGridItem(
    item: YTItem,
    isPinned: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onShowMenu: (() -> Unit)? = null,
    onShowSecondaryMenu: (() -> Unit)? = null,
    isCurrent: Boolean = false,
    isPlaying: Boolean = false,
    enabled: Boolean = true,
    rollHighlight: Float = 0f,
) {
    val displayItem = if (item is PlaylistItem) localizedBuiltinPlaylist(item) else item
    val shape = RoundedCornerShape(uiEnhanced(ThumbnailCornerRadius, 12.dp))
    val artworkShape =
        when (item) {
            is ArtistItem -> artistArtworkShape(item.id)
            is PodcastItem -> CircleShape
            else -> shape
        }
    val profileContentDescription =
        when (item) {
            is ArtistItem -> Res.string.view_artist
            is PodcastItem -> Res.string.view_podcast
            else -> null
        }
    Box(
        modifier =
            modifier
                .navigationPopupSource(shape)
                .graphicsLayer { alpha = if (enabled) 1f else 0.38f }
                .clip(shape)
                .focusedItemBorder(shape)
                .onPreviewKeyEvent { event ->
                    if (enabled && event.type == KeyEventType.KeyDown &&
                        (event.key == Key.Menu || (event.key == Key.F10 && event.isShiftPressed)) &&
                        (onShowSecondaryMenu ?: onShowMenu) != null
                    ) {
                        (onShowSecondaryMenu ?: onShowMenu)?.invoke()
                        true
                    } else {
                        false
                    }
                }.combinedClickable(
                    enabled = enabled,
                    onClick = {
                        CollectionPreviews.record(item)
                        onClick()
                    },
                    onLongClick = onShowMenu,
                ).onSecondaryClick(onShowSecondaryMenu ?: onShowMenu),
    ) {
        if (displayItem is PlaylistItem) {
            PlaylistCoverImage(displayItem, Modifier.fillMaxSize().clip(artworkShape))
        } else if (displayItem.thumbnail.isAutoPlaylistIcon()) {
            AutoPlaylistThumbnail(displayItem.thumbnail, Modifier.fillMaxSize(), artworkShape)
        } else if (displayItem.thumbnail != null) {
            AsyncImage(
                url = displayItem.displayThumbnailUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clip(artworkShape),
            )
        }

        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors =
                                listOf(
                                    Color.Transparent,
                                    Color.Transparent,
                                    Color.Black.copy(alpha = 0.8f),
                                ),
                        ),
                    ),
        )

        if (!Settings.shared.uiEnhancements && (item is SongItem || item is EpisodeItem)) {
            SongThumbnailPlayOverlay(
                isActive = isCurrent,
                isPlaying = isPlaying,
                shape = artworkShape,
            )
        }

        Row(
            modifier =
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (item is SongItem) {
                SongStatusBadges(item)
            } else if (item is AlbumItem && item.isExplicit) {
                ExplicitBadge()
            }
            Text(
                text = (item as? SongItem)?.let(SongMetadataState::apply)?.title ?: displayItem.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            profileContentDescription?.let {
                Icon(
                    MetrolistIcons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = stringResource(it),
                    tint = Color.White,
                    modifier = Modifier.size(16.dp),
                )
            }
        }

        if (isPinned) {
            SpeedDialPinBadge(Modifier.align(Alignment.TopEnd).padding(6.dp))
        }
        if (rollHighlight > 0f) {
            Box(
                Modifier
                    .matchParentSize()
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = rollHighlight * 0.2f))
                    .border(3.dp, MaterialTheme.colorScheme.primary.copy(alpha = rollHighlight), shape),
            )
        }
    }
}

private val DiceCornerRadius = 12.dp

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Dice(
    isPinned: Boolean,
    onShowMenu: (() -> Unit)?,
    onShowSecondaryMenu: (() -> Unit)?,
    face: Int,
    rollFrame: DiceRollFrame,
    isRolling: Boolean,
    motion: SpeedDialRollMotion,
    onRoll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var diceWidthPx by remember { mutableIntStateOf(0) }
    val cornerPx = with(LocalDensity.current) { DiceCornerRadius.toPx() }
    val morph =
        remember(diceWidthPx, cornerPx) {
            // The rectangle spans 2 units before normalizing, so the rounding is the corner's share of half the width.
            val rounding = if (diceWidthPx > 0) (cornerPx * 2f / diceWidthPx).coerceAtMost(1f) else 0f
            Morph(RoundedPolygon.rectangle(rounding = CornerRounding(rounding)).normalized(), MaterialShapes.Cookie4Sided)
        }
    val shape =
        remember(morph, rollFrame.morph) {
            GenericShape { size, _ ->
                addPath(morph.toPath(rollFrame.morph))
                transform(Matrix().apply { scale(size.width, size.height) })
            }
        }
    val surpriseLabel = stringResource(Res.string.speed_dial_surprise_action)
    val loadingLabel = stringResource(Res.string.loading)

    Box(
        modifier =
            modifier
                .onSizeChanged { diceWidthPx = it.width }
                .navigationPopupSource(shape)
                .clip(shape)
                .background(MaterialTheme.colorScheme.secondaryContainer)
                .focusedItemBorder(shape)
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown &&
                        (event.key == Key.Menu || (event.key == Key.F10 && event.isShiftPressed)) &&
                        (onShowSecondaryMenu ?: onShowMenu) != null
                    ) {
                        (onShowSecondaryMenu ?: onShowMenu)?.invoke()
                        true
                    } else {
                        false
                    }
                }.semantics {
                    contentDescription = surpriseLabel
                    if (isRolling) stateDescription = loadingLabel
                }.combinedClickable(
                    role = Role.Button,
                    onLongClick = { onShowMenu?.invoke() },
                    onClick = onRoll,
                ).onSecondaryClick(onShowSecondaryMenu ?: onShowMenu),
        contentAlignment = Alignment.Center,
    ) {
        if (isPinned) {
            SpeedDialPinBadge(Modifier.align(Alignment.TopEnd).padding(6.dp))
        }
        AnimatedContent(
            targetState = face,
            modifier =
                Modifier.graphicsLayer {
                    rotationZ = rollFrame.rotation
                    scaleX = rollFrame.scaleX
                    scaleY = rollFrame.scaleY
                },
            transitionSpec = {
                fadeIn(motion.effectsSpec).togetherWith(fadeOut(motion.effectsSpec))
            },
            contentAlignment = Alignment.Center,
            label = "diceFace",
        ) { diceFace ->
            DiceFace(
                face = diceFace,
                modifier = Modifier.fillMaxSize(),
                dotColor = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

@Composable
private fun DiceFace(
    face: Int,
    modifier: Modifier = Modifier,
    dotColor: Color,
) {
    val dotPositions =
        remember(face) {
            when (face) {
                1 -> listOf(4)
                2 -> listOf(0, 8)
                3 -> listOf(0, 4, 8)
                4 -> listOf(0, 2, 6, 8)
                5 -> listOf(0, 2, 4, 6, 8)
                6 -> listOf(0, 2, 3, 5, 6, 8)
                else -> listOf(4)
            }
        }

    Canvas(modifier) {
        val dotSize = size.minDimension * (15f / 123f)
        val spacing = size.minDimension * (25f / 123f)
        dotPositions.forEach { index ->
            drawCircle(
                color = dotColor,
                radius = dotSize / 2,
                center =
                    center +
                        Offset(
                            (index % 3 - 1) * spacing,
                            (index / 3 - 1) * spacing,
                        ),
            )
        }
    }
}

@Composable
fun Modifier.focusedItemBorder(shape: Shape): Modifier = focusOutline(shape)
