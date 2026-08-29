/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.constants

import com.metrolist.music.ui.screens.OptionStats
import java.time.LocalDateTime
import java.time.ZoneOffset

enum class StatPeriod {
    WEEK_1,
    MONTH_1,
    MONTH_3,
    MONTH_6,
    YEAR_1,
    ALL,
    ;

    fun toLocalDateTime(): LocalDateTime =
        when (this) {
            WEEK_1 ->
                LocalDateTime
                    .now()
                    .minusWeeks(1)

            MONTH_1 ->
                LocalDateTime
                    .now()
                    .minusMonths(1)

            MONTH_3 ->
                LocalDateTime
                    .now()
                    .minusMonths(3)

            MONTH_6 ->
                LocalDateTime
                    .now()
                    .minusMonths(6)

            YEAR_1 ->
                LocalDateTime
                    .now()
                    .minusMonths(12)

            ALL -> LocalDateTime.of(1970, 1, 1, 0, 0)
        }
}

fun statToPeriod(
    selection: OptionStats,
    test: Int,
): LocalDateTime =
    when (selection) {
        OptionStats.WEEKS -> {
            LocalDateTime
                .now()
                .minusWeeks(test.toLong())
                .minusDays(1)
        }

        OptionStats.MONTHS -> {
            LocalDateTime
                .now()
                .withDayOfMonth(1)
                .minusMonths(test.toLong())
        }

        OptionStats.YEARS -> {
            LocalDateTime
                .now()
                .withDayOfMonth(1)
                .withMonth(1)
                .minusYears(test.toLong())
        }

        OptionStats.CONTINUOUS -> {
            val index = if (test >= StatPeriod.entries.size) 0 else test
            StatPeriod.entries[index].toLocalDateTime()
        }
    }
