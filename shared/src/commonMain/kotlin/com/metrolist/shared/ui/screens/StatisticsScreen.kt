@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.shared.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.metrolist.shared.constants.CarouselItemCornerRadius
import com.metrolist.shared.constants.GridTileSpacing
import com.metrolist.shared.constants.ListThumbnailSize
import com.metrolist.shared.constants.currentGridThumbnailHeight
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.albums
import com.metrolist.shared.generated.resources.artists
import com.metrolist.shared.generated.resources.back
import com.metrolist.shared.generated.resources.more_options_for
import com.metrolist.shared.generated.resources.n_album
import com.metrolist.shared.generated.resources.n_artist
import com.metrolist.shared.generated.resources.n_month
import com.metrolist.shared.generated.resources.n_time
import com.metrolist.shared.generated.resources.n_track
import com.metrolist.shared.generated.resources.n_week
import com.metrolist.shared.generated.resources.n_year
import com.metrolist.shared.generated.resources.play
import com.metrolist.shared.generated.resources.refreshing
import com.metrolist.shared.generated.resources.see_all
import com.metrolist.shared.generated.resources.share
import com.metrolist.shared.generated.resources.shuffle
import com.metrolist.shared.generated.resources.statistics
import com.metrolist.shared.generated.resources.statistics_activity
import com.metrolist.shared.generated.resources.statistics_calendar_mode
import com.metrolist.shared.generated.resources.statistics_choose_dates
import com.metrolist.shared.generated.resources.statistics_continuous_mode
import com.metrolist.shared.generated.resources.statistics_custom_dates
import com.metrolist.shared.generated.resources.statistics_duration_hours_minutes
import com.metrolist.shared.generated.resources.statistics_duration_minutes
import com.metrolist.shared.generated.resources.statistics_lifetime
import com.metrolist.shared.generated.resources.statistics_listening_habits
import com.metrolist.shared.generated.resources.statistics_no_data
import com.metrolist.shared.generated.resources.statistics_overview
import com.metrolist.shared.generated.resources.statistics_period_summary
import com.metrolist.shared.generated.resources.statistics_period_summary_no_time
import com.metrolist.shared.generated.resources.statistics_play_count_duration
import com.metrolist.shared.generated.resources.statistics_plays
import com.metrolist.shared.generated.resources.statistics_plays_label
import com.metrolist.shared.generated.resources.statistics_time
import com.metrolist.shared.ui.components.ExpressiveFilterChip
import com.metrolist.shared.ui.components.HeaderPlaybackButtons
import com.metrolist.shared.ui.components.LoadingScreen
import com.metrolist.shared.ui.components.RefreshIndicator
import com.metrolist.shared.ui.components.ScrollingFabState
import com.metrolist.shared.ui.components.images.AsyncImage
import com.metrolist.shared.ui.components.rememberScrollingFabState
import com.metrolist.shared.ui.components.withStaticCorners
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.menu.artworkMenuActions
import com.metrolist.shared.ui.navigation.NavigationBackHandler
import com.metrolist.shared.ui.navigation.acceptsInput
import com.metrolist.shared.ui.navigation.blockInput
import com.metrolist.shared.ui.navigation.rememberNavigationMotion
import com.metrolist.shared.ui.statistics.ActivityHeatmap
import com.metrolist.shared.ui.statistics.HourlyActivityChart
import com.metrolist.shared.ui.statistics.StatisticsDateRange
import com.metrolist.shared.ui.statistics.StatisticsDateRangePicker
import com.metrolist.shared.ui.statistics.StatisticsPeriodMode
import com.metrolist.shared.ui.statistics.StatisticsShareDialog
import com.metrolist.shared.ui.statistics.calendarRange
import com.metrolist.shared.ui.statistics.canExportStatistics
import com.metrolist.shared.ui.statistics.statisticsDateRangeLabel
import com.metrolist.shared.ui.theme.ComponentShapes
import com.metrolist.shared.ui.tv.tvFocusHighlight
import com.metrolist.shared.ui.utils.DragScrollableColumn
import com.metrolist.shared.ui.utils.carouselScroll
import com.metrolist.shared.ui.utils.desktopArrowScroll
import com.metrolist.shared.ui.utils.segmentedGroupShape
import com.metrolist.shared.ui.utils.toPolygonShape
import com.metrolist.shared.utils.formatDuration
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

