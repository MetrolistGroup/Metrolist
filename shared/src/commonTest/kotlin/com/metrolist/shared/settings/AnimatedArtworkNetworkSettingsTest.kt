package com.metrolist.shared.settings

import com.metrolist.shared.youtube.playback.PlaybackNetworkState
import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AnimatedArtworkNetworkSettingsTest {
    @Test
    fun mobileDataRestrictionIsOptInAndDoesNotChangeArtworkPreferences() {
        val backing = MapSettings()
        val settings = Settings(backing)
        val cellular = PlaybackNetworkState("mobile", isCellular = true)
        assertTrue(settings.isAnimatedArtworkAllowedOnNetwork(cellular))

        settings.enableAnimatedCovers = true
        settings.enableAppleMusicArtistVideos = true
        settings.disableAnimatedArtworkOnMobileData = true
        assertFalse(settings.isAnimatedArtworkAllowedOnNetwork(cellular))
        assertFalse(settings.isAnimatedArtworkAllowedOnNetwork(cellular.copy(connected = false)))
        assertTrue(settings.isAnimatedArtworkAllowedOnNetwork(PlaybackNetworkState("wifi")))
        assertTrue(settings.isAnimatedArtworkAllowedOnNetwork(PlaybackNetworkState("offline", connected = false)))
        assertTrue(settings.enableAnimatedCovers)
        assertTrue(settings.enableAppleMusicArtistVideos)
        assertTrue(Settings(backing).disableAnimatedArtworkOnMobileData)

        val restored = Settings(MapSettings())
        restored.importBackupSettings(settings.exportBackupSettings())
        assertTrue(restored.disableAnimatedArtworkOnMobileData)
        assertFalse(restored.isAnimatedArtworkAllowedOnNetwork(cellular))
        restored.disableAnimatedArtworkOnMobileData = false
        assertTrue(restored.isAnimatedArtworkAllowedOnNetwork(cellular))
    }
}
