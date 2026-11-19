package com.metrolist.shared.youtube.lyrics

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InnerTubeLyricsApiTest {
    @Test
    fun captionTracksAreDetectedOnlyWhenTheTracklistHasEntries() {
        assertTrue(json("""{"captions":{"playerCaptionsTracklistRenderer":{"captionTracks":[{"baseUrl":"u"}]}}}""").hasCaptionTracks())
        assertFalse(json("""{"captions":{"playerCaptionsTracklistRenderer":{"captionTracks":[]}}}""").hasCaptionTracks())
        assertFalse(json("""{"playabilityStatus":{"status":"UNPLAYABLE"}}""").hasCaptionTracks())
    }

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject
}
