package com.metrolist.android.ui.component.toggles

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp

@Stable
class SwitchGestureState(checked: Boolean) {
    var dragging by mutableStateOf(false)
    var position by mutableFloatStateOf(if (checked) 1f else 0f)
}

@Composable
fun rememberSwitchGestureState(checked: Boolean, enabled: Boolean): SwitchGestureState {
    val state = remember { SwitchGestureState(checked) }
    LaunchedEffect(checked) {
        state.position = if (checked) 1f else 0f
    }
    LaunchedEffect(enabled) { if (!enabled) state.dragging = false }
    return state
}

fun Modifier.switchInput(
    state: SwitchGestureState,
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    enabled: Boolean,
    interactionSource: MutableInteractionSource?,
    trackWidth: Dp,
    thumbWidth: Dp,
): Modifier = minimumInteractiveComponentSize()
    .toggleable(
        value = checked,
        interactionSource = interactionSource,
        indication = null,
        enabled = enabled,
        role = Role.Switch,
        onValueChange = { onCheckedChange?.invoke(it) }
    )