enum class StatisticsPeriod {
    WEEK,
    MONTH,
    THREE_MONTHS,
    SIX_MONTHS,
    YEAR,
    ALL,
}

enum class StatisticsDetail {
    TRACKS,
    ALBUMS,
    ARTISTS,
}

data class StatisticsItem(
    val id: String,
    val title: String,
    val subtitle: String,
    val thumbnailUrl: String?,
    val circularArtwork: Boolean = false,
    val isClickable: Boolean = true,
    val rankedTitle: String = title,
)

data class StatisticsUiState(
    val period: StatisticsPeriod = StatisticsPeriod.WEEK,
    val dateRange: StatisticsDateRange? = null,
    val mode: StatisticsPeriodMode = StatisticsPeriodMode.CONTINUOUS,
    val totalPlays: Int = 0,
    val totalTimeMs: Long = 0L,
    val artistCount: Int = 0,
    val albumCount: Int = 0,
    val dailyStats: Map<String, Int> = emptyMap(),
    val hourlyStats: Map<Int, Int> = emptyMap(),
    val tracks: List<StatisticsItem> = emptyList(),
    val albums: List<StatisticsItem> = emptyList(),
    val artists: List<StatisticsItem> = emptyList(),
    val isLoading: Boolean = false,
    val hasLoaded: Boolean = !isLoading,
    val errorMessage: String? = null,
)

