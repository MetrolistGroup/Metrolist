package com.metrolist.shared.playlistimport

import com.metrolist.shared.settings.Settings
import com.metrolist.shared.utils.SystemLocale
import com.russhwolf.settings.MapSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale

class PlaylistImportUrlRegressionTest {
    private lateinit var originalLocale: Locale

    @Before
    fun setUp() {
        originalLocale = Locale.getDefault()
        Locale.setDefault(Locale.ROOT)
        Settings.replaceSharedForTest(Settings(MapSettings()))
    }

    @After
    fun tearDown() {
        Locale.setDefault(originalLocale)
        Settings.replaceSharedForTest(null)
        AppleMusicPlaylistImporter.resetClientForTests()
        AmazonMusicPlaylistImporter.resetClientForTests()
    }

    @Test
    fun appleMusicExplicitStorefrontWorksWithoutSystemCountry() =
        runBlocking {
            Settings.shared.contentCountry = "SYSTEM"
            var catalogRequest: String? = null
            val client =
                HttpClient(
                    MockEngine { request ->
                        when (request.url.toString()) {
                            "https://music.apple.com/browse" -> {
                                respondHtml("<script src=\"/assets/index.js\"></script>")
                            }

                            "https://music.apple.com/assets/index.js" -> {
                                respond("const token = eyJ${"a".repeat(24)}.eyJleHAiOjQxMDAwMDAwMDAwfQ.sig;")
                            }

                            "https://api.music.apple.com/v1/catalog/gb/playlists/pl.123" -> {
                                catalogRequest = request.url.toString()
                                respondJson("""{"data":[{"attributes":{"name":"Playlist","trackCount":1}}]}""")
                            }

                            "https://api.music.apple.com/v1/catalog/gb/playlists/pl.123/tracks?limit=100" -> {
                                respondJson("""{"data":[{"attributes":{"name":"Track","artistName":"Artist"}}]}""")
                            }

                            else -> {
                                error("Unexpected request: ${request.url}")
                            }
                        }
                    },
                )
            AppleMusicPlaylistImporter.resetClientForTests(client)

            val result = AppleMusicPlaylistImporter.loadPlaylist("https://music.apple.com/gb/playlist/name/pl.123")

            assertTrue(result.isSuccess)
            assertEquals("https://api.music.apple.com/v1/catalog/gb/playlists/pl.123", catalogRequest)
            assertEquals(
                "Track",
                result
                    .getOrThrow()
                    .tracks
                    .single()
                    .title,
            )
            client.close()
        }

    @Test
    fun appleMusicBareOrCountrylessInputsStillRequireFallbackStorefront() =
        runBlocking {
            var requests = 0
            val client =
                HttpClient(
                    MockEngine {
                        requests++
                        error("No request expected")
                    },
                )
            AppleMusicPlaylistImporter.resetClientForTests(client)
            val systemLocale = SystemLocale.current
            SystemLocale.current = Locale.ROOT
            try {
                for (input in listOf("pl.123", "https://music.apple.com/playlist/name/pl.123")) {
                    val result = AppleMusicPlaylistImporter.loadPlaylist(input)
                    assertFalse(result.isSuccess)
                    assertTrue(result.exceptionOrNull() is ImportException)
                }
            } finally {
                SystemLocale.current = systemLocale
            }

            assertEquals(0, requests)
            client.close()
        }

    @Test
    fun amazonMusicPreservesRegionalHostAndUserPlaylistRoute() =
        runBlocking {
            val urls =
                listOf(
                    "https://music.amazon.co.uk/user-playlists/B012345678" to "https://music.amazon.co.uk/user-playlists/B012345678",
                    "https://music.amazon.co.uk/user-playlists/B012345678/" to "https://music.amazon.co.uk/user-playlists/B012345678/",
                    "https://music.amazon.co.uk/userplaylists/B012345678" to "https://music.amazon.co.uk/userplaylists/B012345678",
                    "https://music.amazon.ie/user-playlists/B012345678" to "https://music.amazon.ie/user-playlists/B012345678",
                    "https://Music.Amazon.com/playlists/B012345678" to "https://music.amazon.com/playlists/B012345678",
                    "music.amazon.com/playlists/B012345678" to "https://music.amazon.com/playlists/B012345678",
                    "Music.Amazon.co.uk/user-playlists/B012345678" to "https://music.amazon.co.uk/user-playlists/B012345678",
                )
            for ((inputUrl, expectedUrl) in urls) {
                val client =
                    HttpClient(
                        MockEngine { request ->
                            assertEquals(expectedUrl, request.url.toString())
                            respond(
                                "<script type=\"application/ld+json\">{\"name\":\"Playlist\",\"trackList\":[{\"name\":\"Track\",\"artist\":\"Artist\"}]}</script>",
                                headers = headersOf(HttpHeaders.ContentType, "text/html"),
                            )
                        },
                    )
                AmazonMusicPlaylistImporter.resetClientForTests(client)

                val result = AmazonMusicPlaylistImporter.loadPlaylist(inputUrl)

                assertTrue(result.isSuccess)
                assertEquals(
                    "Track",
                    result
                        .getOrThrow()
                        .tracks
                        .single()
                        .title,
                )
                client.close()
            }
        }

    @Test
    fun amazonMusicRejectsMalformedAndNonAmazonUrlsBeforeRequesting() =
        runBlocking {
            var requests = 0
            val client =
                HttpClient(
                    MockEngine {
                        requests++
                        error("No request expected")
                    },
                )
            AmazonMusicPlaylistImporter.resetClientForTests(client)

            val invalidUrls =
                listOf(
                    "not a URL",
                    "https://evil.example/user-playlists/B012345678",
                    "https://music.amazon.com.evil.example/user-playlists/B012345678",
                    "music.amazon.com.evil.example/user-playlists/B012345678",
                    "music.amazon.com@evil.example/user-playlists/B012345678",
                    "https://music.amazon.xyz/playlists/B012345678",
                    "http://music.amazon.com/user-playlists/B012345678",
                    "https://music.amazon.com/user-playlists/../../B012345678",
                    "https://music.amazon.com/not-a-playlist/B012345678",
                    "https://music.amazon.com:8443/user-playlists/B012345678",
                    "https://user@music.amazon.com/user-playlists/B012345678",
                    "https://evil.example/B012345678",
                )
            for (url in invalidUrls) {
                val result = AmazonMusicPlaylistImporter.loadPlaylist(url)
                assertTrue("Expected rejection for $url", result.exceptionOrNull() is IllegalArgumentException)
            }

            assertEquals(0, requests)
            client.close()
        }

    private fun MockRequestHandleScope.respondHtml(body: String) = respond(body, headers = headersOf(HttpHeaders.ContentType, "text/html"))

    private fun MockRequestHandleScope.respondJson(body: String) =
        respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
}
