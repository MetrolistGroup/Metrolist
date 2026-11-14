package com.metrolist.shared.playlistimport

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AppleMusicPlaylistImporterTest {
    @Test
    fun tokenBootstrapAcceptsCurrentBrowsePagesAndRejectsOversizedResponses() =
        runBlocking {
            for (pageBytes in listOf(3 * 1024 * 1024, 4 * 1024 * 1024 + 1)) {
                val script = """<script src="/assets/index.js"></script>"""
                val page = " ".repeat(pageBytes - script.length) + script
                var catalogRequests = 0
                HttpClient(
                    MockEngine { request ->
                        when (request.url.host) {
                            "music.apple.com" -> {
                                if (request.url.encodedPath == "/assets/index.js") {
                                    respond("eyJ${"a".repeat(24)}.eyJleHAiOjQxMDAwMDAwMDAwfQ.sig")
                                } else {
                                    respond(page)
                                }
                            }

                            "api.music.apple.com" -> {
                                catalogRequests++
                                respond(
                                    """{"data":[{"attributes":{"name":"Album","artistName":"Artist"},"relationships":{"tracks":{"data":[{"attributes":{"name":"Track","artistName":"Artist"}}]}}}]}""",
                                    HttpStatusCode.OK,
                                )
                            }

                            else -> {
                                error("Unexpected request: ${request.url}")
                            }
                        }
                    },
                ).use { client ->
                    AppleMusicPlaylistImporter.resetClientForTests(client)
                    try {
                        val result = AppleMusicPlaylistImporter.loadPlaylist("https://music.apple.com/gb/album/123")
                        val accepted = pageBytes <= 4 * 1024 * 1024
                        assertEquals(accepted, result.isSuccess, "Browse page size: $pageBytes")
                        assertEquals(if (accepted) 1 else 0, catalogRequests)
                        if (accepted) assertTrue(result.getOrThrow().tracks.any { it.title == "Track" })
                    } finally {
                        AppleMusicPlaylistImporter.resetClientForTests()
                    }
                }
            }
        }
}
