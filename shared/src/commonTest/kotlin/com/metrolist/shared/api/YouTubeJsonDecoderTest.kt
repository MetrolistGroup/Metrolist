package com.metrolist.shared.api

import com.metrolist.innertubex.models.response.BrowseResponse
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class YouTubeJsonDecoderTest {
    @Test
    fun skipsTelemetryButRetainsMenusEndpointsAndUnknownFields() {
        val text = """{
            "trackingParams":"opaque",
            "contents":[{"clickTrackingParams":"opaque","loggingContext":{"data":[1,true,null,{}]},
                "menu":{"editPlaylistEndpoint":{"actions":[{"action":"ACTION_REMOVE_VIDEO"}]}},
                "playlistItemData":{"videoId":"song","playlistSetVideoId":"occurrence"},
                "unknown":{"continuations":[{"nextContinuationData":{"continuation":"next"}}]}}],
            "loggingDirectives":{"nested":[{"a":"escaped\"quote"}]}
        }"""
        val full = repositoryJson.parseToJsonElement(text)
        val decoded = decodeYouTubeJson(text)
        assertEquals(withoutTelemetry(full), decoded)
        assertEquals(full.hasPlaylistEditControls(), decoded.hasPlaylistEditControls())
        assertEquals(full.jsonObject.findContinuationToken(), decoded.findContinuationToken())
    }

    @Test
    fun preservesEscapesUnicodeNumbersDuplicateKeysAndEmptyContainers() {
        for (text in listOf(
            """{"text":"\"\\\/\b\f\n\r\t\u0041\uD83D\uDE00","number":-1.25e+12,"nil":null,"yes":true,"no":false}""",
            """{"a":1,"a":2,"empty":{},"array":[[],{},0,-0,1e-9],"naïve":"日本語 🎵"}""",
            """{"tr\u0061ckingParams":"opaque","key\u0041":"value","loggingContext":"x"}""",
        )) {
            assertEquals(withoutTelemetry(repositoryJson.parseToJsonElement(text)), decodeYouTubeJson(text))
        }
    }

    @Test
    fun malformedTelemetryIsStillRejected() {
        for (text in listOf(
            """{"trackingParams":"\q"}""",
            """{"loggingContext":{"a":[1,]}}""",
            """{"loggingContext":{"a":"\uZZZZ"}}""",
            """{"loggingContext":{"a":true false}}""",
            """{"trackingParams":"unfinished}""",
            """{"a":1} trailing""",
            """{"loggingContext":{"a":{}}""",
        )) {
            assertFails(text) { decodeYouTubeJson(text) }
        }
    }

    @Test
    fun deeplyNestedResponseUsesLibraryFallback() {
        val text = """{"unknown":""" + "[".repeat(140) + "0" + "]".repeat(140) + "}"
        assertEquals(repositoryJson.parseToJsonElement(text), decodeYouTubeJson(text))
    }

    @Test
    fun flagsRecognizeNestedBadgesEpisodeTypesAndEditControls() {
        for (text in listOf(
            """{"title":"An explicit title","other":{"nested":[{"iconType":"MUSIC_NEW_RELEASE_BADGE"}]}}""",
            """{"futureWrapper":[[{"icon":"MUSIC_EXPLICIT_BADGE","type":"MUSIC_VIDEO_TYPE_PODCAST_EPISODE"}]],"menu":{"editPlaylistEndpoint":{}}}""",
            """{"type":"MUSIC_PAGE_TYPE_PODCAST_EPISODE_DETAIL_PAGE","title":"escaped\"title"}""",
            """{"navigationEndpoint":{"browseEndpoint":{"pageType":"MUSIC_PAGE_TYPE_NON_MUSIC_AUDIO_TRACK_PAGE"}}}""",
        )) {
            val json = repositoryJson.parseToJsonElement(text)
            val serialized = json.toString()
            val flags = json.rendererFlags()
            assertEquals(serialized.contains("MUSIC_EXPLICIT_BADGE"), flags.explicit)
            assertEquals(serialized.contains("MUSIC_NEW_RELEASE_BADGE"), flags.newRelease)
            assertEquals(
                serialized.contains("PODCAST_EPISODE") || serialized.contains("MUSIC_PAGE_TYPE_NON_MUSIC_AUDIO_TRACK_PAGE"),
                flags.podcastEpisode,
            )
            assertEquals(serialized.contains("editPlaylistEndpoint"), flags.editable)
        }
    }

    @Test
    fun editabilityRequiresControlsInTheResponse() {
        val ordinary = decodeYouTubeJson("""{"header":{"musicDetailHeaderRenderer":{"title":{"runs":[{"text":"Playlist"}]}}}}""")
        val editable = decodeYouTubeJson("""{"header":{"musicEditablePlaylistDetailHeaderRenderer":{}}}""")
        assertFalse(ordinary.hasPlaylistEditControls())
        assertTrue(editable.hasPlaylistEditControls())
        for ((raw, expected) in listOf(ordinary to false, editable to true)) {
            val response = repositoryJson.decodeFromJsonElement<BrowseResponse>(raw)
            assertEquals(expected, response.hasPlaylistEditControls())
        }
        assertTrue(decodeYouTubeJson("""{"menu":{"action":"ACTION_MOVE_VIDEO_BEFORE"}}""").hasPlaylistEditControls())
    }
}

internal fun withoutTelemetry(element: JsonElement): JsonElement =
    when (element) {
        is JsonObject -> {
            JsonObject(
                element
                    .filterKeys {
                        it !in setOf("trackingParams", "clickTrackingParams", "loggingContext", "loggingDirectives")
                    }.mapValues { withoutTelemetry(it.value) },
            )
        }

        is JsonArray -> {
            JsonArray(element.map(::withoutTelemetry))
        }

        else -> {
            element
        }
    }
