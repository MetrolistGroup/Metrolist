package com.metrolist.shared.viewmodels

import com.metrolist.shared.api.lastfm.LastFM
import com.metrolist.shared.ui.screens.artistGalleryPhotos
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val DAY_MILLIS = 24 * 60 * 60 * 1_000L
internal const val ARTIST_PHOTO = "https://lastfm-img.freetls.fastly.net/i/u/ar0/photo"
internal const val ARTIST_GALLERY = """<li class="image-list-item"><img src="$ARTIST_PHOTO"></li>"""

internal fun TestScope.lastFmArtistClient(
    gallery: suspend () -> String = { ARTIST_GALLERY },
    beforeRequest: () -> Unit = {},
) = HttpClient(MockEngine) {
    engine {
        dispatcher = StandardTestDispatcher(testScheduler)
        addHandler { request ->
            beforeRequest()
            respond(
                when {
                    request.url.host == "ws.audioscrobbler.com" -> {
                        val summary = if (request.url.parameters["lang"] == "en") "Biography" else ""
                        """{"artist":{"bio":{"summary":"$summary"},"stats":{"listeners":"123","playcount":"456"},"tags":{"tag":[{"name":"pop"}]},"similar":{"artist":[{"name":"Related artist"}]}}}"""
                    }

                    request.url.encodedPath.endsWith("/+images") -> {
                        gallery()
                    }

                    else -> {
                        """<html lang="${request.headers["Accept-Language"]}"><a href="/music/Artist/_/Song"></a></html>"""
                    }
                },
            )
        }
    }
}

