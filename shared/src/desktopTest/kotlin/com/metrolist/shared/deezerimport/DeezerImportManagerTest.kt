package com.metrolist.shared.deezerimport

import com.metrolist.shared.playlistimport.ImportErrorKey
import com.metrolist.shared.playlistimport.ImportException
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DeezerImportManagerTest {
    @Test
    fun failedPagesRejectPartialPlaylistsAndFavorites() =
        runBlocking {
            for (id in listOf("liked", "playlist")) {
                for (failure in listOf("http", "api", "missing", "oversized")) {
                    var requests = 0
                    HttpClient(
                        MockEngine {
                            requests++
                            if (requests == 1) {
                                respond("""{"data":[{"title":"First"}],"next":"/next"}""")
                            } else {
                                when (failure) {
                                    "http" -> respond("", HttpStatusCode.BadGateway)
                                    "api" -> respond("""{"error":{}}""")
                                    "missing" -> respond("{}")
                                    else -> respond(" ".repeat(8 * 1024 * 1024 + 1))
                                }
                            }
                        },
                    ).use { client ->
                        DeezerImportManager { client }.use { manager ->
                            manager.authenticate("session")
                            assertFailsWith<ImportException> {
                                manager.loadPlaylist(DeezerImportManager.LoadedPlaylist(id, "Playlist", null, 2))
                            }
                            assertEquals(2, requests)
                        }
                    }
                }
            }
        }

    @Test
    fun successfulPaginationReturnsEveryPage() =
        runBlocking {
            var requests = 0
            HttpClient(
                MockEngine {
                    requests++
                    respond(
                        if (requests == 1) {
                            """{"data":[{"title":"First"}],"next":"/next"}"""
                        } else {
                            """{"data":[{"title":"Second"}]}"""
                        },
                    )
                },
            ).use { client ->
                DeezerImportManager { client }.use { manager ->
                    manager.authenticate("session")
                    val result = manager.loadPlaylist(DeezerImportManager.LoadedPlaylist("playlist", "Playlist", null, 2))
                    assertEquals(listOf("First", "Second"), result.tracks.map { it.title })
                }
            }
        }

    @Test
    fun closeReleasesOnlyAnInitializedClient() =
        runBlocking {
            var creations = 0
            val client = HttpClient(MockEngine { respond("{}") })
            val unused =
                DeezerImportManager {
                    creations++
                    client
                }
            unused.close()
            assertEquals(0, creations)
            val used =
                DeezerImportManager {
                    creations++
                    client
                }
            used.authenticate("token")
            assertFailsWith<ImportException> { used.loadPlaylists() }
            used.close()
            used.close()
            withTimeout(5_000) { client.coroutineContext.job.join() }
            assertEquals(1, creations)
            assertEquals(false, used.isAuthenticated)
        }

    @Test
    fun exactTrackLimitRequiresSourceExhaustionForBothCollections() =
        runBlocking {
            val data = List(100_000) { "{\"title\":\"Song\"}" }.joinToString(",")
            for (id in listOf("liked", "playlist")) {
                for (more in listOf(false, true)) {
                    val continuation = if (more) ",\"next\":\"https://api.deezer.com/next\"" else ",\"next\":null"
                    DeezerImportManager { HttpClient(MockEngine { respond("{\"data\":[$data]$continuation}") }) }.use { manager ->
                        manager.authenticate("token")
                        val playlist = DeezerImportManager.LoadedPlaylist(id, "Playlist", null, 100_000)
                        if (more) {
                            assertEquals(
                                ImportErrorKey.TooManyTracks,
                                assertFailsWith<ImportException> { manager.loadPlaylist(playlist) }.errorKey,
                            )
                        } else {
                            assertEquals(100_000, manager.loadPlaylist(playlist).tracks.size)
                        }
                    }
                }
            }
        }
}
