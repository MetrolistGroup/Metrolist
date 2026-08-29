/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */
package com.metrolist.music.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.metrolist.music.R
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Button
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi

fun decodeDayTimes(raw: String): MutableMap<Int, Pair<String, String>> {
    if (raw.isBlank()) return mutableMapOf()
    return raw
        .split(";")
        .mapNotNull { entry ->
            val parts = entry.split("=")
            if (parts.size != 2) return@mapNotNull null
            val dayIndex = parts[0].toIntOrNull() ?: return@mapNotNull null
            val times = parts[1].split("-")
            if (times.size != 2) return@mapNotNull null
            dayIndex to (times[0] to times[1])
        }.toMap()
        .toMutableMap()
}

fun encodeDayTimes(map: Map<Int, Pair<String, String>>): String =
    map.entries.joinToString(";") { (day, times) -> "$day=${times.first}-${times.second}" }

private const val DEFAULT_START = "22:00"
private const val DEFAULT_END = "06:00"

private val WEEKDAY_INDICES = 0..4 // Monday to Friday
private val WEEKEND_INDICES = 5..6 // Saturday and Sunday

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SleepTimerDialog(
    isVisible: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (
        repeat: String,
        startTime: String,
        endTime: String,
        customDays: List<Int>?,
        dayTimes: Map<Int, Pair<String, String>>,
    ) -> Unit,
    initialRepeat: String = "daily",
    initialStartTime: String = DEFAULT_START,
    initialEndTime: String = DEFAULT_END,
    initialCustomDays: List<Int> = listOf(0, 1, 2, 3, 4),
    initialDayTimes: Map<Int, Pair<String, String>> = emptyMap(),
) {
    if (!isVisible) return

    var selectedRepeat by remember {
        mutableStateOf(
            when (initialRepeat) {
                "weekdays", "weekends", "weekdays_weekends" -> "weekdays_weekends"
                else -> initialRepeat
            },
        )
    }

    var weekdaysEnabled by remember {
        // Restore from the previously saved repeat value
        mutableStateOf(initialRepeat in listOf("weekdays", "weekdays_weekends"))
    }
    var weekendsEnabled by remember {
        mutableStateOf(initialRepeat in listOf("weekends", "weekdays_weekends"))
    }

    var weekdaysStart by remember {
        mutableStateOf(initialDayTimes[WEEKDAY_INDICES.first]?.first ?: initialStartTime)
    }
    var weekdaysEnd by remember {
        mutableStateOf(initialDayTimes[WEEKDAY_INDICES.first]?.second ?: initialEndTime)
    }
    var weekendsStart by remember {
        mutableStateOf(initialDayTimes[WEEKEND_INDICES.first]?.first ?: initialStartTime)
    }
    var weekendsEnd by remember {
        mutableStateOf(initialDayTimes[WEEKEND_INDICES.first]?.second ?: initialEndTime)
    }

    var selectedStartTime by remember { mutableStateOf(initialStartTime) }
    var selectedEndTime by remember { mutableStateOf(initialEndTime) }
    var selectedDays by remember { mutableStateOf(initialCustomDays) }
    var dayTimesMap by remember { mutableStateOf(initialDayTimes) }

    var activeTimePicker by remember { mutableStateOf<String?>(null) }

    activeTimePicker?.let { pickerKey ->
        val isStart = pickerKey.contains("start")
        val title =
            if (isStart) {
                stringResource(R.string.sleep_timer_start_time)
            } else {
                stringResource(R.string.sleep_timer_end_time)
            }

        val currentTime =
            when (pickerKey) {
                "global_start" -> {
                    selectedStartTime
                }

                "global_end" -> {
                    selectedEndTime
                }

                "weekdays_start" -> {
                    weekdaysStart
                }

                "weekdays_end" -> {
                    weekdaysEnd
                }

                "weekends_start" -> {
                    weekendsStart
                }

                "weekends_end" -> {
                    weekendsEnd
                }

                else -> {
                    val dayIdx = pickerKey.substringAfterLast("_").toIntOrNull() ?: 0
                    if (isStart) {
                        dayTimesMap[dayIdx]?.first ?: DEFAULT_START
                    } else {
                        dayTimesMap[dayIdx]?.second ?: DEFAULT_END
                    }
                }
            }

        SleepTimerTimePickerDialog(
            title = title,
            initialTime = currentTime,
            onDismiss = { activeTimePicker = null },
            onConfirm = { time ->
                when (pickerKey) {
                    "global_start" -> {
                        selectedStartTime = time
                    }

                    "global_end" -> {
                        selectedEndTime = time
                    }

                    "weekdays_start" -> {
                        weekdaysStart = time
                    }

                    "weekdays_end" -> {
                        weekdaysEnd = time
                    }

                    "weekends_start" -> {
                        weekendsStart = time
                    }

                    "weekends_end" -> {
                        weekendsEnd = time
                    }

                    else -> {
                        val dayIdx = pickerKey.substringAfterLast("_").toIntOrNull() ?: 0
                        val existing = dayTimesMap[dayIdx] ?: (DEFAULT_START to DEFAULT_END)
                        dayTimesMap =
                            (
                                dayTimesMap + (
                                    dayIdx to
                                        if (isStart) {
                                            existing.copy(first = time)
                                        } else {
                                            existing.copy(second = time)
                                        }
                                )
                            ).toMutableMap()
                    }
                }
                activeTimePicker = null
            },
        )
    }

    val dayLabelRes =
        listOf(
            R.string.sleep_timer_monday,
            R.string.sleep_timer_tuesday,
            R.string.sleep_timer_wednesday,
            R.string.sleep_timer_thursday,
            R.string.sleep_timer_friday,
            R.string.sleep_timer_saturday,
            R.string.sleep_timer_sunday,
        )

    ListDialog(onDismiss = onDismiss) {
        item {
            Text(
                text = stringResource(R.string.sleep_timer),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }

        item {
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                SegmentedButton(
                    selected = selectedRepeat == "daily",
                    onClick = { selectedRepeat = "daily" },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 3),
                    label = { Text(stringResource(R.string.sleep_timer_daily)) },
                )
                SegmentedButton(
                    selected = selectedRepeat == "weekdays_weekends",
                    onClick = {
                        selectedRepeat = "weekdays_weekends"
                        if (!weekdaysEnabled && !weekendsEnabled) weekdaysEnabled = true
                    },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 3),
                    label = { Text(stringResource(R.string.sleep_timer_weekdays_weekends)) },
                )
                SegmentedButton(
                    selected = selectedRepeat == "custom",
                    onClick = { selectedRepeat = "custom" },
                    shape = SegmentedButtonDefaults.itemShape(index = 2, count = 3),
                    label = { Text(stringResource(R.string.sleep_timer_custom)) },
                )
            }
        }

        item {
            AnimatedVisibility(
                visible = selectedRepeat == "daily",
                enter = expandVertically(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMedium,
                    ),
                ) + fadeIn(),
                exit = shrinkVertically(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMedium,
                    ),
                ) + fadeOut(),
            ) {
                ElevatedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    TimeRangeRow(
                        startTime = selectedStartTime,
                        endTime = selectedEndTime,
                        onStartClick = { activeTimePicker = "global_start" },
                        onEndClick = { activeTimePicker = "global_end" },
                    )
                }
            }
        }

        item {
            AnimatedVisibility(
                visible = selectedRepeat == "weekdays_weekends",
                enter = expandVertically(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioLowBouncy,
                        stiffness = Spring.StiffnessMedium,
                    ),
                ) + fadeIn(),
                exit = shrinkVertically(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioLowBouncy,
                        stiffness = Spring.StiffnessMedium,
                    ),
                ) + fadeOut(),
            ) {
                ElevatedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { weekdaysEnabled = !weekdaysEnabled }
                                .padding(vertical = 8.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.sleep_timer_weekdays),
                                modifier = Modifier.weight(1f),
                            )
                            Switch(
                                checked = weekdaysEnabled,
                                onCheckedChange = { weekdaysEnabled = it },
                                thumbContent = {
                                    Icon(
                                        painter = painterResource(
                                            if (weekdaysEnabled) R.drawable.check else R.drawable.close,
                                        ),
                                        contentDescription = null,
                                        modifier = Modifier.size(SwitchDefaults.IconSize),
                                    )
                                },
                                modifier = Modifier.scale(0.85f),
                            )
                        }
                        AnimatedVisibility(
                            visible = weekdaysEnabled,
                            enter = expandVertically(
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioMediumBouncy,
                                    stiffness = Spring.StiffnessMedium,
                                ),
                            ) + fadeIn(),
                            exit = shrinkVertically(
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioMediumBouncy,
                                    stiffness = Spring.StiffnessMedium,
                                ),
                            ) + fadeOut(),
                        ) {
                            TimeRangeRow(
                                startTime = weekdaysStart,
                                endTime = weekdaysEnd,
                                onStartClick = { activeTimePicker = "weekdays_start" },
                                onEndClick = { activeTimePicker = "weekdays_end" },
                                modifier = Modifier.padding(bottom = 4.dp),
                            )
                        }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { weekendsEnabled = !weekendsEnabled }
                                .padding(vertical = 8.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.sleep_timer_weekends),
                                modifier = Modifier.weight(1f),
                            )
                            Switch(
                                checked = weekendsEnabled,
                                onCheckedChange = { weekendsEnabled = it },
                                thumbContent = {
                                    Icon(
                                        painter = painterResource(
                                            if (weekendsEnabled) R.drawable.check else R.drawable.close,
                                        ),
                                        contentDescription = null,
                                        modifier = Modifier.size(SwitchDefaults.IconSize),
                                    )
                                },
                                modifier = Modifier.scale(0.85f),
                            )
                        }
                        AnimatedVisibility(
                            visible = weekendsEnabled,
                            enter = expandVertically(
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioMediumBouncy,
                                    stiffness = Spring.StiffnessMedium,
                                ),
                            ) + fadeIn(),
                            exit = shrinkVertically(
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioMediumBouncy,
                                    stiffness = Spring.StiffnessMedium,
                                ),
                            ) + fadeOut(),
                        ) {
                            TimeRangeRow(
                                startTime = weekendsStart,
                                endTime = weekendsEnd,
                                onStartClick = { activeTimePicker = "weekends_start" },
                                onEndClick = { activeTimePicker = "weekends_end" },
                                modifier = Modifier.padding(bottom = 4.dp),
                            )
                        }
                    }
                }
            }
        }


        item {
            AnimatedVisibility(
                visible = selectedRepeat == "custom",
                enter = expandVertically(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioLowBouncy,
                        stiffness = Spring.StiffnessMedium,
                    ),
                ) + fadeIn(),
                exit = shrinkVertically(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioLowBouncy,
                        stiffness = Spring.StiffnessMedium,
                    ),
                ) + fadeOut(),
            ) {
                ElevatedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        dayLabelRes.indices.forEach { index ->
                            val isDaySelected = index in selectedDays
                            val dayTimes = dayTimesMap[index] ?: (DEFAULT_START to DEFAULT_END)

                            if (index > 0) {
                                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                            }

                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 4.dp),
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            selectedDays =
                                                if (index in selectedDays) selectedDays - index else selectedDays + index
                                        }.padding(vertical = 6.dp),
                                ) {
                                    Text(
                                        text = stringResource(dayLabelRes[index]),
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    Switch(
                                        checked = isDaySelected,
                                        onCheckedChange = {
                                            selectedDays =
                                                if (index in selectedDays) selectedDays - index else selectedDays + index
                                        },
                                        thumbContent = {
                                            Icon(
                                                painter = painterResource(
                                                    if (isDaySelected) R.drawable.check else R.drawable.close,
                                                ),
                                                contentDescription = null,
                                                modifier = Modifier.size(SwitchDefaults.IconSize),
                                            )
                                        },
                                        modifier = Modifier.scale(0.85f),
                                    )
                                }
                                AnimatedVisibility(
                                    visible = isDaySelected,
                                    enter = expandVertically(
                                        animationSpec = spring(
                                            dampingRatio = Spring.DampingRatioMediumBouncy,
                                            stiffness = Spring.StiffnessMedium,
                                        ),
                                    ) + fadeIn(),
                                    exit = shrinkVertically(
                                        animationSpec = spring(
                                            dampingRatio = Spring.DampingRatioMediumBouncy,
                                            stiffness = Spring.StiffnessMedium,
                                        ),
                                    ) + fadeOut(),
                                ) {
                                    TimeRangeRow(
                                        startTime = dayTimes.first,
                                        endTime = dayTimes.second,
                                        onStartClick = { activeTimePicker = "day_start_$index" },
                                        onEndClick = { activeTimePicker = "day_end_$index" },
                                        modifier = Modifier.padding(bottom = 4.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        item {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    onClick = onDismiss,
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(stringResource(android.R.string.cancel))
                }
                androidx.compose.material3.Button(
                    shapes = ButtonDefaults.shapes(),
                    onClick = {
                            val (finalRepeat, finalDayTimes) =
                                when (selectedRepeat) {
                                    "weekdays_weekends" -> {
                                        val repeat =
                                            when {
                                                weekdaysEnabled && weekendsEnabled -> "weekdays_weekends"
                                                weekdaysEnabled -> "weekdays"
                                                weekendsEnabled -> "weekends"
                                                else -> "daily"
                                            }
                                        val times =
                                            buildMap {
                                                if (weekdaysEnabled) {
                                                    for (d in WEEKDAY_INDICES) put(d, weekdaysStart to weekdaysEnd)
                                                }
                                                if (weekendsEnabled) {
                                                    for (d in WEEKEND_INDICES) put(d, weekendsStart to weekendsEnd)
                                                }
                                            }
                                        repeat to times
                                    }
                                    else -> {
                                        selectedRepeat to dayTimesMap.toMap()
                                    }
                                }
                            onConfirm(finalRepeat, selectedStartTime, selectedEndTime, selectedDays, finalDayTimes)
                            onDismiss()
                        },
                    ) {
                        Text(stringResource(android.R.string.ok))
                    }
            }
        }
    }
}

