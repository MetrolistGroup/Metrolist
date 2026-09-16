/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.metrolist.music.db.entities.AlbumArtistMap
import com.metrolist.music.db.entities.AlbumEntity
import com.metrolist.music.db.entities.ArtistEntity
import com.metrolist.music.db.entities.SongAlbumMap
import com.metrolist.music.db.entities.SongArtistMap
import com.metrolist.music.db.entities.SongEntity
import java.time.LocalDateTime
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
 * The cached artist page is worth kilobytes per artist, and the relations that pull artists in
 * bulk build one [ArtistEntity] per pairing. Reading a library of songs used to mean holding one
 * copy of the page per song, so these lock down that the bulk reads leave it on disk while the
 * artist screen still gets it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ArtistPageCacheTest {
    private lateinit var database: InternalDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, InternalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        database.runInTransaction {
            database.dao.insert(
                ArtistEntity(
                    id = ARTIST_ID,
                    name = "Artist",
                    thumbnailUrl = "https://example.invalid/artist.jpg",
                    channelId = "UCchannel",
                    lastUpdateTime = UPDATED_AT,
                    bookmarkedAt = BOOKMARKED_AT,
                    isPodcastChannel = true,
                    cachedPageJson = CACHED_PAGE,
                ),
            )
            database.dao.insert(AlbumEntity(id = ALBUM_ID, title = "Album", songCount = 1, duration = 0))
            database.dao.insert(SongEntity(id = SONG_ID, title = "Song", albumId = ALBUM_ID))
            database.dao.insert(SongArtistMap(songId = SONG_ID, artistId = ARTIST_ID, position = 0))
            database.dao.insert(SongAlbumMap(songId = SONG_ID, albumId = ALBUM_ID, index = 0))
            database.dao.insert(AlbumArtistMap(albumId = ALBUM_ID, artistId = ARTIST_ID, order = 0))
        }
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `reading a song leaves the cached artist page on disk`() {
        val artist = database.dao.getSongByIdBlocking(SONG_ID)!!.artists.single()

        assertNull(artist.cachedPageJson)
    }

    @Test
    fun `reading a song keeps every other artist column`() {
        val artist = database.dao.getSongByIdBlocking(SONG_ID)!!.artists.single()

        assertEquals(ARTIST_ID, artist.id)
        assertEquals("Artist", artist.name)
        assertEquals("https://example.invalid/artist.jpg", artist.thumbnailUrl)
        assertEquals("UCchannel", artist.channelId)
        assertEquals(UPDATED_AT, artist.lastUpdateTime)
        assertEquals(BOOKMARKED_AT, artist.bookmarkedAt)
        assertEquals(false, artist.isLocal)
        assertEquals(true, artist.isPodcastChannel)
    }

    @Test
    fun `reading an album leaves the cached artist page on disk`() = runBlocking {
        val artist = database.dao.albumWithSongs(ALBUM_ID).first()!!.artists.single()

        assertNull(artist.cachedPageJson)
    }

    @Test
    fun `the artist screen still reads the cached page`() = runBlocking {
        val artist = database.dao.artist(ARTIST_ID).first()!!.artist

        assertEquals(CACHED_PAGE, artist.cachedPageJson)
    }

    @Test
    fun `storing a thumbnail found while playing keeps the cached page`() {
        database.dao.updateArtistThumbnail(ARTIST_ID, "https://example.invalid/fetched.jpg")

        val artist = database.dao.getArtistById(ARTIST_ID)
        assertNotNull(artist)
        assertEquals("https://example.invalid/fetched.jpg", artist!!.thumbnailUrl)
        assertEquals(CACHED_PAGE, artist.cachedPageJson)
    }

    private companion object {
        const val ARTIST_ID = "UCartist"
        const val ALBUM_ID = "album"
        const val SONG_ID = "song"
        const val CACHED_PAGE = """{"sections":[{"title":"Songs"}]}"""
        val UPDATED_AT: LocalDateTime = LocalDateTime.of(2026, 1, 1, 0, 0)
        val BOOKMARKED_AT: LocalDateTime = LocalDateTime.of(2026, 2, 2, 0, 0)
    }
}
