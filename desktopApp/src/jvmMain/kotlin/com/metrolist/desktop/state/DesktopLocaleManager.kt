package com.metrolist.desktop.state

import com.metrolist.shared.BuildConfig
import com.metrolist.shared.settings.canonicalAppLanguageTag
import com.metrolist.shared.utils.SystemLocale
import com.metrolist.shared.utils.desktopSystemLocale
import java.util.Locale

data class DesktopLanguage(
    val tag: String,
    val displayName: String? = null,
    val isSystem: Boolean = false,
)

object DesktopLocaleManager {
    const val SYSTEM = "SYSTEM"
    private val bundledTags: List<String> by lazy {
        BuildConfig.BUNDLED_LANGUAGE_TAGS
            .split(',')
            .filter(String::isNotBlank)
            .distinct()
            .sorted()
    }
    val systemLocale: Locale = desktopSystemLocale

    fun normalize(value: String?): String {
        val raw = value.orEmpty().trim()
        if (raw.isEmpty() || raw.equals("SYSTEM", true) || raw.equals("SYSTEM_DEFAULT", true)) return SYSTEM
        val locale = Locale.forLanguageTag(canonicalAppLanguageTag(raw.replace('_', '-')))
        return locale.toLanguageTag().takeIf { it.isNotBlank() && it != "und" } ?: SYSTEM
    }

    fun apply(value: String?) {
        SystemLocale.current = systemLocale
        val selectedTag = selectionTag(value)
        Locale.setDefault(if (selectedTag == SYSTEM) systemLocale else Locale.forLanguageTag(selectedTag))
    }

    fun languages(): List<DesktopLanguage> =
        listOf(DesktopLanguage(SYSTEM, isSystem = true)) +
            bundledTags.map { tag ->
                val locale = Locale.forLanguageTag(tag)
                DesktopLanguage(tag, locale.getDisplayName(locale))
            }

    fun displayName(
        value: String?,
        system: Locale = Locale.getDefault(),
    ): String {
        val selected = selectionTag(value)
        require(selected != SYSTEM) { "System locale labels must come from localized UI resources" }
        return Locale.forLanguageTag(selected).getDisplayName(system).ifBlank { selected }
    }

    fun selectionTag(value: String?): String {
        val normalized = normalize(value)
        if (normalized == SYSTEM) return SYSTEM
        return matchingBundledTag(normalized) ?: SYSTEM
    }

    private fun matchingBundledTag(normalized: String): String? {
        bundledTags.firstOrNull { it.equals(normalized, ignoreCase = true) }?.let { return it }
        val language = Locale.forLanguageTag(normalized).language
        return bundledTags.firstOrNull { it.equals(language, ignoreCase = true) }
    }
}
