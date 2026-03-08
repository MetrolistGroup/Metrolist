@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.shared.ui.statistics

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.DateRangePickerDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.unit.dp
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.apply
import com.metrolist.shared.generated.resources.cancel
import com.metrolist.shared.generated.resources.statistics_calendar_hint
import com.metrolist.shared.generated.resources.statistics_calendar_month
import com.metrolist.shared.generated.resources.statistics_calendar_year
import com.metrolist.shared.generated.resources.statistics_custom_dates
import com.metrolist.shared.generated.resources.statistics_date_range
import com.metrolist.shared.generated.resources.statistics_friday_week
import com.metrolist.shared.generated.resources.statistics_monday_week
import com.metrolist.shared.ui.components.ChipShelf
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.todayIn
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock
import kotlin.time.Instant

@Composable
internal fun statisticsDateRangeLabel(range: StatisticsDateRange): String {
    val formatter = remember(LocalLocale.current) { StatisticsFormatter() }
    return stringResource(Res.string.statistics_date_range, formatter.date(range.start), formatter.date(range.end))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StatisticsDateRangePicker(
    selected: StatisticsDateRange?,
    onSelected: (StatisticsDateRange) -> Unit,
    onDismiss: () -> Unit,
) {
    var calendarPeriod by remember { mutableStateOf<StatisticsCalendarPeriod?>(null) }
    val today = Clock.System.todayIn(TimeZone.currentSystemDefault())
    val start = selected?.start ?: today
    val end = selected?.end ?: today
    val years = minOf(DatePickerDefaults.YearRange.first, start.year)..maxOf(DatePickerDefaults.YearRange.last, end.year)
    // Material date pickers encode calendar dates at UTC midnight, not at local midnight.
    val rangeState =
        rememberDateRangePickerState(
            initialSelectedStartDateMillis = start.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds(),
            initialSelectedEndDateMillis = end.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds(),
            yearRange = years,
        )
    val dayState =
        rememberDatePickerState(
            initialSelectedDateMillis = start.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds(),
            yearRange = years,
        )
    val selection =
        if (calendarPeriod == null) {
            val first = rangeState.selectedStartDateMillis?.pickerDate()
            val last = rangeState.selectedEndDateMillis?.pickerDate()
            if (first != null && last != null) StatisticsDateRange(first, last) else null
        } else {
            dayState.selectedDateMillis?.pickerDate()?.let { calendarPeriod?.containing(it) }
        }
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(enabled = selection != null, onClick = {
                selection?.let(onSelected)
                onDismiss()
            }, shapes = ButtonDefaults.shapes()) { Text(stringResource(Res.string.apply)) }
        },
        dismissButton = {
            TextButton(onClick = { onDismiss() }, shapes = ButtonDefaults.shapes()) { Text(stringResource(Res.string.cancel)) }
        },
    ) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            ChipShelf(
                options = StatisticsCalendarPeriod.entries,
                selectedOption = calendarPeriod,
                onSelectionChange = { calendarPeriod = it },
                allLabel = stringResource(Res.string.statistics_custom_dates),
                selectionRequired = true,
                label = {
                    stringResource(
                        when (it) {
                            StatisticsCalendarPeriod.MONDAY_WEEK -> Res.string.statistics_monday_week
                            StatisticsCalendarPeriod.FRIDAY_WEEK -> Res.string.statistics_friday_week
                            StatisticsCalendarPeriod.MONTH -> Res.string.statistics_calendar_month
                            StatisticsCalendarPeriod.YEAR -> Res.string.statistics_calendar_year
                        },
                    )
                },
            )
            if (calendarPeriod == null) {
                val dateFormatter = remember { DatePickerDefaults.dateFormatter() }
                DateRangePicker(
                    state = rangeState,
                    modifier = Modifier.height(400.dp),
                    dateFormatter = dateFormatter,
                    title = {
                        DateRangePickerDefaults.DateRangePickerTitle(
                            displayMode = rangeState.displayMode,
                            modifier = Modifier.padding(start = 24.dp, end = 12.dp),
                        )
                    },
                    headline = {
                        ProvideTextStyle(MaterialTheme.typography.titleMedium) {
                            DateRangePickerDefaults.DateRangePickerHeadline(
                                selectedStartDateMillis = rangeState.selectedStartDateMillis,
                                selectedEndDateMillis = rangeState.selectedEndDateMillis,
                                displayMode = rangeState.displayMode,
                                dateFormatter = dateFormatter,
                                modifier = Modifier.padding(start = 24.dp, end = 12.dp, bottom = 12.dp),
                            )
                        }
                    },
                )
            } else {
                Text(stringResource(Res.string.statistics_calendar_hint), modifier = Modifier.padding(horizontal = 24.dp))
                DatePicker(state = dayState)
            }
            selection?.let { Text(statisticsDateRangeLabel(it), modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) }
        }
    }
}

private fun Long.pickerDate(): LocalDate = Instant.fromEpochMilliseconds(this).toLocalDateTime(TimeZone.UTC).date
