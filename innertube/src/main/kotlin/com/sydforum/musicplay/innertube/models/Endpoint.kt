package com.sydforum.musicplay.innertube.models

import com.sydforum.musicplay.innertube.models.BrowseEndpoint.BrowseEndpointContextSupportedConfigs.BrowseEndpointContextMusicConfig.Companion.MUSIC_PAGE_TYPE_ALBUM
import com.sydforum.musicplay.innertube.models.BrowseEndpoint.BrowseEndpointContextSupportedConfigs.BrowseEndpointContextMusicConfig.Companion.MUSIC_PAGE_TYPE_ARTIST
import com.sydforum.musicplay.innertube.models.BrowseEndpoint.BrowseEndpointContextSupportedConfigs.BrowseEndpointContextMusicConfig.Companion.MUSIC_PAGE_TYPE_PLAYLIST
import com.sydforum.musicplay.innertube.models.BrowseEndpoint.BrowseEndpointContextSupportedConfigs.BrowseEndpointContextMusicConfig.Companion.MUSIC_PAGE_TYPE_AUDIOBOOK
import com.sydforum.musicplay.innertube.models.BrowseEndpoint.BrowseEndpointContextSupportedConfigs.BrowseEndpointContextMusicConfig.Companion.MUSIC_PAGE_TYPE_PODCAST_SHOW_DETAIL_PAGE
import kotlinx.serialization.Serializable

@Serializable
sealed class Endpoint

@Serializable
data class WatchEndpoint(
    val videoId: String? = null,
    val playlistId: String? = null,
    val playlistSetVideoId: String? = null,
    val params: String? = null,
    val index: Int? = null,
    val watchEndpointMusicSupportedConfigs: WatchEndpointMusicSupportedConfigs? = null,
) : Endpoint() {

    @Serializable
    data class WatchEndpointMusicSupportedConfigs(
        val watchEndpointMusicConfig: WatchEndpointMusicConfig,
    ) {
        @Serializable
        data class WatchEndpointMusicConfig(
            val musicVideoType: String,
        ) {
            companion object {
                const val MUSIC_VIDEO_TYPE_OMV = "MUSIC_VIDEO_TYPE_OMV"
                const val MUSIC_VIDEO_TYPE_UGC = "MUSIC_VIDEO_TYPE_UGC"
                const val MUSIC_VIDEO_TYPE_ATV = "MUSIC_VIDEO_TYPE_ATV"
            }
        }
    }
}

@Serializable
data class BrowseEndpoint(
    val browseId: String,
    val params: String? = null,
    val browseEndpointContextSupportedConfigs: BrowseEndpointContextSupportedConfigs? = null,
) : Endpoint() {
    val isArtistEndpoint: Boolean
        get() = browseEndpointContextSupportedConfigs?.browseEndpointContextMusicConfig?.pageType == MUSIC_PAGE_TYPE_ARTIST
    val isAlbumEndpoint: Boolean
        get() = browseEndpointContextSupportedConfigs?.browseEndpointContextMusicConfig?.pageType == MUSIC_PAGE_TYPE_ALBUM ||
                browseEndpointContextSupportedConfigs?.browseEndpointContextMusicConfig?.pageType == MUSIC_PAGE_TYPE_AUDIOBOOK
    val isPlaylistEndpoint: Boolean
        get() = browseEndpointContextSupportedConfigs?.browseEndpointContextMusicConfig?.pageType == MUSIC_PAGE_TYPE_PLAYLIST
    val isPodcastEndpoint: Boolean
        get() = browseEndpointContextSupportedConfigs?.browseEndpointContextMusicConfig?.pageType == MUSIC_PAGE_TYPE_PODCAST_SHOW_DETAIL_PAGE

    @Serializable
    data class BrowseEndpointContextSupportedConfigs(
        val browseEndpointContextMusicConfig: BrowseEndpointContextMusicConfig,
    ) {
        @Serializable
        data class BrowseEndpointContextMusicConfig(
            val pageType: String,
        ) {
            companion object {
                const val MUSIC_PAGE_TYPE_ALBUM = "MUSIC_PAGE_TYPE_ALBUM"
                const val MUSIC_PAGE_TYPE_AUDIOBOOK = "MUSIC_PAGE_TYPE_AUDIOBOOK"
                const val MUSIC_PAGE_TYPE_PLAYLIST = "MUSIC_PAGE_TYPE_PLAYLIST"
                const val MUSIC_PAGE_TYPE_ARTIST = "MUSIC_PAGE_TYPE_ARTIST"
                const val MUSIC_PAGE_TYPE_LIBRARY_ARTIST = "MUSIC_PAGE_TYPE_LIBRARY_ARTIST"
                const val MUSIC_PAGE_TYPE_USER_CHANNEL = "MUSIC_PAGE_TYPE_USER_CHANNEL"
                const val MUSIC_PAGE_TYPE_TRACK_LYRICS = "MUSIC_PAGE_TYPE_TRACK_LYRICS"
                const val MUSIC_PAGE_TYPE_TRACK_RELATED = "MUSIC_PAGE_TYPE_TRACK_RELATED"
                const val MUSIC_PAGE_TYPE_PODCAST_SHOW_DETAIL_PAGE = "MUSIC_PAGE_TYPE_PODCAST_SHOW_DETAIL_PAGE"
                const val MUSIC_PAGE_TYPE_NON_MUSIC_AUDIO_TRACK_PAGE = "MUSIC_PAGE_TYPE_NON_MUSIC_AUDIO_TRACK_PAGE"
            }
        }
    }
}

@Serializable
data class SearchEndpoint(
    val params: String?,
    val query: String,
) : Endpoint()

@Serializable
data class FeedbackEndpoint(
    val feedbackToken: String
) : Endpoint()

@Serializable
data class QueueAddEndpoint(
    val queueInsertPosition: String,
    val queueTarget: QueueTarget,
) : Endpoint() {
    @Serializable
    data class QueueTarget(
        val videoId: String? = null,
        val playlistId: String? = null,
    )
}

@Serializable
data class ShareEntityEndpoint(
    val serializedShareEntity: String,
) : Endpoint()

@Serializable
data class DefaultServiceEndpoint(
    var subscribeEndpoint: SubscribeEndpoint?,
    var feedbackEndpoint: FeedbackEndpoint?
) : Endpoint() {
    @Serializable
    data class SubscribeEndpoint(
        val channelIds: List<String>,
        val params: String? = null,
    ) : Endpoint()
}

@Serializable
data class ToggledServiceEndpoint(
    var feedbackEndpoint: FeedbackEndpoint?
) : Endpoint()