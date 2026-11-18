package com.metrolist.android.ui.glass

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape

/** Adds a Glass contour without changing the card fill, content or shape. */
@Composable
fun Modifier.glassSettingsCardRim(shape: Shape): Modifier {
    val settings = rememberGlassSettings()
    if (!settings.usesGlassContour(GlassArea.SettingsCards)) return this
    return glassSettingsCardRim(settings, shape, glassTint(settings))
}

internal fun Modifier.glassSettingsCardRim(settings: GlassSettings, shape: Shape, tint: Color): Modifier {
    if (!settings.usesGlassContour(GlassArea.SettingsCards)) return this
    return glassEdgeRim(settings, shape, tint)
}
