package com.metrolist.shared.state

import platform.Foundation.ISOCountryCodes
import platform.Foundation.ISOLanguageCodes
import platform.Foundation.NSLocale
import platform.Foundation.availableLocaleIdentifiers
import platform.Foundation.canonicalLanguageIdentifierFromString

actual val contentCountryCodes: Set<String> by lazy { NSLocale.ISOCountryCodes.filterIsInstance<String>().toSet() }

internal actual val platformContentLanguageCodes: Set<String> by lazy {
    NSLocale.ISOLanguageCodes.filterIsInstance<String>().toSet() +
        NSLocale.availableLocaleIdentifiers.filterIsInstance<String>().map { it.replace('_', '-') }
}

internal actual fun canonicalLanguageTag(tag: String): String = NSLocale.canonicalLanguageIdentifierFromString(tag).replace('_', '-')
