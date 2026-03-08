package com.metrolist.shared.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import kotlinx.coroutines.flow.StateFlow

internal data class PlayerContentState(
    val isVisible: Boolean,
    val isSettled: Boolean,
    val isActive: Boolean,
    val isPositionActive: Boolean,
)

internal fun resolvePlayerContentState(
    isContentVisible: Boolean,
    isSettledExpanded: Boolean,
    isScreenVisible: Boolean,
    isInPipMode: Boolean,
): PlayerContentState {
    val visible = isContentVisible && !isInPipMode
    val settled = visible && isSettledExpanded
    return PlayerContentState(visible, settled, settled && isScreenVisible, visible && isScreenVisible)
}

internal val LocalPlayerPositionActive = compositionLocalOf { true }
internal val LocalPlayerContentActive = compositionLocalOf { true }
internal val LocalPlayerBackgroundAnimating = compositionLocalOf { true }

@Composable
internal fun <T> StateFlow<T>.collectWhilePlayerActive(active: Boolean = LocalPlayerContentActive.current): State<T> =
    key(this, active) {
        produceState(value) {
            if (active) collect { value = it }
        }
    }
