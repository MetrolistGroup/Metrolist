package com.metrolist.shared.ui.statistics

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.HoverInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.statistics_chart_detail
import com.metrolist.shared.generated.resources.statistics_chart_hour
import com.metrolist.shared.generated.resources.statistics_chart_less
import com.metrolist.shared.generated.resources.statistics_chart_listens
import com.metrolist.shared.generated.resources.statistics_chart_more
import com.metrolist.shared.generated.resources.statistics_chart_range
import com.metrolist.shared.ui.screens.StatisticsPeriod
import com.metrolist.shared.ui.utils.carouselScroll
import com.metrolist.shared.utils.isDesktop
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.todayIn
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock
import kotlin.time.Instant

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ActivityHeatmap(
    dailyStats: Map<String, Int>,
    period: StatisticsPeriod,
    dateRange: StatisticsDateRange? = null,
) {
    val formatter = remember(LocalLocale.current) { StatisticsFormatter() }
    val today = dateRange?.end ?: Clock.System.todayIn(TimeZone.currentSystemDefault())
    val firstDate =
        dateRange?.start ?: period.takeUnless { it == StatisticsPeriod.ALL }?.let {
            Instant.fromEpochMilliseconds(it.epochRange().first).toLocalDateTime(TimeZone.currentSystemDefault()).date
        }
    val weeks =
        remember(today, dailyStats, firstDate, dateRange) {
            listeningActivityWeeks(today, dailyStats, firstDate, showFullRange = dateRange != null)
        }
    val days = remember(weeks) { weeks.flatten().filterNotNull() }
    val buckets = remember(days) { listeningCountBuckets(days.maxOf { it.count }) }
    var selectedDate by remember(today, dailyStats, firstDate) { mutableStateOf(today) }
    val selectedDay = days.first { it.date == selectedDate }
    val scroll = rememberScrollState()
    val labelHeight =
        with(LocalDensity.current) {
            MaterialTheme.typography.labelMedium.lineHeight
                .toDp()
        }
    val monthHeaderHeight = labelHeight + 8.dp
    val minimumCellSize = 28.dp.coerceAtLeast(labelHeight + 4.dp)
    val monday = today.minus(today.dayOfWeek.ordinal, DateTimeUnit.DAY)
    val weekdayLabels = (0..6).map { formatter.weekday(monday.plus(it, DateTimeUnit.DAY)) }
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelMedium
    val weekdayWidth =
        with(LocalDensity.current) {
            weekdayLabels.maxOf { textMeasurer.measure(it, labelStyle).size.width }.toDp()
        }
    val monthLabels =
        remember(weeks) {
            weeks.mapIndexedNotNull { index, week ->
                val day =
                    week.filterNotNull().firstOrNull { it.date.day == 1 }
                        ?: week.filterNotNull().firstOrNull()?.takeIf { index == 0 }
                day?.let { index to it.date }
            }
        }
    LaunchedEffect(scroll.maxValue) { scroll.scrollTo(scroll.maxValue) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ChartDetail(formatter.date(selectedDate), selectedDay.count, formatter)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            // Round down once so individually rounded cells never overflow a fitted grid.
            val fittedCellSize = with(LocalDensity.current) { ((maxWidth - weekdayWidth - 8.dp).toPx() / weeks.size).toInt().toDp() }
            val cellSize = fittedCellSize.coerceIn(minimumCellSize, minimumCellSize.coerceAtLeast(32.dp))
            Row(
                Modifier.width((cellSize * weeks.size + weekdayWidth + 8.dp).coerceAtMost(maxWidth)).align(Alignment.Center),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column {
                    Spacer(Modifier.height(monthHeaderHeight))
                    weekdayLabels.forEach { label ->
                        Box(Modifier.height(cellSize), contentAlignment = Alignment.CenterStart) {
                            Text(
                                label,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Column(Modifier.weight(1f).carouselScroll(scroll).horizontalScroll(scroll)) {
                    Row {
                        monthLabels.forEachIndexed { index, (weekIndex, date) ->
                            val span = (monthLabels.getOrNull(index + 1)?.first ?: weeks.size) - weekIndex
                            Box(
                                Modifier.width(cellSize * span).height(monthHeaderHeight).padding(start = 4.dp),
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                Text(formatter.month(date), style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                    Row {
                        weeks.forEach { week ->
                            Column {
                                week.forEach { day ->
                                    if (day == null) {
                                        Spacer(Modifier.size(cellSize))
                                    } else {
                                        HeatmapCell(day, cellSize, day.date == selectedDate, buckets, formatter) { selectedDate = day.date }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        ChartScrollIndicator(scroll)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(Res.string.statistics_chart_less), style = MaterialTheme.typography.labelMedium)
                Text(stringResource(Res.string.statistics_chart_more), style = MaterialTheme.typography.labelMedium)
            }
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                buckets.forEachIndexed { index, range ->
                    val label =
                        if (range.first == range.last) {
                            formatter.number(range.first.toLong())
                        } else {
                            stringResource(
                                Res.string.statistics_chart_range,
                                formatter.number(range.first.toLong()),
                                formatter.number(range.last.toLong()),
                            )
                        }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.size(16.dp).clip(RoundedCornerShape(5.dp)).background(activityColor(index, buckets.lastIndex)))
                        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun HeatmapCell(
    day: StatisticsDay,
    cellSize: Dp,
    selected: Boolean,
    buckets: List<IntRange>,
    formatter: StatisticsFormatter,
    onSelect: () -> Unit,
) {
    val label = remember(day.date, formatter) { formatter.date(day.date) }
    val description = chartDescription(label, day.count, formatter)
    val corner = animateDpAsState(cellSize * if (selected) 0.4f else 0.12f, MaterialTheme.motionScheme.fastSpatialSpec())
    val inset = animateDpAsState(if (selected) 2.dp else 4.dp, MaterialTheme.motionScheme.fastSpatialSpec())
    val bucket = buckets.indexOfFirst { day.count in it }
    val color = activityColor(bucket, buckets.lastIndex)
    val outline = MaterialTheme.colorScheme.onSurface
    Canvas(Modifier.size(cellSize).chartSelection(description, selected, onSelect)) {
        val padding = inset.value.toPx()
        val bounds = Size((size.width - padding * 2).coerceAtLeast(0f), (size.height - padding * 2).coerceAtLeast(0f))
        val radius = CornerRadius(corner.value.toPx())
        drawRoundRect(color, Offset(padding, padding), bounds, radius)
        if (selected) drawRoundRect(outline, Offset(padding, padding), bounds, radius, style = Stroke(2.dp.toPx()))
    }
}

@Composable
private fun activityColor(
    bucket: Int,
    lastBucket: Int,
): Color =
    if (bucket == 0) {
        MaterialTheme.colorScheme.surfaceContainerHighest
    } else {
        lerp(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.primary, bucket.toFloat() / lastBucket)
    }

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun HourlyActivityChart(hourlyStats: Map<Int, Int>) {
    val formatter = remember(LocalLocale.current) { StatisticsFormatter() }
    val counts = remember(hourlyStats) { (0..23).map { hourlyStats[it]?.coerceAtLeast(0) ?: 0 } }
    val ticks = remember(counts) { listeningCountTicks(counts.max()) }
    var selectedHour by remember(hourlyStats) { mutableIntStateOf(counts.indices.maxBy { counts[it] }) }
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val axisColor = MaterialTheme.colorScheme.outline
    val chartHeight = 144.dp
    val tickLabelHeight =
        with(LocalDensity.current) {
            MaterialTheme.typography.labelSmall.lineHeight
                .toDp()
        }
    val scroll = rememberScrollState()
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ChartDetail(formatter.hour(selectedHour), counts[selectedHour], formatter)
        Text(
            stringResource(Res.string.statistics_chart_listens),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column {
                Column(
                    Modifier.height(chartHeight + tickLabelHeight),
                    verticalArrangement = Arrangement.SpaceBetween,
                    horizontalAlignment = Alignment.End,
                ) {
                    ticks.asReversed().forEach { tick ->
                        Text(
                            formatter.number(tick),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            BoxWithConstraints(Modifier.weight(1f)) {
                val plotWidth = maxWidth.coerceAtLeast((if (isDesktop) 24.dp else 48.dp) * 24)
                val slotWidthPx = with(LocalDensity.current) { (plotWidth / 24).toPx() }
                LaunchedEffect(hourlyStats, scroll.viewportSize, slotWidthPx) {
                    scroll.scrollTo((slotWidthPx * (selectedHour + 0.5f) - scroll.viewportSize / 2f).toInt().coerceAtLeast(0))
                }
                Column(Modifier.carouselScroll(scroll).horizontalScroll(scroll).width(plotWidth)) {
                    Box(Modifier.fillMaxWidth().padding(top = tickLabelHeight / 2).height(chartHeight)) {
                        Canvas(Modifier.fillMaxSize()) {
                            ticks.forEach { tick ->
                                val y = size.height * (1f - tick.toFloat() / ticks.last())
                                drawLine(if (tick == 0L) axisColor else gridColor, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
                            }
                            val axisX = if (isRtl) size.width else 0f
                            drawLine(axisColor, Offset(axisX, 0f), Offset(axisX, size.height), 1.dp.toPx())
                        }
                        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.Bottom) {
                            counts.forEachIndexed { hour, count ->
                                HourlyBar(
                                    hour,
                                    count,
                                    hour == selectedHour,
                                    ticks.last(),
                                    chartHeight,
                                    formatter,
                                    Modifier.weight(1f).fillMaxHeight(),
                                ) { selectedHour = hour }
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        (0..23).forEach { hour ->
                            Box(
                                Modifier.weight(1f).height(28.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (hour == selectedHour) {
                                    Box(
                                        Modifier
                                            .fillMaxSize()
                                            .padding(horizontal = 1.dp)
                                            .clip(MaterialTheme.shapes.small)
                                            .background(MaterialTheme.colorScheme.primaryContainer),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            formatter.number(hour.toLong()),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        )
                                    }
                                } else if (hour % 3 == 0 || hour == 23) {
                                    Text(formatter.number(hour.toLong()), style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            }
        }
        ChartScrollIndicator(scroll)
        Text(
            stringResource(Res.string.statistics_chart_hour),
            Modifier.align(Alignment.End),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ChartDetail(
    label: String,
    count: Int,
    formatter: StatisticsFormatter,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            label,
            Modifier.weight(1f),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            formatter.number(count.toLong()),
            style = MaterialTheme.typography.titleLargeEmphasized,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            stringResource(Res.string.statistics_chart_listens),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun HourlyBar(
    hour: Int,
    count: Int,
    selected: Boolean,
    maximum: Long,
    chartHeight: Dp,
    formatter: StatisticsFormatter,
    modifier: Modifier,
    onSelect: () -> Unit,
) {
    val label = remember(hour, formatter) { formatter.hour(hour) }
    val description = chartDescription(label, count, formatter)
    val fraction =
        animateFloatAsState(listeningBarFraction(count, maximum, 4.dp / chartHeight), MaterialTheme.motionScheme.defaultSpatialSpec())
    val color =
        animateColorAsState(
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
            MaterialTheme.motionScheme.fastEffectsSpec(),
        )
    val width = animateFloatAsState(if (selected) 0.7f else 0.55f, MaterialTheme.motionScheme.fastSpatialSpec())
    val emptyColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    Canvas(modifier.chartSelection(description, selected, onSelect)) {
        if (count > 0) {
            val barWidth = size.width * width.value.coerceIn(0f, 1f)
            val barHeight = size.height * fraction.value.coerceIn(0f, 1f)
            val x = (size.width - barWidth) / 2f
            val y = size.height - barHeight
            val radius = minOf(3.dp.toPx(), barHeight / 2f)
            drawRoundRect(color.value, Offset(x, y), Size(barWidth, barHeight), CornerRadius(radius))
            drawRect(color.value, Offset(x, y + barHeight / 2f), Size(barWidth, barHeight / 2f))
        } else {
            drawRoundRect(
                emptyColor,
                Offset((size.width - 10.dp.toPx()) / 2f, size.height - 2.dp.toPx()),
                Size(10.dp.toPx(), 2.dp.toPx()),
                CornerRadius(1.dp.toPx()),
            )
        }
    }
}

@Composable
private fun chartDescription(
    label: String,
    count: Int,
    formatter: StatisticsFormatter,
): String =
    stringResource(
        Res.string.statistics_chart_detail,
        label,
        stringResource(Res.string.statistics_chart_listens),
        formatter.number(count.toLong()),
    )

@Composable
private fun Modifier.chartSelection(
    description: String,
    isSelected: Boolean,
    onSelect: () -> Unit,
): Modifier {
    val source = remember { MutableInteractionSource() }
    val select by rememberUpdatedState(onSelect)
    LaunchedEffect(source) {
        source.interactions.collect { interaction ->
            if (interaction is HoverInteraction.Enter || interaction is PressInteraction.Press ||
                interaction is FocusInteraction.Focus
            ) {
                select()
            }
        }
    }
    return clickable(source, indication = null, role = Role.Button, onClick = onSelect)
        .semantics {
            contentDescription = description
            selected = isSelected
        }
}

@Composable
private fun ChartScrollIndicator(scroll: ScrollState) {
    if (scroll.maxValue == 0) return
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val thumb = MaterialTheme.colorScheme.outline
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Canvas(Modifier.fillMaxWidth().height(4.dp)) {
        val thumbWidth = size.width * scroll.viewportSize / (scroll.viewportSize + scroll.maxValue).toFloat()
        val progress = scroll.value.toFloat() / scroll.maxValue
        val x = (size.width - thumbWidth) * if (isRtl) 1f - progress else progress
        drawRoundRect(track, cornerRadius = CornerRadius(size.height / 2))
        drawRoundRect(thumb, topLeft = Offset(x, 0f), size = Size(thumbWidth, size.height), cornerRadius = CornerRadius(size.height / 2))
    }
}
