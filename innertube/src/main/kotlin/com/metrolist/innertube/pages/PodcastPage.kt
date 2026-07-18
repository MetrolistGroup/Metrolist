package com.metrolist.innertube.pages

import com.metrolist.innertube.models.Album
import com.metrolist.innertube.models.Artist
import com.metrolist.innertube.models.EpisodeItem
import com.metrolist.innertube.models.MusicMultiRowListItemRenderer
import com.metrolist.innertube.models.MusicResponsiveListItemRenderer
import com.metrolist.innertube.models.PodcastItem
import com.metrolist.innertube.models.splitBySeparator
import com.metrolist.innertube.utils.parseTime

data class PodcastPage(
    val podcast: PodcastItem,
    val episodes: List<EpisodeItem>,
    val continuation: String?,
    val isChannelSubscribed: Boolean = false,
) {
    companion object {
        fun fromMusicMultiRowListItemRenderer(
            renderer: MusicMultiRowListItemRenderer,
            podcast: PodcastItem? = null
        ): EpisodeItem? {
            val subtitleRuns = renderer.subtitle?.runs?.splitBySeparator()
            val libraryTokens = PageHelper.extractLibraryTokensFromMenuItems(renderer.menu?.menuRenderer?.items)

            return EpisodeItem(
                id = renderer.onTap?.watchEndpoint?.videoId ?: return null,
                title = renderer.title?.runs?.firstOrNull()?.text ?: return null,
                author = podcast?.author,
                podcast = podcast?.let {
                    Album(name = it.title, id = it.id)
                },
                duration = subtitleRuns?.lastOrNull()?.firstOrNull()?.text?.parseTime(),
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
            podcast: PodcastItem? = null
        ): EpisodeItem? {
            val secondaryLineRuns = renderer.flexColumns
                .getOrNull(1)
                ?.musicResponsiveListItemFlexColumnRenderer
                ?.text
                ?.runs
                ?.splitBySeparator()
            val libraryTokens = PageHelper.extractLibraryTokensFromMenuItems(renderer.menu?.menuRenderer?.items)

            return EpisodeItem(
                id = renderer.videoId ?: return null,
                title = renderer.flexColumns.firstOrNull()
                    ?.musicResponsiveListItemFlexColumnRenderer?.text
                    ?.runs?.firstOrNull()?.text ?: return null,
                author = podcast?.author ?: secondaryLineRuns?.firstOrNull()?.firstOrNull()?.let {
                    Artist(
                        name = it.text,
                        id = it.navigationEndpoint?.browseEndpoint?.browseId,
                    )
                },
                podcast = podcast?.let {
                    Album(name = it.title, id = it.id)
                },
                duration = secondaryLineRuns?.lastOrNull()?.firstOrNull()?.text?.parseTime(),
                publishDateText = secondaryLineRuns?.getOrNull(1)?.firstOrNull()?.text,
                thumbnail = renderer.thumbnail?.getThumbnailUrl() ?: return null,
                explicit = renderer.badges?.find {
                    it.musicInlineBadgeRenderer?.icon?.iconType == "MUSIC_EXPLICIT_BADGE"
                } != null,
                endpoint = renderer.overlay?.musicItemThumbnailOverlayRenderer?.content?.musicPlayButtonRenderer?.playNavigationEndpoint?.watchEndpoint,
                libraryAddToken = libraryTokens.addToken,
                libraryRemoveToken = libraryTokens.removeToken,
            )
        }
    }
}
