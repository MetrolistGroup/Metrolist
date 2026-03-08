package com.metrolist.desktop.ui.screens

import com.metrolist.desktop.history.AlbumStat
import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.ui.statistics.matchingStatisticsAlbum

internal fun statisticsArtistName(id: String): String = id.removePrefix("artist:").substringAfter(':')

internal fun List<AlbumItem>.matchingStatisticsAlbum(album: AlbumStat): AlbumItem? = matchingStatisticsAlbum(album.name, album.artists)
