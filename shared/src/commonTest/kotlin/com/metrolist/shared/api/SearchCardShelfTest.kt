package com.metrolist.shared.api

import com.metrolist.innertubex.InnerTube
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.settings.Settings
import com.russhwolf.settings.MapSettings
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
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SearchCardShelfTest {
    @Test
    fun cardWithOnlyATypeLabelHasNoArtist() {
        val card =
            Json
                .parseToJsonElement(
                    """{
                  "title":{"runs":[{"text":"No Surprises"}]},
                  "onTap":{"watchEndpoint":{"videoId":"track"}},
                  "subtitle":{"runs":[{"text":"Titel"}]}
                }""",
                ).jsonObject

        assertTrue(assertIs<SongItem>(YouTubeSearchParser.parseCardShelfItem(card)).artists.isEmpty())
    }

    @Test
    fun cardTypeLabelsDoNotBecomeUnlinkedArtists() {
        for (subtitle in listOf(
            """[{"text":"Titel"},{"text":" • "},{"text":"Radiohead"},{"text":" • "},{"text":"OK Computer"}]""",
            """[{"text":"Titre • Radiohead • OK Computer"}]""",
            """[{"text":"row type"},{"text":" • "},{"text":"Radiohead"},{"text":" • "},{"text":"OK Computer","navigationEndpoint":{"browseEndpoint":{"browseId":"MPREbalbum"}}}]""",
        )) {
            val card =
                Json
                    .parseToJsonElement(
                        """{
                      "title":{"runs":[{"text":"No Surprises"}]},
                      "onTap":{"watchEndpoint":{"videoId":"track"}},
                      "subtitle":{"runs":$subtitle}
                    }""",
                    ).jsonObject
            val song = assertIs<SongItem>(YouTubeSearchParser.parseCardShelfItem(card))

            assertEquals("Radiohead", song.artists.single().name)
            assertEquals("OK Computer", song.album?.name)
        }
    }

    @Test
    fun artistAndAlbumLinksSurviveRemovingTheCardType() {
        val card =
            Json
                .parseToJsonElement(
                    """{
                  "title":{"runs":[{"text":"No Surprises"}]},
                  "onTap":{"watchEndpoint":{"videoId":"track"}},
                  "subtitle":{"runs":[
                    {"text":"Titel"},{"text":" • "},
                    {"text":"Radiohead","navigationEndpoint":{"browseEndpoint":{"browseId":"UCband"}}},
                    {"text":" • "},
                    {"text":"OK Computer","navigationEndpoint":{"browseEndpoint":{"browseId":"MPREbalbum"}}}
                  ]}
                }""",
                ).jsonObject
        val song = assertIs<SongItem>(YouTubeSearchParser.parseCardShelfItem(card))

        assertEquals("UCband", song.artists.single().id)
        assertEquals("MPREbalbum", song.album?.id)
    }

    @Test
    fun keepsUnlabeledBrowseResults() =
        runBlocking {
            val client =
                HttpClient(
                    MockEngine {
                        respond(
                            """{"contents":{"sectionListRenderer":{"contents":[{"musicTwoRowItemRenderer":{"title":{"runs":[{"text":"Browse page"}]},"navigationEndpoint":{"browseEndpoint":{"browseId":"FEmusic_test"}}}}]}}}""",
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
                ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
            Settings.replaceSharedForTest(Settings(MapSettings()))
            try {
                val result = YouTubeSearchOperations(InnerTube(client)).getSearchSummary("query", false)!!
                assertEquals(listOf("FEmusic_test"), result.summaries.flatMap { it.items }.map { it.id })
            } finally {
                Settings.replaceSharedForTest(null)
                client.close()
            }
        }

    @Test
    fun preservesTopResultBadgesBeforeFilteringBothSearchApis() =
        runBlocking {
            val client =
                HttpClient(
                    MockEngine {
                        respond(
                            content = SEARCH_RESPONSE,
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
                ) {
                    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                }
            val settings = Settings(MapSettings())
            Settings.replaceSharedForTest(settings)
            try {
                val operations = YouTubeSearchOperations(InnerTube(client))
                assertTrue(
                    operations
                        .getSearchSummary("sample", false)!!
                        .summaries
                        .single()
                        .isTopResult,
                )
                val songs =
                    listOf(
                        operations.search("sample", false)!!.single(),
                        operations
                            .getSearchSummary("sample", false)!!
                            .summaries
                            .flatMap { it.items }
                            .single(),
                    )
                for (item in songs) {
                    val song = assertIs<SongItem>(item)
                    assertEquals("video", song.id)
                    assertEquals("Artist", song.artists.single().name)
                    assertTrue(song.isExplicit)
                    assertTrue(song.isNew)
                }

                settings.hideExplicit = true
                assertTrue(operations.search("sample", false)!!.isEmpty())
                assertTrue(
                    operations
                        .getSearchSummary("sample", false)!!
                        .summaries
                        .flatMap { it.items }
                        .isEmpty(),
                )
            } finally {
                Settings.replaceSharedForTest(null)
                client.close()
            }
        }

    private companion object {
        val SEARCH_RESPONSE =
            """
            {"contents": {"sectionListRenderer": {"contents": [{
              "musicCardShelfRenderer": {
                "title": {"runs": [{"text": "Top song", "navigationEndpoint": {"watchEndpoint": {"videoId": "video"}}}]},
                "subtitle": {"runs": [{"text": "Titel"}, {"text": " • "}, {"text": "Artist"}]},
                "subtitleBadges": [{"musicInlineBadgeRenderer": {"icon": {"iconType": "MUSIC_EXPLICIT_BADGE"}}}],
                "badges": [{"musicInlineBadgeRenderer": {"icon": {"iconType": "MUSIC_NEW_RELEASE_BADGE"}}}]
              }
            }]}}}
            """.trimIndent()
    }
}
