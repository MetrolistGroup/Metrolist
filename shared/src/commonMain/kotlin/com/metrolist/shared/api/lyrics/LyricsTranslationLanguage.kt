package com.metrolist.shared.api.lyrics

import com.metrolist.shared.settings.Settings
import com.metrolist.shared.utils.getSystemLanguage

internal fun lyricsTranslationTargetLanguage(configuredLanguage: String = Settings.shared.lyricsAiTargetLanguage): String =
    if (configuredLanguage == "device" || configuredLanguage.isBlank()) {
        runCatching { getSystemLanguage() }.getOrNull().orEmpty()
    } else {
        configuredLanguage
    }

internal fun lyricsTranslationLanguageMatches(
    language: String,
    target: String,
): Boolean =
    language.isNotBlank() && target.isNotBlank() &&
        language.replace('_', '-').substringBefore('-').equals(target.replace('_', '-').substringBefore('-'), ignoreCase = true)
