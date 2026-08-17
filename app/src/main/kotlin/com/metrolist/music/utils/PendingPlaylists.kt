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
