@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.shared.ui.wrapped

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.metrolist.shared.db.HistoryStatisticsSnapshot
import com.metrolist.shared.db.HistorySyncOutcome
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.db.historyChanges
import com.metrolist.shared.db.refreshYouTubeHistory
import com.metrolist.shared.db.wrappedStatisticsSnapshot
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.back
import com.metrolist.shared.generated.resources.next
import com.metrolist.shared.generated.resources.previous
import com.metrolist.shared.generated.resources.refresh
import com.metrolist.shared.generated.resources.statistics_load_failed
import com.metrolist.shared.generated.resources.statistics_plays_label
import com.metrolist.shared.generated.resources.statistics_top_albums
import com.metrolist.shared.generated.resources.statistics_top_artists
import com.metrolist.shared.generated.resources.statistics_top_tracks
import com.metrolist.shared.generated.resources.wrapped
import com.metrolist.shared.generated.resources.wrapped_chapter
import com.metrolist.shared.generated.resources.wrapped_cover_heading
import com.metrolist.shared.generated.resources.wrapped_empty
import com.metrolist.shared.generated.resources.wrapped_history_scope
import com.metrolist.shared.generated.resources.wrapped_measured_time
import com.metrolist.shared.generated.resources.wrapped_merge_note
import com.metrolist.shared.generated.resources.wrapped_share_caption
import com.metrolist.shared.generated.resources.wrapped_share_summary
import com.metrolist.shared.generated.resources.wrapped_soundtrack
import com.metrolist.shared.generated.resources.wrapped_soundtrack_failed
import com.metrolist.shared.generated.resources.wrapped_soundtrack_loading
import com.metrolist.shared.generated.resources.wrapped_soundtrack_unavailable
import com.metrolist.shared.generated.resources.wrapped_summary
import com.metrolist.shared.generated.resources.wrapped_sync_failed
import com.metrolist.shared.generated.resources.wrapped_time_coverage
import com.metrolist.shared.generated.resources.wrapped_time_heading
import com.metrolist.shared.generated.resources.wrapped_year
import com.metrolist.shared.ui.components.ContainedLoadingIndicator
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.navigation.NavigationBackHandler
import com.metrolist.shared.ui.playback.PlaybackController
import com.metrolist.shared.ui.screens.StatisticsItem
import com.metrolist.shared.ui.screens.StatisticsPeriod
import com.metrolist.shared.ui.screens.StatisticsUiState
import com.metrolist.shared.ui.screens.statisticsDuration
import com.metrolist.shared.ui.screens.statisticsPlayCountDuration
import com.metrolist.shared.ui.statistics.StatisticsDateRange
import com.metrolist.shared.ui.statistics.StatisticsPeriodMode
import com.metrolist.shared.ui.statistics.StatisticsShareDialog
import com.metrolist.shared.ui.statistics.epochRange
import com.metrolist.shared.ui.statistics.rememberStatisticsFormatter
import com.metrolist.shared.ui.statistics.statisticsDateRangeLabel
import com.metrolist.shared.ui.utils.DragScrollableGrid
import com.metrolist.shared.ui.utils.desktopArrowScroll
import com.metrolist.shared.ui.utils.segmentedGroupShape
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs
import kotlin.time.Clock

internal enum class WrappedChapter {
    COVER,
    TIME,
    TRACKS,
    ARTISTS,
    ALBUMS,
    SUMMARY,
}

