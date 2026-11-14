package com.metrolist.shared.model

import com.metrolist.shared.utils.formatDuration
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.Month
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

data class HistoryEntryUi(
    val id: String,
    val songId: String,
    val title: String,
    val artists: String,
    val album: String?,
    val thumbnailUrl: String?,
    val playedAt: Long,
    val remoteFeedbackToken: String?,
    val playTimeMs: Long? = null,
    val remoteDateLabel: String? = null,
    val remoteOrder: Int = 0,
    val hasEstimatedDate: Boolean = false,
)

fun <T> toggleDisplayedHistorySelection(
    selected: Set<T>,
    displayed: Set<T>,
): Set<T> = if (displayed.isNotEmpty() && selected.containsAll(displayed)) emptySet() else selected + displayed

enum class HistoryDateBucket {
    TODAY,
    YESTERDAY,
    THIS_WEEK,
    LAST_WEEK,
    MONTH,
}

fun historyDateBucket(
    playedAt: Long,
    nowMs: Long,
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
): HistoryDateBucket {
    val date = Instant.fromEpochMilliseconds(playedAt).toLocalDateTime(timeZone).date
    val today = Instant.fromEpochMilliseconds(nowMs).toLocalDateTime(timeZone).date
    return when {
        date == today -> HistoryDateBucket.TODAY
        date == today.minus(DatePeriod(days = 1)) -> HistoryDateBucket.YESTERDAY
        date > today.minus(DatePeriod(days = 7)) -> HistoryDateBucket.THIS_WEEK
        date > today.minus(DatePeriod(days = 14)) -> HistoryDateBucket.LAST_WEEK
        else -> HistoryDateBucket.MONTH
    }
}

inline fun historyEntryTimeText(
    entry: HistoryEntryUi,
    sortMode: HistorySortMode,
    formatRelativeTime: () -> String,
): String? =
    if (sortMode == HistorySortMode.PLAY_TIME && entry.playTimeMs != null) {
        formatDuration(entry.playTimeMs)
    } else if (entry.remoteDateLabel != null || entry.hasEstimatedDate) {
        entry.remoteDateLabel
    } else {
        formatRelativeTime()
    }

fun HistoryEntryUi.toSongItem(): SongItem =
    SongItem(
        id = songId,
        title = title,
        artists = artists.split(", ").filter(String::isNotBlank).map { ArtistTiny(null, it) },
        album = album?.let { AlbumTiny(null, it) },
        thumbnail = thumbnailUrl,
    )

internal fun groupHistory(
    entries: List<HistoryEntryUi>,
    nowMs: Long,
    bucketLabels: Map<HistoryDateBucket, String>,
    formatMonth: (Long) -> String,
    timeZone: TimeZone,
): Map<String, List<HistoryEntryUi>> {
    val groups = LinkedHashMap<String, MutableList<HistoryEntryUi>>()
    val months = mutableMapOf<Pair<Int, Month>, String>()
    entries.forEach { entry ->
        val bucket = historyDateBucket(entry.playedAt, nowMs, timeZone)
        val title =
            entry.remoteDateLabel
                ?: if (bucket == HistoryDateBucket.MONTH) {
                    val date = Instant.fromEpochMilliseconds(entry.playedAt).toLocalDateTime(timeZone).date
                    months.getOrPut(date.year to date.month) { formatMonth(entry.playedAt) }
                } else {
                    bucketLabels.getValue(bucket)
                }
        groups.getOrPut(title) { mutableListOf() }.add(entry)
    }
    return groups
}
