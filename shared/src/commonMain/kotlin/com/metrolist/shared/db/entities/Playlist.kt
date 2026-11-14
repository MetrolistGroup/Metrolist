/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.db.entities

import androidx.room3.Embedded

data class Playlist(
    @Embedded
    val playlist: PlaylistEntity,
    val songCount: Int,
) : LocalItem() {
    override val id: String
        get() = playlist.id
    override val title: String
        get() = playlist.name
    override val thumbnailUrl: String?
        get() = playlist.customThumbnailUrl ?: playlist.thumbnailUrl
}
