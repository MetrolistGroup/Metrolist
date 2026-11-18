package com.metrolist.android.ui.glass

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.lerp
import com.metrolist.android.ui.theme.LocalAppearance
import com.metrolist.android.ui.theme.withColor

/** Scope readable foreground roles to a real Artwork Glass surface, preserving its native theme. */
@Composable
internal fun GlassArtworkContent(
    settings: GlassSettings,
    active: Boolean = true,
    surface: Color? = null,
    content: @Composable () -> Unit,
) {
    val appearance = LocalAppearance.current
    val paneDarkness = if (active && settings.enabled) glassSurfaceDarkness(settings) else null
    if (surface != null) {
        // A concrete plate owns its ink independently of a surrounding player transition.
        CompositionLocalProvider(
            LocalNeutralForegroundTransition provides null,
            LocalNeutralForegroundOverride provides null,
            LocalNeutralForegroundSurface provides null,
            LocalNeutralForegroundAmount provides 1f,
        ) {
            SurfaceForegroundContent(surface, glassInkDarkness = paneDarkness, content = content)
        }
        return
    }
    val darkness = if (active && settings.enabled) glassArtworkDarkness(settings) else 0f
    val forced = darkness > 0f
    val palette = appearance.colorPalette
    val artworkSurface = if (forced) glassSurfaceTint(settings) else Color.Transparent
    fun neutralRole(original: Color): Color {
        val corrected = glassPaneForeground(original, artworkSurface, IconContrast, paneDarkness ?: darkness)
        return if (darkness == 1f || original == corrected || !original.isSpecified) corrected
            else lerp(original, corrected, darkness)
    }
    val foregroundPalette = if (forced) palette.copy(
        text = neutralRole(palette.text),
        textSecondary = neutralRole(palette.textSecondary),
        textDisabled = neutralRole(palette.textDisabled),
        iconButtonPlayer = neutralRole(palette.iconButtonPlayer),
    ) else palette
    val effective = if (forced) appearance.copy(
        colorPalette = foregroundPalette,
        typography = appearance.typography.withColor(foregroundPalette.text),
    ) else appearance
    val inheritedContent = LocalContentColor.current
    val contentColor = if (forced) neutralRole(inheritedContent) else inheritedContent
    CompositionLocalProvider(
        LocalAppearance provides effective,
        LocalContentColor provides contentColor,
        LocalGlassInkDarkness provides paneDarkness,
        content = content,
    )
}

/** Explicit glyph colors may have been evaluated outside the surface's content provider. */
@Composable
internal fun glassArtworkForeground(settings: GlassSettings, original: Color, active: Boolean = true): Color =
    if (active && settings.enabled && isNeutralForeground(original)) {
        val darkness = glassArtworkDarkness(settings)
        if (darkness <= 0f) original else {
            val corrected = glassPaneForeground(original, glassSurfaceTint(settings), IconContrast,
                glassSurfaceDarkness(settings), glyph = true)
            if (darkness == 1f || original == corrected) corrected else lerp(original, corrected, darkness)
        }
    } else original
