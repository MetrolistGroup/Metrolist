package com.metrolist.music.viewmodels

import com.metrolist.innertube.models.SongItem
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeSpeedDialTest {
    @Test
    fun `recent and frequent history fill speed dial without copying quick picks or home`() {
        val pinned = song("pinned")
        val recent = song("recent")
        val frequent = song("frequent")
        val resume = song("resume")
        val favorite = song("favorite")

        val result = buildSpeedDialItems(
            pinned = listOf(pinned),
            recent = listOf(recent, pinned.copy(title = "duplicate")),
            frequent = listOf(frequent, recent.copy(title = "duplicate recent")),
            resume = listOf(resume),
            favorites = listOf(favorite, frequent.copy(title = "duplicate frequent")),
        )

        assertEquals(listOf("pinned", "recent", "frequent", "resume", "favorite"), result.map { it.id })
    }

    @Test
    fun `pinned items always stay first and total items are capped at 27`() {
        val pinned = listOf(song("pinned"))
        val recent = (1..12).map { song("recent-$it") }
        val frequent = (1..12).map { song("frequent-$it") }
        val resume = (1..6).map { song("resume-$it") }
        val favorites = (1..9).map { song("favorite-$it") }

        val result = buildSpeedDialItems(pinned, recent, frequent, resume, favorites)

        assertEquals("pinned", result.first().id)
        assertEquals(27, result.size)
        assertEquals(result.size, result.distinctBy { it.id }.size)
    }

    @Test
    fun `empty history still preserves pinned items`() {
        val pinned = song("pinned")

        val result = buildSpeedDialItems(
            pinned = listOf(pinned),
            recent = emptyList(),
            frequent = emptyList(),
            resume = emptyList(),
            favorites = emptyList(),
        )

        assertEquals(listOf("pinned"), result.map { it.id })
    }


    @Test
    fun `live session plays are merged ahead of persisted history without duplicates`() {
        val session = listOf(song("current"), song("older-session"))
        val persisted = listOf(song("persisted"), song("current").copy(title = "duplicate"))

        val result = (session + persisted).distinctBy { it.id }

        assertEquals(listOf("current", "older-session", "persisted"), result.map { it.id })
    }

    private fun song(id: String) =
        SongItem(
            id = id,
            title = id,
            artists = emptyList(),
            thumbnail = "",
        )
}
