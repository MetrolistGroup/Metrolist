package com.metrolist.desktop.state.playback

import com.metrolist.shared.model.SongItem

internal class SuggestedSongKeys {
    private var nextKey = -1L
    private var keys = emptyMap<Pair<String, Int>, Long>()

    fun update(songs: List<SongItem>): Map<Long, SongItem> {
        val occurrences = mutableMapOf<String, Int>()
        val identities =
            songs.map { song ->
                val occurrence = occurrences.getOrDefault(song.id, 0)
                occurrences[song.id] = occurrence + 1
                song.id to occurrence
            }
        keys = identities.associateWith { keys[it] ?: nextKey-- }
        return songs.indices.associate { index -> keys.getValue(identities[index]) to songs[index] }
    }
}
