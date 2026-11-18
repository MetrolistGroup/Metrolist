package com.metrolist.android.ui.glass

import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.isActive

/** Only modal descendants opt out of retained optical captures. */
internal val LocalGlassLiveFrame = staticCompositionLocalOf<(() -> Long)?> { null }

/** Display-frame clock independent of animation scale, disposed together with the modal. */
@Composable
internal fun rememberGlassModalLiveFrame(): () -> Long {
    // Captured menu locals may contain a clock owned by an already-closed modal.
    // This provider must own its writer instead of inheriting that stale clock.
    val frame = remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (isActive) {
            withInfiniteAnimationFrameNanos { frame.longValue = it }
        }
    }
    // Only draw nodes read this state, so advancing it does not recompose the modal.
    return remember(frame) { { frame.longValue } }
}

/** Native/tint-only modals keep their existing idle behavior. */
@Composable
internal fun rememberGlassModalLiveFrameIfEnabled(covered: Boolean = false): (() -> Long)? {
    val settings = rememberGlassSettings()
    return if ((!covered || !settings.optimizationEnabled) && settings.enabled && glassRenderer(settings) != GlassRenderer.Tint)
        rememberGlassModalLiveFrame() else null
}
