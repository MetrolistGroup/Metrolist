package com.metrolist.innertube.pages

import com.metrolist.innertube.models.Album
import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.Artist
import com.metrolist.innertube.models.MusicResponsiveListItemRenderer
import com.metrolist.innertube.models.MusicTwoRowItemRenderer
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.YTItem
import com.metrolist.innertube.models.oddElements
import com.metrolist.innertube.models.splitBySeparator
import com.metrolist.innertube.utils.parseTime

data class ArtistItemsPage(
    val title: String,
    val items: List<YTItem>,
    val continuation: String?,
) {
    companion object {
        fun fromMusicResponsiveListItemRenderer(renderer: MusicResponsiveListItemRenderer): SongItem? {
            // Extract artists from flexColumns - try multiple approaches
            val artists = renderer.flexColumns.getOrNull(1)
                ?.musicResponsiveListItemFlexColumnRenderer?.text?.runs
                ?.oddElements()?.map {
                    Artist(
                        name = it.text,
                        id = it.navigationEndpoint?.browseEndpoint?.browseId
                    )
                }

            // Extract album from last flexColumn (like SimpMusic does)
            val album = renderer.flexColumns.lastOrNull()
                ?.musicResponsiveListItemFlexColumnRenderer?.text?.runs
                ?.firstOrNull()?.let {
                    if (it.navigationEndpoint?.browseEndpoint?.browseId != null) {
                        Album(
                            name = it.text,
                            id = it.navigationEndpoint.browseEndpoint.browseId
                        )
                    } else null
                }

            // Extract library tokens using the new method that properly handles multiple toggle items
            val libraryTokens = PageHelper.extractLibraryTokensFromMenuItems(renderer.menu?.menuRenderer?.items)

            return SongItem(
                id = renderer.playlistItemData?.videoId ?: return null,
                title = renderer.flexColumns.firstOrNull()
                    ?.musicResponsiveListItemFlexColumnRenderer?.text
                    ?.runs?.firstOrNull()?.text ?: return null,
                artists = artists ?: return null,
                album = album,
                duration = renderer.fixedColumns?.firstOrNull()
                    ?.musicResponsiveListItemFlexColumnRenderer?.text
                    ?.runs?.firstOrNull()
                    ?.text?.parseTime(),
                musicVideoType = renderer.musicVideoType,
                thumbnail = renderer.thumbnail?.musicThumbnailRenderer?.getThumbnailUrl() ?: return null,
                explicit = renderer.badges?.find {
                    it.musicInlineBadgeRenderer?.icon?.iconType == "MUSIC_EXPLICIT_BADGE"
                } != null,
                endpoint = renderer.overlay?.musicItemThumbnailOverlayRenderer?.content?.musicPlayButtonRenderer?.playNavigationEndpoint?.watchEndpoint,
                libraryAddToken = libraryTokens.addToken,
                libraryRemoveToken = libraryTokens.removeToken
            )
        }

        fun fromMusicTwoRowItemRenderer(renderer: MusicTwoRowItemRenderer): YTItem? {
            return when {
                renderer.isAlbum -> AlbumItem(
                    browseId = renderer.navigationEndpoint.browseEndpoint?.browseId ?: return null,
                    playlistId = renderer.thumbnailOverlay?.musicItemThumbnailOverlayRenderer
                        ?.content?.musicPlayButtonRenderer?.playNavigationEndpoint
                        ?.anyWatchEndpoint?.playlistId ?: return null,
                    title = renderer.title.runs?.firstOrNull()?.text ?: return null,
                    artists = null,
                    year = renderer.subtitle?.runs?.lastOrNull()?.text?.toIntOrNull(),
                    thumbnail = renderer.thumbnailRenderer.musicThumbnailRenderer?.getThumbnailUrl() ?: return null,
                    explicit = renderer.subtitleBadges?.find {
                        it.musicInlineBadgeRenderer?.icon?.iconType == "MUSIC_EXPLICIT_BADGE"
                    } != null
                )
                // Video
                renderer.isSong -> SongItem(
                    id = renderer.navigationEndpoint.watchEndpoint?.videoId ?: return null,
                    title = renderer.title.runs?.firstOrNull()?.text ?: return null,
                    artists = renderer.subtitle?.runs?.splitBySeparator()?.firstOrNull()?.oddElements()?.map {
                        Artist(
                            name = it.text,
                            id = it.navigationEndpoint?.browseEndpoint?.browseId
                        )
                    } ?: return null,
                    album = null,
                    duration = null,
                    musicVideoType = renderer.musicVideoType,
                    thumbnail = renderer.thumbnailRenderer.musicThumbnailRenderer?.getThumbnailUrl() ?: return null,
                    endpoint = renderer.navigationEndpoint.watchEndpoint
                )
                renderer.isPlaylist -> PlaylistItem(
                    id = renderer.navigationEndpoint.browseEndpoint?.browseId?.removePrefix("VL") ?: return null,
                    title = renderer.title.runs?.firstOrNull()?.text ?: return null,
                    author = renderer.subtitle?.runs?.firstOrNull()?.let {
                        Artist(
                            name = it.text,
                            id = it.navigationEndpoint?.browseEndpoint?.browseId
                        )
                    },
                    songCountText = renderer.subtitle?.runs?.getOrNull(4)?.text,
                    thumbnail = renderer.thumbnailRenderer.musicThumbnailRenderer?.getThumbnailUrl() ?: return null,
                    playEndpoint = renderer.thumbnailOverlay
                        ?.musicItemThumbnailOverlayRenderer?.content
                        ?.musicPlayButtonRenderer?.playNavigationEndpoint
                        ?.watchPlaylistEndpoint ?: return null,
                    shuffleEndpoint = renderer.menu?.menuRenderer?.items?.find {
                        it.menuNavigationItemRenderer?.icon?.iconType == "MUSIC_SHUFFLE"
                    }?.menuNavigationItemRenderer?.navigationEndpoint?.watchPlaylistEndpoint ?: return null,
                    radioEndpoint = renderer.menu.menuRenderer.items.find {
                        it.menuNavigationItemRenderer?.icon?.iconType == "MIX"
                    }?.menuNavigationItemRenderer?.navigationEndpoint?.watchPlaylistEndpoint ?: return null
                )
                else -> null
            }
        }
    }
}