@Composable
fun StatisticsScreen(
    state: StatisticsUiState,
    onPeriodSelected: (StatisticsPeriod) -> Unit,
    onModeSelected: (StatisticsPeriodMode) -> Unit,
    onDateRangeSelected: (StatisticsDateRange) -> Unit,
    onPlayAll: (shuffle: Boolean) -> Unit,
    onRefresh: (() -> Unit)?,
    exportLabel: String,
    onExport: suspend (ImageBitmap) -> Boolean,
    onItemClick: (StatisticsItem) -> Unit = {},
    onPlayItem: ((StatisticsItem) -> Unit)? = null,
    onItemMenu: ((StatisticsItem) -> Unit)? = null,
    onOpenDetail: ((StatisticsDetail) -> Unit)? = null,
    onNavigateBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    bottomContentPadding: Dp = 0.dp,
    onArtistVisible: (suspend (StatisticsItem) -> Unit)? = null,
) {
    var detail by remember { mutableStateOf<StatisticsDetail?>(null) }
    val openDetail: (StatisticsDetail) -> Unit = onOpenDetail ?: { detail = it }
    val motion = rememberNavigationMotion()
    NavigationBackHandler(enabled = detail != null, onBack = { detail = null })
    var shareSnapshot by remember { mutableStateOf<StatisticsUiState?>(null) }
    shareSnapshot?.let { snapshot ->
        val period = statisticsPeriodLabel(snapshot)
        StatisticsShareDialog(
            state = snapshot,
            period = period,
            summary = statisticsSummary(snapshot),
            exportLabel = exportLabel,
            onExport = onExport,
            onDismiss = { shareSnapshot = null },
        )
    }
    val listState = rememberLazyListState()
    val tracksState = rememberLazyListState()
    val albumsState = rememberLazyListState()
    val artistsState = rememberLazyListState()
    val isLoading by rememberUpdatedState(state.isLoading)
    LaunchedEffect(state.period, state.mode, state.dateRange) {
        snapshotFlow { isLoading }.first { !it }
        tracksState.requestScrollToItem(0)
        albumsState.requestScrollToItem(0)
        artistsState.requestScrollToItem(0)
    }
    AnimatedContent(
        targetState = detail,
        modifier = modifier,
        transitionSpec = { motion.transition(isPop = targetState == null) },
        label = "statisticsDetail",
    ) { selectedDetail ->
        Box(Modifier.fillMaxSize().blockInput(!transition.acceptsInput(EnterExitState.Visible))) {
            if (selectedDetail != null) {
                StatisticsDetailScreen(
                    detail = selectedDetail,
                    items =
                        when (selectedDetail) {
                            StatisticsDetail.TRACKS -> state.tracks
                            StatisticsDetail.ALBUMS -> state.albums
                            StatisticsDetail.ARTISTS -> state.artists
                        },
                    count =
                        when (selectedDetail) {
                            StatisticsDetail.TRACKS -> state.tracks.size
                            StatisticsDetail.ALBUMS -> state.albumCount
                            StatisticsDetail.ARTISTS -> state.artistCount
                        },
                    onNavigateBack = { detail = null },
                    onItemClick = onItemClick,
                    onPlayItem = onPlayItem,
                    onItemMenu = onItemMenu,
                    onPlayAll = onPlayAll,
                    isLoading = state.isLoading,
                    errorMessage = state.errorMessage,
                    contentPadding = PaddingValues(bottom = bottomContentPadding),
                    onArtistVisible = onArtistVisible,
                )
            } else {
                Surface(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize()) {
                        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                onNavigateBack?.let { BackButton(it) }
                                Spacer(Modifier.weight(1f))
                            }
                            StatisticsRefreshIndicator(state.isLoading && state.hasLoaded)
                            state.errorMessage?.let {
                                Text(
                                    it,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(16.dp),
                                )
                            }
                            val alpha by animateFloatAsState(
                                if (state.isLoading &&
                                    state.hasLoaded
                                ) {
                                    0.7f
                                } else {
                                    1f
                                },
                                MaterialTheme.motionScheme.defaultEffectsSpec(),
                            )
                            val pullState = rememberPullToRefreshState()
                            var userRefreshing by remember { mutableStateOf(false) }
                            LaunchedEffect(userRefreshing) {
                                if (!userRefreshing) return@LaunchedEffect
                                withTimeoutOrNull(1_500L) { snapshotFlow { isLoading }.first { it } }
                                snapshotFlow { isLoading }.first { !it }
                                userRefreshing = false
                            }
                            PullToRefreshBox(
                                isRefreshing = userRefreshing,
                                onRefresh = {
                                    userRefreshing = true
                                    if (!isLoading) onRefresh?.invoke()
                                },
                                state = pullState,
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                                indicator = {
                                    if (onRefresh != null) {
                                        RefreshIndicator(
                                            state = pullState,
                                            isRefreshing = userRefreshing,
                                            modifier = Modifier.align(Alignment.TopCenter),
                                        )
                                    }
                                },
                            ) {
                                DragScrollableColumn(
                                    state = listState,
                                    modifier =
                                        Modifier
                                            .fillMaxSize()
                                            .desktopArrowScroll(listState)
                                            .graphicsLayer { this.alpha = alpha },
                                    contentPadding = PaddingValues(bottom = 96.dp + bottomContentPadding),
                                    verticalArrangement = Arrangement.spacedBy(18.dp),
                                ) {
                                    item {
                                        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Text(
                                                stringResource(Res.string.statistics),
                                                style = MaterialTheme.typography.headlineLarge,
                                                modifier = Modifier.semantics { heading() },
                                            )
                                            Text(
                                                statisticsSummary(state),
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                            Button(
                                                onClick = { onPlayAll(true) },
                                                enabled = !state.isLoading && state.errorMessage == null && state.tracks.isNotEmpty(),
                                                modifier =
                                                    Modifier
                                                        .fillMaxWidth()
                                                        .height(ButtonDefaults.MediumContainerHeight)
                                                        .tvFocusHighlight(CircleShape),
                                                shapes = ButtonDefaults.shapes(),
                                            ) {
                                                Icon(MetrolistIcons.Rounded.Shuffle, null)
                                                Spacer(Modifier.width(8.dp))
                                                Text(stringResource(Res.string.shuffle), style = MaterialTheme.typography.titleMedium)
                                            }
                                            HorizontalDivider(Modifier.padding(top = 8.dp))
                                        }
                                    }
                                    item { PeriodButtons(state, onPeriodSelected, onModeSelected, onDateRangeSelected) }
                                    if (state.isLoading && !state.hasLoaded) {
                                        item { LoadingScreen(Modifier.fillMaxWidth().height(160.dp)) }
                                    } else {
                                        item {
                                            StatisticsCarousel(trackCount(state.tracks.size), state.tracks, tracksState, onItemClick, {
                                                openDetail(StatisticsDetail.TRACKS)
                                            }, onItemMenu)
                                        }
                                        item {
                                            StatisticsCarousel(albumCount(state.albumCount), state.albums, albumsState, onItemClick, {
                                                openDetail(StatisticsDetail.ALBUMS)
                                            }, onItemMenu)
                                        }
                                        item {
                                            StatisticsCarousel(artistCount(state.artistCount), state.artists, artistsState, onItemClick, {
                                                openDetail(StatisticsDetail.ARTISTS)
                                            }, onItemMenu, onArtistVisible)
                                        }
                                        item { StatisticsCard(stringResource(Res.string.statistics_overview)) { SummaryGrid(state) } }
                                        item {
                                            StatisticsCard(stringResource(Res.string.statistics_activity)) {
                                                val range =
                                                    state.dateRange
                                                        ?: if (state.mode ==
                                                            StatisticsPeriodMode.CALENDAR
                                                        ) {
                                                            state.period.calendarRange()
                                                        } else {
                                                            null
                                                        }
                                                ActivityHeatmap(state.dailyStats, state.period, range)
                                            }
                                        }
                                        item {
                                            StatisticsCard(
                                                stringResource(Res.string.statistics_listening_habits),
                                            ) { HourlyActivityChart(state.hourlyStats) }
                                        }
                                    }
                                }
                            }
                        }
                        val fabState = rememberScrollingFabState(listState)
                        val shareLabel = stringResource(Res.string.share)
                        AnimatedVisibility(
                            visible = state.canExportStatistics(),
                            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = bottomContentPadding + 16.dp),
                        ) {
                            ExtendedFloatingActionButton(
                                modifier =
                                    Modifier
                                        .blockInput(!state.canExportStatistics())
                                        .tvFocusHighlight(FloatingActionButtonDefaults.extendedFabShape),
                                onClick = {
                                    shareSnapshot =
                                        state.copy(
                                            dateRange =
                                                state.dateRange
                                                    ?: if (state.mode ==
                                                        StatisticsPeriodMode.CALENDAR
                                                    ) {
                                                        state.period.calendarRange()
                                                    } else {
                                                        null
                                                    },
                                        )
                                },
                                expanded = fabState == ScrollingFabState.Expanded,
                                icon = {
                                    Icon(
                                        MetrolistIcons.Outlined.Share,
                                        shareLabel.takeUnless { fabState == ScrollingFabState.Expanded },
                                    )
                                },
                                text = { Text(shareLabel) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BackButton(onBack: () -> Unit) {
    IconButton(onClick = onBack, modifier = Modifier.tvFocusHighlight(CircleShape), shapes = IconButtonDefaults.shapes()) {
        Icon(MetrolistIcons.AutoMirrored.Filled.ArrowBack, stringResource(Res.string.back))
    }
}

@Composable
private fun StatisticsRefreshIndicator(refreshing: Boolean) {
    Box(Modifier.fillMaxWidth().height(10.dp)) {
        if (refreshing) {
            val description = stringResource(Res.string.refreshing)
            LinearWavyProgressIndicator(
                Modifier.fillMaxWidth().semantics {
                    contentDescription = description
                    liveRegion = LiveRegionMode.Polite
                },
            )
        }
    }
}

@Composable
private fun PeriodButtons(
    state: StatisticsUiState,
    onSelected: (StatisticsPeriod) -> Unit,
    onModeSelected: (StatisticsPeriodMode) -> Unit,
    onDateRangeSelected: (StatisticsDateRange) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var showDates by remember { mutableStateOf(false) }
    if (showDates) StatisticsDateRangePicker(state.dateRange, onDateRangeSelected, onDismiss = { showDates = false })
    val scrollState = rememberScrollState()
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .carouselScroll(scrollState)
                .horizontalScroll(scrollState)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            ExpressiveFilterChip(selected = false, onClick = { expanded = true }, label = {
                Text(
                    stringResource(
                        if (state.dateRange !=
                            null
                        ) {
                            Res.string.statistics_custom_dates
                        } else if (state.mode ==
                            StatisticsPeriodMode.CALENDAR
                        ) {
                            Res.string.statistics_calendar_mode
                        } else {
                            Res.string.statistics_continuous_mode
                        },
                    ),
                )
                Icon(MetrolistIcons.Outlined.KeyboardArrowDown, null)
            })
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                StatisticsPeriodMode.entries.forEach { mode ->
                    DropdownMenuItem(
                        modifier = Modifier.tvFocusHighlight(RoundedCornerShape(4.dp)),
                        text = {
                            Text(
                                stringResource(
                                    if (mode ==
                                        StatisticsPeriodMode.CALENDAR
                                    ) {
                                        Res.string.statistics_calendar_mode
                                    } else {
                                        Res.string.statistics_continuous_mode
                                    },
                                ),
                            )
                        },
                        onClick = {
                            expanded = false
                            onModeSelected(mode)
                        },
                    )
                }
                DropdownMenuItem(modifier = Modifier.tvFocusHighlight(RoundedCornerShape(4.dp)), text = {
                    Text(stringResource(Res.string.statistics_choose_dates))
                }, onClick = {
                    expanded = false
                    showDates =
                        true
                })
            }
        }
        val labels = StatisticsPeriod.entries.map { periodLabel(it) }
        ButtonGroup(
            overflowIndicator = { ButtonGroupDefaults.OverflowIndicator(it) },
            modifier = Modifier.selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
        ) {
            StatisticsPeriod.entries.forEachIndexed { index, period ->
                customItem(
                    buttonGroupContent = {
                        val interactions = remember { MutableInteractionSource() }
                        val shapes =
                            when (index) {
                                0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                                StatisticsPeriod.entries.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                                else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                            }.withStaticCorners()
                        ToggleButton(
                            checked = state.dateRange == null && state.period == period,
                            onCheckedChange = { onSelected(period) },
                            interactionSource = interactions,
                            shapes = shapes,
                            modifier = Modifier.animateWidth(interactions).tvFocusHighlight(shapes.shape),
                        ) {
                            Text(labels[index], maxLines = 1, softWrap = false)
                        }
                    },
                    menuContent = {
                        DropdownMenuItem(text = { Text(labels[index]) }, onClick = { onSelected(period) })
                    },
                )
            }
        }
    }
}

@Composable
private fun StatisticsCard(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier =
                Modifier
                    .semantics {
                        heading()
                    },
        )
        Card(
            shape = ComponentShapes.CardShape,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        ) {
            Column(Modifier.padding(16.dp)) { content() }
        }
    }
}

