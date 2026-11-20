package com.metrolist.shared.playback

import com.metrolist.innertubex.extraction.StreamAttemptDiagnostic
import com.metrolist.innertubex.extraction.StreamDiagnostics
import com.metrolist.shared.settings.Settings
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlaybackClientStatsTest {
    @Test
    fun countsCatalogProfilesAndReportsOnEveryFifthLaunch() =
        runTest {
            val previousSettings = Settings.sharedOverrideForTest()
            Settings.replaceSharedForTest(Settings(MapSettings()))
            try {
                PlaybackClientStats.recordAttempts(
                    StreamDiagnostics(
                        attempts =
                            listOf(
                                attempt("WEB_REMIX__po", "request:SocketTimeoutException"),
                                attempt("TVHTML5_SIMPLY__po", "po_token_unavailable"),
                                attempt("VISIONOS_0_1__nopo", "playable_response"),
                                attempt("IOS_MUSIC__nopo", "selection:client marked broken"),
                                attempt("cached-audio", "playable_response"),
                            ),
                        usedAuthenticatedWatchPage = false,
                    ),
                )
                PlaybackClientStats.recordPlayback("VISIONOS_0_1__nopo")
                PlaybackClientStats.recordPlayback("VISIONOS_0_1__nopo", PlaybackFailureCategory.CDN_FORBIDDEN)
                PlaybackClientStats.recordPlayback("local")

                repeat(PlaybackClientStats.REPORT_EVERY_LAUNCHES - 1) { assertNull(PlaybackClientStats.takeReportOnLaunch()) }
                assertEquals(
                    mapOf(
                        "WEB_REMIX__po" to mapOf("attempt.request_failure" to 1),
                        "TVHTML5_SIMPLY__po" to mapOf("attempt.po_token_unavailable" to 1),
                        "VISIONOS_0_1__nopo" to mapOf("attempt.playable_response" to 1, "play.ok" to 1, "play.fail.cdn_forbidden" to 1),
                    ),
                    PlaybackClientStats.takeReportOnLaunch(),
                )
                repeat(PlaybackClientStats.REPORT_EVERY_LAUNCHES) { assertNull(PlaybackClientStats.takeReportOnLaunch()) }
            } finally {
                Settings.replaceSharedForTest(previousSettings)
            }
        }

    private fun attempt(
        profileId: String,
        outcome: String,
    ) = StreamAttemptDiagnostic(clientName = profileId.substringBefore("__"), profileId = profileId, userAgent = "", outcome = outcome)
}
