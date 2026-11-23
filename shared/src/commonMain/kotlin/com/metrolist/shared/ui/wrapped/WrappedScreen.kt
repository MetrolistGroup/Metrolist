@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.shared.ui.wrapped

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
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
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metrolist.shared.api.lastfm.LastFM
import com.metrolist.shared.api.lastfm.LastFmListen
import com.metrolist.shared.db.HistoryArtistStatistic
import com.metrolist.shared.db.HistoryStatisticsSnapshot
import com.metrolist.shared.db.HistorySyncOutcome
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.db.historyChanges
import com.metrolist.shared.db.refreshYouTubeHistory
import com.metrolist.shared.db.wrappedStatisticsSnapshot
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.back
import com.metrolist.shared.generated.resources.logo
import com.metrolist.shared.generated.resources.n_album
import com.metrolist.shared.generated.resources.n_artist
import com.metrolist.shared.generated.resources.n_song
import com.metrolist.shared.generated.resources.n_time
import com.metrolist.shared.generated.resources.statistics_load_failed
import com.metrolist.shared.generated.resources.statistics_plays
import com.metrolist.shared.generated.resources.statistics_plays_label
import com.metrolist.shared.generated.resources.wrapped
import com.metrolist.shared.generated.resources.wrapped_artist_mix
import com.metrolist.shared.generated.resources.wrapped_chapter
import com.metrolist.shared.generated.resources.wrapped_cover_heading
import com.metrolist.shared.generated.resources.wrapped_empty
import com.metrolist.shared.generated.resources.wrapped_history_scope
import com.metrolist.shared.generated.resources.wrapped_home_card_dismissed
import com.metrolist.shared.generated.resources.wrapped_lastfm_coverage
import com.metrolist.shared.generated.resources.wrapped_lastfm_failed
import com.metrolist.shared.generated.resources.wrapped_lastfm_loading
import com.metrolist.shared.generated.resources.wrapped_measured_time
import com.metrolist.shared.generated.resources.wrapped_merge_note
import com.metrolist.shared.generated.resources.wrapped_minutes_listened
import com.metrolist.shared.generated.resources.wrapped_share_history_note
import com.metrolist.shared.generated.resources.wrapped_share_lastfm_failed
import com.metrolist.shared.generated.resources.wrapped_share_lastfm_included
import com.metrolist.shared.generated.resources.wrapped_share_lastfm_loading
import com.metrolist.shared.generated.resources.wrapped_share_summary
import com.metrolist.shared.generated.resources.wrapped_share_sync_failed
import com.metrolist.shared.generated.resources.wrapped_soundtrack
import com.metrolist.shared.generated.resources.wrapped_summary
import com.metrolist.shared.generated.resources.wrapped_sync_failed
import com.metrolist.shared.generated.resources.wrapped_time_coverage
import com.metrolist.shared.generated.resources.wrapped_time_heading
import com.metrolist.shared.generated.resources.wrapped_top_album
import com.metrolist.shared.generated.resources.wrapped_top_artist
import com.metrolist.shared.generated.resources.wrapped_top_song
import com.metrolist.shared.generated.resources.wrapped_year
import com.metrolist.shared.model.SongItem
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
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.time.Clock

