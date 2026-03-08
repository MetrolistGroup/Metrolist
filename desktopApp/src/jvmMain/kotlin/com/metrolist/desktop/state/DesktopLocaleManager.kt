package com.metrolist.desktop.state

import com.metrolist.shared.BuildConfig
import com.metrolist.shared.settings.canonicalAppLanguageTag
import com.metrolist.shared.utils.SystemLocale
import com.metrolist.shared.utils.desktopSystemLocale
import java.io.File
import java.net.JarURLConnection
import java.util.Locale
import java.util.jar.JarFile

data class DesktopLanguage(
    val tag: String,
    val displayName: String? = null,
    val isSystem: Boolean = false,
)

object DesktopLocaleManager {
    const val SYSTEM = "SYSTEM"
    private val bundledTags: List<String> by lazy {
        val staticTags = BuildConfig.BUNDLED_LANGUAGE_TAGS.split(',').filter(String::isNotBlank)
        (staticTags + discoverBundledLanguages()).distinct().sorted()
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

    private fun discoverBundledLanguages(): List<String> {
        val langs = mutableSetOf<String>()

        val classLoader = DesktopLocaleManager::class.java.classLoader
        val resourceUrls = classLoader.getResources("composeResources")
        while (resourceUrls.hasMoreElements()) {
            val resourceUrl = resourceUrls.nextElement()
            when (resourceUrl.protocol) {
                "file" -> {
                    val basePath = File(resourceUrl.toURI())
                    collectFromDirectory(basePath, langs)
                }

                "jar" -> {
                    val connection = resourceUrl.openConnection() as? JarURLConnection
                    if (connection != null) {
                        connection.useCaches = false
                        connection.jarFile.use { jar -> collectFromJar(jar, langs) }
                    }
                }
            }
        }

        return langs.sorted()
    }

    private fun collectFromDirectory(
        basePath: File,
        langs: MutableSet<String>,
    ) {
        if (!basePath.isDirectory) return
        basePath.listFiles()?.forEach { child ->
            when {
                !child.isDirectory -> {
                    return@forEach
                }

                child.name == "values-xml" -> {
                    return@forEach
                }

                child.name == "values" -> {
                    return@forEach
                }

                child.name.startsWith("values-") -> {
                    val tag = languageTagFromValuesDirectory(child.name)
                    if (tag.isNotBlank()) langs.add(tag)
                    return@forEach
                }

                else -> {
                    collectFromDirectory(child, langs)
                }
            }
        }
    }

    private fun collectFromJar(
        jar: JarFile,
        langs: MutableSet<String>,
    ) {
        val seenDirs = mutableSetOf<String>()
        val entries = jar.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            val valuesDir = valuesDirectory(entry.name) ?: continue
            if (!seenDirs.add(valuesDir)) continue
            if (valuesDir == "values-xml") continue
            if (valuesDir != "values") {
                val tag = languageTagFromValuesDirectory(valuesDir)
                if (tag.isNotBlank()) langs.add(tag)
            }
        }
    }

    private fun valuesDirectory(entryPath: String): String? {
        val segments = entryPath.split('/')
        return segments.firstOrNull { it == "values" || it.startsWith("values-") }
    }

    internal fun languageTagFromValuesDirectory(valuesDirectory: String): String {
        val qualifier = valuesDirectory.removePrefix("values-")
        val languageTag =
            if (qualifier.startsWith("b+")) {
                qualifier.removePrefix("b+").replace('+', '-')
            } else {
                qualifier.replace(Regex("-r(?=[A-Z]{2}(?:$|-))"), "-")
            }
        return Locale.forLanguageTag(languageTag).toLanguageTag()
    }
}
