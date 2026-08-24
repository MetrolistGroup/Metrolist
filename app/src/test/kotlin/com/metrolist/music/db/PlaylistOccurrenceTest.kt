/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.metrolist.music.db.entities.PlaylistEntity
import com.metrolist.music.db.entities.SongEntity
import java.time.LocalDateTime
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A playlist may hold the same song twice, which the duplicate warning's "add anyway" leaves
 * behind on purpose. A setVideoId names one of those two items on YouTube rather than the song,
 * so each row has to be able to carry its own.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PlaylistOccurrenceTest {
    private lateinit var database: InternalDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, InternalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        database.runInTransaction {
            database.dao.insert(
                PlaylistEntity(
                    id = PLAYLIST_ID,
                    name = "Playlist",
                    bookmarkedAt = LocalDateTime.of(2026, 1, 1, 0, 0),
                ),
            )
            database.dao.insert(SongEntity(id = SONG_ID, title = "Song"))
        }
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun playlist() = database.dao.playlistBlocking(PLAYLIST_ID)!!

    @Test
    fun `adding a song twice gives each row its own id`() {
        val first = database.dao.addSongsToPlaylist(playlist(), listOf(SONG_ID to null))
        val second = database.dao.addSongsToPlaylist(playlist(), listOf(SONG_ID to null))

        assertEquals(1, first.size)
        assertEquals(1, second.size)
        assertEquals(listOf(SONG_ID), first.map { it.first })
        assertEquals(2, listOf(first.single().second, second.single().second).distinct().size)
    }

    @Test
    fun `confirming one copy leaves the other alone`() {
        val first = database.dao.addSongsToPlaylist(playlist(), listOf(SONG_ID to null)).single()
        val second = database.dao.addSongsToPlaylist(playlist(), listOf(SONG_ID to null)).single()

        database.dao.updatePlaylistSongSetVideoId(second.second, "svi_second")

        val confirmed = database.dao.playlistSongSetVideoIds(PLAYLIST_ID, SONG_ID)
        assertEquals(listOf("svi_second"), confirmed)
        val maps = database.dao.playlistSongsBlocking(PLAYLIST_ID).map { it.map }
        assertEquals("svi_second", maps.single { it.id.toLong() == second.second }.setVideoId)
        assertEquals(null, maps.single { it.id.toLong() == first.second }.setVideoId)
    }

    @Test
    fun `both copies can be confirmed separately`() {
        val first = database.dao.addSongsToPlaylist(playlist(), listOf(SONG_ID to null)).single()
        val second = database.dao.addSongsToPlaylist(playlist(), listOf(SONG_ID to null)).single()

        database.dao.updatePlaylistSongSetVideoId(first.second, "svi_first")
        database.dao.updatePlaylistSongSetVideoId(second.second, "svi_second")

        assertEquals(
            listOf("svi_first", "svi_second"),
            database.dao.playlistSongSetVideoIds(PLAYLIST_ID, SONG_ID).sorted(),
        )
    }

    private companion object {
        const val PLAYLIST_ID = "playlist"
        const val SONG_ID = "song"
    }
}
