package com.metrolist.music.ui.menu

import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.pages.PlaylistPage
import com.metrolist.music.db.entities.Playlist
import com.metrolist.music.db.entities.PlaylistEntity
import com.metrolist.music.db.entities.PlaylistSongMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistRemovalCandidatesTest {
    @Test
    fun `initial local removal targets only the requested copy`() {
        val rows = listOf(
            PlaylistSongMap(id = 1, playlistId = "local", songId = "song"),
            PlaylistSongMap(id = 2, playlistId = "local", songId = "song"),
            PlaylistSongMap(id = 3, playlistId = "other", songId = "song"),
        )
        assertEquals(listOf(rows[0]), localPlaylistRemovalOccurrences(rows, "local", rowId = 1))
    }

    @Test
    fun `choosing a playlist after the initial copy disappeared offers the remaining copies`() {
        val rows = listOf(
            PlaylistSongMap(id = 2, playlistId = "local", songId = "song"),
            PlaylistSongMap(id = 3, playlistId = "local", songId = "song"),
            PlaylistSongMap(id = 4, playlistId = "other", songId = "song"),
        )
        assertTrue(localPlaylistRemovalOccurrences(rows, "local", rowId = 1).isEmpty())
        assertEquals(rows.take(2), localPlaylistRemovalOccurrences(rows, "local"))
    }

    private fun playlist(id: String, remote: Boolean = false, editable: Boolean = true) = Playlist(
        PlaylistEntity(id = id, name = id, browseId = id.takeIf { remote }, isEditable = editable),
        songCount = 1, songThumbnails = emptyList(),
    )

    private fun page(vararg ids: String, editable: Boolean = true) = PlaylistPage(
        playlist = PlaylistItem(
            id = "remote", title = "remote", author = null, songCountText = "${ids.size} songs",
            thumbnail = null, playEndpoint = null, shuffleEndpoint = null, radioEndpoint = null,
            isEditable = editable,
        ),
        songs = ids.map { SongItem(id = it, title = it, artists = emptyList(), thumbnail = "") },
        songsContinuation = null, continuation = null,
    )

    @Test
    fun `a single confirmed playlist is automatically selected`() {
        val onlyPlaylist = playlist("only")
        assertEquals(onlyPlaylist, PlaylistRemovalCandidates(listOf(onlyPlaylist), false).automaticPlaylist())
    }

    @Test
    fun `multiple matching playlists require selection`() {
        assertNull(PlaylistRemovalCandidates(listOf(playlist("first"), playlist("second")), false).automaticPlaylist())
    }

    @Test
    fun `a single match is not automatically selected when another lookup failed`() {
        assertNull(PlaylistRemovalCandidates(listOf(playlist("only")), true).automaticPlaylist())
    }

    @Test
    fun `no matching playlists never automatically selects a target`() {
        assertNull(PlaylistRemovalCandidates(emptyList(), false).automaticPlaylist())
    }

    @Test
    fun `a missing initial remote target never automatically selects another playlist`() {
        val candidates = PlaylistRemovalCandidates(listOf(playlist("other", remote = true)), false)
        assertNull(candidates.automaticPlaylist(initialBrowseId = "original"))
        assertEquals("other", candidates.playlists.single().id)
    }

    @Test
    fun `a missing initial local row requires explicit selection of the remaining copy`() {
        val candidates = PlaylistRemovalCandidates(listOf(playlist("local")), false)
        assertNull(candidates.automaticPlaylist(initialLocalPlaylistId = "local"))
    }

    @Test
    fun `only local playlists containing this song are offered`() = runBlocking {
        val result = findPlaylistRemovalCandidates(
            listOf(playlist("present"), playlist("absent"), playlist("read-only", editable = false)),
            "song", listOf(
                PlaylistSongMap(playlistId = "present", songId = "song", position = 0),
                PlaylistSongMap(playlistId = "absent", songId = "other", position = 0),
                PlaylistSongMap(playlistId = "read-only", songId = "song", position = 0),
            ),
        ) { error("Local playlists should not make network requests") }
        assertEquals(listOf("present"), result.playlists.map { it.id })
        assertFalse(result.hasFailures)
    }

    @Test
    fun `remote contents override stale local membership and include all matching playlists`() = runBlocking {
        val result = findPlaylistRemovalCandidates(
            listOf(playlist("removed", true), playlist("added-A", true), playlist("added-B", true)),
            "song", listOf(PlaylistSongMap(playlistId = "removed", songId = "song", position = 0, setVideoId = "old-copy")),
        ) { id -> if (id == "removed") page("other") else page("song", "song") }
        assertEquals(listOf("added-A", "added-B"), result.playlists.map { it.id })
        assertFalse(result.hasFailures)
    }

    @Test
    fun `pending additions are offered only after a successful editable remote check`() = runBlocking {
        val rows = listOf("empty", "other-songs", "failed", "read-only").map {
            PlaylistSongMap(playlistId = it, songId = "song")
        }
        val result = findPlaylistRemovalCandidates(
            rows.map { playlist(it.playlistId, remote = true) }, "song", rows,
        ) { id ->
            when (id) {
                "failed" -> error("offline")
                "read-only" -> page(editable = false)
                "other-songs" -> page("other")
                else -> page()
            }
        }
        assertEquals(listOf("empty", "other-songs"), result.playlists.map { it.id })
        assertTrue(result.hasFailures)
    }

    private fun remoteSong(setVideoId: String?) = SongItem(
        id = "song", title = "Song", artists = emptyList(), thumbnail = "", setVideoId = setVideoId,
    )

    @Test
    fun `remote chooser preserves row identity when a pending copy can be backfilled`() {
        val rows = listOf(
            PlaylistSongMap(id = 1, playlistId = "P", songId = "song", setVideoId = "first"),
            PlaylistSongMap(id = 2, playlistId = "P", songId = "song", position = 1),
        )
        val matches = remotePlaylistRemovalOccurrences(rows, "P", "song", listOf(remoteSong("first"), remoteSong("second")))
        assertEquals(listOf(1, 2), matches.map { it.id })
        assertEquals(listOf("first", "second"), matches.map { it.setVideoId })
    }

    @Test
    fun `remote chooser offers pending local copies without duplicating remote copies`() {
        val rows = listOf(
            PlaylistSongMap(id = 1, playlistId = "P", songId = "song", setVideoId = "first"),
            PlaylistSongMap(id = 2, playlistId = "P", songId = "song", position = 1),
            PlaylistSongMap(id = 3, playlistId = "other", songId = "song"),
        )
        assertEquals(rows.take(2), remotePlaylistRemovalOccurrences(rows, "P", "song", listOf(remoteSong("first"))))
        assertEquals(rows.take(2).drop(1), remotePlaylistRemovalOccurrences(rows, "P", "song", emptyList()))
    }

    @Test
    fun `ambiguous pending copies cannot be offered as local only removals`() {
        val rows = listOf(
            PlaylistSongMap(id = 1, playlistId = "P", songId = "song"),
            PlaylistSongMap(id = 2, playlistId = "P", songId = "song", position = 1),
        )
        val matches = remotePlaylistRemovalOccurrences(rows, "P", "song", listOf(remoteSong("unclaimed")))
        assertEquals(1, matches.size)
        assertEquals(0, matches.single().id)
        assertEquals("unclaimed", matches.single().setVideoId)
    }

    @Test(expected = IllegalStateException::class)
    fun `missing remote occurrence IDs never expose local only removal`() {
        remotePlaylistRemovalOccurrences(
            listOf(PlaylistSongMap(id = 1, playlistId = "P", songId = "song")), "P", "song", listOf(remoteSong(null)),
        )
    }

    @Test
    fun `failed lookups are reported without offering unverified playlists`() = runBlocking {
        val result = findPlaylistRemovalCandidates(
            listOf(playlist("failed", true), playlist("present", true)), "song", emptyList(),
        ) { id -> if (id == "failed") error("offline") else page("song") }
        assertEquals(listOf("present"), result.playlists.map { it.id })
        assertTrue(result.hasFailures)
    }

    @Test
    fun `empty and remotely read only playlists are excluded`() = runBlocking {
        val result = findPlaylistRemovalCandidates(
            listOf(playlist("empty", true), playlist("read-only", true)), "song", emptyList(),
        ) { id -> if (id == "empty") page() else page("song", editable = false) }
        assertTrue(result.playlists.isEmpty())
        assertFalse(result.hasFailures)
    }

    @Test
    fun `incomplete pages are reported as failures`() = runBlocking {
        val result = findPlaylistRemovalCandidates(
            listOf(playlist("missing", true), playlist("partial", true)), "song", emptyList(),
        ) { id ->
            if (id == "missing") page().let { it.copy(playlist = it.playlist.copy(songCountText = "5 songs")) }
            else page("song").copy(songsContinuation = "next-page")
        }
        assertTrue(result.playlists.isEmpty())
        assertTrue(result.hasFailures)
    }

    @Test
    fun `remote requests are bounded and result order is preserved`() = runBlocking {
        var active = 0
        var maximum = 0
        val playlists = (1..12).map { playlist("remote-$it", true) }
        val result = findPlaylistRemovalCandidates(playlists, "song", emptyList()) {
            active++
            maximum = maxOf(maximum, active)
            try {
                delay(10)
                page("song")
            } finally {
                active--
            }
        }
        assertTrue(maximum in 2..4)
        assertEquals(playlists, result.playlists)
    }

    @Test(expected = CancellationException::class)
    fun `closing the dialog cancels lookups`() = runBlocking {
        findPlaylistRemovalCandidates(listOf(playlist("remote", true)), "song", emptyList()) {
            throw CancellationException("Dialog closed")
        }
        Unit
    }
}
