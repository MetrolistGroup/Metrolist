package com.metrolist.innertube.pages

import com.metrolist.innertube.models.Album
import com.metrolist.innertube.models.Artist
import com.metrolist.innertube.models.EpisodeItem
import com.metrolist.innertube.models.MusicMultiRowListItemRenderer
import com.metrolist.innertube.models.MusicResponsiveListItemRenderer
import com.metrolist.innertube.models.PodcastItem
import com.metrolist.innertube.models.Run
import com.metrolist.innertube.models.splitBySeparator
import com.metrolist.innertube.utils.parseTime

data class PodcastPage(
    val podcast: PodcastItem,
    val episodes: List<EpisodeItem>,
    val continuation: String?,
    val isChannelSubscribed: Boolean = false,
) {
    companion object {
        fun extractPodcastByline(runs: List<Run>?): List<Artist> {
            val linked = PageHelper.extractArtists(runs)
            if (linked.isNotEmpty()) return linked

            val podcast =
                runs?.firstOrNull {
                    it.text.isNotBlank() && it.navigationEndpoint?.browseEndpoint?.isPodcastEndpoint == true
                }
            if (podcast != null) return listOf(Artist(podcast.text.trim(), null))

            val channel = runs?.splitBySeparator()?.firstOrNull()?.firstOrNull()
            return channel
                ?.takeIf { it.text.isNotBlank() && it.navigationEndpoint == null }
                ?.let { listOf(Artist(it.text.trim(), null)) }
                .orEmpty()
        }

        fun extractPodcastArtists(
            renderer: MusicMultiRowListItemRenderer,
            sectionTitle: List<Run>? = null,
        ): List<Artist> {
            val runs = renderer.secondSubtitle?.runs.orEmpty() + renderer.secondarySubtitle?.runs.orEmpty() + sectionTitle.orEmpty()
            return PageHelper
                .extractArtists(runs)
                .ifEmpty {
                    runs.mapNotNull { run ->
                        run
                            .takeIf { it.text.isNotBlank() && it.navigationEndpoint?.browseEndpoint?.isPodcastEndpoint == true }
                            ?.let { Artist(it.text.trim(), null) }
                    }
                }.ifEmpty {
                    val hasPodcastMenu =
                        renderer.menu?.menuRenderer?.items?.any {
                            it.menuNavigationItemRenderer
                                ?.navigationEndpoint
                                ?.browseEndpoint
                                ?.isPodcastEndpoint == true
                        } == true
                    if (!hasPodcastMenu) return@ifEmpty emptyList()
                    // Only the dedicated single-run podcast byline is trusted when the show link lives in the menu.
                    listOf(renderer.secondSubtitle, renderer.secondarySubtitle)
                        .firstNotNullOfOrNull { subtitle ->
                            subtitle
                                ?.runs
                                ?.singleOrNull()
                                ?.takeIf { it.navigationEndpoint == null && it.text.isNotBlank() }
                                ?.let { Artist(it.text.trim(), null) }
                        }?.let(::listOf)
                        .orEmpty()
                }
        }

        fun fromMusicMultiRowListItemRenderer(
            renderer: MusicMultiRowListItemRenderer,
            podcast: PodcastItem? = null,
        ): EpisodeItem? {
            val subtitleRuns = renderer.subtitle?.runs?.splitBySeparator()
            val libraryTokens = PageHelper.extractLibraryTokensFromMenuItems(renderer.menu?.menuRenderer?.items)

            return EpisodeItem(
                id = renderer.onTap?.watchEndpoint?.videoId ?: return null,
                title =
                    renderer.title
                        ?.runs
                        ?.firstOrNull()
                        ?.text ?: return null,
                author = podcast?.author,
                podcast =
                    podcast?.let {
                        Album(name = it.title, id = it.id)
                    },
                duration =
                    subtitleRuns
                        ?.lastOrNull()
                        ?.firstOrNull()
                        ?.text
                        ?.parseTime(),
                publishDateText = subtitleRuns?.firstOrNull()?.firstOrNull()?.text,
                thumbnail = renderer.thumbnail?.getThumbnailUrl() ?: return null,
                explicit = false,
                endpoint = renderer.onTap.watchEndpoint,
                libraryAddToken = libraryTokens.addToken,
                libraryRemoveToken = libraryTokens.removeToken,
            )
        }

        fun fromMusicResponsiveListItemRenderer(
            renderer: MusicResponsiveListItemRenderer,
            podcast: PodcastItem? = null,
        ): EpisodeItem? {
            val secondaryLineRuns =
                renderer.flexColumns
                    .getOrNull(1)
                    ?.musicResponsiveListItemFlexColumnRenderer
                    ?.text
                    ?.runs
                    ?.splitBySeparator()
            val libraryTokens = PageHelper.extractLibraryTokensFromMenuItems(renderer.menu?.menuRenderer?.items)

            return EpisodeItem(
                id = renderer.videoId ?: return null,
                title =
                    renderer.flexColumns
                        .firstOrNull()
                        ?.musicResponsiveListItemFlexColumnRenderer
                        ?.text
                        ?.runs
                        ?.firstOrNull()
                        ?.text ?: return null,
                author = podcast?.author ?: extractPodcastByline(secondaryLineRuns?.firstOrNull()).firstOrNull(),
                podcast =
                    podcast?.let {
                        Album(name = it.title, id = it.id)
                    },
                duration =
                    secondaryLineRuns
                        ?.lastOrNull()
                        ?.firstOrNull()
                        ?.text
                        ?.parseTime(),
                publishDateText = secondaryLineRuns?.getOrNull(1)?.firstOrNull()?.text,
                thumbnail = renderer.thumbnail?.getThumbnailUrl() ?: return null,
                explicit =
                    renderer.badges?.find {
                        it.musicInlineBadgeRenderer?.icon?.iconType == "MUSIC_EXPLICIT_BADGE"
                    } != null,
                endpoint =
                    renderer.overlay
                        ?.musicItemThumbnailOverlayRenderer
                        ?.content
                        ?.musicPlayButtonRenderer
                        ?.playNavigationEndpoint
                        ?.watchEndpoint,
                libraryAddToken = libraryTokens.addToken,
                libraryRemoveToken = libraryTokens.removeToken,
            )
        }
    }
}
