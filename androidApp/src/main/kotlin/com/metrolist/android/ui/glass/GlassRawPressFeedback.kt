package com.metrolist.android.ui.glass

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.Indication
import androidx.compose.foundation.gestures.PressGestureScope
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset

/** Feeds an opted-in raw tap detector into global styling without adding gesture ownership. */
internal class GlassRawPressFeedback(
    private val active: State<Boolean>,
    private val source: MutableInteractionSource,
) {
    @Composable
    fun modifier(): Modifier = if (active.value) {
        Modifier.indication(source, LocalIndication.current)
    } else Modifier

    suspend fun onPress(scope: PressGestureScope, position: Offset) {
        if (!active.value) return
        val press = PressInteraction.Press(position)
        source.emit(press)
        var released = false
        try {
            released = scope.tryAwaitRelease()
        } finally {
            // Cancellation must clear the halo too; tryEmit does not suspend a cancelled detector.
            source.tryEmit(if (released) PressInteraction.Release(press) else PressInteraction.Cancel(press))
        }
    }
}

@Composable
internal fun rememberGlassRawPressFeedback(nativeIndication: Indication? = null): GlassRawPressFeedback {
    // Raw artwork gestures have no native ripple unless their caller explicitly supplies one.
    val active = rememberUpdatedState(nativeIndication != null && LocalGlassRippleActive.current)
    val source = remember { MutableInteractionSource() }
    return remember(source) { GlassRawPressFeedback(active, source) }
}
