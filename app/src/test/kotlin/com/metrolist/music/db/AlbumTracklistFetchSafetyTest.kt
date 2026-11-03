/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */
package com.metrolist.music.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.metrolist.innertube.InnerTube
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.Album
import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.pages.AlbumPage
import com.metrolist.music.db.entities.AlbumEntity
import com.metrolist.music.db.entities.SongAlbumMap
import com.metrolist.music.db.entities.SongEntity
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AlbumTracklistFetchSafetyTest {
    private lateinit var database: InternalDatabase
    private val dao get() = database.dao

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            InternalDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao.insert(AlbumEntity(id = "album", title = "Album", songCount = 3, duration = 300))
        dao.insert(AlbumEntity(id = "other", title = "Other", songCount = 1, duration = 100))
        listOf("a", "b", "c").forEachIndexed { index, id ->
            dao.insert(SongEntity(id = id, title = id, duration = 100))
            dao.insert(SongAlbumMap(songId = id, albumId = "album", index = index))
        }
        dao.insert(SongAlbumMap(songId = "b", albumId = "other", index = 0))
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun `empty refresh preserves mappings count and duration`() = runBlocking {
        val before = snapshot()
        dao.update(checkNotNull(dao.albumEntity("album")), page())
        assertEquals(before, snapshot())
        assertNotNull(dao.getSongByIdBlocking("b"))
    }

    @Test
    fun `shorter nonempty snapshot cannot renumber or shrink the cached tracklist`() = runBlocking {
        val before = snapshot()
        dao.update(checkNotNull(dao.albumEntity("album")), page("c", "a"))
        assertEquals(before, snapshot())
    }

    @Test
    fun `late smaller response is compared with current state not its captured album`() = runBlocking {
        val captured = checkNotNull(dao.albumEntity("album"))
        dao.update(captured, page("a", "b", "c", "d"))
        val newer = snapshot()
        assertEquals(4, newer.count)
        assertEquals(400, newer.duration)
        // Deterministic scheduling of an older response after the larger update commits.
        dao.update(captured, page("c", "b", "a"))
        assertEquals(newer, snapshot())
    }

    @Test
    fun `persisted track count also protects a cache with fewer mapped rows`() = runBlocking {
        val captured = checkNotNull(dao.albumEntity("album"))
        dao.update(captured.copy(songCount = 5, duration = 500))
        val before = snapshot()
        dao.update(captured, page("a", "b", "c", "d"))
        assertEquals(before, snapshot())
    }

    @Test
    fun `nonempty same size reorder is still applied without deleting other album mappings`() = runBlocking {
        dao.update(checkNotNull(dao.albumEntity("album")), page("c", "a", "b"))
        val after = snapshot()
        assertEquals(listOf("c", "a", "b"), after.ids)
        assertEquals(3, after.count)
        assertEquals(300, after.duration)
        assertTrue(after.mappings.contains(Triple("other", "b", 0)))
        assertNotNull(dao.getSongByIdBlocking("b"))
    }

    @Test
    fun `malformed fetched album fails before the cache update callback`() = runBlocking {
        val before = snapshot()
        var requests = 0
        val engine = MockEngine {
            val response = if (requests++ == 0) albumHeader else malformedTracks
            check(requests <= 2) { "Unexpected extra request" }
            respond(response, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
        }.use { client ->
            val innerField = YouTube::class.java.getDeclaredField("innerTube").apply { isAccessible = true }
            val inner = innerField.get(YouTube) as InnerTube
            val clientField = InnerTube::class.java.getDeclaredField("httpClient").apply { isAccessible = true }
            val transportField = InnerTube::class.java.getDeclaredField("innerTubeX").apply { isAccessible = true }
            val originalClient = clientField.get(inner)
            val originalTransport = transportField.get(inner)
            try {
                clientField.set(inner, client)
                transportField.set(inner, transportField.get(InnerTube(client)))
                var updated = false
                val captured = checkNotNull(dao.albumEntity("album"))
                val result = YouTube.album("album").onSuccess {
                    updated = true
                    dao.update(captured, it)
                }
                assertTrue(result.isFailure)
                assertTrue(result.exceptionOrNull()?.message?.contains("unparseable track") == true)
                assertEquals(2, requests)
                assertFalse(updated)
                assertEquals(before, snapshot())
            } finally {
                clientField.set(inner, originalClient)
                transportField.set(inner, originalTransport)
            }
        }
    }

    private data class Snapshot(
        val ids: List<String>,
        val mappings: List<Triple<String, String, Int>>,
        val count: Int,
        val duration: Int,
    )

    private suspend fun snapshot(): Snapshot {
        val album = checkNotNull(dao.albumEntity("album"))
        val mappings = database.openHelper.readableDatabase.query(
            "SELECT albumId, songId, `index` FROM song_album_map ORDER BY albumId, `index`, songId",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(Triple(cursor.getString(0), cursor.getString(1), cursor.getInt(2)))
            }
        }
        return Snapshot(dao.albumSongs("album", 100, 0).map { it.id }, mappings, album.songCount, album.duration)
    }

    private fun page(vararg ids: String) = AlbumPage(
        album = AlbumItem(browseId = "album", playlistId = "playlist", title = "Album", artists = null, thumbnail = "cover"),
        songs = ids.map { SongItem(id = it, title = it, artists = emptyList(), album = Album("Album", "album"), duration = 100, thumbnail = "cover") },
        otherVersions = emptyList(),
    )

    private val albumHeader = """{
        "responseContext":{},
        "microformat":{"microformatDataRenderer":{"urlCanonical":"https://music.youtube.com/playlist?list=playlist"}},
        "contents":{"twoColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"content":{
            "sectionListRenderer":{"contents":[{"musicResponsiveHeaderRenderer":{
                "title":{"runs":[{"text":"Album"}]},
                "thumbnail":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"cover","width":120,"height":120}]}}}
            }}]}
        }}}]}}
    }"""

    private val malformedTracks = """{
        "responseContext":{},"contents":{"twoColumnBrowseResultsRenderer":{"secondaryContents":{
            "sectionListRenderer":{"contents":[{"musicPlaylistShelfRenderer":{"contents":[{
                "musicResponsiveListItemRenderer":{
                    "playlistItemData":{"videoId":"broken"},"fixedColumns":[],
                    "flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{
                        "text":"Broken","navigationEndpoint":{"watchEndpoint":{"videoId":"broken",
                            "watchEndpointMusicSupportedConfigs":{"watchEndpointMusicConfig":{"musicVideoType":"MUSIC_VIDEO_TYPE_ATV"}}
                        }}
                    }]}}}]
                }
            }]}}]}
        }}}
    }"""
}
