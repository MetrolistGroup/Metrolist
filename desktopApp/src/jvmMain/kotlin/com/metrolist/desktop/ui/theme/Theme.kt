/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.desktop.ui.theme

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import com.metrolist.desktop.utils.DesktopFontManager
import com.metrolist.shared.settings.FontOption
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.ui.theme.DefaultThemeColor
import com.metrolist.shared.ui.theme.MetrolistTypography
import com.metrolist.shared.ui.theme.currentAppLocaleList
import com.metrolist.shared.ui.theme.resolveThemeSeedColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.metrolist.shared.ui.theme.MetrolistTheme as SharedMetrolistTheme

@Composable
private fun appFontFamily(
    systemFamily: FontFamily,
    languageTag: String,
): FontFamily {
    val option = Settings.shared.appFont
    val revision = DesktopFontManager.revision
    val family by produceState(systemFamily, option, revision, systemFamily to languageTag) {
        value = systemFamily
        if (option != FontOption.SYSTEM) {
            value =
                withContext(Dispatchers.IO) {
                    // Compose does not pass TextStyle locales to Skia; keep the locale-matched system font as the fallback.
                    DesktopFontManager
                        .loadFontFamily(option)
                        ?.takeIf { DesktopFontManager.supportsLanguage(option, languageTag) }
                        ?: systemFamily
                }
        }
    }
    return family
}

@Composable
fun AppTypography(): Typography {
    val localeList = currentAppLocaleList()
    val sharedTypography = MetrolistTypography(localeList)
    val systemFamily = sharedTypography.bodyLarge.fontFamily ?: FontFamily.Default
    val family = appFontFamily(systemFamily, localeList[0].toLanguageTag())
    return Typography(
        fontFamily = family,
        displayLarge = sharedTypography.displayLarge.copy(fontFamily = family),
        displayMedium = sharedTypography.displayMedium.copy(fontFamily = family),
        displaySmall = sharedTypography.displaySmall.copy(fontFamily = family),
        headlineLarge = sharedTypography.headlineLarge.copy(fontFamily = family),
        headlineMedium = sharedTypography.headlineMedium.copy(fontFamily = family),
        headlineSmall = sharedTypography.headlineSmall.copy(fontFamily = family),
        titleLarge = sharedTypography.titleLarge.copy(fontFamily = family),
        titleMedium = sharedTypography.titleMedium.copy(fontFamily = family),
        titleSmall = sharedTypography.titleSmall.copy(fontFamily = family),
        bodyLarge = sharedTypography.bodyLarge.copy(fontFamily = family),
        bodyMedium = sharedTypography.bodyMedium.copy(fontFamily = family),
        bodySmall = sharedTypography.bodySmall.copy(fontFamily = family),
        labelLarge = sharedTypography.labelLarge.copy(fontFamily = family),
        labelMedium = sharedTypography.labelMedium.copy(fontFamily = family),
        labelSmall = sharedTypography.labelSmall.copy(fontFamily = family),
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MetrolistTheme(
    seedColor: Color = DefaultThemeColor,
    pureBlack: Boolean = Settings.shared.isPureBlackTheme(),
    content: @Composable () -> Unit,
) {
    val isDark = Settings.shared.getCurrentThemeIsDark()

    SharedMetrolistTheme(
        seedColor =
            resolveThemeSeedColor(
                materialYouEnabled = Settings.shared.materialYouEnabled,
                songArtworkColorsEnabled = Settings.shared.dynamicTheme,
                selectedThemeColor = Settings.shared.selectedThemeColor,
                artworkSeedColor = seedColor,
            ),
        darkTheme = isDark,
        pureBlack = pureBlack,
    ) {
        MaterialExpressiveTheme(
            colorScheme = MaterialTheme.colorScheme,
            shapes = MaterialTheme.shapes,
            typography = AppTypography(),
            motionScheme = MotionScheme.expressive(),
            content = content,
        )
    }
}
