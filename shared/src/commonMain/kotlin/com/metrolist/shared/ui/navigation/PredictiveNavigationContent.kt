package com.metrolist.shared.ui.navigation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.NavigationEventTransitionState
import androidx.navigationevent.compose.rememberNavigationEventState
import androidx.navigationevent.compose.NavigationBackHandler as EventBackHandler

@Composable
fun <T> PredictiveNavigationContent(
    targetState: T,
    previousState: T,
    backEnabled: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    isPop: Boolean = false,
    pattern: (T, T) -> NavigationPattern = { _, _ -> NavigationPattern.Default },
    origin: (T) -> TransformOrigin = { TransformOrigin.Center },
    content: @Composable AnimatedVisibilityScope.(T) -> Unit,
) {
    val motion = rememberNavigationMotion()
    val state = remember { SeekableTransitionState(targetState) }
    val transition = rememberTransition(state, label = "nestedNavigation")
    val back = rememberNavigationEventState(NavigationEventInfo.None)
    var completion by remember { mutableStateOf<Pair<T, T>?>(null) }
    val completing = completion != null
    val gesture = back.transitionState as? NavigationEventTransitionState.InProgress
    EventBackHandler(
        state = back,
        isBackEnabled = backEnabled || completing,
        onBackCompleted = { if (!completing) completion = targetState to previousState },
    )
    val finishBack = completion
    LaunchedEffect(targetState, previousState, gesture, finishBack) {
        try {
            when {
                gesture != null && backEnabled && !completing && motion.enabled -> {
                    state.seekTo(gesture.latestEvent.progress, previousState)
                }

                finishBack != null && finishBack.first == targetState -> {
                    if (motion.enabled) state.animateTo(finishBack.second) else state.snapTo(finishBack.second)
                    onBack()
                }

                motion.enabled -> {
                    state.animateTo(targetState)
                }

                else -> {
                    state.snapTo(targetState)
                }
            }
        } finally {
            if (finishBack != null && completion === finishBack) completion = null
        }
    }
    transition.AnimatedContent(
        modifier = modifier,
        transitionSpec = {
            val pop = gesture != null || finishBack != null || isPop
            val source = if (pop) targetState else initialState
            val destination = if (pop) initialState else targetState
            motion.transition(pattern(source, destination), pop, origin(destination)) using null
        },
    ) { destination ->
        val settled = this.transition.acceptsInput(EnterExitState.Visible)
        Box(Modifier.fillMaxSize().blockInput(!settled || gesture != null || completing)) { content(destination) }
    }
}
