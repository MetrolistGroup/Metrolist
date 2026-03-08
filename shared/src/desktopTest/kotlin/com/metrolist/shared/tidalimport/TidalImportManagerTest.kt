package com.metrolist.shared.tidalimport

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

class TidalImportManagerTest {
    @Test
    fun favoritesAndPlaylistsSharePaginationAndParsing() =
        runBlocking {
            for (type in TidalImportManager.PlaylistType.entries) {
                val favorites = type == TidalImportManager.PlaylistType.FAVORITES
                val path = if (favorites) "users/user/favorites/tracks" else "playlists/playlist/tracks"
                val offsets = mutableListOf<Int>()
                val progress = mutableListOf<Pair<Int, Int?>>()
                HttpClient(
                    MockEngine { request ->
                        val offset = request.url.parameters["offset"]!!.toInt()
                        offsets += offset
                        assertEquals("https://api.tidal.com/v1/$path?offset=$offset&limit=50", request.url.toString())
                        assertEquals("session", request.headers["x-tidal-session-id"])
                        respond(
                            when (offset) {
                                0 -> """{"items":[{"title":" First ","artist":{"name":" Artist "}},{"artist":{"name":"Skip"}}]}"""
                                50 -> """{"items":[{"title":"Second"}]}"""
                                else -> """{"items":[]}"""
                            },
                        )
                    },
                ).use { client ->
                    TidalImportManager { client }.use { manager ->
                        manager.authenticate("session", "user")
                        val result =
                            manager.loadPlaylist(
                                TidalImportManager.LoadedPlaylist("playlist", "Playlist", null, 2, type),
                                likedSongsName = "Favorites",
                            ) { loaded, total -> progress += loaded to total }

                        assertEquals(if (favorites) "Favorites" else "Playlist", result.title)
                        assertEquals("Tidal", result.source)
                        assertEquals(listOf(CsvTrack("First", "Artist", null), CsvTrack("Second", "", null)), result.tracks)
                        assertEquals(listOf(0, 50, 100), offsets)
                        assertEquals(listOf<Pair<Int, Int?>>(1 to null, 2 to null), progress)
                    }
                }
            }
        }

    @Test
    fun failedPagesRejectPartialResultsAndEmptyPagesCompleteImports() =
        runBlocking {
            for (type in TidalImportManager.PlaylistType.entries) {
                for (terminal in listOf("http", "api", "missing", "empty", "oversized")) {
                    for (partial in listOf(false, true)) {
                        var requests = 0
                        val progress = mutableListOf<Pair<Int, Int?>>()
                        HttpClient(
                            MockEngine {
                                requests++
                                if (partial && requests == 1) {
                                    respond("""{"items":[{"title":"First"}]}""")
                                } else {
                                    when (terminal) {
                                        "http" -> respond("not json", HttpStatusCode.BadGateway)
                                        "api" -> respond("""{"errorCode":1,"items":[{"title":"Ignored"}]}""")
                                        "missing" -> respond("{}")
                                        "empty" -> respond("""{"items":[]}""")
                                        else -> respond(" ".repeat(4_194_305), headers = headersOf(HttpHeaders.ContentLength, "4194305"))
                                    }
                                }
                            },
                        ).use { client ->
                            TidalImportManager { client }.use { manager ->
                                manager.authenticate("session", "user")
                                val playlist = TidalImportManager.LoadedPlaylist("playlist", "Playlist", null, 1, type)
                                if (partial && terminal == "empty") {
                                    val result =
                                        manager.loadPlaylist(
                                            playlist,
                                            "Favorites",
                                        ) { loaded, total -> progress += loaded to total }
                                    assertEquals(listOf(CsvTrack("First", "", null)), result.tracks)
                                    assertEquals(listOf<Pair<Int, Int?>>(1 to null), progress)
                                    assertEquals(2, requests)
                                } else {
                                    val error =
                                        assertFailsWith<ImportException> {
                                            manager.loadPlaylist(playlist, "Favorites") { loaded, total ->
                                                progress += loaded to total
                                            }
                                        }
                                    if (terminal == "empty") assertEquals(ImportErrorKey.NoTracksToImport, error.errorKey)
                                    assertEquals(if (partial) 2 else 1, requests)
                                }
                            }
                        }
                    }
                }
            }
        }

    @Test
    fun cancellationStopsBothImportPaths() =
        runBlocking {
            for (type in TidalImportManager.PlaylistType.entries) {
                var requests = 0
                HttpClient(
                    MockEngine {
                        requests++
                        respond("""{"items":[{"title":"First"}]}""")
                    },
                ).use { client ->
                    TidalImportManager { client }.use { manager ->
                        manager.authenticate("session", "user")
                        assertFailsWith<CancellationException> {
                            manager.loadPlaylist(TidalImportManager.LoadedPlaylist("playlist", "Playlist", null, 1, type)) { _, _ ->
                                throw CancellationException("Cancelled import")
                            }
                        }
                        assertEquals(1, requests)
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
                TidalImportManager {
                    creations++
                    client
                }
            unused.close()
            assertEquals(0, creations)
            val used =
                TidalImportManager {
                    creations++
                    client
                }
            used.authenticate("session", "user")
            assertFailsWith<ImportException> { used.loadPlaylists() }
            used.close()
            used.close()
            withTimeout(5_000) { client.coroutineContext.job.join() }
            assertEquals(1, creations)
            assertEquals(false, used.isAuthenticated)
        }

    @Test
    fun exactTrackLimitChecksTheNextPageForBothCollections() =
        runBlocking {
            val data = List(100_000) { "{\"title\":\"Song\"}" }.joinToString(",")
            for (type in TidalImportManager.PlaylistType.entries) {
                for (more in listOf(false, true)) {
                    var requests = 0
                    TidalImportManager {
                        HttpClient(
                            MockEngine {
                                requests++
                                respond(
                                    if (requests ==
                                        1
                                    ) {
                                        "{\"items\":[$data]}"
                                    } else if (more) {
                                        "{\"items\":[{\"title\":\"Extra\"}]}"
                                    } else {
                                        "{\"items\":[]}"
                                    },
                                )
                            },
                        )
                    }.use { manager ->
                        manager.authenticate("session", "user")
                        val playlist = TidalImportManager.LoadedPlaylist("id", "Playlist", null, 100_000, type)
                        if (more) {
                            assertEquals(
                                ImportErrorKey.TooManyTracks,
                                assertFailsWith<ImportException> { manager.loadPlaylist(playlist) }.errorKey,
                            )
                        } else {
                            assertEquals(100_000, manager.loadPlaylist(playlist).tracks.size)
                        }
                        assertEquals(2, requests)
                    }
                }
            }
        }
}
