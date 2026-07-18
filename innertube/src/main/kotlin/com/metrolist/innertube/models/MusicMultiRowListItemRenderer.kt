package com.metrolist.innertube.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class MusicMultiRowListItemRenderer(
    val title: Runs?,
    val subtitle: Runs?,
    @SerialName("secondSubtitle")
    val secondSubtitle: Runs? = null,
    @SerialName("secondarySubtitle")
    val secondarySubtitle: Runs? = null,
    val thumbnail: ThumbnailRenderer?,
    val onTap: NavigationEndpoint?,
    val playbackProgress: PlaybackProgress?,
    val displayStyle: String?,
    val menu: Menu?,
) {
    @Serializable
    data class PlaybackProgress(
        val value: Float? = null,
    )
}
