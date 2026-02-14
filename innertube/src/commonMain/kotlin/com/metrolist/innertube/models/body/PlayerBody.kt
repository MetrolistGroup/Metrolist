package com.metrolist.innertube.models.body

import com.metrolist.innertube.models.Context
import kotlinx.serialization.Serializable

@Serializable
data class PlayerBody(
    val context: Context,
    val videoId: String,
    val playlistId: String?,
    val playbackContext: PlaybackContext? = null,
    val serviceIntegrityDimensions: ServiceIntegrityDimensions? = null,
    val contentCheckOk: Boolean = true,
    val racyCheckOk: Boolean = true,
) {
    @Serializable
    data class PlaybackContext(
        val contentPlaybackContext: ContentPlaybackContext
    ) {
        @Serializable
        data class ContentPlaybackContext(
            val signatureTimestamp: Int
        )
    }

    @Serializable
    data class ServiceIntegrityDimensions(
        val poToken: String
    )
}