@Composable
fun WrappedScreen(
    database: MusicDatabase,
    playbackController: PlaybackController,
    onBack: () -> Unit,
    onExport: suspend (ImageBitmap) -> Boolean,
    exportLabel: String,
    desktopLayout: Boolean = false,
    bottomContentPadding: Dp = 0.dp,
) {
    val today = Clock.System.todayIn(TimeZone.currentSystemDefault())
    val range = remember(today) { StatisticsDateRange(LocalDate(today.year, 1, 1), today) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var snapshot by remember(database, range) { mutableStateOf<HistoryStatisticsSnapshot?>(null) }
    var loadFailed by remember(database, range) { mutableStateOf(false) }
    var syncFailed by remember(database, range) { mutableStateOf(false) }
    var loading by remember(database, range) { mutableStateOf(true) }
    LaunchedEffect(database, range, refreshKey) {
        database.historyChanges().collectLatest {
            try {
                loadFailed = false
                val (since, _) = range.epochRange()
                snapshot = database.wrappedStatisticsSnapshot(since, Clock.System.now().toEpochMilliseconds())
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                loadFailed = true
            } finally {
                loading = false
            }
        }
    }
    LaunchedEffect(database, range, refreshKey) {
        syncFailed = false
        try {
            syncFailed = withTimeout(30_000) { database.refreshYouTubeHistory() } == HistorySyncOutcome.FAILED
        } catch (_: TimeoutCancellationException) {
            syncFailed = true
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            syncFailed = true
        }
    }
    NavigationBackHandler(onBack = onBack)
    MaterialTheme(motionScheme = MotionScheme.expressive()) {
        val title = stringResource(Res.string.wrapped)
        Surface(Modifier.fillMaxSize().semantics { paneTitle = title }) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
                val result = snapshot
                val hasReport = !loadFailed && result != null && result.totals.playCount > 0
                if (!hasReport) {
                    WrappedToolbar(title, onBack, { refreshKey++ })
                    if (syncFailed) WrappedSyncFailure()
                }
                when {
                    loading && result == null -> {
                        Box(
                            Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) { ContainedLoadingIndicator() }
                    }

                    loadFailed -> {
                        WrappedMessage(stringResource(Res.string.statistics_load_failed))
                    }

                    result == null || result.totals.playCount == 0 -> {
                        WrappedMessage(stringResource(Res.string.wrapped_empty))
                    }

                    else -> {
                        WrappedReport(
                            result,
                            range,
                            playbackController,
                            onExport,
                            exportLabel,
                            desktopLayout,
                            bottomContentPadding,
                            onBack,
                            { refreshKey++ },
                            syncFailed,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WrappedToolbar(
    title: String,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(MetrolistIcons.AutoMirrored.Filled.ArrowBack, stringResource(Res.string.back))
        }
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
        IconButton(onClick = onRefresh) {
            Icon(MetrolistIcons.Filled.Refresh, stringResource(Res.string.refresh))
        }
    }
}

@Composable
private fun WrappedSyncFailure() {
    Text(
        stringResource(Res.string.wrapped_sync_failed),
        Modifier.padding(horizontal = 24.dp),
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun WrappedMessage(message: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(message, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun WrappedReport(
    snapshot: HistoryStatisticsSnapshot,
    range: StatisticsDateRange,
    playbackController: PlaybackController,
    onExport: suspend (ImageBitmap) -> Boolean,
    exportLabel: String,
    desktopLayout: Boolean,
    bottomContentPadding: Dp,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    syncFailed: Boolean,
) {
    val formatter = rememberStatisticsFormatter()
    val state =
        StatisticsUiState(
            period = StatisticsPeriod.YEAR,
            mode = StatisticsPeriodMode.CALENDAR,
            dateRange = range,
            totalPlays = snapshot.totals.playCount,
            totalTimeMs = snapshot.totals.playTimeMs,
            artistCount = snapshot.artists.size,
            albumCount = snapshot.albums.size,
            tracks =
                snapshot.songs.map {
                    StatisticsItem(it.songId, it.title, statisticsPlayCountDuration(it.playCount, it.playTimeMs), it.thumbnailUrl)
                },
            artists =
                snapshot.artists.map {
                    StatisticsItem(
                        it.id ?: it.name,
                        it.name,
                        statisticsPlayCountDuration(it.playCount, it.playTimeMs),
                        it.thumbnailUrl,
                        circularArtwork = true,
                    )
                },
            albums =
                snapshot.albums.map {
                    StatisticsItem(it.id ?: it.name, it.name, statisticsPlayCountDuration(it.playCount, it.playTimeMs), it.thumbnailUrl)
                },
        )
    val chapters =
        buildList {
            add(WrappedChapter.COVER)
            if (state.totalTimeMs > 0) add(WrappedChapter.TIME)
            if (state.tracks.isNotEmpty()) add(WrappedChapter.TRACKS)
            if (state.artists.isNotEmpty()) add(WrappedChapter.ARTISTS)
            if (state.albums.isNotEmpty()) add(WrappedChapter.ALBUMS)
            add(WrappedChapter.SUMMARY)
        }
    val soundtrack = rememberWrappedSoundtrack(playbackController, snapshot.songItems.take(10))
    val motionEnabled = wrappedMotionEnabled()
    var sharing by rememberSaveable { mutableStateOf(false) }
    val title = stringResource(Res.string.wrapped_year, range.start.year.toString())
    val dateLabel = statisticsDateRangeLabel(range)
    val measuredPlays = snapshot.songs.sumOf { it.measuredPlayCount }
    if (sharing) {
        StatisticsShareDialog(
            state = state,
            period = dateLabel,
            summary = stringResource(Res.string.wrapped_share_caption),
            exportLabel = exportLabel,
            onExport = onExport,
            onDismiss = { sharing = false },
            title = title,
            timeLabel = stringResource(Res.string.wrapped_measured_time),
        )
    }
    val pager = rememberPagerState { chapters.size }
    val scope = rememberCoroutineScope()
    val spatialSpec = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()

    fun moveTo(page: Int) {
        val target = page.coerceIn(0, chapters.lastIndex)
        scope.launch {
            if (motionEnabled) pager.animateScrollToPage(target, animationSpec = spatialSpec) else pager.scrollToPage(target)
        }
    }
    val foreground =
        if (desktopLayout) {
            MaterialTheme.colorScheme.onSurface
        } else {
            wrappedChapterContentColor(
                chapters[pager.currentPage.coerceIn(0, chapters.lastIndex)],
            )
        }
    val iconColors = IconButtonDefaults.iconButtonColors(contentColor = foreground, disabledContentColor = foreground.copy(alpha = 0.38f))
    CompositionLocalProvider(LocalContentColor provides foreground) {
        Box(Modifier.fillMaxSize()) {
            if (!desktopLayout) {
                WrappedBackdrop(
                    chapters,
                    { pager.currentPage + pager.currentPageOffsetFraction },
                    motionEnabled,
                    Modifier.matchParentSize(),
                )
            }
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(if (desktopLayout) 12.dp else 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack, colors = iconColors) {
                        Icon(MetrolistIcons.AutoMirrored.Filled.ArrowBack, stringResource(Res.string.back))
                    }
                    if (desktopLayout) {
                        Text(
                            stringResource(Res.string.wrapped),
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    ToggleButton(
                        modifier = if (desktopLayout) Modifier else Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        checked = soundtrack.enabled,
                        onCheckedChange = { soundtrack.toggle() },
                        enabled = soundtrack.enabled || (soundtrack.available && !soundtrack.loading),
                    ) {
                        if (LocalDensity.current.fontScale < 1.5f) {
                            Icon(MetrolistIcons.Outlined.MusicNote, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(
                            stringResource(
                                if (soundtrack.loading) Res.string.wrapped_soundtrack_loading else Res.string.wrapped_soundtrack,
                            ),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = onRefresh, colors = iconColors) {
                        Icon(MetrolistIcons.Filled.Refresh, stringResource(Res.string.refresh))
                    }
                    if (desktopLayout) {
                        FilledTonalButton(onClick = { sharing = true }, shapes = ButtonDefaults.shapes()) {
                            Icon(MetrolistIcons.Outlined.Share, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(Res.string.wrapped_share_summary))
                        }
                    }
                }
                if (syncFailed) WrappedSyncFailure()
                if (soundtrack.failed || !soundtrack.available) {
                    Text(
                        stringResource(
                            if (soundtrack.failed) Res.string.wrapped_soundtrack_failed else Res.string.wrapped_soundtrack_unavailable,
                        ),
                        Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (desktopLayout) {
                    val grid = rememberLazyGridState()
                    DragScrollableGrid(
                        columns = GridCells.Adaptive(400.dp),
                        state = grid,
                        modifier =
                            Modifier
                                .widthIn(max = 1200.dp)
                                .fillMaxSize()
                                .align(Alignment.CenterHorizontally)
                                .desktopArrowScroll(grid),
                        contentPadding = PaddingValues(start = 24.dp, top = 12.dp, end = 24.dp, bottom = bottomContentPadding + 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        items(chapters, key = { it.name }, span = {
                            GridItemSpan(if (it == WrappedChapter.COVER || it == WrappedChapter.SUMMARY) maxLineSpan else 1)
                        }) { chapter ->
                            Surface(shape = RoundedCornerShape(32.dp), color = Color.Transparent) {
                                Box {
                                    WrappedBackdrop(listOf(chapter), { 0f }, false, Modifier.matchParentSize())
                                    WrappedChapterContent(
                                        chapter,
                                        state,
                                        title,
                                        dateLabel,
                                        measuredPlays,
                                        true,
                                        motionEnabled,
                                        Modifier.padding(28.dp),
                                    ) {
                                        sharing = true
                                    }
                                }
                            }
                        }
                    }
                } else {
                    WrappedChapterRail(chapters.size, pager.currentPage, motionEnabled) { moveTo(it) }
                    HorizontalPager(
                        state = pager,
                        key = { chapters[it].name },
                        flingBehavior = PagerDefaults.flingBehavior(pager, snapAnimationSpec = if (motionEnabled) spatialSpec else snap()),
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.Top,
                    ) { page ->
                        val scroll = rememberScrollState()
                        val direction = LocalLayoutDirection.current
                        BoxWithConstraints(
                            Modifier.fillMaxSize().pointerInput(chapters.size, direction, scroll, pager, motionEnabled) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    val scrollingAtDown = scroll.isScrollInProgress || pager.isScrollInProgress
                                    if (down.isConsumed) return@awaitEachGesture
                                    val up = waitForUpOrCancellation() ?: return@awaitEachGesture
                                    if (scrollingAtDown || scroll.isScrollInProgress || pager.isScrollInProgress) return@awaitEachGesture
                                    if (up.id != down.id ||
                                        up.uptimeMillis - down.uptimeMillis >= viewConfiguration.longPressTimeoutMillis
                                    ) {
                                        return@awaitEachGesture
                                    }
                                    if ((up.position - down.position).getDistance() > viewConfiguration.touchSlop) return@awaitEachGesture
                                    val left = down.position.x < size.width * 0.24f
                                    val right = down.position.x > size.width * 0.76f
                                    if (!left && !right) return@awaitEachGesture
                                    val delta = if (right == (direction == LayoutDirection.Ltr)) 1 else -1
                                    val target = (pager.currentPage + delta).coerceIn(0, chapters.lastIndex)
                                    if (target != pager.currentPage) {
                                        up.consume()
                                        moveTo(target)
                                    }
                                }
                            },
                        ) {
                            val visible = abs(pager.currentPage + pager.currentPageOffsetFraction - page) < 1f
                            WrappedChapterContent(
                                chapters[page],
                                state,
                                title,
                                dateLabel,
                                measuredPlays,
                                visible,
                                motionEnabled,
                                Modifier
                                    .fillMaxWidth()
                                    .verticalScroll(scroll)
                                    .heightIn(min = maxHeight)
                                    .padding(24.dp),
                            ) { sharing = true }
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = bottomContentPadding + 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = { moveTo(pager.targetPage - 1) }, enabled = pager.currentPage > 0, colors = iconColors) {
                            Icon(MetrolistIcons.AutoMirrored.Filled.ArrowBack, stringResource(Res.string.previous))
                        }
                        Spacer(Modifier.weight(1f))
                        Text(
                            stringResource(
                                Res.string.wrapped_chapter,
                                formatter.number((pager.currentPage + 1).toLong()),
                                formatter.number(chapters.size.toLong()),
                            ),
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                        Spacer(Modifier.weight(1f))
                        IconButton(
                            onClick = { moveTo(pager.targetPage + 1) },
                            enabled = pager.currentPage < chapters.lastIndex,
                            colors = iconColors,
                        ) {
                            Icon(MetrolistIcons.AutoMirrored.Filled.ArrowForward, stringResource(Res.string.next))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WrappedChapterRail(
    count: Int,
    current: Int,
    motionEnabled: Boolean,
    onSelect: (Int) -> Unit,
) {
    val formatter = rememberStatisticsFormatter()
    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(count) { index ->
            val selected by animateFloatAsState(
                if (index ==
                    current
                ) {
                    1.65f
                } else {
                    1f
                },
                if (motionEnabled) MaterialTheme.motionScheme.fastSpatialSpec() else snap(),
            )
            val label = stringResource(Res.string.wrapped_chapter, formatter.number((index + 1).toLong()), formatter.number(count.toLong()))
            Box(
                Modifier.weight(selected).heightIn(min = 48.dp).clickable(onClickLabel = label) { onSelect(index) }.semantics {
                    contentDescription = label
                    this.selected = index == current
                },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(if (index <= current) 5.dp else 2.dp)
                        .clip(CircleShape)
                        .background(LocalContentColor.current),
                )
            }
        }
    }
}

@Composable
private fun wrappedChapterContentColor(chapter: WrappedChapter): Color {
    val colors = MaterialTheme.colorScheme
    val pureBlack = colors.surface == Color.Black && colors.surfaceContainer == Color.Black
    return if (pureBlack) {
        colors.onSurface
    } else {
        when (chapter) {
            WrappedChapter.COVER, WrappedChapter.TRACKS -> colors.onPrimaryContainer
            WrappedChapter.TIME, WrappedChapter.ARTISTS -> colors.onTertiaryContainer
            WrappedChapter.ALBUMS -> colors.onSecondaryContainer
            WrappedChapter.SUMMARY -> colors.onSurface
        }
    }
}

@Composable
private fun WrappedChapterContent(
    chapter: WrappedChapter,
    state: StatisticsUiState,
    title: String,
    dateLabel: String,
    measuredPlays: Int,
    visible: Boolean,
    motionEnabled: Boolean,
    modifier: Modifier = Modifier,
    onShare: () -> Unit,
) {
    val foreground = wrappedChapterContentColor(chapter)
    CompositionLocalProvider(LocalContentColor provides foreground) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(20.dp)) {
            when (chapter) {
                WrappedChapter.COVER -> WrappedCover(state, dateLabel, visible, motionEnabled)
                WrappedChapter.TIME -> WrappedTime(state, measuredPlays, visible, motionEnabled)
                WrappedChapter.SUMMARY -> WrappedSummary(state, title, dateLabel, visible, motionEnabled, onShare)
                else -> WrappedLeaders(chapter, state, visible, motionEnabled)
            }
        }
    }
}

@Composable
private fun WrappedCover(
    state: StatisticsUiState,
    dateLabel: String,
    visible: Boolean,
    motionEnabled: Boolean,
) {
    val formatter = rememberStatisticsFormatter()
    Text(
        stringResource(Res.string.wrapped_cover_heading),
        Modifier.semantics {
            heading()
        },
        style = MaterialTheme.typography.headlineSmallEmphasized,
    )
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
        Text(dateLabel, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.labelLarge)
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val compact = maxWidth < 640.dp
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f)) {
                Text(
                    checkNotNull(state.dateRange).start.year.toString(),
                    Modifier.fillMaxWidth().wrappedEntrance(visible, motionEnabled),
                    style = MaterialTheme.typography.displayLargeEmphasized.copy(fontWeight = FontWeight.ExtraBold),
                    autoSize = TextAutoSize.StepBased(minFontSize = 20.sp, maxFontSize = 112.sp),
                    maxLines = 1,
                )
            }
            WrappedSleeves(
                state.tracks.take(3),
                Modifier.weight(1f).wrappedEntrance(visible, motionEnabled, 1),
                height = if (compact) 144.dp else 240.dp,
            )
        }
    }
    Surface(
        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 8.dp, bottomStart = 8.dp, bottomEnd = 32.dp),
        color = MaterialTheme.colorScheme.primary,
    ) {
        Column(Modifier.fillMaxWidth().padding(24.dp)) {
            WrappedFigure(state.totalPlays.toLong(), formatter::number, visible, motionEnabled, Modifier.fillMaxWidth())
            Text(stringResource(Res.string.statistics_plays_label), style = MaterialTheme.typography.titleLargeEmphasized)
        }
    }
    Text(stringResource(Res.string.wrapped_history_scope), style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun WrappedSleeves(
    items: List<StatisticsItem>,
    modifier: Modifier = Modifier,
    height: Dp = 240.dp,
) {
    BoxWithConstraints(modifier.height(height), contentAlignment = Alignment.Center) {
        val size = minOf(maxWidth * 0.62f, height * 0.86f, 208.dp)
        items.getOrNull(2)?.let {
            WrappedArtwork(
                it,
                2,
                Modifier.size(size * 0.7f).align(Alignment.TopStart).graphicsLayer { rotationZ = -12f },
                RoundedCornerShape(10.dp),
            )
        }
        items.getOrNull(1)?.let {
            WrappedArtwork(
                it,
                1,
                Modifier.size(size * 0.78f).align(Alignment.CenterEnd).graphicsLayer { rotationZ = 12f },
                RoundedCornerShape(10.dp),
            )
        }
        items.firstOrNull()?.let {
            WrappedArtwork(it, 0, Modifier.size(size).graphicsLayer { rotationZ = -4f }, RoundedCornerShape(12.dp))
        }
    }
}

@Composable
private fun WrappedTime(
    state: StatisticsUiState,
    measuredPlays: Int,
    visible: Boolean,
    motionEnabled: Boolean,
) {
    val formatter = rememberStatisticsFormatter()
    Text(
        stringResource(Res.string.wrapped_time_heading),
        Modifier.semantics {
            heading()
        },
        style = MaterialTheme.typography.headlineLargeEmphasized,
    )
    Spacer(Modifier.height(24.dp))
    Text(
        statisticsDuration(state.totalTimeMs),
        Modifier.fillMaxWidth().wrappedEntrance(visible, motionEnabled),
        style =
            MaterialTheme.typography.displayLargeEmphasized.copy(
                lineHeight = 1.2.em,
                fontWeight = FontWeight.ExtraBold,
                fontFeatureSettings = "tnum",
            ),
        autoSize = TextAutoSize.StepBased(minFontSize = 36.sp, maxFontSize = 84.sp),
        maxLines = 3,
    )
    Text(stringResource(Res.string.wrapped_measured_time), style = MaterialTheme.typography.titleLargeEmphasized)
    Spacer(Modifier.height(24.dp))
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            val coverage = measuredPlays.toFloat() / state.totalPlays.coerceAtLeast(1)
            LinearProgressIndicator(progress = { coverage.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(16.dp))
            Text(
                stringResource(
                    Res.string.wrapped_time_coverage,
                    formatter.number(measuredPlays.toLong()),
                    formatter.number(state.totalPlays.toLong()),
                ),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

@Composable
private fun WrappedLeaders(
    chapter: WrappedChapter,
    state: StatisticsUiState,
    visible: Boolean,
    motionEnabled: Boolean,
) {
    val formatter = rememberStatisticsFormatter()
    val items =
        when (chapter) {
            WrappedChapter.TRACKS -> state.tracks
            WrappedChapter.ARTISTS -> state.artists
            else -> state.albums
        }
    val heading =
        stringResource(
            when (chapter) {
                WrappedChapter.TRACKS -> Res.string.statistics_top_tracks
                WrappedChapter.ARTISTS -> Res.string.statistics_top_artists
                else -> Res.string.statistics_top_albums
            },
        )
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text(heading, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLargeEmphasized)
        items.firstOrNull()?.let { lead ->
            if (chapter == WrappedChapter.ALBUMS) {
                WrappedSleeves(items.take(3), Modifier.fillMaxWidth().wrappedEntrance(visible, motionEnabled))
            } else {
                Box(Modifier.fillMaxWidth().height(248.dp).wrappedEntrance(visible, motionEnabled), contentAlignment = Alignment.Center) {
                    WrappedArtwork(
                        lead,
                        0,
                        Modifier.size(if (chapter == WrappedChapter.ARTISTS) 224.dp else 208.dp).graphicsLayer {
                            rotationZ = if (chapter == WrappedChapter.TRACKS) -4f else 0f
                        },
                    )
                    Surface(
                        modifier = Modifier.align(Alignment.BottomEnd).padding(end = 12.dp),
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.primary,
                    ) {
                        Text(
                            formatter.number(1),
                            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.displaySmallEmphasized,
                        )
                    }
                }
            }
            Text(
                lead.title,
                Modifier.wrappedEntrance(visible, motionEnabled, 1),
                style = MaterialTheme.typography.headlineLargeEmphasized,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Text(lead.subtitle, style = MaterialTheme.typography.titleMedium)
        }
        WrappedRankings(items.drop(1).take(4), 2, visible, motionEnabled, chapter == WrappedChapter.ALBUMS)
    }
}

@Composable
private fun WrappedRankings(
    items: List<StatisticsItem>,
    startRank: Int,
    visible: Boolean,
    motionEnabled: Boolean,
    lined: Boolean = false,
) {
    val formatter = rememberStatisticsFormatter()
    Column(verticalArrangement = Arrangement.spacedBy(if (lined) 12.dp else 4.dp)) {
        items.forEachIndexed { index, item ->
            Surface(
                modifier = Modifier.fillMaxWidth().wrappedEntrance(visible, motionEnabled, index + 2),
                color = if (lined) Color.Transparent else MaterialTheme.colorScheme.surfaceContainerLow,
                contentColor = if (lined) LocalContentColor.current else MaterialTheme.colorScheme.onSurface,
                shape = segmentedGroupShape(index == 0, index == items.lastIndex, false, outer = 24.dp, inner = 6.dp),
            ) {
                Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(formatter.number((startRank + index).toLong()), style = MaterialTheme.typography.titleLargeEmphasized)
                    WrappedArtwork(item, startRank + index - 1, Modifier.size(48.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            item.title,
                            style = MaterialTheme.typography.titleMediumEmphasized,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(item.subtitle, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            if (lined && index < items.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun WrappedSummary(
    state: StatisticsUiState,
    title: String,
    dateLabel: String,
    visible: Boolean,
    motionEnabled: Boolean,
    onShare: () -> Unit,
) {
    val formatter = rememberStatisticsFormatter()
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text(
            stringResource(Res.string.wrapped_summary),
            Modifier.semantics { heading() },
            style = MaterialTheme.typography.headlineLargeEmphasized,
        )
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(title, style = MaterialTheme.typography.headlineSmallEmphasized)
                Text(dateLabel, style = MaterialTheme.typography.labelLarge)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    listOfNotNull(state.artists.firstOrNull(), state.tracks.firstOrNull(), state.albums.firstOrNull()).forEachIndexed {
                        index,
                        item,
                        ->
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            WrappedArtwork(item, index, Modifier.fillMaxWidth().aspectRatio(1f))
                            Text(item.title, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                WrappedFigure(state.totalPlays.toLong(), formatter::number, visible, motionEnabled, Modifier.fillMaxWidth())
                Text(stringResource(Res.string.statistics_plays_label), style = MaterialTheme.typography.titleMedium)
                if (state.totalTimeMs > 0) {
                    Text(statisticsDuration(state.totalTimeMs), style = MaterialTheme.typography.headlineMediumEmphasized)
                    Text(stringResource(Res.string.wrapped_measured_time), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        Button(onClick = onShare, modifier = Modifier.fillMaxWidth(), shapes = ButtonDefaults.shapes()) {
            Icon(MetrolistIcons.Outlined.Share, null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(Res.string.wrapped_share_summary))
        }
        Text(stringResource(Res.string.wrapped_history_scope), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(Res.string.wrapped_merge_note), style = MaterialTheme.typography.bodySmall)
    }
}
