package com.metrolist.desktop.ui.screens

import com.metrolist.desktop.history.AlbumStat
import com.metrolist.shared.db.HistoryArtistStatistic
import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.ui.statistics.matchingStatisticsAlbum

internal fun statisticsArtistName(id: String): String =
    id
        .removePrefix("artist:rank:")
        .substringAfter(':')

internal fun statisticsArtistItemId(
    index: Int,
    artistName: String,
    artistId: String?,
): String = artistId?.let { "artist:id:$it" } ?: "artist:rank:$index:$artistName"

internal fun statisticsArtistIndex(id: String): Int? = id.removePrefix("artist:rank:").substringBefore(':').toIntOrNull()

internal fun statisticsArtistStatistic(
    id: String,
    artists: List<HistoryArtistStatistic>,
): HistoryArtistStatistic? =
    if (id.startsWith("artist:id:")) {
        artists.firstOrNull { it.id == id.removePrefix("artist:id:") }
    } else {
        statisticsArtistIndex(id)
            ?.let(artists::getOrNull)
            ?.takeIf { it.name == statisticsArtistName(id) }
    }

internal fun statisticsArtistProfileId(
    statistic: HistoryArtistStatistic?,
    cachedId: String?,
): String? = statistic?.id ?: cachedId

internal fun List<AlbumItem>.matchingStatisticsAlbum(album: AlbumStat): AlbumItem? = matchingStatisticsAlbum(album.name, album.artists)