internal enum class WrappedChapter {
    COVER,
    TIME,
    TRACKS,
    ARTISTS,
    MIX,
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
    showBackButton: Boolean = false,
    onHomeCardRetired: (message: String) -> Unit = {},
) {
    val formatter = rememberStatisticsFormatter()
    val today = Clock.System.todayIn(TimeZone.currentSystemDefault())
    val range = remember(today) { StatisticsDateRange(LocalDate(today.year, 1, 1), today) }
    val lastFmUser =
        Settings.shared.lastFmUsername.takeIf {
            it.isNotBlank() && Settings.shared.lastFmSession.isNotBlank() &&
                !Settings.shared.lastFmSessionExpired
        }
    var snapshot by remember(database, range, lastFmUser) { mutableStateOf<HistoryStatisticsSnapshot?>(null) }
    var loadFailed by remember(database, range, lastFmUser) { mutableStateOf(false) }
    var syncFailed by remember(database, range) { mutableStateOf(false) }
    var syncPending by remember(database, range) { mutableStateOf(true) }
    var syncRevision by remember(database, range) { mutableIntStateOf(0) }
    var appliedSyncRevision by remember(database, range, lastFmUser) { mutableIntStateOf(-1) }
    var loading by remember(database, range, lastFmUser) { mutableStateOf(true) }
    var lastFm by remember(range, lastFmUser) { mutableStateOf<List<LastFmListen>>(emptyList()) }
    var appliedLastFm by remember(database, range, lastFmUser) { mutableStateOf<List<LastFmListen>?>(null) }
    var lastFmLoading by remember(range, lastFmUser) { mutableStateOf(lastFmUser != null) }
    var lastFmFailed by remember(range, lastFmUser) { mutableStateOf(false) }
    var lastFmLoaded by remember(range, lastFmUser) { mutableStateOf(false) }
    LaunchedEffect(range, lastFmUser) {
        if (lastFmUser.isNullOrBlank()) return@LaunchedEffect
        lastFmLoading = true
        lastFmFailed = false
        try {
            val (since, _) = range.epochRange()
            lastFm =
                withTimeout(1_800_000) {
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
    LaunchedEffect(database, range, lastFm, lastFmUser, syncRevision) {
        database.historyChanges().collectLatest {
            try {
                loadFailed = false
                val (since, _) = range.epochRange()
                val listens = lastFm
                val revision = syncRevision
                snapshot = database.wrappedStatisticsSnapshot(since, Clock.System.now().toEpochMilliseconds(), listens)
                appliedLastFm = listens
                appliedSyncRevision = revision
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                loadFailed = true
            } finally {
                loading = false
            }
        }
    }
    LaunchedEffect(database, range) {
        syncFailed = false
        try {
            val outcome = withTimeout(30_000) { database.refreshYouTubeHistory() }
            syncFailed = outcome == HistorySyncOutcome.FAILED
            if (outcome == HistorySyncOutcome.UPDATED) syncRevision++
        } catch (_: TimeoutCancellationException) {
            syncFailed = true
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            syncFailed = true
        } finally {
            syncPending = false
        }
    }
    NavigationBackHandler(onBack = onBack)
    MaterialTheme(motionScheme = MotionScheme.expressive(), typography = wrappedTypography()) {
        val title = stringResource(Res.string.wrapped)
        Surface(Modifier.fillMaxSize().semantics { paneTitle = title }) {
            val result = snapshot
            if (loadFailed || result == null || result.totals.playCount == 0) {
                Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
                    if (showBackButton || desktopLayout) {
                        IconButton(onClick = onBack, modifier = Modifier.padding(8.dp)) {
                            Icon(MetrolistIcons.AutoMirrored.Filled.ArrowBack, stringResource(Res.string.back))
                        }
                    }
                    when {
                        // Either refresh may still supply plays absent from the current snapshot.
                        (loading && result == null) ||
                            (
                                !loadFailed && (
                                    syncPending || appliedSyncRevision != syncRevision ||
                                        lastFmLoading || (lastFmLoaded && appliedLastFm !== lastFm)
                                )
                            ) -> {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { ContainedLoadingIndicator() }
                        }

                        loadFailed -> {
                            WrappedMessage(stringResource(Res.string.statistics_load_failed))
                        }

                        else -> {
                            WrappedMessage(
                                listOfNotNull(
                                    stringResource(Res.string.wrapped_empty),
                                    stringResource(Res.string.wrapped_sync_failed).takeIf { syncFailed },
                                    stringResource(Res.string.wrapped_lastfm_failed).takeIf { lastFmFailed },
                                ).joinToString("\n\n"),
                            )
                        }
                    }
                }
            } else {
                val notes =
                    listOfNotNull(
                        stringResource(Res.string.wrapped_sync_failed).takeIf { syncFailed },
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
                    )
                WrappedReport(
                    result,
                    range,
                    playbackController,
                    onExport,
                    exportLabel,
                    desktopLayout,
                    bottomContentPadding,
                    onBack,
                    showBackButton,
                    notes,
                    listOfNotNull(
                        stringResource(Res.string.wrapped_share_history_note),
                        stringResource(Res.string.wrapped_share_sync_failed).takeIf { syncFailed },
                        when {
                            lastFmLoading || (lastFmLoaded && appliedLastFm !== lastFm) -> {
                                stringResource(
                                    Res.string.wrapped_share_lastfm_loading,
                                )
                            }

                            lastFmFailed -> {
                                stringResource(Res.string.wrapped_share_lastfm_failed)
                            }

                            lastFmLoaded -> {
                                stringResource(Res.string.wrapped_share_lastfm_included)
                            }

                            else -> {
                                null
                            }
                        },
                    ).joinToString("\n"),
                    onHomeCardRetired,
                )
            }
        }
    }
}

private const val WRAPPED_CHAPTER_MILLIS = 10_000

/** Height available to each chapter's artwork stage, sized so the headline and stats stay on screen. */
private val LocalWrappedStage = staticCompositionLocalOf { 340.dp }

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
    showBackButton: Boolean,
    notes: List<String>,
    shareFooter: String,
    onHomeCardRetired: (message: String) -> Unit,
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
            if (state.artists.size >= 4) add(WrappedChapter.MIX)
            if (state.albums.isNotEmpty()) add(WrappedChapter.ALBUMS)
            add(WrappedChapter.SUMMARY)
        }
    val pager = rememberPagerState { chapters.size }
    val chapterSongs = remember(snapshot, chapters) { wrappedChapterSongs(snapshot, chapters) }
    val soundtrack = rememberWrappedSoundtrack(playbackController, chapterSongs, pager.settledPage, desktopLayout)
    var soundtrackStarted by rememberSaveable { mutableStateOf(false) }
    // The soundtrack starts by itself once, as soon as playback allows it; turning it off is respected.
    LaunchedEffect(soundtrack.available) {
        if (!soundtrackStarted && soundtrack.available) {
            soundtrackStarted = true
            soundtrack.toggle()
        }
    }
    val motionEnabled = wrappedMotionEnabled()
    var sharing by rememberSaveable { mutableStateOf(false) }
    val title = stringResource(Res.string.wrapped_year, range.start.year.toString())
    val dateLabel = statisticsDateRangeLabel(range)
    val measuredPlays = snapshot.songs.sumOf { it.measuredPlayCount }
    if (sharing) {
        StatisticsShareDialog(
            state = state,
            period = dateLabel,
            summary = "",
            exportLabel = exportLabel,
            onExport = onExport,
            onDismiss = { sharing = false },
            title = title,
            timeLabel = stringResource(Res.string.wrapped_measured_time),
            imageColors = wrappedShareColors(),
            logo = painterResource(Res.drawable.logo),
            footer = shareFooter,
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
    val current = chapters[pager.currentPage.coerceIn(0, chapters.lastIndex)]
    val palette = wrappedPalette(current)
    val foreground = if (desktopLayout) MaterialTheme.colorScheme.onSurface else palette.ink
    if (desktopLayout) {
        CompositionLocalProvider(LocalContentColor provides foreground) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) {
                        Icon(MetrolistIcons.AutoMirrored.Filled.ArrowBack, stringResource(Res.string.back))
                    }
                    Text(
                        stringResource(Res.string.wrapped),
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    WrappedSoundtrackButton(soundtrack, foreground, MaterialTheme.colorScheme.surface)
                    FilledTonalButton(onClick = { sharing = true }, shapes = ButtonDefaults.shapes()) {
                        Icon(MetrolistIcons.Outlined.Share, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(Res.string.wrapped_share_summary))
                    }
                }
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
                                    measuredPlays,
                                    notes,
                                    true,
                                    motionEnabled,
                                    Modifier.padding(28.dp),
                                ) { sharing = true }
                            }
                        }
                    }
                }
            }
        }
        return
    }
    WrappedStatusBarAppearance(palette.background.luminance() > 0.5f)
    val retiredMessage = stringResource(Res.string.wrapped_home_card_dismissed, range.start.year.toString())
    LaunchedEffect(pager.settledPage) {
        // Reaching the last chapter finishes Wrapped, so its December Home card has done its job.
        val today = Clock.System.todayIn(TimeZone.currentSystemDefault())
        if (pager.settledPage == chapters.lastIndex && wrappedHomeCardDue(today)) {
            Settings.shared.wrappedCardDismissedYear = today.year
            onHomeCardRetired(retiredMessage)
        }
    }
    var held by remember { mutableStateOf(false) }
    val scrolls = remember(chapters.size) { List(chapters.size) { ScrollState(0) } }
    val pageScroll = scrolls[pager.settledPage.coerceIn(0, chapters.lastIndex)]
    val scrolling by remember(pageScroll) { derivedStateOf { pageScroll.isScrollInProgress } }
    val reading by remember(pageScroll) { derivedStateOf { pageScroll.isScrollInProgress || pageScroll.value > 0 } }
    val progress = remember { Animatable(0f) }
    var progressPage by remember { mutableIntStateOf(-1) }
    LaunchedEffect(pager.settledPage, held && !scrolling, reading, pager.isScrollInProgress, motionEnabled) {
        if (progressPage != pager.settledPage) {
            progressPage = pager.settledPage
            progress.snapTo(0f)
        }
        // Chapters advance on their own like stories. Holding still pauses one; scrolling through it runs 4x slower.
        if (!motionEnabled || (held && !scrolling) || pager.isScrollInProgress) return@LaunchedEffect
        val remaining = ((1f - progress.value) * WRAPPED_CHAPTER_MILLIS * if (reading) 4 else 1).toInt()
        progress.animateTo(1f, tween(remaining, easing = LinearEasing))
        if (pager.settledPage < chapters.lastIndex) moveTo(pager.settledPage + 1)
    }
    val density = LocalDensity.current
    val topInset = with(density) { WindowInsets.statusBars.getTop(this).toDp() }
    val bottomInset = with(density) { WindowInsets.navigationBars.getBottom(this).toDp() } + bottomContentPadding
    CompositionLocalProvider(LocalContentColor provides foreground) {
        Box(Modifier.fillMaxSize()) {
            WrappedBackdrop(
                chapters,
                { pager.currentPage + pager.currentPageOffsetFraction },
                motionEnabled,
                Modifier.matchParentSize(),
            )
            HorizontalPager(
                state = pager,
                key = { chapters[it].name },
                flingBehavior = PagerDefaults.flingBehavior(pager, snapAnimationSpec = if (motionEnabled) spatialSpec else snap()),
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.Top,
            ) { page ->
                val scroll = scrolls[page]
                val direction = LocalLayoutDirection.current
                BoxWithConstraints(
                    Modifier.fillMaxSize().pointerInput(chapters.size, direction, scroll, pager, motionEnabled) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            held = true
                            try {
                                val scrollingAtDown = scroll.isScrollInProgress || pager.isScrollInProgress
                                // A press on artwork still holds the chapter until release; it just does not navigate.
                                val consumedAtDown = down.isConsumed
                                val up = waitForUpOrCancellation() ?: return@awaitEachGesture
                                if (consumedAtDown) return@awaitEachGesture
                                if (scrollingAtDown || scroll.isScrollInProgress || pager.isScrollInProgress) return@awaitEachGesture
                                if (up.id != down.id ||
                                    up.uptimeMillis - down.uptimeMillis >= viewConfiguration.longPressTimeoutMillis
                                ) {
                                    return@awaitEachGesture
                                }
                                if ((up.position - down.position).getDistance() > viewConfiguration.touchSlop) return@awaitEachGesture
                                // The leading 30% goes back: the left edge in LTR, the right edge in RTL.
                                val leading =
                                    if (direction == LayoutDirection.Ltr) {
                                        down.position.x < size.width * 0.3f
                                    } else {
                                        down.position.x > size.width * 0.7f
                                    }
                                val forward = !leading
                                val target = (pager.currentPage + if (forward) 1 else -1).coerceIn(0, chapters.lastIndex)
                                if (target != pager.currentPage) {
                                    up.consume()
                                    moveTo(target)
                                }
                            } finally {
                                held = false
                            }
                        }
                    },
                ) {
                    val stage = (maxHeight - topInset - bottomInset - 360.dp).coerceIn(220.dp, 440.dp)
                    val visible = abs(pager.currentPage + pager.currentPageOffsetFraction - page) < 1f
                    CompositionLocalProvider(LocalWrappedStage provides stage) {
                        WrappedChapterContent(
                            chapters[page],
                            state,
                            snapshot,
                            title,
                            measuredPlays,
                            notes,
                            visible,
                            motionEnabled,
                            Modifier
                                .fillMaxWidth()
                                .verticalScroll(scroll)
                                .heightIn(min = maxHeight)
                                .padding(start = 24.dp, top = topInset + 72.dp, end = 24.dp, bottom = bottomInset + 32.dp),
                            { page - pager.currentPage - pager.currentPageOffsetFraction },
                        ) { sharing = true }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(start = if (showBackButton) 4.dp else 24.dp, top = topInset + 8.dp, end = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (showBackButton) {
                    IconButton(onClick = onBack) {
                        Icon(MetrolistIcons.AutoMirrored.Filled.ArrowBack, stringResource(Res.string.back))
                    }
                }
                WrappedChapterRail(chapters.size, pager.currentPage, { progress.value }, Modifier.weight(1f)) { moveTo(it) }
                WrappedSoundtrackButton(soundtrack, foreground, palette.background)
            }
        }
    }
}

