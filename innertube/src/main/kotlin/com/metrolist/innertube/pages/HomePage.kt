package com.metrolist.innertube.pages

import com.metrolist.innertube.models.Album
import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.Artist
import com.metrolist.innertube.models.ArtistItem
import com.metrolist.innertube.models.BrowseEndpoint
import com.metrolist.innertube.models.EpisodeItem
import com.metrolist.innertube.models.MusicCarouselShelfRenderer
import com.metrolist.innertube.models.MusicMultiRowListItemRenderer
import com.metrolist.innertube.models.MusicResponsiveListItemRenderer
import com.metrolist.innertube.models.MusicTwoRowItemRenderer
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.innertube.models.PodcastItem
import com.metrolist.innertube.models.SectionListRenderer
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.YTItem
import com.metrolist.innertube.models.oddElements
import com.metrolist.innertube.models.splitBySeparator
import com.metrolist.innertube.models.filterExplicit
import com.metrolist.innertube.models.filterVideoSongs
import com.metrolist.innertube.utils.parseTime
import timber.log.Timber

data class HomePage(
    val chips: List<Chip>?,
    val sections: List<Section>,
    val continuation: String? = null,
) {
    data class Chip(
        val title: String,
        val endpoint: BrowseEndpoint?,
        val deselectEndPoint: BrowseEndpoint?,
    ) {
        companion object {
            fun fromChipCloudChipRenderer(renderer: SectionListRenderer.Header.ChipCloudRenderer.Chip): Chip? {
                return Chip(
                    title = renderer.chipCloudChipRenderer.text?.runs?.firstOrNull()?.text ?: return null,
                    endpoint = renderer.chipCloudChipRenderer.navigationEndpoint?.browseEndpoint,
                    deselectEndPoint = renderer.chipCloudChipRenderer.onDeselectedCommand?.browseEndpoint,
                )
            }
        }
    }

    data class Section(
        val title: String,
        val label: String?,
        val thumbnail: String?,
        val endpoint: BrowseEndpoint?,
        val items: List<YTItem>,
    ) {
        companion object {
            fun fromMusicCarouselShelfRenderer(renderer: MusicCarouselShelfRenderer): Section? {
                val title = renderer.header?.musicCarouselShelfBasicHeaderRenderer?.title?.runs?.firstOrNull()?.text
                Timber.d("HomePage section title: $title, contents: ${renderer.contents.size}")

                if (title == null) {
                    Timber.d("HomePage section skipped: no title")
                    return null
                }

                val twoRowCount = renderer.contents.count { it.musicTwoRowItemRenderer != null }
                val multiRowCount = renderer.contents.count { it.musicMultiRowListItemRenderer != null }
                val responsiveCount = renderer.contents.count { it.musicResponsiveListItemRenderer != null }
                Timber.d("HomePage section '$title': twoRow=$twoRowCount, multiRow=$multiRowCount, responsive=$responsiveCount")

                val items = mutableListOf<YTItem>()

                // Parse musicTwoRowItemRenderer items (songs, albums, playlists, artists, podcasts)
                renderer.contents.mapNotNull { it.musicTwoRowItemRenderer }
                    .mapNotNull { fromMusicTwoRowItemRenderer(it) }
                    .let { items.addAll(it) }

                // Parse musicMultiRowListItemRenderer items (podcast episodes)
                renderer.contents.mapNotNull { it.musicMultiRowListItemRenderer }
                    .mapNotNull { fromMusicMultiRowListItemRenderer(it) }
                    .let { items.addAll(it) }

                // Parse musicResponsiveListItemRenderer items (quick picks songs)
                renderer.contents.mapNotNull { it.musicResponsiveListItemRenderer }
                    .mapNotNull { fromMusicResponsiveListItemRenderer(it) }
                    .let { items.addAll(it) }

                val podcastCount = items.count { it is PodcastItem }
                val episodeCount = items.count { it is EpisodeItem }
                val songCount = items.count { it is SongItem }
                Timber.d("HomePage section '$title': parsed ${items.size} items (podcasts=$podcastCount, episodes=$episodeCount, songs=$songCount)")

                if (items.isEmpty()) {
                    Timber.d("HomePage section '$title' skipped: no items")
                    return null
                }

                return Section(
                    title = title,
                    label = renderer.header.musicCarouselShelfBasicHeaderRenderer.strapline?.runs?.firstOrNull()?.text,
                    thumbnail = renderer.header.musicCarouselShelfBasicHeaderRenderer.thumbnail?.getThumbnailUrl(),
                    endpoint = renderer.header.musicCarouselShelfBasicHeaderRenderer.moreContentButton?.buttonRenderer?.navigationEndpoint?.browseEndpoint,
                    items = items
                )
            }

            private fun fromMusicMultiRowListItemRenderer(renderer: MusicMultiRowListItemRenderer): EpisodeItem? {
                val subtitleRuns = renderer.subtitle?.runs?.splitBySeparator()
                val libraryTokens = PageHelper.extractLibraryTokensFromMenuItems(renderer.menu?.menuRenderer?.items)

                return EpisodeItem(
                    id = renderer.onTap?.watchEndpoint?.videoId ?: return null,
                    title = renderer.title?.runs?.firstOrNull()?.text ?: return null,
                    author = null,
                    podcast = null,
                    duration = subtitleRuns?.lastOrNull()?.firstOrNull()?.text?.parseTime(),
                    publishDateText = subtitleRuns?.firstOrNull()?.firstOrNull()?.text,
                    thumbnail = renderer.thumbnail?.getThumbnailUrl() ?: return null,
                    explicit = false,
                    endpoint = renderer.onTap.watchEndpoint,
                    libraryAddToken = libraryTokens.addToken,
                    libraryRemoveToken = libraryTokens.removeToken,
                )
            }

            private fun fromMusicResponsiveListItemRenderer(renderer: MusicResponsiveListItemRenderer): SongItem? {
                // Quick picks uses musicResponsiveListItemRenderer for songs
                if (!renderer.isSong) return null

                val secondaryLine = renderer.flexColumns
                    .getOrNull(1)
                    ?.musicResponsiveListItemFlexColumnRenderer
                    ?.text
                    ?.runs
                    ?.splitBySeparator()
                    ?: return null

                return SongItem(
                    id = renderer.videoId ?: return null,
                    title = renderer.flexColumns
                        .firstOrNull()
                        ?.musicResponsiveListItemFlexColumnRenderer
                        ?.text
                        ?.runs
                        ?.firstOrNull()
                        ?.text ?: return null,
                    artists = secondaryLine.getOrNull(0)?.oddElements()?.map {
                        Artist(
                            name = it.text,
                            id = it.navigationEndpoint?.browseEndpoint?.browseId
                        )
                    } ?: return null,
                    album = secondaryLine.getOrNull(1)?.firstOrNull()
                        ?.takeIf { it.navigationEndpoint?.browseEndpoint != null }
                        ?.let {
                            Album(
                                name = it.text,
                                id = it.navigationEndpoint?.browseEndpoint?.browseId!!
                            )
                        },
                    duration = secondaryLine.lastOrNull()?.firstOrNull()?.text?.parseTime(),
                    thumbnail = renderer.thumbnail?.getThumbnailUrl() ?: return null,
                    explicit = renderer.badges?.find {
                        it.musicInlineBadgeRenderer?.icon?.iconType == "MUSIC_EXPLICIT_BADGE"
                    } != null,
                    isEpisode = renderer.isEpisode
                )
            }

            private fun fromMusicTwoRowItemRenderer(renderer: MusicTwoRowItemRenderer): YTItem? {
                // Debug logging for type detection
                val title = renderer.title.runs?.firstOrNull()?.text ?: "unknown"
                val pageType = renderer.navigationEndpoint.browseEndpoint
                    ?.browseEndpointContextSupportedConfigs
                    ?.browseEndpointContextMusicConfig
                    ?.pageType
                val hasWatchEndpoint = renderer.navigationEndpoint.watchEndpoint != null

                if (!renderer.isSong && !renderer.isAlbum && !renderer.isPlaylist && !renderer.isArtist && !renderer.isPodcast && !renderer.isEpisode) {
                    Timber.d("HomePage twoRow '$title': no type matched - pageType=$pageType, hasWatchEndpoint=$hasWatchEndpoint")
                }

                // Debug for episodes
                if (renderer.isEpisode) {
                    val overlayVideoId = renderer.thumbnailOverlay
                        ?.musicItemThumbnailOverlayRenderer?.content
                        ?.musicPlayButtonRenderer?.playNavigationEndpoint
                        ?.watchEndpoint?.videoId
                    val browseId = renderer.navigationEndpoint.browseEndpoint?.browseId
                    Timber.d("HomePage episode '$title': overlayVideoId=$overlayVideoId, browseId=$browseId")
                }

                return when {
                    renderer.isSong -> {
                        val subtitleRuns = renderer.subtitle?.runs ?: return null
                        val artistRuns = subtitleRuns.filter {
                            it.navigationEndpoint?.browseEndpoint?.browseId?.startsWith("MPREb_") != true
                        }
                        val title = renderer.title.runs?.firstOrNull()?.text ?: return null
                        val videoId = renderer.navigationEndpoint.watchEndpoint?.videoId ?: return null
                        val artists = PageHelper.extractArtists(artistRuns)

                        SongItem(
                            id = videoId,
                            title = title,
                            artists = artists,
                            album = subtitleRuns.firstOrNull {
                                it.navigationEndpoint?.browseEndpoint?.browseId?.startsWith("MPREb_") == true
                            }?.let {
                                Album(
                                    name = it.text,
                                    id = it.navigationEndpoint?.browseEndpoint?.browseId ?: return@let null
                                )
                            },
                            duration = null,
                            thumbnail = renderer.thumbnailRenderer.getThumbnailUrl()
                                ?: return null,
                            explicit = renderer.subtitleBadges?.any {
                                it.musicInlineBadgeRenderer?.icon?.iconType == "MUSIC_EXPLICIT_BADGE"
                            } == true
                        )
                    }
                    renderer.isAlbum -> {
                        AlbumItem(
                            browseId = renderer.navigationEndpoint.browseEndpoint?.browseId ?: return null,
                            playlistId = renderer.thumbnailOverlay?.musicItemThumbnailOverlayRenderer?.content
                                ?.musicPlayButtonRenderer?.playNavigationEndpoint
                                ?.watchPlaylistEndpoint?.playlistId ?: return null,
                            title = renderer.title.runs?.firstOrNull()?.text ?: return null,
                            artists = renderer.subtitle?.runs?.oddElements()?.drop(1)?.map {
                                Artist(
                                    name = it.text,
                                    id = it.navigationEndpoint?.browseEndpoint?.browseId
                                )
                            },
                            year = null,
                            thumbnail = renderer.thumbnailRenderer.getThumbnailUrl() ?: return null,
                            explicit = renderer.subtitleBadges?.find {
                                it.musicInlineBadgeRenderer?.icon?.iconType == "MUSIC_EXPLICIT_BADGE"
                            } != null
                        )
                    }

                    renderer.isPlaylist -> {
                        PlaylistItem(
                            id = renderer.navigationEndpoint.browseEndpoint?.browseId?.removePrefix("VL") ?: return null,
                            title = renderer.title.runs?.firstOrNull()?.text ?: return null,
                            author = Artist(
                                name = renderer.subtitle?.runs?.firstOrNull()?.text ?: return null,
                                id = null
                            ),
                            songCountText = null,
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
                            }?.menuNavigationItemRenderer?.navigationEndpoint?.watchPlaylistEndpoint
                        )
                    }

                    renderer.isArtist -> {
                        ArtistItem(
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
                    }

                    renderer.isPodcast -> {
                        PodcastItem(
                            id = renderer.navigationEndpoint.browseEndpoint?.browseId ?: return null,
                            title = renderer.title.runs?.firstOrNull()?.text ?: return null,
                            author = renderer.subtitle?.runs?.firstOrNull()?.let {
                                Artist(
                                    name = it.text,
                                    id = it.navigationEndpoint?.browseEndpoint?.browseId
                                )
                            },
                            episodeCountText = null,
                            thumbnail = renderer.thumbnailRenderer.getThumbnailUrl(),
                            playEndpoint = renderer.thumbnailOverlay
                                ?.musicItemThumbnailOverlayRenderer?.content
                                ?.musicPlayButtonRenderer?.playNavigationEndpoint
                                ?.watchPlaylistEndpoint,
                            shuffleEndpoint = renderer.menu?.menuRenderer?.items?.find {
                                it.menuNavigationItemRenderer?.icon?.iconType == "MUSIC_SHUFFLE"
                            }?.menuNavigationItemRenderer?.navigationEndpoint?.watchPlaylistEndpoint,
                        )
                    }

                    renderer.isEpisode -> {
                        val videoId = renderer.thumbnailOverlay
                            ?.musicItemThumbnailOverlayRenderer?.content
                            ?.musicPlayButtonRenderer?.playNavigationEndpoint
                            ?.watchEndpoint?.videoId
                        val titleText = renderer.title.runs?.firstOrNull()?.text
                        val thumbnail = renderer.thumbnailRenderer.getThumbnailUrl()

                        if (videoId == null || titleText == null || thumbnail == null) {
                            Timber.d("HomePage episode FAILED: videoId=$videoId, title=$titleText, thumbnail=$thumbnail")
                            return null
                        }

                        val subtitleRuns = renderer.subtitle?.runs?.splitBySeparator()
                        val libraryTokens = PageHelper.extractLibraryTokensFromMenuItems(renderer.menu?.menuRenderer?.items)

                        // Find podcast link in subtitle (has isPodcastEndpoint)
                        val podcastRun = renderer.subtitle?.runs?.find {
                            it.navigationEndpoint?.browseEndpoint?.isPodcastEndpoint == true
                        }
                        val podcastAlbum = podcastRun?.let {
                            Album(
                                name = it.text,
                                id = it.navigationEndpoint?.browseEndpoint?.browseId ?: return@let null
                            )
                        }

                        Timber.d("HomePage episode SUCCESS: '$titleText', podcast: ${podcastAlbum?.name}")
                        EpisodeItem(
                            id = videoId,
                            title = titleText,
                            author = subtitleRuns?.firstOrNull()?.firstOrNull()?.let {
                                Artist(
                                    name = it.text,
                                    id = it.navigationEndpoint?.browseEndpoint?.browseId
                                )
                            },
                            podcast = podcastAlbum,
                            duration = subtitleRuns?.lastOrNull()?.firstOrNull()?.text?.parseTime(),
                            publishDateText = subtitleRuns?.getOrNull(1)?.firstOrNull()?.text,
                            thumbnail = thumbnail,
                            explicit = renderer.subtitleBadges?.any {
                                it.musicInlineBadgeRenderer?.icon?.iconType == "MUSIC_EXPLICIT_BADGE"
                            } == true,
                            endpoint = renderer.thumbnailOverlay
                                .musicItemThumbnailOverlayRenderer.content
                                .musicPlayButtonRenderer.playNavigationEndpoint
                                .watchEndpoint,
                            libraryAddToken = libraryTokens.addToken,
                            libraryRemoveToken = libraryTokens.removeToken,
                        )
                    }

                    else -> null
                }
            }
        }
    }

    fun filterExplicit(enabled: Boolean = true) =
        if (enabled) {
            copy(sections = sections.map {
                it.copy(items = it.items.filterExplicit())
            })
        } else this

    fun filterVideoSongs(disableVideos: Boolean = false) =
        if (disableVideos) {
            copy(sections = sections.map { section ->
                section.copy(items = section.items.filterVideoSongs(true))
            })
        } else this
}
