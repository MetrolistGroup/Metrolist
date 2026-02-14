package com.metrolist.innertube.pages

import com.metrolist.innertube.models.YTItem

data class LibraryContinuationPage(
    val items: List<YTItem>,
    val continuation: String?,
)
