package com.metrolist.shared.api

import com.metrolist.innertubex.InnerTube
import com.metrolist.shared.settings.Settings
import com.russhwolf.settings.MapSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class YouTubePlaybackTrackingTest {
    @Test
    fun reportingRequiresLoginSyncAndUnpausedHistoryForEveryPing() =
        runBlocking {
            Settings.replaceSharedForTest(Settings(MapSettings()))
            val engine = MockEngine { error("Disabled reporting must not contact YouTube") }
            val client = HttpClient(engine)
            val tube = InnerTube(client)
            try {
                val tracking = YouTubePlaybackTracking(tube)
                for ((cookie, sync, paused) in listOf(
                    Triple("", true, false),
                    Triple("PREF=theme=3", true, false),
                    Triple("SAPISID=account", false, false),
                    Triple("SAPISID=account", true, true),
                )) {
                    tube.replaceSession(cookie, "visitor", "account", "0", true)
                    Settings.shared.ytmSync = sync
                    Settings.shared.pauseListenHistory = paused
                    val session = tracking.playbackTrackingSession()
                    assertEquals(false, tracking.isPlaybackReportingEnabled())
                    assertEquals(false, tracking.registerPlayback("https://s.youtube.com/api/stats/playback", "nonce", "0", null, session))
                    assertEquals(
                        false,
                        tracking.registerWatchtime(
                            "https://s.youtube.com/api/stats/watchtime",
                            "nonce",
                            "0",
                            "1",
                            "1",
                            "1",
                            true,
                            null,
                            session,
                        ),
                    )
                }
                assertEquals(0, engine.requestHistory.size)
            } finally {
                tube.close()
                client.close()
                Settings.replaceSharedForTest(null)
            }
        }

    @Test
    fun trackingUrlValidationPreventsRequestsOutsideAllowedHttpsEndpoints() =
        runBlocking {
            Settings.replaceSharedForTest(Settings(MapSettings()).apply { pauseListenHistory = false })
            val engine = MockEngine { respond("") }
            val client = HttpClient(engine)
            val tube = InnerTube(client, retryDelay = {}).apply { cookie = "SAPISID=original" }
            try {
                val tracking = YouTubePlaybackTracking(tube)
                val session = tracking.playbackTrackingSession()
                val invalidUrls =
                    listOf(
                        "http://music.youtube.com/api/stats/playback",
                        "https://music.youtube.com.evil.example/api/stats/playback",
                        "https://evil-music.youtube.com/api/stats/playback",
                        "https://youtube.com/api/stats/playback",
                        "https://user@music.youtube.com/api/stats/playback",
                        "https://user:password@s.youtube.com/api/stats/playback",
                        "https://music.youtube.com:444/api/stats/playback",
                        "https://music.youtube.com/api/stats/other",
                        "https://music.youtube.com/api/stats/playback/extra",
                    )
                for (url in invalidUrls) {
                    assertEquals(false, tracking.registerPlayback(url, "nonce", "0", null, session), url)
                    assertEquals(false, tracking.registerWatchtime(url, "nonce", "0", "1", "1", "1", false, null, session), url)
                    assertEquals(0, engine.requestHistory.size, url)
                }
                for (host in listOf("music.youtube.com", "s.youtube.com")) {
                    assertEquals(true, tracking.registerPlayback("https://$host/api/stats/playback", "nonce", "0", null, session))
                    assertEquals(
                        true,
                        tracking.registerWatchtime("https://$host/api/stats/watchtime", "nonce", "0", "1", "1", "1", false, null, session),
                    )
                }
                assertEquals(4, engine.requestHistory.size)
                engine.requestHistory.forEach { assertNotNull(it.headers[HttpHeaders.Authorization]) }
            } finally {
                tube.close()
                client.close()
                Settings.replaceSharedForTest(null)
            }
        }
}
