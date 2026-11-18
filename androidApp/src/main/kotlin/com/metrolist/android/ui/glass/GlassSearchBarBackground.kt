package com.metrolist.android.ui.glass

import androidx.compose.foundation.background
import com.metrolist.android.ui.glass.backdrop.Backdrop
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.metrolist.android.ui.glass.backdrop.backdrops.LayerBackdrop

/** One themed search surface; its foreground and focus choreography remain native. */
@Composable
internal fun Modifier.glassSearchBarBackground(
    backgroundColor: Color,
    containerColor: Color,
    shape: Shape,
    shadowColor: Color,
    shadowElevation: Dp = 0.dp,
    settings: GlassSettings,
    exportedBackdrop: LayerBackdrop? = null,
    sourceBackdrop: Backdrop? = null,
): Modifier {
    if (!settings.usesGlassMaterial(GlassArea.SearchBars)) {
        return then(if (shadowElevation > 0.dp) Modifier.shadow(
            elevation = shadowElevation, shape = shape, spotColor = shadowColor,
        ) else Modifier).background(containerColor, shape)
            .glassAreaContour(settings, GlassArea.SearchBars, shape)
    }
    val backdrop = sourceBackdrop ?: rememberInlineGlassBackdrop(backgroundColor)
    return glassMaterial(
        settings = settings.forArea(GlassArea.SearchBars),
        shape = shape,
        ownBackdrop = backdrop,
        filmColor = containerColor,
        drawShadow = false,
        exportedBackdrop = exportedBackdrop,
    )
}
