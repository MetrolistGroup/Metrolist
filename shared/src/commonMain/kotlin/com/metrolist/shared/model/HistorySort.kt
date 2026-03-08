package com.metrolist.shared.model

enum class HistorySortMode {
    NEWEST,
    OLDEST,
    TITLE,
    ARTIST,
    PLAY_TIME,
}

fun <T> List<T>.filterAndSortHistory(
    query: String,
    sortMode: HistorySortMode,
    extractTitle: (T) -> String,
    extractArtists: (T) -> String,
    extractAlbum: (T) -> String?,
    extractPlayedAt: (T) -> Long,
    extractPlayTimeMs: (T) -> Long?,
    checkCancelled: () -> Unit = {},
    direction: SortDirection = sortMode.defaultDirection,
    extractRemoteOrder: (T) -> Int = { 0 },
): List<T> {
    val normalized = query.trim()
    val filtered =
        if (normalized.isEmpty()) {
            this
        } else {
            filter { entry ->
                checkCancelled()
                extractTitle(entry).contains(normalized, ignoreCase = true) ||
                    extractArtists(entry).contains(normalized, ignoreCase = true) ||
                    extractAlbum(entry)?.contains(normalized, ignoreCase = true) == true
            }
        }
    val sorted =
        when (sortMode) {
            HistorySortMode.NEWEST -> {
                filtered.sortedWith(
                    compareByDescending<T> {
                        checkCancelled()
                        extractPlayedAt(it)
                    }.thenByDescending(extractRemoteOrder),
                )
            }

            HistorySortMode.OLDEST -> {
                filtered.sortedWith(
                    compareBy<T> {
                        checkCancelled()
                        extractPlayedAt(it)
                    }.thenBy(extractRemoteOrder),
                )
            }

            HistorySortMode.TITLE -> {
                filtered.sortedBy {
                    checkCancelled()
                    extractTitle(it).lowercase()
                }
            }

            HistorySortMode.ARTIST -> {
                filtered.sortedBy {
                    checkCancelled()
                    extractArtists(it).lowercase()
                }
            }

            HistorySortMode.PLAY_TIME -> {
                filtered.sortedByDescending {
                    checkCancelled()
                    extractPlayTimeMs(it) ?: 0L
                }
            }
        }
    return if (direction == sortMode.defaultDirection) sorted else sorted.asReversed()
}

val HistorySortMode.defaultDirection: SortDirection
    get() =
        if (this in
            setOf(HistorySortMode.TITLE, HistorySortMode.ARTIST, HistorySortMode.OLDEST)
        ) {
            SortDirection.ASCENDING
        } else {
            SortDirection.DESCENDING
        }
