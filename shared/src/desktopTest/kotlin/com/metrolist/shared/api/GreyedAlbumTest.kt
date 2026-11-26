package com.metrolist.shared.api

import com.metrolist.innertubex.InnerTube
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GreyedAlbumTest {
    @Test
    fun albumFallsBackToItsBrowseShelfAndKeepsPremiumUpsellTracksPlayable() =
        runBlocking {
            val album = checkNotNull(javaClass.getResourceAsStream("/youtube/greyed-album.json")).bufferedReader().use { it.readText() }
            val client =
                HttpClient(
                    MockEngine { request ->
                        val browseId =
                            (request.body as? TextContent)
                                ?.text
                                ?.let {
                                    Json
                                        .parseToJsonElement(it)
                                        .jsonObject["browseId"]
                                        ?.jsonPrimitive
                                        ?.content
                                }
                        // The album's playlist is empty even though its browse shelf still has track IDs and durations.
                        respond(if (browseId == ALBUM_ID) album else "{}", headers = headersOf(HttpHeaders.ContentType, "application/json"))
                    },
                ) { install(ContentNegotiation) { json() } }
            try {
                val page = assertNotNull(YouTubeRepository(InnerTube(client)).getAlbum(ALBUM_ID))
                assertEquals(10, page.songs.size)
                assertTrue(page.songs.none { it.isUnavailable })
                assertEquals((1..10).toList(), page.songs.map { it.trackNumber })
                assert(page.songs.all { it.artists.isNotEmpty() && it.album?.id == ALBUM_ID })
            } finally {
                client.close()
            }
        }

    private companion object {
        const val ALBUM_ID = "MPREb_W5dnW0IWbjr"
    }
}
