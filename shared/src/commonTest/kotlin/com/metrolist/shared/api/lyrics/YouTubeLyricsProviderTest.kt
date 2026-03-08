package com.metrolist.shared.api.lyrics

import com.metrolist.innertubex.models.YouTubeClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class YouTubeLyricsProviderTest {
    @Test
    fun returnsSyncedLyricsWhenTimedLyricsDataIsPresent() =
        runBlocking {
            val watchNextJson =
                """
                {
                  "contents": {
                    "singleColumnMusicWatchNextResultsRenderer": {
                      "tabbedRenderer": {
                        "watchNextTabbedResultsRenderer": {
                          "tabs": [
                            {
                              "tabRenderer": {
                                "title": "Songtext",
                                "endpoint": {
                                  "browseEndpoint": {
                                    "browseId": "MPLYt_test_123"
                                  }
                                }
                              }
                            }
                          ]
                        }
                      }
                    }
                  }
                }
                """.trimIndent()

            val androidTimedLyricsJson =
                """
                {
                  "contents": {
                    "elementRenderer": {
                      "newElement": {
                        "type": {
                          "componentType": {
                            "model": {
                              "timedLyricsModel": {
                                "lyricsData": {
                                  "timedLyricsData": [
                                    {
                                      "lyricLine": "♪",
                                      "cueRange": {
                                        "startTimeMilliseconds": "0",
                                        "endTimeMilliseconds": "13520"
                                      }
                                    },
                                    {
                                      "lyricLine": "Yeah",
                                      "cueRange": {
                                        "startTimeMilliseconds": "13520",
                                        "endTimeMilliseconds": "16240"
                                      }
                                    },
                                    {
                                      "lyricLine": "I said, \"Ooh, I'm blinded by the lights\"",
                                      "cueRange": {
                                        "startTimeMilliseconds": "60890",
                                        "endTimeMilliseconds": "66670"
                                      }
                                    }
                                  ]
                                }
                              }
                            }
                          }
                        }
                      }
                    }
                  }
                }
                """.trimIndent()

            val api =
                object : YouTubeLyricsApi {
                    override suspend fun watchNext(videoId: String): JsonObject = Json.parseToJsonElement(watchNextJson).jsonObject

                    override suspend fun browse(browseId: String): JsonObject = JsonObject(emptyMap())

                    override suspend fun browse(
                        client: YouTubeClient,
                        browseId: String,
                    ): JsonObject {
                        assertEquals(YouTubeLyricsProvider.ANDROID_MUSIC_LYRICS_CLIENT.clientName, client.clientName)
                        assertEquals(YouTubeLyricsProvider.ANDROID_MUSIC_LYRICS_CLIENT.clientVersion, client.clientVersion)
                        assertEquals("MPLYt_test_123", browseId)
                        return Json.parseToJsonElement(androidTimedLyricsJson).jsonObject
                    }

                    override suspend fun player(videoId: String): JsonObject = JsonObject(emptyMap())

                    override suspend fun transcript(videoId: String): List<YouTubeTranscriptCue> = emptyList()

                    override suspend fun getText(url: String): String = ""
                }

            val provider = YouTubeLyricsProvider(api)
            val mlx = provider.getLyrics("test_video_id")

            assertNotNull(mlx)
            assertTrue(mlx.contains("[00:00.000~00:13.520]♪"))
            assertTrue(mlx.contains("[00:13.520~00:16.240]Yeah"))
            assertTrue(mlx.contains("[01:00.890~01:06.670]I said, \"Ooh, I'm blinded by the lights\""))

            val doc = LyricsUtils.parseLyrics(mlx)
            assertEquals(3, doc.size)
            assertEquals(0L, doc[0].time)
            assertEquals(13520L, doc[0].endTime)
            assertEquals("♪", doc[0].text)

            assertEquals(13520L, doc[1].time)
            assertEquals(16240L, doc[1].endTime)
            assertEquals("Yeah", doc[1].text)

            assertEquals(60890L, doc[2].time)
            assertEquals(66670L, doc[2].endTime)
            assertEquals("I said, \"Ooh, I'm blinded by the lights\"", doc[2].text)
        }

    @Test
    fun fallsBackToPlainLyricsWhenTimedLyricsAreNotAvailable() =
        runBlocking {
            val watchNextJson =
                """
                {
                  "contents": {
                    "singleColumnMusicWatchNextResultsRenderer": {
                      "tabbedRenderer": {
                        "watchNextTabbedResultsRenderer": {
                          "tabs": [
                            {
                              "tabRenderer": {
                                "title": "Lyrics",
                                "endpoint": {
                                  "browseEndpoint": {
                                    "browseId": "MPLYt_fallback_456"
                                  }
                                }
                              }
                            }
                          ]
                        }
                      }
                    }
                  }
                }
                """.trimIndent()

            val webPlainJson =
                """
                {
                  "contents": {
                    "sectionListRenderer": {
                      "contents": [
                        {
                          "musicDescriptionShelfRenderer": {
                            "description": {
                              "runs": [
                                {
                                  "text": "First line of song\nSecond line of song"
                                }
                              ]
                            }
                          }
                        }
                      ]
                    }
                  }
                }
                """.trimIndent()

            val api =
                object : YouTubeLyricsApi {
                    override suspend fun watchNext(videoId: String): JsonObject = Json.parseToJsonElement(watchNextJson).jsonObject

                    override suspend fun browse(browseId: String): JsonObject = Json.parseToJsonElement(webPlainJson).jsonObject

                    override suspend fun browse(
                        client: YouTubeClient,
                        browseId: String,
                    ): JsonObject = JsonObject(emptyMap())

                    override suspend fun player(videoId: String): JsonObject = JsonObject(emptyMap())

                    override suspend fun transcript(videoId: String): List<YouTubeTranscriptCue> = emptyList()

                    override suspend fun getText(url: String): String = ""
                }

            val provider = YouTubeLyricsProvider(api)
            val mlx = provider.getLyrics("fallback_video_id")

            assertNotNull(mlx)
            val doc = LyricsUtils.parseLyrics(mlx)
            assertEquals(2, doc.size)
            assertEquals("First line of song", doc[0].text)
            assertEquals("Second line of song", doc[1].text)
        }
}
