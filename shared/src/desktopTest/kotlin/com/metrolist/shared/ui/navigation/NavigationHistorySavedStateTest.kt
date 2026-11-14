package com.metrolist.shared.ui.navigation

import androidx.compose.runtime.mutableStateOf
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.savedstate.compose.serialization.serializers.SnapshotStateListSerializer
import androidx.savedstate.serialization.decodeFromSavedState
import androidx.savedstate.serialization.encodeToSavedState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NavigationHistorySavedStateTest {
    @Test
    fun savedForwardHistoryRestoresItsOwningStackAndNavigationOrder() {
        val home = NavBackStack<NavKey>(Screen.Home)
        val library = NavBackStack<NavKey>(Screen.Library)
        val state = NavigationState(Screen.Home, mutableStateOf(Screen.Home), mapOf(Screen.Home to home, Screen.Library to library))
        val navigator = Navigator(state)
        navigator.navigate(Screen.Library)
        val playlist = Screen.Playlist("playlist")
        navigator.navigate(playlist)
        assertTrue(navigator.goBack())
        assertTrue(navigator.goBack())

        val serializer = SnapshotStateListSerializer(ForwardRoute.serializer())
        val saved = encodeToSavedState(serializer, state.forwardRoutes)
        val restored =
            NavigationState(
                Screen.Home,
                mutableStateOf(Screen.Home),
                mapOf(Screen.Home to home, Screen.Library to library),
                decodeFromSavedState(serializer, saved),
            )
        val restoredNavigator = Navigator(restored)
        assertEquals(listOf(Screen.Library, playlist), restored.retainedRoutes(Screen.Library))
        assertTrue(restoredNavigator.goForward())
        assertEquals(Screen.Library, restored.currentRoute)
        assertTrue(restoredNavigator.goForward())
        assertEquals(playlist, restored.currentRoute)
        assertFalse(restoredNavigator.canGoForward)
    }
}
