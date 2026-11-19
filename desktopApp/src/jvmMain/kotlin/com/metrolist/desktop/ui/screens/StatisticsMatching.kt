package com.metrolist.desktop.ui.screens

import com.metrolist.desktop.history.AlbumStat
import com.metrolist.shared.db.HistoryArtistStatistic
import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.ui.statistics.matchingStatisticsAlbum

internal fun statisticsArtistName(id: String): String = id.removePrefix("artist:").substringAfter(':')

internal fun statisticsArtistItemId(
    index: Int,
    artistName: String,
): String = "artist:$index:$artistName"

internal fun statisticsArtistIndex(id: String): Int? = id.removePrefix("artist:").substringBefore(':').toIntOrNull()

internal fun statisticsArtistProfileId(
    statistic: HistoryArtistStatistic?,
    cachedId: String?,
): String? = statistic?.id ?: cachedId

internal fun List<AlbumItem>.matchingStatisticsAlbum(album: AlbumStat): AlbumItem? = matchingStatisticsAlbum(album.name, album.artists)
