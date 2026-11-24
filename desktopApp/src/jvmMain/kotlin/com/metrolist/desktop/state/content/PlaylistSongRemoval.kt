package com.metrolist.desktop.state.content

import com.metrolist.shared.model.EpisodeItem
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.YTItem

private fun YTItem.removalOccurrence(): Pair<String, String>? =
    when (this) {
        is SongItem -> playlistLocalMappingId?.let { "local:$it" } ?: playlistSetVideoId?.let { "remote:$it" }
        is EpisodeItem -> playlistSetVideoId?.let { "remote:$it" }
        else -> null
    }?.let { id to it }

internal fun Map<String, List<YTItem>>.removePlaylistSongs(songs: List<SongItem>): Map<String, List<YTItem>> {
    // Match the playlist track projection: named track shelves take precedence; suggestions aren't membership.
    val namedTrackSections =
        listOf("Tracks", "Continuation").filter { section ->
            get(section).orEmpty().any { it is SongItem || it is EpisodeItem }
        }
    val trackSections =
        namedTrackSections.ifEmpty {
            keys.filterNot { it.equals("header", ignoreCase = true) || it.contains("suggest", ignoreCase = true) }
        }
    val requestedOccurrences = songs.mapNotNull { it.removalOccurrence() }.toSet()
    val loadedOccurrences =
        if (requestedOccurrences.isEmpty()) {
            emptySet()
        } else {
            trackSections
                .asSequence()
                .flatMap { getValue(it).asSequence() }
                .mapNotNull { it.removalOccurrence() }
                .filter { it in requestedOccurrences }
                .toSet()
        }
    val bySong = mutableMapOf<String, ArrayDeque<Int>>()
    val byOccurrence = mutableMapOf<Pair<String, String>, ArrayDeque<Int>>()
    val withoutOccurrence = mutableMapOf<String, ArrayDeque<Int>>()
    songs.forEachIndexed { index, song ->
        val occurrence = song.removalOccurrence()
        // Don't let a tokenless copy consume a removal that has an exact loaded match.
        if (occurrence !in loadedOccurrences) bySong.getOrPut(song.id) { ArrayDeque() }.addLast(index)
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
    val updatedTracks =
        trackSections.associateWith { section ->
            getValue(section).filter { item ->
                if (item !is SongItem && item !is EpisodeItem) return@filter true
                val occurrence = item.removalOccurrence()
                val match =
                    if (occurrence == null) {
                        bySong[item.id].next()
                    } else {
                        byOccurrence[occurrence].next() ?: withoutOccurrence[item.id].next()
                    }
                if (match != null) consumed[match] = true
                match == null
            }
        }
    return mapValues { (section, items) -> updatedTracks[section] ?: items }
}
