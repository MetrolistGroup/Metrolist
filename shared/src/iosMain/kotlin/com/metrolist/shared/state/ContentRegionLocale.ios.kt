package com.metrolist.shared.state

import platform.Foundation.ISOCountryCodes
import platform.Foundation.ISOLanguageCodes
import platform.Foundation.NSLocale
import platform.Foundation.availableLocaleIdentifiers
import platform.Foundation.canonicalLanguageIdentifierFromString

internal actual val platformCountryCodes: Set<String> by lazy { NSLocale.ISOCountryCodes.filterIsInstance<String>().toSet() }

internal actual val platformContentLanguageCodes: Set<String> by lazy {
    NSLocale.ISOLanguageCodes.filterIsInstance<String>().toSet() +
        NSLocale.availableLocaleIdentifiers.filterIsInstance<String>().map { it.replace('_', '-') }
}

internal actual fun canonicalLanguageTag(tag: String): String = NSLocale.canonicalLanguageIdentifierFromString(tag).replace('_', '-')

// Foundation has no likely-subtags API, so the script comes from the preferred language tag when it has one.
internal fun closestContentCountry(
    languageTag: String,
    country: String,
): String {
    if (country in contentCountryCodes) return country
    val target = languageTag.split('-')
    val script = target.getOrNull(1)?.takeIf { it.length == 4 }
    val sameLanguage =
        NSLocale.availableLocaleIdentifiers
            .filterIsInstance<String>()
            .map { it.split('_') }
            .filter { it.size > 1 && it.first() == target.first() && it.last() in contentCountryCodes }
    return sameLanguage
        .filter { script != null && script in it }
        .ifEmpty { sameLanguage }
        .minOfOrNull { it.last() }
        // Maintainer decision (#5150): with no served region for the language, send US instead of an unserved gl.
        ?: "US"
}
