package com.metrolist.shared.repository

import com.metrolist.innertubex.InnerTube
import com.metrolist.shared.BuildConfig
import com.metrolist.shared.api.lyrics.LyprioLyricsProvider
import com.metrolist.shared.api.lyrics.LyricsClient
import com.metrolist.shared.api.lyrics.LyricsTranslationClient
import com.metrolist.shared.api.lyrics.LyricsUtils
import com.metrolist.shared.model.LyricsTimingKind
import com.metrolist.shared.network.createHttpClient
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.youtube.lyrics.InnerTubeLyricsApi
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LyricsPreviewLiveTest {
    @Test
    fun previewFetchesRealSyncedLyricsAndReusesTheSessionResult() =
        runBlocking {
            if (System.getProperty("runLiveLyricsTests") != "true" && System.getenv("RUN_LIVE_LYRICS_TESTS") != "true") {
                return@runBlocking
            }
            Settings.replaceSharedForTest(Settings(MapSettings()))
            try {
                createHttpClient().use { client ->
                    val api = InnerTubeLyricsApi(InnerTube(client))
                    val lyprio = LyprioLyricsProvider.createOrNull(client, BuildConfig.LYPRIO_BASE_URL, BuildConfig.LYPRIO_HMAC_KEY)
                    for (providers in listOfNotNull("LRCLIB", "LYPRIO,LRCLIB".takeIf { lyprio != null })) {
                        Settings.shared.lyricsProviderOrder = providers
                        Settings.shared.lyricsEnabledProviders = providers
                        val repository = LyricsRepository(LyricsClient(client, api, lyprio), LyricsTranslationClient(client))
                        val result = assertNotNull(repository.getPreviewLyrics())
                        assertTrue(result.timingKind != LyricsTimingKind.UNSYNCED)
                        val entries = LyricsUtils.parseLyrics(result.lyrics)
                        assertTrue(entries.count { it.text.isNotBlank() } > 20)
                        assertTrue(entries.any { it.text.contains("never gonna give you up", ignoreCase = true) })
                        assertTrue(entries.any { it.time > 0 })
                        assertSame(result, repository.getPreviewLyrics())
                        println("Preview fetched ${entries.size} timed lines from ${result.providerName}")
                    }
                }
            } finally {
                Settings.replaceSharedForTest(null)
            }
        }
}
