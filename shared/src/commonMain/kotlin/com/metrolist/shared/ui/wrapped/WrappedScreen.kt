@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.shared.ui.wrapped

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metrolist.shared.api.lastfm.LastFM
import com.metrolist.shared.api.lastfm.LastFmListen
import com.metrolist.shared.db.HistoryStatisticsSnapshot
import com.metrolist.shared.db.HistorySyncOutcome
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.db.historyChanges
import com.metrolist.shared.db.refreshYouTubeHistory
import com.metrolist.shared.db.wrappedStatisticsSnapshot
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.back
import com.metrolist.shared.generated.resources.n_album
import com.metrolist.shared.generated.resources.n_artist
import com.metrolist.shared.generated.resources.n_time
import com.metrolist.shared.generated.resources.next
import com.metrolist.shared.generated.resources.previous
import com.metrolist.shared.generated.resources.refresh
import com.metrolist.shared.generated.resources.statistics_load_failed
import com.metrolist.shared.generated.resources.statistics_plays
import com.metrolist.shared.generated.resources.statistics_plays_label
import com.metrolist.shared.generated.resources.wrapped
import com.metrolist.shared.generated.resources.wrapped_chapter
import com.metrolist.shared.generated.resources.wrapped_cover_heading
import com.metrolist.shared.generated.resources.wrapped_empty
import com.metrolist.shared.generated.resources.wrapped_history_scope
import com.metrolist.shared.generated.resources.wrapped_lastfm_coverage
import com.metrolist.shared.generated.resources.wrapped_lastfm_failed
import com.metrolist.shared.generated.resources.wrapped_lastfm_loading
import com.metrolist.shared.generated.resources.wrapped_measured_time
import com.metrolist.shared.generated.resources.wrapped_merge_note
import com.metrolist.shared.generated.resources.wrapped_share_caption
import com.metrolist.shared.generated.resources.wrapped_share_lastfm_caption
import com.metrolist.shared.generated.resources.wrapped_share_summary
import com.metrolist.shared.generated.resources.wrapped_soundtrack
import com.metrolist.shared.generated.resources.wrapped_soundtrack_failed
import com.metrolist.shared.generated.resources.wrapped_soundtrack_loading
import com.metrolist.shared.generated.resources.wrapped_soundtrack_partial
import com.metrolist.shared.generated.resources.wrapped_soundtrack_playing
import com.metrolist.shared.generated.resources.wrapped_soundtrack_preparing
import com.metrolist.shared.generated.resources.wrapped_soundtrack_unavailable
import com.metrolist.shared.generated.resources.wrapped_summary
import com.metrolist.shared.generated.resources.wrapped_sync_failed
import com.metrolist.shared.generated.resources.wrapped_time_coverage
import com.metrolist.shared.generated.resources.wrapped_time_heading
import com.metrolist.shared.generated.resources.wrapped_top_album
import com.metrolist.shared.generated.resources.wrapped_top_artist
import com.metrolist.shared.generated.resources.wrapped_top_song
import com.metrolist.shared.generated.resources.wrapped_year
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.state.GlobalYouTube
import com.metrolist.shared.ui.components.ContainedLoadingIndicator
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.navigation.NavigationBackHandler
import com.metrolist.shared.ui.playback.PlaybackController
import com.metrolist.shared.ui.screens.StatisticsItem
import com.metrolist.shared.ui.screens.StatisticsPeriod
import com.metrolist.shared.ui.screens.StatisticsUiState
import com.metrolist.shared.ui.screens.statisticsArtworkShape
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import org.jetbrains.compose.resources.pluralStringResource
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
    val formatter = rememberStatisticsFormatter()
    val today = Clock.System.todayIn(TimeZone.currentSystemDefault())
    val range = remember(today) { StatisticsDateRange(LocalDate(today.year, 1, 1), today) }
    var refreshKey by remember { mutableIntStateOf(0) }
    val lastFmUser =
        Settings.shared.lastFmUsername.takeIf {
            it.isNotBlank() && Settings.shared.lastFmSession.isNotBlank() &&
                !Settings.shared.lastFmSessionExpired
        }
    var snapshot by remember(database, range, lastFmUser) { mutableStateOf<HistoryStatisticsSnapshot?>(null) }
    var loadFailed by remember(database, range, lastFmUser) { mutableStateOf(false) }
    var syncFailed by remember(database, range) { mutableStateOf(false) }
    var loading by remember(database, range, lastFmUser) { mutableStateOf(true) }
    var lastFm by remember(range, lastFmUser) { mutableStateOf<List<LastFmListen>>(emptyList()) }
    var appliedLastFm by remember(database, range, lastFmUser) { mutableStateOf<List<LastFmListen>?>(null) }
    var lastFmLoading by remember(range, lastFmUser) { mutableStateOf(lastFmUser != null) }
    var lastFmFailed by remember(range, lastFmUser) { mutableStateOf(false) }
    var lastFmLoaded by remember(range, lastFmUser) { mutableStateOf(false) }
    LaunchedEffect(range, refreshKey, lastFmUser) {
        if (lastFmUser.isNullOrBlank()) return@LaunchedEffect
        lastFmLoading = true
        lastFmFailed = false
        try {
            val (since, _) = range.epochRange()
            lastFm =
                withTimeout(300_000) {
                    LastFM
                        .getListeningHistory(
                            GlobalYouTube.client,
                            lastFmUser,
                            since,
                            Clock.System.now().toEpochMilliseconds(),
                        ).getOrThrow()
                }
            lastFmLoaded = true
        } catch (_: TimeoutCancellationException) {
            lastFmFailed = true
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            lastFmFailed = true
        } finally {
            lastFmLoading = false
        }
    }
    LaunchedEffect(database, range, refreshKey, lastFm, lastFmUser) {
        database.historyChanges().collectLatest {
            try {
                loadFailed = false
                val (since, _) = range.epochRange()
                snapshot = database.wrappedStatisticsSnapshot(since, Clock.System.now().toEpochMilliseconds(), lastFm)
                appliedLastFm = lastFm
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
                    if (lastFmLoading ||
                        lastFmFailed
                    ) {
                        Text(
                            stringResource(if (lastFmFailed) Res.string.wrapped_lastfm_failed else Res.string.wrapped_lastfm_loading),
                            Modifier.padding(horizontal = 24.dp),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
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
                            when {
                                lastFmLoading || (lastFmLoaded && appliedLastFm !== lastFm) -> {
                                    stringResource(Res.string.wrapped_lastfm_loading)
                                }

                                lastFmFailed -> {
                                    stringResource(Res.string.wrapped_lastfm_failed)
                                }

                                lastFmLoaded -> {
                                    stringResource(
                                        Res.string.wrapped_lastfm_coverage,
                                        formatter.number(result.lastFmPlays.toLong()),
                                        formatter.number(result.lastFmAmbiguousPlays.toLong()),
                                    )
                                }

                                else -> {
                                    null
                                }
                            },
                            lastFmLoaded && appliedLastFm === lastFm,
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
    lastFmStatus: String?,
    lastFmLoaded: Boolean,
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
    val pager = rememberPagerState { chapters.size }
    val soundtrack = rememberWrappedSoundtrack(playbackController, snapshot.songItems.take(10), pager.settledPage, desktopLayout)
    val motionEnabled = wrappedMotionEnabled()
    var sharing by rememberSaveable { mutableStateOf(false) }
    val title = stringResource(Res.string.wrapped_year, range.start.year.toString())
    val dateLabel = statisticsDateRangeLabel(range)
    val measuredPlays = snapshot.songs.sumOf { it.measuredPlayCount }
    if (sharing) {
        StatisticsShareDialog(
            state = state,
            period = dateLabel,
            summary =
                if (lastFmLoaded) {
                    stringResource(Res.string.wrapped_share_lastfm_caption) + " " +
                        stringResource(
                            Res.string.wrapped_lastfm_coverage,
                            formatter.number(snapshot.lastFmPlays.toLong()),
                            formatter.number(snapshot.lastFmAmbiguousPlays.toLong()),
                        )
                } else {
                    stringResource(Res.string.wrapped_share_caption)
                },
            exportLabel = exportLabel,
            onExport = onExport,
            onDismiss = { sharing = false },
            title = title,
            timeLabel = stringResource(Res.string.wrapped_measured_time),
        )
    }
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
            wrappedPalette(chapters[pager.currentPage.coerceIn(0, chapters.lastIndex)]).ink
        }
    val toolbarBackground =
        if (desktopLayout) {
            MaterialTheme.colorScheme.surface
        } else {
            wrappedPalette(chapters[pager.currentPage.coerceIn(0, chapters.lastIndex)]).background
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
                        colors =
                            ToggleButtonDefaults.colors(
                                containerColor = foreground.copy(alpha = 0.14f),
                                contentColor = foreground,
                                checkedContainerColor = foreground,
                                checkedContentColor = toolbarBackground,
                            ),
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
                if (lastFmStatus !=
                    null
                ) {
                    Text(
                        lastFmStatus,
                        Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (soundtrack.enabled) {
                    val song = soundtrack.playingSong
                    Text(
                        if (soundtrack.preparing) {
                            stringResource(
                                Res.string.wrapped_soundtrack_preparing,
                                formatter.number(soundtrack.preparedCount.toLong()),
                                formatter.number(soundtrack.songCount.toLong()),
                            )
                        } else if (song != null) {
                            stringResource(
                                Res.string.wrapped_soundtrack_playing,
                                song.title,
                                song.artists.joinToString(", ") { it.name },
                            )
                        } else {
                            stringResource(Res.string.wrapped_soundtrack_loading)
                        },
                        Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (soundtrack.failed || !soundtrack.available) {
                    Text(
                        stringResource(
                            when {
                                soundtrack.failed && soundtrack.enabled -> Res.string.wrapped_soundtrack_partial
                                soundtrack.failed -> Res.string.wrapped_soundtrack_failed
                                else -> Res.string.wrapped_soundtrack_unavailable
                            },
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
                                        snapshot,
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
                                snapshot,
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
                                { page - pager.currentPage - pager.currentPageOffsetFraction },
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
private fun WrappedChapterContent(
    chapter: WrappedChapter,
    state: StatisticsUiState,
    snapshot: HistoryStatisticsSnapshot,
    title: String,
    dateLabel: String,
    measuredPlays: Int,
    visible: Boolean,
    motionEnabled: Boolean,
    modifier: Modifier = Modifier,
    spin: () -> Float = { 0f },
    onShare: () -> Unit,
) {
    val palette = wrappedPalette(chapter)
    CompositionLocalProvider(LocalContentColor provides palette.ink) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(20.dp)) {
            when (chapter) {
                WrappedChapter.COVER -> WrappedCover(state, palette, dateLabel, visible, motionEnabled, spin)
                WrappedChapter.TIME -> WrappedTime(state, palette, measuredPlays, visible, motionEnabled, spin)
                WrappedChapter.SUMMARY -> WrappedSummary(state, palette, title, visible, motionEnabled, spin, onShare)
                else -> WrappedLeaders(chapter, state, snapshot, palette, visible, motionEnabled, spin)
            }
        }
    }
}

/** The square stage that holds a chapter's artwork over its animated pattern. */
@Composable
private fun WrappedStage(
    pattern: WrappedPattern,
    palette: WrappedPalette,
    motionEnabled: Boolean,
    spin: () -> Float,
    bottomBleed: Dp = 64.dp,
    content: @Composable BoxScope.(side: Dp) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val side = minOf(maxWidth * 0.86f, 340.dp)
        Box(
            Modifier.fillMaxWidth().height(side).wrappedPattern(pattern, palette, motionEnabled, spin, bottomBleed = bottomBleed),
            contentAlignment = Alignment.Center,
        ) { content(side) }
    }
}

@Composable
private fun WrappedCover(
    state: StatisticsUiState,
    palette: WrappedPalette,
    dateLabel: String,
    visible: Boolean,
    motionEnabled: Boolean,
    spin: () -> Float,
) {
    val items = state.tracks.take(3)
    WrappedStage(WrappedPattern.RAYS, palette, motionEnabled, spin) { side ->
        items.getOrNull(1)?.let {
            WrappedShapedArtwork(
                it,
                MaterialShapes.Clover4Leaf,
                visible,
                motionEnabled,
                Modifier.size(side * 0.34f).align(Alignment.TopStart),
                delayMillis = 140,
                turnMillis = 18_000,
                tilt = -10f,
            )
        }
        items.getOrNull(2)?.let {
            WrappedShapedArtwork(
                it,
                MaterialShapes.Cookie6Sided,
                visible,
                motionEnabled,
                Modifier.size(side * 0.3f).align(Alignment.BottomEnd),
                delayMillis = 220,
                turnMillis = 22_000,
                tilt = 8f,
            )
        }
        items.firstOrNull()?.let {
            WrappedShapedArtwork(it, MaterialShapes.Sunny, visible, motionEnabled, Modifier.size(side * 0.56f), turnMillis = 30_000)
        }
    }
    WrappedSticker(stringResource(Res.string.wrapped_cover_heading), palette, visible, motionEnabled)
    WrappedHeadline(
        checkNotNull(state.dateRange).start.year.toString(),
        palette,
        Modifier.fillMaxWidth().wrappedEntrance(visible, motionEnabled, 1),
        maxFontSize = 168.sp,
        maxLines = 1,
        depth = 8.dp,
    )
    WrappedPills(
        listOf(
            dateLabel,
            pluralStringResource(Res.plurals.statistics_plays, state.totalPlays, state.totalPlays),
            pluralStringResource(Res.plurals.n_artist, state.artistCount, state.artistCount),
            pluralStringResource(Res.plurals.n_album, state.albumCount, state.albumCount),
        ),
        palette,
        visible,
        motionEnabled,
    )
    Text(stringResource(Res.string.wrapped_history_scope), style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun WrappedTime(
    state: StatisticsUiState,
    palette: WrappedPalette,
    measuredPlays: Int,
    visible: Boolean,
    motionEnabled: Boolean,
    spin: () -> Float,
) {
    val formatter = rememberStatisticsFormatter()
    WrappedStage(WrappedPattern.DIAL, palette, motionEnabled, spin, bottomBleed = 0.dp) { side ->
        WrappedHeadline(
            statisticsDuration(state.totalTimeMs),
            palette,
            Modifier.width(side * 0.56f).wrappedEntrance(visible, motionEnabled),
            maxFontSize = 76.sp,
            minFontSize = 24.sp,
            maxLines = 3,
            textAlign = TextAlign.Center,
        )
    }
    WrappedSticker(stringResource(Res.string.wrapped_time_heading), palette, visible, motionEnabled)
    Text(
        stringResource(Res.string.wrapped_measured_time),
        Modifier.semantics { heading() },
        style = MaterialTheme.typography.headlineSmallEmphasized.copy(fontWeight = FontWeight.Black),
    )
    val coverage = (measuredPlays.toFloat() / state.totalPlays.coerceAtLeast(1)).coerceIn(0f, 1f)
    val fill = rememberWrappedReveal(visible, motionEnabled, 500, Spring.StiffnessVeryLow, 0.9f)
    val stroke = Stroke(with(LocalDensity.current) { 8.dp.toPx() }, cap = StrokeCap.Round)
    LinearWavyProgressIndicator(
        progress = { coverage * fill.value.coerceIn(0f, 1f) },
        modifier = Modifier.fillMaxWidth().height(20.dp),
        color = palette.ink,
        trackColor = palette.ink.copy(alpha = 0.2f),
        stroke = stroke,
        trackStroke = stroke,
    )
    Text(
        stringResource(
            Res.string.wrapped_time_coverage,
            formatter.number(measuredPlays.toLong()),
            formatter.number(state.totalPlays.toLong()),
        ),
        style = MaterialTheme.typography.bodyLarge,
    )
}

@Composable
private fun WrappedLeaders(
    chapter: WrappedChapter,
    state: StatisticsUiState,
    snapshot: HistoryStatisticsSnapshot,
    palette: WrappedPalette,
    visible: Boolean,
    motionEnabled: Boolean,
    spin: () -> Float,
) {
    val formatter = rememberStatisticsFormatter()
    val items =
        when (chapter) {
            WrappedChapter.TRACKS -> state.tracks
            WrappedChapter.ARTISTS -> state.artists
            else -> state.albums
        }
    val lead = items.firstOrNull() ?: return
    val (byline, plays, timeMs) =
        when (chapter) {
            WrappedChapter.TRACKS -> snapshot.songs.first().let { Triple(it.artists, it.playCount, it.playTimeMs) }
            WrappedChapter.ARTISTS -> snapshot.artists.first().let { Triple("", it.playCount, it.playTimeMs) }
            else -> snapshot.albums.first().let { Triple(it.artists, it.playCount, it.playTimeMs) }
        }
    WrappedStage(
        when (chapter) {
            WrappedChapter.TRACKS -> WrappedPattern.GROOVES
            WrappedChapter.ARTISTS -> WrappedPattern.RINGS
            else -> WrappedPattern.STRIPES
        },
        palette,
        motionEnabled,
        spin,
        bottomBleed = if (chapter == WrappedChapter.TRACKS) 0.dp else 64.dp,
    ) { side ->
        when (chapter) {
            WrappedChapter.TRACKS -> {
                WrappedShapedArtwork(
                    lead,
                    MaterialShapes.Cookie12Sided,
                    visible,
                    motionEnabled,
                    Modifier.size(side * 0.46f),
                    turnMillis = 9_000,
                )
            }

            WrappedChapter.ARTISTS -> {
                WrappedShapedArtwork(
                    lead,
                    MaterialShapes.Cookie9Sided,
                    visible,
                    motionEnabled,
                    Modifier.size(side * 0.64f),
                    turnMillis = 24_000,
                )
            }

            else -> {
                items.getOrNull(2)?.let {
                    WrappedShapedArtwork(
                        it,
                        MaterialShapes.Square,
                        visible,
                        motionEnabled,
                        Modifier.size(side * 0.4f).align(Alignment.CenterStart),
                        delayMillis = 160,
                        tilt = -14f,
                    )
                }
                items.getOrNull(1)?.let {
                    WrappedShapedArtwork(
                        it,
                        MaterialShapes.Square,
                        visible,
                        motionEnabled,
                        Modifier.size(side * 0.44f).align(Alignment.CenterEnd),
                        delayMillis = 80,
                        tilt = 12f,
                    )
                }
                WrappedShapedArtwork(lead, MaterialShapes.Square, visible, motionEnabled, Modifier.size(side * 0.6f), tilt = -3f)
            }
        }
        WrappedRankBadge(formatter.number(1), palette, visible, motionEnabled, Modifier.size(side * 0.24f).align(Alignment.TopEnd))
    }
    WrappedSticker(
        stringResource(
            when (chapter) {
                WrappedChapter.TRACKS -> Res.string.wrapped_top_song
                WrappedChapter.ARTISTS -> Res.string.wrapped_top_artist
                else -> Res.string.wrapped_top_album
            },
        ),
        palette,
        visible,
        motionEnabled,
        Modifier.semantics { heading() },
    )
    WrappedHeadline(lead.title, palette, Modifier.fillMaxWidth().wrappedEntrance(visible, motionEnabled, 2), maxLines = 3)
    WrappedPills(
        listOfNotNull(
            byline.takeIf { it.isNotBlank() },
            pluralStringResource(Res.plurals.n_time, plays, plays),
            statisticsDuration(timeMs).takeIf { timeMs > 0 },
        ),
        palette,
        visible,
        motionEnabled,
    )
    WrappedRankings(items.drop(1).take(4), 2, palette, visible, motionEnabled)
}

@Composable
private fun WrappedRankings(
    items: List<StatisticsItem>,
    startRank: Int,
    palette: WrappedPalette,
    visible: Boolean,
    motionEnabled: Boolean,
) {
    val formatter = rememberStatisticsFormatter()
    Column {
        items.forEachIndexed { index, item ->
            Row(
                Modifier.fillMaxWidth().wrappedEntrance(visible, motionEnabled, index + 5).padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    formatter.number((startRank + index).toLong()),
                    Modifier.widthIn(min = 36.dp),
                    style = MaterialTheme.typography.headlineMediumEmphasized.copy(fontWeight = FontWeight.Black),
                )
                WrappedArtwork(
                    item,
                    Modifier.size(56.dp),
                    if (item.circularArtwork) statisticsArtworkShape(item, startRank + index - 1) else RoundedCornerShape(12.dp),
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        item.title,
                        style = MaterialTheme.typography.titleMediumEmphasized.copy(fontWeight = FontWeight.ExtraBold),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(item.subtitle, style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (index < items.lastIndex) HorizontalDivider(thickness = 2.dp, color = palette.ink.copy(alpha = 0.18f))
        }
    }
}

@Composable
private fun WrappedSummary(
    state: StatisticsUiState,
    palette: WrappedPalette,
    title: String,
    visible: Boolean,
    motionEnabled: Boolean,
    spin: () -> Float,
    onShare: () -> Unit,
) {
    val formatter = rememberStatisticsFormatter()
    val trio =
        listOfNotNull(
            state.artists.firstOrNull()?.to(MaterialShapes.Cookie9Sided),
            state.tracks.firstOrNull()?.to(MaterialShapes.Sunny),
            state.albums.firstOrNull()?.to(MaterialShapes.Square),
        )
    WrappedStage(WrappedPattern.WAVES, palette, motionEnabled, spin, bottomBleed = 0.dp) { side ->
        Row(
            Modifier.fillMaxWidth().height(side * 0.5f),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            trio.forEachIndexed { index, (item, shape) ->
                WrappedShapedArtwork(
                    item,
                    shape,
                    visible,
                    motionEnabled,
                    Modifier.weight(1f, fill = false).size(side * 0.3f),
                    delayMillis = index * 90L,
                    turnMillis = 20_000 + index * 4_000,
                    tilt = (index - 1) * 8f,
                )
            }
        }
    }
    WrappedSticker(stringResource(Res.string.wrapped_summary), palette, visible, motionEnabled, Modifier.semantics { heading() })
    WrappedHeadline(title, palette, Modifier.fillMaxWidth().wrappedEntrance(visible, motionEnabled, 1), maxFontSize = 72.sp)
    WrappedPills(trio.map { it.first.title }, palette, visible, motionEnabled)
    Column {
        WrappedFigure(state.totalPlays.toLong(), formatter::number, palette, visible, motionEnabled, Modifier.fillMaxWidth())
        Text(
            stringResource(Res.string.statistics_plays_label),
            style = MaterialTheme.typography.titleLargeEmphasized.copy(fontWeight = FontWeight.Black),
        )
    }
    if (state.totalTimeMs > 0) {
        Column {
            Text(
                statisticsDuration(state.totalTimeMs),
                style = MaterialTheme.typography.headlineLargeEmphasized.copy(fontWeight = FontWeight.Black),
            )
            Text(stringResource(Res.string.wrapped_measured_time), style = MaterialTheme.typography.labelLarge)
        }
    }
    Button(
        onClick = onShare,
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
        shapes = ButtonDefaults.shapes(),
        colors = ButtonDefaults.buttonColors(containerColor = palette.ink, contentColor = palette.background),
    ) {
        Icon(MetrolistIcons.Outlined.Share, null)
        Spacer(Modifier.width(10.dp))
        Text(
            stringResource(Res.string.wrapped_share_summary),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
        )
    }
    Text(stringResource(Res.string.wrapped_history_scope), style = MaterialTheme.typography.bodyMedium)
    Text(stringResource(Res.string.wrapped_merge_note), style = MaterialTheme.typography.bodySmall)
}
