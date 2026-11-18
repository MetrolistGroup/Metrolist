package com.metrolist.shared.api

import com.metrolist.innertubex.InnerTube
import com.metrolist.shared.settings.Settings
import com.russhwolf.settings.MapSettings
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UploadedAlbumTest {
    @Test
    fun uploadedAlbumReadsItsOwnShelfAndContinuationWithoutAPublicPlaylist() =
        runBlocking {
            Settings.replaceSharedForTest(Settings(MapSettings()))
            val albumId = "FEmusic_library_privately_owned_release_test"
            var requests = 0
            val client =
                HttpClient(
                    MockEngine { request ->
                        val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                        val response =
                            if (requests++ == 0) {
                                assertEquals(albumId, body["browseId"]?.jsonPrimitive?.content)
                                """{
                  "header":{"musicDetailHeaderRenderer":{"title":{"simpleText":"My album"}}},
                  "contents":{"sectionListRenderer":{"contents":[{"musicShelfRenderer":{
                    "title":{"simpleText":"Uploaded tracks"},
                    "contents":[${row("first")}],
                    "continuations":[{"nextContinuationData":{"continuation":"uploads-next"}}]
                  }}]}}
                }"""
                            } else {
                                assertEquals("uploads-next", body["continuation"]?.jsonPrimitive?.content)
                                """{"continuationContents":{"musicShelfContinuation":{"contents":[${row("second")}]}}}"""
                            }
                        respond(response, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                    },
                ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
            val tube = InnerTube(client, retryDelay = {}).apply { cookie = "SAPISID=test" }
            try {
                val repository = YouTubeRepository(tube)
                val album = assertNotNull(repository.getAlbum(albumId, loadAllPages = false))
                assertNull(album.album.playlistId)
                assertEquals(listOf("first"), album.songs.map { it.id })
                val tracks = assertNotNull(album.playlistPage)
                assertTrue(tracks.hasMore)
                val complete = repository.completePlaylistPage(tracks)
                assertEquals(listOf("first", "second"), complete.songs.map { it.id })
                assertTrue(complete.songs.all { it.isUploaded && it.album?.id == albumId })
                assertEquals(listOf(1, 2), complete.songs.map { it.trackNumber })
                assertEquals(2, requests)
            } finally {
                tube.close()
                client.close()
                Settings.replaceSharedForTest(null)
            }
        }

    private fun row(id: String) =
        """{"musicResponsiveListItemRenderer":{
      "flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"$id"}]}}}],
      "playlistItemData":{"videoId":"$id"},
      "musicVideoType":"MUSIC_VIDEO_TYPE_PRIVATELY_OWNED_TRACK"
    }}"""
}
