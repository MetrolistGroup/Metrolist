package com.metrolist.shared.model

enum class PlaylistSongSort {
    DATE_ADDED,
    TITLE,
    ARTIST,
    ALBUM,
    DURATION,
    YEAR,
    ORIGINAL,
}

enum class SortDirection {
    ASCENDING,
    DESCENDING,
}

fun PlaylistSongSort.allowsReordering(): Boolean = this == PlaylistSongSort.ORIGINAL

fun List<SongItem>.sortedForPlaylist(
    sort: PlaylistSongSort,
    direction: SortDirection,
    dateAddedOrder: SortDirection = SortDirection.ASCENDING,
): List<SongItem> {
    if (sort == PlaylistSongSort.ORIGINAL) return this
    if (sort == PlaylistSongSort.DATE_ADDED) {
        if (dateAddedOrder == SortDirection.ASCENDING && all { it.playlistAddedOrder != null }) {
            return if (direction ==
                SortDirection.ASCENDING
            ) {
                sortedBy { it.playlistAddedOrder }
            } else {
                sortedByDescending { it.playlistAddedOrder }
            }
        }
        return if (direction == dateAddedOrder) this else asReversed()
    }

    return when (sort) {
        PlaylistSongSort.TITLE -> sortedForPlaylistBy(direction) { it.title.lowercase() }
        PlaylistSongSort.ARTIST -> sortedForPlaylistBy(direction) { it.artists.joinToString { artist -> artist.name }.lowercase() }
        PlaylistSongSort.ALBUM -> sortedForPlaylistBy(direction) { it.album?.name?.lowercase() }
        PlaylistSongSort.DURATION -> sortedForPlaylistBy(direction) { it.duration }
        PlaylistSongSort.YEAR -> sortedForPlaylistBy(direction) { it.year }
        PlaylistSongSort.ORIGINAL, PlaylistSongSort.DATE_ADDED -> this
    }
}

private fun <T : Comparable<T>> List<SongItem>.sortedForPlaylistBy(
    direction: SortDirection,
    key: (SongItem) -> T?,
): List<SongItem> =
    mapIndexed { index, song -> Triple(index, song, key(song)) }
        .sortedWith { left, right ->
            compareNullable(left.third, right.third, direction).takeIf { it != 0 }
                ?: left.first.compareTo(right.first)
        }.map { it.second }

fun List<SongItem>.sortedForLikedPlaylist(
    sort: PlaylistSongSort,
    direction: SortDirection,
    recency: List<String> = emptyList(),
): List<SongItem> {
    val songs =
        if (sort == PlaylistSongSort.DATE_ADDED && recency.isNotEmpty()) {
            val positions = recency.withIndex().associate { it.value to it.index }
            sortedBy { positions[it.id] ?: Int.MAX_VALUE }
        } else {
            this
        }
    return songs.sortedForPlaylist(sort, direction, dateAddedOrder = SortDirection.DESCENDING)
}

private fun <T : Comparable<T>> compareNullable(
    left: T?,
    right: T?,
    direction: SortDirection,
): Int =
    when {
        left == null && right == null -> 0
        left == null -> 1
        right == null -> -1
        else -> left.compareTo(right).let { if (direction == SortDirection.ASCENDING) it else -it }
    }

fun List<SongItem>.filterForPlaylistSearch(query: String): List<SongItem> {
    val term = query.trim()
    if (term.isEmpty()) return this
    return filter { song ->
        song.matchesSongQuery(term)
    }
}

fun List<SongItem>.indexOfPlaylistOccurrence(song: SongItem): Int {
    indexOfFirst { it === song }.takeIf { it >= 0 }?.let { return it }
    song.playlistSetVideoId?.let { setVideoId ->
        indexOfFirst { it.id == song.id && it.playlistSetVideoId == setVideoId }
            .takeIf { it >= 0 }
            ?.let { return it }
    }
    song.playlistLocalMappingId?.let { mappingId ->
        indexOfFirst { it.playlistLocalMappingId == mappingId }
            .takeIf { it >= 0 }
            ?.let { return it }
    }
    indexOf(song).takeIf { it >= 0 }?.let { return it }
    return indexOfFirst { it.id == song.id }
}

fun List<SongItem>.playlistSearchIndices(query: String): List<Int> {
    val matches = filterForPlaylistSearch(query).toSet()
    return indices.filter { this[it] in matches }
}

fun YTItem.searchableText(): String =
    when (this) {
        is SongItem -> {
            buildString {
                append(title)
                artists.forEach { append(' ').append(it.name) }
                album?.name?.let { append(' ').append(it) }
            }
        }

        is AlbumItem -> {
            buildString {
                append(title)
                artists.forEach { append(' ').append(it.name) }
            }
        }

        is ArtistItem -> {
            title
        }

        is PlaylistItem -> {
            buildString {
                append(title)
                author?.let { append(' ').append(it) }
            }
        }

        is PodcastItem -> {
            buildString {
                append(title)
                author?.let { append(' ').append(it) }
            }
        }

        is EpisodeItem -> {
            buildString {
                append(title)
                author?.name?.let { append(' ').append(it) }
            }
        }

        is BrowseItem -> {
            buildString {
                append(title)
                subtitle?.let { append(' ').append(it) }
            }
        }
    }

fun List<YTItem>.filterForLibrarySearch(query: String): List<YTItem> {
    val term = query.trim()
    if (term.isEmpty()) return this
    return filter { it.searchableText().contains(term, ignoreCase = true) }
}

fun albumLibraryKey(id: String): String = "album:$id"

fun artistLibraryKey(id: String): String = "artist:$id"

fun playlistLibraryKey(id: String): String = "playlist:${id.removePrefix("VL")}"

fun podcastLibraryKey(id: String): String = "podcast:$id"

fun YTItem.librarySearchKey(): String =
    when (this) {
        is AlbumItem -> albumLibraryKey(id)
        is ArtistItem -> artistLibraryKey(id)
        is BrowseItem -> "browse:$id"
        is EpisodeItem -> "episode:$id"
        is PlaylistItem -> playlistLibraryKey(id)
        is PodcastItem -> podcastLibraryKey(id)
        is SongItem -> "song:$id"
    }

fun SongItem.playlistOccurrenceKey(fallbackIndex: Int): String =
    playlistSetVideoId ?: playlistLocalMappingId?.let { "local:$it" } ?: "$id@$fallbackIndex"

fun List<SongItem>.playlistLazyItemKeys(): List<String> {
    val occurrences = mutableMapOf<String, Int>()
    return map { song ->
        val identity = song.playlistSetVideoId ?: song.playlistLocalMappingId?.let { "local:$it" } ?: song.id
        val occurrence = occurrences[identity] ?: 0
        occurrences[identity] = occurrence + 1
        "playlist_song:$identity@$occurrence"
    }
}
