package com.metrolist.shared.ui.navigation

import androidx.compose.animation.core.Transition
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics

class NavigationInputState {
    private var backInProgress by mutableStateOf(false)

    fun reset() {
        backInProgress = false
    }

    fun beginBack() {
        backInProgress = true
    }

    fun cancelBack() = reset()

    fun acceptsInput(transitionSettled: Boolean): Boolean = !backInProgress && transitionSettled
}

internal fun <T> transitionAcceptsInput(
    current: T,
    target: T,
    visible: T,
    running: Boolean,
    parentSettled: Boolean? = null,
): Boolean = target == visible && (parentSettled ?: (current == visible && !running))

fun <T> Transition<T>.acceptsInput(visible: T): Boolean {
    var parent = parentTransition
    while (parent?.parentTransition != null) parent = parent.parentTransition
    val parentSettled = parent?.let { it.currentState == it.targetState && !it.isSeeking }
    return transitionAcceptsInput(currentState, targetState, visible, isRunning, parentSettled)
}

fun Modifier.blockInput(blocked: Boolean): Modifier =
    then(if (blocked) Modifier.clearAndSetSemantics {} else Modifier)
        .onPreviewKeyEvent { blocked }
        .pointerInput(blocked) {
            if (blocked) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                }
            }
        }
