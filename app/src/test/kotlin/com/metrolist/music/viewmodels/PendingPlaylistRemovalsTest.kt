package com.metrolist.music.viewmodels

import com.metrolist.innertube.models.SongItem
import org.junit.Assert.assertEquals
import org.junit.Test

class PendingPlaylistRemovalsTest {
    private fun song(occurrence: String) = SongItem(
        id = "same-song", title = "Song", artists = emptyList(), thumbnail = "", setVideoId = occurrence,
    )

    @Test
    fun `new browser addition and existing duplicate remain visible`() {
        val pending = PendingPlaylistRemovals()
        pending.record("same-song", "deleted")
        val songs = listOf(song("deleted"), song("duplicate"), song("new-browser-addition"))
        assertEquals(listOf("duplicate", "new-browser-addition"), pending.filter(songs).map { it.setVideoId })
    }

    @Test
    fun `confirmed absence releases the temporary exclusion`() {
        val pending = PendingPlaylistRemovals()
        pending.record("same-song", "deleted")
        pending.observePage(emptyList(), complete = true, reset = true)
        assertEquals(listOf(song("deleted")), pending.filter(listOf(song("deleted"))))
    }

    @Test
    fun `partial page cannot confirm absence of a later occurrence`() {
        val pending = PendingPlaylistRemovals()
        pending.record("same-song", "deleted")
        pending.observePage(listOf(song("first-page")), complete = false, reset = true)
        pending.observePage(listOf(song("deleted")), complete = true)
        assertEquals(emptyList<SongItem>(), pending.filter(listOf(song("deleted"))))
    }

    @Test
    fun `subsequent complete refresh releases exclusions confirmed absent`() {
        val pending = PendingPlaylistRemovals()
        pending.record("same-song", "deleted")
        pending.observePage(listOf(song("deleted")), complete = true, reset = true)
        pending.observePage(listOf(song("first-page")), complete = false, reset = true)
        pending.observePage(listOf(song("last-page")), complete = true)
        assertEquals(listOf(song("deleted")), pending.filter(listOf(song("deleted"))))
    }

    @Test
    fun `exclusion belongs only to the affected playlist`() {
        val original = PendingPlaylistRemovals()
        val other = PendingPlaylistRemovals()
        original.record("same-song", "deleted")
        assertEquals(listOf(song("deleted")), other.filter(listOf(song("deleted"))))
    }
}
