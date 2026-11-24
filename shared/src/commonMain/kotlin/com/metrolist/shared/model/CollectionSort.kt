package com.metrolist.shared.model

import com.metrolist.shared.settings.ArtistNameAliases

enum class CollectionSort {
    NEWEST,
    OLDEST,
    TITLE,
    ARTIST,
    SIZE,
    LAST_USED,
    MOST_PLAYED,
    MANUAL,
}

data class CollectionMeta(
    val timestamp: Long,
    val fileSize: Long,
    val lastAccessed: Long? = null,
    val playCount: Int = 0,
)

fun CollectionSort.allowsReordering(): Boolean = this == CollectionSort.MANUAL

fun CollectionSort.isReordering(
    locked: Boolean,
    editable: Boolean,
): Boolean = !locked && editable && allowsReordering()

fun reconcileCachedCustomOrder(
    currentIds: List<String>,
    savedOrder: List<String>,
): List<String> {
    val distinctCurrent = currentIds.distinct()
    if (savedOrder.isEmpty()) return distinctCurrent
    val currentSet = distinctCurrent.toHashSet()
    val retained =
        savedOrder
            .asSequence()
            .filter { it in currentSet }
            .distinct()
            .toList()
    val retainedSet = retained.toHashSet()
    val newIds = distinctCurrent.filterNot { it in retainedSet }
    return newIds + retained
}

fun moveCachedSong(
    order: List<String>,
    fromSongId: String,
    toSongId: String,
    selectedSongIds: Set<String> = emptySet(),
): List<String> {
    val fromIndex = order.indexOf(fromSongId)
    val toIndex = order.indexOf(toSongId)
    if (fromIndex < 0 || toIndex < 0 || fromIndex == toIndex) return order
    return order.moveSelection(
        fromIndex,
        toIndex,
        order.indices.filterTo(mutableSetOf()) { order[it] in selectedSongIds },
    )
}

fun List<SongItem>.presentCollection(
    query: String,
    sort: CollectionSort,
    direction: SortDirection = sort.defaultDirection,
    customOrder: List<String> = emptyList(),
    artistAliases: Map<String, String> = if (sort == CollectionSort.ARTIST) ArtistNameAliases.aliases else emptyMap(),
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
    if (sort == CollectionSort.MANUAL) {
        val positions =
            reconcileCachedCustomOrder(filtered.map { it.id }, customOrder)
                .withIndex()
                .associate { it.value to it.index }
        return filtered.sortedBy { positions[it.id] ?: Int.MAX_VALUE }
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
                filtered.sortedBy { song ->
                    song.artists
                        .firstOrNull()
                        ?.let { ArtistNameAliases.resolve(artistAliases, it.id, it.name).lowercase() }
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

            CollectionSort.MANUAL -> {
                filtered
            }
        }
    return if (direction == sort.defaultDirection) sorted else sorted.asReversed()
}

val CollectionSort.defaultDirection: SortDirection
    get() =
        if (this in
            setOf(CollectionSort.TITLE, CollectionSort.ARTIST, CollectionSort.OLDEST, CollectionSort.MANUAL)
        ) {
            SortDirection.ASCENDING
        } else {
            SortDirection.DESCENDING
        }

/** Presents a collection using [sort]/[direction] while merging external [playCounts] into [metadata]. */
fun List<SongItem>.presentCollectionWithPlayCounts(
    sort: CollectionSort,
    direction: SortDirection = sort.defaultDirection,
    customOrder: List<String> = emptyList(),
    metadata: Map<String, CollectionMeta> = emptyMap(),
    playCounts: Map<String, Int> = emptyMap(),
    artistAliases: Map<String, String> = if (sort == CollectionSort.ARTIST) ArtistNameAliases.aliases else emptyMap(),
): List<SongItem> =
    presentCollection(
        query = "",
        sort = sort,
        direction = direction,
        customOrder = if (sort == CollectionSort.MANUAL) customOrder else emptyList(),
        artistAliases = artistAliases,
    ) { song ->
        val meta = metadata[song.id]
        if (sort == CollectionSort.MOST_PLAYED) {
            meta?.let { it.copy(playCount = playCounts[song.id] ?: it.playCount) }
                ?: playCounts[song.id]?.let { CollectionMeta(0L, 0L, playCount = it) }
        } else {
            meta
        }
    }
