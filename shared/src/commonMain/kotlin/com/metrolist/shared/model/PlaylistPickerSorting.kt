package com.metrolist.shared.model

import com.metrolist.shared.settings.HiddenLibraryItemKey
import com.metrolist.shared.settings.HiddenLibraryItemType
import com.metrolist.shared.settings.LibraryContentSort
import com.metrolist.shared.settings.LibrarySorting
import com.metrolist.shared.settings.normalizedLibraryOrder

enum class PlaylistPickerSort {
    // The first four ordinals preserve existing picker preferences.
    CREATE_DATE,
    NAME,
    SONG_COUNT,
    LAST_UPDATED,
    LIBRARY,
    MANUAL,
}

fun <T, D : Comparable<D>> List<T>.sortedForPlaylistPicker(
    sort: PlaylistPickerSort,
    direction: SortDirection,
    name: (T) -> String,
    addedAt: (T) -> D?,
    lastUpdated: (T) -> D?,
    songCount: (T) -> Int?,
    id: (T) -> String,
    librarySorting: LibrarySorting,
    manualOrder: String,
    playtime: (T) -> Long?,
): List<T> =
    when (sort) {
        PlaylistPickerSort.NAME -> {
            sortedByKnownValue(direction) { name(it).lowercase() }
        }

        PlaylistPickerSort.CREATE_DATE -> {
            sortedByKnownValue(direction, addedAt)
        }

        PlaylistPickerSort.LAST_UPDATED -> {
            sortedByKnownValue(direction, lastUpdated)
        }

        PlaylistPickerSort.SONG_COUNT -> {
            sortedByKnownValue(direction, songCount)
        }

        PlaylistPickerSort.MANUAL -> {
            sortedByLibraryOrder(manualOrder, id)
        }

        PlaylistPickerSort.LIBRARY -> {
            when (librarySorting.sort) {
                LibraryContentSort.NAME -> sortedByKnownValue(librarySorting.direction) { name(it).lowercase() }
                LibraryContentSort.RECENTLY_ADDED -> sortedByKnownValue(librarySorting.direction, addedAt)
                LibraryContentSort.LAST_UPDATED -> sortedByKnownValue(librarySorting.direction, lastUpdated)
                LibraryContentSort.SONG_COUNT -> sortedByKnownValue(librarySorting.direction, songCount)
                LibraryContentSort.PLAYTIME -> sortedByKnownValue(librarySorting.direction, playtime)
                LibraryContentSort.MANUAL -> sortedByLibraryOrder(manualOrder, id)
                LibraryContentSort.ARTIST, LibraryContentSort.YEAR, LibraryContentSort.LENGTH -> this
            }
        }
    }

private fun <T> List<T>.sortedByLibraryOrder(
    manualOrder: String,
    id: (T) -> String,
): List<T> {
    fun key(item: T) = HiddenLibraryItemKey(HiddenLibraryItemType.PLAYLIST, id(item)).normalized()
    val positions = normalizedLibraryOrder(map(::key), manualOrder).withIndex().associate { it.value to it.index }
    return sortedBy { positions.getValue(key(it)) }
}

private fun <T, V : Comparable<V>> List<T>.sortedByKnownValue(
    direction: SortDirection,
    value: (T) -> V?,
): List<T> =
    sortedWith { first, second ->
        val left = value(first)
        val right = value(second)
        when {
            left == null && right == null -> 0
            left == null -> 1
            right == null -> -1
            direction == SortDirection.ASCENDING -> left.compareTo(right)
            else -> right.compareTo(left)
        }
    }
