package com.metrolist.desktop.state.app

import androidx.compose.runtime.mutableStateOf
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.metrolist.shared.ui.navigation.NavigationState
import com.metrolist.shared.ui.navigation.Navigator
import com.metrolist.shared.ui.navigation.Screen
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ListenTogetherInvitationTest {
    @Test
    fun rejectedDirectJoinRetainsTheDestinationUntilItIsHandled() {
        val home = NavBackStack<NavKey>(Screen.Home)
        val state = NavigationState(Screen.Home, mutableStateOf(Screen.Home), mapOf(Screen.Home to home))
        val navigator = Navigator(state)
        DesktopNavigation.bind(navigator)
        try {
            DesktopNavigation.openListenTogetherInvitation("FIRST123") { false }
            assertEquals(Screen.ListenTogether, state.currentRoute)
            assertEquals("FIRST123", DesktopNavigation.listenTogetherRoomCode)
            DesktopNavigation.openListenTogetherInvitation("OTHER123") { code ->
                assertEquals("OTHER123", code)
                false
            }
            assertEquals("OTHER123", DesktopNavigation.listenTogetherRoomCode)
            DesktopNavigation.openListenTogetherInvitation("JOIN1234") { true }
            assertNull(DesktopNavigation.listenTogetherRoomCode)
        } finally {
            DesktopNavigation.listenTogetherRoomCode = null
            DesktopNavigation.unbind(navigator)
        }
    }
}
