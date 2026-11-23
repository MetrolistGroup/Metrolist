package com.metrolist.shared.state

import com.metrolist.shared.settings.Settings
import java.util.Locale

internal actual val platformCountryCodes: Set<String> by lazy { Locale.getISOCountries().toSet() }

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
        systemCountry = closestContentCountry(systemLocale),
        systemLanguage = systemLocale.toLanguageTag(),
        settings = settings,
    )
}

// The system region when YouTube Music serves it, otherwise the nearest served region using the same language.
internal expect fun closestContentCountry(locale: Locale): String