/** Picks the song that belongs to each chapter, so the soundtrack plays what the chapter is about. */
private fun wrappedChapterSongs(
    snapshot: HistoryStatisticsSnapshot,
    chapters: List<WrappedChapter>,
): List<SongItem> {
    val songs = snapshot.songItems
    val top = snapshot.songs.firstNotNullOfOrNull { group -> songs.firstOrNull { it.id == group.songId } } ?: songs.firstOrNull()

    fun byArtist(artist: HistoryArtistStatistic?) =
        artist?.let { wanted ->
            songs.firstOrNull { song ->
                song.artists.any { if (wanted.id != null) it.id == wanted.id else it.name.equals(wanted.name, ignoreCase = true) }
            }
        }
    val album =
        snapshot.albums.firstOrNull()?.let { wanted ->
            songs.firstOrNull { song ->
                val album = song.album ?: return@firstOrNull false
                // An ID decides alone; a name only counts together with one of the album's artists.
                if (wanted.id != null) {
                    album.id == wanted.id
                } else {
                    album.name.equals(wanted.name, ignoreCase = true) &&
                        song.artists.any { artist -> wanted.artistNames.any { it.equals(artist.name, ignoreCase = true) } }
                }
            }
        }
    val others = songs.filter { it.id != top?.id }
    return chapters.mapNotNull { chapter ->
        when (chapter) {
            WrappedChapter.COVER -> others.getOrNull(0)
            WrappedChapter.TIME -> others.getOrNull(1)
            WrappedChapter.TRACKS, WrappedChapter.SUMMARY -> top
            WrappedChapter.ARTISTS -> byArtist(snapshot.artists.firstOrNull())
            WrappedChapter.MIX -> byArtist(snapshot.artists.getOrNull(1))
            WrappedChapter.ALBUMS -> album
        } ?: top
    }
}

