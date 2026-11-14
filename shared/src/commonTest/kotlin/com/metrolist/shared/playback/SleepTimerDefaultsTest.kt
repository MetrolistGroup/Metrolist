package com.metrolist.shared.playback

import com.metrolist.shared.constants.SleepTimerDefaultMinutesKey
import com.metrolist.shared.settings.Settings
import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class SleepTimerDefaultsTest {
    @Test
    fun durationEntryAcceptsOnlyPositiveDurationsWithinTheHourAndMinuteBounds() {
        assertEquals(60_000L, sleepTimerDurationMs("00", "01"))
        assertEquals(900_000L, sleepTimerDurationMs("", "15"))
        assertEquals(3_600_000L, sleepTimerDurationMs("1", ""))
        assertEquals(86_340_000L, sleepTimerDurationMs("23", "59"))
        listOf(
            "00" to "00",
            "" to "",
            "24" to "00",
            "0" to "60",
            "-1" to "5",
            "a" to "5",
            "1" to "1.5",
            "001" to "0",
        ).forEach { (hour, minute) ->
            assertNull(sleepTimerDurationMs(hour, minute), "$hour:$minute")
        }
    }

    @Test
    fun savingDefaultsPersistsDurationAndBothOptionsWithoutStartingATimer() {
        val backing = MapSettings()
        val settings = Settings(backing)
        assertEquals(SleepTimerDefaults(), settings.sleepTimerDefaults)
        val saved = SleepTimerDefaults(95, finishCurrentSong = true, fadeOut = true)
        settings.sleepTimerDefaults = saved
        assertEquals(saved, Settings(backing).sleepTimerDefaults)
        settings.sleepTimerDefaults = saved.copy(finishCurrentSong = false, fadeOut = false)
        assertEquals(saved.copy(finishCurrentSong = false, fadeOut = false), Settings(backing).sleepTimerDefaults)
    }

    @Test
    fun invalidSavedDurationsUseTheInitialDefaultAndInvalidWritesKeepTheSavedValue() {
        listOf(-1, 0, 1440).forEach { invalid ->
            val backing = MapSettings().apply { putInt(SleepTimerDefaultMinutesKey, invalid) }
            val settings = Settings(backing)
            assertEquals(SleepTimerDefaults(), settings.sleepTimerDefaults)
            settings.sleepTimerDefaults = SleepTimerDefaults(1439, fadeOut = true)
            assertFailsWith<IllegalArgumentException> { settings.sleepTimerDefaults = SleepTimerDefaults(invalid) }
            assertEquals(SleepTimerDefaults(1439, fadeOut = true), Settings(backing).sleepTimerDefaults)
        }
    }
}
