package com.metrolist.shared.api

import com.metrolist.innertubex.InnerTube
import com.metrolist.shared.model.PlaylistItem
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaylistBrowseIdTest {
    @Test
    fun userPlaylistFilterExcludesSystemCollections() {
        fun playlist(id: String) = PlaylistItem(id = id, title = id, thumbnail = null)

        assertTrue(playlist("VLPL_user").isUserPlaylist())
        listOf("LM", "VLLM", "RDPN", "VLRDPN", "SE", "VLSE").forEach {
            assertFalse(playlist(it).isUserPlaylist())
        }
    }

    @Test
    fun playlistIdsBrowseThroughTheirPlaylistPage() {
        // Library sync stores New Episodes as RDPN; YouTube Music rejects that bare ID with HTTP 400.
        assertEquals("VLRDPN", "RDPN".toPlaylistBrowseId())
        assertEquals("VLRDPN", "VLRDPN".toPlaylistBrowseId())
        assertEquals("VLPLUzfTGTE3aPU", "PLUzfTGTE3aPU".toPlaylistBrowseId())
        assertEquals("VLRDAMVLabc", "RDAMVLabc".toPlaylistBrowseId())
        assertEquals("FEmusic_library_privately_owned_tracks", "FEmusic_library_privately_owned_tracks".toPlaylistBrowseId())
        assertEquals("LM:account", "LM:account".toPlaylistBrowseId())
    }

    @Test
    fun failedBrowseDoesNotCreateGenericPlaylist() =
        runBlocking {
            val client =
                HttpClient(
                    MockEngine {
                        respond(
                            content = """{"error":{"code":400,"status":"INVALID_ARGUMENT"}}""",
                            status = HttpStatusCode.BadRequest,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
                ) {
                    install(ContentNegotiation) {
                        json(Json { ignoreUnknownKeys = true })
                    }
                }

            val result = YouTubeRepository(InnerTube(client)).getPlaylist("PLAtJIkhj_beD7LsfPnMsTJjl_O5wkNYVx")

            assertTrue(result.isEmpty())
            client.close()
        }
}
