@file:OptIn(ExperimentalSerializationApi::class)

package com.metrolist.innertube.models

import com.metrolist.innertube.models.BrowseEndpoint.BrowseEndpointContextSupportedConfigs.BrowseEndpointContextMusicConfig.Companion.MUSIC_PAGE_TYPE_ALBUM
import com.metrolist.innertube.models.BrowseEndpoint.BrowseEndpointContextSupportedConfigs.BrowseEndpointContextMusicConfig.Companion.MUSIC_PAGE_TYPE_ARTIST
import com.metrolist.innertube.models.BrowseEndpoint.BrowseEndpointContextSupportedConfigs.BrowseEndpointContextMusicConfig.Companion.MUSIC_PAGE_TYPE_AUDIOBOOK
import com.metrolist.innertube.models.BrowseEndpoint.BrowseEndpointContextSupportedConfigs.BrowseEndpointContextMusicConfig.Companion.MUSIC_PAGE_TYPE_LIBRARY_ARTIST
import com.metrolist.innertube.models.BrowseEndpoint.BrowseEndpointContextSupportedConfigs.BrowseEndpointContextMusicConfig.Companion.MUSIC_PAGE_TYPE_PLAYLIST
import com.metrolist.innertube.models.BrowseEndpoint.BrowseEndpointContextSupportedConfigs.BrowseEndpointContextMusicConfig.Companion.MUSIC_PAGE_TYPE_PODCAST_SHOW_DETAIL_PAGE
import com.metrolist.innertube.models.BrowseEndpoint.BrowseEndpointContextSupportedConfigs.BrowseEndpointContextMusicConfig.Companion.MUSIC_PAGE_TYPE_NON_MUSIC_AUDIO_TRACK_PAGE
import com.metrolist.innertube.models.BrowseEndpoint.BrowseEndpointContextSupportedConfigs.BrowseEndpointContextMusicConfig.Companion.MUSIC_PAGE_TYPE_USER_CHANNEL
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

/**
 * Typical list item
 * Used in [MusicCarouselShelfRenderer], [MusicShelfRenderer]
 * Appears in quick picks, search results, table items, etc.
 */
