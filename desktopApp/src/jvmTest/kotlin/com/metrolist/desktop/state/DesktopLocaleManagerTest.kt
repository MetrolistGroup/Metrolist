package com.metrolist.desktop.state

import com.metrolist.shared.BuildConfig
import com.metrolist.shared.utils.SystemLocale
import com.metrolist.shared.utils.getSystemLanguage
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopLocaleManagerTest {
    @Test
    fun normalizesLegacyAndBcp47Values() {
        assertEquals(DesktopLocaleManager.SYSTEM, DesktopLocaleManager.normalize("SYSTEM_DEFAULT"))
        assertEquals(DesktopLocaleManager.SYSTEM, DesktopLocaleManager.normalize("system"))
        assertEquals("pt-BR", DesktopLocaleManager.normalize("pt_BR"))
        assertEquals("hi-Latn", DesktopLocaleManager.selectionTag("hin"))
    }

    @Test
    fun convertsAndroidResourceQualifiersToLanguageTags() {
        assertEquals("pt-BR", DesktopLocaleManager.languageTagFromValuesDirectory("values-pt-rBR"))
        assertEquals("sr-Latn", DesktopLocaleManager.languageTagFromValuesDirectory("values-b+sr+Latn"))
        assertEquals("hi-Latn", DesktopLocaleManager.languageTagFromValuesDirectory("values-b+hi+Latn"))
        assertEquals("de", DesktopLocaleManager.languageTagFromValuesDirectory("values-de"))
    }

    @Test
    fun discoversLanguagesFromSharedResources() {
        val bundledLanguages = DesktopLocaleManager.languages().filterNot(DesktopLanguage::isSystem)

        assertTrue(bundledLanguages.isNotEmpty())
        assertTrue(bundledLanguages.any { '-' in it.tag })

        val buildConfigTags = BuildConfig.BUNDLED_LANGUAGE_TAGS.split(',').filter(String::isNotBlank)
        val tags = bundledLanguages.map(DesktopLanguage::tag).toSet()
        assertTrue(tags.containsAll(buildConfigTags))
    }

    @Test
    fun bundledNamesStayNativeWhenTheUiLanguageChangesIncludingHinglish() {
        val original = Locale.getDefault()
        val languages = DesktopLocaleManager.languages().filterNot(DesktopLanguage::isSystem)
        try {
            for (displayLanguage in languages.take(3)) {
                Locale.setDefault(Locale.forLanguageTag(displayLanguage.tag))
                val actual = DesktopLocaleManager.languages().filterNot(DesktopLanguage::isSystem)
                assertEquals(languages, actual)
                actual.forEach { language ->
                    val locale = Locale.forLanguageTag(language.tag)
                    assertEquals(locale.getDisplayName(locale), language.displayName)
                }
                val hinglish = actual.single { it.tag == DesktopLocaleManager.selectionTag("hin") }
                val locale = Locale.forLanguageTag(hinglish.tag)
                assertEquals(locale.getDisplayName(locale), hinglish.displayName)
            }
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun applyingAppLanguageDoesNotReplaceSystemLocaleUsedForContent() {
        val previousDefault = Locale.getDefault()
        val previousSystemLocale = SystemLocale.current
        val systemLocale = DesktopLocaleManager.systemLocale
        val appLanguage =
            DesktopLocaleManager
                .languages()
                .first { !it.isSystem && Locale.forLanguageTag(it.tag).language != systemLocale.language }
                .tag

        try {
            DesktopLocaleManager.apply(appLanguage)

            assertEquals(Locale.forLanguageTag(appLanguage), Locale.getDefault())
            assertEquals(systemLocale, DesktopLocaleManager.systemLocale)
            assertEquals(systemLocale.language, getSystemLanguage())
        } finally {
            Locale.setDefault(previousDefault)
            SystemLocale.current = previousSystemLocale
        }
    }
}
