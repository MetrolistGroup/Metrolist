package com.metrolist.shared.youtube.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlaybackResolveSummaryTest {
    private fun event(
        atMs: Long,
        kind: PlaybackEventKind,
        message: String,
        videoId: String? = "song",
        details: Map<String, String> = emptyMap(),
    ) = PlaybackEvent(atMs, atMs, PlaybackEventLevel.DEBUG, kind, message, videoId, details = details)

    @Test
    fun summarisesClientsFailuresAndTokenTiming() {
        val events =
            listOf(
                event(0, PlaybackEventKind.CLIENT, "[PlayerClientDirector] player response batch started"),
                event(
                    6_500,
                    PlaybackEventKind.CLIENT,
                    "[PlayerClientDirector] token fetch completed",
                    details =
                        mapOf(
                            "client" to "MWEB",
                            "tokenPresent" to "false",
                            "elapsedMs" to "17665",
                        ),
                ),
                event(
                    6_501,
                    PlaybackEventKind.CLIENT,
                    "[PlayerClientDirector] client response unavailable",
                    details =
                        mapOf("client" to "MWEB", "tokenUnavailable" to "true"),
                ),
                event(
                    8_000,
                    PlaybackEventKind.CLIENT,
                    "[PlayerClientDirector] player request failed",
                    details =
                        mapOf("client" to "VISIONOS", "exceptionType" to "SocketTimeoutException"),
                ),
                event(8_100, PlaybackEventKind.CLIENT, "other song", videoId = "other", details = mapOf("client" to "TVHTML5")),
                event(8_200, PlaybackEventKind.NETWORK, "ignored"),
            )

        val summary = playbackResolveSummary(events, "song", nowMs = 10_000)!!

        assertEquals("MWEB>VISIONOS", summary.tags["playback.clients_tried"])
        assertEquals("VISIONOS:SocketTimeoutException", summary.tags["playback.last_client_failure"])
        assertEquals("1", summary.tags["playback.token_failures"])
        assertEquals("15-30s", summary.tags["playback.slowest_token"])
        assertEquals("5-15s", summary.tags["playback.resolve_span"])
        assertEquals("MWEB:token_unavailable -> VISIONOS:SocketTimeoutException", summary.extras["playback.client_failures"])
    }

    @Test
    fun ignoresOldEvents() {
        assertNull(playbackResolveSummary(listOf(event(0, PlaybackEventKind.CLIENT, "x")), "song", nowMs = 200_000))
    }
}
