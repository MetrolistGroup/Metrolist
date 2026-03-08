package com.metrolist.desktop.state.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.metrolist.shared.ui.navigation.Navigator
import com.metrolist.shared.ui.navigation.Screen

object DesktopNavigation {
    var listenTogetherRoomCode by mutableStateOf<String?>(null)

    // shortcut: Bridge legacy desktop state until shared screens accept Navigator directly.
    private var boundNavigator: Navigator? = null

    val navigator: Navigator
        get() = checkNotNull(boundNavigator) { "Desktop navigation is not bound" }

    fun bind(navigator: Navigator) {
        boundNavigator = navigator
    }

    fun unbind(navigator: Navigator) {
        if (boundNavigator === navigator) boundNavigator = null
    }

    fun navigate(route: Screen) {
        navigator.navigate(route)
    }

    fun openListenTogetherInvitation(
        roomCode: String,
        joinRoom: (String) -> Boolean,
    ) {
        listenTogetherRoomCode = roomCode
        navigate(Screen.ListenTogether)
        if (joinRoom(roomCode)) listenTogetherRoomCode = null
    }

    fun goBack(): Boolean {
        val navigator = boundNavigator ?: return false
        return performNavigationBack(navigator.canGoBack, navigator::goBack)
    }
}

internal fun performNavigationBack(
    canGoBack: Boolean,
    onBack: () -> Unit,
): Boolean {
    if (!canGoBack) return false
    onBack()
    return true
}
