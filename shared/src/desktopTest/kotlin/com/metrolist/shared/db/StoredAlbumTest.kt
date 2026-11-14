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
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.youtube.playback.PlaybackNetworkState
import com.metrolist.shared.youtube.playback.PlaybackNetworkStateStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class StoredAlbumTest {
    @Test
    fun unlinkedAlbumArtistsSurviveSparseCompleteRefreshAndDatabaseReopen() =
        runBlocking {
            val path = Files.createTempDirectory("album-artists").resolve("music.db")
            var database = getDesktopDatabaseBuilder(path.toFile()).build()
            try {
                val album =
                    AlbumItem("album", "Album", null, listOf(ArtistTiny(null, "Unlinked artist"), ArtistTiny("linked", "Linked artist")))
                val songs = listOf(SongItem("song", "Song", null, emptyList(), AlbumTiny("album", "Album")))
                database.storeAlbumPage(AlbumPage(album, songs))
                database.storeAlbumPage(AlbumPage(album.copy(artists = emptyList()), songs))
                database.close()
                database = getDesktopDatabaseBuilder(path.toFile()).build()
                assertEquals(album.artists, assertNotNull(database.loadStoredAlbum("album")).album.artists)
            } finally {
                database.close()
                path.parent.toFile().deleteRecursively()
            }
        }

    @Test
    fun offlineRepositoryReturnsOrderedAlbumWithoutChangingMembershipOrEditIds() =
        runBlocking {
            val path = Files.createTempDirectory("offline-album").resolve("music.db")
            val database = getDesktopDatabaseBuilder(path.toFile()).build()
            var requests = 0
            val client =
                HttpClient(
                    MockEngine {
                        requests++
                        error("offline")
                    },
                )
            val previousNetwork = PlaybackNetworkStateStore.current
            try {
                val dao = database.dao()
                val songs =
                    listOf("second", "first").map {
                        SongItem(it, it, null, emptyList(), AlbumTiny("album", "Album"))
                    }
                database.storeAlbumPage(
                    AlbumPage(
                        AlbumItem(
                            "album",
                            "Album",
                            "artwork",
                            listOf(ArtistTiny("artist", "Artist")),
                            year = "2026",
                            playlistId = "remote",
                        ),
                        songs,
                    ),
                )
                songs.forEach { dao.updateCachedInfo(it.id, true) }
                dao.upsert(PlaylistEntity(id = "saved", name = "Saved", browseId = "remote", isEditable = true))
                songs.forEachIndexed {
                    index,
                    song,
                    ->
                    dao.insert(PlaylistSongMap(songId = song.id, playlistId = "saved", position = index, setVideoId = "edit-$index"))
                }
                val before = dao.playlistSongs("saved").first()
                val albumBefore = dao.album("album").first()
                val partial =
                    PlaylistPage(
                        sections = mapOf("songs" to songs.take(1)),
                        pending = listOf(ContinuationBranch("next")),
                        seen = emptySet(),
                        session = InnerTube(client).sessionSnapshot(),
                    )
                database.storeAlbumPage(
                    AlbumPage(AlbumItem("album", "Album", null, emptyList()), partial.songs, playlistPage = partial),
                )
                assertEquals(albumBefore, dao.album("album").first())
                val repository = YouTubeRepository(InnerTube(client), databaseProvider = { database })
                val page = assertNotNull(repository.getAlbum("album", loadAllPages = false))
                assertEquals(listOf("second", "first"), page.songs.map { it.id })
                assertNull(page.playlistPage)
                assertEquals("artwork", page.album.thumbnail)
                assertEquals("2026", page.album.year)
                assertEquals("remote", page.album.playlistId)
                assertEquals(listOf(ArtistTiny("artist", "Artist")), page.album.artists)
                val attempted = requests
                PlaybackNetworkStateStore.publish(PlaybackNetworkState("offline", connected = false))
                assertEquals(page.songs, repository.getAlbum("album")?.songs)
                assertEquals(attempted, requests)
                assertEquals(before, dao.playlistSongs("saved").first())
                assertEquals(albumBefore, dao.album("album").first())
                assertNull(repository.getAlbum("unknown"))
            } finally {
                PlaybackNetworkStateStore.publish(previousNetwork)
                client.close()
                database.close()
                path.parent.toFile().deleteRecursively()
            }
        }

    @Test
    fun findsDownloadedMetadataWithoutAlbumMappingOrHeader() =
        runBlocking {
            val path = Files.createTempDirectory("offline-album-metadata").resolve("music.db")
            val database = getDesktopDatabaseBuilder(path.toFile()).build()
            try {
                database.dao().upsertHistorySong(SongItem("song", "Song", null, emptyList(), AlbumTiny("album", "Album")))
                val page = assertNotNull(database.loadStoredAlbum("album"))
                assertEquals("Album", page.album.title)
                assertEquals(listOf("song"), page.songs.map { it.id })
                assertNull(database.dao().album("album").first())
            } finally {
                database.close()
                path.parent.toFile().deleteRecursively()
            }
        }
}
