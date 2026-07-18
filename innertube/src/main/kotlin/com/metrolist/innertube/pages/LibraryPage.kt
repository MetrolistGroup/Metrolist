package com.metrolist.innertube.pages

import com.metrolist.innertube.models.Album
import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.Artist
import com.metrolist.innertube.models.ArtistItem
import com.metrolist.innertube.models.MusicResponsiveListItemRenderer
import com.metrolist.innertube.models.MusicTwoRowItemRenderer
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.innertube.models.PodcastItem
import com.metrolist.innertube.models.Run
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.YTItem
import com.metrolist.innertube.models.oddElements
import com.metrolist.innertube.models.splitBySeparator
import com.metrolist.innertube.utils.parseTime
import timber.log.Timber

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
                    thumbnail = renderer.thumbnailRenderer.getThumbnailUrl()
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
                    thumbnail = renderer.thumbnailRenderer.getThumbnailUrl() ?: return null,
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
                    thumbnail = renderer.thumbnailRenderer.getThumbnailUrl() ?: return null,
                    shuffleEndpoint = renderer.menu?.menuRenderer?.items?.find {
                        it.menuNavigationItemRenderer?.icon?.iconType == "MUSIC_SHUFFLE"
                    }?.menuNavigationItemRenderer?.navigationEndpoint?.watchPlaylistEndpoint ?: return null,
                    radioEndpoint = renderer.menu.menuRenderer.items.find {
                        it.menuNavigationItemRenderer?.icon?.iconType == "MIX"
                    }?.menuNavigationItemRenderer?.navigationEndpoint?.watchPlaylistEndpoint ?: return null,
                )

                // Podcast host channels use MUSIC_PAGE_TYPE_USER_CHANNEL (not ARTIST)
                renderer.isUserChannel -> ArtistItem(
                    id = renderer.navigationEndpoint.browseEndpoint?.browseId ?: return null,
                    title = renderer.title.runs?.firstOrNull()?.text ?: return null,
                    thumbnail = renderer.thumbnailRenderer.getThumbnailUrl(),
                    shuffleEndpoint = renderer.menu?.menuRenderer?.items?.find {
                        it.menuNavigationItemRenderer?.icon?.iconType == "MUSIC_SHUFFLE"
                    }?.menuNavigationItemRenderer?.navigationEndpoint?.watchPlaylistEndpoint,
                    radioEndpoint = renderer.menu?.menuRenderer?.items?.find {
                        it.menuNavigationItemRenderer?.icon?.iconType == "MIX"
                    }?.menuNavigationItemRenderer?.navigationEndpoint?.watchPlaylistEndpoint,
                )

                renderer.isPodcast -> {
                    val libraryTokens = PageHelper.extractLibraryTokensFromMenuItems(renderer.menu?.menuRenderer?.items)
                    PodcastItem(
                        id = renderer.navigationEndpoint.browseEndpoint?.browseId ?: return null,
                        title = renderer.title.runs?.firstOrNull()?.text ?: return null,
                        author = renderer.subtitle?.runs?.firstOrNull()?.let {
                            Artist(
                                name = it.text,
                                id = it.navigationEndpoint?.browseEndpoint?.browseId
                            )
                        },
                        episodeCountText = renderer.subtitle?.runs?.lastOrNull()?.text,
                        thumbnail = renderer.thumbnailRenderer.getThumbnailUrl(),
                        playEndpoint = renderer.thumbnailOverlay
                            ?.musicItemThumbnailOverlayRenderer?.content
                            ?.musicPlayButtonRenderer?.playNavigationEndpoint
                            ?.watchPlaylistEndpoint,
                        shuffleEndpoint = renderer.menu?.menuRenderer?.items?.find {
                            it.menuNavigationItemRenderer?.icon?.iconType == "MUSIC_SHUFFLE"
                        }?.menuNavigationItemRenderer?.navigationEndpoint?.watchPlaylistEndpoint,
                        libraryAddToken = libraryTokens.addToken,
                        libraryRemoveToken = libraryTokens.removeToken,
                    )
                }

                renderer.isEpisode || renderer.isSong -> {
                    val libraryTokens = PageHelper.extractLibraryTokensFromMenuItems(renderer.menu?.menuRenderer?.items)
                    val videoId = renderer.thumbnailOverlay
                        ?.musicItemThumbnailOverlayRenderer?.content
                        ?.musicPlayButtonRenderer?.playNavigationEndpoint
                        ?.watchEndpoint?.videoId ?: return null
                    val title = renderer.title.runs?.firstOrNull()?.text ?: return null
                    val subtitleRuns = renderer.subtitle?.runs?.splitBySeparator()
                    val artists = PageHelper.extractArtists(subtitleRuns?.firstOrNull())
                    
                    if (artists.isEmpty() && (subtitleRuns?.firstOrNull()?.size ?: 0) > 0) {
                        Timber.w("LibraryPage: Song '$title' (id=$videoId) - ARTIST RUNS EXIST but extractArtists returned EMPTY")
                    }
                    
                    SongItem(
                        id = videoId,
                        title = title,
                        artists = artists,
                        album = subtitleRuns?.getOrNull(1)?.firstOrNull()?.let {
                            Album(
                                name = it.text,
                                id = it.navigationEndpoint?.browseEndpoint?.browseId ?: ""
                            )
                        },
                        duration = subtitleRuns?.lastOrNull()?.firstOrNull()?.text?.parseTime(),
                        thumbnail = renderer.thumbnailRenderer.getThumbnailUrl() ?: return null,
                        explicit = renderer.subtitleBadges?.any {
                            it.musicInlineBadgeRenderer?.icon?.iconType == "MUSIC_EXPLICIT_BADGE"
                        } == true,
                        endpoint = renderer.thumbnailOverlay
                            .musicItemThumbnailOverlayRenderer.content
                            .musicPlayButtonRenderer.playNavigationEndpoint
                            .watchEndpoint,
                        libraryAddToken = libraryTokens.addToken,
                        libraryRemoveToken = libraryTokens.removeToken,
                        isEpisode = renderer.isEpisode,
                    )
                }

                else -> null
            }
        }

        fun fromMusicResponsiveListItemRenderer(renderer: MusicResponsiveListItemRenderer): YTItem? {
            // Extract library tokens using the new method that properly handles multiple toggle items
            val libraryTokens = PageHelper.extractLibraryTokensFromMenuItems(renderer.menu?.menuRenderer?.items)

            return when {
                renderer.isSong -> {
                    val videoId = renderer.videoId ?: return null
                    val title = renderer.flexColumns.firstOrNull()
                        ?.musicResponsiveListItemFlexColumnRenderer?.text
                        ?.runs?.firstOrNull()?.text ?: return null

                    val artistRuns = renderer.flexColumns.getOrNull(1)?.musicResponsiveListItemFlexColumnRenderer?.text?.runs?.oddElements()

                    // For uploaded songs, artists may not have browseEndpoint - make it optional
                    val artists = artistRuns?.mapNotNull {
                        val browseId = it.navigationEndpoint?.browseEndpoint?.browseId
                        // For uploaded songs, use empty string for artist ID if not available
                        Artist(name = it.text, id = browseId ?: "")
                    } ?: emptyList()

                    val albumRun = renderer.flexColumns.getOrNull(2)?.musicResponsiveListItemFlexColumnRenderer?.text?.runs?.firstOrNull()

                    // For uploaded songs, album may not have browseEndpoint - make it optional
                    val album = albumRun?.let {
                        val albumBrowseId = it.navigationEndpoint?.browseEndpoint?.browseId
                        Album(name = it.text, id = albumBrowseId ?: "")
                    }

                    val thumbnailUrl = renderer.thumbnail?.getThumbnailUrl() ?: return null

                    // Extract uploadEntityId from delete menu item (for uploaded songs)
                    // The entityId is nested in confirmDialogEndpoint -> content -> confirmDialogRenderer ->
                    // confirmButton -> buttonRenderer -> command -> musicDeletePrivatelyOwnedEntityCommand -> entityId
                    val uploadEntityId = renderer.menu?.menuRenderer?.items?.firstNotNullOfOrNull { item ->
                        item.menuNavigationItemRenderer?.navigationEndpoint?.confirmDialogEndpoint
                            ?.content?.confirmDialogRenderer?.confirmButton?.buttonRenderer
                            ?.command?.musicDeletePrivatelyOwnedEntityCommand?.entityId
                    }
                    timber.log.Timber.d("Parsed uploaded song: id=$videoId, entityId=$uploadEntityId")

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
                        libraryRemoveToken = libraryTokens.removeToken,
                        isEpisode = renderer.isEpisode,
                        uploadEntityId = uploadEntityId
                    )
                }

                renderer.isArtist -> ArtistItem(
                    id = renderer.navigationEndpoint?.browseEndpoint?.browseId ?: return null,
                    title = renderer.flexColumns.firstOrNull()?.musicResponsiveListItemFlexColumnRenderer?.text?.runs?.firstOrNull()?.text
                        ?: return null,
                    thumbnail = renderer.thumbnail?.getThumbnailUrl()
                        ?: return null,
                    shuffleEndpoint = renderer.menu?.menuRenderer?.items
                        ?.find { it.menuNavigationItemRenderer?.icon?.iconType == "MUSIC_SHUFFLE" }
                        ?.menuNavigationItemRenderer?.navigationEndpoint?.watchPlaylistEndpoint,
                    radioEndpoint = renderer.menu?.menuRenderer?.items
                        ?.find { it.menuNavigationItemRenderer?.icon?.iconType == "MIX" }
                        ?.menuNavigationItemRenderer?.navigationEndpoint?.watchPlaylistEndpoint
                )

                // Podcast host channels use MUSIC_PAGE_TYPE_USER_CHANNEL (not ARTIST)
                renderer.isUserChannel -> ArtistItem(
                    id = renderer.navigationEndpoint?.browseEndpoint?.browseId ?: return null,
                    title = renderer.flexColumns.firstOrNull()?.musicResponsiveListItemFlexColumnRenderer?.text?.runs?.firstOrNull()?.text
                        ?: return null,
                    thumbnail = renderer.thumbnail?.getThumbnailUrl(),
                    shuffleEndpoint = renderer.menu?.menuRenderer?.items
                        ?.find { it.menuNavigationItemRenderer?.icon?.iconType == "MUSIC_SHUFFLE" }
                        ?.menuNavigationItemRenderer?.navigationEndpoint?.watchPlaylistEndpoint,
                    radioEndpoint = renderer.menu?.menuRenderer?.items
                        ?.find { it.menuNavigationItemRenderer?.icon?.iconType == "MIX" }
                        ?.menuNavigationItemRenderer?.navigationEndpoint?.watchPlaylistEndpoint
                )

                renderer.isPodcast -> {
                    val podcastLibraryTokens = PageHelper.extractLibraryTokensFromMenuItems(renderer.menu?.menuRenderer?.items)
                    PodcastItem(
                        id = renderer.navigationEndpoint?.browseEndpoint?.browseId ?: return null,
                        title = renderer.flexColumns.firstOrNull()?.musicResponsiveListItemFlexColumnRenderer?.text?.runs?.firstOrNull()?.text
                            ?: return null,
                        author = renderer.flexColumns.getOrNull(1)?.musicResponsiveListItemFlexColumnRenderer?.text?.runs?.firstOrNull()?.let {
                            Artist(
                                name = it.text,
                                id = it.navigationEndpoint?.browseEndpoint?.browseId
                            )
                        },
                        episodeCountText = renderer.flexColumns.getOrNull(1)?.musicResponsiveListItemFlexColumnRenderer?.text?.runs?.lastOrNull()?.text,
                        thumbnail = renderer.thumbnail?.getThumbnailUrl(),
                        playEndpoint = renderer.overlay?.musicItemThumbnailOverlayRenderer?.content?.musicPlayButtonRenderer?.playNavigationEndpoint?.watchPlaylistEndpoint,
                        shuffleEndpoint = renderer.menu?.menuRenderer?.items
                            ?.find { it.menuNavigationItemRenderer?.icon?.iconType == "MUSIC_SHUFFLE" }
                            ?.menuNavigationItemRenderer?.navigationEndpoint?.watchPlaylistEndpoint,
                        libraryAddToken = podcastLibraryTokens.addToken,
                        libraryRemoveToken = podcastLibraryTokens.removeToken,
                    )
                }

                else -> null
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
