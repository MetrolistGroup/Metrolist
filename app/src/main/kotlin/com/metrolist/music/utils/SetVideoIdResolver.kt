/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import kotlinx.coroutines.delay
import timber.log.Timber

const val SET_VIDEO_ID_REMOTE_ATTEMPTS = 3
const val SET_VIDEO_ID_RETRY_DELAY_MS = 2_000L

/**
 * Resolves the setVideoId identifying the playlist row to remove, preferring the locally stored one
 * and falling back to a bounded number of remote lookups.
 *
 * Returns null when the occurrence cannot be identified. Callers must skip the removal in that case:
 * removing without a setVideoId would let YouTube pick an arbitrary occurrence of the song.
 */
suspend fun resolveSetVideoIdForRemoval(
    songId: String,
    fromDb: suspend () -> String?,
    fromRemote: suspend () -> String?,
    attempts: Int = SET_VIDEO_ID_REMOTE_ATTEMPTS,
    retryDelayMs: Long = SET_VIDEO_ID_RETRY_DELAY_MS,
    onDelay: suspend (Long) -> Unit = { delay(it) },
): String? {
    fromDb()?.let { return it }

    Timber.w("scheduleRemoveFromPlaylist: setVideoId not in DB, fetching from YouTube")
    for (attempt in 0 until attempts) {
        runCatching { fromRemote() }.getOrNull()?.let { return it }
        if (attempt < attempts - 1) onDelay(retryDelayMs)
    }

    Timber.w("scheduleRemoveFromPlaylist: setVideoId not found on YouTube, skipping remove for songId=$songId")
    return null
}
