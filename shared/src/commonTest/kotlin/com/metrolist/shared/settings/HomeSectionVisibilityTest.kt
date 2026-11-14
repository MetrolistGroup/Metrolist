package com.metrolist.shared.settings

import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HomeSectionVisibilityTest {
    @Test
    fun delimiterTitlesRoundTripAndMigrateToStableKeys() {
        val settings = Settings(MapSettings())
        settings.homeSectionVisibility = "Made for you: mixes, favourites:false,Other:true"
        assertFalse(settings.shouldShowHomeSection("section:recommendations:mixes", "Made for you: mixes, favourites"))
        assertFalse(settings.shouldShowHomeSection("section:recommendations:mixes", "A translated title"))
        assertTrue(settings.shouldShowHomeSection("Other"))
        assertFalse(settings.shouldShowHomeSection("another-stable-key", "Made for you: mixes, favourites"))
        settings.toggleHomeSectionVisibility("Title: commas, quotes \" and slashes /", false)
        assertFalse(settings.shouldShowHomeSection("Title: commas, quotes \" and slashes /"))
        assertEquals(5, settings.getHomeSectionVisibilityMap().size)
    }
}
