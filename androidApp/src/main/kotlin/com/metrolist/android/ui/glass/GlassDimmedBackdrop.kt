package com.metrolist.android.ui.glass

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.metrolist.android.ui.glass.backdrop.Backdrop
import com.metrolist.android.ui.glass.backdrop.backdrops.rememberCanvasBackdrop
import com.metrolist.android.ui.glass.backdrop.backdrops.rememberCombinedBackdrop

/** Samples the same dimmed scene drawn behind a modal without recording the modal itself. */
@Composable
internal fun rememberGlassDimmedBackdrop(source: Backdrop?, alpha: Float, color: Color = Color.Black): Backdrop? {
    if (source == null) return null
    val opacity = alpha.coerceIn(0f, 1f)
    if (opacity == 0f || color.alpha == 0f) return source
    val scrim = rememberCanvasBackdrop { drawRect(color.copy(alpha = color.alpha * opacity)) }
    return rememberCombinedBackdrop(source, scrim)
}
