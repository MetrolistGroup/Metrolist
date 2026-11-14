package com.metrolist.shared.api

import com.metrolist.innertubex.InnerTube
import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.model.ArtistItem
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
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ArtistPageYearTest {
    @BeforeTest
    fun setUp() {
        Settings.replaceSharedForTest(Settings(MapSettings()))
    }

    @AfterTest
    fun tearDown() {
        Settings.replaceSharedForTest(null)
    }

    @Test
    fun artistAlbumsIncludeReleaseYear() =
        runBlocking {
            val client =
                HttpClient(
                    MockEngine {
                        respond(
                            content = ARTIST_PAGE_RESPONSE,
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
                ) {
                    install(ContentNegotiation) {
                        json(Json { ignoreUnknownKeys = true })
                    }
                }

            val result = YouTubeRepository(InnerTube(client)).getArtist("UCtest")

            val artistHeader = result.sections["header"]?.firstOrNull() as? ArtistItem
            assertEquals("Daft Punk", artistHeader?.title)

            val albums = result.sections["Albums"].orEmpty()
            assertEquals(2, albums.size, "expected two album items in the Albums section")

            val album = albums[0]
            assertIs<AlbumItem>(album)
            assertEquals("Random Access Memories", album.title)
            assertEquals("2013", album.year)
            assertEquals("UCtest", album.artists.single().id)
            assertEquals("Daft Punk", album.artists.single().name)
            assertEquals(artistHeader?.thumbnail, album.artists.single().thumbnail)

            val single = albums[1]
            assertIs<AlbumItem>(single)
            assertEquals("GLBTM (Studio Outtakes)", single.title)
            assertEquals("2023", single.year)
            assertEquals("Daft Punk", single.artists.single().name)
            assertEquals(listOf(single), result.sections["latest_release"])
            assertEquals("latest_release", result.sections.keys.first())

            client.close()
        }

    @Test
    fun artistAlbumsWithoutYearStayNull() =
        runBlocking {
            val client =
                HttpClient(
                    MockEngine {
                        respond(
                            content = ARTIST_PAGE_NO_YEAR_RESPONSE,
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
                ) {
                    install(ContentNegotiation) {
                        json(Json { ignoreUnknownKeys = true })
                    }
                }

            val result = YouTubeRepository(InnerTube(client)).getArtist("UCtest")

            val albums = result.sections["Albums"].orEmpty()
            assertEquals(1, albums.size)
            val album = albums[0]
            assertIs<AlbumItem>(album)
            assertEquals(null, album.year)

            client.close()
        }

    @Test
    fun latestReleaseCardKeepsItsAlbumAndExplicitBadge() =
        runBlocking {
            val client =
                HttpClient(
                    MockEngine {
                        respond(
                            """{
                  "header":{"musicImmersiveHeaderRenderer":{
                    "title":{"runs":[{"text":"Artist"}]},
                    "thumbnail":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"https://example.com/artist.jpg"}]}}}
                  }},
                  "contents":{"singleColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"sectionListRenderer":{"contents":[{
                    "musicCardShelfRenderer":{
                      "title":{"runs":[{"text":"Newest album"}]},
                      "subtitle":{"runs":[{"text":"Album"},{"text":" • "},{"text":"2026"}]},
                      "onTap":{"browseEndpoint":{"browseId":"MPREb_latest"}},
                      "badges":[{"musicInlineBadgeRenderer":{"icon":{"iconType":"MUSIC_EXPLICIT_BADGE"}}}]
                    }
                  }]}}}}]}}
                }""",
                            HttpStatusCode.OK,
                            headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
                ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
            try {
                val result = YouTubeRepository(InnerTube(client)).getArtist("UCtest")
                val release = assertIs<AlbumItem>(result.sections["latest_release"]?.single())
                assertEquals("MPREb_latest", release.id)
                assertEquals("Newest album", release.title)
                assertEquals("2026", release.year)
                assertEquals(true, release.isExplicit)
                assertEquals("Artist", release.artists.single().name)
            } finally {
                client.close()
            }
        }

    private companion object {
        const val ARTIST_PAGE_RESPONSE = """
        {
          "header": {
            "musicImmersiveHeaderRenderer": {
              "title": {"runs": [{"text": "Daft Punk"}]},
              "thumbnail": {"musicThumbnailRenderer": {"thumbnail": {"thumbnails": [{"url": "https://example.com/a.jpg"}]}}}
            }
          },
          "contents": {
            "twoColumnBrowseResultsRenderer": {
              "tabs": [
                {
                  "tabRenderer": {
                    "content": {
                      "sectionListRenderer": {
                        "contents": [
                          {
                            "musicCarouselShelfRenderer": {
                              "header": {"musicCarouselShelfBasicHeaderRenderer": {"title": {"runs": [{"text": "Albums"}]}}},
                              "contents": [
                                {
                                  "musicTwoRowItemRenderer": {
                                    "title": {"runs": [{"text": "Random Access Memories"}]},
                                    "subtitle": {"runs": [{"text": "2013"}]},
                                    "navigationEndpoint": {"browseEndpoint": {"browseId": "MPREb_K8qWMWVqXGi"}}
                                  }
                                },
                                {
                                  "musicTwoRowItemRenderer": {
                                    "title": {"runs": [{"text": "GLBTM (Studio Outtakes)"}]},
                                    "subtitle": {"runs": [{"text": "Single"}, {"text": " • "}, {"text": "2023"}]},
                                    "navigationEndpoint": {"browseEndpoint": {"browseId": "MPREb_X1DQ1j0PPrX"}}
                                  }
                                }
                              ]
                            }
                          }
                        ]
                      }
                    }
                  }
                }
              ]
            }
          }
        }
        """

        const val ARTIST_PAGE_NO_YEAR_RESPONSE = """
        {
          "header": {
            "musicImmersiveHeaderRenderer": {
              "title": {"runs": [{"text": "Daft Punk"}]},
              "thumbnail": {"musicThumbnailRenderer": {"thumbnail": {"thumbnails": [{"url": "https://example.com/a.jpg"}]}}}
            }
          },
          "contents": {
            "twoColumnBrowseResultsRenderer": {
              "tabs": [
                {
                  "tabRenderer": {
                    "content": {
                      "sectionListRenderer": {
                        "contents": [
                          {
                            "musicCarouselShelfRenderer": {
                              "header": {"musicCarouselShelfBasicHeaderRenderer": {"title": {"runs": [{"text": "Albums"}]}}},
                              "contents": [
                                {
                                  "musicTwoRowItemRenderer": {
                                    "title": {"runs": [{"text": "Random Access Memories"}]},
                                    "subtitle": {"runs": [{"text": "Album"}]},
                                    "navigationEndpoint": {"browseEndpoint": {"browseId": "MPREb_K8qWMWVqXGi"}}
                                  }
                                }
                              ]
                            }
                          }
                        ]
                      }
                    }
                  }
                }
              ]
            }
          }
        }
        """
    }
}
