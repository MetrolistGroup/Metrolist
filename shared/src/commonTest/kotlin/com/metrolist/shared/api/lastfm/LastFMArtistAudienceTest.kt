package com.metrolist.shared.api.lastfm

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LastFMArtistAudienceTest {
    @Test
    fun blockedGalleriesTryTheOverviewWithoutPagination() =
        runTest {
            for (status in listOf(
                HttpStatusCode.OK,
                HttpStatusCode.Forbidden,
                HttpStatusCode.TooManyRequests,
                HttpStatusCode.ServiceUnavailable,
                HttpStatusCode(600, "Challenge"),
            )) {
                val requests = mutableListOf<HttpRequestData>()
                HttpClient(
                    MockEngine { request ->
                        requests += request
                        respond("<title>Client Challenge</title>", status)
                    },
                ).use { client ->
                    assertTrue(LastFM.getArtistPhotos(client, "Artist", "de", renderPage = null).isEmpty())
                    assertEquals(listOf("/music/Artist/+images", "/music/Artist"), requests.map { it.url.encodedPath })
                    requests.forEach { request ->
                        assertEquals("de", request.headers[HttpHeaders.AcceptLanguage])
                        assertTrue(request.headers[HttpHeaders.UserAgent].orEmpty().startsWith("Mozilla/5.0"))
                        assertTrue(request.headers[HttpHeaders.Accept].orEmpty().contains("text/html"))
                    }
                }
            }
        }

    @Test
    fun photosRejectUnexpectedContentTypesAndPropagateCancellation() =
        runTest {
            HttpClient(
                MockEngine {
                    respond(
                        """<li class="image-list-item"><img src="https://lastfm-img.freetls.fastly.net/i/u/ar0/photo"></li>""",
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                },
            ).use { client -> assertTrue(LastFM.getArtistPhotos(client, "Artist").isEmpty()) }
            HttpClient(MockEngine { throw CancellationException("Screen closed") }).use { client ->
                assertFailsWith<CancellationException> { LastFM.getArtistPhotos(client, "Artist") }
            }
        }

    @Test
    fun photosAreBoundedDeduplicatedAndRestrictedToTheImageCdn() {
        fun image(url: String) = """<li class="image-list-item"><img src="$url"></li>"""
        val valid = "https://lastfm-img.freetls.fastly.net/i/u/300x300/photo"
        val html =
            image(valid) + image(valid) +
                image("https://evil.example/lastfm-img.freetls.fastly.net/i/u/ar0/photo") +
                image("http://lastfm-img.freetls.fastly.net/i/u/ar0/photo") +
                image("https://lastfm-img.freetls.fastly.net/i/u/ar0/$LASTFM_PLACEHOLDER_HASH") +
                (1..30).joinToString("") { image(valid + it) }
        val photos = LastFM.extractGalleryPhotos(html)
        assertEquals(20, photos.size)
        assertEquals(toFullResolution(valid), photos.first())
        assertEquals(photos.size, photos.distinct().size)
        assertTrue(photos.all { it.startsWith("https://lastfm-img.freetls.fastly.net/i/u/ar0/photo") })
    }

    @Test
    fun catalogMatchRequiresSharedTitles() {
        val youTube = listOf("Smells Like Teen Spirit (Remastered)", "Come As You Are", "Nevermind")

        assertTrue(LastFM.catalogsMatch(youTube, listOf("smells like teen spirit", "Come as You Are - 2021 Remaster")))
        assertFalse(LastFM.catalogsMatch(youTube, listOf("Smells Like Teen Spirit", "Unrelated Song")))
        assertTrue(LastFM.catalogsMatch(listOf("Only Single [feat. Someone]"), listOf("Only Single", "Other")))
        assertFalse(LastFM.catalogsMatch(youTube, emptyList()))
        assertFalse(LastFM.catalogsMatch(emptyList(), youTube))
    }

    @Test
    fun scrapedCatalogTitlesOnlyIncludeTheArtistsOwnTracksAndAlbums() {
        val html =
            """
            <a href="/music/Some+Artist/_/First+Song">
            <a href="/music/Some+Artist/Debut%3A+Album">
            <a href="/music/Some+Artist/+wiki">
            <a href="/music/Other+Artist/_/Not+Theirs">
            <a href="/music/Some+Artist">
            """.trimIndent()

        assertEquals(listOf("First Song", "Debut: Album"), LastFM.extractCatalogTitles(html, "Some Artist"))
    }

    @Test
    fun parsesApiDataAndFallsBackToScrapedData() {
        val info =
            Json
                .parseToJsonElement(
                    """{"tags":{"tag":[{"name":"pop"},{"name":"dance"}]},"stats":{"listeners":"123"}}""",
                ).jsonObject

        assertEquals(
            LastFM.ArtistAudience(listOf("pop", "dance"), "123"),
            LastFM.parseArtistAudience(info, LastFM.ScrapedArtistPage(tags = listOf("fallback"), listeners = "456")),
        )
        assertEquals(
            LastFM.ArtistAudience(listOf("fallback"), "456"),
            LastFM.parseArtistAudience(null, LastFM.ScrapedArtistPage(tags = listOf("fallback"), listeners = "456")),
        )
    }

    @Test
    fun parsesStatsAndSingletonTagsAndSimilarArtistsWithoutTrustingMalformedFields() {
        val info =
            Json
                .parseToJsonElement(
                    """{"stats":{"listeners":"123","playcount":"456"},"tags":{"tag":{"name":"pop"}},"similar":{"artist":[{"name":"Related"},{"name":"Related"},{"name":""},null]}}""",
                ).jsonObject
        assertEquals(LastFM.ArtistAudience(listOf("pop"), "123", "456", listOf("Related")), LastFM.parseArtistAudience(info))
        val malformed =
            Json
                .parseToJsonElement(
                    """{"stats":{"listeners":"-1","playcount":"unknown"},"tags":"","similar":null}""",
                ).jsonObject
        assertEquals(LastFM.ArtistAudience(), LastFM.parseArtistAudience(malformed))
    }

    @Test
    fun artistInfoRequestsTheContentLanguage() =
        runTest {
            var language: String? = null
            val client =
                HttpClient(
                    MockEngine { request ->
                        language = request.url.parameters["lang"]
                        assertEquals("artist.getInfo", request.url.parameters["method"])
                        assertEquals("1", request.url.parameters["autocorrect"])
                        assertEquals("Test Artist", request.url.parameters["artist"])
                        respond("{}")
                    },
                )

            client.use { LastFM.getArtistInfo(it, "Test Artist", "de") }

            assertEquals("de", language)
        }

    @Test
    fun parsesBiographyWithoutHtmlOrReadMoreAndToleratesMissingFields() {
        val info =
            Json
                .parseToJsonElement(
                    """{"bio":{"summary":"Biography <a href='https://www.last.fm/music/Artist'>Read more on Last.fm</a>"}}""",
                ).jsonObject
        assertEquals("Biography", LastFM.parseArtistBio(info, language = "de"))
        for (payload in listOf("{}", """{"bio":null}""", """{"bio":""}""", """{"bio":{"summary":null}}""")) {
            assertNull(LastFM.parseArtistBio(Json.parseToJsonElement(payload).jsonObject, language = "de"))
        }
    }

    @Test
    fun biographyRequiresTheRequestedLanguageEvenWhenTheApiTranslationIsEmpty() {
        val emptyBio = Json.parseToJsonElement("""{"bio":{"summary":" <a>Read more on Last.fm</a>"}}""").jsonObject
        for (info in listOf(null, emptyBio)) {
            for (pageLanguage in listOf("de", "en", null)) {
                val page = LastFM.ScrapedArtistPage(bio = "Biography", language = pageLanguage)
                assertEquals(if (pageLanguage == "de") "Biography" else null, LastFM.parseArtistBio(info, page, "de"))
            }
        }
    }
}