@Serializable
data class MusicResponsiveListItemRenderer(
    val badges: List<Badges>?,
    val fixedColumns: List<FlexColumn>?,
    val flexColumns: List<FlexColumn>,
    val thumbnail: ThumbnailRenderer?,
    val menu: Menu?,
    val playlistItemData: PlaylistItemData?,
    val overlay: Overlay?,
    val navigationEndpoint: NavigationEndpoint?,
) {
    val isSong: Boolean
        get() = navigationEndpoint == null
            || navigationEndpoint.watchEndpoint != null
            || navigationEndpoint.watchPlaylistEndpoint != null
            || overlay?.musicItemThumbnailOverlayRenderer
                ?.content?.musicPlayButtonRenderer
                ?.playNavigationEndpoint?.watchEndpoint != null
    val isPlaylist: Boolean
        get() = navigationEndpoint?.browseEndpoint?.browseEndpointContextSupportedConfigs?.browseEndpointContextMusicConfig?.pageType == MUSIC_PAGE_TYPE_PLAYLIST
    val isAlbum: Boolean
        get() = navigationEndpoint?.browseEndpoint?.browseEndpointContextSupportedConfigs?.browseEndpointContextMusicConfig?.pageType == MUSIC_PAGE_TYPE_ALBUM ||
                navigationEndpoint?.browseEndpoint?.browseEndpointContextSupportedConfigs?.browseEndpointContextMusicConfig?.pageType == MUSIC_PAGE_TYPE_AUDIOBOOK
    val isArtist: Boolean
        get() = navigationEndpoint?.browseEndpoint?.browseEndpointContextSupportedConfigs?.browseEndpointContextMusicConfig?.pageType == MUSIC_PAGE_TYPE_ARTIST
                || navigationEndpoint?.browseEndpoint?.browseEndpointContextSupportedConfigs?.browseEndpointContextMusicConfig?.pageType == MUSIC_PAGE_TYPE_LIBRARY_ARTIST
    val isPodcast: Boolean
        get() = navigationEndpoint?.browseEndpoint?.browseEndpointContextSupportedConfigs?.browseEndpointContextMusicConfig?.pageType == MUSIC_PAGE_TYPE_PODCAST_SHOW_DETAIL_PAGE
    val isUserChannel: Boolean
        get() = navigationEndpoint?.browseEndpoint?.browseEndpointContextSupportedConfigs?.browseEndpointContextMusicConfig?.pageType == MUSIC_PAGE_TYPE_USER_CHANNEL
    val isEpisode: Boolean
        get() {
            // Method 1: Check browse endpoint (for episode detail pages)
            if (navigationEndpoint?.browseEndpoint?.browseEndpointContextSupportedConfigs?.browseEndpointContextMusicConfig?.pageType == MUSIC_PAGE_TYPE_NON_MUSIC_AUDIO_TRACK_PAGE) {
                return true
            }
            // Method 2: Check if first subtitle text is "Episode" (for search results)
            val firstSubtitleText = flexColumns.getOrNull(1)
                ?.musicResponsiveListItemFlexColumnRenderer
                ?.text?.runs?.firstOrNull()?.text
            if (firstSubtitleText == "Episode") {
                return true
            }
            // Method 3: Check for podcast link in subtitle (backup detection).
            //
            // Episode items that appear in filtered search results may have:
            //   - navigationEndpoint.watchEndpoint  (playable) → isSong=true would wrongly match
            //   - playlistItemData = null            (not in a playlist context)
            //   - subtitle: [date · podcast-name]   (podcast-name links to PODCAST_SHOW_DETAIL_PAGE)
            //
            // The presence of a PODCAST_SHOW_DETAIL_PAGE browse link in the subtitle is unique to
            // episodes — regular songs never carry such a link.  We accept either a playlistItemData
            // videoId OR a direct watchEndpoint videoId to handle both playlist and standalone contexts.
            val hasPodcastLink = flexColumns.getOrNull(1)
                ?.musicResponsiveListItemFlexColumnRenderer
                ?.text?.runs?.any { run ->
                    run.navigationEndpoint?.browseEndpoint
                        ?.browseEndpointContextSupportedConfigs
                        ?.browseEndpointContextMusicConfig
                        ?.pageType == MUSIC_PAGE_TYPE_PODCAST_SHOW_DETAIL_PAGE
                } == true
            val hasVideoId = videoId != null
            return hasPodcastLink && hasVideoId
        }

    val musicVideoType: String?
        get() =
            overlay
                ?.musicItemThumbnailOverlayRenderer
                ?.content
                ?.musicPlayButtonRenderer
                ?.playNavigationEndpoint
                ?.musicVideoType
                ?: navigationEndpoint?.musicVideoType

    val videoId: String?
        get() = playlistItemData?.videoId
            ?: flexColumns.firstOrNull()
                ?.musicResponsiveListItemFlexColumnRenderer
                ?.text?.runs?.firstOrNull()
                ?.navigationEndpoint?.watchEndpoint?.videoId
            ?: overlay?.musicItemThumbnailOverlayRenderer
                ?.content?.musicPlayButtonRenderer
                ?.playNavigationEndpoint?.watchEndpoint?.videoId

    val playlistSetVideoId: String?
        get() = playlistItemData?.playlistSetVideoId
            ?: overlay?.musicItemThumbnailOverlayRenderer
                ?.content?.musicPlayButtonRenderer
                ?.playNavigationEndpoint?.watchEndpoint?.playlistSetVideoId

    @Serializable
    data class FlexColumn(
        @JsonNames("musicResponsiveListItemFixedColumnRenderer")
        val musicResponsiveListItemFlexColumnRenderer: MusicResponsiveListItemFlexColumnRenderer,
    ) {
        @Serializable
        data class MusicResponsiveListItemFlexColumnRenderer(
            val text: Runs?,
        )
    }

    @Serializable
    data class PlaylistItemData(
        val playlistSetVideoId: String?,
        val videoId: String,
    )

    @Serializable
    data class Overlay(
        val musicItemThumbnailOverlayRenderer: MusicItemThumbnailOverlayRenderer,
    ) {
        @Serializable
        data class MusicItemThumbnailOverlayRenderer(
            val content: Content,
        ) {
            @Serializable
            data class Content(
                val musicPlayButtonRenderer: MusicPlayButtonRenderer,
            ) {
                @Serializable
                data class MusicPlayButtonRenderer(
                    val playNavigationEndpoint: NavigationEndpoint?,
                )
            }
        }
    }
}
