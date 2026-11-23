package com.metrolist.shared.state

import com.ibm.icu.util.Region
import com.ibm.icu.util.ULocale
import java.util.Locale

private val servedRegionLocales by lazy {
    ULocale
        .getAvailableLocales()
        .map { ULocale.addLikelySubtags(ULocale.createCanonical(it.name)) }
        .filter { it.country in contentCountryCodes }
}

private fun regionContainers(region: String): List<String> =
    runCatching { generateSequence(Region.getInstance(region)) { it.containingRegion }.map(Region::toString).toList() }
        .getOrDefault(emptyList())

internal actual fun closestContentCountry(locale: Locale): String {
    val target = ULocale.addLikelySubtags(ULocale.createCanonical(ULocale.forLocale(locale).name))
    val region = locale.country.ifEmpty { target.country }
    if (region in contentCountryCodes) return region
    val sameLanguage = servedRegionLocales.filter { it.language == target.language }
    val candidates =
        sameLanguage
            .filter { it.script == target.script }
            .ifEmpty { sameLanguage }
            .map(ULocale::getCountry)
            .ifEmpty { contentCountryCodes.toList() }
    val containers = regionContainers(region)
    // Prefer the candidate sharing the smallest UN M.49 area with the system region, e.g. HK over SG for CN.
    return candidates.distinct().minWith(
        compareBy<String> { candidate ->
            regionContainers(candidate).let { c ->
                containers.indexOfFirst(c::contains).takeIf { it >= 0 }
                    ?: Int.MAX_VALUE
            }
        }.thenBy { it },
    )
}
