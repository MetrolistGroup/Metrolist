package com.sydforum.musicplay.innertube.pages

import com.sydforum.musicplay.innertube.models.YTItem

data class ArtistItemsContinuationPage(
    val items: List<YTItem>,
    val continuation: String?,
)
