package com.metrolist.shared.ui.navigation

import androidx.navigation3.runtime.NavKey
import com.metrolist.shared.model.ArtistItem
import com.metrolist.shared.settings.NavigationId
import com.metrolist.shared.ui.statistics.StatisticsDateRange
import com.metrolist.shared.ui.statistics.StatisticsPeriodMode
import kotlinx.serialization.Serializable
import com.metrolist.shared.model.HomeSection as HomeSectionData

@Serializable
data class AlbumSeed(
    val id: String,
    val title: String,
    val thumbnail: String? = null,
)

@Serializable
sealed class Screen : NavKey {
    @Serializable
    data object Home : Screen()

    @Serializable
    data object Library : Screen()

    @Serializable
    data object Search : Screen()

    @Serializable
    data object EasterEgg : Screen()

    @Serializable
    data object Settings : Screen()

    @Serializable
    data object Statistics : Screen()

    @Serializable
    data class StatisticsDetail(
        val detail: String,
        val period: String,
        val dateRange: StatisticsDateRange? = null,
        val mode: StatisticsPeriodMode = StatisticsPeriodMode.CONTINUOUS,
    ) : Screen()

    @Serializable
    data object Recognition : Screen()

    @Serializable
    data object RecognitionHistory : Screen()

    @Serializable
    data object Login : Screen()

    @Serializable
    data object TokenLogin : Screen()

    @Serializable
    data object SwitchChannel : Screen()

    @Serializable
    data class Album(
        val id: String,
        val gallery: List<AlbumSeed> = emptyList(),
        val artworkSource: String? = null,
    ) : Screen()

    @Serializable
    data class Artist(
        val id: String,
    ) : Screen()

    @Serializable
    data class AboutArtist(
        val id: String,
        val name: String,
        val artist: ArtistItem? = null,
    ) : Screen()

    @Serializable
    data class Playlist(
        val id: String,
        val galleryEnabled: Boolean = false,
        val thumbnailHint: String? = null,
    ) : Screen()

    @Serializable
    data class Podcast(
        val id: String,
    ) : Screen()

    @Serializable
    data class HomeSection(
        val key: String,
        val title: String,
        val section: HomeSectionData? = null,
    ) : Screen()

    @Serializable
    data class Browse(
        val browseId: String,
        val params: String? = null,
        val title: String? = null,
        val startVideo: Boolean = false,
    ) : Screen()

    @Serializable
    data object Integrations : Screen()

    @Serializable
    data object Discord : Screen()

    @Serializable
    data object LastFm : Screen()

    @Serializable
    data object ListenBrainz : Screen()

    @Serializable
    data object ListenTogether : Screen()

    @Serializable
    data object ListenTogetherSettings : Screen()

    @Serializable
    data object ListenTogetherServer : Screen()

    @Serializable
    data object ListenTogetherBlockedUsers : Screen()

    @Serializable
    data object ListenTogetherLogs : Screen()

    @Serializable
    data object History : Screen()

    @Serializable
    data object AndroidAuto : Screen()

    @Serializable
    data object Storage : Screen()

    @Serializable
    data object Downloads : Screen()

    @Serializable
    data object BackupRestore : Screen()

    @Serializable
    data object PlaylistImport : Screen()

    @Serializable
    data object Updates : Screen()

    @Serializable
    data object UpdateDetails : Screen()

    @Serializable
    data object About : Screen()

    @Serializable
    data object Devtools : Screen()

    @Serializable
    data object PlaybackBenchmark : Screen()

    @Serializable
    data class AutoPlaylist(
        val type: String,
    ) : Screen()
}

val Screen.navigationId: String?
    get() =
        when (this) {
            Screen.Home -> NavigationId.HOME
            Screen.Library -> NavigationId.LIBRARY
            Screen.History -> NavigationId.HISTORY
            Screen.Statistics -> NavigationId.STATISTICS
            Screen.ListenTogether -> NavigationId.LISTEN_TOGETHER
            else -> null
        }

fun screenForNavigationId(id: String): Screen? =
    when (id) {
        NavigationId.HOME -> Screen.Home
        NavigationId.LIBRARY -> Screen.Library
        NavigationId.HISTORY -> Screen.History
        NavigationId.STATISTICS -> Screen.Statistics
        NavigationId.LISTEN_TOGETHER -> Screen.ListenTogether
        else -> null
    }

fun visibleSidebarScreens(configuration: String): List<Screen> =
    configuration
        .split(',')
        .mapNotNull { item ->
            val parts = item.split(':')
            if (parts.getOrNull(1)?.toBooleanStrictOrNull() != true) return@mapNotNull null
            screenForNavigationId(parts[0])
        }.distinct()
