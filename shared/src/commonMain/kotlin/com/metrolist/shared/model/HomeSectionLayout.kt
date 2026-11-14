package com.metrolist.shared.model

enum class HomeSectionLayout {
    GRID,
    VIDEOS,
    LIST,
}

fun List<YTItem>.homeSectionLayout(): HomeSectionLayout =
    when {
        isVideoShelf() -> HomeSectionLayout.VIDEOS
        isNotEmpty() && all { it is PlaylistItem || it is AlbumItem || it is PodcastItem || it is ArtistItem } -> HomeSectionLayout.GRID
        else -> HomeSectionLayout.LIST
    }