@Composable
private fun WrappedSoundtrackButton(
    soundtrack: WrappedSoundtrack,
    foreground: Color,
    background: Color,
) {
    val label = stringResource(Res.string.wrapped_soundtrack)
    FilledIconToggleButton(
        checked = soundtrack.enabled,
        onCheckedChange = { soundtrack.toggle() },
        enabled = soundtrack.enabled || (soundtrack.available && !soundtrack.loading),
        shapes = IconButtonDefaults.toggleableShapes(),
        colors =
            IconButtonDefaults.filledIconToggleButtonColors(
                containerColor = foreground.copy(alpha = 0.14f),
                contentColor = foreground,
                checkedContainerColor = foreground,
                checkedContentColor = background,
                disabledContainerColor = foreground.copy(alpha = 0.08f),
                disabledContentColor = foreground.copy(alpha = 0.38f),
            ),
        modifier = Modifier.semantics { contentDescription = label },
    ) {
        if (soundtrack.enabled && soundtrack.loading) {
            LoadingIndicator(Modifier.size(28.dp), color = background)
        } else {
            Icon(
                if (soundtrack.enabled) MetrolistIcons.AutoMirrored.Filled.VolumeUp else MetrolistIcons.AutoMirrored.Filled.VolumeOff,
                null,
            )
        }
    }
}

