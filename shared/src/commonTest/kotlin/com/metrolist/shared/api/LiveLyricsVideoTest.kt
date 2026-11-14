package com.metrolist.shared.api

import com.metrolist.shared.model.LyricsProvider
import kotlin.test.Test
import kotlin.test.assertEquals

class LiveLyricsVideoTest {
    @Test
    fun liveVideoEvidenceMovesVideoLyricsAheadOfConfiguredProviders() {
        val configured = listOf(LyricsProvider.LRCLIB)
        val videoFirst = LyricsProvider.afterUserLyricsFallbacks + configured
        val configuredFirst = configured + LyricsProvider.afterUserLyricsFallbacks

        for ((type, title) in listOf(
            "MUSIC_VIDEO_TYPE_LIVE" to "Acoustic set",
            "MUSIC_VIDEO_TYPE_VIDEO" to "Track (Live)",
        )) {
            assertEquals(
                videoFirst,
                LyricsProvider.preferredProvidersForFetch(
                    configured,
                    configured.toSet(),
                    preferVideoProviders = isLiveLyricsVideo(type, title),
                ),
            )
        }
        assertEquals(
            configuredFirst,
            LyricsProvider.preferredProvidersForFetch(
                configured,
                configured.toSet(),
                preferVideoProviders = isLiveLyricsVideo("MUSIC_VIDEO_TYPE_ATV", "Track (Live)"),
            ),
        )
    }
}
