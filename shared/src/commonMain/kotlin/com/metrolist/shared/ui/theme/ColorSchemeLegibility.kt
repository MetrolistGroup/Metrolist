package com.metrolist.shared.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.math.max
import kotlin.math.min

// Allow rounding and contrast-level variation around WCAG text and control thresholds.
private const val MIN_TEXT_CONTRAST = 4.0
private const val MIN_CONTROL_CONTRAST = 2.5

fun ColorScheme.isLegible(isDark: Boolean): Boolean {
    val roles =
        listOf(
            primary,
            onPrimary,
            primaryContainer,
            onPrimaryContainer,
            secondaryContainer,
            onSecondaryContainer,
            background,
            onBackground,
            surface,
            onSurface,
            onSurfaceVariant,
            surfaceContainerHighest,
        )
    if (roles.any { !it.isOpaqueAndFinite() }) return false
    return (surface.luminance() < onSurface.luminance()) == isDark &&
        contrastRatio(onSurface, surface) >= MIN_TEXT_CONTRAST &&
        contrastRatio(onBackground, background) >= MIN_TEXT_CONTRAST &&
        contrastRatio(onSurface, surfaceContainerHighest) >= MIN_TEXT_CONTRAST &&
        contrastRatio(onPrimary, primary) >= MIN_TEXT_CONTRAST &&
        contrastRatio(onPrimaryContainer, primaryContainer) >= MIN_TEXT_CONTRAST &&
        contrastRatio(onSecondaryContainer, secondaryContainer) >= MIN_TEXT_CONTRAST &&
        contrastRatio(onSurfaceVariant, surface) >= MIN_CONTROL_CONTRAST &&
        contrastRatio(primary, surface) >= MIN_CONTROL_CONTRAST
}

inline fun ColorScheme.orIfIllegible(
    isDark: Boolean,
    fallback: () -> ColorScheme,
): ColorScheme = if (isLegible(isDark)) this else fallback()

internal fun contrastRatio(
    first: Color,
    second: Color,
): Double {
    val a = first.luminance().toDouble()
    val b = second.luminance().toDouble()
    return (max(a, b) + 0.05) / (min(a, b) + 0.05)
}

internal fun Color.isOpaqueAndFinite(): Boolean = alpha >= 1f && red.isFinite() && green.isFinite() && blue.isFinite()
