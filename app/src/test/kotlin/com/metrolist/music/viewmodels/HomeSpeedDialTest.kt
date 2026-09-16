package com.metrolist.music.viewmodels

import com.metrolist.innertube.models.SongItem
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeSpeedDialTest {
    @Test
    fun `home recommendations fill sparse speed dial without replacing pinned items`() {
        val pinned = song("pinned")
        val home = song("home")

        val result =
            buildSpeedDialItems(
                pinned = listOf(pinned),
                keepListening = emptyList(),
                quickPicks = emptyList(),
                home = listOf(pinned.copy(title = "duplicate"), home),
            )

        assertEquals(listOf(pinned, home), result)
    }

    private fun song(id: String) =
        SongItem(
            id = id,
            title = id,
            artists = emptyList(),
            thumbnail = "",
        )
}
