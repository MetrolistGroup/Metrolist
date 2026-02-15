package com.metrolist.innertube.pages

import com.metrolist.innertube.models.Album
import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.Artist
import com.metrolist.innertube.models.ArtistItem
import com.metrolist.innertube.models.MusicResponsiveListItemRenderer
import com.metrolist.innertube.models.MusicTwoRowItemRenderer
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.innertube.models.Run
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.YTItem
import com.metrolist.innertube.models.oddElements
import com.metrolist.innertube.utils.parseTime

data class LibraryPage(
    val items: List<YTItem>,
    val continuation: String?,
) {
    companion object {
        fun fromMusicTwoRowItemRenderer(renderer: MusicTwoRowItemRenderer): YTItem? {
            return when {
                renderer.isAlbum -> AlbumItem(
                    browseId = renderer.navigationEndpoint.browseEndpoint?.browseId ?: return null,
                    playlistId = renderer.thumbnailOverlay?.musicItemThumbnailOverlayRenderer?.content
                        ?.musicPlayButtonRenderer?.playNavigationEndpoint
                        ?.watchPlaylistEndpoint?.playlistId ?: return null,
                    title = renderer.title.runs?.firstOrNull()?.text ?: return null,
                    artists = parseArtists(renderer.subtitle?.runs),
                    year = renderer.subtitle?.runs?.lastOrNull()?.text?.toIntOrNull(),
                    thumbnail = renderer.thumbnailRenderer.musicThumbnailRenderer?.getThumbnailUrl()
                        ?: return null,
                    explicit = renderer.subtitleBadges?.find {
                        it.musicInlineBadgeRenderer?.icon?.iconType == "MUSIC_EXPLICIT_BADGE"
                    } != null
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
                    songCountText = renderer.subtitle?.runs?.lastOrNull()?.text,
                    thumbnail = renderer.thumbnailRenderer.musicThumbnailRenderer?.getThumbnailUrl() ?: return null,
                    playEndpoint = renderer.thumbnailOverlay
                        ?.musicItemThumbnailOverlayRenderer?.content
                        ?.musicPlayButtonRenderer?.playNavigationEndpoint
                        ?.watchPlaylistEndpoint,
                    shuffleEndpoint = renderer.menu?.menuRenderer?.items?.find {
                        it.menuNavigationItemRenderer?.icon?.iconType == "MUSIC_SHUFFLE"
                    }?.menuNavigationItemRenderer?.navigationEndpoint?.watchPlaylistEndpoint,
                    radioEndpoint = renderer.menu?.menuRenderer?.items?.find {
                        it.menuNavigationItemRenderer?.icon?.iconType == "MIX"
                    }?.menuNavigationItemRenderer?.navigationEndpoint?.watchPlaylistEndpoint,
                    isEditable = renderer.menu?.menuRenderer?.items?.find {
                        it.menuNavigationItemRenderer?.icon?.iconType == "EDIT"
                    } != null
                )

                renderer.isArtist -> ArtistItem(
                    id = renderer.navigationEndpoint.browseEndpoint?.browseId ?: return null,
                    title = renderer.title.runs?.lastOrNull()?.text ?: return null,
                    thumbnail = renderer.thumbnailRenderer.musicThumbnailRenderer?.getThumbnailUrl() ?: return null,
                    shuffleEndpoint = renderer.menu?.menuRenderer?.items?.find {
                        it.menuNavigationItemRenderer?.icon?.iconType == "MUSIC_SHUFFLE"
                    }?.menuNavigationItemRenderer?.navigationEndpoint?.watchPlaylistEndpoint ?: return null,
                    radioEndpoint = renderer.menu.menuRenderer.items.find {
                        it.menuNavigationItemRenderer?.icon?.iconType == "MIX"
                    }?.menuNavigationItemRenderer?.navigationEndpoint?.watchPlaylistEndpoint ?: return null,
                )

                else -> null
            }
        }

        fun fromMusicResponsiveListItemRenderer(renderer: MusicResponsiveListItemRenderer): YTItem? {
            // Extract library tokens using the new method that properly handles multiple toggle items
            val libraryTokens = PageHelper.extractLibraryTokensFromMenuItems(renderer.menu?.menuRenderer?.items)

            return when {
                renderer.isSong -> {
                    val videoId = renderer.playlistItemData?.videoId
                    if (videoId == null) {
                        println("[UPLOAD_DEBUG] LibraryPage.parse FAILED: videoId is null")
                        return null
                    }

                    val title = renderer.flexColumns.firstOrNull()
                        ?.musicResponsiveListItemFlexColumnRenderer?.text
                        ?.runs?.firstOrNull()?.text
                    if (title == null) {
                        println("[UPLOAD_DEBUG] LibraryPage.parse FAILED: title is null for videoId=$videoId")
                        return null
                    }

                    val artistRuns = renderer.flexColumns.getOrNull(1)?.musicResponsiveListItemFlexColumnRenderer?.text?.runs?.oddElements()
                    println("[UPLOAD_DEBUG] LibraryPage.parse: videoId=$videoId, title=$title, artistRuns=${artistRuns?.map { "${it.text}(browseId=${it.navigationEndpoint?.browseEndpoint?.browseId})" }}")

                    // For uploaded songs, artists may not have browseEndpoint - make it optional
                    val artists = artistRuns?.mapNotNull {
                        val browseId = it.navigationEndpoint?.browseEndpoint?.browseId
                        if (browseId == null) {
                            println("[UPLOAD_DEBUG] LibraryPage.parse: Artist '${it.text}' has no browseId, using empty string")
                            // For uploaded songs, use empty string for artist ID if not available
                            Artist(name = it.text, id = "")
                        } else {
                            Artist(name = it.text, id = browseId)
                        }
                    } ?: emptyList()

                    val albumRun = renderer.flexColumns.getOrNull(2)?.musicResponsiveListItemFlexColumnRenderer?.text?.runs?.firstOrNull()
                    println("[UPLOAD_DEBUG] LibraryPage.parse: albumRun=${albumRun?.text}, albumBrowseId=${albumRun?.navigationEndpoint?.browseEndpoint?.browseId}")

                    // For uploaded songs, album may not have browseEndpoint - make it optional
                    val album = albumRun?.let {
                        val albumBrowseId = it.navigationEndpoint?.browseEndpoint?.browseId
                        if (albumBrowseId == null) {
                            println("[UPLOAD_DEBUG] LibraryPage.parse: Album '${it.text}' has no browseId, using empty string")
                            Album(name = it.text, id = "")
                        } else {
                            Album(name = it.text, id = albumBrowseId)
                        }
                    }

                    val thumbnailUrl = renderer.thumbnail?.musicThumbnailRenderer?.getThumbnailUrl()
                    if (thumbnailUrl == null) {
                        println("[UPLOAD_DEBUG] LibraryPage.parse FAILED: thumbnail is null for videoId=$videoId")
                        return null
                    }

                    println("[UPLOAD_DEBUG] LibraryPage.parse SUCCESS: videoId=$videoId, title=$title, artists=${artists.map { it.name }}, album=${album?.name}")

                    SongItem(
                        id = videoId,
                        title = title,
                        artists = artists,
                        album = album,
                        duration = renderer.fixedColumns?.firstOrNull()?.musicResponsiveListItemFlexColumnRenderer?.text?.runs?.firstOrNull()?.text?.parseTime(),
                        musicVideoType = renderer.musicVideoType,
                        thumbnail = thumbnailUrl,
                        explicit = renderer.badges?.find {
                            it.musicInlineBadgeRenderer?.icon?.iconType == "MUSIC_EXPLICIT_BADGE"
                        } != null,
                        endpoint = renderer.overlay?.musicItemThumbnailOverlayRenderer?.content?.musicPlayButtonRenderer?.playNavigationEndpoint?.watchEndpoint,
                        libraryAddToken = libraryTokens.addToken,
                        libraryRemoveToken = libraryTokens.removeToken
                    )
                }

                renderer.isArtist -> ArtistItem(
                    id = renderer.navigationEndpoint?.browseEndpoint?.browseId ?: return null,
                    title = renderer.flexColumns.firstOrNull()?.musicResponsiveListItemFlexColumnRenderer?.text?.runs?.firstOrNull()?.text
                        ?: return null,
                    thumbnail = renderer.thumbnail?.musicThumbnailRenderer?.getThumbnailUrl()
                        ?: return null,
                    shuffleEndpoint = renderer.menu?.menuRenderer?.items
                        ?.find { it.menuNavigationItemRenderer?.icon?.iconType == "MUSIC_SHUFFLE" }
                        ?.menuNavigationItemRenderer?.navigationEndpoint?.watchPlaylistEndpoint,
                    radioEndpoint = renderer.menu?.menuRenderer?.items
                        ?.find { it.menuNavigationItemRenderer?.icon?.iconType == "MIX" }
                        ?.menuNavigationItemRenderer?.navigationEndpoint?.watchPlaylistEndpoint
                )

                else -> {
                    println("[UPLOAD_DEBUG] LibraryPage.parse: Not a song or artist, isSong=${renderer.isSong}, isArtist=${renderer.isArtist}")
                    null
                }
            }
        }

        private fun parseArtists(runs: List<Run>?): List<Artist> {
            val artists = mutableListOf<Artist>()

            if (runs != null) {
                for (run in runs) {
                    if (run.navigationEndpoint != null) {
                        artists.add(
                            Artist(
                                id = run.navigationEndpoint.browseEndpoint?.browseId!!,
                                name = run.text
                            )
                        )
                    }
                }
            }
            return artists
        }
    }
}
