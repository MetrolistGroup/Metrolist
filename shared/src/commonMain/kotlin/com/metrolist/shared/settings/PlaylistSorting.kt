package com.metrolist.shared.settings

import com.metrolist.shared.model.CollectionSort
import com.metrolist.shared.model.PlaylistSongSort
import com.metrolist.shared.model.SortDirection
import kotlinx.serialization.Serializable

@Serializable
data class SavedPlaylistSongSorting(
    val sort: PlaylistSongSort,
    val direction: SortDirection,
)

@Serializable
data class SavedPlaylistCollectionSorting(
    val sort: CollectionSort,
    val direction: SortDirection,
)
