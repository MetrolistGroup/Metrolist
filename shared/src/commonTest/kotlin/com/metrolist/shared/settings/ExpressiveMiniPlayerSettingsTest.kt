package com.metrolist.shared.settings

import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExpressiveMiniPlayerSettingsTest {
    private val transportButtons =
        listOf(
            ExpressiveMiniPlayerButton.PREVIOUS,
            ExpressiveMiniPlayerButton.PLAY_PAUSE,
            ExpressiveMiniPlayerButton.NEXT,
        )

    @Test
    fun oldSettingsKeepExpressiveTransportAndModernChoices() {
        val settings = Settings(MapSettings())
        settings.importBackupSettings(
            settings.portableSettingsFromLegacy(
                mapOf(
                    "miniPlayerType" to MiniPlayerType.EXPRESSIVE.name,
                    "showMiniPlayerPlaylistButton" to true,
                    "showMiniPlayerLikeButton" to true,
                    "showMiniPlayerSubscribeButton" to true,
                ),
            ),
        )
        assertEquals(MiniPlayerType.EXPRESSIVE, settings.miniPlayerType)
        assertEquals(transportButtons, settings.expressiveMiniPlayerButtons)
        assertFalse(settings.showExpressiveMiniPlayerProgress)
        assertFalse(settings.expressiveMiniPlayerArtworkPlayback)
        assertTrue(settings.expressiveMiniPlayerCookieArtwork)
        assertTrue(settings.showMiniPlayerPlaylistButton)
        assertTrue(settings.showMiniPlayerLikeButton)
        assertTrue(settings.showMiniPlayerSubscribeButton)
    }

    @Test
    fun everyButtonCanBeHiddenIndependentlyAndSurvivesRestore() {
        for (mask in 0 until (1 shl ExpressiveMiniPlayerButton.entries.size)) {
            val backing = MapSettings()
            val settings = Settings(backing)
            settings.showExpressiveMiniPlayerPreviousButton = mask and 1 != 0
            settings.showExpressiveMiniPlayerPlayPauseButton = mask and 2 != 0
            settings.showExpressiveMiniPlayerNextButton = mask and 4 != 0
            settings.showExpressiveMiniPlayerPlaylistButton = mask and 8 != 0
            settings.showExpressiveMiniPlayerSubscribeButton = mask and 16 != 0
            settings.showExpressiveMiniPlayerLikeButton = mask and 32 != 0
            val expected = ExpressiveMiniPlayerButton.entries.filterIndexed { index, _ -> mask and (1 shl index) != 0 }
            val restored = Settings(MapSettings())
            restored.importBackupSettings(settings.exportBackupSettings())
            for (loaded in listOf(settings, Settings(backing), restored)) {
                assertEquals(expected, loaded.expressiveMiniPlayerButtons, "Button mask $mask")
                assertTrue(loaded.showMiniPlayerPlaylistButton)
                assertTrue(loaded.showMiniPlayerLikeButton)
                assertFalse(loaded.showMiniPlayerSubscribeButton)
            }
        }
    }

    @Test
    fun artworkOptionsPersistWithoutChangingOtherStyles() {
        val backing = MapSettings()
        val settings = Settings(backing)
        settings.showExpressiveMiniPlayerProgress = true
        settings.expressiveMiniPlayerArtworkPlayback = true
        settings.expressiveMiniPlayerCookieArtwork = false
        settings.expressiveMiniPlayerShapeMotion = false
        val restored = Settings(MapSettings())
        restored.importBackupSettings(settings.exportBackupSettings())
        for (loaded in listOf(Settings(backing), restored)) {
            assertTrue(loaded.showExpressiveMiniPlayerProgress)
            assertTrue(loaded.expressiveMiniPlayerArtworkPlayback)
            assertFalse(loaded.expressiveMiniPlayerCookieArtwork)
            assertFalse(loaded.expressiveMiniPlayerShapeMotion)
            assertEquals(transportButtons, loaded.expressiveMiniPlayerButtons)
            assertEquals(MiniPlayerType.EXPRESSIVE, loaded.miniPlayerType)
        }
    }
}
