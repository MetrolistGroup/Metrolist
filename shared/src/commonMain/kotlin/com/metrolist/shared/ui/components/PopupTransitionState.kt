package com.metrolist.shared.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

internal class PopupTransitionState {
    var targetVisible by mutableStateOf(false)
        private set
    var retained by mutableStateOf(false)
        private set
    var running by mutableStateOf(false)
        private set
    private var generation = 0

    val acceptsInput: Boolean get() = targetVisible && !running

    fun request(visible: Boolean): Boolean {
        if (targetVisible == visible) return false
        generation++
        targetVisible = visible
        running = true
        if (visible) retained = true
        return true
    }

    suspend fun animate(animation: suspend () -> Unit) {
        val request = generation
        try {
            animation()
        } finally {
            // An interrupted animation must not release a newer request's input guard.
            if (request == generation) {
                retained = targetVisible
                running = false
            }
        }
    }

    fun dispose() {
        generation++
        targetVisible = false
        retained = false
        running = false
    }
}
