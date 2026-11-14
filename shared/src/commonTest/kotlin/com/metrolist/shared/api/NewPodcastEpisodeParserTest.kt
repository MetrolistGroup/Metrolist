package com.metrolist.shared.api

import com.metrolist.shared.model.EpisodeItem
import com.metrolist.shared.model.toSongItem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NewPodcastEpisodeParserTest {
    @Test
    fun parsesSecondaryShelfMultiRowEpisodesWithoutARepositoryOrSession() {
        val response =
            Json
                .parseToJsonElement(
                    """
                    {
                      "contents": {
                        "twoColumnBrowseResultsRenderer": {
                          "secondaryContents": {
                            "sectionListRenderer": {
                              "contents": [{
                                "musicShelfRenderer": {
                                  "contents": [{
                                    "musicMultiRowListItemRenderer": {
                                      "onTap": {"watchEndpoint": {"videoId": "episode-one"}},
                                      "title": {"runs": [{"text": "Episode one"}]},
                                      "secondSubtitle": {"runs": [{"text": "Podcast creator", "navigationEndpoint": {"browseEndpoint": {"browseId": "UCcreator"}}}]},
                                      "fixedColumns": [{"musicResponsiveListItemFixedColumnRenderer": {"text": {"runs": [{"text": "1:02"}]}}}]
                                    }
                                  }]
                                }
                              }]
                            }
                          }
                        }
                      }
                    }
                    """.trimIndent(),
                ).jsonObject

        val episode = YouTubePodcastEpisodeParser.parseNewPodcastEpisodes(response).single()
        assertEquals("episode-one", episode.id)
        assertEquals("Episode one", episode.title)
        assertEquals("Podcast creator", episode.artists.single().name)
        assertEquals(62L, episode.duration)
        assertEquals("MUSIC_VIDEO_TYPE_PODCAST_EPISODE", episode.musicVideoType)
    }

    @Test
    fun parsesMultiRowClockDurationWithOrWithoutAProgressSeparator() {
        for (durationText in listOf(
            """{"runs": [{"text": "1:02:03"}]}""",
            """{"runs": [{"text": " • "}, {"text": "1:02:03"}]}""",
            """{"runs": [{"text": " · "}, {"text": "1:02:03"}]}""",
            """{"simpleText": "1:02:03"}""",
            """{"simpleText": " • 1:02:03"}""",
            """{"simpleText": " · 1:02:03"}""",
        )) {
            val item =
                Json
                    .parseToJsonElement(
                        """
                        {"musicMultiRowListItemRenderer": {
                          "onTap": {"watchEndpoint": {"videoId": "ep", "watchEndpointMusicSupportedConfigs": {"watchEndpointMusicConfig": {"musicVideoType": "MUSIC_VIDEO_TYPE_PODCAST_EPISODE"}}}},
                          "title": {"runs": [{"text": "Episode"}]},
                          "playbackProgress": {"musicPlaybackProgressRenderer": {"durationText": $durationText}}
                        }}
                        """.trimIndent(),
                    ).jsonObject

            val episode = YouTubeResponseParser.parseItem(item) as EpisodeItem
            assertEquals(3723L, episode.duration)
            assertEquals("1:02:03", episode.durationText)
        }
    }

    @Test
    fun preservesLocalizedEpisodeMetadataFromRunsAndSimpleText() {
        for ((subtitle, duration) in listOf(
            "4,9 k vues • il y a 4 jours" to "1 heure 18 minutes",
            "4.900 Aufrufe • vor 4 Tagen" to "1 Std. 18 Min.",
            "4日前" to "1時間18分",
            "قبل ٤ أيام" to "ساعة و١٨ دقيقة",
        )) {
            for (simpleText in listOf(false, true)) {
                val episode = parseEpisodeMetadata(subtitle, " • $duration", simpleText)

                assertEquals(subtitle, episode.subtitle)
                assertEquals(duration, episode.durationText)
                assertNull(episode.duration)
            }
        }
    }

    @Test
    fun parsesNumericDurationWithoutReplacingSuppliedDisplayText() {
        for (simpleText in listOf(false, true)) {
            val episode = parseEpisodeMetadata("4.9K views • 4d ago", " · 1 hr 18 min", simpleText)

            assertEquals("4.9K views • 4d ago", episode.subtitle)
            assertEquals("1 hr 18 min", episode.durationText)
            assertEquals(4680L, episode.duration)
        }
    }

    @Test
    fun ignoresEmptyMetadataAndKeepsFixedColumnDuration() {
        for (simpleText in listOf(false, true)) {
            val episode = parseEpisodeMetadata("  ", " • ", simpleText, fixedDuration = "1:02")

            assertNull(episode.subtitle)
            assertNull(episode.durationText)
            assertEquals(62L, episode.duration)
        }
    }

    @Test
    fun retainsLocalizedDurationAlongsideNumericFallback() {
        val episode = parseEpisodeMetadata("4日前", "1時間18分", simpleText = true, fixedDuration = "1:18:00")

        assertEquals("1時間18分", episode.durationText)
        assertEquals(4680L, episode.duration)
    }

    private fun parseEpisodeMetadata(
        subtitle: String,
        duration: String,
        simpleText: Boolean,
        fixedDuration: String? = null,
    ): EpisodeItem {
        fun text(value: String): String =
            if (simpleText) {
                """{"simpleText": ${JsonPrimitive(value)}}"""
            } else {
                """{"runs": [{"text": ${JsonPrimitive(value)}}]}"""
            }

        val item =
            Json
                .parseToJsonElement(
                    """
                    {"musicMultiRowListItemRenderer": {
                      "onTap": {"watchEndpoint": {"videoId": "ep", "watchEndpointMusicSupportedConfigs": {"watchEndpointMusicConfig": {"musicVideoType": "MUSIC_VIDEO_TYPE_PODCAST_EPISODE"}}}},
                      "title": {"runs": [{"text": "Episode"}]},
                      "subtitle": ${text(subtitle)},
                      "playbackProgress": {"musicPlaybackProgressRenderer": {"durationText": ${text(duration)}}},
                      "fixedColumns": [{"musicResponsiveListItemFixedColumnRenderer": {"text": ${text(fixedDuration.orEmpty())}}}]
                    }}
                    """.trimIndent(),
                ).jsonObject

        return YouTubeResponseParser.parseItem(item) as EpisodeItem
    }

    @Test
    fun parsesEpisodeDescriptionFromMultiRow() {
        val item =
            Json
                .parseToJsonElement(
                    """
                    {"musicMultiRowListItemRenderer": {
                      "onTap": {"watchEndpoint": {"videoId": "ep", "watchEndpointMusicSupportedConfigs": {"watchEndpointMusicConfig": {"musicVideoType": "MUSIC_VIDEO_TYPE_PODCAST_EPISODE"}}}},
                      "title": {"runs": [{"text": "Episode"}]},
                      "subtitle": {"runs": [{"text": "4.9K views"}, {"text": " • "}, {"text": "4d ago"}]},
                      "playbackProgress": {"musicPlaybackProgressRenderer": {"durationText": {"runs": [{"text": " • "}, {"text": "1 hr 18 min"}]}}},
                      "description": {"runs": [{"text": "Hello "}, {"text": "world"}]}
                    }}
                    """.trimIndent(),
                ).jsonObject
        val episode = YouTubeResponseParser.parseItem(item) as EpisodeItem
        assertEquals("Hello world", episode.description)
        assertEquals("4.9K views", episode.viewCountText)
        assertEquals("4d ago", episode.publishDateText)
        assertEquals(4680L, episode.duration)
    }

    @Test
    fun parsesAccountPlaybackProgress() {
        for ((progress, expected) in listOf(
            """{"playbackProgressPercentage": 100, "durationText": {"runs": [{"text": "16 min"}]}}""" to 100,
            """{"playbackProgressPercentage": 59, "durationText": {"runs": [{"text": "16 min"}]}}""" to 59,
            """{"durationText": {"runs": [{"text": "16 min"}]}}""" to null,
        )) {
            val item =
                Json
                    .parseToJsonElement(
                        """
                        {"musicMultiRowListItemRenderer": {
                          "onTap": {"watchEndpoint": {"videoId": "ep", "watchEndpointMusicSupportedConfigs": {"watchEndpointMusicConfig": {"musicVideoType": "MUSIC_VIDEO_TYPE_PODCAST_EPISODE"}}}},
                          "title": {"runs": [{"text": "Episode"}]},
                          "playbackProgress": {"musicPlaybackProgressRenderer": $progress}
                        }}
                        """.trimIndent(),
                    ).jsonObject
            val episode = YouTubeResponseParser.parseItem(item) as EpisodeItem
            assertEquals(expected, episode.playbackProgressPercent)
            assertEquals(expected, episode.toSongItem().playbackProgressPercent)
            assertEquals(960L, episode.duration)
        }
    }

    @Test
    fun parsesWatchResumePointWithoutChangingEpisodeId() {
        val renderer = """{
            "onTap": {"watchEndpoint": {"videoId": "episode-id", "startTimeSeconds": 2813,
                "watchEndpointMusicSupportedConfigs": {"watchEndpointMusicConfig": {"musicVideoType": "MUSIC_VIDEO_TYPE_PODCAST_EPISODE"}}}},
            "title": {"runs": [{"text": "Episode"}]},
            "playbackProgress": {"musicPlaybackProgressRenderer": {"playbackProgressPercentage": 39,
                "durationText": {"runs": [{"text": "2 hr"}]}}}
        }"""
        for (wrapper in listOf("musicMultiRowListItemRenderer", "musicTwoRowItemRenderer")) {
            val item = Json.parseToJsonElement("{\"$wrapper\":$renderer}").jsonObject
            val episode = YouTubeResponseParser.parseItem(item) as EpisodeItem
            val song = episode.toSongItem()
            assertEquals("episode-id", song.id)
            assertEquals(2_813_000L, song.playbackPositionMs)
            assertEquals(39, song.playbackProgressPercent)
        }
    }
}
