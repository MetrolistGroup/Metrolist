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
}
