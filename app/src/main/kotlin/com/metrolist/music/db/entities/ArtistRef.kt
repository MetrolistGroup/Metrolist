package com.metrolist.music.db.entities

import androidx.compose.runtime.Immutable

@Immutable
data class ArtistRef(
    val id: String,
    val name: String,
)
