/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.metrolist.music.db.entities.PlaylistEntity
import com.metrolist.music.db.entities.PlaylistSongMap
import com.metrolist.music.db.entities.SongEntity
import com.metrolist.music.ui.menu.MAX_PLAYLIST_DUPLICATES_BATCH_SIZE
import com.metrolist.music.ui.menu.playlistDuplicatesBatched
import kotlinx.coroutines.runBlocking
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
class PlaylistDuplicatesBatchedTest {
    private lateinit var database: InternalDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database =
            Room
                .inMemoryDatabaseBuilder(context, InternalDatabase::class.java)
                .allowMainThreadQueries()
                .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `duplicate lookup stays correct across variable limit batches`() =
        runBlocking {
            assertTrue(MAX_PLAYLIST_DUPLICATES_BATCH_SIZE + 1 <= 999)

            val libraryDate = LocalDateTime.of(2026, 1, 1, 0, 0)
            database.runInTransaction {
                database.dao.insert(PlaylistEntity(id = "full", name = "Full", bookmarkedAt = libraryDate))
                database.dao.insert(PlaylistEntity(id = "partial", name = "Partial", bookmarkedAt = libraryDate))
                repeat(1_200) { index ->
                    val songId = "song-$index"
                    database.dao.insert(SongEntity(id = songId, title = "Song $index"))
                    database.dao.insert(
                        PlaylistSongMap(playlistId = "full", songId = songId, position = index),
                    )
                    if (index % 2 == 0) {
                        database.dao.insert(
                            PlaylistSongMap(playlistId = "partial", songId = songId, position = index / 2),
                        )
                    }
                }
            }

            val ids = List(1_200) { index -> "song-$index" }

            val fullDuplicates = database.dao.playlistDuplicatesBatched("full", ids)
            assertEquals(1_200, fullDuplicates.size)
            assertEquals(ids.toSet(), fullDuplicates.toSet())

            val partialDuplicates = database.dao.playlistDuplicatesBatched("partial", ids)
            assertEquals(600, partialDuplicates.size)
            assertEquals(ids.filterIndexed { index, _ -> index % 2 == 0 }.toSet(), partialDuplicates.toSet())
            assertEquals(emptyList<String>(), database.dao.playlistDuplicatesBatched("full", emptyList()))
        }
}
