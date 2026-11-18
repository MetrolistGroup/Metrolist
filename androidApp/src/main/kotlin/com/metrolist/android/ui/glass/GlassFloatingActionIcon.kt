package com.metrolist.android.ui.glass

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.metrolist.android.ui.theme.colorPalette
import com.metrolist.android.ui.glass.backdrop.backdrops.rememberCanvasBackdrop
import com.metrolist.android.ui.theme.uiRoundnessShape

/** Restores the action contour on an unchanged native fallback, without adding material. */
@Composable
fun Modifier.glassNativeActionContour(
    settings: GlassSettings = rememberGlassSettings(),
    shape: Shape = uiRoundnessShape(),
): Modifier = if (settings.usesGlassMaterial(GlassArea.ActionButtons)) this
    else glassAreaContour(settings, GlassArea.ActionButtons, shape)

/**
 * Rendering hook: false preserves the caller's native floating action unchanged.
 * [movable] leaves movement consumption to the caller's long-press reposition gesture.
 */
@Composable
fun GlassFloatingActionIcon(
    icon: Painter,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    movable: Boolean = false,
    size: Dp = 62.dp,
    iconSize: Dp = 20.dp,
    iconModifier: Modifier = Modifier,
    tint: Color = colorPalette().text,
    contentDescription: String? = null,
    settings: GlassSettings = rememberGlassSettings(),
    filmColor: Color? = null,
    shape: Shape = uiRoundnessShape(),
): Boolean {
    if (!settings.usesGlassMaterial(GlassArea.ActionButtons)) return false
    val background = colorPalette().background0
    val drawBackground: DrawScope.() -> Unit = remember(background) { { drawRect(background) } }
    // Pages export a live content sibling here; never sample a page containing this action.
    val backdrop = LocalGlassContainerBackdrop.current ?: rememberCanvasBackdrop(drawBackground)
    val film = glassFilmColor(settings.forArea(GlassArea.ActionButtons), filmColor, menu = false)
    val foreground = if (enabled) glassPaneForeground(tint, effectiveSurfaceColor(surfaceBackdropColor(), film),
        IconContrast, glassSurfaceDarkness(settings), glyph = true) else tint
    val glyph: @Composable () -> Unit = {
        Icon(icon, contentDescription, Modifier.size(iconSize).then(iconModifier),
            tint = if (enabled) foreground else foreground.copy(alpha = foreground.alpha * .5f))
    }
    if (enabled) {
        LiquidButton(
            onClick = onClick,
            backdrop = backdrop,
            modifier = modifier.size(size),
            settings = settings.forArea(GlassArea.ActionButtons),
            filmColor = filmColor,
            contentHeight = size,
            contentPadding = 0.dp,
            retainGesture = !movable,
            shape = shape,
        ) { glyph() }
    } else {
        Box(
            modifier.size(size)
                .glassMaterial(settings.forArea(GlassArea.ActionButtons), shape, ownBackdrop = backdrop,
                    filmColor = filmColor)
                .clickable(enabled = false, role = Role.Button, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { glyph() }
    }
    return true
}
