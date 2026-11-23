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
        // shortcut: no served region uses the language, so gl stays unserved; omit gl (needs InnerTubeX) to let YouTube pick by IP.
        ?: region
}
