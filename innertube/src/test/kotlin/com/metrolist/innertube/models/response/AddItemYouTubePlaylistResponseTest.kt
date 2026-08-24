package com.metrolist.innertube.models.response

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AddItemYouTubePlaylistResponseTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `setVideoId is read from the first edit result`() {
        val response = json.decodeFromString<AddItemYouTubePlaylistResponse>(
            """
            {
              "status": "STATUS_SUCCEEDED",
              "playlistEditResults": [
                {
                  "playlistEditVideoAddedResultData": {
                    "setVideoId": "SVID123",
                    "videoId": "VID456"
                  }
                }
              ]
            }
            """.trimIndent(),
        )

        assertEquals("SVID123", response.firstSetVideoId)
    }

    @Test
    fun `an edit response without results parses to a null setVideoId`() {
        val response = json.decodeFromString<AddItemYouTubePlaylistResponse>(
            """{"status":"STATUS_SUCCEEDED"}""",
        )

        assertNull(response.firstSetVideoId)
    }

    @Test
    fun `an edit result with no added data still parses`() {
        // A shape YouTube changed must not turn an add that happened into a failure, because a
        // failure is what makes the caller ask again.
        val response = json.decodeFromString<AddItemYouTubePlaylistResponse>(
            """{"playlistEditResults":[{}]}""",
        )

        assertNull(response.firstSetVideoId)
    }

    @Test
    fun `added data with no ids still parses`() {
        val response = json.decodeFromString<AddItemYouTubePlaylistResponse>(
            """{"playlistEditResults":[{"playlistEditVideoAddedResultData":{}}]}""",
        )

        assertNull(response.firstSetVideoId)
    }

    @Test
    fun `an empty body still parses`() {
        val response = json.decodeFromString<AddItemYouTubePlaylistResponse>("{}")

        assertNull(response.status)
        assertNull(response.firstSetVideoId)
    }

    @Test
    fun `a setVideoId later in the results is still found`() {
        val response = json.decodeFromString<AddItemYouTubePlaylistResponse>(
            """
            {
              "playlistEditResults": [
                {},
                {"playlistEditVideoAddedResultData": {"videoId": "VID456"}},
                {"playlistEditVideoAddedResultData": {"setVideoId": "SVID789", "videoId": "VID456"}}
              ]
            }
            """.trimIndent(),
        )

        assertEquals("SVID789", response.firstSetVideoId)
    }
}
