package com.metrolist.shared.youtube.extraction

import com.metrolist.innertubex.extraction.strategy.PlaybackClientCatalog
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.youtube.extraction.models.automaticVideoMaxHeight
import com.metrolist.shared.youtube.extraction.models.toContentHints
import com.metrolist.shared.youtube.playback.PlaybackBandwidthMeter
import com.metrolist.shared.youtube.playback.PlaybackNetworkState
import com.metrolist.shared.youtube.playback.PlaybackNetworkStateStore
import com.russhwolf.settings.MapSettings
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContentHintsTest {
    private val previousNetwork = PlaybackNetworkStateStore.current

    @Test
    fun automaticAudioPrefersDirectAndManualOverridesKeepTheirSelection() {
        val previousSettings = Settings.sharedOverrideForTest()
        Settings.replaceSharedForTest(Settings(MapSettings()))
        try {
            val song = SongItem(id = "song", title = "Song", thumbnail = null, artists = emptyList(), album = null)
            for (explicit in listOf(false, true)) {
                val hints = song.copy(isExplicit = explicit).toContentHints(wantVideo = false)
                assertFalse(hints.sabrFirst)
                assertFalse(hints.wantVideo)
                assertTrue(hints.allowSabr)
                assertEquals(explicit, hints.isExplicit)
                assertEquals(null, hints.playbackClientOverrideId)
            }
            Settings.shared.devtoolsPlaybackClient = PlaybackClientCatalog.SABR_FIRST_ID
            assertTrue(song.toContentHints().sabrFirst)
            Settings.shared.devtoolsPlaybackClient =
                PlaybackClientCatalog.devtoolsOverrideOptions
                    .first { it.id != PlaybackClientCatalog.SABR_FIRST_ID }
                    .id
            assertFalse(song.toContentHints().sabrFirst)
        } finally {
            Settings.replaceSharedForTest(previousSettings)
        }
    }

    @Test
    fun audioOnlyPlaybackOfVideoTracksKeepsContentMetadataWithoutRequiringVideoClients() {
        Settings.replaceSharedForTest(Settings(MapSettings()))
        try {
            for (type in listOf("MUSIC_VIDEO_TYPE_OMV", "MUSIC_VIDEO_TYPE_UGC", "MUSIC_VIDEO_TYPE_LIVE")) {
                val song =
                    SongItem(
                        id = "video",
                        title = "Video",
                        thumbnail = null,
                        artists = emptyList(),
                        album = null,
                        musicVideoType = type,
                        isExplicit = true,
                        endpointParams = "playback-params",
                    )
                val defaultHints = song.toContentHints()
                assertTrue(defaultHints.wantVideo)
                assertTrue(defaultHints.allowSabr)
                assertEquals(defaultHints.copy(wantVideo = false), song.toContentHints(wantVideo = false))
                assertEquals(defaultHints, song.toContentHints(wantVideo = true))
            }
        } finally {
            Settings.replaceSharedForTest(null)
        }
    }

    @AfterTest
    fun resetNetworkState() {
        PlaybackNetworkStateStore.publish(previousNetwork)
        PlaybackBandwidthMeter.reset()
    }

    @Test
    fun automaticVideoHeightUsesBandwidthAndNetworkScope() {
        PlaybackNetworkStateStore.publish(PlaybackNetworkState(scope = "wifi", connected = true))
        PlaybackBandwidthMeter.reset(20_000_000L)
        assertEquals(2160, automaticVideoMaxHeight(deviceMaxHeight = 2160))
        assertEquals(1080, automaticVideoMaxHeight(deviceMaxHeight = 1080))
        assertEquals(1440, automaticVideoMaxHeight(deviceMaxHeight = 1440))

        PlaybackBandwidthMeter.reset(9_000_000L)
        assertEquals(1440, automaticVideoMaxHeight(deviceMaxHeight = 2160))
        assertEquals(1080, automaticVideoMaxHeight(deviceMaxHeight = 1080))

        PlaybackBandwidthMeter.reset(6_000_000L)
        assertEquals(1080, automaticVideoMaxHeight(deviceMaxHeight = 2160))

        PlaybackBandwidthMeter.reset(3_000_000L)
        assertEquals(720, automaticVideoMaxHeight(deviceMaxHeight = 2160))

        PlaybackBandwidthMeter.reset(1_000_000L)
        assertEquals(480, automaticVideoMaxHeight(deviceMaxHeight = 2160))

        PlaybackNetworkStateStore.publish(PlaybackNetworkState(scope = "cellular", connected = true))
        PlaybackBandwidthMeter.reset(50_000_000L)
        assertEquals(720, automaticVideoMaxHeight(deviceMaxHeight = 2160))
    }
}
