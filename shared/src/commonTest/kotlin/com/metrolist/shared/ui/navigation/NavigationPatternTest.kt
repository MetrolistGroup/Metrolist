package com.metrolist.shared.ui.navigation

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.TransformOrigin
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NavigationPatternTest {
    @Test
    fun albumPagesKeepFastMotionAndShareOnlyTheVisibleSourceInBothDirections() {
        val containers = NavigationContainerState()
        val artist = Screen.Artist("artist")
        val album = Screen.Album("album", artworkSource = "card")
        val key = albumArtworkKey(album.id, "card")
        for (visible in listOf(false, true, false)) {
            containers.albumArtworkVisibility[key] = visible
            val expected = if (visible) NavigationPattern.Expressive else NavigationPattern.Default
            assertEquals(NavigationPattern.Default, containers.pagePattern(artist, album))
            containers.navigate(NavigationChange(artist, album))
            assertEquals(expected, containers.elementPattern)
            containers.navigate(NavigationChange(album, artist, isPop = true))
            assertEquals(NavigationPattern.Default, containers.elementPattern)
            containers.albumArtworkVisibility[key] = visible
            assertEquals(expected, containers.elementPattern)
        }
        containers.albumArtworkVisibility[key] = true
        for ((source, destination, expected) in listOf(
            Triple(artist, album.copy(artworkSource = "other"), NavigationPattern.Default),
            Triple(artist, album.copy(artworkSource = null), NavigationPattern.Default),
            Triple(Screen.Library, album, NavigationPattern.Default),
            Triple(artist, Screen.AboutArtist("artist", "name"), NavigationPattern.Expressive),
        )) {
            containers.navigate(NavigationChange(source, destination))
            assertEquals(expected, containers.elementPattern)
        }
    }

    @Test
    fun navigationInterruptionsReleaseOnlyAfterTheNewTransitionSettles() {
        val stack = NavBackStack<NavKey>(Screen.Library)
        val state = NavigationState(Screen.Library, mutableStateOf(Screen.Library), mapOf(Screen.Library to stack))
        val navigator = Navigator(state)
        val album = Screen.Album("album")
        navigator.navigate(album)
        val input = state.containers.input
        input.beginBack()
        input.cancelBack()
        assertFalse(input.acceptsInput(transitionSettled = false))
        navigator.navigate(Screen.Artist("artist"))
        assertFalse(input.acceptsInput(transitionSettled = false))
        assertTrue(input.acceptsInput(transitionSettled = true))
        input.beginBack()
        assertTrue(navigator.goBack())
        assertFalse(input.acceptsInput(transitionSettled = false))
        assertTrue(input.acceptsInput(transitionSettled = true))
    }

    @Test
    fun onlyNonLibraryAlbumsAndTheMatchingArtistAboutEdgeAreExpressive() {
        val album = Screen.Album("album")
        val artist = Screen.Artist("artist")
        for (source in listOf(
            Screen.Home,
            Screen.Search,
            artist,
            Screen.Browse("browse"),
            Screen.HomeSection("section", "title"),
            album,
            Screen.History,
            Screen.Statistics,
            Screen.Playlist("VLremote"),
        )) {
            assertEquals(NavigationPattern.Expressive, navigationPattern(source, album), source.toString())
            assertEquals(NavigationPattern.Expressive, NavigationChange(album, source, isPop = true).pattern)
            for (plain in listOf(Screen.Artist("other"), Screen.Playlist("VLremote"), Screen.Settings, Screen.Browse("browse"))) {
                assertEquals(NavigationPattern.Default, navigationPattern(source, plain))
            }
        }
        for (source in listOf(
            Screen.Library,
            Screen.Downloads,
            Screen.AutoPlaylist("liked"),
            Screen.AutoPlaylist("downloaded"),
            Screen.Playlist("LP1"),
        )) {
            assertEquals(NavigationPattern.Default, navigationPattern(source, album))
            assertEquals(NavigationPattern.Default, NavigationChange(album, source, isPop = true).pattern)
        }
        val about = Screen.AboutArtist("artist", "name")
        assertEquals(NavigationPattern.Expressive, navigationPattern(artist, about))
        assertEquals(NavigationPattern.Expressive, NavigationChange(about, artist, isPop = true).pattern)
        assertEquals(NavigationPattern.Default, navigationPattern(Screen.Artist("other"), about))
        assertEquals(NavigationPattern.Default, navigationPattern(Screen.Home, about))
        assertEquals(NavigationPattern.Default, navigationPattern(null, album))
    }

    @Test
    fun backAndForwardKeepTheOriginalAlbumSourceAndLibraryChoice() {
        val stack = NavBackStack<NavKey>(Screen.Home)
        val state = NavigationState(Screen.Home, mutableStateOf(Screen.Home), mapOf(Screen.Home to stack))
        val navigator = Navigator(state)
        val origin = TransformOrigin(0.2f, 0.6f)
        val album = Screen.Album("album")
        state.containers.pendingPageOrigin = origin
        navigator.navigate(album)
        assertEquals(origin, state.containers.pageOrigin(album))
        assertEquals(NavigationPattern.Expressive, state.containers.change?.pattern)
        navigator.goBack()
        assertEquals(origin, state.containers.pageOrigin(album))
        assertEquals(NavigationPattern.Expressive, state.containers.change?.pattern)
        assertTrue(state.containers.change?.isPop == true)
        navigator.goForward()
        assertEquals(origin, state.containers.pageOrigin(album))
        assertEquals(NavigationPattern.Expressive, state.containers.change?.pattern)
    }

    @Test
    fun albumArtworkSourcesAreConsumedOnlyOutsideLibrary() {
        for (root in listOf(Screen.Home, Screen.Library)) {
            val stack = NavBackStack<NavKey>(root)
            val state = NavigationState(root, mutableStateOf(root), mapOf(root to stack))
            val navigator = Navigator(state)
            state.containers.pendingAlbumArtworkSource = "card"
            navigator.navigate(Screen.Album("album"))
            val album = state.currentRoute as Screen.Album
            assertEquals(if (root == Screen.Home) "card" else null, album.artworkSource)
            assertEquals(null, state.containers.pendingAlbumArtworkSource)
            navigator.goBack()
            navigator.navigate(Screen.Album("another"))
            assertEquals(null, (state.currentRoute as Screen.Album).artworkSource)
        }
    }

    @Test
    fun aboutArtistCancelCompletionAndInterruptionReleaseTheRetainedPage() {
        val stack = NavBackStack<NavKey>(Screen.Home)
        val state = NavigationState(Screen.Home, mutableStateOf(Screen.Home), mapOf(Screen.Home to stack))
        val navigator = Navigator(state)
        val artist = Screen.Artist("artist")
        val about = Screen.AboutArtist("artist", "name")
        navigator.navigate(artist)
        navigator.navigate(about)
        val input = state.containers.input
        input.beginBack()
        assertFalse(input.acceptsInput(transitionSettled = true))
        input.cancelBack()
        assertFalse(input.acceptsInput(transitionSettled = false))
        assertTrue(input.acceptsInput(transitionSettled = true))
        assertEquals(about, state.currentRoute)
        input.beginBack()
        assertTrue(navigator.goBack())
        assertEquals(artist, state.currentRoute)
        assertEquals(NavigationPattern.Expressive, state.containers.change?.pattern)
        assertFalse(input.acceptsInput(transitionSettled = false))
        assertTrue(input.acceptsInput(transitionSettled = true))
        navigator.navigate(about)
        input.beginBack()
        navigator.navigate(Screen.Search)
        assertEquals(Screen.Search, state.currentRoute)
        assertFalse(input.acceptsInput(transitionSettled = false))
        assertTrue(input.acceptsInput(transitionSettled = true))
    }

    @Test
    fun poppedArtistCannotOpenAnAlbumDuringItsReturnAnimation() {
        val stack = NavBackStack<NavKey>(Screen.Library)
        val state = NavigationState(Screen.Library, mutableStateOf(Screen.Library), mapOf(Screen.Library to stack))
        val navigator = Navigator(state)
        val artist = Screen.Artist("artist")
        navigator.navigate(artist)
        val albumTap = { navigator.navigateFrom(artist, Screen.Album("last-album")) }

        assertTrue(navigator.goBack())
        albumTap()
        assertEquals(Screen.Library, state.currentRoute)
        assertTrue(navigator.canGoForward)

        navigator.navigate(artist)
        assertEquals(artist, state.currentRoute)
        albumTap()
        assertEquals(Screen.Album("last-album"), state.currentRoute)
    }

    @Test
    fun anotherLibraryVisitClearsThePoppedForwardRoute() {
        val stack = NavBackStack<NavKey>(Screen.Library)
        val state = NavigationState(Screen.Library, mutableStateOf(Screen.Library), mapOf(Screen.Library to stack))
        val navigator = Navigator(state)
        navigator.navigate(Screen.Artist("artist"))
        assertTrue(navigator.goBack())

        navigator.navigate(Screen.Album("album"))
        assertEquals(Screen.Album("album"), state.currentRoute)
        assertFalse(navigator.canGoForward)
        assertTrue(navigator.goBack())
        assertEquals(Screen.Library, state.currentRoute)
    }

    @Test
    fun libraryAutoPlaylistsRetainBackAndForwardHistory() {
        val stack = NavBackStack<NavKey>(Screen.Library)
        val state = NavigationState(Screen.Library, mutableStateOf(Screen.Library), mapOf(Screen.Library to stack))
        val navigator = Navigator(state)
        for (type in listOf("liked", "downloaded", "uploaded", "cached", "top100")) {
            val route = Screen.AutoPlaylist(type)
            navigator.navigate(route)
            navigator.navigate(route)
            assertEquals(listOf(Screen.Library, route), stack.toList())
            assertTrue(navigator.goBack())
            assertEquals(Screen.Library, state.currentRoute)
            assertTrue(navigator.goForward())
            assertEquals(route, state.currentRoute)
            assertTrue(navigator.goBack())
        }
    }

    @Test
    fun switchingTabsRestoresTheirDetailStacks() {
        val home = NavBackStack<NavKey>(Screen.Home)
        val library = NavBackStack<NavKey>(Screen.Library)
        val state = NavigationState(Screen.Home, mutableStateOf(Screen.Home), mapOf(Screen.Home to home, Screen.Library to library))
        val navigator = Navigator(state)
        navigator.navigate(Screen.Artist("artist"))
        navigator.navigate(Screen.Library)
        navigator.navigate(Screen.AutoPlaylist("liked"))
        assertEquals(Screen.Library, navigator.previousRoute)
        navigator.navigate(Screen.Home)
        assertEquals(Screen.Artist("artist"), state.currentRoute)
        assertEquals(Screen.Home, navigator.previousRoute)
        navigator.navigate(Screen.Library)
        assertEquals(Screen.AutoPlaylist("liked"), state.currentRoute)
        assertTrue(navigator.goBack())
        assertEquals(Screen.Library, state.currentRoute)
    }
}
