package com.metrolist.shared.playlistimport

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AppleMusicPlaylistImporterTest {
    @Test
    fun publicPlaylistContinuationFailureDoesNotReturnPrefix() = checkFailedContinuation("public")

    @Test
    fun libraryPlaylistContinuationFailureDoesNotReturnPrefix() = checkFailedContinuation("tracks")

    @Test
    fun libraryListContinuationFailureDoesNotReturnPrefix() = checkFailedContinuation("library")

    private fun checkFailedContinuation(mode: String) =
        runBlocking {
            for ((body, status) in listOf(
                "{}" to HttpStatusCode.ServiceUnavailable,
                "{}" to HttpStatusCode.OK,
                " ".repeat(8 * 1024 * 1024 + 1) to HttpStatusCode.OK,
            )) {
                withPaginationClient(body, status) {
                    val error = assertIs<ImportException>(loadPage(mode).exceptionOrNull(), mode)
                    assertEquals(ImportErrorKey.IncompleteSource, error.errorKey)
                }
            }
        }

    @Test
    fun successfulPaginationKeepsAllItems() =
        runBlocking {
            for (mode in listOf("public", "tracks", "library")) {
                withPaginationClient("""{"data":[{"id":"second","attributes":{"name":"Second","artistName":"Artist"}}]}""") {
                    assertEquals(listOf("First", "Second"), loadPage(mode).getOrThrow(), mode)
                }
            }
        }

    @Test
    fun continuationPreservesCallerCancellation() =
        runBlocking {
            for (mode in listOf("public", "tracks", "library")) {
                withPaginationClient("", cancelContinuation = true) {
                    assertFailsWith<CancellationException> { loadPage(mode) }
                }
            }
        }

    private suspend fun loadPage(mode: String): Result<List<String>> =
        when (mode) {
            "public" -> {
                AppleMusicPlaylistImporter.loadPlaylist("https://music.apple.com/gb/playlist/test/pl.test").map {
                    it.tracks.map { track ->
                        track.title
                    }
                }
            }

            "tracks" -> {
                AppleMusicPlaylistImporter.loadUserPlaylistTracks("test-user-token", "test", "Playlist").map {
                    it.tracks.map { track ->
                        track.title
                    }
                }
            }

            else -> {
                AppleMusicPlaylistImporter.loadUserPlaylists("test-user-token").map { it.map { playlist -> playlist.name } }
            }
        }

    private suspend fun withPaginationClient(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
        cancelContinuation: Boolean = false,
        block: suspend () -> Unit,
    ) {
        HttpClient(
            MockEngine { request ->
                when {
                    request.url.host == "music.apple.com" && request.url.encodedPath == "/assets/index.js" -> {
                        respond("eyJ${"a".repeat(24)}.eyJleHAiOjQxMDAwMDAwMDAwfQ.sig")
                    }

                    request.url.host == "music.apple.com" -> {
                        respond("""<script src="/assets/index.js"></script>""")
                    }

                    request.url.parameters["offset"] == "1" -> {
                        if (cancelContinuation) throw CancellationException("cancelled")
                        respond(body, status)
                    }

                    request.url.encodedPath == "/v1/catalog/gb/playlists/pl.test" -> {
                        respond("""{"data":[{"attributes":{"name":"Playlist","trackCount":2}}]}""")
                    }

                    else -> {
                        respond(
                            """{"data":[{"id":"first","attributes":{"name":"First","artistName":"Artist"}}],"next":"${request.url.encodedPath}?offset=1"}""",
                        )
                    }
                }
            },
        ).use { client ->
            AppleMusicPlaylistImporter.resetClientForTests(client)
            try {
                block()
            } finally {
                AppleMusicPlaylistImporter.resetClientForTests()
            }
        }
    }

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
