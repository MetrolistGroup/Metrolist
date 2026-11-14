package com.metrolist.shared.db

import com.metrolist.innertubex.InnerTube
import com.metrolist.shared.api.ContinuationBranch
import com.metrolist.shared.api.PlaylistPage
import com.metrolist.shared.api.YouTubeRepository
import com.metrolist.shared.db.entities.PlaylistEntity
import com.metrolist.shared.db.entities.PlaylistSongMap
import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.model.AlbumPage
import com.metrolist.shared.model.AlbumTiny
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.SearchFilter
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.youtube.playback.PlaybackNetworkState
import com.metrolist.shared.youtube.playback.PlaybackNetworkStateStore
import com.russhwolf.settings.MapSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OfflineAlbumTest {
    private lateinit var previousNetworkState: PlaybackNetworkState

    @BeforeTest
    fun setUp() {
        Settings.replaceSharedForTest(Settings(MapSettings()))
        previousNetworkState = PlaybackNetworkStateStore.current
        PlaybackNetworkStateStore.publish(previousNetworkState.copy(connected = true))
    }

    @AfterTest
    fun tearDown() {
        Settings.replaceSharedForTest(null)
        PlaybackNetworkStateStore.publish(previousNetworkState)
    }

    @Test
    fun downloadedAndCachedTracksRemainAvailableInAlbumOrderAfterRestart() =
        runBlocking {
            val path = Files.createTempFile("metrolist-offline-album", ".db")
            Files.deleteIfExists(path)
            var database = getDesktopDatabaseBuilder(path.toFile()).build()
            val client = createClient(MockEngine { error("Offline") })
            try {
                database.storeAlbumPage(page)
                database.dao().updateCachedInfo("track-2", true)
                database.dao().updateDownloadedInfo("track-1", true, null)
                database.close()
                database = getDesktopDatabaseBuilder(path.toFile()).build()
                val repository = YouTubeRepository(InnerTube(client), databaseProvider = { database })
                val loaded = assertNotNull(repository.getAlbum("album"))
                assertEquals(listOf("track-1", "track-2"), loaded.songs.map { it.id })
                assertEquals("Album", loaded.album.title)
                assertEquals("playlist", loaded.album.playlistId)
                assertNull(loaded.playlistPage)
                database.dao().updateCachedInfo("track-2", false)
                assertEquals(listOf("track-1"), assertNotNull(repository.getAlbum("album")).songs.map { it.id })
                database.dao().updateDownloadedInfo("track-1", false, null)
                assertNull(repository.getAlbum("album"))
            } finally {
                client.close()
                database.close()
                Files.deleteIfExists(path)
            }
        }

    @Test
    fun fullyAvailableAlbumDoesNotRequestNetworkAndKeepsUserMetadata() =
        runBlocking {
            val path = Files.createTempFile("metrolist-local-album", ".db")
            Files.deleteIfExists(path)
            val database = getDesktopDatabaseBuilder(path.toFile()).build()
            var requests = 0
            val client =
                HttpClient(
                    MockEngine {
                        requests++
                        error("Unexpected network request")
                    },
                )
            try {
                database.storeAlbumPage(page)
                val stored = assertNotNull(database.dao().getSongById("track-1")).song
                database.dao().upsert(stored.copy(customTitle = "Custom", isDownloaded = true))
                database.dao().updateCachedInfo("track-2", true)
                database.storeAlbumPage(page)
                val repository = YouTubeRepository(InnerTube(client), databaseProvider = { database })
                PlaybackNetworkStateStore.publish(PlaybackNetworkState(scope = "offline", connected = false))
                val loaded = assertNotNull(repository.getAlbum("album", loadAllPages = false))
                assertEquals(0, requests)
                assertEquals("Custom", loaded.songs.first().title)
                assertEquals(listOf("track-1", "track-2"), loaded.songs.map { it.id })
                database.storeAlbumPage(page.copy(songs = page.songs.reversed()))
                assertEquals(
                    listOf("track-2", "track-1"),
                    assertNotNull(database.loadOfflineAlbum("album")).songs.map { it.id },
                )
            } finally {
                client.close()
                database.close()
                Files.deleteIfExists(path)
            }
        }

    @Test
    fun localAvailabilityProviderSupportsDesktopArtistAndPlaylistPages() =
        runBlocking {
            val path = Files.createTempFile("metrolist-offline-media", ".db")
            Files.deleteIfExists(path)
            val database = getDesktopDatabaseBuilder(path.toFile()).build()
            val client = createClient(MockEngine { error("Offline") })
            try {
                database.storeAlbumPage(page)
                database
                    .dao()
                    .upsert(PlaylistEntity(id = "local-playlist", name = "Playlist", browseId = "remote-playlist"))
                database.dao().insert(PlaylistSongMap(playlistId = "local-playlist", songId = "track-2", position = 0))
                database.dao().insert(PlaylistSongMap(playlistId = "local-playlist", songId = "track-1", position = 1))
                val repository =
                    YouTubeRepository(
                        InnerTube(client),
                        databaseProvider = { database },
                        offlineSongAvailabilityProvider = { OfflineSongAvailability { setOf("track-2") } },
                    )
                assertEquals(listOf("track-2"), assertNotNull(repository.getAlbum("album")).songs.map { it.id })
                assertEquals(listOf("track-2"), repository.getArtistSongs("artist").map { it.id })
                val playlist = repository.getPlaylistPageData("remote-playlist")
                assertEquals(listOf("track-2"), playlist.songs.map { it.id })
                assertFalse(playlist.hasMore)
                val albumPlayback = assertNotNull(repository.getAlbumPlaybackPage("album"))
                assertEquals(listOf("track-2"), albumPlayback.songs.map { it.id })
                assertFalse(albumPlayback.hasMore)
                assertNull(database.loadOfflineAlbum("album"))
                val filteredSearch = repository.searchFiltered("track", SearchFilter.SONG)
                assertEquals(listOf("track-2"), filteredSearch.items.map { it.id })
                assertTrue(filteredSearch.onlineSearchFailed)
                assertEquals(
                    listOf("album"),
                    repository.searchFiltered("Album", SearchFilter.ALBUM).items.map { it.id },
                )
                assertEquals(
                    listOf("artist"),
                    repository.searchFiltered("Artist", SearchFilter.ARTIST).items.map { it.id },
                )
                assertEquals(
                    listOf("local-playlist"),
                    repository.searchFiltered("Playlist", SearchFilter.COMMUNITY_PLAYLIST).items.map { it.id },
                )
                assertEquals(
                    assertNotNull(database.loadOfflineAlbum("album", setOf("track-2"))).album,
                    repository.searchFiltered("Album", SearchFilter.ALBUM).items.single(),
                )
                assertEquals(
                    assertNotNull(database.loadOfflinePlaylist("local-playlist", setOf("track-2")))["header"],
                    repository.searchFiltered("Playlist", SearchFilter.COMMUNITY_PLAYLIST).items,
                )
                database.dao().updateCachedInfo("track-1", true)
                database.dao().updateDownloadedInfo("track-1", true, null)
                assertEquals(emptyList(), database.searchOfflineMedia("Album", emptySet()))
                assertEquals(emptyList(), database.searchOfflineMedia("Playlist", emptySet()))
                assertEquals(listOf("track-2"), repository.searchFiltered("Album", SearchFilter.SONG).items.map { it.id })
                assertEquals(listOf("track-2"), repository.getSongsMetadata(listOf("track-1", "track-2")).map { it.id })
                val searchSummary = repository.getSearchSummary("track")
                assertEquals(listOf("track-2"), searchSummary.summaries.flatMap { it.items }.map { it.id })
                assertTrue(searchSummary.onlineSearchFailed)
            } finally {
                client.close()
                database.close()
                Files.deleteIfExists(path)
            }
        }

    @Test
    fun connectedEmptyRemoteResultsDoNotFallBackToOffline(): Unit =
        runBlocking {
            val path = Files.createTempFile("metrolist-online-empty", ".db")
            Files.deleteIfExists(path)
            val database = getDesktopDatabaseBuilder(path.toFile()).build()
            val client =
                HttpClient(
                    MockEngine {
                        respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                    },
                ) {
                    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                }
            try {
                database.storeAlbumPage(page)
                page.songs.forEach { database.dao().updateCachedInfo(it.id, true) }
                val repository = YouTubeRepository(InnerTube(client), databaseProvider = { database })
                assertEquals(emptyList(), repository.search("track"))
                assertEquals(emptyList(), repository.searchFiltered("track", SearchFilter.SONG).items)
                assertEquals(emptyList(), repository.getSearchSummary("track").summaries)
            } finally {
                client.close()
                database.close()
                Files.deleteIfExists(path)
            }
        }

    @Test
    fun songsCachedBeforeAlbumPersistenceCanStillLoadTheirAlbumAndArtist(): Unit =
        runBlocking {
            val path = Files.createTempFile("metrolist-legacy-offline", ".db")
            Files.deleteIfExists(path)
            val database = getDesktopDatabaseBuilder(path.toFile()).build()
            try {
                database.upsertOfflineTestSong(page.songs.first())
                val loaded = assertNotNull(database.loadOfflineAlbum("album"))
                assertEquals("Album", loaded.album.title)
                assertEquals(listOf("track-1"), loaded.songs.map { it.id })
                assertNull(loaded.album.songCount)
                assertEquals(
                    "Artist",
                    loaded.songs
                        .first()
                        .artists
                        .first()
                        .name,
                )
                assertNotNull(database.loadOfflineArtist("artist"))
                assertEquals(loaded.album, database.searchOfflineMedia("Album").filterIsInstance<AlbumItem>().single())
                database.dao().upsertHistorySong(
                    page.songs.last().copy(
                        thumbnail = "available-cover",
                        artists = listOf(ArtistTiny("available-artist", "Available artist")),
                        isExplicit = true,
                    ),
                )
                val header = database.searchOfflineMedia("Album", setOf("track-2")).filterIsInstance<AlbumItem>().single()
                assertEquals("Album", header.title)
                assertEquals("available-cover", header.thumbnail)
                assertEquals(listOf(ArtistTiny("available-artist", "Available artist")), header.artists)
                assertTrue(header.isExplicit)
                assertNull(header.songCount)
                assertNull(header.duration)
                assertEquals(assertNotNull(database.loadOfflineAlbum("album", setOf("track-2"))).album, header)
                assertEquals(emptyList(), database.searchOfflineMedia("Album", emptySet()))
            } finally {
                database.close()
                Files.deleteIfExists(path)
            }
        }

    @Test
    fun cancellationDoesNotPublishPartialOfflineResults(): Unit =
        runBlocking {
            val path = Files.createTempFile("metrolist-offline-cancel", ".db")
            Files.deleteIfExists(path)
            val database = getDesktopDatabaseBuilder(path.toFile()).build()
            val client = createClient(MockEngine { throw CancellationException("Cancelled") })
            try {
                database.storeAlbumPage(page)
                database.dao().updateCachedInfo("track-1", true)
                val repository = YouTubeRepository(InnerTube(client), databaseProvider = { database })
                assertFailsWith<CancellationException> { repository.getAlbum("album") }
            } finally {
                client.close()
                database.close()
                Files.deleteIfExists(path)
            }
        }

    @Test
    fun partiallyMappedAlbumKeepsAvailableTracksFromUnloadedPages() =
        runBlocking {
            val path = Files.createTempFile("metrolist-unmapped-album", ".db")
            Files.deleteIfExists(path)
            val database = getDesktopDatabaseBuilder(path.toFile()).build()
            try {
                database.dao().upsertHistorySong(page.songs.last())
                database.dao().updateDownloadedInfo(page.songs.last().id, true, null)
                database.storeAlbumPage(page.copy(songs = page.songs.take(1)))
                database.dao().updateCachedInfo(page.songs.first().id, true)
                assertEquals(
                    listOf("track-1", "track-2"),
                    assertNotNull(database.loadOfflineAlbum("album")).songs.map { it.id },
                )
                database.dao().updateDownloadedInfo(page.songs.last().id, false, null)
                assertEquals(listOf("track-1"), assertNotNull(database.loadOfflineAlbum("album")).songs.map { it.id })
            } finally {
                database.close()
                Files.deleteIfExists(path)
            }
        }

    @Test
    fun partialAlbumRefreshRetainsTheSavedOrderUntilTheLastPageArrives() =
        runBlocking {
            val path = Files.createTempFile("metrolist-offline-pagination", ".db")
            Files.deleteIfExists(path)
            val database = getDesktopDatabaseBuilder(path.toFile()).build()
            val client = createClient(MockEngine { error("Offline") })
            try {
                database.storeAlbumPage(page)
                page.songs.forEach { database.dao().updateCachedInfo(it.id, true) }
                val partial =
                    PlaylistPage(
                        sections = mapOf("tracks" to page.songs.take(1)),
                        pending = listOf(ContinuationBranch("next")),
                        seen = emptySet(),
                        session = InnerTube(client).sessionSnapshot(),
                        album = page.album,
                    )
                database.storeAlbumPage(page.copy(songs = partial.songs, playlistPage = partial))
                assertEquals(
                    listOf("track-1", "track-2"),
                    assertNotNull(database.loadOfflineAlbum("album")).songs.map { it.id },
                )
                val staleFirst = page.songs.first().copy(title = "Must not overwrite the first page")
                database.storeAlbumPage(
                    page.copy(songs = listOf(staleFirst, page.songs.last()), playlistPage = partial),
                    incrementalSongs = listOf(page.songs.last()),
                )
                assertEquals(page.songs.first().title, database.dao().getSongById(staleFirst.id)?.title)
                assertEquals(
                    listOf("track-1", "track-2"),
                    assertNotNull(database.loadOfflineAlbum("album")).songs.map { it.id },
                )
                database.storeAlbumPage(page.copy(songs = page.songs.take(1), album = page.album.copy(songCount = 1)))
                assertEquals(listOf("track-1", "track-2"), assertNotNull(database.loadOfflineAlbum("album")).songs.map { it.id })
            } finally {
                client.close()
                database.close()
                Files.deleteIfExists(path)
            }
        }

    @Test
    fun blockedTracksStayHiddenInOfflinePagesAndSearch() =
        runBlocking {
            val path = Files.createTempFile("metrolist-offline-blocked", ".db")
            Files.deleteIfExists(path)
            val database = getDesktopDatabaseBuilder(path.toFile()).build()
            val client = createClient(MockEngine { error("Offline") })
            try {
                database.storeAlbumPage(page)
                page.songs.forEach { database.dao().updateCachedInfo(it.id, true) }
                Settings.shared.blockedSongIds = "track-1"
                val repository = YouTubeRepository(InnerTube(client), databaseProvider = { database })
                PlaybackNetworkStateStore.publish(PlaybackNetworkState(scope = "offline", connected = false))
                assertEquals(listOf("track-2"), assertNotNull(repository.getAlbum("album")).songs.map { it.id })
                assertEquals(listOf("track-2"), repository.getArtistSongs("artist").map { it.id })
                assertEquals(
                    listOf("track-2"),
                    repository.searchFiltered("track", SearchFilter.SONG).items.map { it.id },
                )
            } finally {
                client.close()
                database.close()
                Files.deleteIfExists(path)
            }
        }

    @Test
    fun importedFilesRemainAvailableWithAnEmptyDesktopCacheIndex() =
        runBlocking {
            val path = Files.createTempFile("metrolist-offline-local", ".db")
            Files.deleteIfExists(path)
            val database = getDesktopDatabaseBuilder(path.toFile()).build()
            val client = createClient(MockEngine { error("Unexpected network request") })
            try {
                database.storeAlbumPage(page)
                val song = assertNotNull(database.dao().getSongById("track-1")).song
                database.dao().upsert(song.copy(isLocal = true))
                database.dao().upsert(PlaylistEntity(id = "PLsaved", name = "Saved"))
                database.dao().insert(PlaylistSongMap(playlistId = "PLsaved", songId = song.id, position = 0))
                PlaybackNetworkStateStore.publish(previousNetworkState.copy(connected = false))
                val repository =
                    YouTubeRepository(
                        InnerTube(client),
                        databaseProvider = { database },
                        offlineSongAvailabilityProvider = { OfflineSongAvailability { emptySet() } },
                    )
                assertEquals(listOf(song.id), assertNotNull(repository.getAlbum("album")).songs.map { it.id })
                assertEquals(listOf(song.id), repository.getArtistSongs("artist").map { it.id })
                assertEquals(listOf(song.id), repository.getPlaylistPageData("VLPLsaved").songs.map { it.id })
                assertEquals(listOf(song.id), repository.searchFiltered("track", SearchFilter.SONG).items.map { it.id })
                assertEquals(
                    assertNotNull(database.loadOfflineAlbum("album", emptySet())).album,
                    repository.searchFiltered("Album", SearchFilter.ALBUM).items.single(),
                )
                assertEquals(
                    assertNotNull(database.loadOfflinePlaylist("PLsaved", emptySet()))["header"],
                    repository.searchFiltered("Saved", SearchFilter.COMMUNITY_PLAYLIST).items,
                )
                assertEquals(listOf(song.id), repository.getSongsMetadata(listOf("track-1", "track-2")).map { it.id })
            } finally {
                client.close()
                database.close()
                Files.deleteIfExists(path)
            }
        }

    private suspend fun MusicDatabase.upsertOfflineTestSong(song: SongItem) {
        withDatabaseTransaction { dao().upsertHistorySong(song) }
        dao().updateCachedInfo(song.id, true)
    }

    private fun createClient(engine: MockEngine): HttpClient =
        HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }

    private val page =
        AlbumPage(
            album =
                AlbumItem(
                    "album",
                    "Album",
                    null,
                    listOf(ArtistTiny("artist", "Artist")),
                    playlistId = "playlist",
                    songCount = 2,
                ),
            songs =
                listOf("track-1", "track-2").map { id ->
                    SongItem(
                        id,
                        id,
                        null,
                        listOf(ArtistTiny("artist", "Artist")),
                        AlbumTiny("album", "Album"),
                        duration = 30,
                    )
                },
        )
}
