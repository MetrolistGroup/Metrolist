package com.sydforum.musicplay.innertube.pages

import com.sydforum.musicplay.innertube.models.SongItem

data class PlaylistContinuationPage(
    val songs: List<SongItem>,
    val continuation: String?,
)
