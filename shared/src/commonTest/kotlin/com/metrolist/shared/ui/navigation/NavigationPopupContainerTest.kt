package com.metrolist.shared.ui.navigation

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Rect
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertSame

class NavigationPopupContainerTest {
    private val button = Rect(900f, 40f, 940f, 80f)

    @Test
    fun aButtonOfferIsConsumedOnceAndNavigationClearsAnUnusedOffer() {
        val stack = NavBackStack<NavKey>(Screen.Home)
        val state = NavigationState(Screen.Home, mutableStateOf(Screen.Home), mapOf(Screen.Home to stack))
        val containers = state.containers
        val origin = NavigationPopupOrigin(NavigationPopupSource(), button, button, CircleShape)
        containers.pendingPopup = origin
        assertSame(origin, containers.takePopupSource())
        assertNull(containers.takePopupSource())
        containers.pendingPopup = origin
        Navigator(state).navigate(Screen.Settings)
        assertNull(containers.takePopupSource())
    }
}
