package com.metrolist.android.ui.glass

import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.metrolist.android.ui.theme.colorPalette
import com.metrolist.android.ui.glass.backdrop.Backdrop
import com.metrolist.android.ui.glass.backdrop.backdrops.rememberCanvasBackdrop

/** Lets rows embedded in a card expose that card while preserving their swipe action film. */
internal val LocalSwipeRestingBackground = staticCompositionLocalOf<Color?> { null }

/** Paints the existing swipe reveal without participating in its pointer handling. */
@Composable
fun Modifier.glassSwipeActionBackground(
    originalColor: Color,
    shape: Shape,
    isRevealed: Boolean,
    settings: GlassSettings = rememberGlassSettings(),
    area: GlassArea = GlassArea.ActionButtons,
    backdrop: Backdrop? = null,
    backdropColor: Color = colorPalette().background0,
): Modifier {
    // The sibling foreground row needs this complete background, including its action glyph.
    val surface = glassQueueUnderlayBackdrop(Any())
    val queueBackdrop = LocalQueueRowBackdrop.current
    if (LocalNativeQueueRows.current && queueBackdrop == null) {
        return surface.background(if (isRevealed) originalColor else LocalSwipeRestingBackground.current ?: originalColor)
    }
    // Transparent row content exposes this layer even when no swipe is in progress.
    if (!isRevealed) return surface.background(LocalSwipeRestingBackground.current ?: originalColor)
    if (!settings.usesGlassMaterial(area)) {
        return surface.background(originalColor).glassAreaContour(settings, area, shape)
    }
    // List reveals belong to the page capture: never sample that containing capture.
    // Chrome callers may explicitly supply the page capture when they are its siblings.
    val drawBackground: DrawScope.() -> Unit = remember(backdropColor) {
        { drawRect(backdropColor) }
    }
    val independentBackdrop = rememberCanvasBackdrop(drawBackground)
    return surface.glassMaterial(
        settings = settings.forArea(area),
        shape = shape,
        ownBackdrop = backdrop ?: queueBackdrop ?: independentBackdrop,
        filmColor = originalColor,
        highlightAlpha = .3f,
        drawShadow = false,
    )
}
