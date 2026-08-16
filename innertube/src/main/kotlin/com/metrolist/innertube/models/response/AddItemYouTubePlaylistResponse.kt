package com.metrolist.innertube.models.response

import kotlinx.serialization.Serializable

@Serializable
data class AddItemYouTubePlaylistResponse(
    val status: String? = null,
    val playlistEditResults: List<PlaylistEditResult> = emptyList()
) {
    val firstSetVideoId: String?
        get() = playlistEditResults.firstOrNull()?.playlistEditVideoAddedResultData?.setVideoId

    @Serializable
    data class PlaylistEditResult(
        val playlistEditVideoAddedResultData: PlaylistEditVideoAddedResultData,
    ) {
        @Serializable
        data class PlaylistEditVideoAddedResultData(
            val setVideoId: String,
            val videoId: String
        )
    }
}
