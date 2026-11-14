package com.metrolist.shared.db.daos

import androidx.room3.Embedded
import com.metrolist.shared.db.entities.Event

data class HistoryRow(
    @Embedded val event: Event,
    val title: String,
    val artists: String?,
    val album: String?,
    val duration: Long?,
    val thumbnailUrl: String?,
    val snapshotEventId: Long?,
    val snapshotTitle: String?,
    val snapshotArtists: String?,
    val snapshotAlbum: String?,
    val snapshotDuration: Long?,
    val snapshotThumbnailUrl: String?,
    val snapshotToken: String?,
    val snapshotArtistNamesJson: String?,
    val snapshotAlbumId: String?,
    val catalogAlbumId: String?,
)