@Composable
private fun SummaryGrid(state: StatisticsUiState) {
    val anim = remember { Animatable(0f) }
    LaunchedEffect(state.totalPlays, state.totalTimeMs, state.artistCount, state.albumCount) {
        anim.snapTo(0f)
        anim.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 850, easing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)),
        )
    }
    val progress = anim.value

    val animatedTracks = (state.totalPlays * progress).roundToInt()
    val animatedTimeMs = (state.totalTimeMs * progress).toLong()
    val animatedArtists = (state.artistCount * progress).roundToInt()
    val animatedAlbums = (state.albumCount * progress).roundToInt()

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SummaryCard(stringResource(Res.string.statistics_plays_label), animatedTracks.toString(), Modifier.weight(1f))
            SummaryCard(
                stringResource(Res.string.statistics_time),
                if (hasMeasuredPlayTime(state.totalTimeMs)) {
                    statisticsDuration(animatedTimeMs)
                } else {
                    stringResource(Res.string.statistics_no_data)
                },
                Modifier.weight(1f),
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SummaryCard(stringResource(Res.string.artists), animatedArtists.toString(), Modifier.weight(1f))
            SummaryCard(stringResource(Res.string.albums), animatedAlbums.toString(), Modifier.weight(1f))
        }
    }
}

