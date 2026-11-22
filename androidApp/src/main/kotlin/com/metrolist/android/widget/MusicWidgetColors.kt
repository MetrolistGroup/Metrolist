/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.android.widget

import android.app.UiModeManager
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.os.Build
import android.widget.RemoteViews
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.metrolist.android.ui.theme.systemDynamicColorScheme
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.settings.ThemeMode
import com.metrolist.shared.settings.ThemePaletteStyle
import com.metrolist.shared.ui.theme.orIfIllegible
import com.metrolist.shared.ui.theme.pureBlack
import com.metrolist.shared.ui.theme.resolveThemeSeedColor
import com.metrolist.shared.ui.theme.themeColorScheme

internal data class WidgetColorSchemes(
    val light: ColorScheme,
    val dark: ColorScheme,
) {
    fun current(context: Context): ColorScheme {
        val systemDark =
            (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        return if (systemDark) dark else light
    }
}

internal data class WidgetThemeConfig(
    val themeMode: ThemeMode,
    val autoPureBlack: Boolean,
    val materialYouEnabled: Boolean,
    val dynamicTheme: Boolean,
    val selectedThemeColor: Long,
    val paletteStyle: ThemePaletteStyle,
) {
    companion object {
        fun fromSettings(): WidgetThemeConfig =
            WidgetThemeConfig(
                themeMode = Settings.shared.themeMode,
                autoPureBlack = Settings.shared.autoPureBlack,
                materialYouEnabled = Settings.shared.materialYouEnabled,
                dynamicTheme = Settings.shared.dynamicTheme,
                selectedThemeColor = Settings.shared.selectedThemeColor,
                paletteStyle = Settings.shared.themePaletteStyle,
            )
    }
}

internal fun resolveWidgetColorSchemes(
    context: Context,
    artworkSeed: Color?,
    config: WidgetThemeConfig = WidgetThemeConfig.fromSettings(),
): WidgetColorSchemes {
    val contrast =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            context.getSystemService(UiModeManager::class.java)?.contrast?.toDouble() ?: 0.0
        } else {
            0.0
        }
    val songArtworkColorsEnabled = config.materialYouEnabled && config.dynamicTheme
    val effectiveArtworkSeed = artworkSeed.takeIf { songArtworkColorsEnabled }

    fun buildScheme(isDark: Boolean): ColorScheme {
        val base =
            when {
                effectiveArtworkSeed != null -> {
                    themeColorScheme(effectiveArtworkSeed, isDark, config.paletteStyle, contrast)
                }

                config.materialYouEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                    val wallpaperSeed = Color(context.getColor(android.R.color.system_accent1_500))
                    if (config.paletteStyle == ThemePaletteStyle.TONAL_SPOT) {
                        systemDynamicColorScheme(context, isDark).orIfIllegible(isDark) {
                            themeColorScheme(wallpaperSeed, isDark, config.paletteStyle, contrast)
                        }
                    } else {
                        themeColorScheme(wallpaperSeed, isDark, config.paletteStyle, contrast)
                    }
                }

                else -> {
                    val seed =
                        resolveThemeSeedColor(
                            materialYouEnabled = config.materialYouEnabled,
                            songArtworkColorsEnabled = false,
                            selectedThemeColor = config.selectedThemeColor,
                            artworkSeedColor = null,
                        )
                    themeColorScheme(seed, isDark, config.paletteStyle, contrast)
                }
            }
        val pureBlack =
            isDark &&
                (config.themeMode == ThemeMode.PURE_BLACK || (config.themeMode == ThemeMode.AUTO && config.autoPureBlack))
        return if (pureBlack) base.pureBlack() else base
    }

    return when (config.themeMode) {
        ThemeMode.LIGHT -> {
            val light = buildScheme(isDark = false)
            WidgetColorSchemes(light = light, dark = light)
        }

        ThemeMode.DARK, ThemeMode.PURE_BLACK -> {
            val dark = buildScheme(isDark = true)
            WidgetColorSchemes(light = dark, dark = dark)
        }

        ThemeMode.AUTO -> {
            WidgetColorSchemes(
                light = buildScheme(isDark = false),
                dark = buildScheme(isDark = true),
            )
        }
    }
}

internal fun RemoteViews.setWidgetColor(
    context: Context,
    viewId: Int,
    method: String,
    schemes: WidgetColorSchemes,
    role: (ColorScheme) -> Color,
) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        setColorInt(viewId, method, role(schemes.light).toArgb(), role(schemes.dark).toArgb())
    } else {
        setInt(viewId, method, role(schemes.current(context)).toArgb())
    }
}

internal fun RemoteViews.setWidgetTint(
    context: Context,
    viewId: Int,
    schemes: WidgetColorSchemes,
    role: (ColorScheme) -> Color,
) = setWidgetColor(context, viewId, "setColorFilter", schemes, role)

internal fun RemoteViews.setWidgetTextColor(
    context: Context,
    viewId: Int,
    schemes: WidgetColorSchemes,
    role: (ColorScheme) -> Color,
) = setWidgetColor(context, viewId, "setTextColor", schemes, role)

internal fun RemoteViews.setWidgetIndeterminateTint(
    viewId: Int,
    schemes: WidgetColorSchemes,
    role: (ColorScheme) -> Color,
) {
    // RemoteViews.setColorStateList requires API 31; ProgressBar has no int tint setter on API 24-30.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        setColorStateList(
            viewId,
            "setIndeterminateTintList",
            ColorStateList.valueOf(role(schemes.light).toArgb()),
            ColorStateList.valueOf(role(schemes.dark).toArgb()),
        )
    }
}

internal fun RemoteViews.setWidgetProgressTint(
    context: Context,
    ids: WidgetProgressIds,
    schemes: WidgetColorSchemes,
) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        fun tint(role: (ColorScheme) -> Color) =
            ColorStateList.valueOf(role(schemes.light).toArgb()) to ColorStateList.valueOf(role(schemes.dark).toArgb())
        tint { it.primary }.let { (light, dark) -> setColorStateList(ids.bar, "setProgressTintList", light, dark) }
        tint { it.secondaryContainer }.let { (light, dark) ->
            setColorStateList(ids.bar, "setProgressBackgroundTintList", light, dark)
        }
    } else {
        // Stop is a separate view so the track color filter does not wash out the end dot.
        setWidgetTint(context, ids.track, schemes) { it.secondaryContainer }
        setWidgetTint(context, ids.fill, schemes) { it.primary }
        setWidgetTint(context, ids.stop, schemes) { it.primary }
    }
}

internal fun widgetSurfaceRole(scheme: ColorScheme): Color = if (scheme.surface == Color.Black) Color.Black else scheme.surfaceContainer
