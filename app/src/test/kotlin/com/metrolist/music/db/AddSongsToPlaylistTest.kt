/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.db

import android.content.Context
import android.os.Looper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.metrolist.music.db.entities.Playlist
import com.metrolist.music.db.entities.PlaylistEntity
import com.metrolist.music.db.entities.PlaylistSongMap
import com.metrolist.music.db.entities.SongEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AddSongsToPlaylistTest {
    private lateinit var database: InternalDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, InternalDatabase::class.java).build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `bulk add is safe when called from main thread`() =
        runBlocking {
            val playlist =
                PlaylistEntity(
                    id = "playlist",
                    name = "Playlist",
                    bookmarkedAt = LocalDateTime.of(2026, 1, 1, 0, 0),
                )
            withContext(Dispatchers.IO) {
                database.dao.insert(playlist)
                database.dao.insert(SongEntity(id = "song", title = "Song"))
            }

            assertTrue(Looper.getMainLooper().isCurrentThread)
            database.dao.addSongsToPlaylist(
                Playlist(playlist, 0, emptyList()),
                listOf("song" to null),
            )

            assertEquals(listOf("song"), database.dao.playlistSongIds("playlist"))
        }

    @Test
    fun `append inserts after sparse playlist positions`() =
        runBlocking {
            val playlist =
                PlaylistEntity(
                    id = "playlist",
                    name = "Playlist",
                    bookmarkedAt = LocalDateTime.of(2026, 1, 1, 0, 0),
                )
            withContext(Dispatchers.IO) {
                database.dao.insert(playlist)
                listOf("first", "last", "new").forEach {
                    database.dao.insert(SongEntity(id = it, title = it))
                }
                database.dao.insert(PlaylistSongMap(playlistId = playlist.id, songId = "first", position = 0))
                database.dao.insert(PlaylistSongMap(playlistId = playlist.id, songId = "last", position = 5))
            }

            database.dao.addSongsToPlaylist(
                Playlist(playlist, 2, emptyList()),
                listOf("new" to null),
            )

            assertEquals(listOf("first", "last", "new"), database.dao.playlistSongIds(playlist.id))
        }

    @Test
    fun `bulk add participates in an outer suspending transaction`() =
        runBlocking {
            val playlist =
                PlaylistEntity(
                    id = "playlist",
                    name = "Playlist",
                    bookmarkedAt = LocalDateTime.of(2026, 1, 1, 0, 0),
                )
            withContext(Dispatchers.IO) {
                database.dao.insert(playlist)
                database.dao.insert(SongEntity(id = "song", title = "Song"))
            }

            val result =
                runCatching {
                    MusicDatabase(database).withTransaction {
                        addSongsToPlaylist(
                            Playlist(playlist, 0, emptyList()),
                            listOf("song" to null),
                        )
                        error("roll back")
                    }
                }

            assertTrue(result.isFailure)
            assertEquals(emptyList<String>(), database.dao.playlistSongIds("playlist"))
        }
}
