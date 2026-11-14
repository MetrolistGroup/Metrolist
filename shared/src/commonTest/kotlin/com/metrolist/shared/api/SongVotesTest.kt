package com.metrolist.shared.api

import com.metrolist.shared.api.YouTubeResponseParser.asObject
import com.metrolist.shared.api.YouTubeResponseParser.findAllObjects
import com.metrolist.shared.api.YouTubeResponseParser.primitiveText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class SongVotesTest {
    @Test
    fun rowMenusOverrideLaterGlobalButtonsAndRespectExplicitTargets() {
        val response =
            Json
                .parseToJsonElement(
                    """{"contents":[
                {"musicResponsiveListItemRenderer":{
                    "overlay":[[{"watchEndpoint":{"videoId":"overlay"}},{"watchEndpoint":{"videoId":"wrong"}}]],
                    "menu":{"buttons":[
                        {"likeButtonRenderer":{"likeStatus":"LIKE"}},
                        {"likeButtonRenderer":{"target":{"playlistId":"not-video"},"likeStatus":"DISLIKE"}}
                    ]}}},
                {"musicResponsiveListItemRenderer":{"playlistItemData":{"videoId":"row"},
                    "menu":{"likeButtonRenderer":{"target":{"videoId":"other"},"likeStatus":"DISLIKE"}}}},
                {"likeButtonRenderer":{"target":{"videoId":"overlay"},"likeStatus":"INDIFFERENT"}},
                {"likeButtonRenderer":{"target":{"videoId":"other"},"likeStatus":"LIKE"}}
            ]}""",
                ).jsonObject
        assertEquals(mapOf("overlay" to SongVote.LIKE, "other" to SongVote.DISLIKE), parseSongVotes(response))
    }

    @Test
    fun laterRowsWinEvenWhenNestedInsideAnEarlierRowsMenu() {
        val response =
            Json
                .parseToJsonElement(
                    """{"musicResponsiveListItemRenderer":{"playlistItemData":{"videoId":"same"},"menu":{"contents":[
                {"musicResponsiveListItemRenderer":{"playlistItemData":{"videoId":"same"},
                    "menu":{"likeButtonRenderer":{"likeStatus":"DISLIKE"}}}},
                {"likeButtonRenderer":{"likeStatus":"LIKE"}}
            ]}}}""",
                ).jsonObject
        assertEquals(mapOf("same" to SongVote.DISLIKE), parseSongVotes(response))
        val distinctRows = Json.parseToJsonElement(response.toString().replaceFirst("\"same\"", "\"outer\"")).jsonObject
        assertEquals(legacySongVotes(distinctRows), parseSongVotes(distinctRows))
    }

    @Test
    fun readsOnlyExplicitStatusBoundToAVideo() {
        val response =
            Json
                .parseToJsonElement(
                    """{"contents":[
              {"likeButtonRenderer":{"target":{"videoId":"disliked"},"likeStatus":"DISLIKE"}},
              {"likeButtonRenderer":{"target":{"videoId":"neutral"},"likeStatus":"INDIFFERENT"}},
              {"likeButtonRenderer":{"target":{"playlistId":"playlist"},"likeStatus":"DISLIKE"}},
              {"likeButtonRenderer":{"target":{"videoId":"unknown"},"likeStatus":"UNKNOWN"}},
              {"likeButtonRenderer":{"target":{"videoId":"missing"}}},
              {"likeButtonRenderer":{"likeStatus":"DISLIKE"}},
              {"musicResponsiveListItemRenderer":{"playlistItemData":{"videoId":"liked"},
                "menu":{"menuRenderer":{"topLevelButtons":[{"likeButtonRenderer":{"likeStatus":"LIKE"}}]}}}}
            ]}""",
                ).jsonObject
        assertEquals(
            mapOf("disliked" to SongVote.DISLIKE, "neutral" to SongVote.INDIFFERENT, "liked" to SongVote.LIKE),
            parseSongVotes(response),
        )
    }
}

internal fun legacySongVotes(response: JsonObject): Map<String, SongVote> =
    buildMap {
        fun record(
            button: JsonObject,
            rowId: String? = null,
        ) {
            val target = button["target"].asObject()
            val id = if (target == null) rowId else target["videoId"].primitiveText()
            if (id == null) return
            val status = button["likeStatus"].primitiveText() ?: return
            val vote = SongVote.entries.firstOrNull { it.name == status } ?: return
            if (id.isNotBlank()) put(id, vote)
        }
        response.findAllObjects("likeButtonRenderer").forEach { record(it) }
        response.findAllObjects("musicResponsiveListItemRenderer").forEach { row ->
            val id =
                row["playlistItemData"].asObject()?.get("videoId").primitiveText()
                    ?: row["overlay"]
                        .findAllObjects("watchEndpoint")
                        .firstOrNull()
                        ?.get("videoId")
                        .primitiveText()
            row["menu"].findAllObjects("likeButtonRenderer").forEach { record(it, id) }
        }
    }
