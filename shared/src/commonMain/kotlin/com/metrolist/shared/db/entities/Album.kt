/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.db.entities

import androidx.room3.Embedded
import androidx.room3.Junction
import androidx.room3.Relation

data class Album(
    @Embedded
    val album: AlbumEntity,
    @Relation(
        entity = ArtistEntity::class,
        entityColumns = ["id"],
        parentColumns = ["id"],
        associateBy =
            Junction(
                value = AlbumArtistMap::class,
                parentColumns = ["albumId"],
                entityColumns = ["artistId"],
            ),
    )
    val artists: List<ArtistEntity> = emptyList(),
    val songCountListened: Int? = 0,
    val timeListened: Long? = 0,
) : LocalItem() {
    override val id: String
        get() = album.id
    override val title: String
        get() = album.title
    override val thumbnailUrl: String?
        get() = album.thumbnailUrl
}
