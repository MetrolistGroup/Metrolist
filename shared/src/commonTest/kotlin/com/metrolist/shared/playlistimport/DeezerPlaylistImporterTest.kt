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

class DeezerPlaylistImporterTest {
    @Test
    fun continuationFailuresDoNotReturnTheFetchedPrefix() =
        runBlocking {
            for ((body, status) in listOf(
                """{"data":[]}""" to HttpStatusCode.ServiceUnavailable,
                """{"error":{"message":"Unavailable"},"data":[]}""" to HttpStatusCode.OK,
                "{}" to HttpStatusCode.OK,
                " ".repeat(8 * 1024 * 1024 + 1) to HttpStatusCode.OK,
            )) {
                withClient(
                    MockEngine { request ->
                        if (request.url.encodedPath == "/playlist/123") respond(initialPage) else respond(body, status)
                    },
                ) {
                    val error = assertIs<ImportException>(DeezerPlaylistImporter.loadPlaylist("123").exceptionOrNull())
                    assertEquals(ImportErrorKey.IncompleteSource, error.errorKey)
                }
            }
        }

    @Test
    fun completePaginationKeepsBothTracks() =
        runBlocking {
            withClient(
                MockEngine { request ->
                    if (request.url.encodedPath == "/playlist/123") {
                        respond(initialPage)
                    } else {
                        respond("""{"data":[{"title":"Second"}]}""")
                    }
                },
            ) {
                assertEquals(
                    listOf("First", "Second"),
                    DeezerPlaylistImporter
                        .loadPlaylist("123")
                        .getOrThrow()
                        .tracks
                        .map { it.title },
                )
            }
        }

    @Test
    fun continuationPreservesCallerCancellation() =
        runBlocking {
            withClient(
                MockEngine { request ->
                    if (request.url.encodedPath == "/playlist/123") {
                        respond(initialPage)
                    } else {
                        throw CancellationException("cancelled")
                    }
                },
            ) {
                assertFailsWith<CancellationException> { DeezerPlaylistImporter.loadPlaylist("123") }
            }
        }

    @Test
    fun albumImportsAllPagesRatherThanItsTruncatedEmbeddedPreview() =
        runBlocking {
            val requests = mutableListOf<String>()
            val progress = mutableListOf<Pair<Int, Int?>>()
            withClient(
                MockEngine { request ->
                    requests += request.url.encodedPath + "?" + request.url.encodedQuery
                    when {
                        request.url.encodedPath == "/album/123" -> respond(albumPreview)
                        request.url.parameters["index"] == "1" -> respond("""{"data":[{"title":"Second"}],"total":2,"next":null}""")
                        else -> respond(albumFirstPage)
                    }
                },
            ) {
                val result =
                    DeezerPlaylistImporter
                        .loadPlaylist("https://www.deezer.com/album/123") { loaded, total ->
                            progress += loaded to total
                        }.getOrThrow()
                assertEquals(listOf("First", "Second"), result.tracks.map { it.title })
                assertEquals(listOf("Track artist", "Album artist"), result.tracks.map { it.artist })
                assertEquals(listOf("/album/123?", "/album/123/tracks?", "/album/123/tracks?index=1"), requests)
                assertEquals(2 to 2, progress.last())
            }
        }

    @Test
    fun albumContinuationFailuresDoNotReturnTheFetchedPrefix() =
        runBlocking {
            for ((body, status) in listOf(
                """{"data":[]}""" to HttpStatusCode.ServiceUnavailable,
                """{"error":{"message":"Unavailable"},"data":[]}""" to HttpStatusCode.OK,
                "{}" to HttpStatusCode.OK,
                " ".repeat(8 * 1024 * 1024 + 1) to HttpStatusCode.OK,
            )) {
                withClient(
                    MockEngine { request ->
                        when {
                            request.url.encodedPath == "/album/123" -> respond(albumPreview)
                            request.url.parameters["index"] == "1" -> respond(body, status)
                            else -> respond(albumFirstPage)
                        }
                    },
                ) {
                    val error =
                        assertIs<ImportException>(
                            DeezerPlaylistImporter.loadPlaylist("https://www.deezer.com/album/123").exceptionOrNull(),
                        )
                    assertEquals(ImportErrorKey.IncompleteSource, error.errorKey)
                }
            }
        }

    @Test
    fun albumContinuationPreservesCallerCancellation() =
        runBlocking {
            withClient(
                MockEngine { request ->
                    when {
                        request.url.encodedPath == "/album/123" -> respond(albumPreview)
                        request.url.parameters["index"] == "1" -> throw CancellationException("cancelled")
                        else -> respond(albumFirstPage)
                    }
                },
            ) {
                assertFailsWith<CancellationException> {
                    DeezerPlaylistImporter.loadPlaylist("https://www.deezer.com/album/123")
                }
            }
        }

