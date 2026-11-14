package com.metrolist.shared.settings

import com.metrolist.shared.model.SortDirection
import kotlinx.serialization.Serializable

@Serializable
data class LibrarySorting(
    val sort: LibraryContentSort,
    val direction: SortDirection,
)
