package com.metrolist.shared.api

import com.metrolist.shared.model.EpisodeItem
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.YTItem
import com.metrolist.shared.model.toSongItem
import com.metrolist.shared.playback.MusicQueue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class EpisodeAvailabilityTest {
    @Test
    fun greyedEpisodeAvailabilityUsesPlayableMetadataAcrossRenderersAndConversions() {
        val watch =
            """{"watchEndpoint":{"videoId":"episode",
              "watchEndpointMusicSupportedConfigs":{"watchEndpointMusicConfig":{
                "musicVideoType":"MUSIC_VIDEO_TYPE_PODCAST_EPISODE"
              }}}}"""
        val browse = """{"browseEndpoint":{"browseId":"MPEDepisode"}}"""
        val title = """"title":{"runs":[{"text":"Episode"}]}"""
        val flex = """"flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Episode"}]}}}]"""
        val duration = """"subtitle":{"runs":[{"text":"3:00"}]}"""
        val renderers =
            listOf(
                Triple("musicMultiRowListItemRenderer", """$title,"onTap":$watch""", false),
                Triple("musicTwoRowItemRenderer", """$title,"navigationEndpoint":$watch""", false),
                Triple("musicResponsiveListItemRenderer", """$flex,"navigationEndpoint":$watch""", false),
                Triple("musicTwoRowItemRenderer", """$title,"navigationEndpoint":$browse""", true),
                Triple("musicResponsiveListItemRenderer", """$flex,"navigationEndpoint":$browse""", true),
                Triple("musicTwoRowItemRenderer", """$title,$duration,"navigationEndpoint":$browse""", false),
                Triple("musicResponsiveListItemRenderer", """$flex,$duration,"navigationEndpoint":$browse""", false),
            )
        for ((renderer, fields, unavailable) in renderers) {
            val response =
                Json
                    .parseToJsonElement(
                        """{"$renderer":{$fields,"musicItemRendererDisplayPolicy":"MUSIC_ITEM_RENDERER_DISPLAY_POLICY_GREY_OUT"}}""",
                    ).jsonObject
            val episode = assertIs<EpisodeItem>(YouTubeItemParser.parseItem(response), renderer)
            assertEquals(unavailable, episode.isUnavailable, renderer)
            assertEquals(unavailable, (episode as YTItem).isUnavailable, renderer)
            val song = episode.toSongItem()
            assertEquals(unavailable, song.isUnavailable, renderer)
            assertEquals(unavailable, song.toEpisodeItem().isUnavailable, renderer)
            val playable = song.copy(id = "playable", isUnavailable = false)
            val queue = MusicQueue().apply { setQueue(listOf(song, playable)) }
            assertEquals(if (unavailable) listOf(playable) else listOf(song, playable), queue.items.value, renderer)
            assertEquals("episode", episode.id)
        }
    }

    @Test
    fun fallbackPodcastRowsUseTheSameMetadataAvailability() {
        for (duration in listOf("3:00", "", "0:00")) {
            val response =
                Json
                    .parseToJsonElement(
                        """{"musicMultiRowListItemRenderer":{
                          "videoId":"episode","title":{"runs":[{"text":"Episode"}]},
                          "musicItemRendererDisplayPolicy":"MUSIC_ITEM_RENDERER_DISPLAY_POLICY_GREY_OUT",
                          "fixedColumns":[{"musicResponsiveListItemFixedColumnRenderer":{"text":{"simpleText":"$duration"}}}]
                        }}""",
                    ).jsonObject
            val song = YouTubePodcastEpisodeParser.parseNewPodcastEpisodes(response).single()
            assertEquals(duration != "3:00", song.isUnavailable)
            assertEquals("episode", song.id)
        }
    }

    @Test
    fun serializedEpisodesDefaultToAvailableAndRetainExplicitAvailability() {
        val old = """{"id":"episode","title":"Episode","thumbnail":null}"""
        assertFalse(Json.decodeFromString<EpisodeItem>(old).isUnavailable)
        val episode = EpisodeItem("episode", "Episode", null, saved = true, playlistSetVideoId = "entry", isUnavailable = true)
        assertEquals(episode, Json.decodeFromString<EpisodeItem>(Json.encodeToString(episode)))
        val song = SongItem("episode", "Episode", null, emptyList(), null, isUnavailable = true)
        assertTrue(song.toEpisodeItem().toSongItem().isUnavailable)
        assertEquals("entry", episode.toSongItem().playlistSetVideoId)
    }
}
