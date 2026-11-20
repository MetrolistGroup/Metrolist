package com.metrolist.shared.youtube.extraction

import com.metrolist.shared.model.SongItem
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.youtube.extraction.models.toContentHints
import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProgressiveAudioHintsTest {
    @Test
    fun playbackHintsAllowProgressiveAudioButDownloadCapabilitiesDoNot() {
        val previousSettings = Settings.sharedOverrideForTest()
        Settings.replaceSharedForTest(Settings(MapSettings()))
        try {
            val hints =
                SongItem(id = "song", title = "Song", thumbnail = null, artists = emptyList(), album = null, duration = 200)
                    .toContentHints()
            assertTrue(hints.allowProgressive)
            assertTrue(hints.copy(wantVideo = true).allowProgressive)
            assertFalse(hints.withStreamCapabilities(allowHls = false, allowSabr = true, allowBoundedRange = true).allowProgressive)
        } finally {
            Settings.replaceSharedForTest(previousSettings)
        }
    }
}
