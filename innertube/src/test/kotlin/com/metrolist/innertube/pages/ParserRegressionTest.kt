package com.metrolist.innertube.pages

import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.Artist
import com.metrolist.innertube.models.EpisodeItem
import com.metrolist.innertube.models.MusicCardShelfRenderer
import com.metrolist.innertube.models.MusicCarouselShelfRenderer
import com.metrolist.innertube.models.MusicResponsiveListItemRenderer
import com.metrolist.innertube.models.MusicTwoRowItemRenderer
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.innertube.models.PodcastItem
import com.metrolist.innertube.models.SectionListRenderer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class ParserRegressionTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val cover = """{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"cover","width":100,"height":100}]}}}"""
    private val play = """{"watchPlaylistEndpoint":{"playlistId":"playlist"}}"""
    private val overlay = """{"musicItemThumbnailOverlayRenderer":{"content":{"musicPlayButtonRenderer":{"playNavigationEndpoint":$play}}}}"""
    private val buttons = """[
        {"buttonRenderer":{"text":{"runs":[]},"icon":{"iconType":"PLAY_ARROW"},"command":$play}},
        {"buttonRenderer":{"text":{"runs":[]},"icon":{"iconType":"MUSIC_SHUFFLE"},"command":$play}}
    ]"""
    private val menu = """{"menuRenderer":{"items":[
        {"menuNavigationItemRenderer":{"text":{"runs":[{"text":"Go to artist"}]},"icon":{"iconType":"ARTIST"},"navigationEndpoint":{"browseEndpoint":{"browseId":"UCartist"}}}},
        {"menuNavigationItemRenderer":{"text":{"runs":[]},"icon":{"iconType":"MUSIC_SHUFFLE"},"navigationEndpoint":$play}},
        {"menuNavigationItemRenderer":{"text":{"runs":[]},"icon":{"iconType":"MIX"},"navigationEndpoint":$play}}
    ]}}"""

    private fun endpoint(type: String) = """{"browseEndpoint":{"browseId":"VLitem","browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"MUSIC_PAGE_TYPE_$type"}}}}"""

    private fun tile(type: String, subtitle: String) = """{
        "title":{"runs":[{"text":"Title"}]}, "subtitle":{"runs":$subtitle},
        "navigationEndpoint":${endpoint(type)}, "thumbnailRenderer":$cover,
        "thumbnailOverlay":$overlay, "menu":$menu
    }"""

    @Test
    fun `related album retains menu-only artist id and other metadata`() {
        val renderer = json.decodeFromString<MusicTwoRowItemRenderer>(tile("ALBUM", """[{"text":"Album"},{"text":" • "},{"text":"2024"}]"""))
        val album = RelatedPage.fromMusicTwoRowItemRenderer(renderer) as AlbumItem
        assertEquals(listOf(Artist("", "UCartist")), album.artists)
        assertEquals(2024, album.year)
        assertEquals("cover", album.thumbnail)
        assertEquals("playlist", album.playlistId)
    }

    @Test
    fun `related playlist retains unlinked owner in last subtitle section`() {
        val renderer = json.decodeFromString<MusicTwoRowItemRenderer>(tile("PLAYLIST", """[{"text":"12 songs"},{"text":" • "},{"text":"Owner & Friends"}]"""))
        val playlist = RelatedPage.fromMusicTwoRowItemRenderer(renderer) as PlaylistItem
        assertEquals(Artist("Owner & Friends", null), playlist.author)
        assertEquals("12 songs", playlist.songCountText)
    }

    @Test
    fun `summary playlist retains unlinked owner`() {
        val item = SearchSummaryPage.fromMusicCardShelfRenderer(card("PLAYLIST")) as PlaylistItem
        assertEquals(Artist("Owner & Friends", null), item.author)
    }

    @Test
    fun `summary podcast retains unlinked host`() {
        val item = SearchSummaryPage.fromMusicCardShelfRenderer(card("PODCAST_SHOW_DETAIL_PAGE")) as PodcastItem
        assertEquals(Artist("Owner & Friends", null), item.author)
    }

    private fun card(type: String) = json.decodeFromString<MusicCardShelfRenderer>("""{
        "title":{"runs":[{"text":"Title"}]}, "subtitle":{"runs":[{"text":"Owner & Friends"}]},
        "header":{"musicCardShelfHeaderBasicRenderer":{"title":{"runs":[{"text":"Title"}]}}},
        "onTap":${endpoint(type)}, "thumbnail":$cover, "buttons":$buttons
    }""")

    @Test
    fun `search playlist retains unlinked owner with or without type label`() {
        for (typeLabel in listOf("""{"text":"Playlist"},{"text":" • "},""", "")) {
            val renderer = json.decodeFromString<MusicResponsiveListItemRenderer>("""{
            "flexColumns":[
                {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Title"}]}}},
                {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[$typeLabel{"text":"Owner & Friends"},{"text":" • "},{"text":"12 songs"}]}}}
            ], "navigationEndpoint":${endpoint("PLAYLIST")}, "thumbnail":$cover,
            "overlay":$overlay, "menu":$menu
        }""")
            assertEquals(Artist("Owner & Friends", null), (SearchPage.toYTItem(renderer) as PlaylistItem).author)
        }
    }

    @Test
    fun `podcast episode row retains unlinked channel byline`() {
        val renderer = json.decodeFromString<MusicResponsiveListItemRenderer>("""{
            "flexColumns":[
                {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Title"}]}}},
                {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Owner & Friends"},{"text":" • "},{"text":"Yesterday"},{"text":" • "},{"text":"3:12"}]}}}
            ], "playlistItemData":{"videoId":"episode"}, "thumbnail":$cover
        }""")
        val episode = PodcastPage.fromMusicResponsiveListItemRenderer(renderer)!!
        assertEquals(Artist("Owner & Friends", null), episode.author)
        assertEquals(192, episode.duration)
        assertEquals("Yesterday", episode.publishDateText)
    }

    @Test
    fun `home and artist episode tiles retain plain and podcast-linked bylines`() {
        for (byline in listOf(
            """{"text":"Owner & Friends"}""",
            """{"text":"Owner & Friends","navigationEndpoint":${endpoint("PODCAST_SHOW_DETAIL_PAGE")}}""",
        )) {
            val episodeTile = tile("NON_MUSIC_AUDIO_TRACK_PAGE", """[$byline,{"text":" • "},{"text":"Yesterday"}]""")
                .replace("\"playNavigationEndpoint\":$play", "\"playNavigationEndpoint\":{\"watchEndpoint\":{\"videoId\":\"episode\"}}")
            val carouselJson = """{
                "itemSize":"MUSIC_CAROUSEL_SHELF_ITEM_SIZE_LARGE",
                "header":{"musicCarouselShelfBasicHeaderRenderer":{"title":{"runs":[{"text":"Episodes"}]}}},
                "contents":[{"musicTwoRowItemRenderer":$episodeTile}]
            }"""
            val home = HomePage.Section.fromMusicCarouselShelfRenderer(json.decodeFromString<MusicCarouselShelfRenderer>(carouselJson))!!
            val artist = ArtistPage.fromSectionListRendererContent(json.decodeFromString<SectionListRenderer.Content>("""{"musicCarouselShelfRenderer":$carouselJson}"""))!!
            assertEquals(Artist("Owner & Friends", null), (home.items.single() as EpisodeItem).author)
            assertEquals(Artist("Owner & Friends", null), (artist.items.single() as EpisodeItem).author)
        }
    }
}
