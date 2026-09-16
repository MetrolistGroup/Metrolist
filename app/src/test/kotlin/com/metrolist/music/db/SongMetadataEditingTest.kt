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
import com.metrolist.innertube.models.Artist
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.pages.AlbumPage
import com.metrolist.music.db.entities.AlbumEntity
import com.metrolist.music.db.entities.ArtistEntity
import com.metrolist.music.db.entities.SongArtistMap
import com.metrolist.music.db.entities.SongEntity
import com.metrolist.music.models.MediaMetadata
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SongMetadataEditingTest {
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
    fun `album refresh preserves edited song metadata`() =
        runBlocking {
            database.dao.insert(ArtistEntity(id = "edited-artist", name = "Edited artist"))
            database.dao.insert(SongEntity(id = "song", title = "Edited title", duration = 100))
            database.dao.insert(SongArtistMap(songId = "song", artistId = "edited-artist", position = 0))
            val album = AlbumEntity(id = "album", title = "Album", songCount = 1, duration = 100)
            database.dao.insert(album)

            database.dao.update(
                album = album,
                albumPage =
                    AlbumPage(
                        album =
                            AlbumItem(
                                browseId = "album",
                                playlistId = "playlist",
                                title = "Album",
                                artists = listOf(Artist(name = "Remote artist", id = "remote-artist")),
                                thumbnail = "thumbnail",
                            ),
                        songs =
                            listOf(
                                SongItem(
                                    id = "song",
                                    title = "Remote title",
                                    artists = listOf(Artist(name = "Remote artist", id = "remote-artist")),
                                    album = Album(name = "Album", id = "album"),
                                    duration = 200,
                                    thumbnail = "thumbnail",
                                ),
                            ),
                        otherVersions = emptyList(),
                    ),
            )

            val refreshedSong = database.dao.song("song").first()!!
            assertEquals("Edited title", refreshedSong.song.title)
            assertEquals(200, refreshedSong.song.duration)
            assertEquals(listOf("edited-artist"), refreshedSong.orderedArtists.map { it.id })
        }

    @Test
    fun `download refresh preserves edited title and artists`() =
        runBlocking {
            val songEntity = SongEntity(id = "song", title = "Edited title", duration = 100)
            database.dao.insert(ArtistEntity(id = "edited-artist", name = "Edited artist"))
            database.dao.insert(songEntity)
            database.dao.insert(SongArtistMap(songId = songEntity.id, artistId = "edited-artist", position = 0))
            val existing = database.dao.song(songEntity.id).first()!!

            database.dao.update(
                existing,
                MediaMetadata(
                    id = existing.id,
                    title = "Remote title",
                    artists = listOf(MediaMetadata.Artist(id = "remote-artist", name = "Remote artist")),
                    duration = 200,
                ),
                overwriteTitle = false,
                overwriteArtists = false,
            )

            val refreshedSong = database.dao.song(existing.id).first()!!
            assertEquals("Edited title", refreshedSong.song.title)
            assertEquals(200, refreshedSong.song.duration)
            assertEquals(listOf("edited-artist"), refreshedSong.orderedArtists.map { it.id })
        }

    @Test
    fun `replacing song artists does not rename other songs`() =
        runBlocking {
            val originalArtist = ArtistEntity(id = "original-artist", name = "Original artist")
            val editedArtist = ArtistEntity(id = "edited-artist", name = "Edited artist", isLocal = true)
            database.dao.insert(originalArtist)
            database.dao.insert(SongEntity(id = "edited-song", title = "Edited song"))
            database.dao.insert(SongEntity(id = "other-song", title = "Other song"))
            database.dao.insert(SongArtistMap(songId = "edited-song", artistId = originalArtist.id, position = 0))
            database.dao.insert(SongArtistMap(songId = "other-song", artistId = originalArtist.id, position = 0))

            database.dao.replaceSongArtists("edited-song", listOf(editedArtist))

            assertEquals(
                listOf(editedArtist.id),
                database.dao.song("edited-song").first()!!.orderedArtists.map { it.id },
            )
            assertEquals(
                listOf(originalArtist.id),
                database.dao.song("other-song").first()!!.orderedArtists.map { it.id },
            )
            assertEquals("Original artist", database.dao.getArtistById(originalArtist.id)?.name)
        }
}