@Composable
private fun TimeRangeRow(
    startTime: String,
    endTime: String,
    onStartClick: () -> Unit,
    onEndClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilledTonalButton(onClick = onStartClick, modifier = Modifier.weight(1f)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.sleep_timer_start_time), style = MaterialTheme.typography.labelSmall)
                Text(startTime, style = MaterialTheme.typography.bodyLarge)
            }
        }
        FilledTonalButton(onClick = onEndClick, modifier = Modifier.weight(1f)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.sleep_timer_end_time), style = MaterialTheme.typography.labelSmall)
                Text(endTime, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepTimerTimePickerDialog(
    title: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    initialTime: String = DEFAULT_START,
) {
    val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    val initialLocalTime =
        try {
            LocalTime.parse(initialTime, timeFormatter)
        } catch (e: Exception) {
            LocalTime.of(9, 0)
        }
    val timePickerState =
        rememberTimePickerState(
            initialHour = initialLocalTime.hour,
            initialMinute = initialLocalTime.minute,
            is24Hour = true,
        )
    DefaultDialog(
        title = { Text(title) },
        onDismiss = onDismiss,
        buttons = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
            Button(onClick = {
                val hour = timePickerState.hour.toString().padStart(2, '0')
                val minute = timePickerState.minute.toString().padStart(2, '0')
                onConfirm("$hour:$minute")
            }) { Text(stringResource(android.R.string.ok)) }
        },
    ) { TimePicker(state = timePickerState) }
}
