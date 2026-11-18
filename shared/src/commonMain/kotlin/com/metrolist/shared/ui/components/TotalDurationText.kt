/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.ui.components

import androidx.compose.runtime.Composable
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.days
import com.metrolist.shared.generated.resources.duration_three_units
import com.metrolist.shared.generated.resources.duration_two_units
import com.metrolist.shared.generated.resources.hours
import com.metrolist.shared.generated.resources.less_than_one_minute
import com.metrolist.shared.generated.resources.minutes
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** Formats a playlist or album length as "1 day, 2 hours, 45 minutes", omitting zero units. */
@Composable
internal fun totalDurationText(seconds: Long): String {
    val totalMinutes = seconds / 60
    val days = (totalMinutes / (24 * 60)).toInt()
    val hours = (totalMinutes / 60 % 24).toInt()
    val minutes = (totalMinutes % 60).toInt()
    val units =
        listOfNotNull(
            days.takeIf { it > 0 }?.let { pluralStringResource(Res.plurals.days, it, it) },
            hours.takeIf { it > 0 }?.let { pluralStringResource(Res.plurals.hours, it, it) },
            minutes.takeIf { it > 0 }?.let { pluralStringResource(Res.plurals.minutes, it, it) },
        )
    return when (units.size) {
        0 -> stringResource(Res.string.less_than_one_minute)
        1 -> units[0]
        2 -> stringResource(Res.string.duration_two_units, units[0], units[1])
        else -> stringResource(Res.string.duration_three_units, units[0], units[1], units[2])
    }
}