    @Test
    fun albumRejectsMissingOrRepeatedPages() =
        runBlocking {
            for (lastPage in listOf(
                """{"data":[],"total":2}""",
                albumFirstPage,
            )) {
                withClient(
                    MockEngine { request ->
                        when {
                            request.url.encodedPath == "/album/123" -> respond(albumPreview)
                            request.url.parameters["index"] == "1" -> respond(lastPage)
                            else -> respond(albumFirstPage)
                        }
                    },
                ) {
                    val error =
                        assertIs<ImportException>(
                            DeezerPlaylistImporter.loadPlaylist("https://www.deezer.com/album/123").exceptionOrNull(),
                        )
                    assertEquals(ImportErrorKey.IncompleteSource, error.errorKey)
                }
            }
        }

    @Test
    fun albumPreservesRepeatedTracksAndMissingTotal() =
        runBlocking {
            withClient(
                MockEngine { request ->
                    if (request.url.encodedPath == "/album/123") {
                        respond("""{"title":"Album","artist":{"name":"Artist"}}""")
                    } else {
                        respond("""{"data":[{"title":"Same"},{"title":"Same"}],"next":null}""")
                    }
                },
            ) {
                val result = DeezerPlaylistImporter.loadPlaylist("https://www.deezer.com/album/123").getOrThrow()
                assertEquals(listOf("Same", "Same"), result.tracks.map { it.title })
                assertEquals(listOf("Artist", "Artist"), result.tracks.map { it.artist })
            }
        }

    @Test
    fun albumDoesNotFetchContinuationFromAnotherHost() =
        runBlocking {
            val hosts = mutableListOf<String>()
            withClient(
                MockEngine { request ->
                    hosts += request.url.host
                    if (request.url.encodedPath == "/album/123") {
                        respond(albumPreview)
                    } else {
                        respond(albumFirstPage.replace("https://api.deezer.com/album/123/tracks?index=1", "https://example.com/tracks"))
                    }
                },
            ) {
                assertIs<IllegalArgumentException>(
                    DeezerPlaylistImporter.loadPlaylist("https://www.deezer.com/album/123").exceptionOrNull(),
                )
                assertEquals(listOf("api.deezer.com", "api.deezer.com"), hosts)
            }
        }

    @Test
    fun albumRejectsEmptyContinuationWithoutFetchingAnotherPage() =
        runBlocking {
            var requests = 0
            withClient(
                MockEngine { request ->
                    requests++
                    if (request.url.encodedPath == "/album/123") {
                        respond(albumPreview)
                    } else {
                        respond("""{"data":[],"next":"https://api.deezer.com/album/123/tracks?index=25"}""")
                    }
                },
            ) {
                val error =
                    assertIs<ImportException>(
                        DeezerPlaylistImporter.loadPlaylist("https://www.deezer.com/album/123").exceptionOrNull(),
                    )
                assertEquals(ImportErrorKey.IncompleteSource, error.errorKey)
                assertEquals(2, requests)
            }
        }

    @Test
    fun albumRejectsOversizedTrackListInsteadOfTruncatingIt() =
        runBlocking {
            val data = List(PlaylistImportManager.MAX_PLAYLIST_TRACKS + 1) { """{"title":"Track $it"}""" }.joinToString(",")
            withClient(
                MockEngine { request ->
                    if (request.url.encodedPath == "/album/123") respond(albumPreview) else respond("""{"data":[$data]}""")
                },
            ) {
                val error =
                    assertIs<ImportException>(
                        DeezerPlaylistImporter.loadPlaylist("https://www.deezer.com/album/123").exceptionOrNull(),
                    )
                assertEquals(ImportErrorKey.TooManyTracks, error.errorKey)
            }
        }

    private val albumPreview =
        """{"title":"Album","artist":{"name":"Album artist"},"nb_tracks":2,"tracks":{"data":[{"title":"First"}]}}"""

    private val albumFirstPage =
        """{"data":[{"title":"First","artist":{"name":"Track artist"}}],"total":2,"next":"https://api.deezer.com/album/123/tracks?index=1"}"""

    private suspend fun withClient(
        engine: MockEngine,
        block: suspend () -> Unit,
    ) {
        val original = DeezerPlaylistImporter.clientProvider
        HttpClient(engine).use { client ->
            DeezerPlaylistImporter.clientProvider = { client }
            try {
                block()
            } finally {
                DeezerPlaylistImporter.clientProvider = original
            }
        }
    }

    private val initialPage =
        """
        {"title":"Playlist","nb_tracks":2,"tracks":{
            "data":[{"title":"First"}],"next":"https://api.deezer.com/playlist/123/tracks?index=1"
        }}
        """.trimIndent()
}
