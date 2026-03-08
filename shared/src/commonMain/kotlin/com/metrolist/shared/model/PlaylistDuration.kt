package com.metrolist.shared.model

fun playlistTotalDuration(
    songs: List<SongItem>,
    hasMoreSongs: Boolean,
    loadedSongCount: Int = songs.size,
): Long? =
    songs
        .takeIf { !hasMoreSongs && it.isNotEmpty() && it.size == loadedSongCount }
        ?.takeIf { it.all { song -> song.duration != null && song.duration > 0 } }
        ?.sumOf { requireNotNull(it.duration) }
