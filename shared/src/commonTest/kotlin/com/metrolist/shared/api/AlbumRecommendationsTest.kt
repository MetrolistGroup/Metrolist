package com.metrolist.shared.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlin.test.Test
import kotlin.test.assertEquals

class AlbumRecommendationsTest {
    @Test
    fun localizedOrMissingShelfTitlesDoNotChangeClassification() {
        for (title in listOf("Andere Versionen", "Więcej od wykonawcy", "", "Other versions")) {
            fun shelf(
                size: String,
                id: String,
            ): String =
                """{
                "musicCarouselShelfRenderer":{
                    "itemSize":"$size",
                    "header":{"musicCarouselShelfBasicHeaderRenderer":{"title":{"runs":[{"text":"$title"}]}}},
                    "contents":[{"musicTwoRowItemRenderer":{
                        "title":{"runs":[{"text":"Album"}]},
                        "navigationEndpoint":{"browseEndpoint":{"browseId":"$id",
                            "browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"MUSIC_PAGE_TYPE_ALBUM"}}}}
                    }}]
                }
            }"""
            val shelves =
                listOf(
                    shelf("COLLECTION_STYLE_ITEM_SIZE_SMALL", "MPRE_artist"),
                    shelf("COLLECTION_STYLE_ITEM_SIZE_MEDIUM", "MPRE_version"),
                    shelf("COLLECTION_STYLE_ITEM_SIZE_UNKNOWN", "MPRE_unknown"),
                    shelf("COLLECTION_STYLE_ITEM_SIZE_MEDIUM", "MPRE_second"),
                )
            val (versions, artistAlbums) =
                YouTubeAlbumParser.parseAlbumRecommendations(
                    Json.parseToJsonElement(shelves.joinToString(",", "[", "]")).jsonArray,
                )
            assertEquals(listOf("MPRE_version", "MPRE_second"), versions.map { it.id })
            assertEquals(listOf("MPRE_artist"), artistAlbums.map { it.id })
        }
    }
}
