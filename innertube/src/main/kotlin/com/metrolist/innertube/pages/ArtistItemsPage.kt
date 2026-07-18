package com.metrolist.innertube.pages

import com.metrolist.innertube.models.Album
import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.Artist
import com.metrolist.innertube.models.MusicResponsiveListItemRenderer
import com.metrolist.innertube.models.MusicTwoRowItemRenderer
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.YTItem
import com.metrolist.innertube.models.splitArtistsByConjunction
import com.metrolist.innertube.models.splitBySeparator
import com.metrolist.innertube.utils.parseTime
import timber.log.Timber

data class ArtistItemsPage(
    val title: String,
    val items: List<YTItem>,
    val continuation: String?,
) {
    companion object {
        fun fromMusicResponsiveListItemRenderer(renderer: MusicResponsiveListItemRenderer): SongItem? {
            val artistRuns = renderer.flexColumns
                .getOrNull(1)
                ?.musicResponsiveListItemFlexColumnRenderer
                ?.text
                ?.runs
                ?.splitBySeparator()
                ?.getOrNull(0)
                ?.splitArtistsByConjunction()
                ?.filter { it.text.isNotBlank() && it.text != "&" && it.text != "," }
                ?.map { run ->
                    Artist(
                        name = run.text.trim(),
                        id = run.navigationEndpoint?.browseEndpoint?.browseId
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
                id = renderer.playlistItemData?.videoId
                    ?: renderer.navigationEndpoint?.watchEndpoint?.videoId
                    ?: renderer.overlay?.musicItemThumbnailOverlayRenderer
                        ?.content?.musicPlayButtonRenderer
                        ?.playNavigationEndpoint?.watchEndpoint?.videoId
                    ?: renderer.flexColumns.firstOrNull()
                        ?.musicResponsiveListItemFlexColumnRenderer
                        ?.text?.runs?.firstOrNull()
                        ?.navigationEndpoint?.watchEndpoint?.videoId
                    ?: return null,
                title = renderer.flexColumns.firstOrNull()
                    ?.musicResponsiveListItemFlexColumnRenderer?.text
                    ?.runs?.firstOrNull()?.text ?: return null,
                artists = artistRuns ?: return null,
                album = album,
                duration = renderer.fixedColumns?.firstOrNull()
                    ?.musicResponsiveListItemFlexColumnRenderer?.text
                    ?.runs?.firstOrNull()
                    ?.text?.parseTime(),
                musicVideoType = renderer.musicVideoType,
                thumbnail = renderer.thumbnail?.getThumbnailUrl() ?: return null,
                explicit = renderer.badges?.find {
                    it.musicInlineBadgeRenderer?.icon?.iconType == "MUSIC_EXPLICIT_BADGE"
                } != null,
                endpoint = renderer.overlay?.musicItemThumbnailOverlayRenderer?.content?.musicPlayButtonRenderer?.playNavigationEndpoint?.watchEndpoint,
                libraryAddToken = libraryTokens.addToken,
                libraryRemoveToken = libraryTokens.removeToken,
                isEpisode = renderer.isEpisode
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
                    thumbnail = renderer.thumbnailRenderer.getThumbnailUrl() ?: return null,
                    explicit = renderer.subtitleBadges?.find {
                        it.musicInlineBadgeRenderer?.icon?.iconType == "MUSIC_EXPLICIT_BADGE"
                    } != null
                )
                // Video
                renderer.isSong -> {
                    val title = renderer.title.runs?.firstOrNull()?.text ?: return null
                    val videoId = renderer.navigationEndpoint.watchEndpoint?.videoId ?: return null
                    val subtitleRuns = renderer.subtitle?.runs ?: return null
                    val expandedRuns = subtitleRuns.splitArtistsByConjunction()
                    val artistRuns = expandedRuns.filter { run ->
                        run.text.isNotBlank() && run.text != "&" && run.text != "," &&
                        run.navigationEndpoint?.browseEndpoint?.browseId?.startsWith("UC") == true
                    }
                    val artists = artistRuns.map { run ->
                        Artist(
                            name = run.text.trim(),
                            id = run.navigationEndpoint?.browseEndpoint?.browseId
                        )
                    }
                    
                    if (artists.isEmpty() && renderer.subtitle?.runs != null) {
                        Timber.w("ArtistItemsPage.fromMusicTwoRowItemRenderer: Song '$title' (id=$videoId) - SUBTITLE RUNS EXIST but parsing returned EMPTY")
                    }

                    SongItem(
                        id = videoId,
                        title = title,
                        artists = artists.ifEmpty { null } ?: return null,
                        album = null,
                        duration = null,
                        musicVideoType = renderer.musicVideoType,
                        thumbnail = renderer.thumbnailRenderer.getThumbnailUrl() ?: return null,
                        endpoint = renderer.navigationEndpoint.watchEndpoint
                    )
                }
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
                    thumbnail = renderer.thumbnailRenderer.getThumbnailUrl() ?: return null,
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
