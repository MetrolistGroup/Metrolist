package com.metrolist.shared.state

import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.models.YouTubeClient
import com.metrolist.innertubex.models.YouTubeLocale
import com.metrolist.shared.settings.Settings
import com.russhwolf.settings.MapSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContentRegionLocaleTest {
    private lateinit var previousLocale: YouTubeLocale
    private var previousVisitorData: String? = null
    private var previousRegionOverride = false

    @BeforeTest
    fun saveSession() {
        previousLocale = GlobalYouTube.innerTube.locale
        previousVisitorData = GlobalYouTube.innerTube.visitorData
        previousRegionOverride = GlobalYouTube.innerTube.regionOverrideActive
    }

    @AfterTest
    fun restoreSession() {
        GlobalYouTube.innerTube.locale = previousLocale
        GlobalYouTube.innerTube.visitorData = previousVisitorData
        GlobalYouTube.innerTube.regionOverrideActive = previousRegionOverride
    }

    private fun freshSettings(): Settings = Settings(MapSettings())

    @Test
    fun invalidSavedCountriesReturnToSystemAndInvalidateStaleRegionData() {
        for (country in listOf("150", "EU", "001")) {
            val settings =
                freshSettings().apply {
                    contentCountry = country
                    lastResolvedContentCountry = country
                    visitorData = "stale-visitor"
                    lastHomePageJson = "stale-home"
                }
            GlobalYouTube.innerTube.visitorData = "stale-visitor"

            GlobalYouTube.applyContentRegion(
                contentCountry = settings.contentCountry,
                contentLanguage = "nl",
                systemCountry = "NL",
                systemLanguage = "pl",
                settings = settings,
            )

            assertEquals(GlobalYouTube.SYSTEM, settings.contentCountry, country)
            assertEquals("NL", GlobalYouTube.innerTube.locale.gl, country)
            assertEquals("nl", GlobalYouTube.innerTube.locale.hl, country)
            assertEquals("NL", settings.lastResolvedContentCountry, country)
            assertEquals("", settings.visitorData, country)
            assertNull(GlobalYouTube.innerTube.visitorData, country)
            assertEquals("", settings.lastHomePageJson, country)

            settings.visitorData = "fresh-visitor"
            settings.lastHomePageJson = "fresh-home"
            GlobalYouTube.applyContentRegion(
                contentCountry = settings.contentCountry,
                contentLanguage = "nl",
                systemCountry = "NL",
                systemLanguage = "pl",
                settings = settings,
            )
            assertEquals("fresh-visitor", settings.visitorData, country)
            assertEquals("fresh-home", settings.lastHomePageJson, country)
        }
    }

    @Test
    fun validSavedCountryRemainsIndependentOfSystemRegion() {
        val settings = freshSettings().apply { contentCountry = "NL" }

        GlobalYouTube.applyContentRegion(
            contentCountry = settings.contentCountry,
            contentLanguage = GlobalYouTube.SYSTEM,
            systemCountry = "PL",
            systemLanguage = "pl",
            settings = settings,
        )

        assertEquals("NL", settings.contentCountry)
        assertEquals("NL", GlobalYouTube.innerTube.locale.gl)
        assertEquals("pl", GlobalYouTube.innerTube.locale.hl)
    }

    @Test
    fun systemLanguageAndCountryResolvesToSystemLocale() {
        val settings = freshSettings()
        GlobalYouTube.applyContentRegion(
            contentCountry = "SYSTEM",
            contentLanguage = "SYSTEM",
            systemCountry = "IT",
            systemLanguage = "it",
            settings = settings,
        )
        val locale = GlobalYouTube.innerTube.locale
        assertEquals("it", locale.hl)
        assertEquals("IT", locale.gl)
    }

    @Test
    fun fullSystemLanguageTagsUseTheirLanguageCodeInHomeRequests() =
        runTest {
            for ((tag, language) in listOf(
                "en-ID" to "en",
                "fr-CA" to "fr",
                "nl_NL" to "nl",
                "zh-Hant-TW" to "zh",
                "de-DE-u-co-phonebk" to "de",
            )) {
                val settings =
                    freshSettings().apply {
                        lastResolvedContentCountry = "ID"
                        lastResolvedContentLanguage = tag
                        lastHomePageJson = "stale-home"
                        visitorData = "same-region-visitor"
                    }
                GlobalYouTube.applyContentRegion(
                    contentCountry = GlobalYouTube.SYSTEM,
                    contentLanguage = GlobalYouTube.SYSTEM,
                    systemCountry = "ID",
                    systemLanguage = tag,
                    settings = settings,
                )
                val engine =
                    MockEngine(
                        MockEngineConfig().apply {
                            dispatcher = StandardTestDispatcher(testScheduler)
                            addHandler { respond("{}", headers = headersOf(HttpHeaders.ContentType, "application/json")) }
                        },
                    )
                val innerTube = clientWithContentNegotiation(engine)
                try {
                    innerTube.locale = GlobalYouTube.innerTube.locale
                    innerTube.browse(YouTubeClient.WEB_REMIX, browseId = "FEmusic_home")

                    val request = engine.requestHistory.single()
                    val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                    val client = body["context"]!!.jsonObject["client"]!!.jsonObject
                    assertEquals(language, client["hl"]!!.jsonPrimitive.content, tag)
                    assertEquals("ID", client["gl"]!!.jsonPrimitive.content, tag)
                    assertEquals("$language-ID,$language;q=0.9", request.headers[HttpHeaders.AcceptLanguage], tag)
                    assertEquals(language, settings.lastResolvedContentLanguage, tag)
                    assertEquals("", settings.lastHomePageJson, tag)
                    assertEquals("same-region-visitor", settings.visitorData, tag)
                } finally {
                    innerTube.close()
                    innerTube.httpClient.close()
                }
            }
        }

    @Test
    fun explicitRegionalContentLanguageIsPreservedWithAFullSystemTag() {
        val settings = freshSettings()
        GlobalYouTube.applyContentRegion(
            contentCountry = "CA",
            contentLanguage = "pt-BR",
            systemCountry = "ID",
            systemLanguage = "en-ID",
            settings = settings,
        )

        assertEquals(YouTubeLocale(gl = "CA", hl = "pt-BR"), GlobalYouTube.innerTube.locale)
        assertEquals("pt-BR", settings.lastResolvedContentLanguage)
    }

    @Test
    fun explicitLanguageOnlySetsLocaleNotRegion() {
        val settings = freshSettings()
        GlobalYouTube.applyContentRegion(
            contentCountry = "SYSTEM",
            contentLanguage = "ja",
            systemCountry = "IT",
            systemLanguage = "it",
            settings = settings,
        )
        val locale = GlobalYouTube.innerTube.locale
        assertEquals("ja", locale.hl)
        assertEquals("IT", locale.gl)
    }

    @Test
    fun explicitLanguageAndCountryUsesBoth() {
        val settings = freshSettings()
        GlobalYouTube.applyContentRegion(
            contentCountry = "CA",
            contentLanguage = "fr",
            systemCountry = "US",
            systemLanguage = "en",
            settings = settings,
        )
        val locale = GlobalYouTube.innerTube.locale
        assertEquals("fr", locale.hl)
        assertEquals("CA", locale.gl)
    }

    @Test
    fun explicitLanguageDoesNotChangeSystemCountry() {
        val settings = freshSettings()
        GlobalYouTube.applyContentRegion(
            contentCountry = "SYSTEM",
            contentLanguage = "de",
            systemCountry = "US",
            systemLanguage = "en",
            settings = settings,
        )
        val locale = GlobalYouTube.innerTube.locale
        assertEquals("de", locale.hl)
        assertEquals("US", locale.gl)
    }

    @Test
    fun explicitCountryOverridesSystemCountry() {
        val settings = freshSettings()
        GlobalYouTube.applyContentRegion(
            contentCountry = "JP",
            contentLanguage = "en",
            systemCountry = "US",
            systemLanguage = "en",
            settings = settings,
        )
        val locale = GlobalYouTube.innerTube.locale
        assertEquals("en", locale.hl)
        assertEquals("JP", locale.gl)
    }

    @Test
    fun systemSettingsClearVisitorDataOnRegionChange() {
        val settings =
            freshSettings().apply {
                lastResolvedContentCountry = "US"
                visitorData = "stale-visitor"
            }
        GlobalYouTube.innerTube.visitorData = "stale-visitor"

        GlobalYouTube.applyContentRegion(
            contentCountry = "SYSTEM",
            contentLanguage = "SYSTEM",
            systemCountry = "IT",
            systemLanguage = "it",
            settings = settings,
        )
        assertEquals("", settings.visitorData)
        assertNull(GlobalYouTube.innerTube.visitorData)
    }

    @Test
    fun sameRegionDoesNotClearVisitorData() {
        val settings = freshSettings().apply { lastResolvedContentCountry = "IT" }
        GlobalYouTube.innerTube.visitorData = "keep-this"

        GlobalYouTube.applyContentRegion(
            contentCountry = "SYSTEM",
            contentLanguage = "SYSTEM",
            systemCountry = "IT",
            systemLanguage = "it",
            settings = settings,
        )
        assertEquals("keep-this", GlobalYouTube.innerTube.visitorData)
    }

    @Test
    fun regionOverrideAlwaysActiveAfterApply() {
        val settings = freshSettings()
        GlobalYouTube.innerTube.regionOverrideActive = false

        GlobalYouTube.applyContentRegion(
            contentCountry = "SYSTEM",
            contentLanguage = "SYSTEM",
            systemCountry = "US",
            systemLanguage = "en",
            settings = settings,
        )
        assertTrue(GlobalYouTube.innerTube.regionOverrideActive)
    }

    @Test
    fun lastHomePageJsonClearedWhenResolvedLanguageChanges() {
        val settings =
            freshSettings().apply {
                lastResolvedContentCountry = "US"
                lastResolvedContentLanguage = "en"
                lastHomePageJson = "stale-cache"
            }

        GlobalYouTube.applyContentRegion(
            contentCountry = "SYSTEM",
            contentLanguage = "SYSTEM",
            systemCountry = "US",
            systemLanguage = "it",
            settings = settings,
        )
        assertEquals("", settings.lastHomePageJson)
    }

    @Test
    fun lastHomePageJsonClearedWhenResolvedCountryChanges() {
        val settings =
            freshSettings().apply {
                lastResolvedContentCountry = "US"
                lastResolvedContentLanguage = "en"
                lastHomePageJson = "stale-cache"
            }

        GlobalYouTube.applyContentRegion(
            contentCountry = "SYSTEM",
            contentLanguage = "SYSTEM",
            systemCountry = "IT",
            systemLanguage = "en",
            settings = settings,
        )
        assertEquals("", settings.lastHomePageJson)
    }

    @Test
    fun lastHomePageJsonPreservedWhenResolvedLocaleIsUnchanged() {
        val settings =
            freshSettings().apply {
                lastResolvedContentCountry = "US"
                lastResolvedContentLanguage = "en"
                lastHomePageJson = "cached-home"
            }

        GlobalYouTube.applyContentRegion(
            contentCountry = "SYSTEM",
            contentLanguage = "SYSTEM",
            systemCountry = "US",
            systemLanguage = "en",
            settings = settings,
        )
        assertEquals("cached-home", settings.lastHomePageJson)
    }

    @Test
    fun lastResolvedContentLocaleIsPersisted() {
        val settings = freshSettings()
        GlobalYouTube.applyContentRegion(
            contentCountry = "SYSTEM",
            contentLanguage = "SYSTEM",
            systemCountry = "IT",
            systemLanguage = "it",
            settings = settings,
        )
        assertEquals("IT", settings.lastResolvedContentCountry)
        assertEquals("it", settings.lastResolvedContentLanguage)
    }

    private fun clientWithContentNegotiation(engine: MockEngine): InnerTube {
        val httpClient =
            HttpClient(engine) {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            }
        return InnerTube(httpClient, retryDelay = {})
    }
}
