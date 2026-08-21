/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import com.metrolist.music.db.entities.PlaylistEntity

/**
 * A playlist the user asked to keep in sync that has never reached YouTube: it carries the intent
 * but no `browseId` yet, because the creation failed or the device was offline at the time.
 *
 * The pending state is spelled out of the two columns that already exist rather than a new one, so
 * it costs no schema change. Note that a playlist the user did not ask to sync is never pending,
 * however long it has lived without a `browseId`.
 */
fun PlaylistEntity.isPendingRemoteCreation(): Boolean = isAutoSync && browseId == null

/** The playlists still waiting to be created on YouTube, in the order given. */
fun List<PlaylistEntity>.pendingRemoteCreations(): List<PlaylistEntity> =
    filter { it.isPendingRemoteCreation() }

/**
 * The playlist a creation request left on YouTube after its answer went missing, or null when
 * there is nothing to claim.
 *
 * Creating a playlist carries no key to match on: [com.metrolist.innertube.models.body.CreatePlaylistBody]
 * sends a title and nothing else, and a title is not an identity, so a user who wants a second
 * playlist called "Rock" must be able to have one. What does identify the playlist is *when* it
 * appeared. [idsBefore] is the account's playlists read just before the request went out, and
 * [remoteAfter] is the same list read again after it failed, as pairs of browse id and title. A
 * playlist that was not there before, is there now, and carries the [name] that was asked for is
 * the one that request created.
 *
 * Null when nothing new turned up, and equally when more than one did, since claiming the wrong
 * one would attach this device's playlist to somebody else's.
 */
fun playlistCreatedByLostRequest(
    name: String,
    idsBefore: Set<String>,
    remoteAfter: List<Pair<String, String>>,
): String? =
    remoteAfter
        .filter { (id, title) -> id !in idsBefore && title == name }
        .map { (id, _) -> id }
        .distinct()
        .singleOrNull()
