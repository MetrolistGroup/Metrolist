package com.metrolist.shared.model

enum class CollectionSort {
    NEWEST,
    OLDEST,
    TITLE,
    ARTIST,
    SIZE,
    LAST_USED,
    MOST_PLAYED,
}

data class CollectionMeta(
    val timestamp: Long,
    val fileSize: Long,
    val lastAccessed: Long? = null,
    val playCount: Int = 0,
)

fun List<SongItem>.presentCollection(
    query: String,
    sort: CollectionSort,
    direction: SortDirection = sort.defaultDirection,
    meta: (SongItem) -> CollectionMeta?,
): List<SongItem> {
    val normalized = query.trim()
    val filtered =
        if (normalized.isEmpty()) {
            this
        } else {
            filter { song ->
                song.matchesSongQuery(normalized)
            }
        }
    val sorted =
        when (sort) {
            CollectionSort.NEWEST -> {
                filtered.sortedByDescending { meta(it)?.timestamp ?: 0L }
            }

            CollectionSort.OLDEST -> {
                filtered.sortedBy { meta(it)?.timestamp ?: Long.MAX_VALUE }
            }

            CollectionSort.TITLE -> {
                filtered.sortedBy { it.title.lowercase() }
            }

            CollectionSort.ARTIST -> {
                filtered.sortedBy {
                    it.artists
                        .firstOrNull()
                        ?.name
                        ?.lowercase()
                        .orEmpty()
                }
            }

            CollectionSort.SIZE -> {
                filtered.sortedByDescending { meta(it)?.fileSize ?: 0L }
            }

            CollectionSort.MOST_PLAYED -> {
                filtered.sortedByDescending { meta(it)?.playCount ?: 0 }
            }

            CollectionSort.LAST_USED -> {
                filtered.sortedByDescending { meta(it)?.lastAccessed ?: 0L }
            }
        }
    return if (direction == sort.defaultDirection) sorted else sorted.asReversed()
}

val CollectionSort.defaultDirection: SortDirection
    get() =
        if (this in
            setOf(CollectionSort.TITLE, CollectionSort.ARTIST, CollectionSort.OLDEST)
        ) {
            SortDirection.ASCENDING
        } else {
            SortDirection.DESCENDING
        }
