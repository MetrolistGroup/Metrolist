package com.metrolist.shared.state

import com.metrolist.shared.settings.Settings
import com.russhwolf.settings.MapSettings
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class SystemContentLocaleTest {
    private val previousLocale = GlobalYouTube.innerTube.locale
    private val previousRegionOverride = GlobalYouTube.innerTube.regionOverrideActive
    private val previousVisitorData = GlobalYouTube.innerTube.visitorData

    @AfterTest
    fun restoreRegion() {
        GlobalYouTube.innerTube.locale = previousLocale
        GlobalYouTube.innerTube.regionOverrideActive = previousRegionOverride
        GlobalYouTube.innerTube.visitorData = previousVisitorData
    }

    @Test
    fun macroregionSystemLocalesResolveToACountryWhenRestoringInvalidSettings() {
        for ((tag, country) in listOf("nl-150" to "NL", "fr-001" to "FR")) {
            val settings = Settings(MapSettings()).apply { contentCountry = "150" }

            GlobalYouTube.applyContentRegion(
                contentCountry = settings.contentCountry,
                contentLanguage = GlobalYouTube.SYSTEM,
                systemLocale = Locale.forLanguageTag(tag),
                settings = settings,
            )

            assertEquals(GlobalYouTube.SYSTEM, settings.contentCountry, tag)
            assertEquals(country, GlobalYouTube.innerTube.locale.gl, tag)
            assertEquals(Locale.forLanguageTag(tag).language, GlobalYouTube.innerTube.locale.hl, tag)
        }
    }

    @Test
    fun regionlessSystemLocalesUseTheirLikelyCountryWithoutChangingLanguage() {
        for ((tag, country) in listOf(
            "pl" to "PL",
            "zh-Hant" to "TW",
            "fil" to "PH",
            "tl" to "PH",
            "iw" to "IL",
            "in" to "ID",
            "he" to "IL",
            "id" to "ID",
            "nb" to "NO",
        )) {
            val systemLocale = Locale.forLanguageTag(tag)
            GlobalYouTube.applyContentRegion(
                contentCountry = GlobalYouTube.SYSTEM,
                contentLanguage = GlobalYouTube.SYSTEM,
                systemLocale = systemLocale,
                settings = Settings(MapSettings()),
            )

            assertEquals(country, GlobalYouTube.innerTube.locale.gl, tag)
            assertEquals(systemLocale.language, GlobalYouTube.innerTube.locale.hl, tag)
        }
    }

    @Test
    fun systemCountryDoesNotFollowExplicitContentLanguage() {
        GlobalYouTube.applyContentRegion(
            contentCountry = GlobalYouTube.SYSTEM,
            contentLanguage = "ja",
            systemLocale = Locale.forLanguageTag("pl"),
            settings = Settings(MapSettings()),
        )

        assertEquals("PL", GlobalYouTube.innerTube.locale.gl)
        assertEquals("ja", GlobalYouTube.innerTube.locale.hl)
    }

    @Test
    fun explicitCountryAndExistingSystemRegionArePreserved() {
        for ((tag, setting, expected) in listOf(Triple("fr-CA", GlobalYouTube.SYSTEM, "CA"), Triple("fr", "JP", "JP"))) {
            GlobalYouTube.applyContentRegion(
                contentCountry = setting,
                contentLanguage = GlobalYouTube.SYSTEM,
                systemLocale = Locale.forLanguageTag(tag),
                settings = Settings(MapSettings()),
            )

            assertEquals(expected, GlobalYouTube.innerTube.locale.gl)
            assertEquals("fr", GlobalYouTube.innerTube.locale.hl)
        }
    }

    @Test
    fun unknownSystemLanguageDoesNotAcquireAnEnglishOrUsDefault() {
        for (locale in listOf(Locale.ROOT, Locale.forLanguageTag("und"), Locale.forLanguageTag("zz"))) {
            assertEquals("", likelyContentCountry(locale))
        }
    }

    @Test
    fun inferredRegionInvalidatesStaleSessionAndCacheOnlyOnce() {
        val settings =
            Settings(MapSettings()).apply {
                visitorData = "stale-visitor"
                lastHomePageJson = "stale-home"
            }

        fun apply() =
            GlobalYouTube.applyContentRegion(
                contentCountry = GlobalYouTube.SYSTEM,
                contentLanguage = GlobalYouTube.SYSTEM,
                systemLocale = Locale.forLanguageTag("pl"),
                settings = settings,
            )

        apply()
        assertEquals("PL", settings.lastResolvedContentCountry)
        assertEquals("pl", settings.lastResolvedContentLanguage)
        assertEquals("", settings.visitorData)
        assertEquals("", settings.lastHomePageJson)

        settings.visitorData = "current-visitor"
        settings.lastHomePageJson = "current-home"
        apply()
        assertEquals("current-visitor", settings.visitorData)
        assertEquals("current-home", settings.lastHomePageJson)
    }
}
