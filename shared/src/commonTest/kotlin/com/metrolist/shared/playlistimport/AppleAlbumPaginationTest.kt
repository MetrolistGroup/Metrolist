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

class AppleAlbumPaginationTest {
    private val firstTracks = List(300) { if (it < 2) "Repeated" else "Track $it" }
    private val continuation = "/v1/catalog/gb/albums/123/tracks?offset=300"

    @Test
    fun albumFollowsTrackRelationshipPagesWithoutDroppingRepeatedTracks() =
        runBlocking {
            var requests = 0
            withAlbumClient { path ->
                if (path == continuation) {
                    requests++
                    page(listOf("Last", "Repeated"))
                } else {
                    album()
                }
            }.use { client ->
                AppleMusicPlaylistImporter.resetClientForTests(client)
                try {
                    val result = load().getOrThrow()
                    assertEquals(firstTracks + listOf("Last", "Repeated"), result.tracks.map { it.title })
                    assertEquals(1, requests)
                    assertTrue(result.tracks.all { it.artist == "Album Artist" })
                } finally {
                    AppleMusicPlaylistImporter.resetClientForTests()
                }
            }
        }

    @Test
    fun failedAlbumContinuationDoesNotReturnAnIncompleteAlbum() =
        runBlocking {
            for (failure in listOf("http", "missing", "empty", "malformed", "cycle", "short", "entry")) {
                withAlbumClient(failure) { path ->
                    if (path != continuation) {
                        album()
                    } else {
                        when (failure) {
                            "missing" -> "{}"
                            "empty" -> page(emptyList())
                            "malformed" -> "{"
                            "entry" -> """{"data":[{}]}"""
                            "cycle" -> page(listOf("Last"), continuation)
                            "short" -> page(listOf("Last"))
                            else -> page(listOf("Last", "Repeated"))
                        }
                    }
                }.use { client ->
                    AppleMusicPlaylistImporter.resetClientForTests(client)
                    try {
                        assertTrue(load().isFailure, failure)
                    } finally {
                        AppleMusicPlaylistImporter.resetClientForTests()
                    }
                }
            }
        }

    @Test
    fun missingAlbumContinuationCannotClaimTheDeclaredTrackTotal() =
        runBlocking {
            withAlbumClient { album(next = null) }.use { client ->
                AppleMusicPlaylistImporter.resetClientForTests(client)
                try {
                    assertEquals(ImportErrorKey.IncompleteSource, assertIs<ImportException>(load().exceptionOrNull()).errorKey)
                } finally {
                    AppleMusicPlaylistImporter.resetClientForTests()
                }
            }
        }

    @Test
    fun untrustedAlbumContinuationIsRejectedWithoutSendingCredentials() =
        runBlocking {
            var unexpectedRequests = 0
            withAlbumClient { path ->
                if (path != "/v1/catalog/gb/albums/123") unexpectedRequests++
                album(next = "https://example.com/tracks?offset=300")
            }.use { client ->
                AppleMusicPlaylistImporter.resetClientForTests(client)
                try {
                    assertTrue(load().isFailure)
                    assertEquals(0, unexpectedRequests)
                } finally {
                    AppleMusicPlaylistImporter.resetClientForTests()
                }
            }
        }

    @Test
    fun albumContinuationPreservesCancellation() =
        runBlocking {
            withAlbumClient { path ->
                if (path == continuation) throw CancellationException("cancelled")
                album()
            }.use { client ->
                AppleMusicPlaylistImporter.resetClientForTests(client)
                try {
                    assertFailsWith<CancellationException> { load() }
                    Unit
                } finally {
                    AppleMusicPlaylistImporter.resetClientForTests()
                }
            }
        }

    @Test
    fun albumWithoutDeclaredTotalStillFollowsItsRelationshipCursor() =
        runBlocking {
            withAlbumClient { path ->
                if (path == continuation) page(listOf("Last", "Repeated")) else album(total = null)
            }.use { client ->
                AppleMusicPlaylistImporter.resetClientForTests(client)
                try {
                    assertEquals(firstTracks + listOf("Last", "Repeated"), load().getOrThrow().tracks.map { it.title })
                } finally {
                    AppleMusicPlaylistImporter.resetClientForTests()
                }
            }
        }

    @Test
    fun albumTrackLimitRequiresACompleteFinalPage() =
        runBlocking {
            val limit = PlaylistImportManager.MAX_PLAYLIST_TRACKS
            for ((size, hasNext) in listOf(limit to false, limit to true, (limit + 1) to false)) {
                var requests = 0
                withAlbumClient {
                    requests++
                    album(next = continuation.takeIf { hasNext }, names = List(size) { "Track" }, total = size)
                }.use { client ->
                    AppleMusicPlaylistImporter.resetClientForTests(client)
                    try {
                        val result = load()
                        assertEquals(size == limit && !hasNext, result.isSuccess)
                        if (result.isSuccess) assertEquals(limit, result.getOrThrow().tracks.size)
                        assertEquals(1, requests)
                    } finally {
                        AppleMusicPlaylistImporter.resetClientForTests()
                    }
                }
            }
        }

    private suspend fun load() = UnifiedPlaylistImporter.parseFromInput("https://music.apple.com/gb/album/123")

    private fun album(
        next: String? = continuation,
        names: List<String> = firstTracks,
        total: Int? = 302,
    ): String {
        val count = total?.let { ""","trackCount":$it""" }.orEmpty()
        val tracks = page(names, next)
        return """{"data":[{"attributes":{"name":"Album","artistName":"Album Artist"$count},"relationships":{"tracks":$tracks}}]}"""
    }

    private fun page(
        names: List<String>,
        next: String? = null,
    ): String {
        val data = names.joinToString(",") { """{"attributes":{"name":"$it"}}""" }
        val cursor = next?.let { ""","next":"$it"""" }.orEmpty()
        return """{"data":[$data]$cursor}"""
    }

    private fun withAlbumClient(
        failure: String? = null,
        response: (String) -> String,
    ): HttpClient =
        HttpClient(
            MockEngine { request ->
                when {
                    request.url.host == "music.apple.com" && request.url.encodedPath == "/assets/index.js" -> {
                        respond("eyJ${"a".repeat(24)}.eyJleHAiOjQxMDAwMDAwMDAwfQ.sig")
                    }

                    request.url.host == "music.apple.com" -> {
                        respond("""<script src="/assets/index.js"></script>""")
                    }

                    else -> {
                        val path =
                            request.url.encodedPath +
                                request.url.encodedQuery
                                    .takeIf { it.isNotEmpty() }
                                    ?.let { "?$it" }
                                    .orEmpty()
                        respond(
                            response(path),
                            if (failure == "http" &&
                                path == continuation
                            ) {
                                HttpStatusCode.ServiceUnavailable
                            } else {
                                HttpStatusCode.OK
                            },
                        )
                    }
                }
            },
        )
}