@Composable
private fun WrappedChapterRail(
    count: Int,
    current: Int,
    progress: () -> Float,
    modifier: Modifier = Modifier,
    onSelect: (Int) -> Unit,
) {
    val formatter = rememberStatisticsFormatter()
    val ink = LocalContentColor.current
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(count) { index ->
            val label = stringResource(Res.string.wrapped_chapter, formatter.number((index + 1).toLong()), formatter.number(count.toLong()))
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .clickable(onClickLabel = label) { onSelect(index) }
                    .semantics {
                        contentDescription = label
                        selected = index == current
                    }.drawBehind {
                        val height = 5.dp.toPx()
                        val top = (size.height - height) / 2f
                        val radius = CornerRadius(height / 2f)
                        drawRoundRect(ink.copy(alpha = 0.28f), Offset(0f, top), Size(size.width, height), radius)
                        val fill =
                            when {
                                index < current -> 1f
                                index == current -> progress().coerceIn(0f, 1f)
                                else -> 0f
                            }
                        val start = if (layoutDirection == LayoutDirection.Rtl) size.width * (1f - fill) else 0f
                        if (fill > 0f) drawRoundRect(ink, Offset(start, top), Size(size.width * fill, height), radius)
                    },
            )
        }
    }
}

@Composable
private fun WrappedChapterContent(
    chapter: WrappedChapter,
    state: StatisticsUiState,
    snapshot: HistoryStatisticsSnapshot,
    title: String,
    measuredPlays: Int,
    notes: List<String>,
    visible: Boolean,
    motionEnabled: Boolean,
    modifier: Modifier = Modifier,
    spin: () -> Float = { 0f },
    onShare: () -> Unit,
) {
    val palette = wrappedPalette(chapter)
    CompositionLocalProvider(LocalContentColor provides palette.ink) {
        // Short chapters sit in the middle of the screen like a poster; long ones scroll from the top.
        Column(modifier, verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically)) {
            when (chapter) {
                WrappedChapter.COVER -> WrappedCover(state, palette, visible, motionEnabled, spin)
                WrappedChapter.TIME -> WrappedTime(state, palette, measuredPlays, visible, motionEnabled, spin)
                WrappedChapter.MIX -> WrappedMix(state, snapshot, palette, visible, motionEnabled, spin)
                WrappedChapter.SUMMARY -> WrappedSummary(state, palette, title, notes, visible, motionEnabled, spin, onShare)
                else -> WrappedLeaders(chapter, state, snapshot, palette, visible, motionEnabled, spin)
            }
        }
    }
}

