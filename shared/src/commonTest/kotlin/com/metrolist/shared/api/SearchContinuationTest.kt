package com.metrolist.shared.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class SearchContinuationTest {
    @Test
    fun continuationShelfReturnsItemsAndNextToken() {
        val response =
            Json
                .parseToJsonElement(
                    """
                    {
                      "continuationContents": {
                        "musicShelfContinuation": {
                          "contents": [
                            {"musicResponsiveListItemRenderer": {"playlistItemData": {"videoId": "first"}}}
                          ],
                          "continuations": [
                            {"nextContinuationData": {"continuation": "next-token"}}
                          ]
                        }
                      }
                    }
                    """.trimIndent(),
                ).jsonObject

        val shelf = assertNotNull(response.searchContinuationShelf())

        assertEquals(1, shelf.items.size)
        assertEquals("next-token", shelf.continuation)
    }

    @Test
    fun continuationRendererProvidesTokenAndIsNotAnItem() {
        val response =
            Json
                .parseToJsonElement(
                    """
                    {
                      "continuationContents": {
                        "musicShelfContinuation": {
                          "contents": [
                            {"musicResponsiveListItemRenderer": {"playlistItemData": {"videoId": "first"}}},
                            {"continuationItemRenderer": {"continuationEndpoint": {"continuationCommand": {"token": "renderer-token"}}}}
                          ]
                        }
                      }
                    }
                    """.trimIndent(),
                ).jsonObject

        val shelf = assertNotNull(response.searchContinuationShelf())

        assertEquals(1, shelf.items.size)
        assertEquals("renderer-token", shelf.continuation)
    }
}
