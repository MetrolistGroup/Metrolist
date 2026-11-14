package com.metrolist.shared.ui.navigation

enum class NavigationPattern { Default, Expressive }

fun navigationPattern(
    source: Screen?,
    destination: Screen?,
): NavigationPattern =
    when {
        destination is Screen.Album && source != null &&
            source != Screen.Library && source != Screen.Downloads && source !is Screen.AutoPlaylist &&
            !(source is Screen.Playlist && source.id.startsWith("LP")) -> NavigationPattern.Expressive

        source is Screen.Artist && destination is Screen.AboutArtist && source.id == destination.id -> NavigationPattern.Expressive

        else -> NavigationPattern.Default
    }

data class NavigationChange(
    val source: Screen,
    val destination: Screen,
    val isPop: Boolean = false,
) {
    val pattern: NavigationPattern
        get() = if (isPop) navigationPattern(destination, source) else navigationPattern(source, destination)
}
