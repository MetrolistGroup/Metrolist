package com.metrolist.shared.amazonmusicimport

import com.metrolist.shared.playlistimport.ImportErrorKey
import com.metrolist.shared.playlistimport.ImportException
import com.metrolist.shared.playlistimport.PlaylistImportManager.CsvTrack
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AmazonMusicImportManagerTest {
    @Test
    fun regionalAccountUsesSelectedMarketplaceForEveryRequest() =
        runBlocking {
            val paths = mutableListOf<String>()
            HttpClient(
                MockEngine { request ->
                    assertEquals("music.amazon.de", request.url.host)
                    assertEquals("session-token=session", request.headers[HttpHeaders.Cookie])
                    paths += request.url.encodedPath
                    val body =
                        if (request.url.encodedPath.endsWith("/playlists")) {
                            """{"playlists":[]}"""
                        } else {
                            """{"tracks":[{"title":"Track"}]}"""
                        }
                    respond(body)
                },
            ).use { client ->
                AmazonMusicImportManager { client }.use { manager ->
                    assertFailsWith<IllegalArgumentException> {
                        manager.authenticate("session-token=session", "music.amazon.de.example")
                    }
                    manager.authenticate("session-token=session", "music.amazon.de")
                    manager.loadPlaylists("Favorites")
                    manager.loadPlaylist(
                        AmazonMusicImportManager.LoadedPlaylist(
                            "playlist",
                            "Playlist",
                            null,
                            1,
                            AmazonMusicImportManager.PlaylistType.PLAYLIST,
                        ),
                    )
                    assertEquals(listOf("/api/library/playlists", "/api/library/playlists/playlist/tracks"), paths)
                }
            }
        }

    @Test
    fun favoritesAndPlaylistsKeepTheirEndpointsAndShareParsing() =
        runBlocking {
            for (type in AmazonMusicImportManager.PlaylistType.entries) {
                val likedSongs = type == AmazonMusicImportManager.PlaylistType.LIKED_SONGS
                val path =
                    if (likedSongs) {
                        "tracks?maxResults=500&sortColumn=default&sortDirection=ASC"
                    } else {
                        "playlists/playlist/tracks?maxResults=500"
                    }
                var requests = 0
                val progress = mutableListOf<Pair<Int, Int?>>()
                HttpClient(
                    MockEngine { request ->
                        requests++
                        assertEquals("https://music.amazon.com/api/library/$path", request.url.toString())
                        assertEquals("session-token=session", request.headers[HttpHeaders.Cookie])
                        assertEquals("application/json", request.headers[HttpHeaders.Accept])
                        respond(
                            """{"tracks":[{"title":" First ","artist":" Artist "},{"artist":"Skip"},{"title":"Second"}],"nextToken":null}""",
                        )
                    },
                ).use { client ->
                    AmazonMusicImportManager { client }.use { manager ->
                        manager.authenticate("session-token=session", "music.amazon.com")
                        val result =
                            manager.loadPlaylist(
                                AmazonMusicImportManager.LoadedPlaylist("playlist", "Playlist", null, 2, type),
                                likedSongsName = "Favorites",
                            ) { loaded, total -> progress += loaded to total }

                        assertEquals(if (likedSongs) "Favorites" else "Playlist", result.title)
                        assertEquals("Amazon Music", result.source)
                        assertEquals(listOf(CsvTrack("First", "Artist", null), CsvTrack("Second", "", null)), result.tracks)
                        assertEquals(listOf<Pair<Int, Int?>>(2 to 2), progress)
                        assertEquals(1, requests)
                    }
                }
            }
        }

    @Test
    fun failedResponsesAndEmptyTracksHaveStructuredErrors() =
        runBlocking {
            for (type in AmazonMusicImportManager.PlaylistType.entries) {
                for (response in listOf("http", "missing", "oversized", "empty")) {
                    val progress = mutableListOf<Pair<Int, Int?>>()
                    HttpClient(
                        MockEngine {
                            when (response) {
                                "http" -> respond("not json", HttpStatusCode.BadGateway)
                                "missing" -> respond("{}")
                                "oversized" -> respond(" ".repeat(4_194_305), headers = headersOf(HttpHeaders.ContentLength, "4194305"))
                                else -> respond("""{"tracks":[]}""")
                            }
                        },
                    ).use { client ->
                        AmazonMusicImportManager { client }.use { manager ->
                            manager.authenticate("session-token=session", "music.amazon.com")
                            val playlist = AmazonMusicImportManager.LoadedPlaylist("playlist", "Playlist", null, 0, type)
                            val error =
                                assertFailsWith<ImportException> {
                                    manager.loadPlaylist(playlist, "Favorites") { loaded, total -> progress += loaded to total }
                                }
                            assertEquals(
                                if (response ==
                                    "empty"
                                ) {
                                    ImportErrorKey.NoTracksToImport
                                } else {
                                    ImportErrorKey.Generic
                                },
                                error.errorKey,
                            )
                            assertEquals(if (response == "empty") listOf<Pair<Int, Int?>>(0 to 0) else emptyList(), progress)
                            if (response == "http") assertEquals("Amazon Music request failed (502)", error.cause?.message)
                        }
                    }
                }
            }
        }

    @Test
    fun libraryFailuresDoNotMasqueradeAsEmptyLibraries() =
        runBlocking {
            for (body in listOf("http", "oversized", "missing")) {
                HttpClient(
                    MockEngine {
                        when (body) {
                            "http" -> respond("unavailable", HttpStatusCode.ServiceUnavailable)
                            "oversized" -> respond(" ".repeat(4_194_305), headers = headersOf(HttpHeaders.ContentLength, "4194305"))
                            else -> respond("{}")
                        }
                    },
                ).use { client ->
                    AmazonMusicImportManager { client }.use { manager ->
                        manager.authenticate("session-token=session", "music.amazon.com")
                        assertEquals(
                            ImportErrorKey.Generic,
                            assertFailsWith<ImportException> { manager.loadPlaylists("Favorites") }.errorKey,
                        )
                    }
                }
            }
        }

    @Test
    fun cancellationPropagatesFromBothImportPaths() =
        runBlocking {
            for (type in AmazonMusicImportManager.PlaylistType.entries) {
                HttpClient(MockEngine { throw CancellationException("Cancelled request") }).use { client ->
                    AmazonMusicImportManager { client }.use { manager ->
                        manager.authenticate("session-token=session", "music.amazon.com")
                        assertFailsWith<CancellationException> {
                            manager.loadPlaylist(AmazonMusicImportManager.LoadedPlaylist("playlist", "Playlist", null, 1, type)) { _, _ ->
                                error("Cancelled requests must not report progress")
                            }
                        }
                    }
                }
            }
        }

    @Test
    fun closeReleasesOnlyAnInitializedClient() =
        runBlocking {
            var creations = 0
            val client = HttpClient(MockEngine { respond("{}") })
            val unused =
                AmazonMusicImportManager {
                    creations++
                    client
                }
            unused.close()
            assertEquals(0, creations)
            val used =
                AmazonMusicImportManager {
                    creations++
                    client
                }
            used.authenticate("session-token=session", "music.amazon.com")
            assertFailsWith<ImportException> { used.loadPlaylists() }
            used.close()
            used.close()
            withTimeout(5_000) { client.coroutineContext.job.join() }
            assertEquals(1, creations)
            assertEquals(false, used.isAuthenticated)
        }

    @Test
    fun incompleteCollectionsCannotBeImportedSuccessfully() =
        runBlocking {
            for ((count, body) in listOf(
                501 to "{\"tracks\":[{\"title\":\"Song\"}]}",
                1 to "{\"tracks\":[]}",
                0 to "{\"tracks\":[${List(500) { "{\"title\":\"Song\"}" }.joinToString(",")}]}",
                1 to "{\"tracks\":[{\"title\":\"Song\"}],\"nextToken\":\"more\"}",
            )) {
                AmazonMusicImportManager { HttpClient(MockEngine { respond(body) }) }.use { manager ->
                    manager.authenticate("session-token=session", "music.amazon.com")
                    for (type in AmazonMusicImportManager.PlaylistType.entries) {
                        val playlist = AmazonMusicImportManager.LoadedPlaylist("id", "Playlist", null, count, type)
                        assertEquals(
                            ImportErrorKey.IncompleteSource,
                            assertFailsWith<ImportException> { manager.loadPlaylist(playlist) }.errorKey,
                        )
                    }
                }
            }
        }
}
