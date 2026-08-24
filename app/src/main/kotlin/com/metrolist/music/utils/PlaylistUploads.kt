/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

/** What reading the playlist said about an upload whose answer never came back. */
sealed interface UploadOutcome {
    /** The song reached the playlist. [setVideoId] names the copy it created, when it is knowable. */
    data class Landed(val setVideoId: String?) : UploadOutcome

    /** The playlist holds no more copies than before, so the request never arrived. */
    data object DidNotLand : UploadOutcome

    /** The playlist could not be read, so nothing can be said either way. */
    data object Unknown : UploadOutcome
}

/**
 * Settles an upload that failed without saying whether it had already been applied.
 *
 * An add carries no key to match on, and asking whether the song is in the playlist answers the
 * wrong question: a playlist is allowed to hold the same song twice, which is exactly what the
 * duplicate warning's "add anyway" leaves behind, so presence proves nothing. What does settle it
 * is how many copies there are. [knownSetVideoIds] are the copies of that song this device had
 * already recorded for the playlist, and [remoteSetVideoIds] are the copies the playlist holds
 * now: one more than before means the request landed, and the copy that was not there before is
 * the one it created.
 *
 * A landed upload whose new copy cannot be picked out, because more than one appeared, still
 * counts as landed with no [UploadOutcome.Landed.setVideoId]. Guessing which copy is which would
 * point the row at an item it does not name, while leaving it empty only costs a lookup when the
 * song is later removed.
 */
fun reconcileUpload(
    knownSetVideoIds: List<String>,
    remoteSetVideoIds: List<String>,
): UploadOutcome {
    if (remoteSetVideoIds.size <= knownSetVideoIds.size) return UploadOutcome.DidNotLand
    val appeared = remoteSetVideoIds.toMutableList()
    knownSetVideoIds.forEach { appeared.remove(it) }
    return UploadOutcome.Landed(appeared.singleOrNull())
}
