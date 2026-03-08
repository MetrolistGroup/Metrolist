package com.metrolist.shared.model

import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

enum class TopPlaylistPeriod(
    private val windowMillis: Long?,
) {
    ALL_TIME(null),
    PAST_24_HOURS(24.hours.inWholeMilliseconds),
    PAST_WEEK(7.days.inWholeMilliseconds),
    PAST_MONTH(30.days.inWholeMilliseconds),
    PAST_YEAR(365.days.inWholeMilliseconds),
    ;

    fun fromTimestamp(nowMillis: Long): Long = windowMillis?.let { (nowMillis - it).coerceAtLeast(0L) } ?: 0L
}

fun List<SongItem>.sortedForTopPlaylist(direction: SortDirection): List<SongItem> =
    if (direction == SortDirection.DESCENDING) this else asReversed()
