package com.metrolist.shared.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class YouTubeMetadataParsingTest {
    @Test
    fun parsesClockDurationsButNotYears() {
        assertEquals(225L, parseDurationSeconds("3:45"))
        assertEquals(3_723L, parseDurationSeconds("1:02:03"))
        assertNull(parseDurationSeconds("2024"))
        assertNull(parseDurationSeconds("3:75"))
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
