package com.metrolist.shared.settings

import com.metrolist.shared.youtube.playback.PlaybackEventKind
import com.metrolist.shared.youtube.playback.PlaybackEventLevel
import com.metrolist.shared.youtube.playback.PlaybackEventLog
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class AndroidSettingsFailureLogTest {
    @Before
    @After
    fun clearEvents() {
        PlaybackEventLog.clear()
    }

    @Test
    fun capturesSettingsFailuresWithoutExportingTheThrowableMessage() {
        logSettingsFailure("Could not persist preferences", IllegalStateException("private-setting-value"))

        val event = PlaybackEventLog.events.value.single()
        assertEquals(PlaybackEventLevel.ERROR, event.level)
        assertEquals(PlaybackEventKind.OTHER, event.kind)
        assertTrue(event.message.contains("Could not persist preferences"))
        assertTrue(event.message.contains("IllegalStateException"))
        assertFalse(event.message.contains("private-setting-value"))
    }
}
