package com.metrolist.shared.ui.components

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CollectionHeaderTest {
    @Test
    fun mediumArtworkGrowsWithWidthAndReservesMostOfTheRowForText() {
        val narrow = mediumCollectionArtworkSize(600.dp, 800.dp)
        val wide = mediumCollectionArtworkSize(959.dp, 800.dp)

        assertTrue(narrow > 176.dp)
        assertTrue(wide > narrow)
        assertEquals(256.dp, wide)
        val textWidth = 600.dp - CollectionHeaderHorizontalPadding * 2 - 24.dp - narrow
        assertTrue(textWidth > narrow)
    }

    @Test
    fun shortViewportsLimitArtworkToLeaveRoomForTheSongList() {
        for (height in listOf(300.dp, 400.dp, 600.dp)) {
            assertEquals(height * 0.4f, mediumCollectionArtworkSize(959.dp, height))
        }
        assertEquals(0.dp, mediumCollectionArtworkSize(959.dp, (-16).dp))
    }
}
