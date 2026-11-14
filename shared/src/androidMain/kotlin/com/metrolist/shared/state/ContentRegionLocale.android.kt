package com.metrolist.shared.state

import android.icu.util.ULocale
import java.util.Locale

private val contentLocaleLanguages by lazy {
    ULocale.getAvailableLocales().mapTo(mutableSetOf()) { ULocale.createCanonical(it.name).language }
}

internal actual fun likelyContentCountry(locale: Locale): String {
    val canonicalLocale = ULocale.createCanonical(ULocale.forLocale(locale).name)
    val language = canonicalLocale.language
    if (language.isBlank() || language !in contentLocaleLanguages) return locale.country
    return ULocale.addLikelySubtags(canonicalLocale).country
}
