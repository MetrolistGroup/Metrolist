/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.metrolist.innertube.models.Album
import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.pages.AlbumPage
import com.metrolist.music.db.entities.AlbumEntity
import com.metrolist.music.db.entities.SongAlbumMap
import com.metrolist.music.db.entities.SongEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Album refreshes must not overwrite a cached tracklist with an empty or shorter,
 * potentially incomplete snapshot. An accepted snapshot makes the album's mappings
 * match it exactly, without touching song rows or other albums' mappings (issue #4461).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AlbumTracklistRefreshTest {
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
    fun `album refresh removes stale mappings for this album only`() =
        runBlocking {
            val dao = database.dao
            val album = AlbumEntity(id = ALBUM_ID, title = "Album", songCount = 3, duration = 300)
            val otherAlbum = AlbumEntity(id = OTHER_ALBUM_ID, title = "Other album", songCount = 1, duration = 100)
            dao.insert(album)
            dao.insert(otherAlbum)
            listOf(OPENING_ID, INTERLUDE_ID, FINALE_ID, REPLACEMENT_ID, STALE_ONLY_ID).forEach { songId ->
                dao.insert(
                    SongEntity(
                        id = songId,
                        title = songId,
                        duration = 100,
                        albumId = ALBUM_ID,
                        albumName = "Album",
                    ),
                )
            }
            // Saved song that names this album but was never mapped to it: refresh must keep it.
            dao.insert(
                SongEntity(
                    id = UNMAPPED_ID,
                    title = UNMAPPED_ID,
                    duration = 100,
                    albumId = ALBUM_ID,
                    albumName = "Album",
                ),
            )
            // This song belongs to another album and is not mapped to the album being refreshed.
            dao.insert(
                SongEntity(
                    id = OTHER_ONLY_ID,
                    title = OTHER_ONLY_ID,
                    duration = 100,
                    albumId = OTHER_ALBUM_ID,
                    albumName = "Other album",
                ),
            )
            // Partial tracklist cached while the release was still publishing:
            // a provisional interlude sits where the finale belongs.
            dao.insert(SongAlbumMap(songId = OPENING_ID, albumId = ALBUM_ID, index = 0))
            dao.insert(SongAlbumMap(songId = INTERLUDE_ID, albumId = ALBUM_ID, index = 1))
            dao.insert(SongAlbumMap(songId = FINALE_ID, albumId = ALBUM_ID, index = 2))
            dao.insert(SongAlbumMap(songId = STALE_ONLY_ID, albumId = ALBUM_ID, index = 3))
            // The same song legitimately belongs to another album as well.
            dao.insert(SongAlbumMap(songId = INTERLUDE_ID, albumId = OTHER_ALBUM_ID, index = 0))

            // The completed release swaps the interlude for a different track.
            dao.update(
                album = album,
                albumPage = albumPage(OPENING_ID, REPLACEMENT_ID, FINALE_ID),
                artists = emptyList(),
            )

            // Mappings now match the refreshed tracklist exactly, in order.
            assertEquals(
                listOf(OPENING_ID, REPLACEMENT_ID, FINALE_ID),
                dao.albumSongs(ALBUM_ID, limit = 10, offset = 0).map { it.id },
            )
            assertEquals(3, dao.albumWithSongs(ALBUM_ID).first()!!.album.songCount)

            // A later same-sized refresh still applies.
            dao.update(
                album = album,
                albumPage = albumPage(OPENING_ID, REPLACEMENT_ID, FINALE_ID, title = "Updated album"),
                artists = emptyList(),
            )
            assertEquals("Updated album", dao.albumWithSongs(ALBUM_ID).first()!!.album.title)

            // The stale song row and its mapping on the other album both survive.
            assertNotNull(dao.getSongByIdBlocking(INTERLUDE_ID))
            assertEquals(OTHER_ALBUM_ID, dao.getSongByIdBlocking(INTERLUDE_ID)?.song?.albumId)
            assertEquals("Other album", dao.getSongByIdBlocking(INTERLUDE_ID)?.song?.albumName)
            assertNull(dao.getSongByIdBlocking(STALE_ONLY_ID)?.song?.albumId)
            assertNull(dao.getSongByIdBlocking(STALE_ONLY_ID)?.song?.albumName)
            assertEquals(ALBUM_ID, dao.getSongByIdBlocking(UNMAPPED_ID)?.song?.albumId)
            assertEquals("Album", dao.getSongByIdBlocking(UNMAPPED_ID)?.song?.albumName)
            assertEquals(ALBUM_ID, dao.getSongByIdBlocking(OPENING_ID)?.song?.albumId)
            assertEquals(ALBUM_ID, dao.getSongByIdBlocking(FINALE_ID)?.song?.albumId)
            assertEquals(OTHER_ALBUM_ID, dao.getSongByIdBlocking(OTHER_ONLY_ID)?.song?.albumId)
            assertEquals(
                listOf(INTERLUDE_ID),
                dao.albumSongs(OTHER_ALBUM_ID, limit = 10, offset = 0).map { it.id },
            )
        }

    @Test
    fun `album refresh keeps shared songs mapped to both albums with per-album order`() =
        runBlocking {
            val dao = database.dao
            val album = AlbumEntity(id = ALBUM_ID, title = "Album", songCount = 2, duration = 200)
            val otherAlbum = AlbumEntity(id = OTHER_ALBUM_ID, title = "Other album", songCount = 2, duration = 200)
            dao.insert(album)
            dao.insert(otherAlbum)
            listOf(OPENING_ID, FINALE_ID).forEach { songId ->
                dao.insert(SongEntity(id = songId, title = songId, duration = 100))
            }
            dao.insert(SongAlbumMap(songId = OPENING_ID, albumId = ALBUM_ID, index = 0))
            dao.insert(SongAlbumMap(songId = FINALE_ID, albumId = ALBUM_ID, index = 1))
            dao.insert(SongAlbumMap(songId = OPENING_ID, albumId = OTHER_ALBUM_ID, index = 0))
            dao.insert(SongAlbumMap(songId = FINALE_ID, albumId = OTHER_ALBUM_ID, index = 1))

            dao.update(
                album = album,
                albumPage = albumPage(FINALE_ID, OPENING_ID),
                artists = emptyList(),
            )

            assertEquals(
                listOf(FINALE_ID, OPENING_ID),
                dao.albumSongs(ALBUM_ID, limit = 10, offset = 0).map { it.id },
            )
            // The other album keeps its opposite order for the same shared songs.
            assertEquals(
                listOf(OPENING_ID, FINALE_ID),
                dao.albumSongs(OTHER_ALBUM_ID, limit = 10, offset = 0).map { it.id },
            )
            assertNotNull(dao.getSongByIdBlocking(OPENING_ID))
            assertNotNull(dao.getSongByIdBlocking(FINALE_ID))
        }

    @Test
    fun `album refresh keeps mappings for songs still in the tracklist`() =
        runBlocking {
            val dao = database.dao
            val album = AlbumEntity(id = ALBUM_ID, title = "Album", songCount = 3, duration = 300)
            dao.insert(album)
            listOf(OPENING_ID, INTERLUDE_ID, FINALE_ID).forEach { songId ->
                dao.insert(SongEntity(id = songId, title = songId, duration = 100))
            }
            dao.insert(SongAlbumMap(songId = OPENING_ID, albumId = ALBUM_ID, index = 0))
            dao.insert(SongAlbumMap(songId = INTERLUDE_ID, albumId = ALBUM_ID, index = 1))
            dao.insert(SongAlbumMap(songId = FINALE_ID, albumId = ALBUM_ID, index = 2))

            // Same songs, corrected order.
            dao.update(
                album = album,
                albumPage = albumPage(FINALE_ID, OPENING_ID, INTERLUDE_ID),
                artists = emptyList(),
            )

            assertEquals(
                listOf(FINALE_ID, OPENING_ID, INTERLUDE_ID),
                dao.albumSongs(ALBUM_ID, limit = 10, offset = 0).map { it.id },
            )
        }

    @Test
    fun `empty album refresh keeps the cached tracklist`() =
        runBlocking {
            val dao = database.dao
            val album = AlbumEntity(id = ALBUM_ID, title = "Album", songCount = 2, duration = 200)
            dao.insert(album)
            dao.insert(SongEntity(id = OPENING_ID, title = OPENING_ID, duration = 100))
            dao.insert(SongEntity(id = FINALE_ID, title = FINALE_ID, duration = 100))
            dao.insert(SongAlbumMap(songId = OPENING_ID, albumId = ALBUM_ID, index = 0))
            dao.insert(SongAlbumMap(songId = FINALE_ID, albumId = ALBUM_ID, index = 1))

            dao.update(
                album = album,
                albumPage = albumPage(),
                artists = emptyList(),
            )

            assertEquals(
                listOf(OPENING_ID, FINALE_ID),
                dao.albumSongs(ALBUM_ID, limit = 10, offset = 0).map { it.id },
            )
        }

    private fun albumPage(vararg songIds: String, title: String = "Album") =
        AlbumPage(
            album =
                AlbumItem(
                    browseId = ALBUM_ID,
                    playlistId = "playlist",
                    title = title,
                    artists = null,
                    thumbnail = "thumbnail",
                ),
            songs =
                songIds.map { songId ->
                    SongItem(
                        id = songId,
                        title = songId,
                        artists = emptyList(),
                        album = Album(name = "Album", id = ALBUM_ID),
                        duration = 100,
                        thumbnail = "thumbnail",
                    )
                },
            otherVersions = emptyList(),
        )

    private companion object {
        const val ALBUM_ID = "album"
        const val OTHER_ALBUM_ID = "other-album"
        const val OTHER_ONLY_ID = "other-only"
        const val STALE_ONLY_ID = "stale-only"
        const val UNMAPPED_ID = "unmapped"
        const val OPENING_ID = "opening"
        const val INTERLUDE_ID = "interlude"
        const val REPLACEMENT_ID = "replacement"
        const val FINALE_ID = "finale"
    }
}