@Composable
private fun SummaryCard(
    label: String,
    value: String,
    modifier: Modifier,
) {
    Card(
        modifier = modifier,
        shape = MaterialTheme.shapes.extraSmall,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
        }
    }
}

@Composable
fun StatisticsDetailScreen(
    detail: StatisticsDetail,
    items: List<StatisticsItem>,
    onNavigateBack: () -> Unit,
    onPlayAll: (shuffle: Boolean) -> Unit,
    onItemClick: ((StatisticsItem) -> Unit)? = null,
    onPlayItem: ((StatisticsItem) -> Unit)? = null,
    onItemMenu: ((StatisticsItem) -> Unit)? = null,
    count: Int = items.size,
    isLoading: Boolean = false,
    errorMessage: String? = null,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    modifier: Modifier = Modifier,
    onArtistVisible: (suspend (StatisticsItem) -> Unit)? = null,
) {
    val listState = rememberLazyListState()
    val alpha by animateFloatAsState(if (isLoading) 0.7f else 1f, MaterialTheme.motionScheme.defaultEffectsSpec())
    Surface(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) { BackButton(onNavigateBack) }
            StatisticsRefreshIndicator(isLoading)
            errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
            DragScrollableColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().desktopArrowScroll(listState).graphicsLayer { this.alpha = alpha },
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = contentPadding.calculateBottomPadding() + 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                item {
                    Column(Modifier.padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(
                            when (detail) {
                                StatisticsDetail.TRACKS -> trackCount(count)
                                StatisticsDetail.ALBUMS -> albumCount(count)
                                StatisticsDetail.ARTISTS -> artistCount(count)
                            },
                            style = MaterialTheme.typography.headlineLarge,
                            modifier = Modifier.semantics { heading() },
                        )
                        HeaderPlaybackButtons(
                            onPlayAll = { onPlayAll(false) },
                            onShuffle = { onPlayAll(true) },
                            playLabel = stringResource(Res.string.play),
                            shuffleLabel = stringResource(Res.string.shuffle),
                            buttonHeight = ButtonDefaults.MediumContainerHeight,
                            modifier = Modifier.fillMaxWidth(),
                            playFirst = true,
                            tonalShuffle = true,
                            enabled = !isLoading && errorMessage == null && items.isNotEmpty(),
                        )
                        HorizontalDivider()
                    }
                }
                if (items.isEmpty() && !isLoading && errorMessage == null) {
                    item {
                        Text(
                            stringResource(Res.string.statistics_no_data),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                itemsIndexed(items, key = { _, item -> item.id }) { index, item ->
                    LaunchedEffect(item.id) { if (item.circularArtwork) onArtistVisible?.invoke(item) }
                    val interactions = remember { MutableInteractionSource() }
                    val pressed by interactions.collectIsPressedAsState()
                    val shape = segmentedGroupShape(index == 0, index == items.lastIndex, pressed)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(shape)
                            .background(MaterialTheme.colorScheme.surfaceContainer)
                            .artworkMenuActions(onItemMenu?.let { { it(item) } })
                            .combinedClickable(
                                enabled = !isLoading && item.isClickable,
                                interactionSource = interactions,
                                indication = ripple(),
                                onClick = { (onItemClick ?: onPlayItem)?.invoke(item) },
                                onLongClick = onItemMenu?.let { { it(item) } },
                            ).padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        AsyncImage(item.thumbnailUrl, Modifier.size(ListThumbnailSize), shape = statisticsArtworkShape(item, index))
                        Column(Modifier.weight(1f)) {
                            Text(
                                item.rankedTitle,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                item.subtitle,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        onItemMenu?.let { menu ->
                            IconButton(onClick = { menu(item) }, enabled = !isLoading, shapes = IconButtonDefaults.shapes()) {
                                Icon(MetrolistIcons.Default.MoreVert, stringResource(Res.string.more_options_for, item.title))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun periodLabel(period: StatisticsPeriod): String =
    when (period) {
        StatisticsPeriod.WEEK -> pluralStringResource(Res.plurals.n_week, 1, 1)
        StatisticsPeriod.MONTH -> pluralStringResource(Res.plurals.n_month, 1, 1)
        StatisticsPeriod.THREE_MONTHS -> pluralStringResource(Res.plurals.n_month, 3, 3)
        StatisticsPeriod.SIX_MONTHS -> pluralStringResource(Res.plurals.n_month, 6, 6)
        StatisticsPeriod.YEAR -> pluralStringResource(Res.plurals.n_year, 1, 1)
        StatisticsPeriod.ALL -> stringResource(Res.string.statistics_lifetime)
    }

/**
 * Statistics know a play duration only when this app measured it. Plays synced from YouTube
 * history and history imported from the old desktop app carry no duration, so a zero total
 * means nothing was measured, not that nothing was listened to.
 */
internal fun hasMeasuredPlayTime(playTimeMs: Long): Boolean = playTimeMs > 0

@Composable
internal fun statisticsDuration(milliseconds: Long): String {
    val hours = milliseconds / 3_600_000
    val minutes = (milliseconds % 3_600_000) / 60_000
    return if (hours > 0) {
        stringResource(Res.string.statistics_duration_hours_minutes, hours, minutes)
    } else {
        stringResource(Res.string.statistics_duration_minutes, minutes)
    }
}

@Composable
private fun statisticsSummary(state: StatisticsUiState): String {
    val period = statisticsPeriodLabel(state)
    val plays = playCount(state.totalPlays)
    return if (hasMeasuredPlayTime(state.totalTimeMs)) {
        stringResource(Res.string.statistics_period_summary, period, plays, statisticsDuration(state.totalTimeMs))
    } else {
        stringResource(Res.string.statistics_period_summary_no_time, period, plays)
    }
}

@Composable
fun statisticsPlayCountDuration(
    playCount: Int,
    playTimeMs: Long,
): String {
    val plays = pluralStringResource(Res.plurals.n_time, playCount, playCount)
    return if (hasMeasuredPlayTime(playTimeMs)) {
        stringResource(Res.string.statistics_play_count_duration, plays, formatDuration(playTimeMs))
    } else {
        plays
    }
}

@Composable
private fun trackCount(count: Int): String = pluralStringResource(Res.plurals.n_track, count, count)

@Composable
private fun playCount(count: Int): String = pluralStringResource(Res.plurals.statistics_plays, count, count)

@Composable
private fun albumCount(count: Int): String = pluralStringResource(Res.plurals.n_album, count, count)

@Composable
private fun artistCount(count: Int): String = pluralStringResource(Res.plurals.n_artist, count, count)

@Composable
private fun statisticsPeriodLabel(state: StatisticsUiState): String {
    val range = state.dateRange ?: if (state.mode == StatisticsPeriodMode.CALENDAR) state.period.calendarRange() else null
    return range?.let { statisticsDateRangeLabel(it) } ?: periodLabel(state.period)
}

@Composable
internal fun statisticsArtworkShape(
    item: StatisticsItem,
    index: Int,
): Shape =
    if (item.circularArtwork) {
        when (index % 3) {
            0 -> MaterialShapes.Cookie9Sided
            1 -> MaterialShapes.Circle
            else -> MaterialShapes.Cookie12Sided
        }.toPolygonShape()
    } else {
        RoundedCornerShape(CarouselItemCornerRadius)
    }

@Composable
private fun StatisticsCarousel(
    title: String,
    items: List<StatisticsItem>,
    carouselState: LazyListState,
    onItemClick: (StatisticsItem) -> Unit,
    onOpenDetail: () -> Unit,
    onItemMenu: ((StatisticsItem) -> Unit)? = null,
    onItemVisible: (suspend (StatisticsItem) -> Unit)? = null,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                modifier =
                    Modifier.weight(1f).semantics {
                        heading()
                    },
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            FilledIconButton(onClick = onOpenDetail, shapes = IconButtonDefaults.shapes()) {
                Icon(MetrolistIcons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(Res.string.see_all))
            }
        }
        if (items.isEmpty()) {
            Text(
                stringResource(Res.string.statistics_no_data),
                Modifier.padding(horizontal = 16.dp).semantics {
                    liveRegion =
                        LiveRegionMode.Polite
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            val size = currentGridThumbnailHeight()
            val titleHeight =
                with(LocalDensity.current) {
                    MaterialTheme.typography.titleSmall.lineHeight
                        .toDp()
                }
            val subtitleHeight =
                with(LocalDensity.current) {
                    MaterialTheme.typography.bodySmall.lineHeight
                        .toDp()
                }
            LazyRow(
                state = carouselState,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(size + 6.dp + titleHeight + subtitleHeight)
                        .semantics { role = Role.Carousel }
                        .carouselScroll(carouselState),
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(GridTileSpacing),
            ) {
                itemsIndexed(items.take(500), key = { _, item -> item.id }) { index, item ->
                    LaunchedEffect(item.id) { onItemVisible?.invoke(item) }
                    val shape = statisticsArtworkShape(item, index)
                    Column(
                        Modifier
                            .width(size)
                            .artworkMenuActions(onItemMenu?.let { { it(item) } })
                            .combinedClickable(enabled = item.isClickable, role = Role.Button, onClick = {
                                onItemClick(item)
                            }, onLongClick = onItemMenu?.let { { it(item) } }),
                    ) {
                        Box(Modifier.size(size)) {
                            AsyncImage(item.thumbnailUrl, Modifier.fillMaxSize(), shape = shape)
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            item.rankedTitle,
                            modifier = Modifier.height(titleHeight),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            item.subtitle,
                            modifier = Modifier.height(subtitleHeight),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
