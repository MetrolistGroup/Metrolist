package com.metrolist.shared.ui.statistics

import kotlinx.datetime.LocalDate

internal expect class StatisticsFormatter(
    languageTag: String? = null,
) {
    fun number(value: Long): String

    fun date(value: LocalDate): String

    fun weekday(value: LocalDate): String

    fun month(value: LocalDate): String

    fun hour(value: Int): String
}
