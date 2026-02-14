package com.metrolist.innertube.models.body

import com.metrolist.innertube.models.Context
import kotlinx.serialization.Serializable

@Serializable
data class PlaylistDeleteBody(
    val context: Context,
    val playlistId: String
)
