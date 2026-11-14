package com.metrolist.shared.api

import com.metrolist.innertubex.models.response.BrowseResponse
import com.metrolist.shared.model.PlaylistItem
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaylistHeaderTest {
    @Test
    fun publicUserPlaylistKeepsFacepileAndAllStatistics() {
        val playlist = parse(fixture("user"))
        assertEquals("WOOF", playlist.title)
        assertEquals("Hann", playlist.author)
        assertEquals("UCOdhZ1tDraXdiU3Y6HskbhQ", playlist.authorId)
        assertTrue(assertNotNull(playlist.authorThumbnail).startsWith("https://yt3.ggpht.com/"))
        assertEquals("140 views", playlist.viewCount)
        assertEquals("16 tracks", playlist.songCount)
        assertEquals("57 minutes", playlist.durationText)
        assertNotNull(playlist.thumbnail)
        assertFalse(playlist.isEditable)
    }

    @Test
    fun curatedPlaylistHasAnUnlinkedCreatorAndNoViews() {
        val playlist = parse(fixture("curated"))
        assertEquals("YouTube Music", playlist.author)
        assertNull(playlist.authorId)
        assertNull(playlist.authorThumbnail)
        assertNull(playlist.viewCount)
        assertEquals("265 songs", playlist.songCount)
        assertEquals("7+ hours", playlist.durationText)
        assertEquals("Throw it back to the essentials throughout the decades.", playlist.description)
    }

    @Test
    fun largePlaylistRetainsTheServerTotalBeforeTracksLoad() {
        val playlist = parse(fixture("large"))
        assertEquals("Redlist - Music Charts", playlist.author)
        assertEquals("UCo7i93EtJhQub3SDKrtIAPA", playlist.authorId)
        assertEquals("396K views", playlist.viewCount)
        assertEquals("500 tracks", playlist.songCount)
        assertEquals("34+ hours", playlist.durationText)
        assertNotNull(playlist.description)
    }

    @Test
    fun straplineAndEditableHeadersKeepCreatorEndpointsAndArtwork() {
        val album = fixture("album")
        val header =
            album["contents"]!!
                .jsonObject["twoColumnBrowseResultsRenderer"]!!
                .jsonObject["tabs"]!!
                .jsonArray
                .first()
                .jsonObject["tabRenderer"]!!
                .jsonObject["content"]!!
                .jsonObject["sectionListRenderer"]!!
                .jsonObject["contents"]!!
                .jsonArray
                .first()
                .jsonObject["musicResponsiveHeaderRenderer"]!!
                .jsonObject
        for (wrapper in listOf(
            """{"header":{"musicResponsiveHeaderRenderer":$header}}""",
            """{"header":{"musicEditablePlaylistDetailHeaderRenderer":{"header":{"musicResponsiveHeaderRenderer":$header}}}}""",
        )) {
            val playlist = parse(decodeYouTubeJson(wrapper))
            assertEquals("Fat Dog", playlist.author)
            assertEquals("UCZD6H27aZwvim4h72mbGVMg", playlist.authorId)
            assertNotNull(playlist.authorThumbnail)
            assertNotNull(playlist.thumbnail)
            assertEquals("9 songs", playlist.songCount)
            assertEquals("33 minutes", playlist.durationText)
        }
    }

    @Test
    fun legacyHeadersKeepLocalizedStatisticsAndLinkedCreators() {
        val playlist =
            parse(
                decodeYouTubeJson(
                    """
                    {"header":{"musicDetailHeaderRenderer":{
                      "title":{"simpleText":"Public playlist"},
                      "thumbnail":{"croppedSquareThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"https://example.com/cover"}]}}},
                      "subtitle":{"runs":[{"text":"Playlist"},{"text":" • "},{"text":"Creator 123","navigationEndpoint":{"browseEndpoint":{"browseId":"UCcreator"}}},{"text":" • "},{"text":"2026"}]},
                      "secondSubtitle":{"simpleText":"38 tys. wyświetleń • 35 utworów • 1 godzina, 41 minut"},
                      "description":{"runs":[{"text":"Description"}]}
                    }}}
                    """.trimIndent(),
                ),
            )
        assertEquals("Creator 123", playlist.author)
        assertEquals("UCcreator", playlist.authorId)
        assertEquals("38 tys. wyświetleń", playlist.viewCount)
        assertEquals("35 utworów", playlist.songCount)
        assertEquals("1 godzina, 41 minut", playlist.durationText)
        assertEquals("https://example.com/cover", playlist.thumbnail)
        assertEquals("Description", playlist.description)
    }

    @Test
    fun creatorRepeatedInStatisticsOnlyAppearsAsTheAuthor() {
        for (author in listOf("Creator", "Creator 123")) {
            val playlist =
                parse(
                    decodeYouTubeJson(
                        """{"header":{"musicDetailHeaderRenderer":{
                      "title":{"simpleText":"Public playlist"},
                      "subtitle":{"simpleText":"Playlist • $author • 2026"},
                      "secondSubtitle":{"simpleText":"$author • 35 songs • 41 minutes"}
                    }}}""",
                    ),
                )
            assertEquals(author, playlist.author)
            assertNull(playlist.viewCount)
            assertEquals("35 songs", playlist.songCount)
            assertEquals("41 minutes", playlist.durationText)
        }
    }

    @Test
    fun subtitleCanSupplyStatisticsWithoutInventingAnAuthorOrDuration() {
        val playlist =
            parse(
                decodeYouTubeJson(
                    """{"header":{"musicResponsiveHeaderRenderer":{"title":{"simpleText":"Playlist"},"subtitle":{"simpleText":"Playlist • 2026 • 25 Aufrufe • 2 Titel • 5 Minuten"}}}}""",
                ),
            )
        assertNull(playlist.author)
        assertEquals("25 Aufrufe", playlist.viewCount)
        assertEquals("2 Titel", playlist.songCount)
        assertEquals("5 Minuten", playlist.durationText)
        val missing = parse(decodeYouTubeJson("""{"header":{"musicResponsiveHeaderRenderer":{"title":{"simpleText":"Playlist"}}}}"""))
        assertNull(missing.songCount)
        assertNull(missing.durationText)
    }

    @Test
    fun ownLegacyPlaylistAndMicroformatFallbackKeepExistingMetadata() {
        val own =
            parse(
                decodeYouTubeJson(
                    """{"header":{"musicEditablePlaylistDetailHeaderRenderer":{"header":{"musicDetailHeaderRenderer":{"title":{"simpleText":"Own playlist"},"subtitle":{"simpleText":"You"},"secondSubtitle":{"simpleText":"2 songs"}}}}}}""",
                ),
            )
        assertTrue(own.isEditable)
        assertEquals("You", own.author)
        assertEquals("2 songs", own.songCount)
        assertNull(own.durationText)
        val fallback =
            parse(
                decodeYouTubeJson(
                    """{"microformat":{"microformatDataRenderer":{"title":"Fallback title","description":"Description","thumbnail":{"thumbnails":[{"url":"https://example.com/cover"}]}}}}""",
                ),
            )
        assertEquals("Fallback title", fallback.title)
        assertEquals("Description", fallback.description)
        assertEquals("https://example.com/cover", fallback.thumbnail)
    }

    private fun fixture(name: String): JsonObject {
        val text = checkNotNull(javaClass.getResourceAsStream("/youtube/playlist-header-$name.json")).bufferedReader().use { it.readText() }
        return decodeYouTubeJson(text)
    }

    private fun parse(raw: JsonObject): PlaylistItem {
        val response = repositoryJson.decodeFromJsonElement<BrowseResponse>(raw)
        return YouTubePlaylistParser
            .parsePlaylistSections(response, YouTubeResponseParser.parseBrowseResponse(response), "PLtest")
            .getValue("header")
            .single() as PlaylistItem
    }
}
