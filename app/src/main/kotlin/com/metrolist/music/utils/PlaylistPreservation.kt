/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

/**
 * The local song ids a remote playlist does not account for, which a sync has to carry across the
 * rebuild instead of dropping.
 *
 * Compared by occurrence rather than by membership: a song this playlist holds twice while YouTube
 * lists it once is still missing a copy here, and asking only whether the id appears remotely would
 * discard it. A playlist can hold the same song more than once deliberately, which is what the
 * duplicate warning's "add anyway" leaves behind.
 *
 * Order follows [localSongIds], so the songs are appended in the order the user already sees them.
 */
fun songIdsAbsentFromRemote(
    localSongIds: List<String>,
    remoteSongIds: List<String>,
): List<String> {
    val unmatchedRemote = remoteSongIds.groupingBy { it }.eachCount().toMutableMap()
    return localSongIds.filter { songId ->
        val remaining = unmatchedRemote[songId] ?: 0
        if (remaining > 0) {
            // This local row is the remote occurrence, so it is rebuilt from the remote side and
            // must not be preserved a second time.
            unmatchedRemote[songId] = remaining - 1
            false
        } else {
            true
        }
    }
}
