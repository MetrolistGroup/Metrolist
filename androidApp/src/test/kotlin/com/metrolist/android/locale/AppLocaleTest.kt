package com.metrolist.android.locale

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class AppLocaleTest {
    @Test
    fun platformSystemLocaleTakesPrecedenceOverAppResources() {
        val platformLocale = Locale.forLanguageTag("en-US")
        val appResourceLocale = Locale.forLanguageTag("cs")

        assertEquals(platformLocale, AppLocale.selectSystemLocale(platformLocale, appResourceLocale))
        assertEquals(appResourceLocale, AppLocale.selectSystemLocale(null, appResourceLocale))
    }

    @Test
    fun systemSelectionDoesNotCreateAnOverride() {
        assertNull(AppLocale.resolveSelectedLocale("SYSTEM", "de,fr-CA"))
        assertNull(AppLocale.resolveSelectedLocale("SYSTEM_DEFAULT", "de,fr-CA"))
    }

    @Test
    fun selectionIsLimitedToBundledLocales() {
        assertEquals(Locale.forLanguageTag("fr-CA"), AppLocale.resolveSelectedLocale("fr-CA", "de,fr-CA"))
        assertEquals(Locale.forLanguageTag("fr-CA"), AppLocale.resolveSelectedLocale("fr-FR", "de,fr-CA"))
        assertNull(AppLocale.resolveSelectedLocale("ja", "de,fr-CA"))
    }

    @Test
    fun cachedHinglishSelectionKeepsItsScriptWhenHindiIsAlsoBundled() {
        assertEquals("hi-Latn", AppLocale.normalize("hin"))
        assertEquals(Locale.forLanguageTag("hi-Latn"), AppLocale.resolveSelectedLocale("hin", "hi,hi-Latn"))
    }
}
