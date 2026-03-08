/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.android.ui.theme

import android.annotation.SuppressLint
import android.app.UiModeManager
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.graphics.Color
import com.materialkolor.dynamicColorScheme
import com.materialkolor.dynamiccolor.ColorSpec
import com.metrolist.shared.ui.theme.orIfIllegible

/** Samsung system roles can be stale; use wallpaper tonal palettes instead of Android 14+ roles. */
@RequiresApi(Build.VERSION_CODES.S)
internal fun systemDynamicColorScheme(
    context: Context,
    darkTheme: Boolean,
): ColorScheme {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && Build.MANUFACTURER.equals("samsung", ignoreCase = true)) {
        return dynamicColorScheme(
            primary = Color(context.getColor(android.R.color.system_accent1_500)),
            secondary = Color(context.getColor(android.R.color.system_accent2_500)),
            tertiary = Color(context.getColor(android.R.color.system_accent3_500)),
            neutral = Color(context.getColor(android.R.color.system_neutral1_500)),
            neutralVariant = Color(context.getColor(android.R.color.system_neutral2_500)),
            isDark = darkTheme,
            contrastLevel = context.getSystemService(UiModeManager::class.java).contrast.toDouble(),
            specVersion = ColorSpec.SpecVersion.SPEC_2025,
        )
    }

    val base = if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return base

    val suffix = if (darkTheme) "dark" else "light"

    @SuppressLint("DiscouragedApi") // OEMs can omit system color roles; resolve each name before using the Compose fallback.
    fun role(
        name: String,
        fallback: Color,
    ): Color {
        val id = context.resources.getIdentifier("system_${name}_$suffix", "color", "android")
        return if (id == 0) fallback else Color(context.resources.getColor(id, context.theme))
    }

    val primary = role("primary", base.primary)
    val roles =
        base.copy(
            primary = primary,
            onPrimary = role("on_primary", base.onPrimary),
            primaryContainer = role("primary_container", base.primaryContainer),
            onPrimaryContainer = role("on_primary_container", base.onPrimaryContainer),
            secondary = role("secondary", base.secondary),
            onSecondary = role("on_secondary", base.onSecondary),
            secondaryContainer = role("secondary_container", base.secondaryContainer),
            onSecondaryContainer = role("on_secondary_container", base.onSecondaryContainer),
            tertiary = role("tertiary", base.tertiary),
            onTertiary = role("on_tertiary", base.onTertiary),
            tertiaryContainer = role("tertiary_container", base.tertiaryContainer),
            onTertiaryContainer = role("on_tertiary_container", base.onTertiaryContainer),
            background = role("background", base.background),
            onBackground = role("on_background", base.onBackground),
            surface = role("surface", base.surface),
            onSurface = role("on_surface", base.onSurface),
            surfaceVariant = role("surface_variant", base.surfaceVariant),
            onSurfaceVariant = role("on_surface_variant", base.onSurfaceVariant),
            surfaceTint = primary,
            outline = role("outline", base.outline),
            outlineVariant = role("outline_variant", base.outlineVariant),
            error = role("error", base.error),
            onError = role("on_error", base.onError),
            errorContainer = role("error_container", base.errorContainer),
            onErrorContainer = role("on_error_container", base.onErrorContainer),
            surfaceBright = role("surface_bright", base.surfaceBright),
            surfaceDim = role("surface_dim", base.surfaceDim),
            surfaceContainerLowest = role("surface_container_lowest", base.surfaceContainerLowest),
            surfaceContainerLow = role("surface_container_low", base.surfaceContainerLow),
            surfaceContainer = role("surface_container", base.surfaceContainer),
            surfaceContainerHigh = role("surface_container_high", base.surfaceContainerHigh),
            surfaceContainerHighest = role("surface_container_highest", base.surfaceContainerHighest),
        )
    return roles.orIfIllegible(darkTheme) { base }
}