/** The stage that holds a chapter's artwork over its animated pattern; content receives the stage height. */
@Composable
private fun WrappedStage(
    pattern: WrappedPattern,
    palette: WrappedPalette,
    motionEnabled: Boolean,
    spin: () -> Float,
    bottomBleed: Dp = 56.dp,
    content: @Composable BoxScope.(side: Dp) -> Unit,
) {
    val stage = LocalWrappedStage.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val side = minOf(maxWidth, stage)
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
                Modifier.size(side * 0.32f).align(Alignment.TopStart),
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
                Modifier.size(side * 0.28f).align(Alignment.BottomEnd),
                delayMillis = 220,
                turnMillis = 22_000,
                tilt = 8f,
            )
        }
        items.firstOrNull()?.let {
            WrappedShapedArtwork(it, MaterialShapes.Sunny, visible, motionEnabled, Modifier.size(side * 0.6f), turnMillis = 30_000)
        }
    }
    WrappedSticker(stringResource(Res.string.wrapped_cover_heading), palette, visible, motionEnabled)
    WrappedHeadline(
        checkNotNull(state.dateRange).start.year.toString(),
        palette,
        Modifier.fillMaxWidth().wrappedEntrance(visible, motionEnabled, 1),
        maxFontSize = 168.sp,
        maxLines = 1,
        visible = visible,
        motionEnabled = motionEnabled,
    )
    WrappedPills(
        listOf(
            pluralStringResource(Res.plurals.statistics_plays, state.totalPlays, state.totalPlays),
            pluralStringResource(Res.plurals.n_artist, state.artistCount, state.artistCount),
            pluralStringResource(Res.plurals.n_album, state.albumCount, state.albumCount),
        ),
        palette,
        visible,
        motionEnabled,
    )
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
        WrappedFigure(
            state.totalTimeMs / 60_000,
            formatter::number,
            palette,
            visible,
            motionEnabled,
            Modifier.width(side * 0.6f),
            maxFontSize = 88.sp,
            textAlign = TextAlign.Center,
        )
    }
    Text(
        stringResource(Res.string.wrapped_minutes_listened),
        modifier = Modifier.fillMaxWidth(),
        style = wrappedLabelStyle(),
        textAlign = TextAlign.Center,
    )
    WrappedSticker(stringResource(Res.string.wrapped_time_heading), palette, visible, motionEnabled)
    WrappedPills(listOf(statisticsDuration(state.totalTimeMs)), palette, visible, motionEnabled)
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
        style = MaterialTheme.typography.bodyMedium,
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
    val sticker =
        stringResource(
            when (chapter) {
                WrappedChapter.TRACKS -> Res.string.wrapped_top_song
                WrappedChapter.ARTISTS -> Res.string.wrapped_top_artist
                else -> Res.string.wrapped_top_album
            },
        )
    val headline = @Composable {
        WrappedHeadline(
            lead.title,
            palette,
            Modifier.fillMaxWidth().wrappedEntrance(visible, motionEnabled, 2),
            visible = visible,
            motionEnabled = motionEnabled,
        )
    }
    // The artist chapter leads with its name; the others lead with their artwork.
    if (chapter == WrappedChapter.ARTISTS) {
        WrappedSticker(sticker, palette, visible, motionEnabled, Modifier.semantics { heading() }, tilt = 3f, delayMillis = 80)
        headline()
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
        bottomBleed = if (chapter == WrappedChapter.TRACKS) 0.dp else 56.dp,
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
                    Modifier.size(side * 0.66f),
                    turnMillis = 24_000,
                )
            }

            else -> {
                WrappedRecordSleeve(lead, palette, visible, motionEnabled, side)
            }
        }
        WrappedRankBadge(formatter.number(1), palette, visible, motionEnabled, Modifier.size(side * 0.22f).align(Alignment.TopEnd))
    }
    if (chapter != WrappedChapter.ARTISTS) {
        WrappedSticker(sticker, palette, visible, motionEnabled, Modifier.semantics { heading() })
        headline()
    }
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

