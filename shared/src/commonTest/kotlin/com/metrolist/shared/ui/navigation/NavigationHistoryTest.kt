package com.metrolist.shared.ui.navigation

import androidx.compose.runtime.mutableStateOf
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NavigationHistoryTest {
    private val home = NavBackStack<NavKey>(Screen.Home)
    private val library = NavBackStack<NavKey>(Screen.Library)
    private val state = NavigationState(Screen.Home, mutableStateOf(Screen.Home), mapOf(Screen.Home to home, Screen.Library to library))
    private val navigator = Navigator(state)

    @Test
    fun poppedDetailsRetainTheirKeysUntilForwardHistoryIsDiscarded() {
        val playlist = Screen.Playlist("playlist")
        val album = Screen.Album("album")
        navigator.navigate(playlist)
        navigator.navigate(album)

        assertTrue(navigator.goBack())
        assertTrue(navigator.goBack())
        assertEquals(listOf(Screen.Home), home.toList())
        assertEquals(listOf(Screen.Home, playlist, album), state.retainedRoutes(Screen.Home))

        assertTrue(navigator.goForward())
        assertEquals(listOf(Screen.Home, playlist), home.toList())
        assertEquals(listOf(Screen.Home, playlist, album), state.retainedRoutes(Screen.Home))

        val artist = Screen.Artist("artist")
        navigator.navigate(artist)
        assertFalse(navigator.canGoForward)
        assertEquals(listOf(Screen.Home, playlist, artist), state.retainedRoutes(Screen.Home))
    }

    @Test
    fun switchingTabsDiscardsForwardKeysButKeepsLiveDetailsInOtherTabs() {
        val playlist = Screen.Playlist("playlist")
        navigator.navigate(playlist)
        navigator.navigate(Screen.Library)
        val browse = Screen.Browse("browse")
        navigator.navigate(browse)
        assertTrue(navigator.goBack())

        assertEquals(listOf(Screen.Home, playlist), state.retainedRoutes(Screen.Home))
        assertEquals(listOf(Screen.Library, browse), state.retainedRoutes(Screen.Library))
        navigator.navigate(Screen.Home)
        assertEquals(listOf(Screen.Home, playlist), state.retainedRoutes(Screen.Home))
        assertEquals(listOf(Screen.Library), state.retainedRoutes(Screen.Library))
    }

    @Test
    fun returningAcrossRootsKeepsForwardDetailsWithTheirOriginalStack() {
        val playlist = Screen.Playlist("same-playlist")
        navigator.navigate(playlist)
        navigator.navigate(Screen.Library)
        navigator.navigate(playlist)
        assertTrue(navigator.goBack())
        assertTrue(navigator.goBack())

        assertEquals(listOf(Screen.Home, playlist), state.retainedRoutes(Screen.Home))
        assertEquals(listOf(Screen.Library, playlist), state.retainedRoutes(Screen.Library))
        assertTrue(navigator.goForward())
        assertEquals(Screen.Library, state.currentRoute)
        assertTrue(navigator.goForward())
        assertEquals(listOf(Screen.Library, playlist), library.toList())
        assertEquals(library.toList(), state.retainedRoutes(Screen.Library))
    }

    @Test
    fun resettingARootReleasesBothItsLiveDetailsAndItsForwardHistory() {
        navigator.navigate(Screen.Artist("artist"))
        navigator.navigate(Screen.Playlist("playlist"))
        assertTrue(navigator.goBack())
        navigator.navigateToRoot(Screen.Home)

        assertFalse(navigator.canGoForward)
        assertEquals(listOf(Screen.Home), state.retainedRoutes(Screen.Home))
    }

    @Test
    fun repeatedRouteKeysStayRetainedWhileAnotherOccurrenceIsLive() {
        val playlist = Screen.Playlist("playlist")
        val artist = Screen.Artist("artist")
        navigator.navigate(playlist)
        navigator.navigate(artist)
        navigator.navigate(playlist)
        assertTrue(navigator.goBack())
        assertTrue(navigator.goBack())

        assertEquals(listOf(Screen.Home, playlist, artist), state.retainedRoutes(Screen.Home))
        navigator.navigate(Screen.Settings)
        assertEquals(listOf(Screen.Home, playlist, Screen.Settings), state.retainedRoutes(Screen.Home))
    }
}