internal fun TestScope.lastFmScrapingClient(
    info: String = "{}",
    gallery: String = LastFmArtistFixtures.gallery,
    requests: MutableList<HttpRequestData> = mutableListOf(),
    beforeRequest: suspend () -> Unit = {},
) = HttpClient(MockEngine) {
    engine {
        dispatcher = StandardTestDispatcher(testScheduler)
        addHandler { request ->
            requests += request
            beforeRequest()
            if (request.url.host == "ws.audioscrobbler.com") {
                respond(info, headers = headersOf(HttpHeaders.ContentType, "application/json"))
            } else {
                respond(
                    if (request.url.encodedPath.endsWith("/+images")) gallery else LastFmArtistFixtures.overview,
                    headers = headersOf(HttpHeaders.ContentType, "text/html"),
                )
            }
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class LastFmArtistCacheTest {
    @Test
    fun unverifiedPagesKeepTheCacheEmptyAndCanRetry() =
        runTest {
            var requests = 0
            var stored = "{}"
            HttpClient(
                MockEngine { request ->
                    requests++
                    when (request.url.host) {
                        "ws.audioscrobbler.com" -> {
                            respond("""{"error":6,"message":"Artist not found"}""")
                        }

                        else -> {
                            assertTrue(request.url.encodedPath == "/music/Artist")
                            respond("Client Challenge", HttpStatusCode(600, "Challenge"))
                        }
                    }
                },
            ).use { client ->
                val cache =
                    LastFmArtistCache({ stored }, { stored = it }, { 1L }, { "en" }, dispatcher = StandardTestDispatcher(testScheduler))
                cache.refresh("id", "Artist", listOf("Song"), client)
                assertNull(cache.get("id"))
                LastFmArtistCache({
                    stored
                }, {}, { 1L }, { "en" }, dispatcher = StandardTestDispatcher(testScheduler)).refresh("id", "Artist", listOf("Song"), client)
                assertEquals(4, requests)
            }
        }

    @Test
    fun persistedArtistsRefreshOnExpiryOrLanguageChange() =
        runTest {
            var stored = "{}"
            var now = 1L
            var language = "en"
            var requests = 0

            fun cache() =
                LastFmArtistCache({ stored }, { stored = it }, { now }, { language }, dispatcher = StandardTestDispatcher(testScheduler))
            lastFmArtistClient(beforeRequest = { requests++ }).use { client ->
                cache().refresh("id", "Artist", listOf("Song"), client)
                val restored = cache()
                assertEquals(listOf(ArtistPhoto(ARTIST_PHOTO)), restored.get("id")?.photos)
                assertEquals("Biography", restored.get("id")?.summary)
                assertEquals("123", restored.get("id")?.listeners)
                assertEquals("456", restored.get("id")?.scrobbles)
                assertEquals(listOf("pop"), restored.get("id")?.tags)
                assertEquals(listOf("Related artist"), restored.get("id")?.similarArtists)
                val initialRequests = requests
                restored.refresh("id", "Artist", listOf("Song"), client)
                assertEquals(initialRequests, requests)
                now += DAY_MILLIS
                restored.refresh("id", "Artist", listOf("Song"), client)
                assertEquals(initialRequests * 2, requests)
                language = "de"
                assertNull(restored.get("id")?.summary)
                assertEquals(listOf(ArtistPhoto(ARTIST_PHOTO)), restored.get("id")?.photos)
                restored.refresh("id", "Artist", listOf("Song"), client)
                assertEquals(initialRequests * 3, requests)
                assertNull(restored.get("id")?.summary)
                restored.refresh("id", "Artist", listOf("Song"), client)
                assertEquals(initialRequests * 3, requests)
            }
        }

    @Test
    fun blockedAndEmptyGalleriesKeepStoredPhotosAndRetry() =
        runTest {
            var stored = "{}"
            var now = 1L
            var gallery = ARTIST_GALLERY
            var requests = 0

            fun cache() =
                LastFmArtistCache({ stored }, { stored = it }, { now }, { "en" }, dispatcher = StandardTestDispatcher(testScheduler))
            lastFmArtistClient({ gallery }, { requests++ }).use { client ->
                cache().refresh("id", "Artist", listOf("Song"), client)
                for (emptyGallery in listOf("<title>Client Challenge</title>", "<html></html>")) {
                    now += DAY_MILLIS
                    gallery = emptyGallery
                    cache().refresh("id", "Artist", listOf("Song"), client)
                    val before = requests
                    val restored = cache()
                    restored.refresh("id", "Artist", listOf("Song"), client)
                    assertTrue(requests > before)
                    assertEquals(listOf(ArtistPhoto(ARTIST_PHOTO)), restored.get("id")?.photos)
                    assertEquals("Biography", restored.get("id")?.summary)
                }
                now += DAY_MILLIS
                gallery = ARTIST_GALLERY
                val refreshed = cache()
                refreshed.refresh("id", "Artist", listOf("Song"), client)
                assertEquals(listOf(ArtistPhoto(ARTIST_PHOTO)), refreshed.get("id")?.photos)
            }
        }

    @Test
    fun mismatchedArtistsStayUncachedAndOldArtistsAreEvicted() =
        runTest {
            val cache = LastFmArtistCache({ "{}" }, {}, { 1L }, { "en" }, dispatcher = StandardTestDispatcher(testScheduler))
            lastFmArtistClient().use { client ->
                cache.refresh("unrelated", "Artist", listOf("Other"), client)
                assertNull(cache.get("unrelated"))
                repeat(51) { cache.refresh("$it", "Artist", listOf("Song"), client) }
                assertNull(cache.get("0"))
                assertEquals("Biography", cache.get("50")?.summary)
            }
        }

    @Test
    fun cancelledGalleryCanReloadAfterChangingLanguage() =
        runTest {
            var stored = "{}"
            var language = "en"
            var cancel = false
            val cache =
                LastFmArtistCache({ stored }, { stored = it }, { 1L }, { language }, dispatcher = StandardTestDispatcher(testScheduler))
            lastFmArtistClient({
                if (cancel) throw CancellationException("Screen closed")
                ARTIST_GALLERY
            }).use { client ->
                cache.refresh("id", "Artist", listOf("Song"), client)
                language = "de"
                cancel = true
                assertFailsWith<CancellationException> { cache.refresh("id", "Artist", listOf("Song"), client) }
                cancel = false
                val restored = LastFmArtistCache({ stored }, {}, { 1L }, { language }, dispatcher = StandardTestDispatcher(testScheduler))
                restored.refresh("id", "Artist", listOf("Song"), client)
                assertEquals(listOf(ArtistPhoto(ARTIST_PHOTO)), restored.get("id")?.photos)
            }
        }

    @Test
    fun refreshFetchesOnlyMetadataOverviewAndFirstPhotoPage() =
        runTest {
            val urls = mutableListOf<String>()
            HttpClient(
                MockEngine { request ->
                    urls += request.url.toString()
                    respond(
                        when {
                            request.url.host == "ws.audioscrobbler.com" -> "{}"
                            request.url.encodedPath.endsWith("/+images") -> ARTIST_GALLERY
                            else -> """<html lang="en"><p class="truncate-4-lines">Biography</p><a href="/music/Artist/_/Song"></a>"""
                        },
                    )
                },
            ).use { client ->
                val cache = LastFmArtistCache({ "{}" }, {}, { 1L }, { "en" }, dispatcher = StandardTestDispatcher(testScheduler))
                assertTrue(urls.isEmpty())
                cache.refresh("id", "Artist", listOf("Song"), client)
                assertEquals(3, urls.size)
                assertTrue(urls[0].contains("method=artist.getInfo"))
                assertEquals("https://www.last.fm/music/Artist", urls[1])
                assertEquals("https://www.last.fm/music/Artist/+images", urls[2])
                assertEquals(listOf(ArtistPhoto(ARTIST_PHOTO)), cache.get("id")?.photos)
            }
        }

    @Test
    fun publicScrapingFillsTheGalleryWithoutLastFmLoginOrApiMetadata() =
        runTest {
            val previousSession = LastFM.sessionKey
            LastFM.sessionKey = null
            try {
                val requests = mutableListOf<HttpRequestData>()
                var stored = """{"artist":{"data":{},"fetchedAtMillis":1,"language":"en"}}"""
                val cache =
                    LastFmArtistCache({ stored }, { stored = it }, { 1L }, { "en" }, dispatcher = StandardTestDispatcher(testScheduler))
                lastFmScrapingClient(requests = requests).use { client ->
                    cache.refresh("artist", "Artist", listOf("Song", "Album"), client)
                    val data = cache.get("artist")!!
                    val photos = artistGalleryPhotos("Artist", "youtube-thumbnail", data.photos)
                    assertEquals(
                        listOf(LastFmArtistFixtures.FIRST_PHOTO, LastFmArtistFixtures.SECOND_PHOTO),
                        photos.take(2).map(ArtistPhoto::url),
                    )
                    assertEquals(3, photos.size)
                    assertEquals("https://www.last.fm/music/Artist/+images/11111111111111111111111111111111", photos.first().pageUrl)
                    assertEquals("Public artist biography", data.summary)
                    assertEquals(listOf("indie pop"), data.tags)
                    assertEquals("123", data.listeners)
                    assertEquals("456", data.scrobbles)
                    assertEquals(
                        data,
                        LastFmArtistCache({
                            stored
                        }, {}, contentLanguage = { "en" }, dispatcher = StandardTestDispatcher(testScheduler)).get("artist"),
                    )
                    assertEquals(3, requests.size)
                    requests.forEach { request ->
                        assertNull(request.headers[HttpHeaders.Authorization])
                        assertNull(request.headers[HttpHeaders.Cookie])
                        assertNull(request.url.parameters["sk"])
                        assertNull(request.url.parameters["token"])
                    }
                    assertNull(LastFM.sessionKey)
                }
            } finally {
                LastFM.sessionKey = previousSession
            }
        }

    @Test
    fun similarArtistsReachTheAboutStateWithScrapedPhotos() =
        runTest {
            for (similar in listOf("""[{"name":"Related artist"}]""", """{"name":"Related artist"}""")) {
                lastFmScrapingClient(info = LastFmArtistFixtures.metadata(similar)).use { client ->
                    val viewModel =
                        AboutArtistViewModel(
                            "artist",
                            "Artist",
                            backgroundScope,
                            client,
                            LastFmArtistCache({ "{}" }, {}, { 1L }, { "en" }, dispatcher = StandardTestDispatcher(testScheduler)),
                        )
                    viewModel.load(listOf("Song", "Album"))
                    runCurrent()
                    val data = viewModel.uiState.value.lastFmData!!
                    assertEquals(listOf("Related artist"), data.similarArtists)
                    assertEquals("API biography", data.summary)
                    assertEquals("789", data.listeners)
                    assertEquals("987", data.scrobbles)
                    assertEquals(LastFmArtistFixtures.FIRST_PHOTO, data.photos.first().url)
                    assertTrue(!viewModel.uiState.value.isLoading)
                }
            }
        }

    @Test
    fun challengedGalleryUsesTheRegisteredRendererAndOverviewImagesRemainAvailable() =
        runTest {
            val previous = LastFM.renderedPageLoader
            val rendered = mutableListOf<String>()
            try {
                LastFM.renderedPageLoader = { url ->
                    rendered += url
                    LastFmArtistFixtures.gallery
                }
                lastFmScrapingClient(gallery = "<title>Client Challenge</title>").use { client ->
                    val cache = LastFmArtistCache({ "{}" }, {}, { 1L }, { "en" }, dispatcher = StandardTestDispatcher(testScheduler))
                    cache.refresh("artist", "Artist", listOf("Song", "Album"), client)
                    assertEquals(
                        LastFmArtistFixtures.FIRST_PHOTO,
                        cache
                            .get("artist")
                            ?.photos
                            ?.first()
                            ?.url,
                    )
                    assertEquals(listOf("https://www.last.fm/music/Artist/+images"), rendered)
                }
                LastFM.renderedPageLoader = null
                lastFmScrapingClient(gallery = "<html></html>").use { client ->
                    val photos = LastFM.getArtistPhotos(client, "Artist", "en")
                    assertEquals(listOf(LastFmArtistFixtures.AVATAR.replace("avatar170s", "ar0")), photos)
                }
            } finally {
                LastFM.renderedPageLoader = previous
            }
        }

    @Test
    fun arrivingCatalogTitlesReplaceAnUnvalidatedInFlightLoad() =
        runTest {
            var waitingForCatalog = true
            lastFmScrapingClient(beforeRequest = { if (waitingForCatalog) awaitCancellation() }).use { client ->
                val viewModel =
                    AboutArtistViewModel(
                        "artist",
                        "Artist",
                        backgroundScope,
                        client,
                        LastFmArtistCache({ "{}" }, {}, { 1L }, { "en" }, dispatcher = StandardTestDispatcher(testScheduler)),
                    )
                viewModel.load(emptyList())
                runCurrent()
                waitingForCatalog = false
                viewModel.load(listOf("Song", "Album"))
                runCurrent()
                assertEquals(
                    LastFmArtistFixtures.FIRST_PHOTO,
                    viewModel.uiState.value.lastFmData
                        ?.photos
                        ?.firstOrNull()
                        ?.url,
                )
                assertTrue(!viewModel.uiState.value.isLoading)
            }
        }

    @Test
    fun publicApiCatalogMatchesKeepPhotosAndSimilarArtistsAvailableWhenTheOverviewIsBlocked() =
        runTest {
            LastFM.initialize("api-key", "secret")
            val requests = mutableListOf<HttpRequestData>()
            HttpClient(
                MockEngine { request ->
                    requests += request
                    when (request.url.parameters["method"]) {
                        "artist.getInfo" -> {
                            respond(LastFmArtistFixtures.metadata("""[{"name":"Related artist"}]"""))
                        }

                        "artist.getTopTracks" -> {
                            respond("""{"toptracks":{"track":[{"name":"Song"}]}}""")
                        }

                        "artist.getTopAlbums" -> {
                            respond("""{"topalbums":{"album":[{"name":"Album"}]}}""")
                        }

                        else -> {
                            if (request.url.encodedPath.endsWith("/+images")) {
                                respond(LastFmArtistFixtures.gallery, headers = headersOf(HttpHeaders.ContentType, "text/html"))
                            } else {
                                respond("Client Challenge", HttpStatusCode(600, "Challenge"))
                            }
                        }
                    }
                },
            ).use { client ->
                val cache = LastFmArtistCache({ "{}" }, {}, { 1L }, { "en" }, dispatcher = StandardTestDispatcher(testScheduler))
                cache.refresh("artist", "Artist", listOf("Song", "Album"), client)
                assertEquals(
                    LastFmArtistFixtures.FIRST_PHOTO,
                    cache
                        .get("artist")
                        ?.photos
                        ?.firstOrNull()
                        ?.url,
                )
                assertEquals(listOf("Related artist"), cache.get("artist")?.similarArtists)
                assertEquals("API biography", cache.get("artist")?.summary)
                assertEquals(4, requests.size)
                assertTrue(requests.none { it.url.encodedPath == "/music/Artist" || it.url.parameters["sk"] != null })
            }
        }
}
