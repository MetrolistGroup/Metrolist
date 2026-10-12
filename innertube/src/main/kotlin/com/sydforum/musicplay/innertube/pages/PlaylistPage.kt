package com.sydforum.musicplay.innertube.pages

import com.sydforum.musicplay.innertube.models.Album
import com.sydforum.musicplay.innertube.models.Artist
import com.sydforum.musicplay.innertube.models.MusicResponsiveListItemRenderer
import com.sydforum.musicplay.innertube.models.PlaylistItem
import com.sydforum.musicplay.innertube.models.Run
import com.sydforum.musicplay.innertube.models.SongItem
import com.sydforum.musicplay.innertube.models.splitBySeparator
import com.sydforum.musicplay.innertube.utils.parseTime
import timber.log.Timber

data class PlaylistPage(
    val playlist: PlaylistItem,
    val songs: List<SongItem>,
    val songsContinuation: String?,
    val continuation: String?,
) {
    companion object {
        fun ownerFromDetailSubtitle(runs: List<Run>?): Artist? = ownerFromByline(runs?.splitBySeparator()?.getOrNull(1))

        fun ownerFromByline(runs: List<Run>?): Artist? =
            PageHelper.extractArtists(runs).firstOrNull()
                ?: runs
                    ?.splitBySeparator()
                    ?.firstOrNull()
                    ?.firstOrNull()
                    ?.takeIf { it.navigationEndpoint == null && it.text.isNotBlank() }
                    ?.let { Artist(it.text.trim(), null) }

        fun fromMusicResponsiveListItemRenderer(renderer: MusicResponsiveListItemRenderer): SongItem? {
            val libraryTokens = PageHelper.extractLibraryTokensFromMenuItems(renderer.menu?.menuRenderer?.items)

            val secondaryLineRuns =
                renderer.flexColumns
                    .getOrNull(1)
                    ?.musicResponsiveListItemFlexColumnRenderer
                    ?.text
                    ?.runs

            val title =
                renderer.flexColumns
                    .firstOrNull()
                    ?.musicResponsiveListItemFlexColumnRenderer
                    ?.text
                    ?.runs
                    ?.firstOrNull()
                    ?.text ?: return null

            if (secondaryLineRuns == null) {
                Timber.w("PlaylistPage.fromMusicResponsiveListItemRenderer: Song '$title' - NO SECONDARY LINE (flexColumns[1] is null)")
            }

            val artists = PageHelper.extractArtists(secondaryLineRuns)

            return SongItem(
                id = renderer.videoId ?: return null,
                title = title,
                artists = artists,
                album =
                    renderer.flexColumns.getOrNull(2)?.musicResponsiveListItemFlexColumnRenderer?.text?.runs?.firstOrNull()?.let {
                        Album(
                            name = it.text,
                            id = it.navigationEndpoint?.browseEndpoint?.browseId ?: return@let null,
                        )
                    },
                duration =
                    renderer.fixedColumns
                        ?.firstOrNull()
                        ?.musicResponsiveListItemFlexColumnRenderer
                        ?.text
                        ?.runs
                        ?.firstOrNull()
                        ?.text
                        ?.parseTime(),
                musicVideoType = renderer.musicVideoType,
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
                setVideoId = renderer.playlistSetVideoId ?: return null,
                libraryAddToken = libraryTokens.addToken,
                libraryRemoveToken = libraryTokens.removeToken,
                isEpisode = renderer.isEpisode,
            )
        }
    }
}
