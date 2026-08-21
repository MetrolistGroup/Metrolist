package com.metrolist.innertube.models.response

import kotlinx.serialization.Serializable

/**
 * The answer to adding a song to a playlist.
 *
 * Every field is optional on purpose. This model is only read to learn the `setVideoId` of the
 * item that was added, which is a bonus rather than the point: the add either happened or it did
 * not, and that is already settled by the time the body is parsed. A required field missing from a
 * shape YouTube changed would turn a successful add into a failure, and a failure is what makes
 * the caller ask again, which is how the song ends up in the playlist twice.
 */
@Serializable
data class AddItemYouTubePlaylistResponse(
    val status: String? = null,
    val playlistEditResults: List<PlaylistEditResult> = emptyList()
) {
    val firstSetVideoId: String?
        get() = playlistEditResults.firstNotNullOfOrNull {
            it.playlistEditVideoAddedResultData?.setVideoId
        }

    @Serializable
    data class PlaylistEditResult(
        val playlistEditVideoAddedResultData: PlaylistEditVideoAddedResultData? = null,
    ) {
        @Serializable
        data class PlaylistEditVideoAddedResultData(
            val setVideoId: String? = null,
            val videoId: String? = null
        )
    }
}
