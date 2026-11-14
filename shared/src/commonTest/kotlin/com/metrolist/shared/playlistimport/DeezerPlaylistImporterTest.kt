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
