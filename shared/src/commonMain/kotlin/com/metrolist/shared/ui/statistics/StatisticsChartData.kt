package com.metrolist.shared.ui.statistics

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import kotlinx.datetime.plus

internal fun listeningCountTicks(maxCount: Int): List<Long> {
    val maximum = maxCount.coerceAtLeast(1).toLong()
    val roughStep = (maximum + 3) / 4
    var magnitude = 1L
    while (magnitude * 10 <= roughStep) magnitude *= 10
    val step = listOf(1L, 2L, 5L, 10L).first { it * magnitude >= roughStep } * magnitude
    val intervals = (maximum + step - 1) / step
    return (0..intervals).map { it * step }
}

internal fun listeningBarFraction(
    count: Int,
    ceiling: Long,
    minimumFraction: Float,
): Float = if (count <= 0) 0f else (count.toFloat() / ceiling.coerceAtLeast(1)).coerceIn(minimumFraction.coerceIn(0f, 1f), 1f)

internal fun listeningCountBuckets(maxCount: Int): List<IntRange> {
    val maximum = maxCount.coerceAtLeast(0)
    val bucketCount = maximum.coerceAtMost(4)
    return listOf(0..0) +
        (0 until bucketCount).map { index ->
            val start = (maximum.toLong() * index / bucketCount + 1).toInt()
            val end = (maximum.toLong() * (index + 1) / bucketCount).toInt()
            start..end
        }
}

internal data class StatisticsDay(
    val date: LocalDate,
    val count: Int,
)

internal fun listeningActivityWeeks(
    today: LocalDate,
    counts: Map<String, Int>,
    firstDate: LocalDate? = null,
    showFullRange: Boolean = false,
): List<List<StatisticsDay?>> {
    val start =
        if (showFullRange && firstDate != null) {
            firstDate.plus(-firstDate.dayOfWeek.ordinal, DateTimeUnit.DAY)
        } else {
            today.plus(-(today.dayOfWeek.ordinal + 14 * 7), DateTimeUnit.DAY)
        }
    val dayCount = start.daysUntil(today) + 7 - today.dayOfWeek.ordinal
    return (0 until dayCount)
        .map { offset ->
            val date = start.plus(offset, DateTimeUnit.DAY)
            if (date <= today &&
                (firstDate == null || date >= firstDate)
            ) {
                StatisticsDay(date, counts[date.toString()]?.coerceAtLeast(0) ?: 0)
            } else {
                null
            }
        }.chunked(7)
        .dropWhile { week -> week.all { it == null } }
}
