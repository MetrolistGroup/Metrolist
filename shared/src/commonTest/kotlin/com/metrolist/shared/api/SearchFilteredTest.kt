package com.metrolist.shared.api

import com.metrolist.innertubex.InnerTube
import com.metrolist.shared.model.EpisodeItem
import com.metrolist.shared.model.SearchFilter
import com.metrolist.shared.settings.Settings
import com.russhwolf.settings.MapSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SearchFilteredTest {
    @Test
    fun episodeCategoriesMergeWithPluralShelves() =
        runBlocking {
            fun episodeRow(id: String) =
                """
                {"musicResponsiveListItemRenderer": {
                  "flexColumns": [
                    {"musicResponsiveListItemFlexColumnRenderer": {"text": {"runs": [{"text": "Episode title"}]}}},
                    {"musicResponsiveListItemFlexColumnRenderer": {"text": {"runs": [{"text": "Episode"}]}}}
                  ],
                  "navigationEndpoint": {"watchEndpoint": {
                    "videoId": "$id",
                    "watchEndpointMusicSupportedConfigs": {"watchEndpointMusicConfig": {
                      "musicVideoType": "MUSIC_VIDEO_TYPE_PODCAST_EPISODE"
                    }}
                  }}
                }}
                """.trimIndent()
            val response =
                """
                {"contents":{"sectionListRenderer":{"contents":[
                  ${episodeRow("first")},
                  ${episodeRow("second")},
                  {"musicShelfRenderer":{
                    "title":{"runs":[{"text":"Episodes"}]},
                    "contents":[${episodeRow("first")}]
                  }}
                ]}}}
                """.trimIndent()
            val client =
                HttpClient(
                    MockEngine {
                        respond(response, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                    },
                ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
            Settings.replaceSharedForTest(Settings(MapSettings()))
            try {
                val section = YouTubeSearchOperations(InnerTube(client)).getSearchSummary("sample", false)!!.summaries.single()
                assertEquals("Episodes", section.title)
                assertEquals(listOf("first", "second"), section.items.map { assertIs<EpisodeItem>(it).id })
            } finally {
                Settings.replaceSharedForTest(null)
                client.close()
            }
        }

    @Test
    fun episodeFilterSendsEpisodeParamsAndReturnsEpisodeRenderer() =
        runBlocking {
            var requestedParams: String? = null
            val client =
                HttpClient(
                    MockEngine { request ->
                        requestedParams =
                            Json
                                .parseToJsonElement((request.body as TextContent).text)
                                .jsonObject["params"]
                                ?.jsonPrimitive
                                ?.content
                        respond(
                            content = EPISODE_SEARCH_RESPONSE,
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
                ) {
                    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                }

            try {
                Settings.replaceSharedForTest(Settings(MapSettings()))
                val result = YouTubeRepository(InnerTube(client)).searchFiltered("sample", SearchFilter.EPISODE)
                val episode = assertIs<EpisodeItem>(result.items.single())

                assertEquals("EgWKAQJIAWoKEAkQChAFEAMQBA%3D%3D", requestedParams)
                assertEquals("episode-video", episode.id)
                assertEquals("Episode title", episode.title)
                assertEquals("Podcast title", episode.author?.name)
            } finally {
                Settings.replaceSharedForTest(null)
                client.close()
            }
        }

    private companion object {
        val EPISODE_SEARCH_RESPONSE =
            """
            {
              "contents": {
                "tabbedSearchResultsRenderer": {
                  "tabs": [{
                    "tabRenderer": {
                      "content": {
                        "sectionListRenderer": {
                          "contents": [{
                            "musicShelfRenderer": {
                              "contents": [{
                                "musicResponsiveListItemRenderer": {
                                  "thumbnail": {
                                    "musicThumbnailRenderer": {
                                      "thumbnail": {
                                        "thumbnails": [{
                                          "url": "https://example.com/episode=w120-h120-l90-rj",
                                          "width": 120,
                                          "height": 120
                                        }]
                                      }
                                    }
                                  },
                                  "flexColumns": [
                                    {
                                      "musicResponsiveListItemFlexColumnRenderer": {
                                        "text": {"runs": [{"text": "Episode title"}]}
                                      }
                                    },
                                    {
                                      "musicResponsiveListItemFlexColumnRenderer": {
                                        "text": {"runs": [
                                          {"text": "Podcast title", "navigationEndpoint": {"browseEndpoint": {"browseId": "MPSP-podcast"}}},
                                          {"text": " • "},
                                          {"text": "Jan 2, 2026"}
                                        ]}
                                      }
                                    }
                                  ],
                                  "overlay": {
                                    "musicItemThumbnailOverlayRenderer": {
                                      "content": {
                                        "musicPlayButtonRenderer": {
                                          "playNavigationEndpoint": {
                                            "watchEndpoint": {
                                              "videoId": "episode-video",
                                              "watchEndpointMusicSupportedConfigs": {
                                                "watchEndpointMusicConfig": {
                                                  "musicVideoType": "MUSIC_VIDEO_TYPE_PODCAST_EPISODE"
                                                }
                                              }
                                            }
                                          }
                                        }
                                      }
                                    }
                                  }
                                }
                              }]
                            }
                          }]
                        }
                      }
                    }
                  }]
                }
              }
            }
            """.trimIndent()
    }
}
