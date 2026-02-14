package com.metrolist.innertube.pages

import com.metrolist.innertube.models.Album
import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.Artist
import com.metrolist.innertube.models.ArtistItem
import com.metrolist.innertube.models.BrowseEndpoint.BrowseEndpointContextSupportedConfigs.BrowseEndpointContextMusicConfig.Companion.MUSIC_PAGE_TYPE_ALBUM
import com.metrolist.innertube.models.BrowseEndpoint.BrowseEndpointContextSupportedConfigs.BrowseEndpointContextMusicConfig.Companion.MUSIC_PAGE_TYPE_ARTIST
import com.metrolist.innertube.models.BrowseEndpoint.BrowseEndpointContextSupportedConfigs.BrowseEndpointContextMusicConfig.Companion.MUSIC_PAGE_TYPE_USER_CHANNEL
import com.metrolist.innertube.models.MusicCardShelfRenderer
import com.metrolist.innertube.models.MusicResponsiveListItemRenderer
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.YTItem
import com.metrolist.innertube.models.clean
import com.metrolist.innertube.models.filterExplicit
import com.metrolist.innertube.models.filterVideoSongs
import com.metrolist.innertube.models.filterYoutubeShorts
import com.metrolist.innertube.models.oddElements
import com.metrolist.innertube.models.splitBySeparator
import com.metrolist.innertube.utils.parseTime

data class SearchSummary(
    val title: String,
    val items: List<YTItem>,
)

