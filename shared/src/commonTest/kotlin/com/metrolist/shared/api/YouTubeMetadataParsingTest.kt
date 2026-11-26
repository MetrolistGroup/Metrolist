package com.metrolist.shared.api

import com.metrolist.innertubex.models.response.BrowseResponse
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class YouTubeMetadataParsingTest {
    @Test
    fun suggestionClassificationKeepsPlaylistTracks() {
        fun shelf(id: String) =
            """{"title":{"runs":[{"text":"Tracks"}]},"contents":[{"musicResponsiveListItemRenderer":{
                "flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Song"}]}}}],
                "playlistItemData":{"videoId":"$id"}
            }}],"continuations":[{"reloadContinuationData":{"continuation":"reload"}}]}"""
        val suggestions =
            Json.decodeFromString<BrowseResponse>(
                """{"continuationContents":{"musicShelfContinuation":${shelf("suggested")}}}""",
            )
        val parsedSuggestions = YouTubeResponseParser.parseBrowseResponse(suggestions, classifyPlaylistSuggestions = true)
        assertEquals(listOf("suggested"), parsedSuggestions.sections["Suggestions"]?.map { it.id })
        assertTrue(parsedSuggestions.sections.playlistSongs().isEmpty())
        val tracks =
            Json.decodeFromString<BrowseResponse>(
                """{"contents":{"sectionListRenderer":{"contents":[{"musicPlaylistShelfRenderer":${shelf("track")}}]}}}""",
            )
        val parsedTracks = YouTubeResponseParser.parseBrowseResponse(tracks, classifyPlaylistSuggestions = true)
        assertFalse(parsedTracks.sections.containsKey("Suggestions"))
        assertEquals(listOf("track"), parsedTracks.sections.playlistSongs().map { it.id })
    }

    @Test
    fun continuationShelvesKeepTheirNavigationIds() {
        val response =
            Json.decodeFromString<BrowseResponse>(
                """{"continuationContents":{"sectionListContinuation":{"contents":[
                    {"musicShelfRenderer":{"title":{"runs":[{"text":"Section","navigationEndpoint":
                        {"browseEndpoint":{"browseId":"browse-id","params":"params"}}}]},"contents":[]}}
                ]}}}""",
            )
        val browseIds = mutableMapOf<String, Pair<String?, String?>>()
        YouTubeResponseParser.parseBrowseResponse(response, browseIds)
        assertEquals("browse-id" to "params", browseIds["Section"])
    }

    @Test
    fun parsesClockDurationsButNotYears() {
        assertEquals(225L, parseDurationSeconds("3:45"))
        assertEquals(3_723L, parseDurationSeconds("1:02:03"))
        assertNull(parseDurationSeconds("2024"))
        assertNull(parseDurationSeconds("3:75"))
        assertEquals(222L, parseDurationSeconds("3.42"))
        assertEquals(3_723L, parseDurationSeconds("1.02.03"))
        assertNull(parseDurationSeconds("1.234"))
    }

    @Test
    fun extractsOnlyListQueryParameterFromCanonicalUrl() {
        assertEquals(
            "OLAK5uy_test",
            "https://music.youtube.com/playlist?list=OLAK5uy_test&si=tracking".playlistIdFromCanonicalUrl(),
        )
        assertNull("https://music.youtube.com/watch?v=video".playlistIdFromCanonicalUrl())
    }
}
