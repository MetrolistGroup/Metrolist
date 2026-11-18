package com.metrolist.android.ui.glass

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import com.metrolist.android.ui.theme.colorPalette
import com.metrolist.android.ui.glass.backdrop.backdrops.layerBackdrop
import com.metrolist.android.ui.glass.backdrop.backdrops.rememberCanvasBackdrop
import com.metrolist.android.ui.glass.backdrop.backdrops.rememberLayerBackdrop

/** Exports only the existing artwork, never its sibling title or glass controls. */
@Composable
fun GlassArtworkBackdrop(
    hasArtwork: Boolean,
    settings: GlassSettings = rememberGlassSettings(),
    content: @Composable (artworkModifier: Modifier) -> Unit,
) {
    if (!settings.usesGlassMaterial(GlassArea.ActionButtons) || !glassContentCaptureEnabled(settings)) {
        content(Modifier)
        return
    }
    val background = colorPalette().background0
    val drawArtwork: ContentDrawScope.() -> Unit = remember(background) {
        {
            // Faded artwork reveals the same opaque page color as the visible image.
            drawRect(background)
            drawContent()
        }
    }
    val artwork = rememberLayerBackdrop(onDraw = drawArtwork)
    val plainPage = rememberCanvasBackdrop { drawRect(background) }
    val source = if (hasArtwork) artwork else plainPage
    CompositionLocalProvider(
        LocalGlassBackdrop provides source,
        LocalGlassContainerBackdrop provides source,
        LocalGlassMaterialSource provides source,
    ) {
        content(if (hasArtwork) Modifier.layerBackdrop(artwork) else Modifier)
    }
}
