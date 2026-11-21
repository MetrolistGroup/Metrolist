@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.shared.ui.wrapped

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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
import com.metrolist.shared.generated.resources.wrapped_empty
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
import com.metrolist.shared.generated.resources.wrapped_year
import com.metrolist.shared.ui.components.ContainedLoadingIndicator
import com.metrolist.shared.ui.components.images.AsyncImage
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
import com.metrolist.shared.ui.theme.rememberReducedMotion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock

private enum class WrappedChapter {
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
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) {
                        Icon(MetrolistIcons.AutoMirrored.Filled.ArrowBack, stringResource(Res.string.back))
                    }
                    Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = { refreshKey++ }, shapes = ButtonDefaults.shapes()) {
                        Text(stringResource(Res.string.refresh))
                    }
                }
                if (syncFailed) {
                    Text(
                        stringResource(Res.string.wrapped_sync_failed),
                        Modifier.padding(horizontal = 24.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                val result = snapshot
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
                        )
                    }
                }
            }
        }
    }
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
            if (snapshot.totals.playTimeMs > 0) add(WrappedChapter.TIME)
            if (state.tracks.isNotEmpty()) add(WrappedChapter.TRACKS)
            if (state.artists.isNotEmpty()) add(WrappedChapter.ARTISTS)
            if (state.albums.isNotEmpty()) add(WrappedChapter.ALBUMS)
            add(WrappedChapter.SUMMARY)
        }
    val soundtrack = rememberWrappedSoundtrack(playbackController, snapshot.songItems.take(10))
    var sharing by rememberSaveable { mutableStateOf(false) }
    val title = stringResource(Res.string.wrapped_year, range.start.year.toString())
    val dateLabel = statisticsDateRangeLabel(range)
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
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToggleButton(
                checked = soundtrack.enabled,
                onCheckedChange = { soundtrack.toggle() },
                enabled = soundtrack.enabled || (soundtrack.available && !soundtrack.loading),
            ) {
                Text(stringResource(if (soundtrack.loading) Res.string.wrapped_soundtrack_loading else Res.string.wrapped_soundtrack))
            }
        }
        if (soundtrack.failed) {
            Text(
                stringResource(Res.string.wrapped_soundtrack_failed),
                Modifier.padding(horizontal = 24.dp),
                color = MaterialTheme.colorScheme.error,
            )
        } else if (!soundtrack.available) {
            Text(
                stringResource(Res.string.wrapped_soundtrack_unavailable),
                Modifier.padding(horizontal = 24.dp),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        val content: @Composable (WrappedChapter) -> Unit = { chapter ->
            WrappedChapterContent(chapter, state, title, dateLabel, snapshot.songs.sumOf { it.measuredPlayCount }) { sharing = true }
        }
        if (desktopLayout) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(360.dp),
                modifier = Modifier.widthIn(max = 1200.dp).fillMaxSize().align(Alignment.CenterHorizontally),
                contentPadding = PaddingValues(start = 24.dp, top = 8.dp, end = 24.dp, bottom = bottomContentPadding + 24.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(chapters, key = { it.name }, span = {
                    if (it == WrappedChapter.COVER ||
                        it == WrappedChapter.SUMMARY
                    ) {
                        GridItemSpan(maxLineSpan)
                    } else {
                        GridItemSpan(1)
                    }
                }) { content(it) }
            }
        } else {
            val pager = rememberPagerState { chapters.size }
            val scope = rememberCoroutineScope()
            val reducedMotion = rememberReducedMotion()
            val pageMotion = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()

            fun moveTo(page: Int) {
                scope.launch {
                    if (reducedMotion) pager.scrollToPage(page) else pager.animateScrollToPage(page, animationSpec = pageMotion)
                }
            }
            HorizontalPager(state = pager, modifier = Modifier.weight(1f), verticalAlignment = Alignment.Top) { page ->
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
                    content(chapters[page])
                }
            }
            Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = bottomContentPadding + 12.dp)) {
                LinearProgressIndicator(progress = { (pager.currentPage + 1f) / chapters.size }, modifier = Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = { moveTo(pager.currentPage - 1) },
                        enabled = pager.currentPage > 0,
                        shapes = ButtonDefaults.shapes(),
                    ) {
                        Text(stringResource(Res.string.previous))
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        stringResource(
                            Res.string.wrapped_chapter,
                            formatter.number((pager.currentPage + 1).toLong()),
                            formatter.number(chapters.size.toLong()),
                        ),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = {
                        moveTo(pager.currentPage + 1)
                    }, enabled = pager.currentPage < chapters.lastIndex, shapes = ButtonDefaults.shapes()) {
                        Text(stringResource(Res.string.next))
                    }
                }
            }
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
    onShare: () -> Unit,
) {
    val formatter = rememberStatisticsFormatter()
    val heading =
        when (chapter) {
            WrappedChapter.COVER -> title
            WrappedChapter.TIME -> stringResource(Res.string.wrapped_measured_time)
            WrappedChapter.TRACKS -> stringResource(Res.string.statistics_top_tracks)
            WrappedChapter.ARTISTS -> stringResource(Res.string.statistics_top_artists)
            WrappedChapter.ALBUMS -> stringResource(Res.string.statistics_top_albums)
            WrappedChapter.SUMMARY -> stringResource(Res.string.wrapped_summary)
        }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text(heading, Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineLargeEmphasized)
            if (chapter == WrappedChapter.COVER) {
                Text(dateLabel, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                state.tracks.firstOrNull()?.let { WrappedArtwork(it, 0, Modifier.size(176.dp).align(Alignment.CenterHorizontally)) }
            }
            when (chapter) {
                WrappedChapter.COVER, WrappedChapter.SUMMARY -> {
                    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.extraLarge) {
                        Column(Modifier.fillMaxWidth().padding(24.dp)) {
                            Text(formatter.number(state.totalPlays.toLong()), style = MaterialTheme.typography.displayLargeEmphasized)
                            Text(stringResource(Res.string.statistics_plays_label), style = MaterialTheme.typography.titleMedium)
                            if (chapter == WrappedChapter.SUMMARY && state.totalTimeMs > 0) {
                                Spacer(Modifier.height(16.dp))
                                Text(statisticsDuration(state.totalTimeMs), style = MaterialTheme.typography.headlineLargeEmphasized)
                                Text(stringResource(Res.string.wrapped_measured_time), style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                    if (chapter == WrappedChapter.SUMMARY) {
                        Text(stringResource(Res.string.statistics_top_tracks), style = MaterialTheme.typography.titleLarge)
                        WrappedRankings(state.tracks.take(3))
                        FilledTonalButton(onClick = onShare, shapes = ButtonDefaults.shapes()) {
                            Text(stringResource(Res.string.wrapped_share_summary))
                        }
                    }
                    Text(
                        stringResource(Res.string.wrapped_merge_note),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                WrappedChapter.TIME -> {
                    Text(
                        statisticsDuration(state.totalTimeMs),
                        style = MaterialTheme.typography.displayLargeEmphasized,
                        color = MaterialTheme.colorScheme.primary,
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

                else -> {
                    val items =
                        when (chapter) {
                            WrappedChapter.TRACKS -> state.tracks
                            WrappedChapter.ARTISTS -> state.artists
                            else -> state.albums
                        }
                    items.firstOrNull()?.let { lead ->
                        WrappedArtwork(lead, 0, Modifier.size(168.dp).align(Alignment.CenterHorizontally))
                        Text(lead.title, style = MaterialTheme.typography.headlineMediumEmphasized)
                        Text(lead.subtitle, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    }
                    WrappedRankings(items.drop(1).take(4), startRank = 2)
                }
            }
        }
    }
}

@Composable
private fun WrappedRankings(
    items: List<StatisticsItem>,
    startRank: Int = 1,
) {
    val formatter = rememberStatisticsFormatter()
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        items.forEachIndexed { index, item ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    formatter.number((startRank + index).toLong()),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                WrappedArtwork(item, startRank + index - 1, Modifier.size(56.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(item.subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun WrappedArtwork(
    item: StatisticsItem,
    index: Int,
    modifier: Modifier,
) {
    if (item.thumbnailUrl != null) AsyncImage(item.thumbnailUrl, modifier, shape = statisticsArtworkShape(item, index))
}
