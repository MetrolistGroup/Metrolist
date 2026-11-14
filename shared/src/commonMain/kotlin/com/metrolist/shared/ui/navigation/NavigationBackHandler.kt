package com.metrolist.shared.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.NavigationEventTransitionState
import androidx.navigationevent.compose.rememberNavigationEventState
import androidx.navigationevent.compose.NavigationBackHandler as NavigationEventBackHandler

@Composable
fun NavigationBackHandler(
    enabled: Boolean = true,
    onBackCancelled: () -> Unit = {},
    onBack: () -> Unit,
): Float {
    val state = rememberNavigationEventState(NavigationEventInfo.None)
    NavigationEventBackHandler(
        state = state,
        isBackEnabled = enabled,
        onBackCancelled = onBackCancelled,
        onBackCompleted = onBack,
    )
    return (state.transitionState as? NavigationEventTransitionState.InProgress)?.latestEvent?.progress ?: 0f
}
