package com.metrolist.android.ui.glass

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.metrolist.android.ui.glass.backdrop.Backdrop
import com.metrolist.android.ui.glass.backdrop.backdrops.rememberCanvasBackdrop

/** Inline controls share only a child-free source; the page capture can contain the control itself. */
@Composable
internal fun rememberInlineGlassBackdrop(backgroundColor: Color): Backdrop {
    val drawBackground: DrawScope.() -> Unit = remember(backgroundColor) { { drawRect(backgroundColor) } }
    val fallback = rememberCanvasBackdrop(drawBackground)
    return LocalGlassMaterialSource.current ?: fallback
}