data class SearchSummaryPage(
    val summaries: List<SearchSummary>,
) {
    fun filterExplicit(enabled: Boolean) =
        if (enabled) {
            SearchSummaryPage(
                summaries.mapNotNull { s ->
                    SearchSummary(
                        title = s.title,
                        items =
                            s.items.filterExplicit().ifEmpty {
                                return@mapNotNull null
                            },
                    )
                },
            )
        } else {
            this
        }

    fun filterVideoSongs(disableVideos: Boolean) =
        if (disableVideos) {
            SearchSummaryPage(
                summaries.mapNotNull { s ->
                    SearchSummary(
                        title = s.title,
                        items =
                            s.items.filterVideoSongs(true).ifEmpty {
                                return@mapNotNull null
                            },
                    )
                },
            )
        } else {
            this
        }

    fun filterYoutubeShorts(enabled: Boolean = false) =
        if (enabled) {
            SearchSummaryPage(
                summaries.mapNotNull { s ->
                    SearchSummary(
                        title = s.title,
                        items =
                            s.items.filterYoutubeShorts(true).ifEmpty {
                                return@mapNotNull null
                            },
                    )
                },
            )
        } else {
            this
        }

    companion object {
        fun fromMusicCardShelfRenderer(renderer: MusicCardShelfRenderer): YTItem? {
            val subtitle = renderer.subtitle.runs?.splitBySeparator()
            return when {
                renderer.onTap.watchEndpoint != null -> {
                    SongItem(
                        id = renderer.onTap.watchEndpoint.videoId ?: return null,
                        title =
                            renderer.title.runs
                                ?.firstOrNull()
                                ?.text ?: return null,
                        artists =
                            subtitle?.getOrNull(1)?.oddElements()?.map {
                                Artist(
                                    name = it.text,
                                    id = it.navigationEndpoint?.browseEndpoint?.browseId,
                                )
                            } ?: return null,
                        album =
                            subtitle.getOrNull(2)?.firstOrNull()?.takeIf { it.navigationEndpoint?.browseEndpoint != null }?.let {
                                Album(
                                    name = it.text,
                                    id = it.navigationEndpoint?.browseEndpoint?.browseId!!,
                                )
                            },
                        duration =
                            subtitle
                                .lastOrNull()
                                ?.firstOrNull()
                                ?.text
                                ?.parseTime(),
                        musicVideoType = renderer.onTap.musicVideoType,
                        thumbnail = renderer.thumbnail.musicThumbnailRenderer?.getThumbnailUrl() ?: return null,
                        explicit =
                            renderer.subtitleBadges?.find {
                                it.musicInlineBadgeRenderer?.icon?.iconType == "MUSIC_EXPLICIT_BADGE"
                            } != null,
                    )
                }

                renderer.onTap.browseEndpoint?.isArtistEndpoint == true -> {
                    ArtistItem(
                        id = renderer.onTap.browseEndpoint.browseId,
                        title =
                            renderer.title.runs
                                ?.firstOrNull()
                                ?.text ?: return null,
                        thumbnail = renderer.thumbnail.musicThumbnailRenderer?.getThumbnailUrl() ?: return null,
                        shuffleEndpoint =
                            renderer.buttons
                                .find { it.buttonRenderer.icon?.iconType == "MUSIC_SHUFFLE" }
                                ?.buttonRenderer
                                ?.command
                                ?.watchPlaylistEndpoint ?: return null,
                        radioEndpoint =
                            renderer.buttons
                                .find { it.buttonRenderer.icon?.iconType == "MIX" }
                                ?.buttonRenderer
                                ?.command
                                ?.watchPlaylistEndpoint ?: return null,
                    )
                }

                renderer.onTap.browseEndpoint?.isAlbumEndpoint == true -> {
                    AlbumItem(
                        browseId = renderer.onTap.browseEndpoint.browseId,
                        playlistId =
                            renderer.buttons
                                .firstOrNull()
                                ?.buttonRenderer
                                ?.command
                                ?.anyWatchEndpoint
                                ?.playlistId ?: return null,
                        title =
                            renderer.title.runs
                                ?.firstOrNull()
                                ?.text ?: return null,
                        artists =
                            subtitle?.getOrNull(1)?.oddElements()?.map {
                                Artist(
                                    name = it.text,
                                    id = it.navigationEndpoint?.browseEndpoint?.browseId,
                                )
                            } ?: return null,
                        year = null,
                        thumbnail = renderer.thumbnail.musicThumbnailRenderer?.getThumbnailUrl() ?: return null,
                        explicit =
                            renderer.subtitleBadges?.find {
                                it.musicInlineBadgeRenderer?.icon?.iconType == "MUSIC_EXPLICIT_BADGE"
                            } != null,
                    )
                }

                renderer.onTap.browseEndpoint?.isPlaylistEndpoint == true -> {
                    PlaylistItem(
                        id =
                            renderer.onTap.browseEndpoint.browseId
                                .removePrefix("VL"),
                        title =
                            renderer.header?.musicCardShelfHeaderBasicRenderer?.title?.runs
                                ?.joinToString(separator = "") { it.text }
                                ?: return null,
                        author =
                            Artist(
                                id = null,
                                name = renderer.subtitle.runs?.joinToString { it.text } ?: return null,
                            ),
                        songCountText = null,
                        thumbnail = renderer.thumbnail.musicThumbnailRenderer?.getThumbnailUrl() ?: return null,
                        playEndpoint =
                            renderer.buttons
                                .find { it.buttonRenderer.icon?.iconType == "PLAY_ARROW" }
                                ?.buttonRenderer
                                ?.command
                                ?.watchPlaylistEndpoint
                                ?: return null,
                        shuffleEndpoint =
                            renderer.buttons
                                .find { it.buttonRenderer.icon?.iconType == "MUSIC_SHUFFLE" }
                                ?.buttonRenderer
                                ?.command
                                ?.watchPlaylistEndpoint
                                ?: return null,
                        radioEndpoint = null,
                    )
                }

                else -> null
            }
        }

        fun fromMusicResponsiveListItemRenderer(renderer: MusicResponsiveListItemRenderer): YTItem? {
            val secondaryLine =
                renderer.flexColumns
                    .getOrNull(1)
                    ?.musicResponsiveListItemFlexColumnRenderer
                    ?.text
                    ?.runs
                    ?.splitBySeparator()
                    ?: return null
            val thirdLine =
                renderer.flexColumns
                    .getOrNull(2)
                    ?.musicResponsiveListItemFlexColumnRenderer
                    ?.text
                    ?.runs
                    ?.splitBySeparator()
                    ?: emptyList()
            val listRun = (secondaryLine + thirdLine).clean()
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
                        artists = listRun.getOrNull(0)?.oddElements()?.map {
                            Artist(
                                name = it.text,
                                id = it.navigationEndpoint?.browseEndpoint?.browseId
                            )
                        } ?: return null,
                        album = listRun.getOrNull(1)?.firstOrNull()?.takeIf { it.navigationEndpoint?.browseEndpoint != null }?.let {
                            Album(
                                name = it.text,
                                id = it.navigationEndpoint?.browseEndpoint?.browseId!!
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
                                ?.watchPlaylistEndpoint
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
                            secondaryLine.getOrNull(1)?.firstOrNull()?.let {
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
}
