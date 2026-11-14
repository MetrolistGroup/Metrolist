package com.metrolist.shared.settings

import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SavedVolumeTest {
    @Test
    fun volumeCountsAsSavedOnlyAfterItIsWritten() {
        val store = MapSettings()
        val settings = Settings(store)
        assertFalse(settings.hasSavedVolume)

        settings.volume = 0.25f

        assertTrue(settings.hasSavedVolume)
        assertEquals(0.25f, Settings(store).volume)
    }
}
