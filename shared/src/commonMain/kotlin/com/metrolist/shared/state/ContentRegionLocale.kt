package com.metrolist.shared.state

expect val contentCountryCodes: Set<String>

internal expect val platformContentLanguageCodes: Set<String>

// Maps legacy codes such as iw to the tag the platform and bundled resources use.
internal expect fun canonicalLanguageTag(tag: String): String
