package com.metrolist.shared.api.musicvideo

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MusicVideoCounterpartTest {
    @Test
    fun parsesSongToVideoCounterpartAndSegments() {
        val result = parseMusicVideoCounterpart(wrapperResponse(), "TRACK")

        assertEquals("VIDEO", result?.targetVideoId)
        assertEquals(4_992L, result?.positionMap?.mapPosition(500L))
    }

    @Test
    fun reversesVideoToSongCounterpartAndSegments() {
        val result = parseMusicVideoCounterpart(wrapperResponse(), "VIDEO")

        assertEquals("TRACK", result?.targetVideoId)
        assertEquals(500L, result?.positionMap?.mapPosition(4_992L))
    }

    @Test
    fun findsWrapperAfterOrdinaryQueueItem() {
        val result = parseMusicVideoCounterpart(wrapperResponse(includeLeadingItem = true), "TRACK")

        assertEquals("VIDEO", result?.targetVideoId)
    }

    @Test
    fun malformedSegmentsKeepAuthoritativePairWithoutMap() {
        val response = wrapperResponse(segment = """{"durationMilliseconds":"nope"}""")
        val result = parseMusicVideoCounterpart(response, "TRACK")

        assertEquals("VIDEO", result?.targetVideoId)
        assertNull(result?.positionMap)
    }

    @Test
    fun ordinaryQueueHasNoAuthoritativeCounterpart() {
        val response = Json.parseToJsonElement("""{"contents":[{"playlistPanelVideoRenderer":{"videoId":"TRACK"}}]}""").jsonObject

        assertNull(parseMusicVideoCounterpart(response, "TRACK"))
    }

    @Test
    fun mapsMultipleSegmentsAndClampsUnmappedPositions() {
        val map =
            CounterpartPositionMap(
                listOf(
                    CounterpartSegment(100, 1_000, 100),
                    CounterpartSegment(500, 2_000, 100),
                ),
            )

        assertEquals(1_000L, map.mapPosition(50))
        assertEquals(1_050L, map.mapPosition(150))
        assertEquals(2_000L, map.mapPosition(200))
        assertEquals(2_000L, map.mapPosition(300))
        assertEquals(2_050L, map.mapPosition(550))
        assertEquals(2_100L, map.mapPosition(600))
        assertEquals(2_100L, map.mapPosition(700))
    }

    @Test
    fun reversesVideoToTrackSegmentsForLyricsPlaybackOnVideoTimeline() {
        val videoToTrack = CounterpartPositionMap(listOf(CounterpartSegment(0, 4_492, 10_000)))

        assertEquals(4_992L, videoToTrack.mapPosition(500L))
        assertEquals(500L, videoToTrack.reversed().mapPosition(4_992L))
    }

    private fun wrapperResponse(
        includeLeadingItem: Boolean = false,
        segment: String =
            """{
                "primaryVideoStartTimeMilliseconds":"0",
                "counterpartVideoStartTimeMilliseconds":"4492",
                "durationMilliseconds":"131989"
            }""",
    ) = Json
        .parseToJsonElement(
            """{
            "contents":[
                ${if (includeLeadingItem) """{"playlistPanelVideoRenderer":{"videoId":"OTHER"}},""" else ""}
                {"playlistPanelVideoWrapperRenderer":{
                    "primaryRenderer":{"playlistPanelVideoRenderer":{"videoId":"TRACK"}},
                    "counterpart":[{
                        "counterpartRenderer":{"playlistPanelVideoRenderer":{"videoId":"VIDEO"}},
                        "segmentMap":{"segment":[$segment]}
                    }]
                }}
            ]
        }""",
        ).jsonObject
}
