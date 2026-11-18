package com.metrolist.shared.state

import com.metrolist.shared.settings.Settings
import java.util.Locale

actual val contentCountryCodes: Set<String> by lazy { Locale.getISOCountries().toSet() }

internal actual val platformContentLanguageCodes: Set<String> by lazy {
    Locale.getISOLanguages().toSet() + Locale.getAvailableLocales().map(Locale::toLanguageTag)
}

internal actual fun canonicalLanguageTag(tag: String): String = Locale.forLanguageTag(tag).toLanguageTag()

fun GlobalYouTube.applyContentRegion(
    contentCountry: String,
    contentLanguage: String,
    systemLocale: Locale,
    settings: Settings = Settings.shared,
) {
    applyContentRegion(
        contentCountry = contentCountry,
        contentLanguage = contentLanguage,
        systemCountry =
            systemLocale.country.takeIf { it in contentCountryCodes }
                ?: likelyContentCountry(
                    Locale
                        .Builder()
                        .setLocale(systemLocale)
                        .setRegion("")
                        .build(),
                ),
        systemLanguage = systemLocale.language,
        settings = settings,
    )
}

internal expect fun likelyContentCountry(locale: Locale): String
