package com.metrolist.innertube.pages

import com.metrolist.innertube.models.MusicCarouselShelfRenderer
import com.metrolist.innertube.models.MusicResponsiveListItemRenderer
import com.metrolist.innertube.models.MusicTwoRowItemRenderer
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.WatchEndpoint.WatchEndpointMusicSupportedConfigs.WatchEndpointMusicConfig.Companion.MUSIC_VIDEO_TYPE_ATV
import com.metrolist.innertube.models.WatchEndpoint.WatchEndpointMusicSupportedConfigs.WatchEndpointMusicConfig.Companion.MUSIC_VIDEO_TYPE_OMV
import com.metrolist.innertube.models.WatchEndpoint.WatchEndpointMusicSupportedConfigs.WatchEndpointMusicConfig.Companion.MUSIC_VIDEO_TYPE_UGC
import com.metrolist.innertube.models.filterVideoSongs
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class VideoSongFilteringTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val types = listOf(null, MUSIC_VIDEO_TYPE_ATV, MUSIC_VIDEO_TYPE_OMV, MUSIC_VIDEO_TYPE_UGC)
    private val cover = """{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"cover","width":100,"height":100}]}}}"""

    @Test
    fun `home rows and tiles preserve video types so filtering keeps only audio and unknown songs`() {
        for (type in types) {
            val carousel = json.decodeFromString<MusicCarouselShelfRenderer>("""{
                "itemSize":"MUSIC_CAROUSEL_SHELF_ITEM_SIZE_LARGE",
                "header":{"musicCarouselShelfBasicHeaderRenderer":{"title":{"runs":[{"text":"Songs"}]}}},
                "contents":[
                    {"musicResponsiveListItemRenderer":${row(type)}},
                    {"musicTwoRowItemRenderer":${tile(type)}}
                ]
            }""")
            val section = HomePage.Section.fromMusicCarouselShelfRenderer(carousel)!!
            val page = HomePage(chips = null, sections = listOf(section))

            assertEquals(listOf(type, type), section.items.map { (it as SongItem).musicVideoType })
            assertEquals(if (isVideo(type)) 0 else 2, page.filterVideoSongs(true).sections.sumOf { it.items.size })
            assertEquals(if (isVideo(type)) 0 else 1, page.filterVideoSongs(true).sections.size)
            assertEquals(page, page.filterVideoSongs(false))
        }
    }

    @Test
    fun `library tiles preserve video types for filtering`() {
        for (type in types) {
            val renderer = json.decodeFromString<MusicTwoRowItemRenderer>(tile(type))
            val song = LibraryPage.fromMusicTwoRowItemRenderer(renderer) as SongItem

            assertEquals(type, song.musicVideoType)
            assertEquals(if (isVideo(type)) emptyList<SongItem>() else listOf(song), listOf(song).filterVideoSongs(true))
            assertEquals(listOf(song), listOf(song).filterVideoSongs(false))
        }
    }

    @Test
    fun `title watch endpoint provides video type when overlay and row endpoint are missing`() {
        for (type in types) {
            val renderer = json.decodeFromString<MusicResponsiveListItemRenderer>(row(type))
            val song = SearchPage.toYTItem(renderer) as SongItem

            assertEquals(type, renderer.musicVideoType)
            assertEquals(isVideo(type), song.isVideoSong)
            assertEquals(if (isVideo(type)) 0 else 1, listOf(song).filterVideoSongs(true).size)
        }
    }

    @Test
    fun `filtering a cached page keeps audio and pagination without discarding original videos`() {
        val video = SongItem(
            id = "video",
            title = "Video",
            artists = emptyList(),
            musicVideoType = MUSIC_VIDEO_TYPE_OMV,
            thumbnail = "cover",
        )
        val audio = video.copy(id = "audio", musicVideoType = MUSIC_VIDEO_TYPE_ATV)
        val videoSection = HomePage.Section("Videos", null, null, null, listOf(video))
        val audioSection = videoSection.copy(title = "Audio", items = listOf(audio))
        val cached = HomePage(null, listOf(videoSection, audioSection), continuation = "next")

        val hidden = cached.filterVideoSongs(true)

        assertEquals(listOf(audioSection), hidden.sections)
        assertEquals("next", hidden.continuation)
        assertEquals(listOf(videoSection, audioSection), cached.filterVideoSongs(false).sections)
    }

    private fun isVideo(type: String?) = type != null && type != MUSIC_VIDEO_TYPE_ATV

    private fun endpoint(type: String?): String {
        val config = type?.let {
            """, "watchEndpointMusicSupportedConfigs":{"watchEndpointMusicConfig":{"musicVideoType":"$it"}}"""
        }.orEmpty()
        return """{"watchEndpoint":{"videoId":"song"$config}}"""
    }

    private fun row(type: String?) = """{
        "flexColumns":[
            {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Song","navigationEndpoint":${endpoint(type)}}]}}},
            {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Artist"},{"text":" • "},{"text":"3:12"}]}}}
        ],
        "playlistItemData":{"videoId":"song"}, "thumbnail":$cover
    }"""

    private fun tile(type: String?) = """{
        "title":{"runs":[{"text":"Song"}]}, "subtitle":{"runs":[{"text":"Artist"}]},
        "navigationEndpoint":${endpoint(type)}, "thumbnailRenderer":$cover,
        "thumbnailOverlay":{"musicItemThumbnailOverlayRenderer":{"content":{"musicPlayButtonRenderer":{"playNavigationEndpoint":${endpoint(type)}}}}}
    }"""
}
