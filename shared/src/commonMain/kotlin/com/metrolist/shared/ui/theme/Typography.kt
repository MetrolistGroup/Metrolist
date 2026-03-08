/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalLocaleList
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.unit.sp
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.settings.resolveAppLanguageTag
import com.metrolist.shared.state.GlobalYouTube

@Composable
fun currentAppLocaleList(): LocaleList = resolveAppLocaleList(Settings.shared.appLanguage, LocalLocaleList.current)

internal fun resolveAppLocaleList(
    configuredLanguage: String,
    systemLocales: LocaleList,
): LocaleList =
    if (configuredLanguage.isBlank() || configuredLanguage.equals(GlobalYouTube.SYSTEM, ignoreCase = true)) {
        systemLocales
    } else {
        LocaleList(
            resolveAppLanguageTag(configuredLanguage, systemLocales.map { it.toLanguageTag() }),
        )
    }

@Composable
expect fun localeAwareSystemFontFamily(languageTag: String): FontFamily

@Composable
fun MetrolistTypography(localeList: LocaleList = currentAppLocaleList()): Typography {
    val font = localeAwareSystemFontFamily(localeList[0].toLanguageTag())

    return Typography(
        fontFamily = font,
        displayLarge =
            TextStyle(
                fontFamily = font,
                fontWeight = FontWeight.Bold,
                localeList = localeList,
                fontSize = 57.sp,
                lineHeight = 64.sp,
                letterSpacing = (-0.25).sp,
            ),
        displayMedium =
            TextStyle(
                fontFamily = font,
                fontWeight = FontWeight.Bold,
                fontSize = 45.sp,
                localeList = localeList,
                lineHeight = 52.sp,
                letterSpacing = 0.sp,
            ),
        displaySmall =
            TextStyle(
                fontFamily = font,
                fontWeight = FontWeight.Bold,
                fontSize = 36.sp,
                localeList = localeList,
                lineHeight = 44.sp,
                letterSpacing = 0.sp,
            ),
        headlineLarge =
            TextStyle(
                fontFamily = font,
                fontWeight = FontWeight.Bold,
                fontSize = 32.sp,
                localeList = localeList,
                lineHeight = 40.sp,
                letterSpacing = 0.sp,
            ),
        headlineMedium =
            TextStyle(
                fontFamily = font,
                fontWeight = FontWeight.Bold,
                fontSize = 28.sp,
                localeList = localeList,
                lineHeight = 36.sp,
                letterSpacing = 0.sp,
            ),
        headlineSmall =
            TextStyle(
                fontFamily = font,
                fontWeight = FontWeight.Bold,
                fontSize = 24.sp,
                localeList = localeList,
                lineHeight = 32.sp,
                letterSpacing = 0.sp,
            ),
        titleLarge =
            TextStyle(
                fontFamily = font,
                fontWeight = FontWeight.SemiBold,
                localeList = localeList,
                fontSize = 22.sp,
                lineHeight = 28.sp,
                letterSpacing = 0.sp,
            ),
        titleMedium =
            TextStyle(
                fontFamily = font,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                localeList = localeList,
                lineHeight = 24.sp,
                letterSpacing = 0.15.sp,
            ),
        titleSmall =
            TextStyle(
                fontFamily = font,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                localeList = localeList,
                lineHeight = 20.sp,
                letterSpacing = 0.1.sp,
            ),
        bodyLarge =
            TextStyle(
                fontFamily = font,
                fontWeight = FontWeight.Normal,
                fontSize = 16.sp,
                localeList = localeList,
                lineHeight = 24.sp,
                letterSpacing = 0.5.sp,
            ),
        bodyMedium =
            TextStyle(
                fontFamily = font,
                fontWeight = FontWeight.Normal,
                fontSize = 14.sp,
                localeList = localeList,
                lineHeight = 20.sp,
                letterSpacing = 0.25.sp,
            ),
        bodySmall =
            TextStyle(
                fontFamily = font,
                fontWeight = FontWeight.Normal,
                fontSize = 12.sp,
                localeList = localeList,
                lineHeight = 16.sp,
                letterSpacing = 0.4.sp,
            ),
        labelLarge =
            TextStyle(
                fontFamily = font,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                localeList = localeList,
                lineHeight = 20.sp,
                letterSpacing = 0.1.sp,
            ),
        labelMedium =
            TextStyle(
                fontFamily = font,
                fontWeight = FontWeight.Medium,
                fontSize = 12.sp,
                localeList = localeList,
                lineHeight = 16.sp,
                letterSpacing = 0.5.sp,
            ),
        labelSmall =
            TextStyle(
                fontFamily = font,
                fontWeight = FontWeight.Medium,
                fontSize = 11.sp,
                localeList = localeList,
                lineHeight = 16.sp,
                letterSpacing = 0.5.sp,
            ),
    )
}
