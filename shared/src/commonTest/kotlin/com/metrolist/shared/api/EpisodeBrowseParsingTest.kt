package com.metrolist.shared.api

import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.EpisodeItem
import com.metrolist.shared.model.YTItem
import com.metrolist.shared.model.isPodcast
import com.metrolist.shared.model.toSongItem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class EpisodeBrowseParsingTest {
    @Test
    fun episodeDetailTilesProducePlayableEpisodesInsteadOfBrowsePages() {
        for (pageType in listOf("MUSIC_PAGE_TYPE_NON_MUSIC_AUDIO_TRACK_PAGE", "MUSIC_PAGE_TYPE_PODCAST_EPISODE_DETAIL_PAGE", "")) {
            for (responsive in listOf(false, true)) {
                val episode = assertIs<EpisodeItem>(parseEpisode(pageType, responsive))

                assertEquals("episode-one", episode.id)
                assertEquals("Episode one", episode.title)
                assertEquals(ArtistTiny("UCcreator", "Creator"), episode.author)
                assertEquals(3723L, episode.duration)
                val song = episode.toSongItem()
                assertEquals("episode-one", song.id)
                assertTrue(song.isPodcast)
            }
        }
    }

    @Test
    fun episodeDetailRowsWithWatchEndpointsKeepTheirEpisodeTypeAndOccurrence() {
        val episode =
            assertIs<EpisodeItem>(
                parseEpisode("MUSIC_PAGE_TYPE_NON_MUSIC_AUDIO_TRACK_PAGE", responsive = true, playable = true),
            )

        assertEquals("episode-one", episode.id)
        assertEquals("saved-entry", episode.playlistSetVideoId)
        assertTrue(episode.toSongItem().isPodcast)
    }

    @Test
    fun homeEpisodeTilesRetainTheirParentShowForLocalHiding() {
        for (responsive in listOf(false, true)) {
            for (playable in listOf(false, true)) {
                val episode = assertIs<EpisodeItem>(parseEpisode("MUSIC_PAGE_TYPE_PODCAST_EPISODE_DETAIL_PAGE", responsive, playable))
                assertEquals("Episode one", episode.title)
                assertEquals(ArtistTiny("UCcreator", "Creator"), episode.author)
                assertEquals("MPSPshow", episode.podcastId)
                assertEquals("MPSPshow", episode.toSongItem().podcastId)
            }
        }
        val multiRow =
            Json
                .parseToJsonElement(
                    """{"musicMultiRowListItemRenderer": {
                  "title": {"runs": [{"text": "Episode"}]},
                  "onTap": {"watchEndpoint": {"videoId": "episode", "watchEndpointMusicSupportedConfigs": {
                    "watchEndpointMusicConfig": {"musicVideoType": "MUSIC_VIDEO_TYPE_PODCAST_EPISODE"}
                  }}},
                  "secondSubtitle": {"runs": [{"text": "Show", "navigationEndpoint": {"browseEndpoint": {"browseId": "MPSPshow"}}}]}
                }}""",
                ).jsonObject
        assertEquals("MPSPshow", assertIs<EpisodeItem>(YouTubeItemParser.parseItem(multiRow)).podcastId)
    }

    private fun parseEpisode(
        pageType: String,
        responsive: Boolean,
        playable: Boolean = false,
    ): YTItem? {
        val title = Json.parseToJsonElement("""{"runs":[{"text":"Episode one"}]}""")
        val subtitle =
            Json.parseToJsonElement(
                """{"runs":[
                  {"text":"Creator","navigationEndpoint":{"browseEndpoint":{"browseId":"UCcreator"}}},
                  {"text":" • "},{"text":"1:02:03"},
                  {"text":" • "},{"text":"Show","navigationEndpoint":{"browseEndpoint":{"browseId":"MPSPshow"}}}
                ]}""",
            )
        val renderer =
            buildJsonObject {
                put(
                    "navigationEndpoint",
                    Json.parseToJsonElement(
                        """{"browseEndpoint":{
                          "browseId":"MPEDepisode-one",
                          "browseEndpointContextSupportedConfigs":{
                            "browseEndpointContextMusicConfig":{"pageType":"$pageType"}
                          }
                        }}""",
                    ),
                )
                if (playable) {
                    if (responsive) {
                        put(
                            "playlistItemData",
                            Json.parseToJsonElement("""{"videoId":"episode-one","playlistSetVideoId":"saved-entry"}"""),
                        )
                    } else {
                        put(
                            "navigationEndpoint",
                            Json.parseToJsonElement(
                                """{"watchEndpoint": {
                                  "videoId": "episode-one",
                                  "watchEndpointMusicSupportedConfigs": {
                                    "watchEndpointMusicConfig": {"musicVideoType": "MUSIC_VIDEO_TYPE_PODCAST_EPISODE"}
                                  }
                                }}""",
                            ),
                        )
                    }
                }
                if (responsive) {
                    put(
                        "flexColumns",
                        Json.parseToJsonElement(
                            """[
                              {"musicResponsiveListItemFlexColumnRenderer":{"text":$title}},
                              {"musicResponsiveListItemFlexColumnRenderer":{"text":$subtitle}}
                            ]""",
                        ),
                    )
                } else {
                    put("title", title)
                    put("subtitle", subtitle)
                }
            }
        return YouTubeItemParser.parseItem(
            buildJsonObject {
                put(if (responsive) "musicResponsiveListItemRenderer" else "musicTwoRowItemRenderer", renderer)
            },
        )
    }
}
