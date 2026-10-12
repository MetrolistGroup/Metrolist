package com.sydforum.musicplay.innertube.pages

import com.sydforum.musicplay.innertube.models.AlbumItem

data class ExplorePage(
    val newReleaseAlbums: List<AlbumItem>,
    val moodAndGenres: List<MoodAndGenres.Item>,
)
