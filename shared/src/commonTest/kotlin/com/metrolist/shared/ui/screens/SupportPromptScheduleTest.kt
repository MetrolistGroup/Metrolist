package com.metrolist.shared.ui.screens

import com.metrolist.shared.settings.Settings
import com.russhwolf.settings.MapSettings
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SupportPromptScheduleTest {
    @Test
    fun promptAppearsAfterRandomLaunchCountAndStopsWhenDismissed() {
        val settings = Settings(MapSettings())
        val random = Random(1)
        val launchesUntilPrompt = (1..21).first { SupportPromptSchedule.isDue(settings, random) }
        assertTrue(launchesUntilPrompt in 8..20)
        assertEquals(0, settings.supportPromptLaunches)
        assertTrue(settings.supportPromptLaunchThreshold in 8..20)

        settings.supportPromptDismissed = true
        assertFalse((1..40).any { SupportPromptSchedule.isDue(settings, random) })
    }
}