/** An album sleeve with its record sliding out to the end side, kept inside the stage. */
@Composable
private fun BoxScope.WrappedRecordSleeve(
    item: StatisticsItem,
    palette: WrappedPalette,
    visible: Boolean,
    motionEnabled: Boolean,
    side: Dp,
) {
    BoxWithConstraints(Modifier.matchParentSize()) {
        val sleeve = minOf(side * 0.84f, maxWidth * 0.6f)
        val travel = sleeve * 0.4f
        val start = (maxWidth - sleeve - travel) / 2
        val reveal = rememberWrappedReveal(visible, motionEnabled, 120, Spring.StiffnessLow, 0.6f)
        val slide = rememberWrappedReveal(visible, motionEnabled, 520, Spring.StiffnessVeryLow, 0.7f)
        val turn: State<Float>? =
            if (motionEnabled) {
                rememberInfiniteTransition().animateFloat(0f, 360f, infiniteRepeatable(tween(4_000, easing = LinearEasing)))
            } else {
                null
            }
        val disc = sleeve * 0.94f
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .offset { IntOffset(((start + (sleeve - disc) / 2 + travel * slide.value).toPx()).roundToInt(), 0) }
                .size(disc)
                .graphicsLayer {
                    rotationZ = turn?.value ?: 0f
                    alpha = slide.value.coerceIn(0f, 1f)
                }.drawBehind {
                    drawCircle(palette.vinyl)
                    var r = size.minDimension * 0.22f
                    while (r < size.minDimension / 2f - 3.dp.toPx()) {
                        drawCircle(palette.background.copy(alpha = 0.18f), r, style = Stroke(1.dp.toPx()))
                        r += 4.dp.toPx()
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            WrappedArtwork(item, Modifier.size(disc * 0.36f), CircleShape)
        }
        WrappedArtwork(
            item,
            Modifier
                .align(Alignment.CenterStart)
                .offset { IntOffset(start.roundToPx(), 0) }
                .size(sleeve)
                .graphicsLayer {
                    val v = reveal.value
                    scaleX = 0.4f + 0.6f * v
                    scaleY = scaleX
                    rotationZ = -3f - 20f * (1f - v)
                    alpha = (v * 3f).coerceIn(0f, 1f)
                },
            RoundedCornerShape(6.dp),
        )
    }
}

private val WrappedMixShapes =
    listOf(
        MaterialShapes.Cookie9Sided,
        MaterialShapes.Sunny,
        MaterialShapes.Clover4Leaf,
        MaterialShapes.Cookie6Sided,
        MaterialShapes.Puffy,
        MaterialShapes.Cookie4Sided,
        MaterialShapes.Clover8Leaf,
        MaterialShapes.Cookie12Sided,
        MaterialShapes.Bun,
        MaterialShapes.Cookie7Sided,
        MaterialShapes.SoftBurst,
        MaterialShapes.Circle,
    )

/** How wide the year's listening was: a collage of top artists, each in its own shape. */
@Composable
private fun WrappedMix(
    state: StatisticsUiState,
    snapshot: HistoryStatisticsSnapshot,
    palette: WrappedPalette,
    visible: Boolean,
    motionEnabled: Boolean,
    spin: () -> Float,
) {
    val artists = state.artists.take(12)
    val columns = 4
    val rows = (artists.size + columns - 1) / columns
    WrappedStage(WrappedPattern.CHEVRONS, palette, motionEnabled, spin) { side ->
        BoxWithConstraints(Modifier.fillMaxSize()) {
            // Alternate columns drop by half a cell, and higher-ranked artists are larger.
            val drop = if (artists.size > 1) 0.5f else 0f
            val cell = minOf(maxWidth / columns, side / (rows + drop))
            val left = (maxWidth - cell * columns) / 2
            val top = (side - cell * (rows + drop)) / 2
            artists.forEachIndexed { index, artist ->
                val column = index % columns
                val row = index / columns
                val scale = 1f - 0.22f * index / artists.size
                val size = cell * scale
                WrappedShapedArtwork(
                    artist,
                    WrappedMixShapes[index % WrappedMixShapes.size],
                    visible,
                    motionEnabled,
                    Modifier
                        .offset(
                            x = left + cell * column + (cell - size) / 2,
                            y = top + cell * (row + if (column % 2 == 1) drop else 0f) + (cell - size) / 2,
                        ).size(size)
                        .padding(3.dp),
                    delayMillis = index * 55L,
                    turnMillis = 12_000 + (index % 5) * 3_000,
                    tilt = ((index % 3) - 1) * 9f,
                )
            }
        }
    }
    WrappedSticker(stringResource(Res.string.wrapped_artist_mix), palette, visible, motionEnabled, Modifier.semantics { heading() })
    WrappedHeadline(
        pluralStringResource(Res.plurals.n_artist, state.artistCount, state.artistCount),
        palette,
        Modifier.fillMaxWidth().wrappedEntrance(visible, motionEnabled, 1),
        maxLines = 1,
        visible = visible,
        motionEnabled = motionEnabled,
    )
    WrappedPills(
        listOf(
            pluralStringResource(Res.plurals.n_song, snapshot.songs.size, snapshot.songs.size),
            pluralStringResource(Res.plurals.n_album, state.albumCount, state.albumCount),
        ),
        palette,
        visible,
        motionEnabled,
    )
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
                    Modifier.widthIn(min = 32.dp),
                    style = wrappedNumberStyle().copy(fontSize = 28.sp),
                )
                WrappedArtwork(
                    item,
                    Modifier.size(52.dp),
                    if (item.circularArtwork) statisticsArtworkShape(item, startRank + index - 1) else RoundedCornerShape(10.dp),
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        item.title,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(item.subtitle, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
    notes: List<String>,
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
            state.albums.firstOrNull()?.to(MaterialShapes.Cookie4Sided),
        )
    WrappedStage(WrappedPattern.WAVES, palette, motionEnabled, spin, bottomBleed = 0.dp) { side ->
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val art = minOf((maxWidth - 24.dp) / 3, side * 0.62f)
            Row(
                Modifier.align(Alignment.Center),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                trio.forEachIndexed { index, (item, shape) ->
                    WrappedShapedArtwork(
                        item,
                        shape,
                        visible,
                        motionEnabled,
                        Modifier.size(art),
                        delayMillis = index * 90L,
                        turnMillis = 20_000 + index * 4_000,
                        tilt = (index - 1) * 8f,
                    )
                }
            }
        }
    }
    WrappedSticker(stringResource(Res.string.wrapped_summary), palette, visible, motionEnabled, Modifier.semantics { heading() })
    WrappedHeadline(
        title,
        palette,
        Modifier.fillMaxWidth().wrappedEntrance(visible, motionEnabled, 1),
        maxFontSize = 72.sp,
        visible = visible,
        motionEnabled = motionEnabled,
    )
    Column {
        WrappedFigure(state.totalPlays.toLong(), formatter::number, palette, visible, motionEnabled, maxFontSize = 72.sp)
        Text(stringResource(Res.string.statistics_plays_label), style = wrappedLabelStyle(), maxLines = 1)
    }
    WrappedPills(
        listOfNotNull(statisticsDuration(state.totalTimeMs).takeIf { state.totalTimeMs > 0 }) + trio.map { it.first.title },
        palette,
        visible,
        motionEnabled,
    )
    Button(
        onClick = onShare,
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
        shapes = ButtonDefaults.shapes(),
        colors = ButtonDefaults.buttonColors(containerColor = palette.ink, contentColor = palette.background),
    ) {
        Icon(MetrolistIcons.Outlined.Share, null)
        Spacer(Modifier.width(10.dp))
        Text(stringResource(Res.string.wrapped_share_summary), style = wrappedLabelStyle(), maxLines = 1)
    }
    (notes + stringResource(Res.string.wrapped_history_scope) + stringResource(Res.string.wrapped_merge_note)).forEach {
        Text(it, style = MaterialTheme.typography.bodySmall)
    }
}
