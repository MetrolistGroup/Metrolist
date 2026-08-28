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

/**
 * The number of songs a remote playlist says it holds, read out of the localised text YouTube
 * renders under its title, such as "12 songs". Null when the page says nothing countable.
 */
fun remoteSongCountOf(songCountText: String?): Int? =
    songCountText?.let { Regex("""\d+""").find(it)?.value?.toIntOrNull() }

/**
 * Whether a remote read describes the playlist well enough to rebuild the local copy from it.
 *
 * [readSongCount] is how many songs came through and [claimedSongCount] is how many the page says
 * are there. A read that falls short of the claim is one this build could not follow to the end,
 * and it is not the same thing as a playlist that lost songs: every song that failed to arrive
 * would be taken for local-only and moved to the end of the playlist. An empty read is the case
 * that matters most, because nothing at all arrived.
 *
 * A page that claims a number no larger than what arrived is taken at its word, and that includes
 * a playlist which genuinely holds nothing. A page that claims nothing countable is only trusted
 * when at least one song arrived, since there is then no way to tell an empty playlist from an
 * unreadable one.
 */
fun remoteReadAccountsForPlaylist(readSongCount: Int, claimedSongCount: Int?): Boolean =
    if (claimedSongCount == null) readSongCount > 0 else readSongCount >= claimedSongCount
