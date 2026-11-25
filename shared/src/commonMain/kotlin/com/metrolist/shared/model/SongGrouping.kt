/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.model

enum class SongGroupBy {
    NONE,
    ARTIST,
    ALBUM,
}

/**
 * [key] is the display name shared by the group's songs. It is null when no artist or album is
 * known; the UI should label those groups with a localized string. For [SongGroupBy.NONE] there
 * is a single group with a null key, and the UI should ignore the key.
 */
data class SongGroup(
    val key: String?,
    val songs: List<SongItem>,
)

/**
 * Groups songs by primary artist or album. Matching ignores case, so "Queen" and "queen" share a
 * group. Named groups are sorted alphabetically and the unknown group goes last. Songs keep their
 * original order inside each group.
 */
fun List<SongItem>.groupedBy(by: SongGroupBy): List<SongGroup> {
    if (by == SongGroupBy.NONE) return listOf(SongGroup(key = null, songs = this))

    fun nameFor(song: SongItem): String? =
        when (by) {
            SongGroupBy.ARTIST -> song.artists.firstOrNull()?.name
            SongGroupBy.ALBUM -> song.album?.name
            SongGroupBy.NONE -> null
        }?.trim()?.takeIf { it.isNotEmpty() }

    return groupBy { nameFor(it)?.lowercase() }
        .map { (_, songs) -> SongGroup(key = nameFor(songs.first()), songs = songs) }
        .sortedWith { a, b ->
            when {
                a.key == null && b.key == null -> 0
                a.key == null -> 1
                b.key == null -> -1
                else -> a.key.compareTo(b.key, ignoreCase = true)
            }
        }
}
