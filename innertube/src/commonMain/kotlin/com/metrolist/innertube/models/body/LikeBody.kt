package com.metrolist.innertube.models.body

import com.metrolist.innertube.models.Context
import kotlinx.serialization.Serializable

@Serializable
data class LikeBody(
    val context: Context,
    val target: Target,
) {
    @Serializable
    sealed class Target {
        @Serializable
        data class VideoTarget(val videoId: String) : Target()
        @Serializable
        data class PlaylistTarget(val playlistId: String) : Target()
    }
}
