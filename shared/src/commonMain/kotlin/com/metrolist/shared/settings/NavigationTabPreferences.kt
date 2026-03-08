package com.metrolist.shared.settings

import com.metrolist.shared.ui.navigation.Screen

private val libraryStartupTabs = setOf("LIBRARY", "SONGS", "TRACKS", "FAVORITES", "ARTISTS", "ALBUMS", "PLAYLISTS")

fun defaultOpenTabRoot(current: String): String =
    when (current) {
        "SEARCH" -> "SEARCH"
        in libraryStartupTabs -> "LIBRARY"
        else -> "HOME"
    }

fun defaultOpenTabScreen(current: String): Screen =
    when (current) {
        "FAVORITES" -> {
            Screen.AutoPlaylist("liked")
        }

        "MYSTERIOUS" -> {
            Screen.EasterEgg
        }

        else -> {
            when (defaultOpenTabRoot(current)) {
                "SEARCH" -> Screen.Search
                "LIBRARY" -> Screen.Library
                else -> Screen.Home
            }
        }
    }

fun normalizeDefaultOpenTab(
    current: String,
    showHome: Boolean,
    showSearch: Boolean,
    showLibrary: Boolean,
): String {
    val tab = current.takeUnless { it == "SONGS" } ?: "LIBRARY"
    val currentCategory =
        when (tab) {
            "HOME" -> 0
            "SEARCH" -> 1
            in libraryStartupTabs -> 2
            else -> -1
        }
    val visible = listOf(showHome, showSearch, showLibrary)
    if (currentCategory in visible.indices && visible[currentCategory]) return tab

    repeat(visible.size) { offset ->
        val index = (currentCategory + offset + 1).mod(visible.size)
        if (visible[index]) {
            return when (index) {
                0 -> "HOME"
                1 -> "SEARCH"
                else -> "LIBRARY"
            }
        }
    }
    return "MYSTERIOUS"
}

fun Settings.updateDefaultOpenTab(tab: String) {
    if (defaultOpenTab != tab) {
        defaultOpenTab = tab
        libraryFilter = LibraryFilterType.DEFAULT
    }
}

fun Settings.desktopSidebarNavItems(): List<NavItem> =
    try {
        sidebarNavItems.split(",").map { item ->
            val parts = item.split(":")
            NavItem(parts[0], parts[1].toBoolean())
        }
    } catch (_: Exception) {
        listOf(
            NavItem(NavigationId.HOME),
            NavItem(NavigationId.LIBRARY),
            NavItem(NavigationId.HISTORY),
            NavItem(NavigationId.STATISTICS),
            NavItem(NavigationId.LISTEN_TOGETHER),
        )
    }

fun Settings.desktopDefaultOpenTabs(): List<String> {
    val visibleIds = desktopSidebarNavItems().filter { it.visible }.map { it.id }
    return buildList {
        if (NavigationId.HOME in visibleIds) add("HOME")
        add("SEARCH")
        if (NavigationId.LIBRARY in visibleIds) addAll(libraryStartupTabs - "SONGS")
    }
}

fun Settings.desktopDefaultOpenTab(): String {
    val tabs = desktopDefaultOpenTabs()
    val current = defaultOpenTab.takeUnless { it == "SONGS" } ?: "LIBRARY"
    return current.takeIf { it in tabs } ?: tabs.first()
}

fun Settings.initialLibraryFilter(): LibraryFilterType =
    when (libraryFilter) {
        LibraryFilterType.DEFAULT -> {
            when (defaultOpenTab) {
                "TRACKS" -> LibraryFilterType.TRACKS
                "ARTISTS" -> LibraryFilterType.ARTISTS
                "ALBUMS" -> LibraryFilterType.ALBUMS
                "PLAYLISTS" -> LibraryFilterType.PLAYLISTS
                else -> LibraryFilterType.ALL
            }
        }

        else -> {
            libraryFilter
        }
    }
