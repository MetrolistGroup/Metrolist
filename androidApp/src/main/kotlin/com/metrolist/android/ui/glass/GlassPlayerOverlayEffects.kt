package com.metrolist.android.ui.glass

import androidx.compose.runtime.staticCompositionLocalOf

/** The visible full player uses cheaper sampling for its menus and queue, without changing the material. */
val LocalGlassPlayerOverlayActive = staticCompositionLocalOf { false }

internal data class GlassOverlayBlurBudget(val radiusDp: Float, val resolution: Float)

/** Keeps clear panes and ordinary controls at their configured quality. */
internal fun playerOverlayBlurBudget(
    radiusDp: Float,
    resolution: Float,
    playerActive: Boolean,
    menu: Boolean,
    queue: Boolean,
): GlassOverlayBlurBudget = if (playerActive && (menu || queue) && radiusDp > 0f) {
    GlassOverlayBlurBudget(radiusDp, resolution.coerceAtMost(.25f))
} else {
    GlassOverlayBlurBudget(radiusDp, resolution)
}
