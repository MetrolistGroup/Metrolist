package com.metrolist.shared.state

import com.ibm.icu.util.ULocale
import java.util.Locale

private val servedRegionLocales by lazy {
    ULocale
        .getAvailableLocales()
        .map { ULocale.addLikelySubtags(ULocale.createCanonical(it.name)) }
        .filter { it.country in contentCountryCodes }
}

internal actual fun closestContentCountry(locale: Locale): String {
    val target = ULocale.addLikelySubtags(ULocale.createCanonical(ULocale.forLocale(locale).name))
    val region = locale.country.ifEmpty { target.country }
    if (region in contentCountryCodes) return region
    val sameLanguage = servedRegionLocales.filter { it.language == target.language }
    // Same script first, so zh-CN gets a region with a Simplified Chinese locale (HK) and zh-MO a Traditional one (HK).
    return sameLanguage
        .filter { it.script == target.script }
        .ifEmpty { sameLanguage }
        .minOfOrNull(ULocale::getCountry)
        // Maintainer decision (#5150): with no served region for the language, send US instead of an unserved gl.
        ?: "US"
}
