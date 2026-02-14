package com.metrolist.innertube.pages

import com.metrolist.innertube.models.Album
import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.Artist
import com.metrolist.innertube.models.ArtistItem
import com.metrolist.innertube.models.MusicResponsiveListItemRenderer
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.YTItem
import com.metrolist.innertube.models.oddElements
import com.metrolist.innertube.models.splitBySeparator
import com.metrolist.innertube.utils.parseTime

data class SearchResult(
    val items: List<YTItem>,
    val continuation: String? = null,
)

object SearchPage {
    fun toYTItem(renderer: MusicResponsiveListItemRenderer): YTItem? {
        val secondaryLine =
            renderer.flexColumns
                .getOrNull(1)
                ?.musicResponsiveListItemFlexColumnRenderer
                ?.text
                ?.runs
                ?.splitBySeparator()
                ?: return null
        return when {
            renderer.isSong -> {
                // Extract library tokens using the new method that properly handles multiple toggle items
                val libraryTokens = PageHelper.extractLibraryTokensFromMenuItems(renderer.menu?.menuRenderer?.items)

                SongItem(
                    id = renderer.playlistItemData?.videoId ?: return null,
                    title =
                        renderer.flexColumns
                            .firstOrNull()
                            ?.musicResponsiveListItemFlexColumnRenderer
                            ?.text
                            ?.runs
                            ?.firstOrNull()
                            ?.text ?: return null,
                    artists =
                        secondaryLine.firstOrNull()?.oddElements()?.map {
                            Artist(
                                name = it.text,
                                id = it.navigationEndpoint?.browseEndpoint?.browseId,
                            )
                        } ?: return null,
                    album =
                        secondaryLine.getOrNull(1)?.firstOrNull()?.takeIf { it.navigationEndpoint?.browseEndpoint != null }?.let {
                            Album(
                                name = it.text,
                                id = it.navigationEndpoint?.browseEndpoint?.browseId!!,
                            )
                        },
                    duration =
                        secondaryLine
                            .lastOrNull()
                            ?.firstOrNull()
                            ?.text
                            ?.parseTime(),
                    musicVideoType = renderer.musicVideoType,
                    thumbnail = renderer.thumbnail?.musicThumbnailRenderer?.getThumbnailUrl() ?: return null,
                    explicit =
                        renderer.badges?.find {
                            it.musicInlineBadgeRenderer?.icon?.iconType == "MUSIC_EXPLICIT_BADGE"
                        } != null,
                    libraryAddToken = libraryTokens.addToken,
                    libraryRemoveToken = libraryTokens.removeToken
                )
            }
            renderer.isArtist -> {
                ArtistItem(
                    id = renderer.navigationEndpoint?.browseEndpoint?.browseId ?: return null,
                    title =
                        renderer.flexColumns
                            .firstOrNull()
                            ?.musicResponsiveListItemFlexColumnRenderer
                            ?.text
                            ?.runs
                            ?.firstOrNull()
                            ?.text
                            ?: return null,
                    thumbnail = renderer.thumbnail?.musicThumbnailRenderer?.getThumbnailUrl() ?: return null,
                    shuffleEndpoint =
                        renderer.menu
                            ?.menuRenderer
                            ?.items
                            ?.find { it.menuNavigationItemRenderer?.icon?.iconType == "MUSIC_SHUFFLE" }
                            ?.menuNavigationItemRenderer
                            ?.navigationEndpoint
                            ?.watchPlaylistEndpoint ?: return null,
                    radioEndpoint =
                        renderer.menu.menuRenderer.items
                            .find { it.menuNavigationItemRenderer?.icon?.iconType == "MIX" }
                            ?.menuNavigationItemRenderer
                            ?.navigationEndpoint
                            ?.watchPlaylistEndpoint ?: return null,
                )
            }
            renderer.isAlbum -> {
                AlbumItem(
                    browseId = renderer.navigationEndpoint?.browseEndpoint?.browseId ?: return null,
                    playlistId =
                        renderer.overlay
                            ?.musicItemThumbnailOverlayRenderer
                            ?.content
                            ?.musicPlayButtonRenderer
                            ?.playNavigationEndpoint
                            ?.anyWatchEndpoint
                            ?.playlistId
                            ?: return null,
                    title =
                        renderer.flexColumns
                            .firstOrNull()
                            ?.musicResponsiveListItemFlexColumnRenderer
                            ?.text
                            ?.runs
                            ?.firstOrNull()
                            ?.text ?: return null,
                    artists =
                        secondaryLine.getOrNull(1)?.oddElements()?.map {
                            Artist(
                                name = it.text,
                                id = it.navigationEndpoint?.browseEndpoint?.browseId,
                            )
                        } ?: return null,
                    year =
                        secondaryLine
                            .getOrNull(2)
                            ?.firstOrNull()
                            ?.text
                            ?.toIntOrNull(),
                    thumbnail = renderer.thumbnail?.musicThumbnailRenderer?.getThumbnailUrl() ?: return null,
                    explicit =
                        renderer.badges?.find {
                            it.musicInlineBadgeRenderer?.icon?.iconType == "MUSIC_EXPLICIT_BADGE"
                        } != null,
                )
            }
            renderer.isPlaylist -> {
                PlaylistItem(
                    id =
                        renderer.navigationEndpoint
                            ?.browseEndpoint
                            ?.browseId
                            ?.removePrefix("VL") ?: return null,
                    title =
                        renderer.flexColumns
                            .firstOrNull()
                            ?.musicResponsiveListItemFlexColumnRenderer
                            ?.text
                            ?.runs
                            ?.firstOrNull()
                            ?.text ?: return null,
                    author =
                        secondaryLine.firstOrNull()?.firstOrNull()?.let {
                            Artist(
                                name = it.text,
                                id = it.navigationEndpoint?.browseEndpoint?.browseId,
                            )
                        } ?: return null,
                    songCountText =
                        renderer.flexColumns
                            .getOrNull(1)
                            ?.musicResponsiveListItemFlexColumnRenderer
                            ?.text
                            ?.runs
                            ?.lastOrNull()
                            ?.text ?: return null,
                    thumbnail = renderer.thumbnail?.musicThumbnailRenderer?.getThumbnailUrl() ?: return null,
                    playEndpoint =
                        renderer.overlay
                            ?.musicItemThumbnailOverlayRenderer
                            ?.content
                            ?.musicPlayButtonRenderer
                            ?.playNavigationEndpoint
                            ?.watchPlaylistEndpoint ?: return null,
                    shuffleEndpoint =
                        renderer.menu
                            ?.menuRenderer
                            ?.items
                            ?.find { it.menuNavigationItemRenderer?.icon?.iconType == "MUSIC_SHUFFLE" }
                            ?.menuNavigationItemRenderer
                            ?.navigationEndpoint
                            ?.watchPlaylistEndpoint ?: return null,
                    radioEndpoint =
                        renderer.menu.menuRenderer.items
                            .find { it.menuNavigationItemRenderer?.icon?.iconType == "MIX" }
                            ?.menuNavigationItemRenderer
                            ?.navigationEndpoint
                            ?.watchPlaylistEndpoint ?: return null,
                )
            }
            else -> null
        }
    }
}
