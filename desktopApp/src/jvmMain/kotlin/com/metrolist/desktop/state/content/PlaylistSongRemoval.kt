package com.metrolist.desktop.state.content

import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.YTItem

internal fun Map<String, List<YTItem>>.removePlaylistSongs(songs: List<SongItem>): Map<String, List<YTItem>> {
    val bySong = mutableMapOf<String, ArrayDeque<Int>>()
    val byOccurrence = mutableMapOf<String, ArrayDeque<Int>>()
    val withoutOccurrence = mutableMapOf<String, ArrayDeque<Int>>()
    songs.forEachIndexed { index, song ->
        bySong.getOrPut(song.id) { ArrayDeque() }.addLast(index)
        val occurrence = song.playlistLocalMappingId?.let { "local:$it" } ?: song.playlistSetVideoId
        if (occurrence == null) {
            withoutOccurrence.getOrPut(song.id) { ArrayDeque() }.addLast(index)
        } else {
            byOccurrence.getOrPut(occurrence) { ArrayDeque() }.addLast(index)
        }
    }
    val consumed = BooleanArray(songs.size)

    fun ArrayDeque<Int>?.next(): Int? {
        if (this == null) return null
        while (isNotEmpty() && consumed[first()]) removeFirst()
        return firstOrNull()
    }
    return mapValues { (_, items) ->
        items.filter { item ->
            if (item !is SongItem) return@filter true
            val occurrence = item.playlistLocalMappingId?.let { "local:$it" } ?: item.playlistSetVideoId
            val match =
                if (occurrence == null) {
                    bySong[item.id].next()
                } else {
                    val exact = byOccurrence[occurrence].next()
                    val fallback = withoutOccurrence[item.id].next()
                    if (exact != null && fallback != null) minOf(exact, fallback) else exact ?: fallback
                }
            if (match != null) consumed[match] = true
            match == null
        }
    }
}
