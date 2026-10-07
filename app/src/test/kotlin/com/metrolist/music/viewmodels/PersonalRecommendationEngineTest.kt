package com.metrolist.music.viewmodels

import com.metrolist.innertube.models.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalRecommendationEngineTest {
    private fun song(id: String): SongItem =
        SongItem(
            id = id,
            title = id,
            artists = emptyList(),
            thumbnail = "",
        )

    @Test
    fun quickPicks_isDeterministicForTheSameLoadSeed() {
        val familiar = (1..6).map { song("f$it") }
        val related = (1..6).map { song("r$it") }
        val liked = (1..4).map { song("l$it") }
        val discovery = (1..4).map { song("d$it") }

        val first =
            PersonalQuickPicksEngine.build(
                familiar = familiar,
                related = related,
                liked = liked,
                discovery = discovery,
                seed = 42L,
                limit = 20,
            )
        val second =
            PersonalQuickPicksEngine.build(
                familiar = familiar,
                related = related,
                liked = liked,
                discovery = discovery,
                seed = 42L,
                limit = 20,
            )

        assertEquals(first.map { it.id }, second.map { it.id })
        assertEquals(20, first.size)
    }

    @Test
    fun quickPicks_changesWhenTheLoadSeedChanges() {
        val candidates = (1..8).map { song("s$it") }

        val first =
            PersonalQuickPicksEngine.build(
                familiar = candidates,
                related = candidates,
                liked = candidates,
                discovery = candidates,
                seed = 100L,
                limit = 8,
            )
        val second =
            PersonalQuickPicksEngine.build(
                familiar = candidates,
                related = candidates,
                liked = candidates,
                discovery = candidates,
                seed = 200L,
                limit = 8,
            )

        assertNotEquals(first.map { it.id }, second.map { it.id })
    }

    @Test
    fun speedDial_keepsPinnedItemsFirst_andDeduplicates() {
        val pinned = listOf(song("p1"), song("p2"))
        val mostPlayed = listOf(song("p1"), song("m1"))
        val liked = listOf(song("m1"), song("l1"))
        val playlists = listOf(song("pl1"))
        val recent = listOf(song("r1"))
        val resume = listOf(song("l1"), song("x1"))

        val result =
            buildPersonalSpeedDialItems(
                pinned = pinned,
                mostPlayed = mostPlayed,
                liked = liked,
                savedPlaylists = playlists,
                recent = recent,
                resume = resume,
            )

        assertEquals(listOf("p1", "p2", "m1", "l1", "pl1", "r1", "x1"), result.map { it.id })
        assertEquals(result.map { it.id }.distinct(), result.map { it.id })
        assertTrue(result.take(2).map { it.id } == listOf("p1", "p2"))
    }
}
