package com.metrolist.desktop.ui.screens

import com.metrolist.desktop.history.AlbumStat
import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.model.ArtistTiny
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StatisticsMatchingTest {
    @Test
    fun artistNameKeepsAllColons() {
        assertEquals("A:B:C", statisticsArtistName("artist:51:A:B:C"))
    }

    @Test
    fun albumSelectionRequiresAnUnambiguousTitleAndArtistMatch() {
        val stat = AlbumStat("Same title", "Artist A", null, 4)
        val correct = AlbumItem("correct", "Same title", null, listOf(ArtistTiny(null, "Artist A")))
        val wrong = correct.copy(id = "wrong", artists = listOf(ArtistTiny(null, "Artist B")))
        assertEquals(correct, listOf(wrong, correct).matchingStatisticsAlbum(stat))
        assertNull(listOf(wrong).matchingStatisticsAlbum(stat))
        assertNull(listOf(correct, correct.copy(id = "other-edition")).matchingStatisticsAlbum(stat))
        assertEquals(correct, listOf(correct, correct).matchingStatisticsAlbum(stat))
    }
}
