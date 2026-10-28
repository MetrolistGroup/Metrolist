/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

private val DOWNLOAD_SONG_ID = Regex("^[A-Za-z0-9_-]{1,128}$")

/**
 * A download command needs a media id and a title. The id is also used as the
 * Media3 download request id, so path separators and whitespace are rejected.
 */
internal fun isValidDownloadRequest(songId: String?, title: String?): Boolean {
    val id = songId?.trim().orEmpty()
    return DOWNLOAD_SONG_ID.matches(id) && !title.isNullOrBlank()
}
