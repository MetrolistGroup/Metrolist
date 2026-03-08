package com.metrolist.shared.api

import com.metrolist.innertubex.InnerTube
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.model.SongItem
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
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class MixPlaylistTest {
    @BeforeTest
    fun setUp() = Settings.replaceSharedForTest(Settings(MapSettings()))

    @AfterTest
    fun tearDown() = Settings.replaceSharedForTest(null)

    @Test
    fun mixUsesPlaylistBrowseEndpointAndOnlyPaginatesTrackShelves() =
        runBlocking {
            val requests = mutableListOf<String>()
            val client =
                HttpClient(
                    MockEngine { request ->
                        val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                        val cursor = body["browseId"]?.jsonPrimitive?.content ?: body["continuation"]!!.jsonPrimitive.content
                        requests += cursor
                        val response =
                            when (cursor) {
                                "VLRDTMAK_test" -> """{
                                  "header":{"musicResponsiveHeaderRenderer":{
                                    "title":{"runs":[{"text":"Archive mix"}]},
                                    "straplineTextOne":{"runs":[{"text":"Artist","navigationEndpoint":{"browseEndpoint":{"browseId":"UCtest"}}}]}
                                  }},
                                  "contents":{"sectionListRenderer":{"contents":[
                                    {"musicResponsiveHeaderRenderer":{
                                      "title":{"runs":[{"text":"Archive mix"}]},
                                      "subtitle":{"runs":[{"text":"Artist","navigationEndpoint":{"browseEndpoint":{"browseId":"UCtest"}}}]},
                                      "menu":{"continuationCommand":{"token":"header-action"}}
                                    }},
                                    {"musicPlaylistShelfRenderer":{
                                      "contents":[${row("one", "first")},${row("one", "repeated")}],
                                      "continuations":[{"nextContinuationData":{"continuation":"tracks-2"}}]
                                    }}
                                  ]}}
                                }"""

                                "tracks-2" -> """{
                                  "onResponseReceivedActions":[{"appendContinuationItemsAction":{"continuationItems":[
                                    ${row("one", "third")},${row("two", "fourth")},
                                    {"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"tracks-3"}}}}
                                  ]}}]
                                }"""

                                "tracks-3" -> """{"continuationContents":{"musicPlaylistShelfContinuation":{"contents":[${row(
                                    "two",
                                    "fifth",
                                )}]}}}"""

                                else -> error("Unexpected continuation $cursor")
                            }
                        respond(response, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                    },
                ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
            client.use {
                val repository = YouTubeRepository(InnerTube(it))
                val page = repository.completePlaylistPage(repository.getPlaylistPageData("RDTMAK_test"))
                assertEquals(listOf("VLRDTMAK_test", "tracks-2", "tracks-3"), requests)
                assertEquals(listOf("one", "two"), page.songs.map(SongItem::id))
                assertFalse(page.hasMore)
                assertNull((page.sections["header"]!!.single() as PlaylistItem).author)
            }
        }

    @Test
    fun userPlaylistsPreserveDistinctOccurrencesAndRowsWithoutOccurrenceIds() {
        val songs = listOf(song("first"), song("second"), song(null), song(null))
        for (id in listOf("PLuser", "VLRDCLcurated")) {
            val sections = mapOf("header" to listOf(PlaylistItem(id, "Playlist", null)), "Tracks" to songs)
            assertEquals(songs, sections.playlistSongs())
        }
        for (id in listOf("RDTMAK_mix", "VLRDTMAK_mix")) {
            val sections = mapOf("header" to listOf(PlaylistItem(id, "Mix", null)), "Tracks" to songs)
            assertEquals(listOf(songs.first()), sections.playlistSongs())
        }
    }

    private fun song(occurrence: String?) = SongItem("video", "Song", null, emptyList(), null, playlistSetVideoId = occurrence)

    private fun row(
        id: String,
        occurrence: String,
    ) = """{"musicResponsiveListItemRenderer":{
      "flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Song"}]}}}],
      "playlistItemData":{"videoId":"$id","playlistSetVideoId":"$occurrence"},
      "menu":{"continuationCommand":{"token":"song-action"}}
    }}"""
}
