package com.metrolist.android.ui.glass

import com.metrolist.android.ui.glass.backdrop.backdrops.LayerBackdrop
import com.metrolist.android.ui.glass.backdrop.Backdrop
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape

/** Uses the navigation material, with a safe independent backdrop for inline cards. */
@Composable
internal fun Modifier.glassSettingsCardBackground(
    backgroundColor: Color,
    cardColor: Color,
    shape: Shape,
    settings: GlassSettings,
    exportedBackdrop: LayerBackdrop? = null,
    sourceBackdrop: Backdrop? = null,
): Modifier {
    if (!settings.usesGlassMaterial(GlassArea.SettingsCards)) return this
    val backdrop = sourceBackdrop ?: rememberInlineGlassBackdrop(backgroundColor)
    return glassMaterial(
        settings = settings.forArea(GlassArea.SettingsCards),
        shape = shape,
        ownBackdrop = backdrop,
        highlightAlpha = .3f,
        // Keep the lens/highlight at the card's own bounds, without a second shadow plate.
        drawShadow = false,
        exportedBackdrop = exportedBackdrop,
        // The original themed fill also carries the existing search highlight.
        filmColor = cardColor,
    )
}
